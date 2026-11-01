package com.cinematic.tvlauncher;

import com.cinematic.tvlauncher.background.WallpaperCropPolicy;

/** Regression tests for the wallpaper crop passed to ImageDecoder. */
public class WallpaperCropPolicyTest {

    public static void runTests() {
        testFourByThreeSourceCropsTopAndBottom();
        testUltrawideSourceCropsLeftAndRight();
        testMatchingAspectKeepsWholeImage();
        testSmallSourceIsNotExpandedByCropPolicy();
        testPortraitSourceUsesCenteredLandscapeWindow();
        System.out.println("  [PASS] WallpaperCropPolicyTest: all 5 tests passed.");
    }

    private static void testFourByThreeSourceCropsTopAndBottom() {
        int[] crop = WallpaperCropPolicy.centerCropRect(4032, 3024, 2560, 1440);
        assert crop[0] == 0 && crop[2] == 4032 : "4:3 crop must keep full source width";
        assert crop[1] == 378 && crop[3] == 2646 : "4:3 crop must be centered vertically";
    }

    private static void testUltrawideSourceCropsLeftAndRight() {
        int[] crop = WallpaperCropPolicy.centerCropRect(2520, 1080, 1920, 1080);
        assert crop[0] == 300 && crop[2] == 2220 : "Ultrawide crop must be centered horizontally";
        assert crop[1] == 0 && crop[3] == 1080 : "Ultrawide crop must keep full source height";
    }

    private static void testMatchingAspectKeepsWholeImage() {
        int[] crop = WallpaperCropPolicy.centerCropRect(3840, 2160, 2560, 1440);
        assert crop[0] == 0 && crop[1] == 0;
        assert crop[2] == 3840 && crop[3] == 2160;
    }

    private static void testSmallSourceIsNotExpandedByCropPolicy() {
        int[] crop = WallpaperCropPolicy.centerCropRect(1280, 720, 2560, 1440);
        assert crop[0] == 0 && crop[1] == 0;
        assert crop[2] == 1280 && crop[3] == 720;
    }

    private static void testPortraitSourceUsesCenteredLandscapeWindow() {
        int[] crop = WallpaperCropPolicy.centerCropRect(1080, 1920, 1920, 1080);
        assert crop[0] == 0 && crop[2] == 1080;
        assert crop[1] == 656 && crop[3] == 1264;
    }
}
