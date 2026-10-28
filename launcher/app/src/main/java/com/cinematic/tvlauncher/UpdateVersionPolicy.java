package com.cinematic.tvlauncher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Release-tag parsing and compact display formatting kept independent of Android APIs. */
public final class UpdateVersionPolicy {
    // New tags keep the monotonic Android versionCode after '+', while the public version stays readable.
    // Older releases stored the same code as the last versionName component; both formats remain supported.
    private static final Pattern TRAILING_VERSION = Pattern.compile("(?:^|[.+-])([0-9]+)$");
    private static final long RELEASE_VERSION_CODE_OFFSET = 1_000_000_000L;
    private UpdateVersionPolicy() {}

    public static long parseVersionCodeFromTag(String tag) {
        if (tag == null) return -1L;
        String normalized = tag.trim();
        while (normalized.startsWith("v") || normalized.startsWith("V")) normalized = normalized.substring(1);
        Matcher matcher = TRAILING_VERSION.matcher(normalized);
        if (!matcher.find()) return -1L;
        try {
            long value = Long.parseLong(matcher.group(1));
            return value > 0L && value <= 2_100_000_000L ? value : -1L;
        } catch (NumberFormatException ignored) { return -1L; }
    }

    /** Formats release tags/versionNames for people; Android's internal versionCode is never shown. */
    public static String formatDisplayVersion(String rawVersion) {
        if (rawVersion == null) return "—";
        String normalized = rawVersion.trim();
        if (normalized.isEmpty()) return "—";
        String titlePrefix = "Cinematic TV Launcher ";
        if (normalized.startsWith(titlePrefix)) normalized = normalized.substring(titlePrefix.length()).trim();
        while (normalized.startsWith("v") || normalized.startsWith("V")) normalized = normalized.substring(1);

        // New tags look like v1.0.5+1000000005; the suffix is internal build metadata.
        int metadata = normalized.indexOf('+');
        if (metadata >= 0) normalized = normalized.substring(0, metadata);

        // Compact legacy names such as 1.0.1000000004 to 1.0.4 without changing versionCode.
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < normalized.length() - 1) {
            try {
                long last = Long.parseLong(normalized.substring(lastDot + 1));
                if (last >= RELEASE_VERSION_CODE_OFFSET && last < RELEASE_VERSION_CODE_OFFSET * 2L) {
                    normalized = normalized.substring(0, lastDot + 1) + (last - RELEASE_VERSION_CODE_OFFSET);
                }
            } catch (NumberFormatException ignored) {
                // Non-numeric suffixes are left readable rather than guessed or rewritten.
            }
        }
        return normalized.isEmpty() ? "—" : normalized;
    }

    public static boolean isNewer(long remote, long installed) {
        return remote > 0L && remote > installed;
    }
}
