package com.cinematic.tvlauncher;

/** Boundary tests for the weather cache's 90-minute refresh policy. */
public final class WeatherRefreshPolicyTest {
    private WeatherRefreshPolicyTest() {}

    public static void runTests() {
        long now = 1_800_000_000_000L;
        assert WeatherRefreshPolicy.isRefreshDue(0L, now) : "first use must refresh";
        assert !WeatherRefreshPolicy.isRefreshDue(
                now - WeatherRefreshPolicy.REFRESH_INTERVAL_MS + 1L, now)
                : "refresh must wait until 90 minutes have elapsed";
        assert WeatherRefreshPolicy.isRefreshDue(
                now - WeatherRefreshPolicy.REFRESH_INTERVAL_MS, now)
                : "refresh should be due at exactly 90 minutes";
        assert WeatherRefreshPolicy.isRefreshDue(now + 1_000L, now)
                : "clock moving backwards must not block refresh indefinitely";
    }
}
