package com.retro.launcher.core;

import org.junit.Test;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.junit.Assert.*;

public class SolarMathTest {

    /** Two minutes, in decimal hours — the precision the spec claims. */
    private static final float TOL_HOURS = 2f / 60f;

    @Test public void newYorkSummerSolstice() {
        SolarTimes t = SolarMath.sunTimes(40.7128f, -74.0060f,
                LocalDate.of(2026, 6, 21), ZoneId.of("America/New_York"));
        assertNotNull(t);
        assertEquals(5.4214f, t.sunriseHour, TOL_HOURS);
        assertEquals(20.5081f, t.sunsetHour, TOL_HOURS);
        assertEquals(5.4253f, t.tomorrowSunriseHour, TOL_HOURS);
        assertEquals(LocalDate.of(2026, 6, 21), t.date);
    }

    @Test public void londonNearEquinox() {
        SolarTimes t = SolarMath.sunTimes(51.5074f, -0.1278f,
                LocalDate.of(2026, 3, 20), ZoneId.of("Europe/London"));
        assertNotNull(t);
        assertEquals(6.0608f, t.sunriseHour, TOL_HOURS);
        assertEquals(18.2206f, t.sunsetHour, TOL_HOURS);
        assertEquals(6.0228f, t.tomorrowSunriseHour, TOL_HOURS);
    }

    @Test public void sydneySummerSolstice() {
        SolarTimes t = SolarMath.sunTimes(-33.8688f, 151.2093f,
                LocalDate.of(2026, 12, 21), ZoneId.of("Australia/Sydney"));
        assertNotNull(t);
        assertEquals(5.6814f, t.sunriseHour, TOL_HOURS);
        assertEquals(20.0864f, t.sunsetHour, TOL_HOURS);
        assertEquals(5.6894f, t.tomorrowSunriseHour, TOL_HOURS);
    }

    /** Tromsø, Norway, well inside the Arctic Circle, at the summer
     *  solstice: the sun never sets. No sunrise/sunset exists that day. */
    @Test public void polarDayReturnsNull() {
        SolarTimes t = SolarMath.sunTimes(69.6492f, 18.9553f,
                LocalDate.of(2026, 6, 21), ZoneId.of("Europe/Oslo"));
        assertNull(t);
    }

    @Test public void everyHourIsInRange() {
        SolarTimes t = SolarMath.sunTimes(51.5074f, -0.1278f,
                LocalDate.of(2026, 3, 20), ZoneId.of("Europe/London"));
        assertNotNull(t);
        assertTrue(t.sunriseHour >= 0f && t.sunriseHour < 24f);
        assertTrue(t.sunsetHour >= 0f && t.sunsetHour < 24f);
        assertTrue(t.tomorrowSunriseHour >= 0f && t.tomorrowSunriseHour < 24f);
    }
}
