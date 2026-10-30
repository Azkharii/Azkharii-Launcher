package com.cinematic.tvlauncher;

/** Boundary and condition tests for compact weather advice. */
public final class WeatherAdvicePolicyTest {
    private WeatherAdvicePolicyTest() {}

    public static void runTests() {
        assert WeatherAdvicePolicy.phrasesFor(42, 42, 0)[0].equals("Llamen a Dios")
                : "extreme heat must use the requested special phrase";
        assert WeatherAdvicePolicy.phrasesFor(38, 38, 0)[0].equals("Calor intenso")
                : "intense heat advice";
        assert WeatherAdvicePolicy.phrasesFor(33, 34, 1)[0].equals("Toma agua")
                : "warm weather hydration advice";
        assert WeatherAdvicePolicy.phrasesFor(12, 10, 0)[0].equals("Lleva chamarra")
                : "cold weather advice";
        assert WeatherAdvicePolicy.phrasesFor(23, 23, 61)[0].equals("Lleva paraguas")
                : "rain advice";
        assert WeatherAdvicePolicy.phrasesFor(23, 23, 95)[0].equals("Resguárdate")
                : "thunderstorm safety advice";
        assert WeatherAdvicePolicy.phrasesFor(27, 27, 0)[0].equals("Día soleado")
                : "sunny weather advice";
        assert WeatherAdvicePolicy.phrasesFor(22, 22, 2)[0].equals("Cielo nublado")
                : "cloudy weather advice";
        String[] phrases = WeatherAdvicePolicy.phrasesFor(38, 38, 0);
        assert phrases.length == 3 && !phrases[0].equals(phrases[1]) && !phrases[1].equals(phrases[2])
                : "advice must rotate through distinct short phrases";
    }
}
