package com.cinematic.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Samples the dominant chromatic family of an application's icon for the
 * navigation marker only. It never applies a tint or changes the source drawable.
 */
public final class IconAccentSampler {
    private static final int SAMPLE_SIZE = 32;
    private static final int HUE_BINS = 24;
    private static final int FALLBACK = Color.rgb(168, 205, 220);

    private IconAccentSampler() {}

    public static int sample(Drawable icon) {
        if (icon == null) return FALLBACK;

        Bitmap bitmap = null;
        Rect originalBounds = new Rect(icon.getBounds());
        try {
            bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            icon.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
            icon.draw(canvas);

            long[] counts = new long[HUE_BINS];
            long[] red = new long[HUE_BINS];
            long[] green = new long[HUE_BINS];
            long[] blue = new long[HUE_BINS];
            long neutralRed = 0L, neutralGreen = 0L, neutralBlue = 0L, neutralCount = 0L;
            float[] hsv = new float[3];

            for (int y = 0; y < SAMPLE_SIZE; y++) {
                for (int x = 0; x < SAMPLE_SIZE; x++) {
                    int pixel = bitmap.getPixel(x, y);
                    int alpha = Color.alpha(pixel);
                    if (alpha < 96) continue;

                    int r = Color.red(pixel);
                    int g = Color.green(pixel);
                    int b = Color.blue(pixel);
                    int brightest = Math.max(r, Math.max(g, b));
                    if (brightest < 18) continue;

                    neutralRed += r;
                    neutralGreen += g;
                    neutralBlue += b;
                    neutralCount++;

                    Color.colorToHSV(pixel, hsv);
                    if (hsv[1] < 0.18f || hsv[2] < 0.12f || hsv[2] > 0.97f) continue;
                    int bin = Math.min(HUE_BINS - 1, (int) (hsv[0] * HUE_BINS / 360f));
                    counts[bin]++;
                    red[bin] += r;
                    green[bin] += g;
                    blue[bin] += b;
                }
            }

            int dominantBin = -1;
            for (int i = 0; i < HUE_BINS; i++) {
                if (dominantBin < 0 || counts[i] > counts[dominantBin]) dominantBin = i;
            }

            if (dominantBin >= 0 && counts[dominantBin] > 0L) {
                int color = Color.rgb(
                        (int) (red[dominantBin] / counts[dominantBin]),
                        (int) (green[dominantBin] / counts[dominantBin]),
                        (int) (blue[dominantBin] / counts[dominantBin]));
                return keepIconHueVisible(color);
            }
            if (neutralCount > 0L) {
                return keepIconHueVisible(Color.rgb(
                        (int) (neutralRed / neutralCount),
                        (int) (neutralGreen / neutralCount),
                        (int) (neutralBlue / neutralCount)));
            }
            return FALLBACK;
        } catch (RuntimeException ignored) {
            return FALLBACK;
        } finally {
            icon.setBounds(originalBounds);
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static int keepIconHueVisible(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] >= 0.18f) {
            hsv[1] = Math.min(0.92f, Math.max(0.34f, hsv[1]));
        } else {
            // For monochrome icons retain a neutral marker, but keep it visible
            // against the white outline used by the launcher navigation dot.
            hsv[1] = Math.min(0.12f, hsv[1]);
        }
        hsv[2] = Math.min(0.90f, Math.max(0.48f, hsv[2]));
        return Color.HSVToColor(hsv);
    }
}
