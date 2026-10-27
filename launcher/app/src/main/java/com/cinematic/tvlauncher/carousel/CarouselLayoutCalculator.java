package com.cinematic.tvlauncher.carousel;

/**
 * CarouselLayoutCalculator — Pure mathematical and layout model for the bottom 3D carousel.
 * Fulfills MASTER_PROMPT Annex C.1 and D.3.
 * Fully unit-testable without Android framework dependencies.
 */
public class CarouselLayoutCalculator {

    public static final float SCALE_FOCUSED = 1.00f;
    public static final float SCALE_OFFSET_1 = 0.82f;
    public static final float SCALE_OFFSET_2 = 0.68f;
    public static final float SCALE_OFFSET_3 = 0.56f;

    public static final float ALPHA_FOCUSED = 1.00f;
    public static final float ALPHA_OFFSET_1 = 1.00f;
    public static final float ALPHA_OFFSET_2 = 0.75f;
    public static final float ALPHA_OFFSET_3 = 0.50f;

    public static final float ROTATION_Y_MAX_DEG = 12.0f; // Subtle 3D perspective per Annex C.1

    public static class CardTransform {
        public float scale;
        public float alpha;
        public float rotationY;
        public float translationX;
        public boolean visible;

        public CardTransform(float scale, float alpha, float rotationY, float translationX, boolean visible) {
            this.scale = scale;
            this.alpha = alpha;
            this.rotationY = rotationY;
            this.translationX = translationX;
            this.visible = visible;
        }
    }

    /**
     * Calculates the transform for a card at distance 'offset' from the focused item.
     * offset = 0 means the focused card.
     * offset = -1 is the immediate left neighbor, +1 is immediate right neighbor, etc.
     */
    public static CardTransform calculateTransform(int offset, float baseCardWidth, float cardSpacing) {
        int absOffset = Math.abs(offset);

        if (absOffset > 3) {
            return new CardTransform(0f, 0f, 0f, 0f, false);
        }

        float scale;
        float alpha;
        float rotationY;

        switch (absOffset) {
            case 0:
                scale = SCALE_FOCUSED;
                alpha = ALPHA_FOCUSED;
                rotationY = 0f;
                break;
            case 1:
                scale = SCALE_OFFSET_1;
                alpha = ALPHA_OFFSET_1;
                rotationY = (offset < 0 ? 8.0f : -8.0f);
                break;
            case 2:
                scale = SCALE_OFFSET_2;
                alpha = ALPHA_OFFSET_2;
                rotationY = (offset < 0 ? 12.0f : -12.0f);
                break;
            case 3:
                scale = SCALE_OFFSET_3;
                alpha = ALPHA_OFFSET_3;
                rotationY = (offset < 0 ? 12.0f : -12.0f);
                break;
            default:
                scale = 0f;
                alpha = 0f;
                rotationY = 0f;
                break;
        }

        // Calculate translation relative to center
        // Cumulative distance with spacing
        float translationX = 0f;
        if (offset != 0) {
            float sign = offset > 0 ? 1.0f : -1.0f;
            float step = (baseCardWidth * 0.85f) + cardSpacing;
            translationX = sign * (absOffset * step);
        }

        return new CardTransform(scale, alpha, rotationY, translationX, true);
    }

    /** Returns the middle item's index for the initially centered carousel selection. */
    public static int centerIndexForCount(int totalItems) {
        if (totalItems <= 0) return 0;
        return totalItems / 2;
    }

    /**
     * Clamps an index within [0, totalItems - 1].
     * Annex D.3: "Left/Right: Move one carousel item; wrap-around disabled (stop at ends)".
     */
    public static int clampIndex(int targetIndex, int totalItems) {
        if (totalItems <= 0) return 0;
        if (targetIndex < 0) return 0;
        if (targetIndex >= totalItems) return totalItems - 1;
        return targetIndex;
    }

    /**
     * Determines whether move left is allowed.
     */
    public static boolean canMoveLeft(int currentIndex) {
        return currentIndex > 0;
    }

    /**
     * Determines whether move right is allowed.
     */
    public static boolean canMoveRight(int currentIndex, int totalItems) {
        return currentIndex < (totalItems - 1);
    }
}
