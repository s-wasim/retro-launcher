package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

import java.time.LocalDate;
import java.time.ZoneId;

public class LunarMathTest {

    private static final float LAT = 52.52f, LON = 13.4f;
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /** The instant {@code hour} names on {@code date}'s wall clock. Built
     *  through the local time rather than by adding elapsed milliseconds to
     *  midnight, so a 23- or 25-hour DST day still lands on the hour asked
     *  for. */
    private static long at(LocalDate date, double hour, ZoneId zone) {
        int h = (int) hour;
        int m = (int) Math.round((hour - h) * 60);
        return date.atTime(h, m).atZone(zone).toInstant().toEpochMilli();
    }

    /** What that instant actually reads on the clock — the same number the
     *  sky renders against. Not always the hour asked for: a spring-forward
     *  gap resolves to the hour after it. */
    private static float clockHour(long millis, ZoneId zone) {
        java.time.ZonedDateTime z = java.time.Instant.ofEpochMilli(millis).atZone(zone);
        return z.getHour() + z.getMinute() / 60f;
    }

    private static LunarMath.LunarTimes window(float lat, float lon, LocalDate date, double hour, ZoneId zone) {
        return LunarMath.moonWindow(lat, lon, at(date, hour, zone), zone);
    }

    // ---- the contract ----------------------------------------------------

    @Test public void aWindowAlwaysSetsAfterItRises() {
        // 2.3.4's whole point: the hours are offsets from local midnight, not
        // clock readings, so there is no wrapped encoding to decode and the
        // set is unconditionally the larger number.
        LocalDate d = LocalDate.of(2026, 1, 1);
        int seen = 0;
        for (int i = 0; i < 400; i++) {
            for (double h : new double[] { 0.5, 6, 12, 18, 23.5 }) {
                LunarMath.LunarTimes t = window(LAT, LON, d.plusDays(i), h, ZONE);
                if (t == null) continue;
                seen++;
                assertFalse(Float.isNaN(t.moonriseHour));
                assertFalse(Float.isNaN(t.moonsetHour));
                assertTrue("set must follow rise on " + d.plusDays(i) + " at " + h
                                + ": " + t.moonriseHour + ".." + t.moonsetHour,
                        t.moonsetHour > t.moonriseHour);
            }
        }
        assertTrue(seen > 1500);
    }

    @Test public void aWindowIsEitherCompleteOrAbsentNeverPartial() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 400; i++) {
            LunarMath.LunarTimes t = window(LAT, LON, d.plusDays(i), 12, ZONE);
            if (t == null) continue;
            assertTrue("partial window on " + d.plusDays(i),
                    !Float.isNaN(t.moonriseHour) && !Float.isNaN(t.moonsetHour));
        }
    }

    @Test public void aWindowIsNeverLongerThanTheMoonCanActuallyBeUp() {
        // At Berlin's latitude the moon is up at most about 17 hours. A pair
        // stitched together from two different up-periods — the pre-2.3.4
        // failure — shows up here as a span well past that.
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 365; i++) {
            LunarMath.LunarTimes t = window(LAT, LON, d.plusDays(i), 12, ZONE);
            if (t == null) continue;
            float span = t.moonsetHour - t.moonriseHour;
            // Berlin's genuine maximum in 2026 is 18h41m, so anything past
            // nineteen and a half is two windows stitched together rather
            // than a long one.
            assertTrue("implausible span on " + d.plusDays(i) + ": " + span, span < 19.5f);
        }
    }

    // ---- the window actually contains the moment asked about -------------

    @Test public void theWindowBracketsTheMomentWheneverTheMoonIsUp() {
        // Sampled every twenty minutes across a lunar month: whenever the
        // window covers the instant at all, the clock hour of that instant
        // must land inside it, and t must be a drawable 0..1.
        LocalDate d = LocalDate.of(2026, 3, 1);
        int inside = 0;
        for (int i = 0; i < 30; i++) {
            LocalDate date = d.plusDays(i);
            for (int m = 0; m < 24 * 60; m += 20) {
                long now = at(date, m / 60f, ZONE);
                float hour = clockHour(now, ZONE);
                LunarMath.LunarTimes t = LunarMath.moonWindow(LAT, LON, now, ZONE);
                if (t == null) continue;
                if (hour < t.moonriseHour) continue;      // down, waiting to rise
                inside++;
                assertTrue("moment past its own window on " + date + " " + hour,
                        hour <= t.moonsetHour);
                float pos = BodyPath.moonT(hour, t.moonriseHour, t.moonsetHour);
                assertFalse(Float.isNaN(pos));
                assertTrue("moonT out of range: " + pos, pos >= 0f && pos <= 1f);
            }
        }
        assertTrue("a lunar month should spend plenty of time with the moon up", inside > 500);
    }

    @Test public void aWindowThatOpenedYesterdayReportsANegativeRise() {
        // The small hours of a day whose moon rose the previous evening. This
        // is the case the pre-2.3.4 code could only express by wrapping, and
        // the one it paired with the wrong set.
        LocalDate d = LocalDate.of(2026, 1, 1);
        int negative = 0;
        for (int i = 0; i < 365 && negative == 0; i++) {
            LunarMath.LunarTimes t = window(LAT, LON, d.plusDays(i), 1.0, ZONE);
            if (t != null && t.moonriseHour < 0f) negative++;
        }
        assertTrue("a year must contain windows that opened before midnight", negative > 0);
    }

    @Test public void aWindowThatClosesTomorrowReportsASetPastTwentyFour() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        int past = 0;
        for (int i = 0; i < 365 && past == 0; i++) {
            LunarMath.LunarTimes t = window(LAT, LON, d.plusDays(i), 23.0, ZONE);
            if (t != null && t.moonsetHour > 24f) past++;
        }
        assertTrue("a year must contain windows that close after midnight", past > 0);
    }

    /**
     * The regression, as a specific dated case. On 2026-09-21 in Pakistan the
     * moon sets at 00:19 (the tail of the 20th's rise) and rises again at
     * 15:12, setting at 01:19 on the 22nd. The pre-2.3.4 code took the day's
     * first rise and the day's first set — 15:10 and 00:20 — and paired two
     * different windows, retiring the evening moon an hour early.
     */
    @Test public void theEveningWindowKeepsItsOwnSetNotTheMorningsOne() {
        ZoneId pk = ZoneId.of("Asia/Karachi");
        LocalDate date = LocalDate.of(2026, 9, 21);
        LunarMath.LunarTimes evening = window(33.68f, 73.05f, date, 18.0, pk);
        assertNotNull(evening);
        assertEquals("rises mid-afternoon", 15.2f, evening.moonriseHour, 0.25f);
        assertEquals("sets after midnight, not before it", 25.3f, evening.moonsetHour, 0.25f);

        // And the tail of the previous window is still answered correctly for
        // a moment inside it.
        LunarMath.LunarTimes smallHours = window(33.68f, 73.05f, date, 0.1, pk);
        assertNotNull(smallHours);
        assertTrue("rose the previous afternoon", smallHours.moonriseHour < 0f);
        assertEquals("sets just after midnight", 0.32f, smallHours.moonsetHour, 0.25f);
    }

    // ---- the astronomy ---------------------------------------------------

    @Test public void fullMoonRisesNearSunsetAndSetsNearSunrise() {
        // A full moon is, by definition, opposite the sun: it rises as the
        // sun sets and sets as the sun rises. Cross-checking against
        // SolarMath's already-tested sunrise/sunset avoids hardcoding an
        // external "known good" moonrise table this test can't independently
        // verify.
        LocalDate date = nearestFullMoonDate();
        SolarTimes sun = SolarMath.sunTimes(LAT, LON, date, ZONE);
        // Asked about the evening, so the window is the night's own, not the
        // morning tail that a full-moon day also carries.
        LunarMath.LunarTimes moon = window(LAT, LON, date, 20.0, ZONE);
        assertNotNull(sun);
        assertNotNull(moon);

        assertEquals(sun.sunsetHour, moon.moonriseHour, 1.0f);
        // Sets near tomorrow's sunrise, so past 24 on this day's clock.
        assertEquals(sun.sunriseHour + 24f, moon.moonsetHour, 1.0f);
    }

    private static LocalDate nearestFullMoonDate() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate best = start;
        float bestDist = 1f;
        for (int i = 0; i < 400; i++) {
            LocalDate candidate = start.plusDays(i);
            long millis = candidate.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
            float dist = Math.abs(MoonPhase.phase(millis) - 0.5f);
            if (dist < bestDist) { bestDist = dist; best = candidate; }
        }
        return best;
    }

    @Test public void aMoonUpAllDayIsDrawnRatherThanDiscarded() {
        // Above ~61 degrees the moon can stay up for a whole calendar day.
        // That produces no crossing, which used to be indistinguishable from
        // "never up" and drew nothing.
        LocalDate d = LocalDate.of(2026, 1, 1);
        int allDay = 0;
        for (int i = 0; i < 365; i++) {
            LunarMath.LunarTimes t = window(70f, 15f, d.plusDays(i), 12, ZoneId.of("UTC"));
            if (t == null) continue;
            assertTrue(t.moonsetHour > t.moonriseHour);
            if (t.moonriseHour <= 0f && t.moonsetHour >= 24f) {
                allDay++;
                float pos = BodyPath.moonT(12f, t.moonriseHour, t.moonsetHour);
                assertFalse(Float.isNaN(pos));
                assertTrue(pos > 0f && pos < 1f);
            }
        }
        assertTrue("a year at 70N should contain moon-up-all-day", allDay > 0);
    }

    @Test public void aMoonDownAllDayIsReportedAsAbsent() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        int absent = 0;
        for (int i = 0; i < 365 && absent == 0; i++) {
            if (window(78f, 15f, d.plusDays(i), 12, ZoneId.of("UTC")) == null) absent++;
        }
        assertTrue("a year at 78N should contain a day the moon never appears", absent > 0);
    }

    @Test public void aPolarSummerNightGivesAConsistentAnswer() {
        LunarMath.LunarTimes t = window(78f, 15f, LocalDate.of(2026, 6, 21), 12, ZoneId.of("UTC"));
        if (t != null) assertTrue(t.moonsetHour > t.moonriseHour);
    }

    @Test public void aDaylightSavingTransitionDoesNotSkewTheWindow() {
        // Berlin's spring-forward day is 23 hours long. The hours reported are
        // clock readings offset from midnight, not elapsed milliseconds, so a
        // window spanning the jump still lines up with the hour the sky
        // renders against.
        LocalDate springForward = LocalDate.of(2026, 3, 29);
        for (double h : new double[] { 0.5, 4, 12, 22 }) {
            LunarMath.LunarTimes t = window(LAT, LON, springForward, h, ZONE);
            if (t == null) continue;
            assertTrue(t.moonsetHour > t.moonriseHour);
            assertTrue(t.moonsetHour - t.moonriseHour < 18f);
        }
    }
}
