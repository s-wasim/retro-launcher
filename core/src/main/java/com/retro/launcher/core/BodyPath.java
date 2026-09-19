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
     * moonrise, 1 at moonset, outside {@code [0, 1]} when the moon is not up.
     *
     * <p>{@code moonriseHour} and {@code moonsetHour} are hours relative to
     * today's local midnight, as {@link LunarMath#moonWindow} reports them —
     * offsets, not clock readings, so a window that opened yesterday has a
     * negative rise and one that closes tomorrow a set past 24. That leaves
     * nothing to fold: the window is a plain interval and this is a plain
     * lerp across it.
     *
     * <p>Before 2.3.4 both ends were squeezed into {@code [0, 24)} and a set
     * numerically below the rise meant "sets tomorrow". The fold could only
     * ever describe one of the two up-periods a calendar day can hold, so
     * the other was drawn against the wrong end. NaN when the window is
     * degenerate.
     */
    static float moonT(float hour, float moonriseHour, float moonsetHour) {
        float span = moonsetHour - moonriseHour;
        if (!(span > 0f)) return Float.NaN;
        return (hour - moonriseHour) / span;
    }
}
