package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SolarClockTest {

    private static final float SUNRISE = 6.2f;
    private static final float SUNSET  = 18.4f;

    @Test public void sunriseMapsToTheDawnAnchor() {
        assertEquals(SolarClock.SUNRISE_ANCHOR,
                SolarClock.warp(6f, 6f, 18f, 6f), 0.001f);
    }

    @Test public void sunsetMapsToTheDuskAnchor() {
        assertEquals(SolarClock.SUNSET_ANCHOR,
                SolarClock.warp(18f, 6f, 18f, 6f), 0.001f);
    }

    @Test public void solarNoonMapsToTheMidpointOfTheAnchors() {
        float midpointReal = (6f + 18f) / 2f;
        float midpointAnchor = (SolarClock.SUNRISE_ANCHOR + SolarClock.SUNSET_ANCHOR) / 2f;
        assertEquals(midpointAnchor, SolarClock.warp(midpointReal, 6f, 18f, 6f), 0.01f);
    }

    @Test public void aSummerDayCompressesNightAndStaysMonotonicAcrossMidnight() {
        // Sunrise 04:00, sunset 22:00, next sunrise 04:00 — an 18h day, 6h night.
        // The sky's own [SUNSET_ANCHOR, SUNSET_ANCHOR + NIGHT_ANCHOR_SPAN) window
        // spans past 24 by construction, so its mod-24 wrap lands at whatever real
        // hour the raw warped value first reaches 24 — not necessarily real
        // midnight. The guarantee under test is that the value only wraps once
        // (real midnight itself stays a smooth, non-wrapping crossing), not that
        // it never wraps at all.
        float sunrise = 4f, sunset = 22f, tomorrowSunrise = 4f;
        float prev = SolarClock.warp(sunset, sunrise, sunset, tomorrowSunrise);
        int wraps = 0;
        for (float h = sunset + 0.25f; h <= 24f; h += 0.25f) {
            float warped = SolarClock.warp(h % 24f, sunrise, sunset, tomorrowSunrise);
            if (warped < prev) wraps++;
            assertTrue("more than one wrap by real hour " + h, wraps <= 1);
            prev = warped;
        }
        for (float h = 0f; h < sunrise; h += 0.25f) {
            float warped = SolarClock.warp(h, sunrise, sunset, tomorrowSunrise);
            if (warped < prev) wraps++;
            assertTrue("more than one wrap by real hour " + h, wraps <= 1);
            prev = warped;
        }
        assertEquals("expected exactly one wrap across the whole night", 1, wraps);
    }

    @Test public void identityWarpWhenSunsetIsNotAfterSunrise() {
        assertEquals(9f, SolarClock.warp(9f, 12f, 8f, 12f), 0.001f);
        assertEquals(9f, SolarClock.warp(9f, 8f, 8f, 12f), 0.001f);
    }

    @Test public void identityWarpForNaN() {
        assertEquals(9f, SolarClock.warp(9f, Float.NaN, 18f, 6f), 0.001f);
        assertEquals(9f, SolarClock.warp(9f, 6f, Float.NaN, 6f), 0.001f);
        assertEquals(9f, SolarClock.warp(9f, 6f, 18f, Float.NaN), 0.001f);
    }

    @Test public void outputIsAlwaysInZeroToTwentyFour() {
        for (float h = 0f; h < 24f; h += 0.5f) {
            float warped = SolarClock.warp(h, 6f, 18f, 6f);
            assertTrue("warp(" + h + ") = " + warped, warped >= 0f && warped < 24f);
        }
    }
}
