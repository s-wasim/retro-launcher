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

    /**
     * A stand-in sky for when there is no real reading — no location
     * permission, no network, or nothing fetched yet.
     *
     * Real weather (Tier 5) is the source of truth when we have it. Without
     * this the fallback is a hardcoded {@code w = 0}, which leaves the
     * wallpaper permanently, conspicuously cloudless. Instead each day draws a
     * character from its own index and drifts gently across its hours, so the
     * sky still has cloudy mornings and wet afternoons.
     *
     * Deterministic in both arguments: the same day renders the same sky
     * across restarts, and no state has to be persisted for it.
     *
     * @param dayIndex days since the epoch — any stable per-day integer
     * @param hour     decimal hour, 0-24
     */
    public static float drift(long dayIndex, float hour) {
        // splitmix64 finalizer: cheap, and adjacent days land far apart.
        long h = dayIndex * 0x9E3779B97F4A7C15L;
        h ^= h >>> 30; h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27; h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;

        float character = ((h >>> 40) & 0xFF) / 255f;             // the day's mood
        float phase = ((h >>> 24) & 0xFF) / 255f * 6.2831855f;    // when it peaks
        float swing = 0.18f * (float) Math.sin(hour * (Math.PI / 12f) + phase);

        return SkyRenderer.clamp01(character * 0.92f + swing);
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
