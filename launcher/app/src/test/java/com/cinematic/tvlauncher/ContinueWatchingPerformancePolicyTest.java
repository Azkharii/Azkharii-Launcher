package com.cinematic.tvlauncher;

import com.cinematic.tvlauncher.carousel.ContinueWatchingPerformancePolicy;

/** Regression tests for bounded Continue Watching poster and provider work. */
public final class ContinueWatchingPerformancePolicyTest {
    public static void runTests() {
        assert ContinueWatchingPerformancePolicy.calculateArtworkSampleSize(1920, 1080) == 8
                : "Full HD artwork should decode near 240x135";
        assert ContinueWatchingPerformancePolicy.calculateArtworkSampleSize(1280, 720) == 4
                : "720p artwork should decode near 320x180";
        assert ContinueWatchingPerformancePolicy.calculateArtworkSampleSize(640, 360) == 2
                : "Quarter-size artwork should decode near 320x180";
        assert ContinueWatchingPerformancePolicy.calculateArtworkSampleSize(320, 180) == 1
                : "Artwork at the target size should not be downsampled";
        assert ContinueWatchingPerformancePolicy.calculateArtworkSampleSize(0, 0) == 1
                : "Invalid bounds must use a safe sample";
        assert ContinueWatchingPerformancePolicy.PROVIDER_REFRESH_INTERVAL_MS == 5000L
                : "Provider refresh must be coalesced to a five-second minimum";
        System.out.println("  [PASS] ContinueWatchingPerformancePolicyTest: all 6 assertions passed.");
    }
}
