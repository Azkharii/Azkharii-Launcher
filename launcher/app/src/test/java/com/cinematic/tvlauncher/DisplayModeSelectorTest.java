package com.cinematic.tvlauncher;

public final class DisplayModeSelectorTest {
    public static void runTests() {
        testSelects120HzAtSameResolution();
        testDoesNotChangeResolution();
        testKeepsCurrentModeWhenNoFasterModeExists();
        testPrefers120HzOver144HzWhenBothAreOffered();
        System.out.println("  [PASS] DisplayModeSelectorTest: all 4 tests passed.");
    }
    private static void testSelects120HzAtSameResolution() {
        DisplayModeSelector.Mode[] modes = {
            new DisplayModeSelector.Mode(1,3840,2160,60f),
            new DisplayModeSelector.Mode(2,3840,2160,120f),
            new DisplayModeSelector.Mode(3,1920,1080,120f)};
        assert DisplayModeSelector.choosePreferredModeId(1,3840,2160,60f,120f,modes)==2;
    }
    private static void testDoesNotChangeResolution() {
        DisplayModeSelector.Mode[] modes = {
            new DisplayModeSelector.Mode(1,3840,2160,60f),
            new DisplayModeSelector.Mode(3,1920,1080,120f)};
        assert DisplayModeSelector.choosePreferredModeId(1,3840,2160,60f,120f,modes)==1;
    }
    private static void testKeepsCurrentModeWhenNoFasterModeExists() {
        DisplayModeSelector.Mode[] modes = {
            new DisplayModeSelector.Mode(1,1920,1080,60f),
            new DisplayModeSelector.Mode(2,1920,1080,50f)};
        assert DisplayModeSelector.choosePreferredModeId(1,1920,1080,60f,120f,modes)==1;
    }
    private static void testPrefers120HzOver144HzWhenBothAreOffered() {
        DisplayModeSelector.Mode[] modes = {
            new DisplayModeSelector.Mode(1,3840,2160,60f),
            new DisplayModeSelector.Mode(2,3840,2160,120f),
            new DisplayModeSelector.Mode(3,3840,2160,144f)};
        assert DisplayModeSelector.choosePreferredModeId(1,3840,2160,60f,120f,modes)==2;
        assert DisplayModeSelector.choosePreferredModeId(2,3840,2160,120f,120f,modes)==2;
    }
}
