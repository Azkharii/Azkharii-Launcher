package com.cinematic.tvlauncher.background;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
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
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * CinematicBackgroundManager — Controls local wallpaper rotation, viewport-sized decoding,
 * crossfade transitions, and optional subtle motion.
 * Fulfills MASTER_PROMPT Annex C.2 and C.3.
 */
public class CinematicBackgroundManager {
    private static final String TAG = "CinematicBackgroundMgr";
    private static final String WALLPAPER_PREVIEW_CACHE_DIR = "wallpaper-preview-v1";

    private final Activity activity;
    private final ImageView imageViewA;
    private final ImageView imageViewB;
    private final LauncherConfig config;
    private final RotationQueue queue;
    private final Handler mainHandler;
    private final ExecutorService decodeExecutor;

    private volatile boolean isVisible = false;
    private volatile boolean destroyed = false;
    private File pendingPreferredBackground;
    private boolean isFrontA = true;
    private AnimatorSet crossfadeAnimator;
    private Future<?> pendingDecode;
    private final AtomicLong workGeneration = new AtomicLong();
    private boolean hasRealWallpaperLoaded = false;
    private volatile File currentWallpaperFile;
    private File wallpaperFileA;
    private File wallpaperFileB;
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
        loadLocalBackgrounds(null);
    }

    /** Reloads managed images and optionally applies the first newly imported selection. */
    public void loadLocalBackgrounds(File preferredBackground) {
        if (destroyed) return;
        if (preferredBackground != null) pendingPreferredBackground = preferredBackground;
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
                if (currentWallpaperFile != null && currentWallpaperFile.isFile()) {
                    queue.markDisplayed(currentWallpaperFile);
                }
                Log.i(TAG, "Loaded " + images.size() + " local background images");
                // Do not make the first real wallpaper wait through the normal 10-second
                // rotation interval. Decode it immediately after discovery, off the UI thread.
                mainHandler.post(() -> {
                    if (!isVisible || destroyed) return;
                    File preferred = pendingPreferredBackground;
                    if (preferred != null && preferred.isFile() && images.contains(preferred)) {
                        pendingPreferredBackground = null;
                        showWallpaper(preferred);
                    } else {
                        if (preferred != null && !preferred.isFile()) pendingPreferredBackground = null;
                        if (!hasRealWallpaperLoaded && !queue.isEmpty()) {
                            rotateToNext();
                        } else {
                            scheduleRotationIfAvailable();
                        }
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
    private void cancelPendingDecode() {
        Future<?> task = pendingDecode;
        pendingDecode = null;
        if (task == null) return;
        task.cancel(true);
        if (task instanceof Runnable && decodeExecutor instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) decodeExecutor).remove((Runnable) task);
        }
    }

    /** Starts a worker decode and crossfades to an explicitly selected app-managed photo. */
    public boolean showWallpaper(File file) {
        if (file == null || !isSupportedImage(file) || destroyed || !isVisible) return false;
        mainHandler.removeCallbacks(rotationRunnable);
        queue.markDisplayed(file);
        cancelPendingDecode();
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
                    if (!destroyed && isVisible && generation == workGeneration.get()) {
                        applyCrossfade(bitmap, file);
                    }
                    // The decoded bitmap is retained only by the active wallpaper layer.
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
            if (!managed || !canonicalFile.isFile()) return false;

            boolean deletingCurrent = false;
            if (currentWallpaperFile != null) {
                deletingCurrent = canonicalFile.equals(currentWallpaperFile.getCanonicalFile());
            }
            File previewCache = wallpaperPreviewCacheFile(canonicalFile,
                    wallpaperTargetWidth(), wallpaperTargetHeight());
            if (!canonicalFile.delete()) return false;
            if (previewCache != null) previewCache.delete();
            queue.removeImage(file);

            if (deletingCurrent) {
                // Drop pending work and release both owned wallpaper bitmaps before restoring
                // a valid default frame. This avoids retaining a removed full-resolution image.
                workGeneration.incrementAndGet();
                cancelPendingDecode();
                if (crossfadeAnimator != null) {
                    crossfadeAnimator.cancel();
                    crossfadeAnimator = null;
                }
                currentWallpaperFile = null;
                hasRealWallpaperLoaded = false;
                isFrontA = true;
                clearWallpaperLayer(imageViewA, true);
                clearWallpaperLayer(imageViewB, true);
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
        if (pendingPreferredBackground != null && pendingPreferredBackground.isFile()) {
            loadLocalBackgrounds(pendingPreferredBackground);
        } else if (!hasRealWallpaperLoaded && !queue.isEmpty()) {
            rotateToNext();
        } else {
            scheduleRotationIfAvailable();
        }

    }

    public void onPause() {
        isVisible = false;
        workGeneration.incrementAndGet();
        mainHandler.removeCallbacks(rotationRunnable);
        cancelPendingDecode();
        if (crossfadeAnimator != null) crossfadeAnimator.cancel();

    }

    public void onDestroy() {
        destroyed = true;
        onPause();
        clearWallpaperLayer(imageViewA, false);
        clearWallpaperLayer(imageViewB, false);
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
                    if (isVisible && !destroyed && generation == workGeneration.get()) {
                        applyCrossfade(bitmap, nextFile);
                    }
                    // The decoded bitmap is retained only by the active wallpaper layer.
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
                front.setAlpha(1f);
                front.setScaleX(1f);
                front.setScaleY(1f);
                // Drop the outgoing TV-sized bitmap after the top layer has fully faded in.
                clearWallpaperLayer(front, false);
                back.setAlpha(1f);
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
     * Decode near the TV viewport. Reuse a compatible preview already on disk (including one
     * left by an earlier version); otherwise resize during decode to avoid a large intermediate
     * bitmap and a second scaled copy.
     */
    private int wallpaperTargetWidth() {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        return metrics.widthPixels > 0 ? metrics.widthPixels : 1920;
    }

    private int wallpaperTargetHeight() {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        return metrics.heightPixels > 0 ? metrics.heightPixels : 1080;
    }

    private File wallpaperPreviewCacheFile(File source, int targetWidth, int targetHeight) {
        if (source == null) return null;
        try {
            File cacheDirectory = new File(activity.getCacheDir(), WALLPAPER_PREVIEW_CACHE_DIR);
            String identity = source.getCanonicalPath() + "|" + source.length() + "|"
                    + source.lastModified() + "|" + targetWidth + "x" + targetHeight + "|v1";
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                key.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            return new File(cacheDirectory, key + ".jpg");
        } catch (Exception e) {
            Log.w(TAG, "Could not identify wallpaper preview cache entry", e);
            return null;
        }
    }

    private Bitmap decodeCachedWallpaperPreview(File previewFile) {
        if (previewFile == null || !previewFile.isFile() || previewFile.length() <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        Bitmap cached = BitmapFactory.decodeFile(previewFile.getAbsolutePath(), options);
        if (cached != null) previewFile.setLastModified(System.currentTimeMillis());
        return cached;
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
    private Bitmap decodeSampledBitmap(File file) {
        try {
            if (Thread.currentThread().isInterrupted() || destroyed) return null;
            int reqWidth = wallpaperTargetWidth();
            int reqHeight = wallpaperTargetHeight();
            File previewFile = wallpaperPreviewCacheFile(file, reqWidth, reqHeight);
            Bitmap cached = decodeCachedWallpaperPreview(previewFile);
            if (cached != null) return cached;
            if (previewFile != null && previewFile.exists()) previewFile.delete();

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(file);
                return ImageDecoder.decodeBitmap(source, (decoder, info, sourceIgnored) -> {
                    if (Thread.currentThread().isInterrupted() || destroyed) {
                        decoder.setTargetSize(1, 1);
                        return;
                    }
                    int sourceWidth = Math.max(1, info.getSize().getWidth());
                    int sourceHeight = Math.max(1, info.getSize().getHeight());
                    double scale = Math.max(reqWidth / (double) sourceWidth,
                            reqHeight / (double) sourceHeight);
                    scale = Math.min(1d, scale);
                    int targetWidth = Math.max(1, (int) Math.ceil(sourceWidth * scale));
                    int targetHeight = Math.max(1, (int) Math.ceil(sourceHeight * scale));
                    decoder.setTargetSize(targetWidth, targetHeight);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                });
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (Thread.currentThread().isInterrupted() || destroyed) return null;
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
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
