package com.cinematic.tvlauncher.background;

/**
 * Pure geometry policy for matching a wallpaper source to the launcher's center-cropped
 * viewport. Kept independent of Android graphics so aspect-ratio boundaries are testable.
 */
public final class WallpaperCropPolicy {

    private WallpaperCropPolicy() {}

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
