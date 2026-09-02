package com.retro.launcher.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class IconCoverageTest {

    private static final int OPAQUE = 0xFF336699;
    private static final int CLEAR = 0x00000000;

    private static int[] withOpaque(int total, int opaqueCount) {
        int[] pixels = new int[total];
        Arrays.fill(pixels, CLEAR);
        for (int i = 0; i < opaqueCount; i++) pixels[i] = OPAQUE;
        return pixels;
    }

    @Test public void anEntirelyTransparentRenderIsBlank() {
        assertTrue(IconCoverage.isBlank(withOpaque(576, 0)));
    }

    @Test public void aFullyOpaqueRenderIsNotBlank() {
        assertTrue(!IconCoverage.isBlank(withOpaque(576, 576)));
    }

    @Test public void aRenderJustOverTheThresholdIsBlank() {
        // 24x24 = 576. 97% transparent is 558.72, so 559 clear pixels — that
        // is 17 opaque — still reads as blank.
        assertTrue(IconCoverage.isBlank(withOpaque(576, 17)));
    }

    @Test public void aRenderJustUnderTheThresholdIsNotBlank() {
        assertFalse(IconCoverage.isBlank(withOpaque(576, 18)));
    }

    @Test public void aSingleFaintPixelIsStillBlank() {
        assertTrue(IconCoverage.isBlank(withOpaque(576, 1)));
    }

    @Test public void nullAndEmptyAreBlank() {
        assertTrue(IconCoverage.isBlank(null));
        assertTrue(IconCoverage.isBlank(new int[0]));
    }

    @Test public void nearlyTransparentPixelsCountAsTransparent() {
        int[] pixels = new int[576];
        Arrays.fill(pixels, 0x01000000);  // alpha 1 of 255
        assertTrue(IconCoverage.isBlank(pixels));
    }
}
