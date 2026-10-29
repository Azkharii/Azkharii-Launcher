package com.cinematic.tvlauncher;

/** Tests WMO weather-code mapping and safe handling of unknown codes. */
public final class WeatherIconPolicyTest {
    private WeatherIconPolicyTest() {}

    public static void runTests() {
        assert WeatherIconPolicy.fromWmoCode(0) == WeatherIconPolicy.Category.CLEAR;
        assert WeatherIconPolicy.fromWmoCode(1) == WeatherIconPolicy.Category.PARTLY_CLOUDY;
        assert WeatherIconPolicy.fromWmoCode(2) == WeatherIconPolicy.Category.PARTLY_CLOUDY;
        assert WeatherIconPolicy.fromWmoCode(3) == WeatherIconPolicy.Category.CLOUDY;
        assert WeatherIconPolicy.fromWmoCode(45) == WeatherIconPolicy.Category.FOG;
        assert WeatherIconPolicy.fromWmoCode(48) == WeatherIconPolicy.Category.FOG;
        assert WeatherIconPolicy.fromWmoCode(51) == WeatherIconPolicy.Category.RAIN;
        assert WeatherIconPolicy.fromWmoCode(67) == WeatherIconPolicy.Category.RAIN;
        assert WeatherIconPolicy.fromWmoCode(71) == WeatherIconPolicy.Category.SNOW;
        assert WeatherIconPolicy.fromWmoCode(86) == WeatherIconPolicy.Category.SNOW;
        assert WeatherIconPolicy.fromWmoCode(80) == WeatherIconPolicy.Category.RAIN;
        assert WeatherIconPolicy.fromWmoCode(82) == WeatherIconPolicy.Category.RAIN;
        assert WeatherIconPolicy.fromWmoCode(95) == WeatherIconPolicy.Category.STORM;
        assert WeatherIconPolicy.fromWmoCode(99) == WeatherIconPolicy.Category.STORM;
        assert WeatherIconPolicy.fromWmoCode(-1) == WeatherIconPolicy.Category.CLOUDY;
        assert WeatherIconPolicy.fromWmoCode(100) == WeatherIconPolicy.Category.CLOUDY;
    }
}
