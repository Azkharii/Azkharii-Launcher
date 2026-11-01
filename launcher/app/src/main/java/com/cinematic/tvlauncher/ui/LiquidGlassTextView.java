package com.cinematic.tvlauncher.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Lightweight outlined/glass text for the app-library heading.
 * Draws a crisp pale edge with a restrained cyan halo and a low-opacity fill,
 * keeping the original text metrics, font, weight and layout unchanged.
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

        // Outer glass edge: mostly transparent interior, bright enough to stay readable.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(0.95f));
        paint.setColor(Color.argb(244, 235, 249, 255));
        paint.setShadowLayer(dp(2.6f), 0f, 0f, 0xB05BC8EE);
        super.onDraw(canvas);

        // A faint inner tint prevents the outlined glyphs from disappearing on bright images.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(36, 236, 248, 255));
        paint.setShadowLayer(dp(3f), 0f, dp(1f), 0xB0000000);
        super.onDraw(canvas);

        paint.setColor(originalColor);
        paint.setStyle(originalStyle);
        paint.setStrokeWidth(originalStrokeWidth);
        paint.setStrokeJoin(originalJoin);
        paint.setStrokeCap(originalCap);
        // Match the heading's existing black drop-shadow after restoring its original paint.
        paint.setShadowLayer(dp(4f), dp(1f), dp(2f), 0xC0000000);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
