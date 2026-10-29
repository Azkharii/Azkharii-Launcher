package com.cinematic.tvlauncher.background;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;

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
 * CinematicBackgroundManager — Controls Google Photos background rotation,
 * 600ms crossfade transitions, downsampling, and optional subtle motion.
 * Fulfills MASTER_PROMPT Annex C.2 and C.3.
 */
public class CinematicBackgroundManager {
    private static final String TAG = "CinematicBackgroundMgr";

    private final Activity activity;
    private final ImageView imageViewA;
    private final ImageView imageViewB;
    private final LauncherConfig config;
    private final RotationQueue queue;
    private final Handler mainHandler;
    private final ExecutorService decodeExecutor;

    private volatile boolean isVisible = false;
    private volatile boolean destroyed = false;
    private boolean isFrontA = true;
    private AnimatorSet crossfadeAnimator;
    private Future<?> pendingDecode;
    private final AtomicLong workGeneration = new AtomicLong();
    private boolean hasRealWallpaperLoaded = false;
    private File currentWallpaperFile;
    private Runnable backgroundChangedListener;

    private final Runnable rotationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isVisible || destroyed || queue.isEmpty()) return;
            rotateToNext();
            // Keep rotation event-driven: do not wake the main looper when no wallpapers exist.
            // With one photo, displaying it once is enough; re-decoding and crossfading
            // the same bitmap every 10 seconds produces no visual change.
            if (isVisible && !destroyed && queue.size() > 1) {
                mainHandler.postDelayed(this, config.getRotationIntervalSec() * 1000L);
            }
        }
    };

    public CinematicBackgroundManager(Activity activity, ImageView imgA, ImageView imgB, LauncherConfig config) {
        this.activity = activity;
        this.imageViewA = imgA;
        this.imageViewB = imgB;
        this.config = config != null ? config : new LauncherConfig();
        this.queue = new RotationQueue();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.decodeExecutor = new ThreadPoolExecutor(
                0, 1, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

        loadLocalBackgrounds();
    }

    public RotationQueue getQueue() {
        return queue;
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
        if (destroyed) return;
        try {
            decodeExecutor.execute(() -> {
            try {
                java.util.List<File> images = new java.util.ArrayList<>();
                File privatePictures = new File(activity.getFilesDir(), "CinematicBackgrounds");
                addImagesFromDirectory(privatePictures, images);

                // Continue honoring wallpapers imported by previous versions.
                File legacyPictures = activity.getExternalFilesDir("CinematicBackgrounds");
                addImagesFromDirectory(legacyPictures, images);

                File publicPictures = new File("/sdcard/Pictures/CinematicLauncher");
                if (publicPictures.exists() && publicPictures.isDirectory()) {
                    addImagesFromDirectory(publicPictures, images);
                }

                if (destroyed || Thread.currentThread().isInterrupted()) return;
                queue.setImages(images);
                Log.i(TAG, "Loaded " + images.size() + " local background images");
                // Do not make the first real wallpaper wait through the normal 10-second
                // rotation interval. Decode it immediately after discovery, off the UI thread.
                mainHandler.post(() -> {
                    if (isVisible && !destroyed && !hasRealWallpaperLoaded && !queue.isEmpty()) {
                        rotateToNext();
                    } else {
                        scheduleRotationIfAvailable();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Error discovering local background photos", e);
            }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Activity is already being destroyed.
        }
    }

    private void addImagesFromDirectory(File directory, List<File> images) {
        if (directory == null || !directory.isDirectory()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (isSupportedImage(file) && !images.contains(file)) images.add(file);
        }
    }

    private boolean isSupportedImage(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp");
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

    /** Starts a worker decode and crossfades to an explicitly selected app-managed photo. */
    public boolean showWallpaper(File file) {
        if (file == null || !isSupportedImage(file) || destroyed || !isVisible) return false;
        mainHandler.removeCallbacks(rotationRunnable);
        if (pendingDecode != null) pendingDecode.cancel(true);
        if (crossfadeAnimator != null) {
            crossfadeAnimator.cancel();
            crossfadeAnimator = null;
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
                mainHandler.post(() -> {
                    if (destroyed || !isVisible || generation != workGeneration.get()) bitmap.recycle();
                    else applyCrossfade(bitmap, file);
                });
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
            return managed && canonicalFile.isFile() && canonicalFile.delete();
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
        if (!hasRealWallpaperLoaded && !queue.isEmpty()) {
            rotateToNext();
        } else {
            scheduleRotationIfAvailable();
        }

    }

    public void onPause() {
        isVisible = false;
        workGeneration.incrementAndGet();
        mainHandler.removeCallbacks(rotationRunnable);
        if (pendingDecode != null) {
            pendingDecode.cancel(true);
            pendingDecode = null;
        }
        if (crossfadeAnimator != null) crossfadeAnimator.cancel();

    }

    public void onDestroy() {
        destroyed = true;
        onPause();
        decodeExecutor.shutdownNow();
    }

    private void rotateToNext() {
        if (!isVisible || destroyed) return;
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
                mainHandler.post(() -> {
                    if (!isVisible || destroyed || generation != workGeneration.get()) bitmap.recycle();
                    else applyCrossfade(bitmap, nextFile);
                });
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

        // Keep both wallpaper layers beneath the XML scrims and all UI.
        // Never call bringToFront(): it would place the selected photo over the launcher controls.
        back.setImageBitmap(newBitmap);
        back.setAlpha(0f);

        // Crossfade both layers simultaneously so the outgoing image gradually
        // loses opacity while the incoming image becomes visible. Keep the transition
        // long enough to read as cinematic rather than a hard wallpaper swap.
        int duration = Math.max(180, config.getCrossfadeDurationMs());
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(back, "alpha", 0f, 1f);
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(front, "alpha", 1f, 0f);
        AnimatorSet crossfade = new AnimatorSet();
        crossfade.playTogether(fadeIn, fadeOut);
        crossfade.setDuration(duration);
        crossfade.setInterpolator(new AccelerateDecelerateInterpolator());
        crossfade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                front.setAlpha(0f);
                back.setAlpha(1f);
                front.setScaleX(1f);
                front.setScaleY(1f);
                isFrontA = !isFrontA;
                hasRealWallpaperLoaded = true;
                scheduleRotationIfAvailable();
                if (crossfadeAnimator == crossfade) crossfadeAnimator = null;
                if (isVisible && !destroyed) notifyBackgroundChanged();
            }
        });
        crossfadeAnimator = crossfade;
        crossfade.start();
    }

    /**
     * Downsample bitmap to match screen dimensions, avoiding OOM (Annex C.2).
     */
    private Bitmap decodeSampledBitmap(File file) {
        try {
            if (Thread.currentThread().isInterrupted() || destroyed) return null;
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int reqWidth = metrics.widthPixels > 0 ? metrics.widthPixels : 1920;
            int reqHeight = metrics.heightPixels > 0 ? metrics.heightPixels : 1080;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);

            if (Thread.currentThread().isInterrupted() || destroyed) return null;
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565; // Memory efficient for TV wallpapers

            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to decode background image: " + file.getName(), t);
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
