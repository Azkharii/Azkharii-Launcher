package com.cinematic.tvlauncher;

/** Small Spanish weather-advice rotation based on apparent temperature and WMO condition codes. */
final class WeatherAdvicePolicy {
    private WeatherAdvicePolicy() {}

    static String[] phrasesFor(int temperatureC, int feelsLikeC, int weatherCode, boolean isDay) {
        int perceivedHeat = Math.max(temperatureC, feelsLikeC);
        int perceivedCold = Math.min(temperatureC, feelsLikeC);
        if (perceivedHeat >= 42) return new String[] {"Llamen a Dios", "Toma agua", "Busca sombra"};
        if (perceivedHeat >= 38) return new String[] {"Calor intenso", "Enciende el ventilador", "Hidrátate"};
        if (perceivedHeat >= 32) return new String[] {"Toma agua", "Usa ropa ligera", "Busca sombra"};
        if (perceivedCold <= 8) {
            return new String[] {"Abrígate bien", "Evita el frío",
                    isDay ? "Bebida caliente" : "Noche fría"};
        }
        if (perceivedCold <= 15) {
            return new String[] {"Lleva chamarra", "Abrígate",
                    isDay ? "Tarde fresca" : "Noche fresca"};
        }

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
        // WMO codes 0–3 are distinct: clear, mainly clear, partly cloudy, overcast.
        // Keep each condition distinct instead of describing every code 1–3 as cloudy.
        if (weatherCode == 0) {
            return isDay
                    ? new String[] {"Cielo despejado", "Día soleado", "Usa protector solar"}
                    : new String[] {"Noche despejada", "Cielo claro", "Tiempo estable"};
        }
        if (weatherCode == 1) {
            return isDay
                    ? new String[] {"Mayormente despejado", "Pocas nubes", "Buen día para salir"}
                    : new String[] {"Noche mayormente despejada", "Pocas nubes", "Cielo tranquilo"};
        }
        if (weatherCode == 2) {
            return isDay
                    ? new String[] {"Parcialmente nublado", "Sol entre nubes", "Buen día para salir"}
                    : new String[] {"Parcialmente nublado", "Nubes dispersas", "Cielo variable"};
        }
        if (weatherCode == 3) {
            return new String[] {"Cielo nublado", isDay ? "Día cubierto" : "Noche cubierta",
                    "Lleva una chamarra"};
        }
        return isDay
                ? new String[] {"Disfruta el día", "Cuídate mucho", "Clima agradable"}
                : new String[] {"Disfruta la noche", "Cuídate mucho", "Clima agradable"};
    }
}
