package com.cinematic.tvlauncher;

/** Pure scheduling policy for foreground release checks and bounded retry backoff. */
public final class UpdateCheckPolicy {
    public static final long CHECK_INTERVAL_MS = 30L * 60L * 1000L;
    public static final long RETRY_BASE_MS = 5L * 60L * 1000L;
    public static final long RETRY_MAX_MS = 30L * 60L * 1000L;

    private UpdateCheckPolicy() {}

    /**
     * Returns how long to wait before another check. Failures take precedence over the
     * normal success interval so an unavailable network can recover without waiting 30 minutes.
     */
    public static long nextDelayMillis(long now, long lastSuccessAt, long lastAttemptAt,
            int consecutiveFailures) {
        if (consecutiveFailures > 0) {
            int exponent = Math.min(3, consecutiveFailures - 1);
            long retryDelay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS * (1L << exponent));
            long dueAt = lastAttemptAt <= 0L ? now : lastAttemptAt + retryDelay;
            return Math.max(0L, dueAt - now);
        }
        if (lastSuccessAt <= 0L) return 0L;
        return Math.max(0L, lastSuccessAt + CHECK_INTERVAL_MS - now);
    }
}
