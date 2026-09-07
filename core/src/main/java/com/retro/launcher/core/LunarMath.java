package com.retro.launcher.core;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Moonrise and moonset from latitude, longitude and date, offline — mirrors
 * {@link SolarMath}'s style. Pure math, no I/O, no Android type.
 *
 * Low-precision lunar ecliptic position (the same order of approximation
 * {@link MoonPhase} already uses) is converted to equatorial coordinates,
 * then searched hour-by-hour across the local calendar day for the two
 * crossings of {@code +0.125°} — Meeus's fixed mean value of
 * "0.7275·parallax − refraction" for the Moon (Astronomical Algorithms
 * ch. 15), used as a constant rather than recomputed per-instant since a
 * 12px disc cannot show the arcminute-scale difference true parallax would
 * make. Each 2-hour span is fit with a parabola through three altitude
 * samples and solved for its root(s) — the standard hour-stepping method,
 * accurate to a few minutes for a body that moves as slowly as the Moon.
 */
public final class LunarMath {

    private LunarMath() {}

    private static final double MOONRISE_ALTITUDE_DEG = 0.125;
    private static final double UNIX_EPOCH_JD = 2440587.5;
    private static final double J2000_JD = 2451545.0;
    private static final double OBLIQUITY_DEG = 23.4397;
    private static final long HOUR_MS = 3_600_000L;

    public static final class LunarTimes {
        public final float moonriseHour;
        public final float moonsetHour;
        public LunarTimes(float moonriseHour, float moonsetHour) {
            this.moonriseHour = moonriseHour;
            this.moonsetHour = moonsetHour;
        }
    }

    /**
     * @return today's moonrise and/or moonset as decimal local hours in
     *         {@code zone}, or {@code null} if neither happens on this
     *         calendar day.
     */
    public static LunarTimes moonTimes(float latitude, float longitude, LocalDate date, ZoneId zone) {
        long t0 = date.atStartOfDay(zone).toInstant().toEpochMilli();
        double latRad = Math.toRadians(latitude);

        Double rise = null, set = null;
        double h0 = altitude(t0, latRad, longitude) - MOONRISE_ALTITUDE_DEG;

        for (int i = 1; i <= 23 && (rise == null || set == null); i += 2) {
            double h1 = altitude(t0 + i * HOUR_MS, latRad, longitude) - MOONRISE_ALTITUDE_DEG;
            double h2 = altitude(t0 + (i + 1) * HOUR_MS, latRad, longitude) - MOONRISE_ALTITUDE_DEG;

            double a = (h0 + h2) / 2.0 - h1;
            double b = (h2 - h0) / 2.0;

            if (a != 0.0) {
                double xe = -b / (2.0 * a);
                double ye = (a * xe + b) * xe + h1;
                double d = b * b - 4.0 * a * h1;

                if (d >= 0.0) {
                    double dx = Math.sqrt(d) / (Math.abs(a) * 2.0);
                    double x1 = xe - dx, x2 = xe + dx;
                    int roots = 0;
                    if (Math.abs(x1) <= 1.0) roots++;
                    if (Math.abs(x2) <= 1.0) roots++;
                    if (x1 < -1.0) x1 = x2;

                    if (roots == 1) {
                        if (h0 < 0) { if (rise == null) rise = (double) i + x1; }
                        else        { if (set  == null) set  = (double) i + x1; }
                    } else if (roots == 2) {
                        if (ye < 0) {
                            if (rise == null) rise = (double) i + x2;
                            if (set  == null) set  = (double) i + x1;
                        } else {
                            if (rise == null) rise = (double) i + x1;
                            if (set  == null) set  = (double) i + x2;
                        }
                    }
                }
            }
            h0 = h2;
        }

        if (rise == null && set == null) return null;
        return new LunarTimes(
                rise == null ? Float.NaN : rise.floatValue(),
                set == null ? Float.NaN : set.floatValue());
    }

    private static double altitude(long utcMillis, double latRad, double longitudeDeg) {
        double d = daysSinceJ2000(utcMillis);
        double[] eq = moonEquatorial(d);
        double raDeg = eq[0], decRad = eq[1];

        double lst = 280.16 + 360.9856235 * d + longitudeDeg;
        double hourAngleRad = Math.toRadians(norm360(lst - raDeg));

        double sinAlt = Math.sin(latRad) * Math.sin(decRad)
                + Math.cos(latRad) * Math.cos(decRad) * Math.cos(hourAngleRad);
        return Math.toDegrees(Math.asin(clamp(sinAlt, -1.0, 1.0)));
    }

    /** Geocentric right ascension (degrees) and declination (radians) of the
     *  Moon from the low-precision ecliptic-position formula — good to
     *  roughly a degree, far finer than a 12px disc or a 15-minute rise/set
     *  tolerance needs. */
    private static double[] moonEquatorial(double d) {
        double L = Math.toRadians(norm360(218.316 + 13.176396 * d));
        double M = Math.toRadians(norm360(134.963 + 13.064993 * d));
        double F = Math.toRadians(norm360(93.272 + 13.229350 * d));

        double lon = L + Math.toRadians(6.289) * Math.sin(M);
        double lat = Math.toRadians(5.128) * Math.sin(F);
        double e = Math.toRadians(OBLIQUITY_DEG);

        double raRad = Math.atan2(
                Math.sin(lon) * Math.cos(e) - Math.tan(lat) * Math.sin(e),
                Math.cos(lon));
        double decRad = Math.asin(Math.sin(lat) * Math.cos(e) + Math.cos(lat) * Math.sin(e) * Math.sin(lon));

        double raDeg = Math.toDegrees(raRad);
        if (raDeg < 0) raDeg += 360.0;
        return new double[]{ raDeg, decRad };
    }

    private static double daysSinceJ2000(long utcMillis) {
        return utcMillis / 86_400_000.0 + UNIX_EPOCH_JD - J2000_JD;
    }

    private static double norm360(double deg) {
        double v = deg % 360.0;
        return v < 0 ? v + 360.0 : v;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
