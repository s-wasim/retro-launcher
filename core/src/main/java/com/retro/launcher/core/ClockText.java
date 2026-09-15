package com.retro.launcher.core;

/**
 * How a time is written on the clock face.
 *
 * <p>Lifted out of {@code ClockWidget} in 2.2.1, when a second time zone gave
 * it a second caller. The rules are small but each one has a wrong answer that
 * looks almost right — midnight as {@code 0:00 AM}, noon as {@code 0:00 PM},
 * a 24-hour {@code 9:05} that should be {@code 09:05} — and none of them is
 * reachable from a unit test while it lives inside a {@code FrameLayout}.
 *
 * <p>Seconds are not a parameter. The colon is always solid and seconds are
 * never shown: a fixed design decision (issue #6, 2026-08-28), not a
 * preference. See {@code ClockWidget}.
 */
public final class ClockText {

    private ClockText() {}

    /**
     * {@code 09:05} in 24-hour, {@code 9:05 AM} in 12-hour.
     *
     * <p>The leading zero is 24-hour only, which is the convention the design
     * follows and not an oversight: a 24-hour clock is a fixed-width field and
     * a 12-hour one reads as speech.
     *
     * @param hour24 0-23; values outside that are wrapped rather than rejected,
     *               because the caller is a {@code Calendar} field and a
     *               throw here would take the home screen down
     * @param minute 0-59, wrapped the same way
     */
    public static String time(int hour24, int minute, boolean hour12) {
        int h24 = Math.floorMod(hour24, 24);
        int m = Math.floorMod(minute, 60);

        StringBuilder sb = new StringBuilder(8);
        if (hour12) {
            int h = h24 % 12 == 0 ? 12 : h24 % 12;
            sb.append(h);
        } else {
            if (h24 < 10) sb.append('0');
            sb.append(h24);
        }
        sb.append(':');
        if (m < 10) sb.append('0');
        sb.append(m);
        if (hour12) sb.append(h24 < 12 ? " AM" : " PM");
        return sb.toString();
    }

    /**
     * Which way another zone's calendar date sits against the local one:
     * {@code +1} for tomorrow there, {@code -1} for yesterday, {@code 0} for
     * the same day.
     *
     * <p>Takes years and days-of-year rather than a date, because the only two
     * cases that matter are "one day either side" and "the same day", and the
     * pair straddling a year boundary — 31 December against 1 January — is
     * exactly where subtracting day numbers gives 364 instead of -1. Comparing
     * the year first and collapsing to a sign handles it without a calendar.
     *
     * <p>No real zone pair is more than a day apart, so a larger difference
     * means the two values did not come from the same instant. It still
     * collapses to a sign: a clock showing {@code +1} is a better failure than
     * one showing {@code +364}.
     */
    public static int dayDelta(int localYear, int localDayOfYear,
                               int otherYear, int otherDayOfYear) {
        if (otherYear != localYear) return otherYear > localYear ? 1 : -1;
        return Integer.compare(otherDayOfYear, localDayOfYear);
    }
}
