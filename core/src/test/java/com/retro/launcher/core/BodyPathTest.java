package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class BodyPathTest {

    private static final int W = 108, H = 234;
    private static final float R = 13f;

    @Test public void sunIsTangentToTheBottomAtBothAnchors() {
        assertEquals(H - R, BodyPath.sunY(0f, W, H, R), 0.01f);
        assertEquals(H - R, BodyPath.sunY(1f, W, H, R), 0.01f);
    }

    @Test public void sunIsTangentToTheTopAtNoon() {
        assertEquals(R, BodyPath.sunY(0.5f, W, H, R), 0.01f);
    }

    @Test public void sunXIsMonotonicLeftToRight() {
        float prev = BodyPath.sunX(0f, W);
        for (float t = 0.05f; t <= 1f; t += 0.05f) {
            float cur = BodyPath.sunX(t, W);
            assertTrue("t=" + t, cur > prev);
            prev = cur;
        }
    }

    @Test public void sunTIsZeroAtSunriseAndOneAtSunset() {
        assertEquals(0f, BodyPath.sunT(SolarClock.SUNRISE_ANCHOR), 0.0001f);
        assertEquals(1f, BodyPath.sunT(SolarClock.SUNSET_ANCHOR), 0.0001f);
    }

    @Test public void sunTPastTheAnchorsExceedsUnitRange() {
        assertTrue(BodyPath.sunT(SolarClock.SUNRISE_ANCHOR - 1f) < 0f);
        assertTrue(BodyPath.sunT(SolarClock.SUNSET_ANCHOR + 1f) > 1f);
    }

    @Test public void moonEntersAtTheTopLeft() {
        assertEquals(0f, BodyPath.moonY(0f, H), 0.01f);
        assertEquals(0f, BodyPath.moonX(0f, W), 0.01f);
    }

    @Test public void moonVertexIsDeadCentreAtHalfHeight() {
        assertEquals(0.5f * H, BodyPath.moonY(0.5f, H), 0.01f);
    }

    @Test public void moonExitsTwentyPercentAboveTheVertex() {
        assertEquals(0.30f * H, BodyPath.moonY(1f, H), 0.01f);
    }

    @Test public void moonHasZeroSlopeOnBothSidesOfTheVertex() {
        float eps = 0.001f;
        float dLeft = (BodyPath.moonY(0.5f, H) - BodyPath.moonY(0.5f - eps, H)) / eps;
        float dRight = (BodyPath.moonY(0.5f + eps, H) - BodyPath.moonY(0.5f, H)) / eps;
        assertEquals(0f, dLeft, 0.05f);
        assertEquals(0f, dRight, 0.05f);
    }

    @Test public void bothCurvesStayFiniteAcrossTheFullRange() {
        for (float t = -0.5f; t <= 1.5f; t += 0.05f) {
            assertFalse(Float.isNaN(BodyPath.sunY(t, W, H, R)));
            assertFalse(Float.isNaN(BodyPath.moonY(t, H)));
        }
    }

    @Test public void moonTFoldsAMoonsetAfterMidnight() {
        // Moonrise 22:00, moonset 03:00 next day -> a 5h window.
        assertEquals(0f, BodyPath.moonT(22f, 22f, 3f), 0.001f);
        assertEquals(0.5f, BodyPath.moonT(0.5f, 22f, 3f), 0.001f);
        assertEquals(1f, BodyPath.moonT(3f, 22f, 3f), 0.01f);
    }

    @Test public void moonTIsMonotonicAcrossTheWindow() {
        float prev = BodyPath.moonT(22f, 22f, 3f);
        float h = 22f;
        for (int i = 1; i <= 20; i++) {
            h = (h + 0.25f) % 24f;
            float cur = BodyPath.moonT(h, 22f, 3f);
            assertTrue("step " + i, cur > prev);
            prev = cur;
        }
    }

    @Test public void moonTIsNaNForADegenerateWindow() {
        assertTrue(Float.isNaN(BodyPath.moonT(10f, 8f, 8f)));
    }
}
