package com.cinematic.tvlauncher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

/**
 * Small weather readout. Requests foreground approximate location only, caches
 * results, and runs reverse geocoding/network parsing off the UI thread.
 */
public final class WeatherWidgetManager {
    private static final String TAG = "LauncherWeather";
    public static final int REQUEST_LOCATION_PERMISSION = 7104;
    private static final String PREFS_NAME = "cinematic_launcher_prefs";
    private static final String KEY_PERMISSION_ASKED = "weather_location_permission_asked";
    private static final String KEY_SUMMARY = "weather_summary";
    private static final String KEY_LOCATION = "weather_location";
    private static final String KEY_UPDATED = "weather_updated_at";
    private static final long REFRESH_INTERVAL_MS = 30L * 60L * 1000L;
    private static final long LOCATION_MAX_AGE_MS = 60L * 60L * 1000L;

    private final Activity activity;
    private final TextView label;
    private final SharedPreferences preferences;
    private final ExecutorService executor =
            new ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean locationRequestInProgress;
    private LocationListener pendingLocationListener;
    private Runnable locationTimeout;
    private volatile boolean closed;
    private volatile boolean paused = true;
    private volatile HttpURLConnection activeConnection;
    private volatile Future<?> weatherTask;

    public WeatherWidgetManager(Activity activity, TextView label) {
        this.activity = activity;
        this.label = label;
        preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (label != null) {
            label.setOnClickListener(v -> handleClick());
            showCachedOrPrompt();
        }
    }

    public void refreshIfAllowed() {
        if (closed || label == null) return;
        paused = false;
        showCachedOrPrompt();
        if (!hasLocationPermission()) return;
        if (System.currentTimeMillis() - preferences.getLong(KEY_UPDATED, 0L) < REFRESH_INTERVAL_MS) return;
        requestLocation();
    }

    public boolean handlesPermissionRequest(int requestCode) {
        return requestCode == REQUEST_LOCATION_PERMISSION;
    }

    /** Requests permission on first launch, before other startup dialogs can compete with it. */
    public boolean requestInitialPermissionIfNeeded() {
        if (closed || hasLocationPermission()
                || preferences.getBoolean(KEY_PERMISSION_ASKED, false)) return false;
        preferences.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                REQUEST_LOCATION_PERMISSION);
        return true;
    }

    public void onLocationPermissionResult(boolean granted) {
        if (closed) return;
        if (granted) {
            requestLocation();
        } else {
            showCachedOrPrompt();
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("Clima y ubicación")
                    .setMessage("Para mostrar la temperatura y la ciudad del televisor, Cinematic TV necesita permiso de ubicación aproximada mientras está en pantalla. No solicita ubicación en segundo plano.")
                    .setPositiveButton("Abrir permisos", (dialog, which) -> {
                        try {
                            activity.startActivity(new android.content.Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) {
                            Log.w(TAG, "Could not open app permission settings", e);
                        }
                    })
                    .setNegativeButton("Ahora no", null)
                    .show();
        }
    }

    /** Stop location and network work as soon as another app covers the launcher. */
    public void onPause() {
        paused = true;
        cancelPendingLocationRequest();
        Future<?> task = weatherTask;
        weatherTask = null;
        if (task != null) task.cancel(true);
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    public void close() {
        closed = true;
        onPause();
        executor.shutdownNow();
    }

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < 23
                || activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void handleClick() {
        if (closed) return;
        if (!hasLocationPermission()) {
            requestLocationPermission();
            return;
        }
        preferences.edit().putLong(KEY_UPDATED, 0L).apply();
        requestLocation();
    }

    private void requestLocationPermission() {
        boolean askedBefore = preferences.getBoolean(KEY_PERMISSION_ASKED, false);
        if (askedBefore && !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            try {
                activity.startActivity(new android.content.Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception e) {
                showStatus("Ubicación desactivada");
            }
            return;
        }
        preferences.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                REQUEST_LOCATION_PERMISSION);
    }

    private void showCachedOrPrompt() {
        if (label == null) return;
        String summary = preferences.getString(KEY_SUMMARY, null);
        String location = preferences.getString(KEY_LOCATION, null);
        long updated = preferences.getLong(KEY_UPDATED, 0L);
        if (summary != null && location != null
                && System.currentTimeMillis() - updated < 6L * 60L * 60L * 1000L) {
            render(summary, location);
        } else if (hasLocationPermission()) {
            showStatus("Clima · buscando ubicación");
        } else {
            showStatus("Clima · activar ubicación");
        }
    }

    private void requestLocation() {
        if (closed || paused || locationRequestInProgress || !hasLocationPermission()) return;
        LocationManager manager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            showUnavailable("Ubicación no disponible");
            return;
        }
        Location best = null;
        try {
            for (String provider : manager.getProviders(true)) {
                if (LocationManager.GPS_PROVIDER.equals(provider)
                        && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) continue;
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) best = candidate;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission was revoked while reading the last known position", e);
        } catch (Exception e) {
            Log.d(TAG, "No cached location is available", e);
        }

        if (best != null && System.currentTimeMillis() - best.getTime() <= LOCATION_MAX_AGE_MS) {
            fetchWeather(best);
            return;
        }
        requestOneShotLocation(manager, best);
    }

    @SuppressWarnings("deprecation")
    private void requestOneShotLocation(LocationManager manager, Location fallback) {
        if (closed || paused || locationRequestInProgress) return;
        locationRequestInProgress = true;
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (location == null || closed || paused) return;
                cancelPendingLocationRequest();
                fetchWeather(location);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        pendingLocationListener = listener;
        locationTimeout = () -> {
            if (!locationRequestInProgress || closed || paused) return;
            cancelPendingLocationRequest();
            if (fallback != null) fetchWeather(fallback);
            else showUnavailable("Ubicación no disponible");
        };
        mainHandler.postDelayed(locationTimeout, 10000L);

        boolean requestedAny = false;
        try {
            for (String provider : manager.getProviders(true)) {
                if (LocationManager.GPS_PROVIDER.equals(provider)
                        && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) continue;
                try {
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
                    requestedAny = true;
                } catch (SecurityException | IllegalArgumentException ignored) {
                    // Some TVs expose a provider that approximate-location access cannot use.
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission was revoked during the location request", e);
        }
        if (!requestedAny) {
            cancelPendingLocationRequest();
            if (fallback != null) fetchWeather(fallback);
            else showUnavailable("Ubicación no disponible");
        }
    }

    private void cancelPendingLocationRequest() {
        if (locationTimeout != null) {
            mainHandler.removeCallbacks(locationTimeout);
            locationTimeout = null;
        }
        LocationListener listener = pendingLocationListener;
        pendingLocationListener = null;
        if (listener != null) {
            LocationManager manager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
            if (manager != null) {
                try { manager.removeUpdates(listener); } catch (Exception ignored) {}
            }
        }
        locationRequestInProgress = false;
    }

    private void fetchWeather(Location location) {
        if (closed || paused || location == null) return;
        try {
            weatherTask = executor.submit(() -> {
                HttpURLConnection connection = null;
                try {
                    if (closed || paused || Thread.currentThread().isInterrupted()) return;
                    String city = resolveLocationName(location);
                    if (closed || paused || Thread.currentThread().isInterrupted()) return;
                    String query = String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                                    + "&current=temperature_2m,weather_code&temperature_unit=celsius&timezone=auto",
                            location.getLatitude(), location.getLongitude());
                    connection = (HttpURLConnection) new URL(query).openConnection();
                    activeConnection = connection;
                    if (closed || paused || Thread.currentThread().isInterrupted()) return;
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("User-Agent", "CinematicTVLauncher/1.0 (Android TV weather widget)");
                    int status = connection.getResponseCode();
                    if (status < 200 || status >= 300) throw new IllegalStateException("Weather HTTP status " + status);
                    StringBuilder body = new StringBuilder();
                    try (InputStream input = connection.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) body.append(line);
                    }
                    if (closed || paused || Thread.currentThread().isInterrupted()) return;
                    JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
                    int temperature = (int) Math.round(current.getDouble("temperature_2m"));
                    int code = current.getInt("weather_code");
                    String summary = weatherIcon(code) + " " + temperature + "°C";
                    String place = TextUtils.isEmpty(city) ? "Ubicación automática" : city;
                    if (closed || paused || Thread.currentThread().isInterrupted()) return;
                    preferences.edit().putString(KEY_SUMMARY, summary).putString(KEY_LOCATION, place)
                            .putLong(KEY_UPDATED, System.currentTimeMillis()).apply();
                    mainHandler.post(() -> { if (!closed && !paused) render(summary, place); });
                } catch (Exception e) {
                    if (!closed && !paused && !Thread.currentThread().isInterrupted()) {
                        Log.w(TAG, "Unable to update weather", e);
                        mainHandler.post(() -> { if (!closed && !paused) showCachedOrUnavailable(); });
                    }
                } finally {
                    if (connection != null) connection.disconnect();
                    if (activeConnection == connection) activeConnection = null;
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    @SuppressWarnings("deprecation")
    private String resolveLocationName(Location location) {
        try {
            if (!Geocoder.isPresent()) return null;
            Geocoder geocoder = new Geocoder(activity, new Locale("es", "MX"));
            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (addresses == null || addresses.isEmpty()) return null;
            Address address = addresses.get(0);
            String locality = firstNonEmpty(address.getLocality(), address.getSubAdminArea(), address.getSubLocality());
            String region = address.getAdminArea();
            if (!TextUtils.isEmpty(locality) && !TextUtils.isEmpty(region)
                    && !locality.equalsIgnoreCase(region)) return locality + ", " + region;
            return firstNonEmpty(locality, region, address.getCountryName());
        } catch (Exception e) {
            Log.d(TAG, "Reverse geocoding is unavailable", e);
            return null;
        }
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!TextUtils.isEmpty(value) && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private String weatherIcon(int code) {
        if (code == 0) return "☀";
        if (code <= 3) return "⛅";
        if (code == 45 || code == 48) return "🌫";
        if (code >= 51 && code <= 67) return "🌧";
        if (code >= 71 && code <= 77) return "❄";
        if (code >= 80 && code <= 82) return "🌦";
        if (code >= 85 && code <= 86) return "❄";
        if (code >= 95) return "⛈";
        return "☁";
    }

    private void render(String summary, String location) {
        if (label == null) return;
        label.setText(summary + "\n" + location);
        label.setContentDescription("Clima actual: " + summary + ". Ubicación: " + location + ". Pulsa para actualizar.");
    }

    private void showStatus(String value) {
        if (label != null) {
            label.setText(value);
            label.setContentDescription(value);
        }
    }

    private void showUnavailable(String value) {
        if (preferences.getString(KEY_SUMMARY, null) != null) showCachedOrUnavailable();
        else showStatus("☁ " + value);
    }

    private void showCachedOrUnavailable() {
        String summary = preferences.getString(KEY_SUMMARY, null);
        String location = preferences.getString(KEY_LOCATION, null);
        if (summary != null && location != null) render(summary, location);
        else showStatus("☁ Clima no disponible");
    }
}
