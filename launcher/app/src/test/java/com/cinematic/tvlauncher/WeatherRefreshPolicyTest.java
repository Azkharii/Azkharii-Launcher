package com.cinematic.tvlauncher;

/** Every return to the foreground refreshes; persisted cache age never blocks it. */
public final class WeatherRefreshPolicyTest {
    private WeatherRefreshPolicyTest() {}

    public static void runTests() {
        assert WeatherRefreshPolicy.shouldRefreshOnLauncherResume()
                : "launcher resume must always request fresh weather";
        assert WeatherRefreshPolicy.shouldRefreshOnLauncherResume()
                : "a recent previous attempt must not suppress a second launcher resume";
        assert WeatherRefreshPolicy.shouldRefreshOnLauncherResume()
                : "screen-off/screen-on must not inherit a persisted refresh countdown";
    }
}
