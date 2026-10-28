package com.cinematic.tvlauncher;

import com.cinematic.tvlauncher.config.LauncherConfig;

/**
 * Unit tests for LauncherConfig defaults and boundary validation (Annex C, D).
 */
public class LauncherConfigTest {

    public static void runTests() {
        testDefaultValues();
        testIntervalValidation();
        testCrossfadeValidation();
        System.out.println("  [PASS] LauncherConfigTest: all 3 tests passed.");
    }

    private static void testDefaultValues() {
        LauncherConfig config = new LauncherConfig();
        assert config.getRotationIntervalSec() == 10 : "Default rotation interval must be 10s";
        assert config.getCrossfadeDurationMs() == 600 : "Default crossfade must be 600ms";
        assert !config.isMotionEnabled() : "Continuous wallpaper motion must be disabled by default";
        assert config.getMotionDurationSec() == 40 : "Motion duration must be 40s";
    }

    private static void testIntervalValidation() {
        LauncherConfig config = new LauncherConfig();
        config.setRotationIntervalSec(1); // Below minimum
        assert config.getRotationIntervalSec() >= 3 : "Interval below minimum must be clamped";

        config.setRotationIntervalSec(15);
        assert config.getRotationIntervalSec() == 15;
    }

    private static void testCrossfadeValidation() {
        LauncherConfig config = new LauncherConfig();
        config.setCrossfadeDurationMs(50); // Below 100ms
        assert config.getCrossfadeDurationMs() >= 100;

        config.setCrossfadeDurationMs(5000); // Above 2000ms
        assert config.getCrossfadeDurationMs() <= 2000;
    }


}
