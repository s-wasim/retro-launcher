package com.retro.launcher.core;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.*;

/**
 * The moon window's presence, which 2.3.2 made a question the data layer has
 * to answer rather than one it could leave to chance.
 *
 * <p>The disappearance these pin: the network supplies sun times and no moon
 * at all, so {@link WeatherParser#parseSolarTimes} builds the sun-only form.
 * Persisted as it came, that NaN pair replaced the computed window for the
 * day, and a NaN window is drawn as no moon.
 */
public class SolarTimesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 19);

    @Test public void sunOnlyFormHasNoMoonWindow() {
        SolarTimes t = new SolarTimes(6.5f, 19.25f, 6.52f, DAY);
        assertTrue(Float.isNaN(t.moonriseHour));
        assertTrue(Float.isNaN(t.moonsetHour));
        assertFalse("the shape the network path produces must read as moonless",
                t.hasMoonTimes());
    }

    @Test public void aHalfKnownWindowIsWorthNoWindow() {
        // BodyPath.moonT needs both ends, so one alone must not read as a
        // window anyone can draw from.
        assertFalse(new SolarTimes(6.5f, 19.25f, 6.52f, 16.24f, Float.NaN, DAY).hasMoonTimes());
        assertFalse(new SolarTimes(6.5f, 19.25f, 6.52f, Float.NaN, 23.05f, DAY).hasMoonTimes());
        assertTrue(new SolarTimes(6.5f, 19.25f, 6.52f, 16.24f, 23.05f, DAY).hasMoonTimes());
    }

    @Test public void withMoonTimesFillsTheWindowAndKeepsTheSunAndDate() {
        SolarTimes filled = new SolarTimes(6.5f, 19.25f, 6.52f, DAY).withMoonTimes(16.24f, 23.05f);

        assertTrue(filled.hasMoonTimes());
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
