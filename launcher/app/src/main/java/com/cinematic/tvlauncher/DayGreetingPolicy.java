package com.cinematic.tvlauncher;

/** Maps local clock hours to conventional Spanish greetings without a polling timer. */
final class DayGreetingPolicy {
    private static final int MORNING_START_HOUR = 6;
    private static final int AFTERNOON_START_HOUR = 12;
    private static final int EVENING_START_HOUR = 19;

    private DayGreetingPolicy() {}

    static String forHour(int hourOfDay) {
        if (hourOfDay < 0 || hourOfDay > 23) {
            throw new IllegalArgumentException("hourOfDay must be between 0 and 23");
        }
        if (hourOfDay < MORNING_START_HOUR || hourOfDay >= EVENING_START_HOUR) {
            return "Buenas noches";
        }
        if (hourOfDay < AFTERNOON_START_HOUR) {
            return "Buenos días";
        }
        return "Buenas tardes";
    }
}
