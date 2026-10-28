package com.cinematic.tvlauncher;

public final class UpdateCheckPolicyTest {
    public static void runTests() {
        long now = 1_000_000L;
        check(UpdateCheckPolicy.nextDelayMillis(now, 0L, 0L, 0) == 0L,
                "first check is immediately due");
        check(UpdateCheckPolicy.nextDelayMillis(now, now, now, 0)
                        == UpdateCheckPolicy.CHECK_INTERVAL_MS,
                "successful check waits 30 minutes");
        check(UpdateCheckPolicy.nextDelayMillis(now + UpdateCheckPolicy.CHECK_INTERVAL_MS,
                        now, now, 0) == 0L,
                "success interval expires on time");
        check(UpdateCheckPolicy.nextDelayMillis(now, 0L, now, 1)
                        == UpdateCheckPolicy.RETRY_BASE_MS,
                "first failure retries after five minutes");
        check(UpdateCheckPolicy.nextDelayMillis(now, 0L, now, 2)
                        == 10L * 60L * 1000L,
                "second failure doubles retry wait");
        check(UpdateCheckPolicy.nextDelayMillis(now, 0L, now, 3)
                        == 20L * 60L * 1000L,
                "third failure doubles retry wait again");
        check(UpdateCheckPolicy.nextDelayMillis(now, 0L, now, 4)
                        == UpdateCheckPolicy.RETRY_MAX_MS,
                "retry wait is capped at thirty minutes");
        check(UpdateCheckPolicy.nextDelayMillis(now + 60_000L, 0L, now, 1) == 4L * 60L * 1000L,
                "retry wait counts down from the last attempt");
        System.out.println("  [PASS] UpdateCheckPolicyTest (8 assertions)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
