package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

import java.time.LocalDate;
import java.time.ZoneId;

public class LunarMathTest {

    private static final float LAT = 52.52f, LON = 13.4f;
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    @Test public void returnsHoursInRangeWhenTheMoonRisesAndSets() {
        boolean sawAResult = false;
        LocalDate d = LocalDate.of(2026, 3, 1);
        for (int i = 0; i < 30; i++) {
            LunarMath.LunarTimes t = LunarMath.moonTimes(LAT, LON, d.plusDays(i), ZONE);
            if (t == null) continue;
            sawAResult = true;
            if (!Float.isNaN(t.moonriseHour)) {
                assertTrue(t.moonriseHour >= 0f && t.moonriseHour < 24f);
            }
            if (!Float.isNaN(t.moonsetHour)) {
                assertTrue(t.moonsetHour >= 0f && t.moonsetHour < 24f);
            }
        }
        assertTrue(sawAResult);
    }

    @Test public void someDayInALunarMonthHasNeitherARiseNorASet() {
        // A calendar day with *neither* event (both rise and set skip it) is
        // not the common "24h50m lunar day" skip — that usually drops only
        // one of the two onto a neighbouring day (see
        // someDayHasTheMoonSetBeforeItRisesAgain / the returns-in-range
        // test), and empirically never both at once at Berlin's latitude
        // across a full year of scanning. A full "neither" day is the
        // moon's own near-polar-night analogue, reliable only at higher
        // latitudes — verified here at 70N over the same March 2026 window
        // used by the other tests in this file.
        boolean foundNull = false;
        float highLat = 70f;
        LocalDate d = LocalDate.of(2026, 3, 1);
        for (int i = 0; i < 30 && !foundNull; i++) {
            if (LunarMath.moonTimes(highLat, LON, d.plusDays(i), ZONE) == null) foundNull = true;
        }
        assertTrue("expected at least one no-rise-no-set day across a lunar month", foundNull);
    }

    @Test public void someDayHasTheMoonSetBeforeItRisesAgain() {
        // Roughly half of all days: the moon is already up at local midnight
        // (it rose the previous day), sets that morning, then rises again
        // later the same day for the night ahead.
        boolean found = false;
        LocalDate d = LocalDate.of(2026, 3, 1);
        for (int i = 0; i < 30 && !found; i++) {
            LunarMath.LunarTimes t = LunarMath.moonTimes(LAT, LON, d.plusDays(i), ZONE);
            if (t != null && !Float.isNaN(t.moonriseHour) && !Float.isNaN(t.moonsetHour)
                    && t.moonsetHour < t.moonriseHour) {
                found = true;
            }
        }
        assertTrue("expected at least one set-before-rise day in a month", found);
    }

    @Test public void fullMoonRisesNearSunsetAndSetsNearSunrise() {
        // A full moon is, by definition, opposite the sun: it rises as the
        // sun sets and sets as the sun rises. Cross-checking against
        // SolarMath's already-tested sunrise/sunset avoids hardcoding an
        // external "known good" moonrise table this test can't independently
        // verify.
        LocalDate date = nearestFullMoonDate();
        SolarTimes sun = SolarMath.sunTimes(LAT, LON, date, ZONE);
        LunarMath.LunarTimes moon = LunarMath.moonTimes(LAT, LON, date, ZONE);
        assertNotNull(sun);
        assertNotNull(moon);

        if (!Float.isNaN(moon.moonriseHour)) {
            assertEquals(sun.sunsetHour, moon.moonriseHour, 1.0f);
        }
        if (!Float.isNaN(moon.moonsetHour)) {
            assertEquals(sun.sunriseHour, moon.moonsetHour, 1.0f);
        }
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

    @Test public void aPolarSummerNightGivesAConsistentAnswer() {
        // Very high latitude: must not throw, and must return either null or
        // in-range hours — never NaN mixed with an out-of-range number.
        LunarMath.LunarTimes t = LunarMath.moonTimes(78f, 15f, LocalDate.of(2026, 6, 21), ZoneId.of("UTC"));
        if (t != null) {
            if (!Float.isNaN(t.moonriseHour)) assertTrue(t.moonriseHour >= 0f && t.moonriseHour < 24f);
            if (!Float.isNaN(t.moonsetHour)) assertTrue(t.moonsetHour >= 0f && t.moonsetHour < 24f);
        }
    }

    // ---- 2.3.3: windows that cross midnight ------------------------------

    /** Half-known: one end found, the other left NaN. The state that drew no
     *  moon for a whole day, and DESIGN_NOTES delta 34's deferred bug. */
    private static boolean halfKnown(LunarMath.LunarTimes t) {
        if (t == null) return false;
        return Float.isNaN(t.moonriseHour) != Float.isNaN(t.moonsetHour);
    }

    @Test public void noDayOfAYearIsLeftWithHalfAWindow() {
        // The regression itself. Before 2.3.3 this found roughly two days a
        // month at every latitude tested: the moon rises late and sets after
        // midnight, or set in the small hours having risen the day before,
        // and scanning only the calendar day found one end and not the other.
        String[] zones = { "Europe/Berlin", "Asia/Karachi", "America/New_York" };
        float[] lats = { 52.5f, 24.9f, 40.7f };
        float[] lons = { 13.4f, 67.0f, -74.0f };

        for (int z = 0; z < zones.length; z++) {
            ZoneId zone = ZoneId.of(zones[z]);
            LocalDate d = LocalDate.of(2026, 1, 1);
            for (int i = 0; i < 365; i++) {
                LunarMath.LunarTimes t = LunarMath.moonTimes(lats[z], lons[z], d.plusDays(i), zone);
                assertFalse(zones[z] + " " + d.plusDays(i) + " has half a window: "
                                + (t == null ? "null" : t.moonriseHour + ".." + t.moonsetHour),
                        halfKnown(t));
            }
        }
    }

    @Test public void aWindowIsEitherCompleteOrAbsentNeverPartial() {
        // Stated as the invariant rather than as a count, because "complete
        // or absent" is exactly what BodyPath.moonT can consume.
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 400; i++) {
            LunarMath.LunarTimes t = LunarMath.moonTimes(52.5f, 13.4f, d.plusDays(i), ZONE);
            if (t == null) continue;
            boolean complete = !Float.isNaN(t.moonriseHour) && !Float.isNaN(t.moonsetHour);
            assertTrue("partial window on " + d.plusDays(i), complete);
        }
    }

    @Test public void everyReportedHourStaysInRange() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 400; i++) {
            LunarMath.LunarTimes t = LunarMath.moonTimes(52.5f, 13.4f, d.plusDays(i), ZONE);
            if (t == null) continue;
            assertTrue(t.moonriseHour >= 0f && t.moonriseHour < 24f);
            assertTrue(t.moonsetHour >= 0f && t.moonsetHour < 24f);
        }
    }

    @Test public void aCrossMidnightWindowActuallyOccursAndIsDrawable() {
        // Not just "no NaN" — the window has to be one BodyPath can turn
        // into a position. A set earlier than the rise is the wrap case.
        LocalDate d = LocalDate.of(2026, 1, 1);
        int wrapped = 0;
        for (int i = 0; i < 365; i++) {
            LunarMath.LunarTimes t = LunarMath.moonTimes(52.5f, 13.4f, d.plusDays(i), ZONE);
            if (t == null || Float.isNaN(t.moonriseHour) || Float.isNaN(t.moonsetHour)) continue;
            if (t.moonsetHour >= t.moonriseHour) continue;
            wrapped++;
            // Just after rising, and just before setting, the moon must be
            // on screen — t in [0,1] — rather than NaN.
            float justAfterRise = Math.min(23.99f, t.moonriseHour + 0.5f);
            float justBeforeSet = Math.max(0.01f, t.moonsetHour - 0.5f);
            for (float hour : new float[] { justAfterRise, justBeforeSet }) {
                float pos = BodyPath.moonT(hour, t.moonriseHour, t.moonsetHour);
                assertFalse("moonT went NaN inside its own window on " + d.plusDays(i),
                        Float.isNaN(pos));
                assertTrue("moonT out of range: " + pos, pos >= 0f && pos <= 1f);
            }
        }
        assertTrue("a year should contain cross-midnight windows", wrapped > 0);
    }

    @Test public void aMoonUpAllDayIsDrawnRatherThanDiscarded() {
        // Above ~61 degrees the moon can stay up for a whole calendar day.
        // That produces no crossing, which used to be indistinguishable from
        // "never up" and drew nothing. Both still must be one or the other,
        // never a partial window, and an all-day window must be drawable.
        LocalDate d = LocalDate.of(2026, 1, 1);
        int allDay = 0;
        for (int i = 0; i < 365; i++) {
            LunarMath.LunarTimes t = LunarMath.moonTimes(70f, 15f, d.plusDays(i), ZoneId.of("UTC"));
            assertFalse("half a window at 70N on " + d.plusDays(i), halfKnown(t));
            if (t == null) continue;
            if (t.moonriseHour == 0f && t.moonsetHour > 23.9f) {
                allDay++;
                float pos = BodyPath.moonT(12f, t.moonriseHour, t.moonsetHour);
                assertFalse(Float.isNaN(pos));
                assertTrue(pos > 0f && pos < 1f);
            }
        }
        assertTrue("a year at 70N should contain moon-up-all-day", allDay > 0);
    }
}
