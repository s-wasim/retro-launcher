package com.retro.launcher.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 2.3.1. The rule that matters most here is the negative one: CAPE grades a
 * storm and must never declare one. A hot humid afternoon reads 2000+ J/kg
 * with a cloudless sky, and reading that as lightning would be a worse bug
 * than having no intensity at all — so that case is tested first and tested
 * hardest.
 */
public class ThunderIntensityTest {

    // ---- CAPE must never invent a storm -----------------------------------

    @Test public void capeAloneNeverProducesAStorm() {
        for (float cape = 0f; cape <= 8000f; cape += 100f) {
            assertEquals("cape " + cape + " conjured a storm with no thunder code",
                    ThunderIntensity.NONE,
                    ThunderIntensity.levelFor(false, 0, cape));
        }
    }

    @Test public void aClearHotAfternoonWithHugeCapeIsStillNotAStorm() {
        // WMO 0 is "clear sky". This is the exact false positive the design
        // is guarding against.
        assertEquals(ThunderIntensity.NONE, ThunderIntensity.levelFor(false, 0, 3500f));
    }

    @Test public void rainWithoutThunderIsNotGraded() {
        assertEquals(ThunderIntensity.NONE, ThunderIntensity.levelFor(false, 65, 2600f));
    }

    // ---- the WMO floor, for a reading with no CAPE -------------------------

    @Test public void aPlainThunderstormWithNoCapeReadsAsLight() {
        // The "text only" case: no number from the API, but 95 still means
        // something and must yield a realistic level rather than zero.
        assertEquals(ThunderIntensity.LIGHT, ThunderIntensity.levelFor(true, 95, null));
    }

    @Test public void slightHailWithNoCapeReadsAsSevere() {
        assertEquals(ThunderIntensity.SEVERE, ThunderIntensity.levelFor(true, 96, null));
    }

    @Test public void heavyHailWithNoCapeReadsAsExtreme() {
        assertEquals(ThunderIntensity.EXTREME, ThunderIntensity.levelFor(true, 99, null));
    }

    @Test public void theCodeFloorIsOrderedByHail() {
        assertTrue(ThunderIntensity.codeFloor(95) < ThunderIntensity.codeFloor(96));
        assertTrue(ThunderIntensity.codeFloor(96) < ThunderIntensity.codeFloor(99));
    }

    @Test public void aNonThunderCodeHasNoFloor() {
        for (int code : new int[] { 0, 1, 2, 3, 45, 48, 51, 61, 65, 71, 80, 82, 85, 86 }) {
            assertEquals("code " + code, ThunderIntensity.NONE, ThunderIntensity.codeFloor(code));
        }
    }

    // ---- CAPE grading ------------------------------------------------------

    @Test public void capeBandsMapToTheConventionalLevels() {
        assertEquals(ThunderIntensity.DISTANT,  ThunderIntensity.capeLevel(0f));
        assertEquals(ThunderIntensity.DISTANT,  ThunderIntensity.capeLevel(499f));
        assertEquals(ThunderIntensity.LIGHT,    ThunderIntensity.capeLevel(500f));
        assertEquals(ThunderIntensity.LIGHT,    ThunderIntensity.capeLevel(1499f));
        assertEquals(ThunderIntensity.MODERATE, ThunderIntensity.capeLevel(1500f));
        assertEquals(ThunderIntensity.MODERATE, ThunderIntensity.capeLevel(2499f));
        assertEquals(ThunderIntensity.SEVERE,   ThunderIntensity.capeLevel(2500f));
        assertEquals(ThunderIntensity.SEVERE,   ThunderIntensity.capeLevel(3999f));
        assertEquals(ThunderIntensity.EXTREME,  ThunderIntensity.capeLevel(4000f));
        assertEquals(ThunderIntensity.EXTREME,  ThunderIntensity.capeLevel(9000f));
    }

    @Test public void capeLevelIsMonotonic() {
        int previous = ThunderIntensity.NONE;
        for (float cape = 0f; cape <= 9000f; cape += 25f) {
            int level = ThunderIntensity.capeLevel(cape);
            assertTrue("level fell from " + previous + " to " + level + " at " + cape,
                    level >= previous);
            previous = level;
        }
    }

    @Test public void moreCapeNeverMeansALessIntenseStorm() {
        int previous = ThunderIntensity.NONE;
        for (float cape = 0f; cape <= 9000f; cape += 25f) {
            int level = ThunderIntensity.levelFor(true, 95, cape);
            assertTrue("level fell at cape " + cape, level >= previous);
            previous = level;
        }
    }

    // ---- the two sources combined -----------------------------------------

    @Test public void capeCanRaiseAPlainThunderstormAboveItsFloor() {
        assertEquals(ThunderIntensity.EXTREME, ThunderIntensity.levelFor(true, 95, 5000f));
    }

    @Test public void hailKeepsItsLevelEvenWhenCapeIsLow() {
        // Hail is an observation; CAPE is a forecast quantity. A storm
        // dropping heavy hail is extreme whatever the model says.
        assertEquals(ThunderIntensity.EXTREME, ThunderIntensity.levelFor(true, 99, 10f));
        assertEquals(ThunderIntensity.SEVERE, ThunderIntensity.levelFor(true, 96, 10f));
    }

    @Test public void theAnswerIsAlwaysTheHigherOfTheTwo() {
        for (int code : new int[] { 95, 96, 99 }) {
            for (float cape = 0f; cape <= 6000f; cape += 250f) {
                int combined = ThunderIntensity.levelFor(true, code, cape);
                assertTrue(combined >= ThunderIntensity.codeFloor(code));
                assertTrue(combined >= ThunderIntensity.capeLevel(cape));
            }
        }
    }

    // ---- bad inputs --------------------------------------------------------

    @Test public void aMissingOrBrokenCapeFallsBackToTheCodeFloor() {
        assertEquals(ThunderIntensity.LIGHT, ThunderIntensity.levelFor(true, 95, null));
        assertEquals(ThunderIntensity.LIGHT, ThunderIntensity.levelFor(true, 95, Float.NaN));
        // A negative CAPE is not physical; it means the field was garbage.
        assertEquals(ThunderIntensity.LIGHT, ThunderIntensity.levelFor(true, 95, -1f));
    }

    @Test public void aThunderFlagWithAnUnknownCodeStillGradesOnCape() {
        // thunder true but the code is not 95/96/99 — a restored cache, or a
        // manual override. The floor is NONE, so CAPE alone carries it, and
        // the result must still be a real storm rather than zero.
        assertEquals(ThunderIntensity.MODERATE, ThunderIntensity.levelFor(true, -1, 2000f));
        assertTrue(ThunderIntensity.levelFor(true, -1, 2000f) > ThunderIntensity.NONE);
    }

    // ---- the scalar --------------------------------------------------------

    @Test public void theScalarPinsBothEnds() {
        assertEquals(0f, ThunderIntensity.scalar(ThunderIntensity.NONE), 0f);
        assertEquals(1f, ThunderIntensity.scalar(ThunderIntensity.EXTREME), 0f);
    }

    @Test public void theScalarIsMonotonicAndInRange() {
        float previous = -1f;
        for (int level = 0; level <= ThunderIntensity.MAX; level++) {
            float s = ThunderIntensity.scalar(level);
            assertTrue("out of range at " + level, s >= 0f && s <= 1f);
            assertTrue("not increasing at " + level, s > previous);
            previous = s;
        }
    }

    @Test public void anOutOfRangeLevelIsClampedRatherThanTrusted() {
        // Levels are persisted and outlive the code that wrote them.
        assertEquals(ThunderIntensity.NONE, ThunderIntensity.clampLevel(-4));
        assertEquals(ThunderIntensity.MAX, ThunderIntensity.clampLevel(99));
        assertEquals(0f, ThunderIntensity.scalar(-4), 0f);
        assertEquals(1f, ThunderIntensity.scalar(99), 0f);
    }

    @Test public void everyLevelHasAName() {
        for (int level = 0; level <= ThunderIntensity.MAX; level++) {
            assertFalse(ThunderIntensity.name(level).isEmpty());
        }
        assertEquals("NONE", ThunderIntensity.name(ThunderIntensity.NONE));
        assertEquals("LIGHT", ThunderIntensity.name(ThunderIntensity.LIGHT));
        assertEquals("NONE", ThunderIntensity.name(-1));
    }
}
