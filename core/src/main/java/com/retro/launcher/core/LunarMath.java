package com.retro.launcher.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Moonrise and moonset from latitude, longitude and an instant, offline —
 * mirrors {@link SolarMath}'s style. Pure math, no I/O, no Android type.
 *
 * Low-precision lunar ecliptic position (the same order of approximation
 * {@link MoonPhase} already uses) is converted to equatorial coordinates,
 * then searched hour-by-hour for the crossings of {@code +0.125°} — Meeus's
 * fixed mean value of "0.7275·parallax − refraction" for the Moon
 * (Astronomical Algorithms ch. 15), used as a constant rather than
 * recomputed per-instant since a 12px disc cannot show the arcminute-scale
 * difference true parallax would make. Each 2-hour span is fit with a
 * parabola through three altitude samples and solved for its root(s) — the
 * standard hour-stepping method, accurate to a few minutes for a body that
 * moves as slowly as the Moon.
 */
public final class LunarMath {

    private LunarMath() {}

    private static final double MOONRISE_ALTITUDE_DEG = 0.125;
    private static final double UNIX_EPOCH_JD = 2440587.5;
    private static final double J2000_JD = 2451545.0;
    private static final double OBLIQUITY_DEG = 23.4397;
    private static final long HOUR_MS = 3_600_000L;

    /**
     * One rise-to-set window, as decimal hours relative to local midnight of
     * the day the caller asked about.
     *
     * <h3>2.3.4: hours are no longer clock readings</h3>
     * These are <em>offsets</em>, not times of day: {@code moonriseHour} is
     * negative when the moon rose before midnight, and {@code moonsetHour}
     * exceeds 24 when it sets after the next one. {@code moonsetHour} is
     * therefore always greater than {@code moonriseHour}, and
     * {@link BodyPath#moonT} is a plain lerp between them.
     *
     * <p>Before 2.3.4 both were forced into {@code [0, 24)} and a set
     * numerically below the rise was the encoding for "sets tomorrow". That
     * cost more than it saved: a calendar day can hold the tail of one
     * window and the start of the next, and squeezing a rise from one and a
     * set from the other into a single wrapped pair put moonset up to an
     * hour out. See {@link #moonWindow}.
     */
    public static final class LunarTimes {
        public final float moonriseHour;
        public final float moonsetHour;
        public LunarTimes(float moonriseHour, float moonsetHour) {
            this.moonriseHour = moonriseHour;
            this.moonsetHour = moonsetHour;
        }
    }

    /** A crossing of {@link #MOONRISE_ALTITUDE_DEG}: when, and which way. */
    private static final class Crossing {
        final long millis;
        final boolean rising;
        Crossing(long millis, boolean rising) { this.millis = millis; this.rising = rising; }
    }

    /** A rise paired with the set that actually closes it. Either end may be
     *  anchored to the scan's edge when the window runs past it. */
    private static final class Window {
        final long rise, set;
        Window(long rise, long set) { this.rise = rise; this.set = set; }
    }

    /**
     * The moon's up-period covering {@code atMillis}, or the next one to
     * begin after it, as hours relative to local midnight of the day
     * {@code atMillis} falls on in {@code zone}.
     *
     * <h3>Why an instant and not a date</h3>
     * A calendar day is not a lunar day: the moon rises about 50 minutes
     * later each time, so roughly half of all days hold the <em>tail</em> of
     * one up-period in the small hours and the <em>start</em> of the next one
     * later on. There is no single rise/set pair that describes both, and the
     * pre-2.3.4 code built one anyway — it took the day's first rise and the
     * day's first set, which on those days belong to different windows. The
     * moon was retired up to an hour early on the nights either side of
     * {@code 2026-09-21}, and drawn up to an hour before it had risen on the
     * nights either side of {@code 2026-09-05}.
     *
     * <p>Asking about an instant removes the ambiguity rather than encoding
     * around it: there is exactly one window that contains a given moment.
     * The whole computation is a few dozen trig calls with no allocation
     * worth counting and no I/O, so it is cheaper to recompute on the minute
     * tick than it was to cache a value that could only ever be right for
     * part of the day.
     *
     * <h3>A day with no crossing at all</h3>
     * Above roughly 61° of latitude the moon can stay up, or stay down, for a
     * whole day. Neither produces a crossing, so the altitude at
     * {@code atMillis} separates them: up reports a window spanning the day,
     * down reports nothing.
     *
     * @return the window, or {@code null} when the moon is neither up now nor
     *         due to rise within the next couple of days
     */
    public static LunarTimes moonWindow(float latitude, float longitude, long atMillis, ZoneId zone) {
        double latRad = Math.toRadians(latitude);
        LocalDate date = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate();

        // Neighbouring days are resolved through LocalDate rather than by
        // adding 86_400_000ms, so a day that is 23 or 25 hours long under a
        // DST transition still starts where the calendar says it does.
        long dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long from = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long to = date.plusDays(3).atStartOfDay(zone).toInstant().toEpochMilli();

        Window w = windowAt(crossings(from, to, latRad, longitude), dayStart, dayEnd, atMillis);
        if (w == null) {
            // No crossing brackets this moment: the moon is either up for the
            // whole scan or down for it. Only the first is worth drawing, and
            // it gets the day itself as its window.
            return isUp(atMillis, latRad, longitude)
                    ? new LunarTimes(0f, hoursFromMidnight(dayEnd, date, zone))
                    : null;
        }
        return new LunarTimes(hoursFromMidnight(w.rise, date, zone),
                              hoursFromMidnight(w.set, date, zone));
    }

    /**
     * Picks the window containing {@code now} from a time-ordered crossing
     * list, or — when the moon is down — the next one to begin after it.
     * Both answer {@link BodyPath#moonT} correctly: a window that has not
     * opened yet puts the moon before {@code t = 0}, which draws nothing.
     *
     * <p>A window with an end outside the scan is anchored to the day's own
     * edge rather than dropped — "already up when today began", "still up
     * when it ends". Both are true statements about today, which is all the
     * renderer is asking, and both only arise near the pole, where the moon
     * can stay up across several calendar boundaries. The day's edge rather
     * than the scan's, so an anchored window still crosses the screen over
     * the day instead of creeping across a four-day span.
     */
    private static Window windowAt(List<Crossing> crossings, long dayStart, long dayEnd, long now) {
        // A scan that opens with a set is a scan that opened with the moon
        // already up, from further back than it reaches.
        boolean open = !crossings.isEmpty() && !crossings.get(0).rising;
        long openedAt = dayStart;

        for (Crossing c : crossings) {
            if (c.rising) {
                openedAt = c.millis;
                open = true;
            } else if (open) {
                if (c.millis > now) return new Window(openedAt, c.millis);
                open = false;
            }
        }
        // Up when the scan ran out. Anchoring the set to the end of today only
        // describes a window that has actually started by then; one that has
        // not is a moon that neither rises nor sets today, which draws nothing
        // either way.
        return open && openedAt < dayEnd ? new Window(openedAt, dayEnd) : null;
    }

    /** Whether the moon is above its rise/set altitude at this instant. */
    private static boolean isUp(long utcMillis, double latRad, double longitudeDeg) {
        return altitude(utcMillis, latRad, longitudeDeg) - MOONRISE_ALTITUDE_DEG > 0;
    }

    /**
     * An instant as decimal hours from the local midnight that opens
     * {@code date} — negative before it, past 24 after it.
     *
     * <p>Read off the wall clock rather than by dividing elapsed
     * milliseconds, so the number stays comparable with the clock hour the
     * sky renders against on the 23- and 25-hour days a DST transition makes.
     */
    private static float hoursFromMidnight(long millis, LocalDate date, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochMilli(millis).atZone(zone);
        long days = ChronoUnit.DAYS.between(date, z.toLocalDate());
        return (float) (days * 24
                + z.getHour()
                + z.getMinute() / 60.0
                + z.getSecond() / 3600.0);
    }

    /**
     * Every crossing of {@link #MOONRISE_ALTITUDE_DEG} in {@code [from, to)},
     * in time order. The parabola fit is unchanged from the pre-2.3.4 search;
     * it now records both roots of a span instead of keeping only the first
     * rise and the first set of a day.
     */
    private static List<Crossing> crossings(long from, long to, double latRad, double longitudeDeg) {
        List<Crossing> out = new ArrayList<>();
        double h0 = altitude(from, latRad, longitudeDeg) - MOONRISE_ALTITUDE_DEG;

        for (long t = from; t + 2 * HOUR_MS <= to; t += 2 * HOUR_MS) {
            double hStart = h0;
            double h1 = altitude(t + HOUR_MS, latRad, longitudeDeg) - MOONRISE_ALTITUDE_DEG;
            double h2 = altitude(t + 2 * HOUR_MS, latRad, longitudeDeg) - MOONRISE_ALTITUDE_DEG;

            double a = (hStart + h2) / 2.0 - h1;
            double b = (h2 - hStart) / 2.0;
            h0 = h2;
            if (a == 0.0) continue;

            double xe = -b / (2.0 * a);
            double ye = (a * xe + b) * xe + h1;
            double d = b * b - 4.0 * a * h1;
            if (d < 0.0) continue;

            double dx = Math.sqrt(d) / (Math.abs(a) * 2.0);
            double x1 = xe - dx, x2 = xe + dx;
            int roots = 0;
            if (Math.abs(x1) <= 1.0) roots++;
            if (Math.abs(x2) <= 1.0) roots++;
            if (x1 < -1.0) x1 = x2;

            // x is measured from the span's midpoint, an hour in.
            if (roots == 1) {
                // One root: the span starts on one side of the horizon and
                // ends on the other, so where it started says which way.
                out.add(new Crossing(t + HOUR_MS + Math.round(x1 * HOUR_MS), hStart < 0));
            } else if (roots == 2) {
                // Vertex below the horizon: the moon dips, so the earlier root
                // is the set and the later one the rise. Above it: a bump, and
                // the order is the other way round.
                boolean firstRising = ye >= 0;
                out.add(new Crossing(t + HOUR_MS + Math.round(x1 * HOUR_MS), firstRising));
                out.add(new Crossing(t + HOUR_MS + Math.round(x2 * HOUR_MS), !firstRising));
            }
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
