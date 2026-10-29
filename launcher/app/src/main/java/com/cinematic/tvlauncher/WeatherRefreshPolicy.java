package com.cinematic.tvlauncher;

/** Pure refresh policy shared by the weather widget and JVM unit tests. */
final class WeatherRefreshPolicy {
    static final long REFRESH_INTERVAL_MS = 50L * 60L * 1000L;

    private WeatherRefreshPolicy() {}

    /** Refresh on first use, after 50 minutes, or when the wall clock moved backwards. */
    static boolean isRefreshDue(long lastAttemptTimestamp, long nowMillis) {
        return lastAttemptTimestamp <= 0L
                || nowMillis < lastAttemptTimestamp
                || nowMillis - lastAttemptTimestamp >= REFRESH_INTERVAL_MS;
    }
}
