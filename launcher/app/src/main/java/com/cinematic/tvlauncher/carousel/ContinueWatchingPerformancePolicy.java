package com.cinematic.tvlauncher.carousel;

/**
 * Bounds artwork and provider work for the compact Continue Watching wheel.
 * Kept pure Java so decode sizing and refresh cadence can be regression-tested.
 */
public final class ContinueWatchingPerformancePolicy {
    public static final int MAX_ARTWORK_WIDTH = 320;
    public static final int MAX_ARTWORK_HEIGHT = 180;
    public static final long PROVIDER_REFRESH_INTERVAL_MS = 5_000L;

    private ContinueWatchingPerformancePolicy() {}

    /** Chooses a power-of-two sample that keeps poster decode within the wheel's 2x target. */
    public static int calculateArtworkSampleSize(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1;
        int sampleSize = 1;
        while ((sourceWidth / sampleSize > MAX_ARTWORK_WIDTH
                || sourceHeight / sampleSize > MAX_ARTWORK_HEIGHT)
                && sampleSize < (1 << 29)) {
            sampleSize <<= 1;
        }
        return sampleSize;
    }
}
