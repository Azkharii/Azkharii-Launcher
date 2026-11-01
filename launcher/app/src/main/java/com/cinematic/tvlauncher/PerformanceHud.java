package com.cinematic.tvlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Debug;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;


/**
 * Tiny foreground-only diagnostic HUD. Samples process CPU every three seconds and PSS
 * every fifteen seconds; it is stopped whenever the launcher is not in the foreground.
 */
final class PerformanceHud {
    private static final long SAMPLE_INTERVAL_MS = 3000L;
    private static final long PSS_SAMPLE_INTERVAL_MS = 15_000L;

    private final Activity activity;
    private final TextView label;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long previousCpuMs = -1L;
    private long previousElapsedMs = -1L;
    private long lastPssSampleElapsedMs = -1L;
    private int lastPssKb = -1;
    private boolean running;
    private boolean closed;

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (!running || closed || activity.isFinishing() || activity.isDestroyed()) return;
            sampleAndRender();
            if (running && !closed) handler.postDelayed(this, SAMPLE_INTERVAL_MS);
        }
    };

    PerformanceHud(Activity activity, FrameLayout parent) {
        this.activity = activity;
        label = new TextView(activity);
        label.setTextColor(Color.WHITE);
        label.setTextSize(9f);
        label.setTypeface(Typeface.MONOSPACE);
        label.setIncludeFontPadding(false);
        label.setPadding(dp(8), dp(5), dp(8), dp(5));
        label.setText("RAM -- MiB\nCPU --%");
        label.setVisibility(View.GONE);
        label.setContentDescription("Consumo del launcher: memoria PSS y CPU del proceso");
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        label.setFocusable(false);
        label.setClickable(false);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(0xB8090C14);
        background.setCornerRadius(dp(5));
        background.setStroke(dp(1), 0x6678CFFF);
        label.setBackground(background);
        label.setElevation(dp(4));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT);
        params.leftMargin = dp(12);
        params.bottomMargin = dp(12);
        parent.addView(label, params);
    }

    void start() {
        if (closed) return;
        label.setVisibility(View.VISIBLE);
        if (running) return;
        running = true;
        previousCpuMs = Process.getElapsedCpuTime();
        previousElapsedMs = SystemClock.elapsedRealtime();
        lastPssSampleElapsedMs = -1L;
        lastPssKb = -1;
        sampleAndRender();
        handler.removeCallbacks(sampler);
        handler.postDelayed(sampler, SAMPLE_INTERVAL_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(sampler);
        previousCpuMs = -1L;
        previousElapsedMs = -1L;
        lastPssSampleElapsedMs = -1L;
        lastPssKb = -1;
        // Hide the entire diagnostic HUD outside the launcher home surface; no stale
        // RAM number should appear to be a continuing measurement while sampling is stopped.
        label.setVisibility(View.GONE);
        label.setText("RAM -- MiB\nCPU --%");
    }

    void close() {
        stop();
        closed = true;
        if (label.getParent() instanceof FrameLayout) {
            ((FrameLayout) label.getParent()).removeView(label);
        }
    }

    private void sampleAndRender() {
        long elapsed = SystemClock.elapsedRealtime();
        long cpu = Process.getElapsedCpuTime();
        long interval = previousElapsedMs < 0L ? 0L : elapsed - previousElapsedMs;
        long cpuDelta = previousCpuMs < 0L ? -1L : cpu - previousCpuMs;
        previousElapsedMs = elapsed;
        previousCpuMs = cpu;

        int cpuPercent = interval > 0L && cpuDelta >= 0L
                ? Math.max(0, Math.round(cpuDelta * 100f / interval)) : -1;

        if (lastPssSampleElapsedMs < 0L
                || elapsed - lastPssSampleElapsedMs >= PSS_SAMPLE_INTERVAL_MS) {
            try {
                Debug.MemoryInfo memoryInfo = new Debug.MemoryInfo();
                Debug.getMemoryInfo(memoryInfo);
                lastPssKb = memoryInfo.getTotalPss();
            } catch (RuntimeException ignored) {
                // Keep the HUD alive if this firmware does not provide process memory details.
            }
            lastPssSampleElapsedMs = elapsed;
        }

        StringBuilder text = new StringBuilder(40);
        text.append("RAM ");
        if (lastPssKb >= 0) text.append(Math.round(lastPssKb / 1024f)).append(" MiB");
        else text.append("-- MiB");
        text.append('\n').append("CPU ");
        if (cpuPercent >= 0) text.append(cpuPercent).append('%');
        else text.append("--%");
        text.append("  · 3s");
        label.setText(text.toString());
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
