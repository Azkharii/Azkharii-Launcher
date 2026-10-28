package com.cinematic.tvlauncher.carousel;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;

/**
 * Lightweight poster-aware edge illumination shared by the app carousel, app library,
 * and Continue Watching. Eight sampled perimeter colors flow around the rounded frame.
 *
 * The shader is built when bounds change, not during draw(), so animation frames only
 * draw a few rounded strokes and do not allocate bitmaps, paints, or shaders.
 */
public final class AdaptiveGlowDrawable extends Drawable {
    private static final int DEFAULT_ACCENT = Color.rgb(112, 190, 255);
    private static final float[] POSITIONS = {
            0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1f
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final float density;
    private final float cornerRadius;
    private boolean focused;
    private final int[] perimeterColors = new int[8];
    private int[] clockwiseColors;
    private SweepGradient gradient;
    private int alpha = 255;

    /**
     * @param perimeterColors sample order: top-left, top, top-right, right,
     *                        bottom-right, bottom, bottom-left, left
     */
    public AdaptiveGlowDrawable(int[] perimeterColors, float density,
            float cornerRadiusDp, boolean focused) {
        this.density = Math.max(0.5f, density);
        this.cornerRadius = Math.max(1f, cornerRadiusDp) * this.density;
        this.focused = focused;
        paint.setStyle(Paint.Style.STROKE);
        setPerimeterColors(perimeterColors);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rebuildGradient();
    }

    /** Replaces the sampled palette after artwork finishes loading; safe outside draw(). */
    public void setPerimeterColors(int[] suppliedColors) {
        int length = suppliedColors == null ? 0 : suppliedColors.length;
        boolean changed = false;
        for (int i = 0; i < perimeterColors.length; i++) {
            int color = length == 0
                    ? DEFAULT_ACCENT : suppliedColors[Math.min(i, length - 1)];
            if (perimeterColors[i] != color) {
                perimeterColors[i] = color;
                changed = true;
            }
        }
        // Reuse the existing shader and arrays when artwork publishes an unchanged palette.
        if (!changed && clockwiseColors != null) return;

        // SweepGradient starts at the right edge and proceeds clockwise on Android's canvas.
        clockwiseColors = new int[] {
                perimeterColors[3], perimeterColors[4], perimeterColors[5], perimeterColors[6],
                perimeterColors[7], perimeterColors[0], perimeterColors[1], perimeterColors[2],
                perimeterColors[3]
        };
        rebuildGradient();
        invalidateSelf();
    }

    /** Switches between the subtle resting edge and the multi-stroke selected glow. */
    public void setFocused(boolean focused) {
        if (this.focused == focused) return;
        this.focused = focused;
        invalidateSelf();
    }

    private void rebuildGradient() {
        Rect bounds = getBounds();
        frame.set(bounds);
        if (!bounds.isEmpty() && clockwiseColors != null) {
            gradient = new SweepGradient(bounds.exactCenterX(), bounds.exactCenterY(),
                    clockwiseColors, POSITIONS);
            paint.setShader(gradient);
        } else {
            gradient = null;
            paint.setShader(null);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (gradient == null || frame.isEmpty()) return;
        if (!focused) {
            drawStroke(canvas, 1.1f, 1.4f, 68);
            return;
        }

        // The poster sits inset from the frame, exposing these three blended luminous edges.
        drawStroke(canvas, 2.8f, 5.2f, 58);
        drawStroke(canvas, 3.2f, 3.0f, 112);
        drawStroke(canvas, 3.7f, 1.35f, 238);
    }

    private void drawStroke(Canvas canvas, float insetDp, float strokeDp, int requestedAlpha) {
        float inset = insetDp * density;
        frame.inset(inset, inset);
        paint.setStrokeWidth(strokeDp * density);
        paint.setAlpha(Math.min(255, requestedAlpha * alpha / 255));
        float radius = Math.max(1f, cornerRadius - inset);
        canvas.drawRoundRect(frame, radius, radius, paint);
        frame.inset(-inset, -inset);
    }

    @Override
    public void setAlpha(int alpha) {
        int clamped = Math.max(0, Math.min(255, alpha));
        if (this.alpha == clamped) return;
        this.alpha = clamped;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        if (paint.getColorFilter() == colorFilter) return;
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
