package com.cinematic.tvlauncher.background;

/**
 * Pure geometry policy for matching a wallpaper source to the launcher's center-cropped
 * viewport. Kept independent of Android graphics so aspect-ratio boundaries are testable.
 */
public final class WallpaperCropPolicy {

    private WallpaperCropPolicy() {}


    /**
     * Chooses a power-of-two sample for region decoding. A small 12.5% overshoot is accepted
     * so common 4K-to-2K cases can use half-resolution decode instead of retaining 4K pixels.
     */
    public static int calculateRegionSampleSize(int cropWidth, int cropHeight,
                                                int targetWidth, int targetHeight) {
        double widthRatio = Math.max(1, cropWidth) / (double) Math.max(1, targetWidth);
        double heightRatio = Math.max(1, cropHeight) / (double) Math.max(1, targetHeight);
        double ratio = Math.min(widthRatio, heightRatio);
        int sampleSize = 1;
        while (ratio >= sampleSize * 1.75d) {
            if (sampleSize > (1 << 28)) break;
            sampleSize *= 2;
        }
        return sampleSize;
    }

    /** Returns [left, top, right, bottom] in source-image pixels. */
    public static int[] centerCropRect(int sourceWidth, int sourceHeight,
                                       int targetWidth, int targetHeight) {
        int width = Math.max(1, sourceWidth);
        int height = Math.max(1, sourceHeight);
        double targetAspect = Math.max(1, targetWidth) / (double) Math.max(1, targetHeight);
        double sourceAspect = width / (double) height;

        int left = 0;
        int top = 0;
        int right = width;
        int bottom = height;
        if (sourceAspect > targetAspect) {
            int cropWidth = Math.max(1, Math.min(width,
                    (int) Math.round(height * targetAspect)));
            left = (width - cropWidth) / 2;
            right = left + cropWidth;
        } else if (sourceAspect < targetAspect) {
            int cropHeight = Math.max(1, Math.min(height,
                    (int) Math.round(width / targetAspect)));
            top = (height - cropHeight) / 2;
            bottom = top + cropHeight;
        }
        return new int[] {left, top, right, bottom};
    }
}
