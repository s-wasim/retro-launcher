package com.retro.launcher.core;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Screen-time arithmetic: bucketing foreground-usage intervals into calendar
 * days and evaluating the daily limit. Day boundaries go through
 * {@link Calendar} field arithmetic rather than fixed 24h offsets, since a
 * DST transition day is 23 or 25 real hours long in the device's time zone.
 */
public final class UsageMath {

    private UsageMath() {}

    public static final int LIMIT_MIN = 30;
    public static final int LIMIT_MAX = 600;
    public static final int LIMIT_STEP = 15;

    public static final class Interval {
        public final String pkg;
        public final long startMillis;
        public final long endMillis;
        public Interval(String pkg, long startMillis, long endMillis) {
            this.pkg = pkg;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
        }
    }

    public static long startOfDay(long millis, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long addDays(long dayStartMillis, int days, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(dayStartMillis);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    /** The 7 calendar-day starts ending with today's, oldest first. */
    public static long[] last7DayStarts(long nowMillis, TimeZone tz) {
        long today = startOfDay(nowMillis, tz);
        long[] out = new long[7];
        for (int i = 0; i < 7; i++) {
            out[i] = addDays(today, -(6 - i), tz);
        }
        return out;
    }

    /**
     * Splits each interval at calendar-day boundaries and sums per-day
     * millis, keyed by that day's start. Days with zero usage are absent.
     */
    public static Map<Long, Long> dailyTotals(List<Interval> intervals, TimeZone tz) {
        Map<Long, Long> totals = new HashMap<>();
        for (Interval iv : intervals) {
            long cursor = iv.startMillis;
            while (cursor < iv.endMillis) {
                long dayStart = startOfDay(cursor, tz);
                long nextDayStart = addDays(dayStart, 1, tz);
                long segmentEnd = Math.min(iv.endMillis, nextDayStart);
                totals.merge(dayStart, segmentEnd - cursor, Long::sum);
                cursor = segmentEnd;
            }
        }
        return totals;
    }

    public static long totalForDay(List<Interval> intervals, long dayStartMillis, TimeZone tz) {
        Long v = dailyTotals(intervals, tz).get(dayStartMillis);
        return v == null ? 0L : v;
    }

    public static int snapLimit(int minutes) {
        int clamped = Math.max(LIMIT_MIN, Math.min(LIMIT_MAX, minutes));
        return Math.round(clamped / (float) LIMIT_STEP) * LIMIT_STEP;
    }

    public static boolean isOverLimit(long todayMillis, int limitMinutes) {
        return todayMillis > limitMinutes * 60_000L;
    }

    public static int minutesLeft(long todayMillis, int limitMinutes) {
        long left = limitMinutes * 60_000L - todayMillis;
        return left <= 0 ? 0 : (int) (left / 60_000L);
    }

    public static String stateLabel(long todayMillis, int limitMinutes) {
        return isOverLimit(todayMillis, limitMinutes)
                ? "LIMIT EXCEEDED"
                : minutesLeft(todayMillis, limitMinutes) + "M LEFT";
    }

    /** Fraction of the limit used; floored at 0, uncapped above 1 so the UI can clip the bar. */
    public static float usageFraction(long todayMillis, int limitMinutes) {
        float f = todayMillis / (float) (limitMinutes * 60_000L);
        return f < 0 ? 0 : f;
    }
}
