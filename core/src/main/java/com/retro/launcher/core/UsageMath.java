package com.retro.launcher.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /** Every interval except {@code pkg}'s — the launcher's own foreground
     *  time is not screen time (§9 delta 16). A null {@code pkg} filters nothing. */
    public static List<Interval> excluding(List<Interval> intervals, String pkg) {
        List<Interval> out = new ArrayList<>(intervals.size());
        for (Interval iv : intervals) {
            if (pkg == null || !pkg.equals(iv.pkg)) out.add(iv);
        }
        return out;
    }

    /** The mirror of {@link #excluding}: only {@code pkg}'s intervals. */
    public static List<Interval> only(List<Interval> intervals, String pkg) {
        List<Interval> out = new ArrayList<>();
        for (Interval iv : intervals) {
            if (pkg != null && pkg.equals(iv.pkg)) out.add(iv);
        }
        return out;
    }

    /**
     * Coalesces overlapping or touching spans, per package, so no arrangement
     * of events can make one minute count twice. Sorted by start within each
     * package; package order across the result is not meaningful.
     */
    public static List<Interval> merge(List<Interval> intervals) {
        Map<String, List<Interval>> byPkg = new LinkedHashMap<>();
        for (Interval iv : intervals) {
            if (iv.endMillis <= iv.startMillis) continue;
            byPkg.computeIfAbsent(iv.pkg, k -> new ArrayList<>()).add(iv);
        }

        List<Interval> out = new ArrayList<>();
        for (Map.Entry<String, List<Interval>> entry : byPkg.entrySet()) {
            List<Interval> spans = entry.getValue();
            spans.sort((a, b) -> Long.compare(a.startMillis, b.startMillis));
            long start = spans.get(0).startMillis;
            long end = spans.get(0).endMillis;
            for (int i = 1; i < spans.size(); i++) {
                Interval iv = spans.get(i);
                if (iv.startMillis <= end) {
                    // Overlapping or exactly touching: extend rather than add.
                    if (iv.endMillis > end) end = iv.endMillis;
                } else {
                    out.add(new Interval(entry.getKey(), start, end));
                    start = iv.startMillis;
                    end = iv.endMillis;
                }
            }
            out.add(new Interval(entry.getKey(), start, end));
        }
        return out;
    }

    /**
     * Clips every span to {@code windows}, keeping the span's package. Time
     * a device spent with the screen off is not screen time, however
     * confidently the event stream implies an app was foreground through it.
     *
     * <p>A span overlapping several windows produces several intervals. A
     * span overlapping none disappears — including when {@code windows} is
     * empty, which callers must therefore avoid passing when the real
     * meaning is "screen state unknown"; {@link ForegroundSpans} guarantees a
     * non-empty window list whenever the device reported no screen events.
     */
    public static List<Interval> intersect(List<Interval> spans, List<Interval> windows) {
        List<Interval> out = new ArrayList<>();
        for (Interval span : spans) {
            for (Interval window : windows) {
                long from = Math.max(span.startMillis, window.startMillis);
                long to = Math.min(span.endMillis, window.endMillis);
                if (to > from) out.add(new Interval(span.pkg, from, to));
            }
        }
        return out;
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
