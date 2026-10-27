package com.cinematic.tvlauncher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java comparison for release tags such as v1.0.167. */
public final class UpdateVersionPolicy {
    private static final Pattern TRAILING_VERSION = Pattern.compile("(?:^|\\.)([0-9]+)$");
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
    public static boolean isNewer(long remote, long installed) {
        return remote > 0L && remote > installed;
    }
}
