package com.cinematic.tvlauncher;

/** Boundary and condition tests for compact weather advice. */
public final class WeatherAdvicePolicyTest {
    private WeatherAdvicePolicyTest() {}

    public static void runTests() {
        assert WeatherAdvicePolicy.phrasesFor(42, 42, 0, true)[0].equals("Llamen a Dios")
                : "extreme heat must use the requested special phrase";
        assert WeatherAdvicePolicy.phrasesFor(40, 42, 0, true)[0].equals("Llamen a Dios")
                : "apparent extreme heat must trigger the special phrase";
        assert WeatherAdvicePolicy.phrasesFor(38, 38, 0, true)[0].equals("Calor intenso")
                : "intense heat advice";
        assert WeatherAdvicePolicy.phrasesFor(33, 34, 1, true)[0].equals("Toma agua")
                : "warm weather hydration advice";
        assert WeatherAdvicePolicy.phrasesFor(12, 10, 0, true)[0].equals("Lleva chamarra")
                : "cold weather advice";
        assert WeatherAdvicePolicy.phrasesFor(12, 10, 0, false)[2].equals("Noche fresca")
                : "cold advice must not refer to the afternoon at night";
        assert WeatherAdvicePolicy.phrasesFor(22, 22, -1, false)[0].equals("Disfruta la noche")
                : "unknown conditions must use night wording after dark";
        assert WeatherAdvicePolicy.phrasesFor(23, 23, 61, true)[0].equals("Lleva paraguas")
                : "rain advice";
        assert WeatherAdvicePolicy.phrasesFor(23, 23, 95, true)[0].equals("Resguárdate")
                : "thunderstorm safety advice";
        assert WeatherAdvicePolicy.phrasesFor(22, 22, 0, true)[0].equals("Cielo despejado")
                : "clear sky must not depend on temperature";
        assert WeatherAdvicePolicy.phrasesFor(22, 22, 1, true)[0].equals("Mayormente despejado")
                : "mainly clear must not be mislabeled cloudy";
        assert WeatherAdvicePolicy.phrasesFor(22, 22, 2, true)[0].equals("Parcialmente nublado")
                : "partly cloudy must remain distinct from overcast";
        assert WeatherAdvicePolicy.phrasesFor(22, 22, 3, true)[0].equals("Cielo nublado")
                : "overcast weather advice";
        String[] nightClear = WeatherAdvicePolicy.phrasesFor(22, 22, 0, false);
        assert nightClear[0].equals("Noche despejada")
                : "clear conditions must not say sunny after dark";
        assert !nightClear[1].contains("sol") && !nightClear[1].contains("solar")
                : "night advice must not recommend daytime sun protection";
        assert WeatherAdvicePolicy.phrasesFor(22, 22, 2, false)[1].equals("Nubes dispersas")
                : "night partly-cloudy advice must not say sun between clouds";

        String[] phrases = WeatherAdvicePolicy.phrasesFor(38, 38, 0, true);
        assert phrases.length == 3 && !phrases[0].equals(phrases[1]) && !phrases[1].equals(phrases[2])
                : "advice must rotate through distinct short phrases";
    }
}
