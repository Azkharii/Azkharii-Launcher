package com.cinematic.tvlauncher;

/** Tests greeting transitions at each local-time boundary and invalid input. */
public final class DayGreetingPolicyTest {
    private DayGreetingPolicyTest() {}

    public static void runTests() {
        assert DayGreetingPolicy.forHour(0).equals("Buenas noches");
        assert DayGreetingPolicy.forHour(5).equals("Buenas noches");
        assert DayGreetingPolicy.forHour(6).equals("Buenos días");
        assert DayGreetingPolicy.forHour(11).equals("Buenos días");
        assert DayGreetingPolicy.forHour(12).equals("Buenas tardes");
        assert DayGreetingPolicy.forHour(18).equals("Buenas tardes");
        assert DayGreetingPolicy.forHour(19).equals("Buenas noches");
        assert DayGreetingPolicy.forHour(23).equals("Buenas noches");
        boolean rejectedNegativeHour=false;
        try { DayGreetingPolicy.forHour(-1); }
        catch (IllegalArgumentException expected) { rejectedNegativeHour=true; }
        assert rejectedNegativeHour;
        boolean rejectedHourAfterDay=false;
        try { DayGreetingPolicy.forHour(24); }
        catch (IllegalArgumentException expected) { rejectedHourAfterDay=true; }
        assert rejectedHourAfterDay;
    }
}
