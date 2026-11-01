package com.cinematic.tvlauncher.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Draws the app-library heading as outlined liquid-glass lettering.
 * The glyph interiors remain transparent; a cool edge gradient and a restrained
 * cyan halo keep the letters distinct over both bright and dark wallpapers.
 */
public final class LiquidGlassTextView extends TextView {

    public LiquidGlassTextView(Context context) {
        super(context);
    }

    public LiquidGlassTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LiquidGlassTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        TextPaint paint = getPaint();
        int originalColor = paint.getColor();
        Paint.Style originalStyle = paint.getStyle();
        float originalStrokeWidth = paint.getStrokeWidth();
        Paint.Join originalJoin = paint.getStrokeJoin();
        Paint.Cap originalCap = paint.getStrokeCap();
        Shader originalShader = paint.getShader();

        float height = Math.max(1f, getHeight());
        try {
            // Outer refracted edge: this is the visible glass contour, not a filled white font.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(2.0f));
            paint.setShader(new LinearGradient(
                    0f, 0f, 0f, height,
                    new int[] {0xC875E7FF, 0xF5FFFFFF, 0xC86CD7FF},
                    null, Shader.TileMode.CLAMP));
            paint.setShadowLayer(dp(4.2f), 0f, 0f, 0xB35AD8FF);
            super.onDraw(canvas);

            // Fine inner highlight sharpens the contour without filling the letter interior.
            paint.setStrokeWidth(dp(0.85f));
            paint.setShader(new LinearGradient(
                    0f, 0f, 0f, height,
                    new int[] {0xEAFBFFFF, 0xC8E7FAFF, 0xEAFBFFFF},
                    null, Shader.TileMode.CLAMP));
            paint.setShadowLayer(dp(1.4f), 0f, dp(0.3f), 0x90E4FAFF);
            super.onDraw(canvas);
        } finally {
            paint.setColor(originalColor);
            paint.setStyle(originalStyle);
            paint.setStrokeWidth(originalStrokeWidth);
            paint.setStrokeJoin(originalJoin);
            paint.setStrokeCap(originalCap);
            paint.setShader(originalShader);
            paint.clearShadowLayer();
        }
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
