package com.cinematic.tvlauncher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
    private static final String KEY_LATITUDE = "weather_cached_latitude";
    private static final String KEY_LONGITUDE = "weather_cached_longitude";
    private static final String KEY_WEATHER_CODE = "weather_code";
    private static final String KEY_FEELS_LIKE = "weather_feels_like";
    private static final String KEY_IS_DAY = "weather_is_day";
    private int adviceRotationIndex;
    private static final long LOCATION_MAX_AGE_MS = 60L * 60L * 1000L;

    private final Activity activity;
    private final TextView label;
    private final SharedPreferences preferences;
    private final ConnectivityManager connectivityManager;
    private final PowerManager powerManager;
    private final ExecutorService executor =
            new ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean locationRequestInProgress;
    private LocationListener pendingLocationListener;
    private Runnable locationTimeout;
    private volatile boolean closed;
    private volatile boolean paused = true;
    private boolean activityResumed;
    private boolean screenOn = true;
    private boolean screenReceiverRegistered;
    private volatile boolean weatherRequestInProgress;
    private volatile long weatherGeneration;
    private volatile HttpURLConnection activeConnection;
    private volatile Future<?> weatherTask;
    // Defer access to constructor-initialized fields until the callback is actually run.
    private final Runnable adviceRotationRunnable = this::rotateAdvice;
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOn = false;
                stopWeatherWork();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOn = true;
                if (activityResumed && !closed) refreshIfAllowed();
            }
        }
    };

    public WeatherWidgetManager(Activity activity, TextView label) {
        this.activity = activity;
        this.label = label;
        preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        connectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        powerManager = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        if (label != null) {
            label.setCompoundDrawablesRelative(null, null, null, null);
            label.setCompoundDrawablePadding(0);
            label.setOnClickListener(v -> handleClick());
            showCachedOrPrompt();
        }
    }

    public void refreshIfAllowed() {
        if (closed || label == null) return;
        activityResumed = true;
        registerScreenStateReceiver();
        screenOn = powerManager == null || powerManager.isInteractive();
        if (!screenOn) {
            stopWeatherWork();
            return;
        }

        paused = false;
        // Cached temperature remains visible while a fresh request starts. Its day/night
        // wording is recalculated immediately so yesterday's "Día soleado" cannot survive
        // a return to the launcher after sunset.
        preferences.edit().putBoolean(KEY_IS_DAY, isDaytimeFromLocalClock()).apply();
        showCachedOrPrompt();
        scheduleAdviceRotation();

        // The cache is a display fallback only: every launcher/screen return requests
        // current conditions rather than inheriting a persisted 50-minute countdown.
        if (!WeatherRefreshPolicy.shouldRefreshOnLauncherResume() || !hasLocationPermission()) return;
        if (!hasValidatedInternet()) {
            showCachedOrUnavailable();
            return;
        }
        requestLocation();
    }

    private void registerScreenStateReceiver() {
        if (screenReceiverRegistered || closed) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            activity.registerReceiver(screenStateReceiver, filter);
            screenReceiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Screen state receiver could not be registered", e);
        }
    }

    private void unregisterScreenStateReceiver() {
        if (!screenReceiverRegistered) return;
        try {
            activity.unregisterReceiver(screenStateReceiver);
        } catch (RuntimeException e) {
            Log.d(TAG, "Screen state receiver was already unregistered", e);
        } finally {
            screenReceiverRegistered = false;
        }
    }

    private boolean isDaytimeFromLocalClock() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 6 && hour < 18;
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

    /** Stop weather work when another app covers the launcher. */
    public void onPause() {
        activityResumed = false;
        unregisterScreenStateReceiver();
        stopWeatherWork();
    }

    /** Also used when the display turns off without a full Activity pause. */
    private void stopWeatherWork() {
        paused = true;
        ++weatherGeneration;
        mainHandler.removeCallbacks(adviceRotationRunnable);
        cancelPendingLocationRequest();
        Future<?> task = weatherTask;
        weatherTask = null;
        if (task != null) {
            task.cancel(true);
            if (task instanceof Runnable && executor instanceof ThreadPoolExecutor) {
                ((ThreadPoolExecutor) executor).remove((Runnable) task);
            }
        }
        weatherRequestInProgress = false;
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
        if (!hasValidatedInternet()) {
            showCachedOrUnavailable();
            return;
        }
        // A deliberate tap refreshes immediately, just like returning to the launcher.
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
        // Keep the last known weather visible until a newer response replaces it,
        // even when the cache is older than six hours or the network is unavailable.
        if (summary != null && location != null) {
            render(summary, location);
        } else if (hasLocationPermission()) {
            showStatus("Clima · buscando ubicación");
        } else {
            showStatus("Clima · activar ubicación");
        }
    }

    private void requestLocation() {
        if (closed || paused || locationRequestInProgress || !hasLocationPermission()) return;
        if (!hasValidatedInternet()) {
            showCachedOrUnavailable();
            return;
        }
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
        if (closed || paused || location == null || weatherRequestInProgress) return;
        if (!hasValidatedInternet()) {
            showCachedOrUnavailable();
            return;
        }
        weatherRequestInProgress = true;
        final long requestGeneration = ++weatherGeneration;
        try {
            weatherTask = executor.submit(() -> {
                HttpURLConnection connection = null;
                try {
                    if (closed || paused || requestGeneration != weatherGeneration
                            || Thread.currentThread().isInterrupted() || !hasValidatedInternet()) return;
                    // Reverse geocoding may also use network; reuse the locality for nearby coordinates.
                    String city = cachedLocationName(location);
                    if (TextUtils.isEmpty(city)) city = resolveLocationName(location);
                    if (closed || paused || requestGeneration != weatherGeneration
                            || Thread.currentThread().isInterrupted() || !hasValidatedInternet()) return;
                    String query = String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                                    + "&current=temperature_2m,apparent_temperature,weather_code,is_day&temperature_unit=celsius&timezone=auto",
                            location.getLatitude(), location.getLongitude());
                    connection = (HttpURLConnection) new URL(query).openConnection();
                    activeConnection = connection;
                    if (closed || paused || requestGeneration != weatherGeneration
                            || Thread.currentThread().isInterrupted() || !hasValidatedInternet()) return;
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
                    if (closed || paused || requestGeneration != weatherGeneration
                            || Thread.currentThread().isInterrupted()) return;
                    JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
                    int temperature = (int) Math.round(current.getDouble("temperature_2m"));
                    int feelsLike = (int) Math.round(current.optDouble("apparent_temperature", temperature));
                    int weatherCode = current.optInt("weather_code", -1);
                    int isDayValue = current.optInt("is_day", -1);
                    boolean isDay = isDayValue == 1
                            || (isDayValue != 0 && isDaytimeFromLocalClock());
                    String summary = temperature + "°C";
                    String place = TextUtils.isEmpty(city) ? "Ubicación automática" : city;
                    if (closed || paused || requestGeneration != weatherGeneration
                            || Thread.currentThread().isInterrupted()) return;
                    preferences.edit().putString(KEY_SUMMARY, summary)
                            .putString(KEY_LOCATION, place)
                            .putInt(KEY_WEATHER_CODE, weatherCode)
                            .putInt(KEY_FEELS_LIKE, feelsLike)
                            .putBoolean(KEY_IS_DAY, isDay)
                            .putFloat(KEY_LATITUDE, (float) location.getLatitude())
                            .putFloat(KEY_LONGITUDE, (float) location.getLongitude())
                            .putLong(KEY_UPDATED, System.currentTimeMillis()).apply();
                    mainHandler.post(() -> {
                        if (!closed && !paused && requestGeneration == weatherGeneration) {
                            render(summary, place);
                        }
                    });
                } catch (Exception e) {
                    if (!closed && !paused && requestGeneration == weatherGeneration
                            && !Thread.currentThread().isInterrupted()) {
                        Log.w(TAG, "Unable to update weather", e);
                        mainHandler.post(() -> { if (!closed && !paused) showCachedOrUnavailable(); });
                    }
                } finally {
                    if (connection != null) connection.disconnect();
                    if (activeConnection == connection) activeConnection = null;
                    if (requestGeneration == weatherGeneration) weatherRequestInProgress = false;
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            if (requestGeneration == weatherGeneration) weatherRequestInProgress = false;
        }
    }

    /** Requires an active network that Android has validated for Internet access. */
    private boolean hasValidatedInternet() {
        ConnectivityManager manager = connectivityManager;
        if (manager == null) return false;
        try {
            Network network = manager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            Log.d(TAG, "Internet connectivity status is unavailable", e);
            return false;
        }
    }

    private String cachedLocationName(Location location) {
        if (location == null || !preferences.contains(KEY_LATITUDE)
                || !preferences.contains(KEY_LONGITUDE)) return null;
        String cached = preferences.getString(KEY_LOCATION, null);
        if (TextUtils.isEmpty(cached)) return null;
        double latitude = preferences.getFloat(KEY_LATITUDE, 1000f);
        double longitude = preferences.getFloat(KEY_LONGITUDE, 1000f);
        return Math.abs(location.getLatitude() - latitude) <= 0.02d
                && Math.abs(location.getLongitude() - longitude) <= 0.02d ? cached : null;
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

    /** Strips the legacy icon prefix from previously cached temperature strings. */
    private String temperatureText(String summary) {
        if (summary == null) return "";
        String value = summary.trim();
        int separator = value.lastIndexOf(' ');
        if (separator >= 0 && value.substring(separator + 1).contains("°C")) {
            return value.substring(separator + 1).trim();
        }
        return value;
    }

    private void render(String summary, String location) {
        if (label == null) return;
        String temperature = temperatureText(summary);
        int temperatureC = parseTemperature(temperature);
        int feelsLikeC = preferences.getInt(KEY_FEELS_LIKE, temperatureC);
        int weatherCode = preferences.getInt(KEY_WEATHER_CODE, -1);
        boolean isDay = preferences.getBoolean(KEY_IS_DAY, isDaytimeFromLocalClock());
        String[] advice = WeatherAdvicePolicy.phrasesFor(
                temperatureC, feelsLikeC, weatherCode, isDay);
        String phrase = advice[adviceRotationIndex % advice.length];
        label.setText(temperature + " · " + phrase + "\n" + location);
        label.setContentDescription("Clima actual: " + temperature + ". " + phrase
                + ". Ubicación: " + location + ". Pulsa para actualizar.");
        scheduleAdviceRotation();
    }

    private int parseTemperature(String value) {
        if (value == null) return 20;
        try { return Integer.parseInt(value.replace("°C", "").trim()); }
        catch (NumberFormatException ignored) { return 20; }
    }

    private void rotateAdvice() {
        if (closed || paused || label == null) return;
        adviceRotationIndex++;
        String summary = preferences.getString(KEY_SUMMARY, null);
        String location = preferences.getString(KEY_LOCATION, null);
        if (summary != null && location != null) render(summary, location);
        scheduleAdviceRotation();
    }

    private void scheduleAdviceRotation() {
        if (closed || paused || label == null) return;
        mainHandler.removeCallbacks(adviceRotationRunnable);
        mainHandler.postDelayed(adviceRotationRunnable, 9000L);
    }

    private void showStatus(String value) {
        if (label != null) {
            label.setText(value);
            label.setContentDescription(value);
        }
    }

    private void showUnavailable(String value) {
        if (preferences.getString(KEY_SUMMARY, null) != null
                && preferences.getString(KEY_LOCATION, null) != null) {
            showCachedOrUnavailable();
        } else {
            showStatus(value);
        }
    }

    private void showCachedOrUnavailable() {
        String summary = preferences.getString(KEY_SUMMARY, null);
        String location = preferences.getString(KEY_LOCATION, null);
        if (summary != null && location != null) {
            render(summary, location);
        } else {
            showStatus("Clima no disponible");
        }
    }
}
