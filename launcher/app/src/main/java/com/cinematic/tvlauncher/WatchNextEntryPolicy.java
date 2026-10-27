package com.cinematic.tvlauncher;

/** Pure policy helpers for consuming (never publishing) system Watch Next rows. */
public final class WatchNextEntryPolicy {
    private WatchNextEntryPolicy() {}

    /** A real provider title can be displayed even when this TV exposes no supported resume route. */
    public static boolean hasDisplayableTitle(String title) {
        if (title == null) return false;
        String normalizedTitle = title.trim();
        return !normalizedTitle.isEmpty()
                && !"unknown".equalsIgnoreCase(normalizedTitle)
                && !"null".equalsIgnoreCase(normalizedTitle);
    }

    /** A resume card requires a real title and at least one usable route to its publisher. */
    public static boolean hasDisplayableData(String title, String intentUri) {
        return hasDisplayableData(title, intentUri, null);
    }

    /**
     * Accepts a published resume URI or a publisher package that the caller has independently
     * verified is installed and launchable. This does not fabricate a deep link or playback state.
     */
    public static boolean hasDisplayableData(String title, String intentUri, String packageName) {
        if (title == null) return false;
        String normalizedTitle = title.trim();
        String normalizedIntent = intentUri == null ? "" : intentUri.trim();
        String normalizedPackage = packageName == null ? "" : packageName.trim();
        return !normalizedTitle.isEmpty()
                && !"unknown".equalsIgnoreCase(normalizedTitle)
                && !"null".equalsIgnoreCase(normalizedTitle)
                && (!normalizedIntent.isEmpty() || !normalizedPackage.isEmpty());
    }

    /**
     * Prefer the publisher's poster art; use its thumbnail only when poster art is absent.
     * Blank values are treated as missing. This never constructs or guesses an image URL.
     */
    public static String preferredArtworkUri(String posterArtUri, String thumbnailUri) {
        if (posterArtUri != null && !posterArtUri.trim().isEmpty()) return posterArtUri.trim();
        if (thumbnailUri != null && !thumbnailUri.trim().isEmpty()) return thumbnailUri.trim();
        return null;
    }

    /** Percentage for a progress strip, or -1 when the provider has no valid progress data. */
    public static int progressPercent(long positionMillis, long durationMillis) {
        if (durationMillis <= 0L || positionMillis < 0L) return -1;
        long clamped = Math.max(0L, Math.min(positionMillis, durationMillis));
        return (int) Math.max(0L,
                Math.min(100L, Math.round((clamped * 100.0d) / durationMillis)));
    }
}
