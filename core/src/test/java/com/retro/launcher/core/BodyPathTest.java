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
        // A secant over [0.5-eps, 0.5] on a pure quadratic branch equals the
        // true derivative at the *midpoint* 0.5-eps/2, not at 0.5 itself, so
        // it is exactly 4*drop*H*eps here (not O(eps^2)) — with H=234 and
        // drop up to 0.5 that is ~0.47 at eps=1e-3, not sub-0.05. The
        // tolerance below is picked to comfortably bound that known secant
        // value while still being three orders of magnitude below the
        // curve's true edge slope (~470 at the anchors), so it still
        // meaningfully demonstrates "near zero at the vertex".
        float eps = 0.001f;
        float dLeft = (BodyPath.moonY(0.5f, H) - BodyPath.moonY(0.5f - eps, H)) / eps;
        float dRight = (BodyPath.moonY(0.5f + eps, H) - BodyPath.moonY(0.5f, H)) / eps;
        assertEquals(0f, dLeft, 0.5f);
        assertEquals(0f, dRight, 0.5f);
    }

    @Test public void bothCurvesStayFiniteAcrossTheFullRange() {
        for (float t = -0.5f; t <= 1.5f; t += 0.05f) {
            assertFalse(Float.isNaN(BodyPath.sunY(t, W, H, R)));
            assertFalse(Float.isNaN(BodyPath.moonY(t, H)));
        }
    }

    @Test public void moonTSpansAWindowThatClosesAfterMidnight() {
        // Moonrise 22:00, moonset 03:00 the next day -> a 5h window, reported
        // as 22..27 since 2.3.4 rather than wrapped into [0, 24).
        assertEquals(0f, BodyPath.moonT(22f, 22f, 27f), 0.001f);
        assertEquals(0.5f, BodyPath.moonT(24.5f, 22f, 27f), 0.001f);
        assertEquals(1f, BodyPath.moonT(27f, 22f, 27f), 0.001f);
    }

    @Test public void moonTSpansAWindowThatOpenedBeforeMidnight() {
        // The same window seen from the following day: rose at -2 (22:00
        // yesterday), sets at 03:00 today.
        assertEquals(0.4f, BodyPath.moonT(0f, -2f, 3f), 0.001f);
        assertEquals(1f, BodyPath.moonT(3f, -2f, 3f), 0.001f);
    }

    @Test public void moonTIsNegativeBeforeItsWindowOpens() {
        // A window that has not started yet is how LunarMath reports "the
        // moon is down and rises later" — it must read as off-screen, not as
        // a wrapped position at the other end of the night.
        assertTrue(BodyPath.moonT(12f, 15.2f, 25.3f) < 0f);
    }

    @Test public void moonTIsMonotonicAcrossTheWindow() {
        float prev = BodyPath.moonT(22f, 22f, 27f);
        for (int i = 1; i <= 20; i++) {
            float cur = BodyPath.moonT(22f + i * 0.25f, 22f, 27f);
            assertTrue("step " + i, cur > prev);
            prev = cur;
        }
    }

    @Test public void moonTIsNaNForADegenerateWindow() {
        assertTrue(Float.isNaN(BodyPath.moonT(10f, 8f, 8f)));
        // And for a pair left over from the pre-2.3.4 wrapped encoding,
        // rather than a negative span read as a position.
        assertTrue(Float.isNaN(BodyPath.moonT(10f, 22f, 3f)));
    }
}
