package com.retro.launcher.core;

/**
 * Maps real local time onto {@link SkyKeyframes}'s own time frame, anchored
 * on the two hours the keyframe table already draws dawn ({@code 6.2}) and
 * dusk ({@code 18.4}) at.
 *
 * <p>Day (real time in {@code [sunrise, sunset]}) maps linearly onto
 * {@code [SUNRISE_ANCHOR, SUNSET_ANCHOR]}, so every intermediate keyframe
 * lands at the same *fraction* of daylight it always occupied — equally
 * spaced across however long the day actually is at this latitude and time
 * of year. Night (real time in {@code [sunset, next sunrise]}) maps onto
 * {@code [SUNSET_ANCHOR, SUNSET_ANCHOR + 11.8]}, taken mod 24, so
 * pre-midnight and post-midnight night are one continuous segment with no
 * discontinuity at 00:00.
 *
 * <p>Degenerate input — a missing sunrise/sunset, a non-finite value, or
 * {@code sunset <= sunrise} (polar day, polar night, no location fix yet) —
 * warps as the identity, so the sky falls back to the fixed 6.2/18.4 table
 * rather than producing something undefined.
 */
public final class SolarClock {

    private SolarClock() {}

    public static final float SUNRISE_ANCHOR = 6.2f;
    public static final float SUNSET_ANCHOR  = 18.4f;

    /** Night spans {@code 24 - (SUNSET_ANCHOR - SUNRISE_ANCHOR)} anchor
     *  hours — matches {@code SkyRenderer.sunAlt}'s piecewise night span. */
    private static final float NIGHT_ANCHOR_SPAN = 24f - (SUNSET_ANCHOR - SUNRISE_ANCHOR);

    public static float warp(float realHour, float sunriseHour, float sunsetHour, float tomorrowSunriseHour) {
        float h = normalize(realHour);
        if (!valid(sunriseHour, sunsetHour, tomorrowSunriseHour)) return h;

        if (h >= sunriseHour && h <= sunsetHour) {
            float t = (h - sunriseHour) / (sunsetHour - sunriseHour);
            return SUNRISE_ANCHOR + t * (SUNSET_ANCHOR - SUNRISE_ANCHOR);
        }

        // Night: fold pre-midnight and post-midnight real time onto one
        // continuous [sunset, tomorrowSunrise + 24) window.
        float hNight = h < sunriseHour ? h + 24f : h;
        float nightEndReal = tomorrowSunriseHour <= sunsetHour
                ? tomorrowSunriseHour + 24f
                : tomorrowSunriseHour;
        float span = nightEndReal - sunsetHour;
        float t = span <= 0f ? 0f : (hNight - sunsetHour) / span;
        float warped = SUNSET_ANCHOR + clamp01(t) * NIGHT_ANCHOR_SPAN;
        return normalize(warped);
    }

    private static boolean valid(float sunrise, float sunset, float tomorrowSunrise) {
        return isFinite(sunrise) && isFinite(sunset) && isFinite(tomorrowSunrise)
                && sunset > sunrise;
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float normalize(float h) {
        if (Float.isNaN(h)) return h;
        float m = h % 24f;
        return m < 0f ? m + 24f : m;
    }
}
