package com.cinematic.tvlauncher.controls;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.view.WindowManager;
import android.content.ClipData;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.cinematic.tvlauncher.R;

import java.util.ArrayList;
import java.util.List;

/**
 * TvControlManager — Manages the three top-right translucent controls:
 * Inputs, Wi-Fi, and Settings.
 * Implements Annex C.5 specifications.
 */
public class TvControlManager {
    private static final String TAG = "TvControlManager";

    private final Activity activity;
    private final View btnInputs;
    private final View btnWifi;
    private final View btnSettings;
    private final View btnPhotos;
    private final View btnSearch;
    private final TextView tvWifiIcon;
    private final View inputOverlay;
    private final TvView inputTvView;
    private TvInputManager tvInputManager;

    private static final class InputOption {
        final TvInputInfo input;
        final String label;
        final String kind;
        InputOption(TvInputInfo input, String label, String kind) {
            this.input = input;
            this.label = label;
            this.kind = kind;
        }
    }

    public TvControlManager(Activity activity, View btnInputs, View btnWifi, View btnSettings, View btnPhotos, View btnSearch, TextView tvWifiIcon) {
        this(activity, btnInputs, btnWifi, btnSettings, btnPhotos, btnSearch, tvWifiIcon, null, null);
    }

    public TvControlManager(Activity activity, View btnInputs, View btnWifi, View btnSettings, View btnPhotos, View btnSearch, TextView tvWifiIcon, View inputOverlay, TvView inputTvView) {
        this.activity = activity;
        this.btnInputs = btnInputs;
        this.btnWifi = btnWifi;
        this.btnSettings = btnSettings;
        this.btnPhotos = btnPhotos;
        this.btnSearch = btnSearch;
        this.tvWifiIcon = tvWifiIcon;
        this.inputOverlay = inputOverlay;
        this.inputTvView = inputTvView;
        this.tvInputManager = (TvInputManager) activity.getSystemService(Context.TV_INPUT_SERVICE);

        setupListeners();
        updateWifiIndicator();
    }

    private void setupListeners() {
        btnInputs.setOnClickListener(v -> handleInputsAction());
        btnWifi.setOnClickListener(v -> handleWifiAction());
        btnSettings.setOnClickListener(v -> handleSettingsAction());
        if (btnPhotos != null) btnPhotos.setOnClickListener(v -> handlePhotosAction());
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> handleAssistantAction());
        }
    }

    public void updateWifiIndicator() {
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
                if (tvWifiIcon != null) {
                    tvWifiIcon.setAlpha(isConnected ? 1.0f : 0.4f);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error querying Wi-Fi status", e);
        }
    }

    /**
     * Opens the device's selected system assistant (normally Google Assistant).
     * Android/OEM firmware chooses the assistant; this app does not hard-code
     * a Google package name, which would fail on devices with different builds.
     */
    private void handleAssistantAction() {
        Intent assistIntent = new Intent(Intent.ACTION_ASSIST);
        assistIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(assistIntent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "System assistant is not available for ACTION_ASSIST", e);
            Toast.makeText(activity, R.string.assistant_unavailable, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Unable to open system assistant", e);
            Toast.makeText(activity, R.string.assistant_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    /** Opens the Liquid Glass panel for user-chosen local background images. */
    private void handlePhotosAction() {
        Dialog dialog = new Dialog(activity);
        View panel = activity.getLayoutInflater().inflate(R.layout.dialog_custom_background, null, false);
        dialog.setContentView(panel);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        TextView chooseButton = panel.findViewById(R.id.btn_choose_backgrounds);
        TextView cancelButton = panel.findViewById(R.id.btn_cancel_backgrounds);
        chooseButton.setOnClickListener(v -> {
            dialog.dismiss();
            openBackgroundPicker();
        });
        cancelButton.setOnClickListener(v -> dialog.dismiss());

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            float density = activity.getResources().getDisplayMetrics().density;
            int maxWidth = (int) (560 * density);
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            attributes.width = Math.min(maxWidth, (int) (screenWidth * 0.82f));
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.dimAmount = 0.22f;
            window.setAttributes(attributes);
            window.setGravity(android.view.Gravity.CENTER);
        }
        dialog.show();
        // Re-apply sizing after show(), since the dialog decor can reset its window params.
        if (window != null) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            float density = activity.getResources().getDisplayMetrics().density;
            attributes.width = Math.min((int) (560 * density),
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.82f));
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.dimAmount = 0.22f;
            window.setAttributes(attributes);
        }
        chooseButton.requestFocus();
    }

    private void openBackgroundPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            activity.startActivityForResult(picker, REQUEST_PICK_BACKGROUND_IMAGES);
        } catch (Exception e) {
            Log.w(TAG, "System image file picker unavailable", e);
            Toast.makeText(activity, "No se pudo abrir el explorador de imágenes", Toast.LENGTH_LONG).show();
        }
    }

    private static final int REQUEST_PICK_BACKGROUND_IMAGES = 4821;

    /** Returns true when the result belongs to the background photo picker. */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_BACKGROUND_IMAGES) return false;
        if (resultCode != Activity.RESULT_OK || data == null) return true;

        File directory = activity.getExternalFilesDir("CinematicBackgrounds");
        if (directory == null || (!directory.exists() && !directory.mkdirs())) {
            Toast.makeText(activity, "No se pudo preparar la carpeta de fondos", Toast.LENGTH_LONG).show();
            return true;
        }

        int imported = 0;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                if (copyImage(clip.getItemAt(i).getUri(), directory, i)) imported++;
            }
        } else if (data.getData() != null && copyImage(data.getData(), directory, 0)) {
            imported++;
        }

        if (imported > 0) {
            Toast.makeText(activity, "Fondos personalizados importados: " + imported, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(activity, "No se pudo importar ninguna foto", Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private boolean copyImage(Uri uri, File directory, int index) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String mimeType = activity.getContentResolver().getType(uri);
        String extension = "jpg";
        if ("image/png".equalsIgnoreCase(mimeType)) extension = "png";
        else if ("image/webp".equalsIgnoreCase(mimeType)) extension = "webp";
        else if ("image/jpeg".equalsIgnoreCase(mimeType) || "image/jpg".equalsIgnoreCase(mimeType)) extension = "jpg";
        File target = new File(directory, "custom_background_" + stamp + "_" + index + "." + extension);
        try (InputStream input = activity.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(target)) {
            if (input == null) return false;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return target.length() > 0;
        } catch (Exception e) {
            Log.w(TAG, "Unable to import selected background image", e);
            if (target.exists()) target.delete();
            return false;
        }
    }

    /**
     * Inputs Control (Annex C.5):
     * Query TvInputManager if available, otherwise show clear fallback dialog.
     */
    /**
     * Liquid Glass HDMI selector. TvView passthrough can render black on OEM
     * firmware without source-switch privileges, so never cover the launcher
     * with a black surface. Delegate to the system TV app when it exposes a route.
     */
    private void handleInputsAction() {
        List<InputOption> hdmiOptions = new ArrayList<>();
        List<InputOption> avOptions = new ArrayList<>();
        List<InputOption> tvOptions = new ArrayList<>();
        try {
            if (tvInputManager != null) {
                List<TvInputInfo> allInputs = tvInputManager.getTvInputList();
                if (allInputs != null) {
                    for (TvInputInfo info : allInputs) {
                        if (info == null) continue;
                        int type = info.getType();
                        if (type == TvInputInfo.TYPE_HDMI && info.isPassthroughInput()) {
                            if (hdmiOptions.size() < 4) {
                                String fallback = "HDMI " + (hdmiOptions.size() + 1);
                                hdmiOptions.add(new InputOption(info, resolveInputLabel(info, fallback, "HDMI"), "HDMI"));
                            }
                        } else if (isAvInput(info) && info.isPassthroughInput()) {
                            if (avOptions.size() < 4) {
                                String fallback = avOptions.isEmpty() ? "AV" : "AV " + (avOptions.size() + 1);
                                avOptions.add(new InputOption(info, resolveInputLabel(info, fallback, "AV"), "AV"));
                            }
                        } else if (isTvTunerInput(info) && tvOptions.isEmpty()) {
                            tvOptions.add(new InputOption(info, resolveInputLabel(info, "TV", "TV"), "TV"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to enumerate TV inputs", e);
        }

        // AV and TV remain visible even when the firmware hides their input services.
        List<InputOption> options = new ArrayList<>();
        options.addAll(hdmiOptions);
        if (avOptions.isEmpty()) options.add(new InputOption(null, "AV", "AV"));
        else options.addAll(avOptions);
        if (tvOptions.isEmpty()) options.add(new InputOption(null, "TV", "TV"));
        else options.addAll(tvOptions);

        Dialog dialog = new Dialog(activity);
        android.widget.LinearLayout panel = new android.widget.LinearLayout(activity);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(20);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackgroundResource(R.drawable.liquid_glass_dialog_bg);
        TextView title = new TextView(activity);
        title.setText("FUENTES DE ENTRADA");
        title.setTextColor(android.graphics.Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        title.setPadding(dp(4), 0, dp(4), dp(12));
        panel.addView(title);

        for (InputOption source : options) {
            TextView option = new TextView(activity);
            option.setText(source.label);
            option.setTextColor(android.graphics.Color.WHITE);
            option.setTextSize(16);
            option.setGravity(android.view.Gravity.CENTER_VERTICAL);
            option.setPadding(dp(16), 0, dp(16), 0);
            option.setMinHeight(dp(48));
            option.setFocusable(true);
            option.setClickable(true);
            option.setBackgroundResource(R.drawable.liquid_glass_action_bg);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, dp(48));
            lp.bottomMargin = dp(7);
            panel.addView(option, lp);
            option.setOnClickListener(v -> {
                dialog.dismiss();
                openSystemInput(source.input, source.label, source.kind);
            });
        }

        TextView close = new TextView(activity);
        close.setText("VOLVER");
        close.setTextColor(android.graphics.Color.WHITE);
        close.setTextSize(14);
        close.setGravity(android.view.Gravity.CENTER);
        close.setMinHeight(dp(44));
        close.setFocusable(true);
        close.setClickable(true);
        close.setBackgroundResource(R.drawable.liquid_glass_action_bg);
        panel.addView(close, new android.widget.LinearLayout.LayoutParams(-1, dp(44)));
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(panel);
        dialog.setCancelable(true);
        dialog.setOnCancelListener(d -> { if (btnInputs != null) btnInputs.requestFocus(); });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            attrs.width = Math.min(dp(440), (int)(screenWidth * 0.72f));
            attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attrs.dimAmount = 0.18f;
            window.setAttributes(attrs);
            window.setGravity(android.view.Gravity.CENTER);
        }
        if (options.isEmpty()) close.requestFocus(); else panel.getChildAt(1).requestFocus();
    }

    private boolean isAvInput(TvInputInfo info) {
        int type = info.getType();
        if (type == TvInputInfo.TYPE_COMPOSITE || type == TvInputInfo.TYPE_COMPONENT
                || type == TvInputInfo.TYPE_SVIDEO || type == TvInputInfo.TYPE_SCART) return true;
        if (type != TvInputInfo.TYPE_OTHER) return false;
        CharSequence raw = info.loadLabel(activity);
        String label = raw == null ? "" : raw.toString().toUpperCase(Locale.ROOT);
        return label.equals("AV") || label.startsWith("AV ") || label.startsWith("AV-")
                || label.contains("COMPOSITE") || label.contains("VIDEO");
    }

    private boolean isTvTunerInput(TvInputInfo info) {
        if (info.getType() == TvInputInfo.TYPE_TUNER) return true;
        if (info.getType() != TvInputInfo.TYPE_OTHER) return false;
        CharSequence raw = info.loadLabel(activity);
        String label = raw == null ? "" : raw.toString().toUpperCase(Locale.ROOT);
        return label.equals("TV") || label.startsWith("TV ") || label.startsWith("DTV")
                || label.startsWith("ATV") || label.contains("TUNER")
                || label.contains("TELEVISION") || label.contains("TELEVISIÓN");
    }

    private String resolveInputLabel(TvInputInfo info, String fallback, String kind) {
        CharSequence raw = info.loadLabel(activity);
        String label = raw == null ? "" : raw.toString().trim();
        if (label.isEmpty()) return fallback;
        String normalized = label.toUpperCase(Locale.ROOT);
        if ("HDMI".equals(kind)) return normalized.contains("HDMI") ? label : fallback;
        if ("AV".equals(kind)) {
            return normalized.contains("AV") || normalized.contains("VIDEO") || normalized.contains("COMPOSITE")
                    ? label : fallback + " · " + label;
        }
        // Present the single broadcast tuner as TV in the launcher, regardless of
        // whether the firmware calls it "Tuner", "TV Tuner", or "Television".
        return "TV".equals(kind) ? "TV" : (normalized.contains("TV") || normalized.contains("TUNER")
                ? label : fallback + " · " + label);
    }

    private int dp(float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void openSystemInput(TvInputInfo input, String label, String kind) {
        // Preserve the working HDMI route; AV passthrough uses the same Android TV input path.
        if (input != null && input.isPassthroughInput()) {
            try {
                Uri passthroughUri = TvContract.buildChannelUriForPassthroughInput(input.getId());
                Intent viewIntent = new Intent(Intent.ACTION_VIEW, passthroughUri);
                if (viewIntent.resolveActivity(activity.getPackageManager()) != null) {
                    activity.startActivity(viewIntent);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "System passthrough route for " + label + " unavailable", e);
            }
        }

        if ("TV".equals(kind)) {
            if (input != null) {
                try {
                    Uri channelsUri = TvContract.buildChannelsUriForInput(input.getId());
                    Intent viewIntent = new Intent(Intent.ACTION_VIEW, channelsUri);
                    viewIntent.putExtra(TvInputInfo.EXTRA_INPUT_ID, input.getId());
                    if (viewIntent.resolveActivity(activity.getPackageManager()) != null) {
                        activity.startActivity(viewIntent);
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "System TV channel route for " + label + " unavailable", e);
                }
            }
            if (launchSystemSourceApp("TV", input == null ? null : input.getId())) return;
        } else if ("AV".equals(kind) && input == null) {
            if (launchSystemSourceApp("AV", null)) return;
        }

        String detail = input == null
                ? "El firmware no expone " + label + " como entrada controlable por aplicaciones."
                : "El firmware no permite abrir " + label + " directamente desde una app.";
        Toast.makeText(activity, detail + " Usa INPUT en el mando para seleccionar esa fuente.", Toast.LENGTH_LONG).show();
        if (btnInputs != null) btnInputs.requestFocus();
    }

    private boolean launchSystemSourceApp(String kind, String inputId) {
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> candidates;
        try {
            candidates = activity.getPackageManager().queryIntentActivities(query, 0);
        } catch (Exception e) {
            Log.w(TAG, "Unable to inspect TV source activities", e);
            return false;
        }

        ResolveInfo best = null;
        int bestScore = 0;
        String ownPackage = activity.getPackageName().toLowerCase(Locale.ROOT);
        for (ResolveInfo candidate : candidates) {
            if (candidate == null || candidate.activityInfo == null) continue;
            String packageName = candidate.activityInfo.packageName;
            if (packageName == null || packageName.toLowerCase(Locale.ROOT).equals(ownPackage)) continue;
            String appLabel;
            try {
                CharSequence raw = candidate.loadLabel(activity.getPackageManager());
                appLabel = raw == null ? "" : raw.toString();
            } catch (Exception ignored) {
                appLabel = "";
            }
            String identity = (appLabel + " " + packageName).toLowerCase(Locale.ROOT);
            if (identity.contains("launcher") || identity.contains("settings")
                    || identity.contains("play store") || identity.contains("netflix")) continue;
            int score = 0;
            if ("TV".equals(kind)) {
                if (identity.contains("live tv") || identity.contains("live channels")
                        || identity.contains("television") || identity.contains("televisión")
                        || identity.contains("canales")) score = 100;
                else if (identity.contains("livetv") || identity.contains("livechannels")
                        || identity.contains("tvinput") || identity.contains("tuner")) score = 90;
                else if (identity.contains("hisense") && identity.contains("tv")) score = 70;
                else if (identity.matches(".*\\btv\\b.*")) score = 50;
                else if (identity.contains("google tv")) score = 35;
            } else {
                if (identity.contains("input source") || identity.contains("inputsource")
                        || identity.contains("entradas") || identity.contains("fuente")
                        || identity.contains("sourceswitch")) score = 100;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null || best.activityInfo == null) return false;
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            launch.setComponent(new ComponentName(best.activityInfo.packageName, best.activityInfo.name));
            launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (inputId != null) launch.putExtra(TvInputInfo.EXTRA_INPUT_ID, inputId);
            activity.startActivity(launch);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Unable to launch system " + kind + " source app", e);
            return false;
        }
    }

    public boolean closeInputOverlay() {
        if (inputOverlay == null || inputOverlay.getVisibility() != View.VISIBLE) return false;
        if (inputTvView != null) { try { inputTvView.reset(); } catch (Exception ignored) {} }
        inputOverlay.setVisibility(View.GONE);
        if (btnInputs != null) btnInputs.requestFocus();
        return true;
    }

    /**
     * Wi-Fi Control (Annex C.5):
     * Open verified network settings intent. Never stores Wi-Fi passwords.
     */
    private void handleWifiAction() {
        Intent[] wifiIntents = new Intent[] {
                new Intent(Settings.ACTION_WIFI_SETTINGS),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS)
        };

        for (Intent intent : wifiIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }

        Toast.makeText(activity, "Configuración de red no disponible", Toast.LENGTH_SHORT).show();
    }

    /**
     * Settings Control (Annex C.5):
     * Open verified TV settings intent with fallback.
     */
    private void handleSettingsAction() {
        Intent[] settingsIntents = new Intent[] {
                new Intent("android.settings.TV_SETTINGS"), // Leanback / Android TV settings
                new Intent(Settings.ACTION_SETTINGS)
        };

        for (Intent intent : settingsIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }

        Toast.makeText(activity, "Ajustes del sistema no disponibles", Toast.LENGTH_SHORT).show();
    }
}
