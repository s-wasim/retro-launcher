package com.retro.launcher.core;

/**
 * The real lunar phase for a moment in time, as a fraction of the synodic
 * cycle: {@code 0} new, {@code 0.25} first quarter, {@code 0.5} full,
 * {@code 0.75} last quarter.
 *
 * <p>The sky renderer used to draw a fixed {@code 0.62}, so the moon sat
 * permanently at a waning gibbous no matter what the real one was doing. This
 * computes the moon's true elongation from the sun — Meeus, <i>Astronomical
 * Algorithms</i> ch. 47 (mean elements) and 48.4 (phase angle), rearranged for
 * elongation — which is good to well under an hour of the true phase across
 * any date this launcher will see. That is far finer than the 12px disc can
 * show, and it costs seven sines once a frame.
 *
 * <p>Location matters too, but only for orientation: a phase is a geocentric
 * quantity, while <em>which limb looks lit</em> is not. See
 * {@link #southernView}.
 */
public final class MoonPhase {

    private MoonPhase() {}

    /** Julian Day of the Unix epoch, and of J2000.0 — the epoch the series use. */
    private static final double UNIX_EPOCH_JD = 2440587.5;
    private static final double J2000_JD      = 2451545.0;

    /** Mean length of a synodic month, in days. Exposed for callers that want
     *  to reason about how fast the value moves. */
    public static final double SYNODIC_DAYS = 29.530588853;

    /** @param utcMillis wall-clock instant, {@code System.currentTimeMillis()} */
    public static float phase(long utcMillis) {
        double t = centuries(utcMillis);
        double t2 = t * t, t3 = t2 * t, t4 = t3 * t;

        // Meeus 47.2 / 47.3 / 47.4 — mean elongation, and the sun's and moon's
        // mean anomalies. Degrees.
        double d  = 297.8501921 + 445267.1114034 * t - 0.0018819 * t2
                  + t3 / 545868.0 - t4 / 113065000.0;
        double m  = 357.5291092 + 35999.0502909 * t - 0.0001536 * t2
                  + t3 / 24490000.0;
        double mp = 134.9633964 + 477198.8675055 * t + 0.0087414 * t2
                  + t3 / 69699.0 + t4 / 14712000.0;

        // Meeus 48.4 gives the phase angle i; the elongation is 180 - i. Using
        // elongation rather than i is what distinguishes waxing from waning —
        // the phase angle alone is the same on both sides of full.
        double psi = d
                + 6.289 * sinDeg(mp)
                - 2.100 * sinDeg(m)
                + 1.274 * sinDeg(2 * d - mp)
                + 0.658 * sinDeg(2 * d)
                + 0.214 * sinDeg(2 * mp)
                + 0.110 * sinDeg(d);

        return (float) (norm360(psi) / 360.0);
    }

    /** Fraction of the disc lit, 0 at new and 1 at full. */
    public static float illumination(long utcMillis) {
        return illuminationFor(phase(utcMillis));
    }

    /** {@link #illumination} for an already-computed phase. */
    public static float illuminationFor(float phase) {
        return (float) ((1.0 - Math.cos(2 * Math.PI * phase)) / 2.0);
    }

    /**
     * True when the observer sees the moon rotated roughly 180° from the
     * northern-hemisphere view — a waxing crescent lit on the left, not the
     * right. Latitude is the only part of a location fix this needs.
     *
     * <p>{@code Float.NaN} (no fix yet) reads as northern: it keeps the
     * pre-location behaviour rather than inventing a hemisphere.
     */
    public static boolean southernView(float latitude) {
        return !Float.isNaN(latitude) && latitude < 0f;
    }

    private static double centuries(long utcMillis) {
        double jd = utcMillis / 86_400_000.0 + UNIX_EPOCH_JD;
        return (jd - J2000_JD) / 36525.0;
    }

    private static double sinDeg(double degrees) {
        return Math.sin(Math.toRadians(degrees));
    }

    private static double norm360(double degrees) {
        double v = degrees % 360.0;
        return v < 0 ? v + 360.0 : v;
    }
}
