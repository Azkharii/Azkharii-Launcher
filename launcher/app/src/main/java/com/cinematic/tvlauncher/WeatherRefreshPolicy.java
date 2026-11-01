package com.cinematic.tvlauncher;

/** Every foreground return is a refresh opportunity; cache is display-only fallback. */
final class WeatherRefreshPolicy {
    private WeatherRefreshPolicy() {}

    static boolean shouldRefreshOnLauncherResume() {
        return true;
    }
}
