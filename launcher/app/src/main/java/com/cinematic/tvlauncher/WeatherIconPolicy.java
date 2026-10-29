package com.cinematic.tvlauncher;

/** Maps Open-Meteo WMO weather codes to stable, platform-independent icon categories. */
final class WeatherIconPolicy {
    enum Category {
        CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, STORM
    }

    private WeatherIconPolicy() {}

    static Category fromWmoCode(int code) {
        if (code == 0) return Category.CLEAR;
        if (code == 1 || code == 2) return Category.PARTLY_CLOUDY;
        if (code == 3) return Category.CLOUDY;
        if (code == 45 || code == 48) return Category.FOG;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return Category.RAIN;
        if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) return Category.SNOW;
        if (code >= 95 && code <= 99) return Category.STORM;
        return Category.CLOUDY;
    }
}
