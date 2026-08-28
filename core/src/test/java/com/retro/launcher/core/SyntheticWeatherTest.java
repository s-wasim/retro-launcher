package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SyntheticWeatherTest {

    @Test public void bandBoundariesMatchTheSourceTableRain() {
        assertEquals("CLEAR",         SyntheticWeather.label(0.00f, false));
        assertEquals("CLEAR",         SyntheticWeather.label(0.069f, false));
        assertEquals("HAZY",          SyntheticWeather.label(0.07f, false));
        assertEquals("HAZY",          SyntheticWeather.label(0.179f, false));
        assertEquals("FAIR",          SyntheticWeather.label(0.18f, false));
        assertEquals("FAIR",          SyntheticWeather.label(0.299f, false));
        assertEquals("PARTLY CLOUDY", SyntheticWeather.label(0.30f, false));
        assertEquals("PARTLY CLOUDY", SyntheticWeather.label(0.419f, false));
        assertEquals("CLOUDY",        SyntheticWeather.label(0.42f, false));
        assertEquals("CLOUDY",        SyntheticWeather.label(0.539f, false));
        assertEquals("OVERCAST",      SyntheticWeather.label(0.54f, false));
        assertEquals("OVERCAST",      SyntheticWeather.label(0.639f, false));
        assertEquals("LIGHT RAIN",    SyntheticWeather.label(0.64f, false));
        assertEquals("LIGHT RAIN",    SyntheticWeather.label(0.759f, false));
        assertEquals("RAIN",          SyntheticWeather.label(0.76f, false));
        assertEquals("RAIN",          SyntheticWeather.label(0.869f, false));
        assertEquals("DOWNPOUR",      SyntheticWeather.label(0.87f, false));
        assertEquals("DOWNPOUR",      SyntheticWeather.label(0.949f, false));
        assertEquals("THUNDERSTORM",  SyntheticWeather.label(0.95f, false));
        assertEquals("THUNDERSTORM",  SyntheticWeather.label(1.00f, false));
    }

    @Test public void snowVariantsReplaceThePrecipitationBands() {
        assertEquals("LIGHT SNOW", SyntheticWeather.label(0.64f, true));
        assertEquals("SNOW",       SyntheticWeather.label(0.76f, true));
        assertEquals("HEAVY SNOW", SyntheticWeather.label(0.87f, true));
        assertEquals("BLIZZARD",   SyntheticWeather.label(0.95f, true));
        // Non-precipitation bands are unaffected by the snow flag.
        assertEquals("CLEAR", SyntheticWeather.label(0f, true));
    }

    @Test public void noonIsWarmerThanMidnightAtTheSameWeather() {
        Weather noon = SyntheticWeather.at(12f, 0f, false);
        Weather midnight = SyntheticWeather.at(0f, 0f, false);
        assertTrue(noon.tempC > midnight.tempC);
    }

    @Test public void aStormIsColderThanClearAtTheSameHour() {
        Weather clear = SyntheticWeather.at(12f, 0f, false);
        Weather storm = SyntheticWeather.at(12f, 0.95f, false);
        assertTrue(storm.tempC < clear.tempC);
    }

    @Test public void fahrenheitConversionAtKnownPoints() {
        assertEquals(32, new Weather(0, "CLEAR", 0f).tempIn("F"));
        assertEquals(212, new Weather(100, "CLEAR", 0f).tempIn("F"));
        assertEquals(0, new Weather(0, "CLEAR", 0f).tempIn("C"));
    }

    @Test public void wIsAlwaysReturnedInUnitRange() {
        for (float raw = -0.5f; raw <= 1.5f; raw += 0.1f) {
            Weather w = SyntheticWeather.at(9f, raw, false);
            assertTrue(w.w >= 0f && w.w <= 1f);
        }
    }
}
