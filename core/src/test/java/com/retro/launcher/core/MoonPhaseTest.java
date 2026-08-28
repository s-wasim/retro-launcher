package com.retro.launcher.core;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Reference instants are published lunation times (UTC). One hour of the
 * synodic month is 0.0014 of a cycle, so a 0.002 tolerance holds the
 * algorithm to roughly ±90 minutes — an order of magnitude finer than the
 * 12-pixel disc can render.
 */
public class MoonPhaseTest {

    private static final float TOL = 0.002f;

    private static long at(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    /** Compares on the circle: 0.9998 and 0.0002 are 0.0004 apart, not 0.9996. */
    private static void assertPhase(float expected, long millis) {
        float actual = MoonPhase.phase(millis);
        float diff = Math.abs(actual - expected);
        assertEquals(0f, Math.min(diff, 1f - diff), TOL);
    }

    @Test public void newMoonReadsAsZero() {
        assertPhase(0f, at("2024-01-11T11:57:00Z"));
        assertPhase(0f, at("2000-01-06T18:14:00Z"));
        assertPhase(0f, at("2026-08-12T17:37:00Z"));
    }

    @Test public void firstQuarterReadsAsAQuarter() {
        assertPhase(0.25f, at("2024-01-18T03:53:00Z"));
    }

    @Test public void fullMoonReadsAsAHalf() {
        assertPhase(0.5f, at("2024-01-25T17:54:00Z"));
        assertPhase(0.5f, at("2026-08-28T04:18:00Z"));
    }

    @Test public void lastQuarterReadsAsThreeQuarters() {
        assertPhase(0.75f, at("2024-02-02T23:18:00Z"));
    }

    @Test public void phaseStaysInRange() {
        long start = at("2024-01-01T00:00:00Z");
        for (int hour = 0; hour < 24 * 400; hour += 7) {
            float p = MoonPhase.phase(start + hour * 3_600_000L);
            assertTrue("phase " + p + " below range", p >= 0f);
            assertTrue("phase " + p + " above range", p < 1f);
        }
    }

    /** One synodic month later is the same phase — the drift over a single
     *  cycle is smaller than the mean-month approximation itself. */
    @Test public void repeatsEverySynodicMonth() {
        long start = at("2026-03-01T00:00:00Z");
        long later = start + Math.round(MoonPhase.SYNODIC_DAYS * 86_400_000.0);
        float a = MoonPhase.phase(start), b = MoonPhase.phase(later);
        float diff = Math.abs(a - b);
        assertEquals(0f, Math.min(diff, 1f - diff), 0.02f);
    }

    @Test public void illuminationTracksThePhase() {
        assertEquals(0f, MoonPhase.illuminationFor(0f), 1e-6f);
        assertEquals(0.5f, MoonPhase.illuminationFor(0.25f), 1e-6f);
        assertEquals(1f, MoonPhase.illuminationFor(0.5f), 1e-6f);
        assertEquals(0.5f, MoonPhase.illuminationFor(0.75f), 1e-6f);
        assertEquals(1f, MoonPhase.illumination(at("2024-01-25T17:54:00Z")), 0.001f);
        assertEquals(0f, MoonPhase.illumination(at("2024-01-11T11:57:00Z")), 0.001f);
    }

    @Test public void hemisphereComesFromLatitudeSign() {
        assertTrue(MoonPhase.southernView(-33.9f));     // Sydney
        assertFalse(MoonPhase.southernView(31.5f));     // Lahore
        assertFalse(MoonPhase.southernView(0f));
    }

    /** No fix yet must not silently mean "southern". */
    @Test public void unknownLatitudeReadsAsNorthern() {
        assertFalse(MoonPhase.southernView(Float.NaN));
    }
}
