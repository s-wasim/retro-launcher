package com.retro.launcher.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The one-foreground-app state machine, as a pure function.
 *
 * <p>Android's model has exactly one foreground activity at a time. The
 * previous implementation kept a {@code Map<String, Long>} of open packages,
 * so several could be "open" at once and every extra entry counted the same
 * minute twice. Worse, any span without a matching pause was closed at
 * <em>now</em>, which is why an app foregrounded before the screen went off
 * was credited with every minute since — the reported symptom.
 *
 * <p>One rule fixes that: a span still open when the scan ends is closed at
 * the window end <strong>only if the screen is currently interactive</strong>.
 * Otherwise it is discarded, because the device stopped telling us anything
 * and inventing time is worse than losing it.
 *
 * <p>The event-type values are {@code UsageEvents.Event}'s own public
 * constants, repeated here so this class stays free of any Android import
 * and therefore unit-testable. {@code ACTIVITY_STOPPED} is API 29+; below
 * that the machine simply never sees one and relies on
 * {@code ACTIVITY_PAUSED} plus the screen-off close.
 */
public final class ForegroundSpans {

    private ForegroundSpans() {}

    public static final int ACTIVITY_RESUMED = 1;        // == MOVE_TO_FOREGROUND
    public static final int ACTIVITY_PAUSED = 2;         // == MOVE_TO_BACKGROUND
    public static final int SCREEN_INTERACTIVE = 15;
    public static final int SCREEN_NON_INTERACTIVE = 16;
    public static final int KEYGUARD_SHOWN = 17;
    public static final int KEYGUARD_HIDDEN = 18;
    public static final int ACTIVITY_STOPPED = 23;       // API 29+
    public static final int DEVICE_SHUTDOWN = 26;

    /** One row of the platform's event stream, with nothing else attached. */
    public static final class Event {
        public final String pkg;
        public final int type;
        public final long ts;
        public Event(String pkg, int type, long ts) {
            this.pkg = pkg;
            this.type = type;
            this.ts = ts;
        }
    }

    public static final class Result {
        /** Foreground spans, in the order they closed. Never overlapping. */
        public final List<UsageMath.Interval> apps;
        /** Spans during which the display was interactive. */
        public final List<UsageMath.Interval> awake;
        /** Keyguard dismissals in the window. */
        public final int pickups;
        Result(List<UsageMath.Interval> apps, List<UsageMath.Interval> awake, int pickups) {
            this.apps = apps;
            this.awake = awake;
            this.pickups = pickups;
        }
    }

    /** The pseudo-package awake windows ride under. Not a legal package name,
     *  so it can never collide with a real one. */
    public static final String AWAKE = "!awake";

    /**
     * Replays {@code events} — which must be in timestamp order, as
     * {@code queryEvents} returns them — into foreground spans, awake
     * windows and a pickup count.
     *
     * <p>The window is assumed awake at {@code windowStart} until an event
     * says otherwise. That covers both "the screen was already lit when the
     * window opened" and "this device emits no screen events at all"
     * (below API 28), where clipping to an empty awake list would wrongly
     * zero the whole day.
     */
    public static Result scan(List<Event> events, long windowStart, long windowEnd) {
        List<UsageMath.Interval> apps = new ArrayList<>();
        List<UsageMath.Interval> awake = new ArrayList<>();
        int pickups = 0;

        if (windowEnd <= windowStart) return new Result(apps, awake, 0);

        String focused = null;
        long focusedSince = 0L;
        long awakeSince = windowStart;

        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            long ts = clamp(e.ts, windowStart, windowEnd);
            switch (e.type) {
                case ACTIVITY_RESUMED:
                    if (focused != null) addSpan(apps, focused, focusedSince, ts);
                    focused = e.pkg;
                    focusedSince = ts;
                    break;

                case ACTIVITY_PAUSED:
                case ACTIVITY_STOPPED:
                    // Only the focused activity's own pause ends the span. A
                    // pause from anything else is a background transition we
                    // were never counting.
                    if (focused != null && focused.equals(e.pkg)) {
                        addSpan(apps, focused, focusedSince, ts);
                        focused = null;
                    }
                    break;

                case SCREEN_NON_INTERACTIVE:
                case KEYGUARD_SHOWN:
                case DEVICE_SHUTDOWN:
                    if (focused != null) {
                        addSpan(apps, focused, focusedSince, ts);
                        focused = null;
                    }
                    if (awakeSince >= 0) {
                        addSpan(awake, AWAKE, awakeSince, ts);
                        awakeSince = -1L;
                    }
                    break;

                case KEYGUARD_HIDDEN:
                    pickups++;
                    if (awakeSince < 0) awakeSince = ts;
                    break;

                case SCREEN_INTERACTIVE:
                    if (awakeSince < 0) awakeSince = ts;
                    break;

                default:
                    break;
            }
        }

        if (awakeSince >= 0) {
            // The screen is still on, so a span with no pause really is still
            // in the foreground and runs to the window's end.
            addSpan(awake, AWAKE, awakeSince, windowEnd);
            if (focused != null) addSpan(apps, focused, focusedSince, windowEnd);
        }
        // Otherwise the still-open span is dropped on the floor, on purpose.

        return new Result(apps, awake, pickups);
    }

    private static void addSpan(List<UsageMath.Interval> out, String pkg, long from, long to) {
        if (to > from) out.add(new UsageMath.Interval(pkg, from, to));
    }

    private static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
