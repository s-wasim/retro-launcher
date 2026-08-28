package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuantizeTest {

    @Test public void rampSortsAscendingByLuminance() {
        int[] ramp = Palettes.get("gb", false).ramp();
        assertEquals(6, ramp.length);
        for (int i = 1; i < ramp.length; i++) {
            assertTrue(Quantize.luminance(ramp[i - 1]) <= Quantize.luminance(ramp[i]));
        }
    }

    @Test public void nearestIndexIsStableForSamePixelAndPosition() {
        int[] ramp = Palettes.get("gb", false).ramp();
        int a = Quantize.nearestIndex(0xFF808080, ramp, 3, 5);
        int b = Quantize.nearestIndex(0xFF808080, ramp, 3, 5);
        assertEquals(a, b);
    }

    @Test public void nearestIndexPicksClosestRampLuminance() {
        int[] ramp = { 0xFF000000, 0xFF808080, 0xFFFFFFFF };
        assertEquals(0, Quantize.nearestIndex(0xFF050505, ramp, 0, 0));
        assertEquals(2, Quantize.nearestIndex(0xFFFAFAFA, ramp, 0, 0));
    }

    @Test public void nearestIndexIsAlwaysInRampBounds() {
        int[] ramp = Palettes.get("plasma", true).ramp();
        for (int v = 0; v <= 255; v += 17) {
            int argb = 0xFF000000 | (v << 16) | (v << 8) | v;
            int idx = Quantize.nearestIndex(argb, ramp, v, v);
            assertTrue(idx >= 0 && idx < ramp.length);
        }
    }
}
