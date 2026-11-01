package com.cinematic.tvlauncher.ui;

import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Restrained liquid-glass treatment for the app-library heading only.
 * A translucent cool-neutral fill, fine refracted edge and soft depth shadow keep the
 * glyphs readable without the saturated cyan halo or inflated bubble-like outline.
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
            // Narrow low-opacity contour suggests a polished glass edge without expanding
            // the glyph silhouette or producing a detached neon glow.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(0.65f));
            paint.setShader(new LinearGradient(
                    0f, 0f, 0f, height,
                    new int[] {0x557F96A8, 0x8AFFFFFF, 0x55718A9E},
                    null, Shader.TileMode.CLAMP));
            paint.clearShadowLayer();
            super.onDraw(canvas);

            // Near-neutral translucent fill keeps the title readable over bright and dark
            // wallpapers; the low-saturation gradient provides the glass depth.
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(0f);
            paint.setShader(new LinearGradient(
                    0f, 0f, 0f, height,
                    new int[] {0xEEF7FAFD, 0xDADDE5ED, 0xEAF1F5FA},
                    null, Shader.TileMode.CLAMP));
            paint.setShadowLayer(dp(1.1f), 0f, dp(0.8f), 0x55030A12);
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
