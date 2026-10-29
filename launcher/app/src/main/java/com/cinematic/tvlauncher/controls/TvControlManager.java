package com.cinematic.tvlauncher.controls;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
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
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.cinematic.tvlauncher.R;
import com.cinematic.tvlauncher.background.CinematicBackgroundManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * TvControlManager — Manages the three top-right translucent controls:
 * Inputs, Wi-Fi, and Settings.
 * Implements Annex C.5 specifications.
 */
public class TvControlManager {
    private static final String TAG = "TvControlManager";
    private static final ExecutorService BACKGROUND_IMPORT_EXECUTOR =
            new ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    private final Activity activity;
    private final View btnInputs;
    private final View btnWifi;
    private final View btnSettings;
    private final View btnPhotos;
    private final View btnSearch;
    private final TextView tvWifiIcon;
    private final View inputOverlay;
    private final TvView inputTvView;
    private final CinematicBackgroundManager backgroundManager;
    private TvInputManager tvInputManager;
    private ConnectivityManager connectivityManager;
    private final Handler networkHandler = new Handler(Looper.getMainLooper());
    private boolean networkCallbackRegistered;

    private final Runnable wifiRefresh = this::updateWifiIndicator;
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { scheduleWifiIndicatorRefresh(); }
        @Override public void onLost(Network network) { scheduleWifiIndicatorRefresh(); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            scheduleWifiIndicatorRefresh();
        }
    };

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

    public TvControlManager(Activity activity, View btnInputs, View btnWifi, View btnSettings,
            View btnPhotos, View btnSearch, TextView tvWifiIcon) {
        this(activity, btnInputs, btnWifi, btnSettings, btnPhotos, btnSearch, tvWifiIcon, null, null, null);
    }

    public TvControlManager(Activity activity, View btnInputs, View btnWifi, View btnSettings,
            View btnPhotos, View btnSearch, TextView tvWifiIcon, View inputOverlay, TvView inputTvView) {
        this(activity, btnInputs, btnWifi, btnSettings, btnPhotos, btnSearch, tvWifiIcon,
                inputOverlay, inputTvView, null);
    }

    public TvControlManager(Activity activity, View btnInputs, View btnWifi, View btnSettings,
            View btnPhotos, View btnSearch, TextView tvWifiIcon, View inputOverlay, TvView inputTvView,
            CinematicBackgroundManager backgroundManager) {
        this.activity = activity;
        this.btnInputs = btnInputs;
        this.btnWifi = btnWifi;
        this.btnSettings = btnSettings;
        this.btnPhotos = btnPhotos;
        this.btnSearch = btnSearch;
        this.tvWifiIcon = tvWifiIcon;
        this.inputOverlay = inputOverlay;
        this.inputTvView = inputTvView;
        this.backgroundManager = backgroundManager;
        this.tvInputManager = (TvInputManager) activity.getSystemService(Context.TV_INPUT_SERVICE);
        this.connectivityManager = (ConnectivityManager)
                activity.getSystemService(Context.CONNECTIVITY_SERVICE);

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

    /** Register connectivity events only while the launcher is visible. */
    public void onResume() {
        if (connectivityManager == null) {
            connectivityManager = (ConnectivityManager)
                    activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        }
        if (connectivityManager != null && !networkCallbackRegistered) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback, networkHandler);
                networkCallbackRegistered = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not register foreground connectivity callback", e);
            }
        }
        updateWifiIndicator();
    }

    /** Release the callback when another app covers the launcher. */
    public void onPause() {
        networkHandler.removeCallbacks(wifiRefresh);
        if (!networkCallbackRegistered || connectivityManager == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            Log.d(TAG, "Connectivity callback was already unregistered", e);
        } finally {
            networkCallbackRegistered = false;
        }
    }

    private void scheduleWifiIndicatorRefresh() {
        networkHandler.removeCallbacks(wifiRefresh);
        networkHandler.post(wifiRefresh);
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

    /** Entry point for physical remotes that send Search/Assist key codes. */
    public void openAssistantFromRemote() {
        handleAssistantAction();
    }

    /**
     * Opens the device-selected assistant without hard-coding a Google package.
     * Some TV firmware exposes VOICE_ASSIST rather than ACTION_ASSIST.
     */
    private void handleAssistantAction() {
        Intent[] intents = new Intent[] {
                new Intent(Intent.ACTION_ASSIST),
                new Intent(Intent.ACTION_VOICE_COMMAND)
        };
        for (Intent intent : intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.d(TAG, "Assistant route unavailable for " + intent.getAction(), e);
            } catch (Exception e) {
                Log.w(TAG, "Unable to open assistant route " + intent.getAction(), e);
            }
        }
        Toast.makeText(activity, R.string.assistant_unavailable, Toast.LENGTH_LONG).show();
    }

    /** Opens the Liquid Glass panel for user-chosen local background images. */
    private void handlePhotosAction() {
        Dialog dialog = new Dialog(activity);
        View panel = activity.getLayoutInflater().inflate(R.layout.dialog_custom_background, null, false);
        dialog.setContentView(panel);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        TextView chooseButton = panel.findViewById(R.id.btn_choose_backgrounds);
        TextView manageButton = panel.findViewById(R.id.btn_manage_backgrounds);
        TextView cancelButton = panel.findViewById(R.id.btn_cancel_backgrounds);
        chooseButton.setOnClickListener(v -> {
            dialog.dismiss();
            openBackgroundPicker();
        });
        if (manageButton != null) {
            manageButton.setOnClickListener(v -> {
                dialog.dismiss();
                showManagedBackgroundsDialog();
            });
        }
        cancelButton.setOnClickListener(v -> dialog.dismiss());

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            float density = activity.getResources().getDisplayMetrics().density;
            int maxWidth = (int) (440 * density);
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            attributes.width = Math.min(maxWidth, (int) (screenWidth * 0.70f));
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
            attributes.width = Math.min((int) (440 * density),
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.70f));
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.dimAmount = 0.22f;
            window.setAttributes(attributes);
        }
        chooseButton.requestFocus();
    }


    private void showManagedBackgroundsDialog() {
        if (backgroundManager == null) {
            Toast.makeText(activity, "La gestión de fondos no está disponible.", Toast.LENGTH_LONG).show();
            return;
        }

        Dialog dialog = new Dialog(activity);
        android.widget.LinearLayout content = new android.widget.LinearLayout(activity);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(10), dp(14), dp(10));
        content.setBackgroundResource(R.drawable.liquid_glass_dialog_bg);

        TextView title = new TextView(activity);
        title.setText("MIS FONDOS");
        title.setTextColor(android.graphics.Color.WHITE);
        title.setTextSize(16);
        title.setIncludeFontPadding(false);
        title.setPadding(dp(6), dp(4), dp(6), dp(8));
        title.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.BOLD));
        content.addView(title);

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.setFillViewport(true);
        android.widget.LinearLayout list = new android.widget.LinearLayout(activity);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        scroll.addView(list, new android.widget.ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, dp(176)));

        TextView close = new TextView(activity);
        close.setText("CERRAR");
        close.setGravity(android.view.Gravity.CENTER);
        close.setTextColor(android.graphics.Color.WHITE);
        close.setTextSize(13);
        close.setFocusable(true);
        close.setClickable(true);
        close.setBackgroundResource(R.drawable.liquid_glass_action_bg);
        android.widget.LinearLayout.LayoutParams closeParams =
                new android.widget.LinearLayout.LayoutParams(-1, dp(32));
        closeParams.topMargin = dp(5);
        content.addView(close, closeParams);
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(content);
        dialog.setCancelable(true);
        populateManagedBackgroundRows(list, dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = Math.min(dp(420),
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.68f));
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.dimAmount = 0.22f;
            window.setAttributes(attributes);
            window.setGravity(android.view.Gravity.CENTER);
        }
        dialog.show();
        if (window != null) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = Math.min(dp(420),
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.68f));
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(attributes);
        }
        close.requestFocus();
    }

    private void populateManagedBackgroundRows(
            android.widget.LinearLayout list, Dialog dialog) {
        list.removeAllViews();
        List<File> files = backgroundManager.getManagedBackgrounds();
        if (files.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("No hay fondos importados. Usa «Elegir fotos» para añadirlos.");
            empty.setTextColor(0xFFCFD8E3);
            empty.setTextSize(14);
            empty.setPadding(0, dp(14), 0, dp(14));
            list.addView(empty);
            return;
        }

        for (File file : files) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));

            // Identify each imported image visually with a low-resolution preview, not a filename.
            FrameLayout previewFrame = new FrameLayout(activity);
            GradientDrawable previewBorder = new GradientDrawable();
            previewBorder.setColor(0xFF111722);
            previewBorder.setCornerRadius(dp(5));
            previewBorder.setStroke(Math.max(1, dp(1)), 0x887FC9EA);
            previewFrame.setBackground(previewBorder);
            previewFrame.setClipToOutline(true);
            ImageView preview = new ImageView(activity);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setContentDescription("Vista previa del fondo");
            String thumbnailPath = file.getAbsolutePath();
            preview.setTag(thumbnailPath);
            // Keep all thumbnail file I/O and pixel decoding off the UI thread.
            try {
                BACKGROUND_IMPORT_EXECUTOR.execute(() -> {
                    Bitmap previewBitmap = decodeBackgroundThumbnail(file);
                    if (previewBitmap == null) return;
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()
                                || preview.getParent() == null
                                || !thumbnailPath.equals(preview.getTag())) {
                            previewBitmap.recycle();
                            return;
                        }
                        preview.setImageBitmap(previewBitmap);
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // The manager is shutting down; the empty thumbnail frame remains harmless.
            }
            FrameLayout.LayoutParams previewImageParams = new FrameLayout.LayoutParams(-1, -1);
            previewImageParams.setMargins(dp(2), dp(2), dp(2), dp(2));
            previewFrame.addView(preview, previewImageParams);
            android.widget.LinearLayout.LayoutParams previewParams =
                    new android.widget.LinearLayout.LayoutParams(dp(52), dp(30));
            previewParams.setMargins(dp(2), 0, dp(5), 0);
            row.addView(previewFrame, previewParams);

            TextView use = new TextView(activity);
            use.setText("USAR");
            use.setGravity(android.view.Gravity.CENTER);
            use.setTextColor(0xFFB8E8FF);
            use.setTextSize(10);
            use.setFocusable(true);
            use.setClickable(true);
            use.setBackgroundResource(R.drawable.liquid_glass_action_bg);
            android.widget.LinearLayout.LayoutParams useParams =
                    new android.widget.LinearLayout.LayoutParams(dp(52), dp(34));
            useParams.setMargins(dp(4), 0, dp(4), 0);
            row.addView(use, useParams);
            use.setOnClickListener(v -> {
                if (backgroundManager.showWallpaper(file)) {
                    dialog.dismiss();
                    Toast.makeText(activity, "Cambiando al fondo seleccionado…",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity, "No se pudo cargar ese fondo.",
                            Toast.LENGTH_LONG).show();
                }
            });

            TextView remove = new TextView(activity);
            remove.setText("BORRAR");
            remove.setGravity(android.view.Gravity.CENTER);
            remove.setTextColor(0xFFFFC7C7);
            remove.setTextSize(9);
            remove.setFocusable(true);
            remove.setClickable(true);
            remove.setBackgroundResource(R.drawable.liquid_glass_action_bg);
            android.widget.LinearLayout.LayoutParams removeParams =
                    new android.widget.LinearLayout.LayoutParams(dp(56), dp(34));
            row.addView(remove, removeParams);
            remove.setOnClickListener(v -> new AlertDialog.Builder(activity)
                    .setTitle("Eliminar este fondo")
                    .setMessage("Se eliminará la copia guardada dentro de Cinematic TV. Esta acción no borra la imagen original de la galería.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Eliminar", (d, which) -> {
                        if (backgroundManager.deleteManagedBackground(file)) {
                            Toast.makeText(activity, "Fondo eliminado de Cinematic TV.",
                                    Toast.LENGTH_SHORT).show();
                            populateManagedBackgroundRows(list, dialog);
                        } else {
                            Toast.makeText(activity, "No se pudo eliminar ese fondo.",
                                    Toast.LENGTH_LONG).show();
                        }
                    }).show());

            list.addView(row, new android.widget.LinearLayout.LayoutParams(-1, dp(40)));
        }
    }

    /**
     * Decode only a thumbnail. Full-resolution wallpapers stay on the background worker.
     */
    private Bitmap decodeBackgroundThumbnail(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int targetWidth = Math.max(1, dp(52) * 2);
            int targetHeight = Math.max(1, dp(30) * 2);
            int sampleSize = 1;
            while (bounds.outWidth / (sampleSize * 2) >= targetWidth
                    && bounds.outHeight / (sampleSize * 2) >= targetHeight) {
                sampleSize *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not create a wallpaper thumbnail", e);
            return null;
        }
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

        List<Uri> selected = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) selected.add(uri);
            }
        } else if (data.getData() != null) {
            selected.add(data.getData());
        }
        if (selected.isEmpty()) {
            Toast.makeText(activity, "No se seleccionaron imágenes", Toast.LENGTH_SHORT).show();
            return true;
        }

        // ACTION_OPEN_DOCUMENT can return persistable grants. Keep them where supported so a
        // delayed worker import never loses access while the picker activity is gone.
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            for (Uri uri : selected) {
                try {
                    activity.getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Immediate import still works for providers that do not support persistence.
                }
            }
        }

        try {
            BACKGROUND_IMPORT_EXECUTOR.execute(() -> {
                File directory = new File(activity.getFilesDir(), "CinematicBackgrounds");
                if (!directory.exists() && !directory.mkdirs()) {
                    networkHandler.post(() -> Toast.makeText(activity,
                            "No se pudo preparar el almacenamiento privado de fondos.",
                            Toast.LENGTH_LONG).show());
                    return;
                }

                int imported = 0;
                for (int i = 0; i < selected.size(); i++) {
                    if (copyImage(selected.get(i), directory, i)) imported++;
                }
                final int importedCount = imported;
                networkHandler.post(() -> {
                    if (importedCount > 0) {
                        if (backgroundManager != null) backgroundManager.loadLocalBackgrounds();
                        Toast.makeText(activity,
                                "Fondos guardados dentro de Cinematic TV: " + importedCount,
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(activity,
                                "No se pudo importar ninguna imagen.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Toast.makeText(activity, "No se pudo iniciar la importación de fondos.", Toast.LENGTH_LONG).show();
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

        String displayName = null;
        android.database.Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(
                    uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (column >= 0) displayName = cursor.getString(column);
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "Source image name is unavailable; using a generic label.", e);
        } finally {
            if (cursor != null) cursor.close();
        }

        String readableName = displayName == null ? "fondo" : displayName.trim();
        int dot = readableName.lastIndexOf('.');
        if (dot > 0) readableName = readableName.substring(0, dot);
        readableName = readableName.replaceAll("[^A-Za-z0-9._-]", "_");
        readableName = readableName.replaceAll("_+", "_");
        if (readableName.length() > 56) readableName = readableName.substring(0, 56);
        if (readableName.isEmpty()) readableName = "fondo";

        File target = new File(directory, "custom_background_" + stamp + "_" + index
                + "_" + readableName + "." + extension);
        try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
            if (input == null) return false;
            try (OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            return target.isFile() && target.length() > 0L;
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
        int pad = dp(14);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackgroundResource(R.drawable.liquid_glass_dialog_bg);
        TextView title = new TextView(activity);
        title.setText("FUENTES DE ENTRADA");
        title.setTextColor(android.graphics.Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        title.setPadding(dp(2), 0, dp(2), dp(8));
        panel.addView(title);

        for (InputOption source : options) {
            TextView option = new TextView(activity);
            option.setText(source.label);
            option.setTextColor(android.graphics.Color.WHITE);
            option.setTextSize(14);
            option.setGravity(android.view.Gravity.CENTER_VERTICAL);
            option.setPadding(dp(12), 0, dp(12), 0);
            option.setMinHeight(dp(38));
            option.setFocusable(true);
            option.setClickable(true);
            option.setBackgroundResource(R.drawable.liquid_glass_action_bg);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, dp(38));
            lp.bottomMargin = dp(4);
            panel.addView(option, lp);
            option.setOnClickListener(v -> {
                dialog.dismiss();
                openSystemInput(source.input, source.label, source.kind);
            });
        }

        TextView close = new TextView(activity);
        close.setText("VOLVER");
        close.setTextColor(android.graphics.Color.WHITE);
        close.setTextSize(13);
        close.setGravity(android.view.Gravity.CENTER);
        close.setMinHeight(dp(34));
        close.setFocusable(true);
        close.setClickable(true);
        close.setBackgroundResource(R.drawable.liquid_glass_action_bg);
        panel.addView(close, new android.widget.LinearLayout.LayoutParams(-1, dp(34)));
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
            attrs.width = Math.min(dp(360), (int)(screenWidth * 0.66f));
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
    /** Entry point for remotes that emit KEYCODE_SETTINGS or KEYCODE_MENU. */
    public boolean openSettingsFromRemote() {
        // If no route can be launched, let Activity.dispatchKeyEvent delegate the original
        // physical key to Android/OEM handling instead of swallowing it.
        return handleSettingsAction(false);
    }

    private void handleSettingsAction() {
        handleSettingsAction(true);
    }

    private boolean handleSettingsAction(boolean showUnavailableToast) {
        Intent[] settingsIntents = new Intent[] {
                new Intent("android.settings.TV_SETTINGS"), // OEM / Android TV settings
                new Intent(Settings.ACTION_SETTINGS)
        };

        for (Intent intent : settingsIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                Log.i(TAG, "Opened system settings with action " + intent.getAction());
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "System settings action unavailable: " + intent.getAction(), e);
            } catch (Exception e) {
                Log.w(TAG, "Could not launch system settings action: " + intent.getAction(), e);
            }
        }

        // Some OEM TV builds do not publish either implicit settings action. As a fallback,
        // search only the TV-launchable activities that Android already exposes to this app.
        try {
            Intent leanback = new Intent(Intent.ACTION_MAIN);
            leanback.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> candidates = activity.getPackageManager()
                    .queryIntentActivities(leanback, 0);
            ResolveInfo best = null;
            int bestScore = 0;
            for (ResolveInfo candidate : candidates) {
                if (candidate == null || candidate.activityInfo == null) continue;
                String packageName = candidate.activityInfo.packageName;
                if (packageName == null || packageName.equals(activity.getPackageName())) continue;
                CharSequence label;
                try {
                    label = candidate.loadLabel(activity.getPackageManager());
                } catch (Exception ignored) {
                    label = "";
                }
                String identity = (packageName + " " + candidate.activityInfo.name + " "
                        + String.valueOf(label)).toLowerCase(Locale.ROOT);
                int score = 0;
                if (identity.contains("settings") || identity.contains("ajustes")
                        || identity.contains("configuración") || identity.contains("configuracion")) {
                    score = 100;
                }
                if (identity.contains("hisense") && score > 0) score += 30;
                if (identity.contains("tv.settings") || identity.contains("tvsettings")) score += 20;
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }

            if (best != null) {
                Intent launch = new Intent(Intent.ACTION_MAIN);
                launch.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                launch.setComponent(new ComponentName(
                        best.activityInfo.packageName, best.activityInfo.name));
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(launch);
                Log.i(TAG, "Opened TV settings activity fallback: "
                        + best.activityInfo.packageName + "/" + best.activityInfo.name);
                return true;
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Could not launch the TV settings activity fallback", e);
        } catch (Exception e) {
            Log.w(TAG, "Failed while looking for a TV settings activity", e);
        }

        if (showUnavailableToast) {
            Toast.makeText(activity,
                    "No se pudieron abrir Ajustes. El sistema no expuso una actividad compatible.",
                    Toast.LENGTH_LONG).show();
        }
        return false;
    }
}
