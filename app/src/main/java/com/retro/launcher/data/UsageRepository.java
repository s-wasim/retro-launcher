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
 * Wraps {@link UsageStatsManager} and turns its event stream into the
 * {@link UsageMath.Interval} lists that class does its calendar-day arithmetic
 * on. All the bucketing (today's total, the 7-day window, midnight/DST
 * correctness) lives in {@code UsageMath}, unit-tested without any Android
 * dependency; this class only reconstructs intervals from real device data.
 * Requires {@code PACKAGE_USAGE_STATS} — every method degrades to empty/zero
 * rather than throwing when access isn't granted.
 *
 * <p>Two readings come out of the one scan (§9 delta 16). The device's own
 * screen-on record — {@code SCREEN_INTERACTIVE} to {@code SCREEN_NON_INTERACTIVE},
 * API 28+ — is the preferred total, with the launcher's own foreground time
 * subtracted from it; the per-app sum is the fallback for a day the device
 * reported no screen events for. Either way the launcher is never counted:
 * time on the home screen is not time on an app.
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

    /** Screen-on spans ride as intervals under this pseudo-package so they go
     *  through the same day-splitting arithmetic as app usage. Not a legal
     *  package name, so it can never collide with a real one. */
    private static final String SCREEN = "!screen";

    /** One pass over the event stream: app foreground spans, and the device's
     *  own screen-on spans. */
    private static final class Scan {
        final List<UsageMath.Interval> apps = new ArrayList<>();
        final List<UsageMath.Interval> screen = new ArrayList<>();
    }

    private final UsageStatsManager usm;
    private final String selfPkg;
    private final TimeZone tz = TimeZone.getDefault();

    public UsageRepository(Context context) {
        this.usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.selfPkg = context.getPackageName();
    }

    private Scan scan(long start, long end) {
        Scan out = new Scan();
        if (usm == null || end <= start) return out;
        UsageEvents events = usm.queryEvents(start, end);
        Map<String, Long> openedAt = new LinkedHashMap<>();
        long litAt = -1L;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            String pkg = e.getPackageName();
            long ts = e.getTimeStamp();
            switch (e.getEventType()) {
                case UsageEvents.Event.MOVE_TO_FOREGROUND:
                    openedAt.put(pkg, ts);
                    break;
                case UsageEvents.Event.MOVE_TO_BACKGROUND: {
                    Long opened = openedAt.remove(pkg);
                    if (opened != null && ts > opened) {
                        out.apps.add(new UsageMath.Interval(pkg, opened, ts));
                    }
                    break;
                }
                // Below API 28 the platform emits neither of these, so the
                // screen list stays empty and every day falls back.
                case UsageEvents.Event.SCREEN_INTERACTIVE:
                    if (litAt < 0) litAt = ts;
                    break;
                case UsageEvents.Event.SCREEN_NON_INTERACTIVE: {
                    // A screen-off with no matching screen-on means the
                    // display was already lit when the window opened.
                    long from = litAt < 0 ? start : litAt;
                    if (ts > from) out.screen.add(new UsageMath.Interval(SCREEN, from, ts));
                    litAt = -1L;
                    break;
                }
                default:
                    break;
            }
        }
        // Anything still foregrounded at query end (usually the launcher
        // itself, mid-query) counts up to `end`, as does a screen still lit.
        for (Map.Entry<String, Long> entry : openedAt.entrySet()) {
            if (end > entry.getValue()) out.apps.add(new UsageMath.Interval(entry.getKey(), entry.getValue(), end));
        }
        if (litAt >= 0 && end > litAt) out.screen.add(new UsageMath.Interval(SCREEN, litAt, end));
        return out;
    }

    /** Device screen-on for that day less launcher time, or the per-app sum
     *  without the launcher when the device reported no screen events. */
    private long dayTotal(Scan s, long dayStart) {
        long device = UsageMath.totalForDay(s.screen, dayStart, tz);
        long launcher = UsageMath.totalForDay(UsageMath.only(s.apps, selfPkg), dayStart, tz);
        long apps = UsageMath.totalForDay(UsageMath.excluding(s.apps, selfPkg), dayStart, tz);
        return UsageMath.resolveTotal(device, launcher, apps);
    }

    public long todayMillis(long nowMillis) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        return dayTotal(scan(dayStart, nowMillis), dayStart);
    }

    /** Millis used on each of the last 7 calendar days, oldest to today. */
    public long[] last7DaysMillis(long nowMillis) {
        long[] dayStarts = UsageMath.last7DayStarts(nowMillis, tz);
        Scan week = scan(dayStarts[0], nowMillis);
        long[] out = new long[7];
        for (int i = 0; i < 7; i++) out[i] = dayTotal(week, dayStarts[i]);
        return out;
    }

    /** Per-app totals for today, descending, capped at {@code limit} rows.
     *  The launcher is not one of the apps. */
    public List<AppUsage> mostUsedToday(long nowMillis, int limit) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        Map<String, Long> totals = new LinkedHashMap<>();
        for (UsageMath.Interval iv : UsageMath.excluding(scan(dayStart, nowMillis).apps, selfPkg)) {
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
