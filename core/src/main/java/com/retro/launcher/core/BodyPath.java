package com.retro.launcher.core;

/**
 * Pure body-position math for the sky wallpaper. No Android type; fully
 * unit-testable. Nothing else may compute a sun or moon screen position.
 */
public final class BodyPath {

    private BodyPath() {}

    static final float DAY_SPAN = SolarClock.SUNSET_ANCHOR - SolarClock.SUNRISE_ANCHOR;

    /** 0 at the sunrise anchor, 1 at the sunset anchor; <0 and >1 during twilight. */
    static float sunT(float warpedHour) {
        return (warpedHour - SolarClock.SUNRISE_ANCHOR) / DAY_SPAN;
    }

    static float sunX(float t, int w) { return t * w; }

    /** Tangent to the top edge at noon (t=0.5); tangent to the bottom edge
     *  at both anchors (t=0, t=1). Past the anchors {@code u*u} exceeds 1
     *  and the parabola carries the disc below the buffer on its own. */
    static float sunY(float t, int w, int h, float r) {
        float u = 2f * t - 1f;
        return r + (h - 2f * r) * u * u;
    }

    static float moonX(float t, int w) { return t * w; }

    /** Enters at the top-left (t=0, y=0); falls to dead centre at the vertex
     *  (t=0.5, y=0.5h); climbs back to 20% above the vertex on exit
     *  (t=1, y=0.3h). The two branches meet at the vertex with zero slope on
     *  both sides despite the differing fall/climb coefficients. */
    static float moonY(float t, int h) {
        float u = 2f * t - 1f;
        float drop = (t < 0.5f) ? 0.50f : 0.20f;
        return 0.5f * h - drop * h * u * u;
    }

    /**
     * Fraction of the way through the moon's own rise-to-set window: 0 at
     * moonrise, 1 at moonset. Folds the window across midnight the same way
     * {@link SolarClock#warp} folds night, so a moonset that lands after
     * 00:00 (encoded as {@code moonsetHour <= moonriseHour}) still produces
     * a monotonic result. NaN when the window is degenerate.
     */
    static float moonT(float hour, float moonriseHour, float moonsetHour) {
        // Strict '<' so an exactly-equal rise/set (a degenerate window, not
        // a genuine after-midnight moonset) falls through to span <= 0 and
        // yields NaN below, rather than being folded into a spurious 24h
        // window.
        float end = moonsetHour < moonriseHour ? moonsetHour + 24f : moonsetHour;
        float span = end - moonriseHour;
        if (!(span > 0f)) return Float.NaN;
        float h = hour < moonriseHour ? hour + 24f : hour;
        return (h - moonriseHour) / span;
    }
}
