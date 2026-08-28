package com.retro.launcher.core;

/**
 * The prototype's synthetic weather formula, ported from tempC() /
 * weatherName() — see DESIGN_NOTES §9 delta 1. Backs the home screen before
 * any network code exists (Tier 1); {@code WeatherRepository} swaps in a real
 * source at Tier 5 behind the same seam.
 */
public final class SyntheticWeather {

    private SyntheticWeather() {}

    public static Weather at(float hour, float w, boolean snow) {
        float wv = SkyRenderer.clamp01(w);
        float sunAlt = SkyRenderer.sunAlt(hour);
        float cover = SkyRenderer.smooth(0.10f, 0.66f, wv);
        float precip = SkyRenderer.smooth(0.62f, 0.98f, wv);

        int tempC = snow
                ? Math.round(-2 - 6 * precip - 3 * cover + 4 * SkyRenderer.clamp01(sunAlt))
                : Math.round(17 + 9 * sunAlt - 5 * cover - 4 * precip);

        return new Weather(tempC, label(wv, snow), wv);
    }

    public static String label(float w, boolean snow) {
        if (w < 0.07f) return "CLEAR";
        if (w < 0.18f) return "HAZY";
        if (w < 0.30f) return "FAIR";
        if (w < 0.42f) return "PARTLY CLOUDY";
        if (w < 0.54f) return "CLOUDY";
        if (w < 0.64f) return "OVERCAST";
        if (w < 0.76f) return snow ? "LIGHT SNOW" : "LIGHT RAIN";
        if (w < 0.87f) return snow ? "SNOW" : "RAIN";
        if (w < 0.95f) return snow ? "HEAVY SNOW" : "DOWNPOUR";
        return snow ? "BLIZZARD" : "THUNDERSTORM";
    }
}
