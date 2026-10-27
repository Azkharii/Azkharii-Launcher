package com.cinematic.tvlauncher;

/** Pure display-mode policy, testable without Android hardware. */
public final class DisplayModeSelector {
    private DisplayModeSelector() {}

    public static int choosePreferredModeId(int currentModeId, int currentWidth, int currentHeight,
            float currentRefreshRate, float targetRefreshRate, Mode[] modes) {
        if (modes == null || modes.length == 0 || currentRefreshRate >= targetRefreshRate - 0.5f) {
            return currentModeId;
        }
        Mode bestWithinTarget = null;
        Mode bestAboveTarget = null;
        for (Mode mode : modes) {
            if (mode == null || mode.width != currentWidth || mode.height != currentHeight
                    || mode.refreshRate <= currentRefreshRate + 0.5f) continue;
            if (mode.refreshRate <= targetRefreshRate + 0.5f) {
                if (bestWithinTarget == null || mode.refreshRate > bestWithinTarget.refreshRate) bestWithinTarget = mode;
            } else if (bestAboveTarget == null || mode.refreshRate < bestAboveTarget.refreshRate) {
                bestAboveTarget = mode;
            }
        }
        if (bestWithinTarget != null) return bestWithinTarget.modeId;
        return bestAboveTarget == null ? currentModeId : bestAboveTarget.modeId;
    }

    public static final class Mode {
        public final int modeId;
        public final int width;
        public final int height;
        public final float refreshRate;
        public Mode(int modeId, int width, int height, float refreshRate) {
            this.modeId = modeId; this.width = width; this.height = height; this.refreshRate = refreshRate;
        }
    }
}
