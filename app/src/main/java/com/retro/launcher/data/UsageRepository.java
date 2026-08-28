package com.retro.launcher.data;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;

import com.retro.launcher.core.UsageMath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Wraps {@link UsageStatsManager} and turns its foreground/background event
 * stream into the {@link UsageMath.Interval} list that class does its
 * calendar-day arithmetic on. All the bucketing (today's total, the 7-day
 * window, midnight/DST correctness) lives in {@code UsageMath}, unit-tested
 * without any Android dependency; this class only reconstructs intervals
 * from real device data. Requires {@code PACKAGE_USAGE_STATS} — every method
 * degrades to empty/zero rather than throwing when access isn't granted.
 */
public final class UsageRepository {

    public static final class AppUsage {
        public final String pkg;
        public final long millis;
        public AppUsage(String pkg, long millis) {
            this.pkg = pkg;
            this.millis = millis;
        }
    }

    private final UsageStatsManager usm;
    private final TimeZone tz = TimeZone.getDefault();

    public UsageRepository(Context context) {
        this.usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    private List<UsageMath.Interval> intervals(long start, long end) {
        List<UsageMath.Interval> out = new ArrayList<>();
        if (usm == null || end <= start) return out;
        UsageEvents events = usm.queryEvents(start, end);
        Map<String, Long> openedAt = new LinkedHashMap<>();
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            String pkg = e.getPackageName();
            int type = e.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                openedAt.put(pkg, e.getTimeStamp());
            } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                Long opened = openedAt.remove(pkg);
                if (opened != null && e.getTimeStamp() > opened) {
                    out.add(new UsageMath.Interval(pkg, opened, e.getTimeStamp()));
                }
            }
        }
        // Anything still foregrounded at query end (usually the launcher
        // itself, mid-query) counts up to `end`.
        for (Map.Entry<String, Long> entry : openedAt.entrySet()) {
            if (end > entry.getValue()) out.add(new UsageMath.Interval(entry.getKey(), entry.getValue(), end));
        }
        return out;
    }

    public long todayMillis(long nowMillis) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        return UsageMath.totalForDay(intervals(dayStart, nowMillis), dayStart, tz);
    }

    /** Millis used on each of the last 7 calendar days, oldest to today. */
    public long[] last7DaysMillis(long nowMillis) {
        long[] dayStarts = UsageMath.last7DayStarts(nowMillis, tz);
        List<UsageMath.Interval> ivs = intervals(dayStarts[0], nowMillis);
        long[] out = new long[7];
        for (int i = 0; i < 7; i++) out[i] = UsageMath.totalForDay(ivs, dayStarts[i], tz);
        return out;
    }

    /** Per-app totals for today, descending, capped at {@code limit} rows. */
    public List<AppUsage> mostUsedToday(long nowMillis, int limit) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        Map<String, Long> totals = new LinkedHashMap<>();
        for (UsageMath.Interval iv : intervals(dayStart, nowMillis)) {
            totals.merge(iv.pkg, iv.endMillis - iv.startMillis, Long::sum);
        }
        List<AppUsage> out = new ArrayList<>(totals.size());
        for (Map.Entry<String, Long> e : totals.entrySet()) out.add(new AppUsage(e.getKey(), e.getValue()));
        out.sort((a, b) -> Long.compare(b.millis, a.millis));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /**
     * Approximated as keyguard-dismiss events today (API 28+; below that,
     * always 0 — the "no pickups" state is a truthful degradation, not a
     * crash).
     */
    public int pickupsToday(long nowMillis) {
        if (usm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0;
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        UsageEvents events = usm.queryEvents(dayStart, nowMillis);
        UsageEvents.Event e = new UsageEvents.Event();
        int count = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.KEYGUARD_HIDDEN) count++;
        }
        return count;
    }
}
