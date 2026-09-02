package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class UsageMathTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static long utc(int y, int mon0, int d, int h, int min) {
        Calendar c = Calendar.getInstance(UTC);
        c.clear();
        c.set(y, mon0, d, h, min, 0);
        return c.getTimeInMillis();
    }

    @Test public void dailyTotalSumsAnIntervalWhollyInsideOneDay() {
        UsageMath.Interval iv = new UsageMath.Interval("a",
                utc(2026, Calendar.AUGUST, 28, 10, 0),
                utc(2026, Calendar.AUGUST, 28, 10, 30));
        long dayStart = UsageMath.startOfDay(iv.startMillis, UTC);
        assertEquals(30 * 60_000L, UsageMath.totalForDay(Arrays.asList(iv), dayStart, UTC));
    }

    @Test public void dailyTotalSplitsAnIntervalCrossingMidnight() {
        UsageMath.Interval iv = new UsageMath.Interval("a",
                utc(2026, Calendar.AUGUST, 28, 23, 50),
                utc(2026, Calendar.AUGUST, 29, 0, 10));
        long day28 = UsageMath.startOfDay(utc(2026, Calendar.AUGUST, 28, 12, 0), UTC);
        long day29 = UsageMath.startOfDay(utc(2026, Calendar.AUGUST, 29, 12, 0), UTC);
        assertEquals(10 * 60_000L, UsageMath.totalForDay(Arrays.asList(iv), day28, UTC));
        assertEquals(10 * 60_000L, UsageMath.totalForDay(Arrays.asList(iv), day29, UTC));
    }

    @Test public void dailyTotalsMergesMultipleIntervalsOnTheSameDay() {
        List<UsageMath.Interval> ivs = Arrays.asList(
                new UsageMath.Interval("a", utc(2026, Calendar.AUGUST, 28, 9, 0), utc(2026, Calendar.AUGUST, 28, 9, 20)),
                new UsageMath.Interval("b", utc(2026, Calendar.AUGUST, 28, 14, 0), utc(2026, Calendar.AUGUST, 28, 14, 40)));
        long day28 = UsageMath.startOfDay(utc(2026, Calendar.AUGUST, 28, 0, 0), UTC);
        assertEquals(60 * 60_000L, UsageMath.totalForDay(ivs, day28, UTC));
    }

    @Test public void last7DayStartsEndsOnTodayAndIsAscendingAndDistinct() {
        long now = utc(2026, Calendar.AUGUST, 28, 15, 30);
        long[] days = UsageMath.last7DayStarts(now, UTC);
        assertEquals(7, days.length);
        assertEquals(UsageMath.startOfDay(now, UTC), days[6]);
        for (int i = 1; i < days.length; i++) {
            assertTrue(days[i] > days[i - 1]);
        }
    }

    @Test public void addDaysAcrossTheSpringForwardTransitionYieldsAShortDay() {
        // America/New_York DST begins 2026-03-08 02:00 local -> 03:00 local:
        // that calendar day is only 23 hours of real elapsed time.
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        Calendar c = Calendar.getInstance(ny);
        c.clear();
        c.set(2026, Calendar.MARCH, 8, 0, 0, 0);
        long march8Start = c.getTimeInMillis();
        long march9Start = UsageMath.addDays(march8Start, 1, ny);
        assertEquals(23 * 3_600_000L, march9Start - march8Start);
    }

    @Test public void dailyTotalsSplitCorrectlyAcrossTheSpringForwardMidnight() {
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        Calendar c = Calendar.getInstance(ny);
        c.clear();
        c.set(2026, Calendar.MARCH, 7, 23, 0, 0);
        long start = c.getTimeInMillis();
        c.clear();
        c.set(2026, Calendar.MARCH, 8, 1, 0, 0);
        long end = c.getTimeInMillis();
        UsageMath.Interval iv = new UsageMath.Interval("a", start, end);

        c.clear();
        c.set(2026, Calendar.MARCH, 7, 12, 0, 0);
        long day7 = UsageMath.startOfDay(c.getTimeInMillis(), ny);
        c.clear();
        c.set(2026, Calendar.MARCH, 8, 12, 0, 0);
        long day8 = UsageMath.startOfDay(c.getTimeInMillis(), ny);

        assertEquals(60 * 60_000L, UsageMath.totalForDay(Arrays.asList(iv), day7, ny));
        assertEquals(60 * 60_000L, UsageMath.totalForDay(Arrays.asList(iv), day8, ny));
    }

    @Test public void snapLimitClampsToRangeAndSnapsToNearestFifteen() {
        assertEquals(30, UsageMath.snapLimit(0));
        assertEquals(30, UsageMath.snapLimit(30));
        assertEquals(30, UsageMath.snapLimit(37));
        assertEquals(45, UsageMath.snapLimit(38));
        assertEquals(600, UsageMath.snapLimit(9999));
        assertEquals(600, UsageMath.snapLimit(600));
        assertEquals(150, UsageMath.snapLimit(150));
    }

    @Test public void limitStateReportsMinutesLeftUnderLimit() {
        long today = 100 * 60_000L; // 100 minutes used
        assertFalse(UsageMath.isOverLimit(today, 240));
        assertEquals(140, UsageMath.minutesLeft(today, 240));
        assertEquals("140M LEFT", UsageMath.stateLabel(today, 240));
    }

    @Test public void limitStateReportsExceededAtAndOverTheLimit() {
        assertFalse(UsageMath.isOverLimit(240 * 60_000L, 240));
        assertTrue(UsageMath.isOverLimit(241 * 60_000L, 240));
        assertEquals(0, UsageMath.minutesLeft(300 * 60_000L, 240));
        assertEquals("LIMIT EXCEEDED", UsageMath.stateLabel(300 * 60_000L, 240));
    }

    @Test public void usageFractionIsClampedAtZeroAndUncappedAboveOne() {
        assertEquals(0f, UsageMath.usageFraction(0, 240), 0.001f);
        assertEquals(0.5f, UsageMath.usageFraction(120 * 60_000L, 240), 0.001f);
        assertEquals(1.25f, UsageMath.usageFraction(300 * 60_000L, 240), 0.001f);
    }

    private static List<UsageMath.Interval> mixedDay() {
        return Arrays.asList(
                new UsageMath.Interval("com.retro.launcher",
                        utc(2026, Calendar.AUGUST, 28, 9, 0), utc(2026, Calendar.AUGUST, 28, 9, 20)),
                new UsageMath.Interval("com.other.app",
                        utc(2026, Calendar.AUGUST, 28, 10, 0), utc(2026, Calendar.AUGUST, 28, 10, 40)),
                new UsageMath.Interval("com.retro.launcher",
                        utc(2026, Calendar.AUGUST, 28, 11, 0), utc(2026, Calendar.AUGUST, 28, 11, 10)));
    }

    @Test public void excludingDropsEveryIntervalOfThatPackage() {
        List<UsageMath.Interval> kept = UsageMath.excluding(mixedDay(), "com.retro.launcher");
        assertEquals(1, kept.size());
        assertEquals("com.other.app", kept.get(0).pkg);
    }

    @Test public void onlyKeepsEveryIntervalOfThatPackage() {
        long day28 = UsageMath.startOfDay(utc(2026, Calendar.AUGUST, 28, 0, 0), UTC);
        List<UsageMath.Interval> mine = UsageMath.only(mixedDay(), "com.retro.launcher");
        assertEquals(2, mine.size());
        assertEquals(30 * 60_000L, UsageMath.totalForDay(mine, day28, UTC));
    }

    @Test public void excludingAndOnlyIgnoreNullAndUnknownPackages() {
        assertEquals(3, UsageMath.excluding(mixedDay(), null).size());
        assertEquals(3, UsageMath.excluding(mixedDay(), "com.nobody").size());
        assertTrue(UsageMath.only(mixedDay(), null).isEmpty());
        assertTrue(UsageMath.only(mixedDay(), "com.nobody").isEmpty());
    }

    private static UsageMath.Interval iv(String pkg, long from, long to) {
        return new UsageMath.Interval(pkg, from, to);
    }

    private static long sum(List<UsageMath.Interval> ivs) {
        long total = 0;
        for (UsageMath.Interval i : ivs) total += i.endMillis - i.startMillis;
        return total;
    }

    @Test public void mergeCoalescesOverlappingSpansForOnePackage() {
        List<UsageMath.Interval> merged = UsageMath.merge(Arrays.asList(
                iv("a", 0, 100), iv("a", 50, 200)));
        assertEquals(1, merged.size());
        assertEquals(0L, merged.get(0).startMillis);
        assertEquals(200L, merged.get(0).endMillis);
    }

    @Test public void mergeCoalescesTouchingSpans() {
        List<UsageMath.Interval> merged = UsageMath.merge(Arrays.asList(
                iv("a", 0, 100), iv("a", 100, 200)));
        assertEquals(1, merged.size());
        assertEquals(200L, merged.get(0).endMillis);
    }

    @Test public void mergeKeepsSeparatePackagesSeparate() {
        List<UsageMath.Interval> merged = UsageMath.merge(Arrays.asList(
                iv("a", 0, 100), iv("b", 50, 200)));
        assertEquals(2, merged.size());
        assertEquals(250L, sum(merged));
    }

    @Test public void mergeKeepsAGapAsTwoSpans() {
        List<UsageMath.Interval> merged = UsageMath.merge(Arrays.asList(
                iv("a", 0, 100), iv("a", 150, 200)));
        assertEquals(2, merged.size());
        assertEquals(150L, sum(merged));
    }

    @Test public void mergeSwallowsAFullyContainedSpan() {
        List<UsageMath.Interval> merged = UsageMath.merge(Arrays.asList(
                iv("a", 0, 500), iv("a", 100, 200)));
        assertEquals(1, merged.size());
        assertEquals(500L, sum(merged));
    }

    @Test public void mergeHandlesUnsortedInput() {
        List<UsageMath.Interval> merged = UsageMath.merge(Arrays.asList(
                iv("a", 150, 200), iv("a", 0, 100), iv("a", 90, 160)));
        assertEquals(1, merged.size());
        assertEquals(200L, sum(merged));
    }

    @Test public void mergeOfNothingIsNothing() {
        assertTrue(UsageMath.merge(new ArrayList<>()).isEmpty());
    }

    @Test public void intersectClipsSpansToTheWindows() {
        List<UsageMath.Interval> clipped = UsageMath.intersect(
                Arrays.asList(iv("a", 0, 1000)),
                Arrays.asList(iv("!awake", 200, 400), iv("!awake", 600, 700)));
        assertEquals(2, clipped.size());
        assertEquals(300L, sum(clipped));
        assertEquals("a", clipped.get(0).pkg);
    }

    @Test public void intersectDropsSpansEntirelyOutsideEveryWindow() {
        List<UsageMath.Interval> clipped = UsageMath.intersect(
                Arrays.asList(iv("a", 0, 100)),
                Arrays.asList(iv("!awake", 500, 900)));
        assertTrue(clipped.isEmpty());
    }

    @Test public void intersectWithNoWindowsKeepsNothing() {
        List<UsageMath.Interval> clipped = UsageMath.intersect(
                Arrays.asList(iv("a", 0, 100)),
                new ArrayList<>());
        assertTrue(clipped.isEmpty());
    }

    @Test public void intersectPreservesAFullyContainedSpan() {
        List<UsageMath.Interval> clipped = UsageMath.intersect(
                Arrays.asList(iv("a", 200, 300)),
                Arrays.asList(iv("!awake", 0, 1000)));
        assertEquals(1, clipped.size());
        assertEquals(100L, sum(clipped));
    }
}
