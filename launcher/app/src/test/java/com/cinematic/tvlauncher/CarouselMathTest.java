package com.cinematic.tvlauncher;

import com.cinematic.tvlauncher.carousel.CarouselLayoutCalculator;
import com.cinematic.tvlauncher.carousel.CarouselLayoutCalculator.CardTransform;

/**
 * Unit tests for CarouselLayoutCalculator (Annex C.1, D.3).
 */
public class CarouselMathTest {

    public static void runTests() {
        testFocusedCardTransform();
        testNeighborTransforms();
        testHiddenCardsBeyondOffsetThree();
        testIndexClampingAndBoundaries();
        testDefaultCenterIndex();
        System.out.println("  [PASS] CarouselMathTest: all 5 tests passed.");
    }

    private static void testFocusedCardTransform() {
        CardTransform t = CarouselLayoutCalculator.calculateTransform(0, 200f, 20f);
        assert t.visible : "Focused card must be visible";
        assert Math.abs(t.scale - 1.00f) < 0.001f : "Focused scale must be 1.00";
        assert Math.abs(t.alpha - 1.00f) < 0.001f : "Focused alpha must be 1.00";
        assert Math.abs(t.rotationY - 0.0f) < 0.001f : "Focused card should have 0 Y-rotation";
        assert Math.abs(t.translationX - 0.0f) < 0.001f : "Focused card translationX must be 0";
    }

    private static void testNeighborTransforms() {
        // Offset 1
        CardTransform t1 = CarouselLayoutCalculator.calculateTransform(1, 200f, 20f);
        assert t1.visible;
        assert Math.abs(t1.scale - 0.82f) < 0.001f : "Offset 1 scale must be 0.82";
        assert Math.abs(t1.alpha - 1.00f) < 0.001f : "Offset 1 alpha must be 1.00";
        assert t1.rotationY == -8.0f : "Right neighbor should have negative rotation";
        assert t1.translationX > 0 : "Right neighbor translationX must be positive";

        // Offset -1
        CardTransform tm1 = CarouselLayoutCalculator.calculateTransform(-1, 200f, 20f);
        assert tm1.rotationY == 8.0f : "Left neighbor should have positive rotation";
        assert tm1.translationX < 0 : "Left neighbor translationX must be negative";

        // Offset 2
        CardTransform t2 = CarouselLayoutCalculator.calculateTransform(2, 200f, 20f);
        assert Math.abs(t2.scale - 0.68f) < 0.001f : "Offset 2 scale must be 0.68";
        assert Math.abs(t2.alpha - 0.75f) < 0.001f : "Offset 2 alpha must be 0.75";

        // Offset 3
        CardTransform t3 = CarouselLayoutCalculator.calculateTransform(3, 200f, 20f);
        assert Math.abs(t3.scale - 0.56f) < 0.001f : "Offset 3 scale must be 0.56";
        assert Math.abs(t3.alpha - 0.50f) < 0.001f : "Offset 3 alpha must be 0.50";
    }

    private static void testHiddenCardsBeyondOffsetThree() {
        CardTransform t4 = CarouselLayoutCalculator.calculateTransform(4, 200f, 20f);
        assert !t4.visible : "Offset 4 card must not be visible";

        CardTransform tm5 = CarouselLayoutCalculator.calculateTransform(-5, 200f, 20f);
        assert !tm5.visible : "Offset -5 card must not be visible";
    }

    private static void testDefaultCenterIndex() {
        assert CarouselLayoutCalculator.centerIndexForCount(0) == 0 : "Empty row has no center index";
        assert CarouselLayoutCalculator.centerIndexForCount(1) == 0 : "Single app uses the only slot";
        assert CarouselLayoutCalculator.centerIndexForCount(2) == 1 : "Two apps use a middle slot";
        assert CarouselLayoutCalculator.centerIndexForCount(4) == 2 : "Four apps use the right middle slot";
        assert CarouselLayoutCalculator.centerIndexForCount(5) == 2 : "Five recent apps initially center the third app";
    }

    private static void testIndexClampingAndBoundaries() {
        int total = 10;
        assert CarouselLayoutCalculator.clampIndex(-5, total) == 0 : "Negative index clamps to 0";
        assert CarouselLayoutCalculator.clampIndex(0, total) == 0;
        assert CarouselLayoutCalculator.clampIndex(9, total) == 9;
        assert CarouselLayoutCalculator.clampIndex(15, total) == 9 : "Overflow index clamps to total-1";

        assert !CarouselLayoutCalculator.canMoveLeft(0) : "Cannot move left at 0";
        assert CarouselLayoutCalculator.canMoveLeft(1) : "Can move left at 1";
        assert !CarouselLayoutCalculator.canMoveRight(9, total) : "Cannot move right at end";
        assert CarouselLayoutCalculator.canMoveRight(8, total) : "Can move right at 8";
    }
}
