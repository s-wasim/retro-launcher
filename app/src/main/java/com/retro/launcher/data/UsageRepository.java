package com.retro.launcher.data;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import com.retro.launcher.core.ForegroundSpans;
import com.retro.launcher.core.UsageMath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Wraps {@link UsageStatsManager} and turns its event stream into the
 * {@link UsageMath.Interval} lists that class does its calendar-day
 * arithmetic on. Requires {@code PACKAGE_USAGE_STATS} — every method degrades
 * to empty/zero rather than throwing when access isn't granted.
 *
 * <p>All the judgement lives in {@link ForegroundSpans}, which is a pure
 * function and unit-tested; this class only reads events and hands them over.
 * That split is the point: the previous in-line state machine could not be
 * tested without a device, and it was wrong in three ways at once —
 * several packages "open" at the same time, unclosed spans credited with
 * every minute up to now, and a headline number that was screen-on time
 * rather than app time.
 *
 * <p>Today's total is now, simply, the app spans: merged so no minute counts
 * twice, clipped to the spans in which the display was actually interactive,
 * and with the launcher's own foreground time removed — time on the home
 * screen is not time on an app. The per-app rows come off the same list, so
 * the headline and the rows finally agree.
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
    private final String selfPkg;
    private final TimeZone tz = TimeZone.getDefault();

    public UsageRepository(Context context) {
        this.usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.selfPkg = context.getPackageName();
    }

    /** One pass over the platform's stream, replayed through the pure machine. */
    private ForegroundSpans.Result scan(long start, long end) {
        List<ForegroundSpans.Event> raw = new ArrayList<>();
        if (usm != null && end > start) {
            UsageEvents events = usm.queryEvents(start, end);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                raw.add(new ForegroundSpans.Event(
                        e.getPackageName(), e.getEventType(), e.getTimeStamp()));
            }
        }
        return ForegroundSpans.scan(raw, start, end);
    }

    /**
     * App spans, deduplicated, clipped to the screen-awake windows, with the
     * launcher removed. Everything else in this class is a projection of it.
     */
    private List<UsageMath.Interval> countable(ForegroundSpans.Result r) {
        List<UsageMath.Interval> merged = UsageMath.merge(r.apps);
        List<UsageMath.Interval> awake = UsageMath.merge(r.awake);
        return UsageMath.excluding(UsageMath.intersect(merged, awake), selfPkg);
    }

    public long todayMillis(long nowMillis) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        return UsageMath.totalForDay(countable(scan(dayStart, nowMillis)), dayStart, tz);
    }

    /** Millis used on each of the last 7 calendar days, oldest to today. */
    public long[] last7DaysMillis(long nowMillis) {
        long[] dayStarts = UsageMath.last7DayStarts(nowMillis, tz);
        List<UsageMath.Interval> week = countable(scan(dayStarts[0], nowMillis));
        long[] out = new long[7];
        for (int i = 0; i < 7; i++) out[i] = UsageMath.totalForDay(week, dayStarts[i], tz);
        return out;
    }

    /** Per-app totals for today, descending, capped at {@code limit} rows.
     *  The launcher is not one of the apps. */
    public List<AppUsage> mostUsedToday(long nowMillis, int limit) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        Map<String, Long> totals = new LinkedHashMap<>();
        for (UsageMath.Interval iv : countable(scan(dayStart, nowMillis))) {
            totals.merge(iv.pkg, iv.endMillis - iv.startMillis, Long::sum);
        }
        List<AppUsage> out = new ArrayList<>(totals.size());
        for (Map.Entry<String, Long> e : totals.entrySet()) out.add(new AppUsage(e.getKey(), e.getValue()));
        out.sort((a, b) -> Long.compare(b.millis, a.millis));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /**
     * Keyguard dismissals today. Counted by the same scan that produces the
     * spans, rather than the second independent {@code queryEvents} pass this
     * used to make. Below API 28 the platform emits no keyguard events, so
     * this is 0 — a truthful degradation, not a crash.
     */
    public int pickupsToday(long nowMillis) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        return scan(dayStart, nowMillis).pickups;
    }
}
