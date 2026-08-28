package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SkyKeyframesTest {

    private float[] at(float hour) {
        float[] out = new float[6];
        SkyKeyframes.at(hour, out);
        return out;
    }

    // DESIGN_NOTES §2b keyframe table.
    @Test public void midnightMatchesTheFirstKeyframe() {
        float[] c = at(0f);
        assertEquals(10f, c[0], 0.001f);  assertEquals(14f, c[1], 0.001f);
        assertEquals(38f, c[2], 0.001f);  assertEquals(22f, c[3], 0.001f);
        assertEquals(28f, c[4], 0.001f);  assertEquals(64f, c[5], 0.001f);
    }

    @Test public void noonMatchesItsKeyframe() {
        float[] c = at(12f);
        assertEquals(54f,  c[0], 0.001f); assertEquals(130f, c[1], 0.001f);
        assertEquals(228f, c[2], 0.001f); assertEquals(156f, c[3], 0.001f);
        assertEquals(208f, c[4], 0.001f); assertEquals(247f, c[5], 0.001f);
    }

    @Test public void sunriseKeyframeMatches() {
        float[] c = at(6.2f);   // #2e3e80 over #d67668
        assertEquals(46f,  c[0], 0.001f); assertEquals(62f,  c[1], 0.001f);
        assertEquals(128f, c[2], 0.001f); assertEquals(214f, c[3], 0.001f);
        assertEquals(118f, c[4], 0.001f); assertEquals(104f, c[5], 0.001f);
    }

    @Test public void interpolatesLinearlyBetweenKeyframes() {
        // Halfway between 22.0 [12,16,44] and 24.0 [10,14,38].
        float[] c = at(23f);
        assertEquals(11f, c[0], 0.001f);
        assertEquals(15f, c[1], 0.001f);
        assertEquals(41f, c[2], 0.001f);
    }

    @Test public void endOfDayMatchesStartOfDay() {
        float[] a = at(0f), b = at(24f);
        for (int i = 0; i < 6; i++) assertEquals(a[i], b[i], 0.001f);
    }

    @Test public void hoursOutsideTheRangeClampRatherThanCrash() {
        float[] lo = at(-1f), hi = at(25f), zero = at(0f);
        for (int i = 0; i < 6; i++) {
            assertEquals(zero[i], lo[i], 0.001f);
            assertEquals(zero[i], hi[i], 0.001f);
        }
    }

    @Test public void bayerBiasIsCenteredAndTiles() {
        assertEquals(-0.5f,   Bayer.bias(0, 0), 0.001f);   //  0/16 - 0.5
        assertEquals(0.4375f, Bayer.bias(0, 3), 0.001f);   // 15/16 - 0.5
        assertEquals(Bayer.bias(0, 0), Bayer.bias(4, 4), 0.001f);
        assertEquals(Bayer.bias(1, 2), Bayer.bias(9, 6), 0.001f);
    }
}
