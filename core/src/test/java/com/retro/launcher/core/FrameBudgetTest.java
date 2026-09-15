package com.retro.launcher.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 2.1.3. The sky used to redraw 30 times a second whatever was in it. These
 * tests pin the two halves of replacing that: that a scene which is genuinely
 * moving still gets the frames it needs, and that one which is not stops
 * asking for them.
 *
 * <p>The gates here are copies of {@link SkyRenderer}'s own — a layer that
 * draws nothing must not hold the frame rate up, and a layer that draws
 * something must not be starved — so several of these compare the budget's
 * answer against the renderer's threshold directly. That is the coupling
 * worth testing: the two drifting apart is how you get frozen rain.
 */
public class FrameBudgetTest {

    private static final int W = 108;
    private static final int H = 230;

    /** Noon, clear, calm, mild — the baseline everything else perturbs. */
    private static SkyConditions clearNoon() {
        return at(12f, 0f, 0f, Precip.NONE, false, 20);
    }

    private static SkyConditions at(float hour, float cover, float precip,
                                    Precip type, boolean thunder, int tempC) {
        return new SkyConditions(hour, hour, Float.NaN, Float.NaN,
                cover, precip, 0.5f, type, thunder, tempC);
    }

    private static long budget(SkyConditions c) {
        return FrameBudget.animationIntervalMs(c, W, H);
    }

    // ---- what must stay fast ---------------------------------------------

    @Test public void rainGetsTheFastRate() {
        assertEquals(FrameBudget.FAST_MS, budget(at(12f, 0.8f, 0.6f, Precip.RAIN, false, 12)));
    }

    @Test public void snowGetsTheFastRate() {
        // A flake is one pixel moving 14-34 px/s; at the slow rate it would
        // jump several of its own widths and read as a dotted trail.
        assertEquals(FrameBudget.FAST_MS, budget(at(12f, 0.8f, 0.4f, Precip.SNOW, false, -2)));
    }

    @Test public void aPrecipTypeWithNoIntensityDoesNotCountAsFalling() {
        // renderPrecipitation and renderSnow both return at precip <= 0.01.
        assertNotEquals(FrameBudget.FAST_MS, budget(at(12f, 0.2f, 0f, Precip.RAIN, false, 20)));
    }

    @Test public void thunderGetsTheFastRateEvenWithNoPrecipitation() {
        // The bolt and the flash decay per frame, not per second, so a slow
        // rate would stretch a flash into something that reads as a fault.
        assertEquals(FrameBudget.FAST_MS, budget(at(12f, 0.9f, 0f, Precip.NONE, true, 20)));
    }

    @Test public void thunderAtNightIsStillFast() {
        assertEquals(FrameBudget.FAST_MS, budget(at(2f, 0.9f, 0.7f, Precip.RAIN, true, 14)));
    }

    // ---- clouds ----------------------------------------------------------

    @Test public void driftingCloudGetsTheSlowRate() {
        // A cloud crosses a pixel about every two seconds, so 4fps and 30fps
        // produce the identical sequence of frames.
        assertEquals(FrameBudget.SLOW_MS, budget(at(12f, 0.5f, 0f, Precip.NONE, false, 20)));
    }

    @Test public void halfACloudIsWhereTheCloudLayerStartsDrawing() {
        // SkyRenderer draws Math.round(cover * CLOUD_COUNT) clouds, so the
        // budget's gate has to round the same way.
        assertEquals(0, FrameBudget.cloudsShown(0.4f / SkyRenderer.CLOUD_COUNT));
        assertEquals(1, FrameBudget.cloudsShown(0.5f / SkyRenderer.CLOUD_COUNT));
    }

    @Test public void anOvercastNightIsSlowRatherThanIdleBecauseTheCloudsDrift() {
        // Full cover puts the stars out and hides the sun, but fourteen
        // clouds are still moving — "overcast" is not "still".
        assertEquals(FrameBudget.SLOW_MS, budget(at(2f, 1f, 0f, Precip.NONE, false, 10)));
    }

    // ---- the slow layers -------------------------------------------------

    @Test public void aClearDayIsSlowBecauseTheSunsRaysPulse() {
        assertEquals(FrameBudget.SLOW_MS, budget(clearNoon()));
    }

    @Test public void aClearNightIsSlowBecauseTheStarsTwinkle() {
        assertEquals(FrameBudget.SLOW_MS, budget(at(2f, 0f, 0f, Precip.NONE, false, 15)));
    }

    @Test public void heatShimmerEarnsTheSlowRateOnItsOwn() {
        assertEquals(FrameBudget.SLOW_MS, budget(at(2f, 1f, 0f, Precip.NONE, false, 40)));
    }

    // ---- what must go idle -----------------------------------------------

    @Test public void noRealWeatherEverReachesTheIdleRate() {
        // Worth stating outright, because the obvious reading of "idle" is
        // "a clear sky", and a clear sky is the one thing it is not: the sun
        // is up or the stars are out at every hour, and either earns SLOW.
        // IDLE is the no-weather-yet branch. If this ever starts failing, a
        // layer's gate has moved and the budget has stopped matching it.
        for (int hour = 0; hour < 24; hour++) {
            for (int pct = 0; pct <= 100; pct += 5) {
                SkyConditions c = at(hour, pct / 100f, 0f, Precip.NONE, false, 20);
                assertNotEquals("idle at hour " + hour + ", cover " + pct + "%",
                        FrameBudget.IDLE_MS, budget(c));
            }
        }
    }

    @Test public void moreCloudCoverNeverAsksForMoreFrames() {
        // Cover trades stars for clouds — both slow layers — so crossing
        // between them must not produce a rate change in either direction.
        for (int hour = 0; hour < 24; hour++) {
            long clear = budget(at(hour, 0f, 0f, Precip.NONE, false, 20));
            long overcast = budget(at(hour, 1f, 0f, Precip.NONE, false, 20));
            assertTrue("hour " + hour, overcast >= clear);
        }
    }

    @Test public void aMovingSceneIsNotStill() {
        assertFalse(FrameBudget.isStill(clearNoon(), W, H));
        assertFalse(FrameBudget.isStill(at(12f, 0.8f, 0.6f, Precip.RAIN, false, 12), W, H));
    }

    @Test public void aNullSceneIsIdleRatherThanAnException() {
        // SkyView has no weather at all until the first reading lands.
        assertEquals(FrameBudget.IDLE_MS, budget(null));
    }

    // ---- the obscured floor ----------------------------------------------

    @Test public void aPanelOverTheSkyCapsEvenRainAtOneFrameASecond() {
        SkyConditions rain = at(12f, 0.8f, 0.6f, Precip.RAIN, false, 12);
        assertEquals(FrameBudget.FAST_MS, FrameBudget.intervalMs(rain, W, H, false));
        assertEquals(FrameBudget.OBSCURED_FLOOR_MS, FrameBudget.intervalMs(rain, W, H, true));
    }

    @Test public void theFloorNeverSpeedsASlowerSceneUp() {
        // It is a floor, not a rate: a scene already slower than 1fps stays
        // there rather than being pulled up to it.
        assertEquals(FrameBudget.IDLE_MS, FrameBudget.intervalMs(null, W, H, true));
    }

    @Test public void theFloorSlowsAClearDayDownToOneFrameASecond() {
        assertEquals(FrameBudget.SLOW_MS, FrameBudget.intervalMs(clearNoon(), W, H, false));
        assertEquals(FrameBudget.OBSCURED_FLOOR_MS,
                FrameBudget.intervalMs(clearNoon(), W, H, true));
    }

    // ---- the rates themselves --------------------------------------------

    @Test public void theRatesAreOrderedAndNoneIsFasterThanTheOldFixedRate() {
        assertTrue(FrameBudget.FAST_MS < FrameBudget.SLOW_MS);
        assertTrue(FrameBudget.SLOW_MS < FrameBudget.IDLE_MS);
        // The old loop slept 33ms. Nothing here may ask for more frames than
        // that — this change only ever removes work.
        assertTrue("a rate faster than the 30fps this replaced",
                FrameBudget.FAST_MS >= 33L);
    }

    @Test public void theStarTwinkleStaysSmoothAtTheSlowRate() {
        // renderStars twinkles on sin(seconds * 1.7), a 3.7s period. At the
        // slow rate that is ~15 samples a cycle; below about 8 it steps.
        double periodSeconds = 2 * Math.PI / 1.7;
        double samplesPerCycle = periodSeconds / (FrameBudget.SLOW_MS / 1000.0);
        assertTrue("twinkle would visibly step at " + samplesPerCycle + " samples/cycle",
                samplesPerCycle >= 8);
    }

    @Test public void aRaindropStillMovesEveryFrameAtTheFastRate() {
        // Drops fall at v pixels a second in a 230-row buffer; at 24fps the
        // slowest still advances about a pixel a frame, which is what keeps
        // rain reading as rain rather than as a ladder.
        assertTrue(FrameBudget.FAST_MS <= 42L);
    }
}
