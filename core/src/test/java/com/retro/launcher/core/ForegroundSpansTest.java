package com.retro.launcher.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ForegroundSpansTest {

    private static final long MIN = 60_000L;

    private static ForegroundSpans.Event e(String pkg, int type, long ts) {
        return new ForegroundSpans.Event(pkg, type, ts);
    }

    private static long totalFor(List<UsageMath.Interval> spans, String pkg) {
        long sum = 0;
        for (UsageMath.Interval iv : spans) {
            if (pkg.equals(iv.pkg)) sum += iv.endMillis - iv.startMillis;
        }
        return sum;
    }

    @Test public void aPausedAppIsCreditedOnlyWithItsForegroundTime() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 10 * MIN),
                e("a", ForegroundSpans.ACTIVITY_PAUSED, 25 * MIN)
        ), 0, 100 * MIN);
        assertEquals(15 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void anAppLeftRunningAcrossAScreenOffStopsAtTheScreenOff() {
        // The reported symptom: a background app credited with every minute
        // since the screen went dark.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 10 * MIN),
                e("", ForegroundSpans.SCREEN_NON_INTERACTIVE, 20 * MIN)
        ), 0, 600 * MIN);
        assertEquals(10 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void anAppNeverPausedWithTheScreenNowOffIsDiscardedEntirely() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("", ForegroundSpans.SCREEN_NON_INTERACTIVE, 5 * MIN),
                e("", ForegroundSpans.SCREEN_INTERACTIVE, 10 * MIN),
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 12 * MIN),
                e("", ForegroundSpans.SCREEN_NON_INTERACTIVE, 15 * MIN)
        ), 0, 600 * MIN);
        assertEquals(3 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void anAppStillForegroundWithTheScreenOnRunsToTheWindowEnd() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("", ForegroundSpans.SCREEN_INTERACTIVE, 5 * MIN),
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 10 * MIN)
        ), 0, 30 * MIN);
        assertEquals(20 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void twoResumesWithNoPauseBetweenThemDoNotDoubleCount() {
        // Only one activity is ever foreground; resuming b closes a.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 0),
                e("b", ForegroundSpans.ACTIVITY_RESUMED, 10 * MIN),
                e("b", ForegroundSpans.ACTIVITY_PAUSED, 30 * MIN)
        ), 0, 100 * MIN);
        assertEquals(10 * MIN, totalFor(r.apps, "a"));
        assertEquals(20 * MIN, totalFor(r.apps, "b"));
        assertEquals(30 * MIN, totalFor(r.apps, "a") + totalFor(r.apps, "b"));
    }

    @Test public void aPauseForAnAppThatIsNotFocusedIsIgnored() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 0),
                e("b", ForegroundSpans.ACTIVITY_PAUSED, 5 * MIN),
                e("a", ForegroundSpans.ACTIVITY_PAUSED, 10 * MIN)
        ), 0, 100 * MIN);
        assertEquals(10 * MIN, totalFor(r.apps, "a"));
        assertEquals(0L, totalFor(r.apps, "b"));
    }

    @Test public void activityStoppedClosesTheFocusedSpanToo() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 0),
                e("a", ForegroundSpans.ACTIVITY_STOPPED, 8 * MIN)
        ), 0, 100 * MIN);
        assertEquals(8 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void keyguardShownClosesTheFocusedSpan() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 0),
                e("", ForegroundSpans.KEYGUARD_SHOWN, 7 * MIN)
        ), 0, 100 * MIN);
        assertEquals(7 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void deviceShutdownClosesTheFocusedSpan() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 0),
                e("", ForegroundSpans.DEVICE_SHUTDOWN, 4 * MIN)
        ), 0, 100 * MIN);
        assertEquals(4 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void aDayWithNoScreenEventsTreatsTheWholeWindowAsAwake() {
        // Below API 28 the platform emits no screen events at all. Clipping
        // to an empty awake list would zero the day; assuming the window is
        // awake is the truthful degradation.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 10 * MIN),
                e("a", ForegroundSpans.ACTIVITY_PAUSED, 40 * MIN)
        ), 0, 100 * MIN);
        assertEquals(30 * MIN, totalFor(r.apps, "a"));
        assertEquals(1, r.awake.size());
        assertEquals(0L, r.awake.get(0).startMillis);
        assertEquals(100 * MIN, r.awake.get(0).endMillis);
    }

    @Test public void awakeWindowsTrackTheScreen() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("", ForegroundSpans.SCREEN_NON_INTERACTIVE, 10 * MIN),
                e("", ForegroundSpans.SCREEN_INTERACTIVE, 30 * MIN),
                e("", ForegroundSpans.SCREEN_NON_INTERACTIVE, 50 * MIN)
        ), 0, 100 * MIN);
        assertEquals(2, r.awake.size());
        assertEquals(0L, r.awake.get(0).startMillis);
        assertEquals(10 * MIN, r.awake.get(0).endMillis);
        assertEquals(30 * MIN, r.awake.get(1).startMillis);
        assertEquals(50 * MIN, r.awake.get(1).endMillis);
    }

    @Test public void pickupsCountKeyguardDismissals() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("", ForegroundSpans.KEYGUARD_HIDDEN, 1 * MIN),
                e("", ForegroundSpans.KEYGUARD_SHOWN, 2 * MIN),
                e("", ForegroundSpans.KEYGUARD_HIDDEN, 3 * MIN),
                e("", ForegroundSpans.KEYGUARD_HIDDEN, 4 * MIN)
        ), 0, 100 * MIN);
        assertEquals(3, r.pickups);
    }

    @Test public void aSpanStraddlingMidnightIsOneUnsplitInterval() {
        // Splitting is UsageMath.dailyTotals' job, not this machine's.
        long midnight = 1_700_000_000_000L;
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, midnight - 10 * MIN),
                e("a", ForegroundSpans.ACTIVITY_PAUSED, midnight + 10 * MIN)
        ), midnight - 60 * MIN, midnight + 60 * MIN);
        assertEquals(1, r.apps.size());
        assertEquals(midnight - 10 * MIN, r.apps.get(0).startMillis);
        assertEquals(midnight + 10 * MIN, r.apps.get(0).endMillis);
    }

    @Test public void zeroLengthSpansAreDropped() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 5 * MIN),
                e("a", ForegroundSpans.ACTIVITY_PAUSED, 5 * MIN)
        ), 0, 100 * MIN);
        assertTrue(r.apps.isEmpty());
    }

    @Test public void anEmptyEventStreamProducesNothingButTheAwakeWindow() {
        ForegroundSpans.Result r = ForegroundSpans.scan(
                new ArrayList<>(), 0, 100 * MIN);
        assertTrue(r.apps.isEmpty());
        assertEquals(0, r.pickups);
        assertEquals(1, r.awake.size());
    }

    @Test public void anEmptyWindowProducesNothingAtAll() {
        ForegroundSpans.Result r = ForegroundSpans.scan(
                new ArrayList<>(), 100 * MIN, 100 * MIN);
        assertTrue(r.apps.isEmpty());
        assertTrue(r.awake.isEmpty());
    }
}
