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
        // The lunar day is ~24h50m, so at any latitude roughly once a month a
        // calendar day has neither event.
        boolean foundNull = false;
        LocalDate d = LocalDate.of(2026, 3, 1);
        for (int i = 0; i < 30 && !foundNull; i++) {
            if (LunarMath.moonTimes(LAT, LON, d.plusDays(i), ZONE) == null) foundNull = true;
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
}
