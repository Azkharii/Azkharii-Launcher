package com.cinematic.tvlauncher;

import com.cinematic.tvlauncher.background.WallpaperCropPolicy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Regression tests for the wallpaper crop passed to ImageDecoder. */
public class WallpaperCropPolicyTest {

    public static void runTests() {
        testFourByThreeSourceCropsTopAndBottom();
        testUltrawideSourceCropsLeftAndRight();
        testMatchingAspectKeepsWholeImage();
        testSmallSourceIsNotExpandedByCropPolicy();
        testPortraitSourceUsesCenteredLandscapeWindow();
        testFourKSourceUsesHalfResolutionRegionDecode();
        testVeryLargeSourceUsesQuarterResolutionRegionDecode();
        testNearViewportSourceKeepsFullResolution();
        testOpaqueRgbPngUsesRgb565();
        testOpaqueGrayscalePngUsesRgb565();
        testTruecolorTransparencyChunkKeepsArgb();
        testGrayscaleTransparencyChunkKeepsArgb();
        testAlphaAndPalettePngsKeepArgb();
        testMalformedPngKeepsArgb();
        System.out.println("  [PASS] WallpaperCropPolicyTest: all 14 tests passed.");
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

    private static void testFourKSourceUsesHalfResolutionRegionDecode() {
        assert WallpaperCropPolicy.calculateRegionSampleSize(3840, 2160, 2048, 1152) == 2;
    }

    private static void testVeryLargeSourceUsesQuarterResolutionRegionDecode() {
        assert WallpaperCropPolicy.calculateRegionSampleSize(7680, 4320, 2048, 1152) == 4;
    }

    private static void testNearViewportSourceKeepsFullResolution() {
        assert WallpaperCropPolicy.calculateRegionSampleSize(2560, 1440, 2048, 1152) == 1;
    }

    private static void testOpaqueRgbPngUsesRgb565() {
        assert !WallpaperCropPolicy.hasTransparency(createPng(2, false));
    }

    private static void testOpaqueGrayscalePngUsesRgb565() {
        assert !WallpaperCropPolicy.hasTransparency(createPng(0, false));
    }

    private static void testTruecolorTransparencyChunkKeepsArgb() {
        assert WallpaperCropPolicy.hasTransparency(createPng(2, true));
    }

    private static void testGrayscaleTransparencyChunkKeepsArgb() {
        assert WallpaperCropPolicy.hasTransparency(createPng(0, true));
    }

    private static void testAlphaAndPalettePngsKeepArgb() {
        assert WallpaperCropPolicy.hasTransparency(createPng(4, false));
        assert WallpaperCropPolicy.hasTransparency(createPng(6, false));
        assert WallpaperCropPolicy.hasTransparency(createPng(3, false));
    }

    private static void testMalformedPngKeepsArgb() {
        File file = null;
        try {
            file = File.createTempFile("cinematic-invalid-", ".png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(new byte[] {1, 2, 3, 4});
            }
            assert WallpaperCropPolicy.hasTransparency(file);
        } catch (IOException e) {
            throw new AssertionError("Could not create malformed PNG fixture", e);
        } finally {
            if (file != null && file.exists()) file.delete();
        }
    }

    private static File createPng(int colorType, boolean transparentChunk) {
        File file = null;
        try {
            file = File.createTempFile("cinematic-png-policy-", ".png");
            file.deleteOnExit();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(new byte[] {
                        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
                });
                byte[] ihdr = new byte[13];
                ihdr[3] = 1;
                ihdr[7] = 1;
                ihdr[8] = 8;
                ihdr[9] = (byte) colorType;
                writePngChunk(output, "IHDR", ihdr);
                if (transparentChunk) writePngChunk(output, "tRNS", new byte[] {0});
                writePngChunk(output, "IDAT", new byte[0]);
                writePngChunk(output, "IEND", new byte[0]);
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                bytes.writeTo(output);
            }
            return file;
        } catch (IOException e) {
            if (file != null) file.delete();
            throw new AssertionError("Could not create PNG test fixture", e);
        }
    }

    private static void writePngChunk(DataOutputStream output, String name, byte[] data)
            throws IOException {
        output.writeInt(data.length);
        output.write(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(data);
        output.writeInt(0);
    }
}
