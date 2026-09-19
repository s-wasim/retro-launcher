package com.retro.launcher.core;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Moonrise and moonset from latitude, longitude and date, offline — mirrors
 * {@link SolarMath}'s style. Pure math, no I/O, no Android type.
 *
 * Low-precision lunar ecliptic position (the same order of approximation
 * {@link MoonPhase} already uses) is converted to equatorial coordinates,
 * then searched hour-by-hour for the two crossings of {@code +0.125°} — Meeus's fixed mean value of
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

    /** A day's crossings, either of which may be absent. */
    private static final class Scan {
        Double rise, set;
    }

    /** The set hour reported for a moon that never sets on this day. Not
     *  24.0: {@link SolarTimes} documents its hours as {@code [0, 24)}, and
     *  {@link BodyPath#moonT} reads set &lt; rise as "sets tomorrow", which a
     *  literal 24 would not trip. A 36-millisecond shortfall is invisible on
     *  a 12px disc. */
    private static final float ALL_DAY_SET = 23.99999f;

    /** The calendar day itself. */
    private static final int DAY_SCAN_HOURS = 24;

    /** A neighbour is scanned two hours long, not 24. A day under a
     *  daylight-saving fall-back is 25 hours, and a 24-hour scan of it stops
     *  an hour before it ends — which is exactly where a late moonrise sat on
     *  2026-11-02 in New York, leaving that day with half a window. */
    private static final int NEIGHBOUR_SCAN_HOURS = 26;

    /**
     * Today's moonrise and moonset as decimal local hours in {@code zone}.
     *
     * <h3>2.3.3: windows that cross midnight</h3>
     * The moon rises about 50 minutes later each day, so roughly twice a
     * month it rises in the late evening and does not set until after
     * midnight — and about as often it set in the small hours having risen
     * the previous evening. Scanning only the local calendar day finds one
     * end of those windows and not the other, and a half-known window is
     * worth exactly nothing to the renderer: {@link BodyPath#moonT} needs
     * both ends and answers NaN without them, which draws no moon at all for
     * the whole day. That was DESIGN_NOTES delta 34's deferred bug.
     *
     * <p>{@link BodyPath#moonT} already handles the wrap — it reads a set
     * earlier than the rise as "sets tomorrow" and adds 24 — so the missing
     * piece was only ever finding the crossing. Each absent end is now
     * searched for in the adjoining day and reported as that day's own
     * {@code [0, 24)} hour, which is exactly the form the wrap expects.
     *
     * <h3>A day with no crossing at all</h3>
     * Above roughly 61° of latitude the moon can stay up, or stay down, for
     * a whole day. Neither produces a crossing, so both used to return null
     * and draw nothing — right for one and wrong for the other. The altitude
     * at midday now separates them: up all day reports a full-day window,
     * down all day still reports nothing.
     *
     * @return the day's window, or {@code null} when the moon does not
     *         appear at all
     */
    public static LunarTimes moonTimes(float latitude, float longitude, LocalDate date, ZoneId zone) {
        double latRad = Math.toRadians(latitude);
        long t0 = date.atStartOfDay(zone).toInstant().toEpochMilli();

        Scan today = scanDay(t0, DAY_SCAN_HOURS, latRad, longitude);
        Double rise = today.rise, set = today.set;

        if (rise == null && set == null) {
            // No crossing either way: up all day, or down all day.
            return isUp(t0 + 12 * HOUR_MS, latRad, longitude)
                    ? new LunarTimes(0f, ALL_DAY_SET) : null;
        }

        // Each neighbour is resolved through LocalDate rather than by adding
        // 86_400_000ms, so a day that is 23 or 25 hours long under a DST
        // transition still starts where the calendar says it does.
        long dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        if (set == null) {
            set = scanDay(dayEnd, NEIGHBOUR_SCAN_HOURS, latRad, longitude).set;
        }
        if (rise == null) {
            long previousStart = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            rise = scanDay(previousStart, NEIGHBOUR_SCAN_HOURS, latRad, longitude).rise;
        }

        // Still missing means the window is longer than a day and a half —
        // which happens at high latitude, where the moon can stay up across
        // two calendar boundaries. Rather than hand back half a window, which
        // draws nothing at all, anchor the unknown end to the day's own edge:
        // if the moon is already up at midnight it rose before this day, and
        // if it is still up at the end of it it sets after. Both are true
        // statements about today, which is all the renderer is asking.
        if (rise == null && isUp(t0, latRad, longitude)) rise = 0.0;
        if (set == null && isUp(dayEnd, latRad, longitude)) set = (double) ALL_DAY_SET;

        // The invariant the renderer depends on: a window is complete or it
        // is absent. BodyPath.moonT needs both ends and answers NaN without
        // them, so half a window and no window draw the same nothing — and
        // returning null at least says so honestly.
        if (rise == null || set == null) return null;

        return new LunarTimes(hour(rise), hour(set));
    }

    /** Whether the moon is above its rise/set altitude at this instant. */
    private static boolean isUp(long utcMillis, double latRad, double longitudeDeg) {
        return altitude(utcMillis, latRad, longitudeDeg) - MOONRISE_ALTITUDE_DEG > 0;
    }

    /** Normalises a scan result into {@code [0, 24)}; a parabola root at the
     *  very end of the last span can land on exactly 24. */
    private static float hour(Double h) {
        if (h == null) return Float.NaN;
        double v = h % 24.0;
        if (v < 0) v += 24.0;
        return (float) v;
    }

    /**
     * Scans {@code spanHours} from {@code t0} for the moon's crossings of
     * {@link #MOONRISE_ALTITUDE_DEG}. The body is unchanged from the
     * pre-2.3.3 search; only its start and length are now parameters.
     */
    private static Scan scanDay(long t0, int spanHours, double latRad, double longitudeDeg) {
        Scan out = new Scan();
        double h0 = altitude(t0, latRad, longitudeDeg) - MOONRISE_ALTITUDE_DEG;

        for (int i = 1; i <= spanHours - 1 && (out.rise == null || out.set == null); i += 2) {
            double h1 = altitude(t0 + i * HOUR_MS, latRad, longitudeDeg) - MOONRISE_ALTITUDE_DEG;
            double h2 = altitude(t0 + (i + 1) * HOUR_MS, latRad, longitudeDeg) - MOONRISE_ALTITUDE_DEG;

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
                        if (h0 < 0) { if (out.rise == null) out.rise = (double) i + x1; }
                        else        { if (out.set  == null) out.set  = (double) i + x1; }
                    } else if (roots == 2) {
                        if (ye < 0) {
                            if (out.rise == null) out.rise = (double) i + x2;
                            if (out.set  == null) out.set  = (double) i + x1;
                        } else {
                            if (out.rise == null) out.rise = (double) i + x1;
                            if (out.set  == null) out.set  = (double) i + x2;
                        }
                    }
                }
            }
            h0 = h2;
        }
        return out;
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
