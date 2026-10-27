package com.cinematic.tvlauncher;

public final class WatchNextEntryPolicyTest {
    public static void runTests() {
        check(WatchNextEntryPolicy.hasDisplayableData(
                "Movie", "intent:#Intent;action=android.intent.action.VIEW;end"),
                "row with a real title and provider resume intent is displayable");
        check(WatchNextEntryPolicy.hasDisplayableData("Episode", "https://example.com/episode/2"),
                "HTTP playback URI remains eligible");
        check(!WatchNextEntryPolicy.hasDisplayableData("Movie", ""),
                "row without the provider resume intent is not reproducible");
        check(!WatchNextEntryPolicy.hasDisplayableData("Movie", null),
                "null resume URI is not reproducible");
        check(!WatchNextEntryPolicy.hasDisplayableData("   ", "intent:#Intent;end"),
                "blank title must be skipped");
        check(!WatchNextEntryPolicy.hasDisplayableData(" UNKNOWN ", "intent:#Intent;end"),
                "placeholder title must be skipped case-insensitively");
        check(!WatchNextEntryPolicy.hasDisplayableData("Movie", "   "),
                "whitespace-only resume URI must be skipped");
        check(WatchNextEntryPolicy.hasDisplayableData("Movie", "", "com.example.player"),
                "real Watch Next row may fall back to its publisher app when no resume URI exists");
        check(!WatchNextEntryPolicy.hasDisplayableData("Movie", "", null),
                "row without a resume URI or publisher package must be skipped");
        check(!WatchNextEntryPolicy.hasDisplayableData("UNKNOWN", "", "com.example.player"),
                "placeholder title must remain ineligible even when a publisher package exists");
        check(!WatchNextEntryPolicy.hasDisplayableData("Movie", "   ", "  "),
                "blank URI and blank publisher package must be skipped");
        check("content://publisher/poster".equals(
                        WatchNextEntryPolicy.preferredArtworkUri(
                                " content://publisher/poster ", "content://publisher/thumbnail")),
                "poster art URI takes priority when present");
        check("content://publisher/thumbnail".equals(
                        WatchNextEntryPolicy.preferredArtworkUri("  ", "content://publisher/thumbnail")),
                "thumbnail URI is used when poster art is missing");
        check(WatchNextEntryPolicy.preferredArtworkUri(null, " ") == null,
                "missing poster and thumbnail URIs remain absent instead of inventing artwork");
        check(WatchNextEntryPolicy.progressPercent(30_000L, 120_000L) == 25,
                "provider playback progress is calculated");
        check(WatchNextEntryPolicy.progressPercent(140_000L, 120_000L) == 100,
                "progress is clamped at duration");
        check(WatchNextEntryPolicy.progressPercent(0L, 0L) == -1,
                "missing duration must not fabricate progress");
        System.out.println("  [PASS] WatchNextEntryPolicyTest (17 assertions)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
