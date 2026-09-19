package com.retro.launcher.core;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.*;

/**
 * The moon window's presence, which 2.3.2 made a question the data layer has
 * to answer rather than one it could leave to chance.
 *
 * <p>The disappearance these pin: the network supplies sun times and no moon
 * at all, so {@link WeatherParser#parseSolarTimes} builds the sun-only form,
 * whose window is a NaN pair — and a NaN window is drawn as no moon. 2.3.2
 * repaired such a day after the fact; 2.3.4 stopped persisting the window at
 * all, so the sun-only form is now simply what the cache holds and the moon
 * half is recomputed for the moment being drawn.
 */
public class SolarTimesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 19);

    @Test public void sunOnlyFormHasNoMoonWindow() {
        SolarTimes t = new SolarTimes(6.5f, 19.25f, 6.52f, DAY);
        assertTrue(Float.isNaN(t.moonriseHour));
        assertTrue(Float.isNaN(t.moonsetHour));
    }

    @Test public void aHalfKnownWindowVanishesTheMoonRatherThanHalfDrawingIt() {
        // BodyPath.moonT needs both ends, so one alone must read as no window
        // at all rather than as a position.
        assertTrue(Float.isNaN(BodyPath.moonT(20f,
                new SolarTimes(6.5f, 19.25f, 6.52f, 16.24f, Float.NaN, DAY).moonriseHour,
                new SolarTimes(6.5f, 19.25f, 6.52f, 16.24f, Float.NaN, DAY).moonsetHour)));
        assertTrue(Float.isNaN(BodyPath.moonT(20f,
                new SolarTimes(6.5f, 19.25f, 6.52f, Float.NaN, 23.05f, DAY).moonriseHour,
                new SolarTimes(6.5f, 19.25f, 6.52f, Float.NaN, 23.05f, DAY).moonsetHour)));
    }

    @Test public void withMoonTimesFillsTheWindowAndKeepsTheSunAndDate() {
        SolarTimes filled = new SolarTimes(6.5f, 19.25f, 6.52f, DAY).withMoonTimes(16.24f, 23.05f);

        assertEquals(16.24f, filled.moonriseHour, 1e-4f);
        assertEquals(23.05f, filled.moonsetHour, 1e-4f);
        assertEquals(6.5f, filled.sunriseHour, 1e-4f);
        assertEquals(19.25f, filled.sunsetHour, 1e-4f);
        assertEquals(6.52f, filled.tomorrowSunriseHour, 1e-4f);
        assertEquals(DAY, filled.date);
    }

    @Test public void aFilledWindowSurvivesIntoBodyPath() {
        SolarTimes t = new SolarTimes(6.5f, 19.25f, 6.52f, DAY).withMoonTimes(16.24f, 23.05f);
        float mt = BodyPath.moonT(20f, t.moonriseHour, t.moonsetHour);
        assertFalse("a filled window must place the moon, not vanish it", Float.isNaN(mt));
        assertTrue(mt >= 0f && mt <= 1f);
    }

    @Test public void theSunOnlyFormVanishesTheMoonInBodyPath() {
        SolarTimes t = new SolarTimes(6.5f, 19.25f, 6.52f, DAY);
        assertTrue("NaN in either end reads as 'the moon is not up', at every hour",
                Float.isNaN(BodyPath.moonT(20f, t.moonriseHour, t.moonsetHour)));
    }
}
