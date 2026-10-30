package com.cinematic.tvlauncher;

/** Small Spanish weather-advice rotation based on apparent temperature and WMO condition codes. */
final class WeatherAdvicePolicy {
    private WeatherAdvicePolicy() {}

    static String[] phrasesFor(int temperatureC, int feelsLikeC, int weatherCode) {
        int perceivedHeat = Math.max(temperatureC, feelsLikeC);
        int perceivedCold = Math.min(temperatureC, feelsLikeC);
        if (perceivedHeat >= 42) return new String[] {"Llamen a Dios", "Toma agua", "Busca sombra"};
        if (perceivedHeat >= 38) return new String[] {"Calor intenso", "Enciende el ventilador", "Hidrátate"};
        if (perceivedHeat >= 32) return new String[] {"Toma agua", "Usa ropa ligera", "Busca sombra"};
        if (perceivedCold <= 8) return new String[] {"Abrígate bien", "Evita el frío", "Bebida caliente"};
        if (perceivedCold <= 15) return new String[] {"Lleva chamarra", "Abrígate", "Tarde fresca"};

        if (weatherCode == 95 || weatherCode == 96 || weatherCode == 99) {
            return new String[] {"Resguárdate", "Evita zonas abiertas", "Tormenta cercana"};
        }
        if ((weatherCode >= 51 && weatherCode <= 67)
                || (weatherCode >= 80 && weatherCode <= 82)) {
            return new String[] {"Lleva paraguas", "Piso mojado", "Maneja con cuidado"};
        }
        if (weatherCode == 71 || weatherCode == 73 || weatherCode == 75
                || weatherCode == 77 || weatherCode == 85 || weatherCode == 86) {
            return new String[] {"Abrígate bien", "Cuidado con el hielo", "Frío intenso"};
        }
        if (weatherCode == 45 || weatherCode == 48) {
            return new String[] {"Maneja con cuidado", "Baja visibilidad", "Enciende las luces"};
        }
        if (weatherCode == 0 && temperatureC >= 24) {
            return new String[] {"Día soleado", "Usa protector solar", "Hidrátate"};
        }
        if (weatherCode >= 1 && weatherCode <= 3) {
            return new String[] {"Cielo nublado", "Lleva una chamarra", "Buen día para salir"};
        }
        return new String[] {"Disfruta el día", "Cuídate mucho", "Clima agradable"};
    }
}
