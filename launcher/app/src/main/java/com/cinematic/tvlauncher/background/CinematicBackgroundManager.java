package com.cinematic.tvlauncher.background;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.graphics.drawable.Drawable;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;

import com.cinematic.tvlauncher.R;
import com.cinematic.tvlauncher.config.LauncherConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CinematicBackgroundManager — Controls local wallpaper rotation, viewport-sized decoding,
 * crossfade transitions with foreground-only work.
 * Fulfills MASTER_PROMPT Annex C.2 and C.3.
 */
public class CinematicBackgroundManager {
    private static final String TAG = "CinematicBackgroundMgr";
    private static final int MAX_WALLPAPER_DECODE_WIDTH = 2048;
    private static final int MAX_WALLPAPER_DECODE_HEIGHT = 1152;
    private static final String PREFERENCES_NAME = "cinematic_launcher_prefs";
    private static final String KEY_BACKGROUND_ROTATION_ENABLED = "background_rotation_enabled";
    private static final String KEY_PAUSED_WALLPAPER_PATH = "background_paused_wallpaper_path";

    private final Activity activity;
    private final ImageView imageViewA;
    private final ImageView imageViewB;
    private final LauncherConfig config;
    private final SharedPreferences preferences;
    private final RotationQueue queue;
    private volatile boolean rotationEnabled = true;
    private final Handler mainHandler;
    private final ExecutorService decodeExecutor;

    private volatile boolean isVisible = false;
    private volatile boolean destroyed = false;
    private File pendingPreferredBackground;
    private boolean isFrontA = true;
    private AnimatorSet crossfadeAnimator;
    private Future<?> pendingDecode;
    private volatile Future<?> pendingLocalBackgroundLoad;
    private volatile boolean localBackgroundsLoaded;
    private final AtomicLong workGeneration = new AtomicLong();
    private final AtomicLong localBackgroundLoadGeneration = new AtomicLong();
    private boolean hasRealWallpaperLoaded = false;
    private volatile File currentWallpaperFile;
    private File wallpaperFileA;
    private File wallpaperFileB;
    private Runnable backgroundChangedListener;

    private final Runnable rotationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!rotationEnabled || !isVisible || destroyed || queue.isEmpty()) return;
            rotateToNext();
            // Keep rotation event-driven: do not wake the main looper when no wallpapers exist.
            // With one photo, displaying it once is enough; re-decoding and crossfading
            // the same bitmap every 20 seconds produces no visual change.
            if (rotationEnabled && isVisible && !destroyed && queue.size() > 1) {
                mainHandler.postDelayed(this, config.getRotationIntervalSec() * 1000L);
            }
        }
    };

    public CinematicBackgroundManager(Activity activity, ImageView imgA, ImageView imgB, LauncherConfig config) {
        this.activity = activity;
        this.imageViewA = imgA;
        this.imageViewB = imgB;
        this.config = config != null ? config : new LauncherConfig();
        this.preferences = activity.getSharedPreferences(PREFERENCES_NAME, Activity.MODE_PRIVATE);
        this.rotationEnabled = preferences.getBoolean(KEY_BACKGROUND_ROTATION_ENABLED, true);
        this.queue = new RotationQueue();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.decodeExecutor = new ThreadPoolExecutor(
                0, 1, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                    Thread worker = new Thread(() -> {
                        try {
                            android.os.Process.setThreadPriority(
                                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
                        } catch (RuntimeException ignored) {
                            // Keep decoding if this firmware rejects a thread-priority change.
                        }
                        task.run();
                    }, "CinematicWallpaperWorker");
                    return worker;
                });

        loadLocalBackgrounds();
    }

    public RotationQueue getQueue() {
        return queue;
    }

    public boolean isRotationEnabled() {
        return rotationEnabled;
    }

    /**
     * Pauses automatic wallpaper changes while preserving the currently visible image.
     * Explicit showWallpaper() calls remain available so the user can choose a fixed image.
     */
    public void setRotationEnabled(boolean enabled) {
        if (destroyed || rotationEnabled == enabled) return;
        if (!enabled) {
            rotationEnabled = false;
            mainHandler.removeCallbacks(rotationRunnable);
            workGeneration.incrementAndGet();
            cancelPendingDecode();
            // If a timed crossfade is in progress, keep the last fully settled frame.
            cancelCrossfadeAnimation();
            File selected = currentWallpaperFile;
            SharedPreferences.Editor editor = preferences.edit()
                    .putBoolean(KEY_BACKGROUND_ROTATION_ENABLED, false);
            if (selected != null && selected.isFile()) {
                editor.putString(KEY_PAUSED_WALLPAPER_PATH, selected.getAbsolutePath());
            } else if (pendingPreferredBackground != null && pendingPreferredBackground.isFile()) {
                editor.putString(KEY_PAUSED_WALLPAPER_PATH,
                        pendingPreferredBackground.getAbsolutePath());
            } else {
                editor.remove(KEY_PAUSED_WALLPAPER_PATH);
            }
            editor.apply();
        } else {
            rotationEnabled = true;
            preferences.edit()
                    .putBoolean(KEY_BACKGROUND_ROTATION_ENABLED, true)
                    .remove(KEY_PAUSED_WALLPAPER_PATH)
                    .apply();
            scheduleRotationIfAvailable();
        }
    }

    private File getPausedWallpaper() {
        String path = preferences.getString(KEY_PAUSED_WALLPAPER_PATH, null);
        if (path == null || path.isEmpty()) return null;
        File file = new File(path);
        if (!isSupportedImage(file)) {
            preferences.edit().remove(KEY_PAUSED_WALLPAPER_PATH).apply();
            return null;
        }
        return file;
    }

    public void setOnBackgroundChangedListener(Runnable listener) {
        backgroundChangedListener = listener;
    }

    /** Returns the active wallpaper layer; call on the main thread. */
    public Drawable getCurrentBackgroundDrawable() {
        return (isFrontA ? imageViewA : imageViewB).getDrawable();
    }

    private void notifyBackgroundChanged() {
        if (backgroundChangedListener != null) backgroundChangedListener.run();
    }

    /** Arms the next rotation only while visible and only when a real wallpaper is available. */
    private void scheduleRotationIfAvailable() {
        mainHandler.removeCallbacks(rotationRunnable);
        if (isVisible && !destroyed && queue.size() > 1) {
            mainHandler.postDelayed(rotationRunnable, config.getRotationIntervalSec() * 1000L);
        }
    }

    public void loadLocalBackgrounds() {
        loadLocalBackgrounds(null);
    }

    /** Reloads managed images and optionally applies the first newly imported selection. */
    public void loadLocalBackgrounds(File preferredBackground) {
        if (destroyed) return;
        if (preferredBackground != null) pendingPreferredBackground = preferredBackground;
        final long loadGeneration = localBackgroundLoadGeneration.incrementAndGet();
        cancelPendingLocalBackgroundLoad();
        try {
            pendingLocalBackgroundLoad = decodeExecutor.submit(() -> {
            try {
                List<File> images = new ArrayList<>();
                File privatePictures = new File(activity.getFilesDir(), "CinematicBackgrounds");
                addImagesFromDirectory(privatePictures, images);

                // Continue honoring wallpapers imported by previous versions.
                File legacyPictures = activity.getExternalFilesDir("CinematicBackgrounds");
                addImagesFromDirectory(legacyPictures, images);

                File publicPictures = new File("/sdcard/Pictures/CinematicLauncher");
                if (publicPictures.exists() && publicPictures.isDirectory()) {
                    addImagesFromDirectory(publicPictures, images);
                }

                if (destroyed || Thread.currentThread().isInterrupted()
                        || loadGeneration != localBackgroundLoadGeneration.get()) return;
                queue.setImages(images);
                localBackgroundsLoaded = true;
                if (currentWallpaperFile != null && currentWallpaperFile.isFile()) {
                    queue.markDisplayed(currentWallpaperFile);
                }
                File preferred = pendingPreferredBackground;
                if (preferred == null && !rotationEnabled) preferred = getPausedWallpaper();
                if (preferred != null && (!preferred.isFile() || !images.contains(preferred))) {
                    preferred = null;
                    if (!rotationEnabled) {
                        preferences.edit().remove(KEY_PAUSED_WALLPAPER_PATH).apply();
                    }
                    pendingPreferredBackground = null;
                }
                final File preferredToShow = preferred;
                Log.i(TAG, "Loaded " + images.size() + " local background images"
                        + " rotationEnabled=" + rotationEnabled);
                // Do not make the first real wallpaper wait through the normal 20-second
                // rotation interval. Decode it immediately after discovery, off the UI thread.
                mainHandler.post(() -> {
                    if (!isVisible || destroyed
                            || loadGeneration != localBackgroundLoadGeneration.get()) return;
                    if (preferredToShow != null) {
                        showWallpaper(preferredToShow);
                    } else if (!hasRealWallpaperLoaded && !queue.isEmpty()) {
                        if (rotationEnabled) {
                            rotateToNext();
                        } else {
                            // With rotation disabled, show one initial/persisted frame but never
                            // schedule an automatic successor.
                            File initial = queue.getNextRandomImage();
                            if (initial != null) showWallpaper(initial);
                        }
                    } else {
                        scheduleRotationIfAvailable();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Error discovering local background photos", e);
            } finally {
                if (loadGeneration == localBackgroundLoadGeneration.get()) {
                    pendingLocalBackgroundLoad = null;
                }
            }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Activity is already being destroyed.
        }
    }

    private void addImagesFromDirectory(File directory, List<File> images) {
        if (Thread.currentThread().isInterrupted() || directory == null
                || !directory.isDirectory()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) return;
            if (isSupportedImage(file) && !images.contains(file)) images.add(file);
        }
    }

    private boolean isSupportedImage(File file) {
        return file != null && file.isFile()
                && RotationQueue.isSupportedImageName(file.getName());
    }

    /** Lists only app-imported wallpapers, excluding arbitrary files in public Pictures. */
    public List<File> getManagedBackgrounds() {
        List<File> files = new ArrayList<>();
        addImagesFromDirectory(new File(activity.getFilesDir(), "CinematicBackgrounds"), files);
        addImagesFromDirectory(activity.getExternalFilesDir("CinematicBackgrounds"), files);
        Collections.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files;
    }

    public File getCurrentWallpaperFile() {
        return currentWallpaperFile;
    }

    /**
     * Drops the manager's reference to an outgoing decoded wallpaper so the bitmap becomes
     * eligible for reclamation. Do not call Bitmap.recycle() here: HWUI can still be finishing
     * a frame that references the prior drawable.
     */
    private void clearWallpaperLayer(ImageView layer, boolean restoreDefault) {
        if (layer == null) return;
        if (restoreDefault) layer.setImageResource(R.drawable.default_wallpaper);
        else layer.setImageDrawable(null);
        if (layer == imageViewA) wallpaperFileA = null;
        else if (layer == imageViewB) wallpaperFileB = null;
    }

    /** Cancels both running and queued decode work; retains the cached displayed bitmap. */
    private void cancelDecodeTask(Future<?> task) {
        if (task == null) return;
        task.cancel(true);
        if (task instanceof Runnable && decodeExecutor instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) decodeExecutor).remove((Runnable) task);
        }
    }

    private void cancelPendingDecode() {
        Future<?> task = pendingDecode;
        pendingDecode = null;
        cancelDecodeTask(task);
    }

    /** A newer directory scan supersedes an older pending scan; do not queue redundant work. */
    private void cancelPendingLocalBackgroundLoad() {
        Future<?> task = pendingLocalBackgroundLoad;
        pendingLocalBackgroundLoad = null;
        cancelDecodeTask(task);
    }

    /** Cancels an in-flight crossfade and restores the last fully displayed layer. */
    private void cancelCrossfadeAnimation() {
        AnimatorSet running = crossfadeAnimator;
        if (running == null) return;
        // Clear identity before cancel(): Animator.cancel() also dispatches onAnimationEnd().
        crossfadeAnimator = null;
        ImageView front = isFrontA ? imageViewA : imageViewB;
        ImageView back = isFrontA ? imageViewB : imageViewA;
        // applyCrossfade() stages the incoming file before its fade finishes; on cancellation,
        // the outgoing/front layer remains the one actually visible to the user.
        currentWallpaperFile = isFrontA ? wallpaperFileA : wallpaperFileB;
        running.cancel();
        front.setAlpha(1f);
        front.setScaleX(1f);
        front.setScaleY(1f);
        clearWallpaperLayer(back, false);
        back.setAlpha(0f);
        back.setScaleX(1f);
        back.setScaleY(1f);
    }

    /** Starts a worker decode and crossfades to an explicitly selected app-managed photo. */
    public boolean showWallpaper(File file) {
        if (file == null || !isSupportedImage(file) || destroyed || !isVisible) return false;
        mainHandler.removeCallbacks(rotationRunnable);
        queue.markDisplayed(file);
        cancelPendingDecode();
        cancelCrossfadeAnimation();
        pendingPreferredBackground = file;
        if (!rotationEnabled) {
            preferences.edit().putString(KEY_PAUSED_WALLPAPER_PATH, file.getAbsolutePath()).apply();
        }
        long generation = workGeneration.incrementAndGet();
        try {
            pendingDecode = decodeExecutor.submit(() -> {
                Bitmap bitmap = decodeSampledBitmap(file);
                if (bitmap == null) return;
                if (Thread.currentThread().isInterrupted() || destroyed || !isVisible
                        || generation != workGeneration.get()) {
                    bitmap.recycle();
                    return;
                }
                boolean posted = mainHandler.post(() -> {
                    if (!destroyed && isVisible && generation == workGeneration.get()) {
                        applyCrossfade(bitmap, file);
                    } else if (!bitmap.isRecycled()) {
                        // The request became stale after the worker posted its UI callback.
                        bitmap.recycle();
                    }
                });
                if (!posted && !bitmap.isRecycled()) bitmap.recycle();
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
    }

    /** Deletes only files inside the app's private or legacy app-specific wallpaper folders. */
    public boolean deleteManagedBackground(File file) {
        if (file == null || !isSupportedImage(file)) return false;
        try {
            File canonicalFile = file.getCanonicalFile();
            File privateDir = new File(activity.getFilesDir(), "CinematicBackgrounds").getCanonicalFile();
            File legacy = activity.getExternalFilesDir("CinematicBackgrounds");
            File parent = canonicalFile.getParentFile();
            boolean managed = parent != null && (parent.equals(privateDir)
                    || (legacy != null && parent.equals(legacy.getCanonicalFile())));
            if (!managed || !canonicalFile.isFile()) return false;

            boolean deletingCurrent = false;
            if (currentWallpaperFile != null) {
                deletingCurrent = canonicalFile.equals(currentWallpaperFile.getCanonicalFile());
            }
            if (!canonicalFile.delete()) return false;
            queue.removeImage(file);
            String pausedPath = preferences.getString(KEY_PAUSED_WALLPAPER_PATH, null);
            if (canonicalFile.getAbsolutePath().equals(pausedPath)) {
                preferences.edit().remove(KEY_PAUSED_WALLPAPER_PATH).apply();
            }

            if (deletingCurrent) {
                // Drop pending work and release both owned wallpaper bitmaps before restoring
                // a valid default frame. This avoids retaining a removed full-resolution image.
                workGeneration.incrementAndGet();
                cancelPendingDecode();
                cancelCrossfadeAnimation();
                currentWallpaperFile = null;
                hasRealWallpaperLoaded = false;
                isFrontA = true;
                // Only the visible fallback layer needs the default bitmap. Keep the
                // transparent incoming layer empty until the next wallpaper is ready.
                clearWallpaperLayer(imageViewA, true);
                clearWallpaperLayer(imageViewB, false);
                imageViewA.setAlpha(1f);
                imageViewB.setAlpha(0f);
                imageViewA.setScaleX(1f);
                imageViewA.setScaleY(1f);
                imageViewB.setScaleX(1f);
                imageViewB.setScaleY(1f);
            }
            loadLocalBackgrounds();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not delete managed wallpaper", e);
            return false;
        }
    }

    public void onResume() {
        if (destroyed) return;
        isVisible = true;
        workGeneration.incrementAndGet();
        // On cold start, show the first real wallpaper as soon as the local queue is ready.
        // Once one has loaded, preserve the current image and resume normal timed rotation.
        if (pendingPreferredBackground != null && !pendingPreferredBackground.isFile()) {
            pendingPreferredBackground = null;
        }
        if (pendingPreferredBackground != null) {
            if (!isLocalBackgroundLoadPending()) {
                loadLocalBackgrounds(pendingPreferredBackground);
            }
        } else if (!localBackgroundsLoaded && !isLocalBackgroundLoadPending()) {
            loadLocalBackgrounds();
        } else if (!rotationEnabled) {
            File pausedWallpaper = getPausedWallpaper();
            if (pausedWallpaper != null && !pausedWallpaper.equals(currentWallpaperFile)) {
                showWallpaper(pausedWallpaper);
            } else if (!hasRealWallpaperLoaded && !queue.isEmpty()) {
                File initial = queue.getNextRandomImage();
                if (initial != null) showWallpaper(initial);
            }
        } else if (!hasRealWallpaperLoaded && !queue.isEmpty()) {
            rotateToNext();
        } else {
            scheduleRotationIfAvailable();
        }
    }

    private boolean isLocalBackgroundLoadPending() {
        Future<?> task = pendingLocalBackgroundLoad;
        return task != null && !task.isDone();
    }

    public void onPause() {
        isVisible = false;
        workGeneration.incrementAndGet();
        mainHandler.removeCallbacks(rotationRunnable);
        cancelPendingDecode();
        if (isLocalBackgroundLoadPending()) {
            localBackgroundsLoaded = false;
        }
        localBackgroundLoadGeneration.incrementAndGet();
        cancelPendingLocalBackgroundLoad();
        cancelCrossfadeAnimation();
    }

    public void onDestroy() {
        destroyed = true;
        onPause();
        clearWallpaperLayer(imageViewA, false);
        clearWallpaperLayer(imageViewB, false);
        decodeExecutor.shutdownNow();
    }

    private void rotateToNext() {
        if (!rotationEnabled || !isVisible || destroyed) return;
        if (pendingDecode != null && !pendingDecode.isDone()) return;
        if (queue.isEmpty()) {
            // Retain default or last valid background; no black frame per Annex C.2
            return;
        }

        File nextFile = queue.getNextRandomImage();
        if (nextFile == null) return;

        final long generation = workGeneration.get();
        try {
            pendingDecode = decodeExecutor.submit(() -> {
                if (Thread.currentThread().isInterrupted() || !isVisible || destroyed) return;
                Bitmap bitmap = decodeSampledBitmap(nextFile);
                if (bitmap == null) return;
                if (Thread.currentThread().isInterrupted() || !isVisible || destroyed
                        || generation != workGeneration.get()) {
                    bitmap.recycle();
                    return;
                }
                boolean posted = mainHandler.post(() -> {
                    if (isVisible && !destroyed && generation == workGeneration.get()) {
                        applyCrossfade(bitmap, nextFile);
                    } else if (!bitmap.isRecycled()) {
                        // HOME paused or a newer wallpaper superseded this request after posting.
                        bitmap.recycle();
                    }
                });
                if (!posted && !bitmap.isRecycled()) bitmap.recycle();
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The launcher is shutting down; no background work should be restarted.
        }
    }

    private void applyCrossfade(Bitmap newBitmap, File sourceFile) {
        if (!isVisible || destroyed || activity.isFinishing() || activity.isDestroyed()) {
            newBitmap.recycle();
            return;
        }

        final ImageView front = isFrontA ? imageViewA : imageViewB;
        final ImageView back = isFrontA ? imageViewB : imageViewA;
        currentWallpaperFile = sourceFile;
        back.setScaleX(front.getScaleX());
        back.setScaleY(front.getScaleY());

        // Keep both full-screen images below the XML scrims and all launcher controls.
        // The incoming image must be immediately above the outgoing one to crossfade with
        // a single alpha animation, avoiding simultaneous full-screen alpha updates.
        keepIncomingWallpaperAboveOutgoing(front, back);
        back.setImageBitmap(newBitmap);
        if (back == imageViewA) wallpaperFileA = sourceFile;
        else wallpaperFileB = sourceFile;
        back.setAlpha(0f);

        int duration = Math.max(160, config.getCrossfadeDurationMs());
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(back, "alpha", 0f, 1f);
        AnimatorSet crossfade = new AnimatorSet();
        crossfade.play(fadeIn);
        crossfade.setDuration(duration);
        crossfade.setInterpolator(new AccelerateDecelerateInterpolator());
        crossfade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // A cancelled/stale animator must not commit a layer swap after HOME pauses.
                if (crossfadeAnimator != crossfade) return;
                front.setAlpha(1f);
                front.setScaleX(1f);
                front.setScaleY(1f);
                // Drop the outgoing TV-sized bitmap after the top layer has fully faded in.
                clearWallpaperLayer(front, false);
                back.setAlpha(1f);
                isFrontA = !isFrontA;
                hasRealWallpaperLoaded = true;
                if (pendingPreferredBackground != null
                        && pendingPreferredBackground.equals(sourceFile)) {
                    pendingPreferredBackground = null;
                }
                scheduleRotationIfAvailable();
                if (crossfadeAnimator == crossfade) crossfadeAnimator = null;
                if (isVisible && !destroyed) notifyBackgroundChanged();
            }
        });
        crossfadeAnimator = crossfade;
        crossfade.start();
    }

    private int wallpaperTargetWidth() {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = metrics.widthPixels > 0 ? metrics.widthPixels : 1920;
        // 1440p is a practical upper bound for a background surface on 4K TVs: it avoids
        // decoding 8.3 MP frames when the UI does not need pixel-perfect 4K artwork.
        return Math.min(width, MAX_WALLPAPER_DECODE_WIDTH);
    }

    private int wallpaperTargetHeight() {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int height = metrics.heightPixels > 0 ? metrics.heightPixels : 1080;
        return Math.min(height, MAX_WALLPAPER_DECODE_HEIGHT);
    }

    /**
     * Keep the two wallpaper layers in adjacent child positions, still below the scrims and
     * application UI. This preserves overlay compositing when A/B alternate as the new image.
     */
    private void keepIncomingWallpaperAboveOutgoing(ImageView outgoing, ImageView incoming) {
        android.view.ViewParent rawParent = incoming.getParent();
        if (!(rawParent instanceof android.view.ViewGroup)
                || outgoing.getParent() != rawParent) return;
        android.view.ViewGroup parent = (android.view.ViewGroup) rawParent;
        int incomingIndex = parent.indexOfChild(incoming);
        int outgoingIndex = parent.indexOfChild(outgoing);
        if (incomingIndex < 0 || outgoingIndex < 0 || incomingIndex > outgoingIndex) return;
        android.view.ViewGroup.LayoutParams params = incoming.getLayoutParams();
        parent.removeView(incoming);
        int updatedOutgoingIndex = parent.indexOfChild(outgoing);
        parent.addView(incoming, updatedOutgoingIndex + 1, params);
    }

    /**
     * Decode close to the TV viewport. Reuse a validated on-disk derivative after first display;
     * otherwise resize during decode on API 28+, with a sampled BitmapFactory fallback on API 26–27.
     */
    private boolean isJpegFile(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    /**
     * BitmapRegionDecoder can return opaque JPEGs in RGB_565, using half the pixel memory of
     * ARGB_8888. Only normal-orientation JPEGs use this route; other EXIF orientations fall
     * through to ImageDecoder so their displayed rotation remains correct.
     */
    private Bitmap decodeRegion(File file, int reqWidth, int reqHeight,
                                Bitmap.Config preferredConfig) throws java.io.IOException {
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        int orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
        if (orientation != ExifInterface.ORIENTATION_NORMAL
                && orientation != ExifInterface.ORIENTATION_UNDEFINED) return null;

        BitmapRegionDecoder regionDecoder = null;
        try {
            regionDecoder = BitmapRegionDecoder.newInstance(file.getAbsolutePath(), false);
            int sourceWidth = regionDecoder.getWidth();
            int sourceHeight = regionDecoder.getHeight();
            int[] bounds = WallpaperCropPolicy.centerCropRect(
                    sourceWidth, sourceHeight, reqWidth, reqHeight);
            Rect crop = new Rect(bounds[0], bounds[1], bounds[2], bounds[3]);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = WallpaperCropPolicy.calculateRegionSampleSize(
                    crop.width(), crop.height(), reqWidth, reqHeight);
            options.inPreferredConfig = preferredConfig;
            options.inDither = preferredConfig == Bitmap.Config.RGB_565;
            Bitmap decoded = regionDecoder.decodeRegion(crop, options);
            if (decoded != null && (decoded.getWidth() > reqWidth || decoded.getHeight() > reqHeight)) {
                double scale = Math.min(reqWidth / (double) decoded.getWidth(),
                        reqHeight / (double) decoded.getHeight());
                int width = Math.max(1, (int) Math.round(decoded.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(decoded.getHeight() * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
                if (scaled != decoded) decoded.recycle();
                decoded = scaled;
            }
            return decoded;
        } finally {
            if (regionDecoder != null) regionDecoder.recycle();
        }
    }

    /**
     * Decode only the pixels required by the TV viewport. Opaque normal-orientation JPEGs use
     * a cropped RGB_565 region; images that require alpha/EXIF handling use ImageDecoder.
     */
    private Bitmap decodeSampledBitmap(File file) {
        long startedAt = SystemClock.elapsedRealtime();
        long cpuStartedAt = Debug.threadCpuTimeNanos();
        try {
            if (Thread.currentThread().isInterrupted() || destroyed) return null;
            int reqWidth = wallpaperTargetWidth();
            int reqHeight = wallpaperTargetHeight();
            Bitmap decoded = null;
            String decodePath = "BITMAP_FACTORY";

            if (isJpegFile(file)) {
                try {
                    decoded = decodeRegion(file, reqWidth, reqHeight, Bitmap.Config.RGB_565);
                    if (decoded != null) decodePath = "JPEG_REGION_RGB565";
                } catch (Exception e) {
                    Log.w(TAG, "JPEG region decode unavailable; falling back to ImageDecoder", e);
                }
            } else if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
                try {
                    Bitmap.Config pngConfig = WallpaperCropPolicy.hasTransparency(file)
                            ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                    decoded = decodeRegion(file, reqWidth, reqHeight, pngConfig);
                    if (decoded != null) {
                        decodePath = pngConfig == Bitmap.Config.ARGB_8888
                                ? "PNG_REGION_ARGB8888_ALPHA"
                                : "PNG_REGION_RGB565_OPAQUE";
                    }
                } catch (Exception e) {
                    Log.w(TAG, "PNG region decode unavailable; falling back to ImageDecoder", e);
                }
            }

            if (decoded == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(file);
                decoded = ImageDecoder.decodeBitmap(source, (decoder, info, sourceIgnored) -> {
                    if (Thread.currentThread().isInterrupted() || destroyed) {
                        decoder.setTargetSize(1, 1);
                        return;
                    }
                    // ImageDecoder applies target scaling before its subset validation on this
                    // firmware. Do not pass a source-resolution crop to setCrop(); keep the
                    // source aspect ratio and let the ImageView's existing CENTER_CROP do the
                    // final viewport crop.
                    int sourceWidth = Math.max(1, info.getSize().getWidth());
                    int sourceHeight = Math.max(1, info.getSize().getHeight());
                    double scale = Math.min(1d, Math.min(
                            reqWidth / (double) sourceWidth,
                            reqHeight / (double) sourceHeight));
                    int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
                    int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
                    decoder.setTargetSize(targetWidth, targetHeight);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                });
                decodePath = "IMAGE_DECODER_NO_CROP";
            } else if (decoded == null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                if (Thread.currentThread().isInterrupted() || destroyed) return null;
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                options.inDither = true;
                decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            }

            if (decoded != null) {
                Log.i(TAG, "Wallpaper decode "
                        + decoded.getWidth() + "x" + decoded.getHeight()
                        + " allocation=" + decoded.getAllocationByteCount()
                        + "B config=" + decoded.getConfig()
                        + " path=" + decodePath
                        + " target=" + reqWidth + "x" + reqHeight
                        + " display=" + activity.getResources().getDisplayMetrics().widthPixels
                        + "x" + activity.getResources().getDisplayMetrics().heightPixels
                        + " elapsed=" + (SystemClock.elapsedRealtime() - startedAt) + "ms"
                        + " cpu=" + Math.max(0L,
                                (Debug.threadCpuTimeNanos() - cpuStartedAt) / 1_000_000L) + "ms");
            }
            return decoded;
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode background image: " + file.getName(), e);
            return null;
        }
    }

    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }
}
