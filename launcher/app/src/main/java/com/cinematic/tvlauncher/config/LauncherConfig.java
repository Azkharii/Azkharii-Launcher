package com.cinematic.tvlauncher.config;

import android.content.Context;
import android.content.res.Resources;
import com.cinematic.tvlauncher.R;

/**
 * LauncherConfig — Manages runtime configuration for the cinematic TV launcher.
 * Implements defaults and constraints specified in MASTER_PROMPT Annex C and D.
 */
public class LauncherConfig {

    public static final int DEFAULT_ROTATION_INTERVAL_SEC = 10;
    public static final int DEFAULT_CROSSFADE_DURATION_MS = 400;
    private int rotationIntervalSec = DEFAULT_ROTATION_INTERVAL_SEC;
    private int crossfadeDurationMs = DEFAULT_CROSSFADE_DURATION_MS;

    public LauncherConfig() {
        // Default constructor
    }

    public static LauncherConfig loadFromResources(Context context) {
        LauncherConfig config = new LauncherConfig();
        if (context == null) return config;

        Resources res = context.getResources();
        try {
            config.rotationIntervalSec = res.getInteger(R.integer.background_rotation_interval_sec);
            config.crossfadeDurationMs = res.getInteger(R.integer.background_crossfade_duration_ms);
        } catch (Exception ignored) {
            // Fall back to constants
        }
        return config;
    }

    public int getRotationIntervalSec() { return rotationIntervalSec; }
    public void setRotationIntervalSec(int val) { this.rotationIntervalSec = Math.max(3, val); }

    public int getCrossfadeDurationMs() { return crossfadeDurationMs; }
    public void setCrossfadeDurationMs(int val) { this.crossfadeDurationMs = Math.max(100, Math.min(2000, val)); }


}
