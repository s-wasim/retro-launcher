package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SkyRendererTest {

    private static final int W = 108, H = 234;

    private int[] renderAt(float hour, float weather) {
        SkyRenderer r = new SkyRenderer(W, H);
        int[] buf = new int[W * H];
        r.render(buf, hour, weather, 0.62f, 0f);
        return buf;
    }

    @Test public void sunAltitudePeaksAtNoonAndBottomsAtMidnight() {
        assertEquals(1f,  SkyRenderer.sunAlt(12f), 0.001f);
        assertEquals(0f,  SkyRenderer.sunAlt(6f),  0.001f);
        assertEquals(-1f, SkyRenderer.sunAlt(0f),  0.001f);
        assertEquals(0f,  SkyRenderer.sunAlt(18f), 0.001f);
    }

    @Test public void smoothstepIsClampedAndMonotonic() {
        assertEquals(0f,   SkyRenderer.smooth(0f, 1f, -1f), 0.001f);
        assertEquals(1f,   SkyRenderer.smooth(0f, 1f, 2f),  0.001f);
        assertEquals(0.5f, SkyRenderer.smooth(0f, 1f, 0.5f), 0.001f);
        assertTrue(SkyRenderer.smooth(0.1f, 0.66f, 0.3f)
                 < SkyRenderer.smooth(0.1f, 0.66f, 0.5f));
    }

    @Test public void everyPixelIsWrittenAndFullyOpaque() {
        int[] buf = renderAt(12f, 0f);
        for (int i = 0; i < buf.length; i++) {
            assertEquals("alpha at " + i, 0xFF, (buf[i] >>> 24));
        }
    }

    @Test public void nightIsDarkerThanNoon() {
        assertTrue(meanLuma(renderAt(0f, 0f)) < meanLuma(renderAt(12f, 0f)));
    }

    @Test public void theGradientRunsTopToBottom() {
        // Sample columns away from the sun disc so the gradient dominates.
        int[] buf = renderAt(12f, 0f);
        assertNotEquals(luma(buf[2 * W + 4]), luma(buf[(H - 3) * W + 4]), 0.5f);
    }

    @Test public void quantizationSnapsToFifteenLevelSteps() {
        // The prototype rounds each channel to multiples of 15 before dither.
        // Every produced channel value must therefore be a multiple of 15,
        // clamped into range.
        int[] buf = renderAt(3f, 0f);
        for (int px : buf) {
            for (int shift : new int[]{16, 8, 0}) {
                int v = (px >> shift) & 0xFF;
                assertEquals("channel " + v + " is not a multiple of 15",
                        0, v % 15);
            }
        }
    }

    @Test public void aStormDarkensTheSky() {
        assertTrue(meanLuma(renderAt(12f, 1f)) < meanLuma(renderAt(12f, 0f)));
    }

    @Test public void renderIsDeterministicForTheSameInputs() {
        assertArrayEquals(renderAt(9.5f, 0.4f), renderAt(9.5f, 0.4f));
    }

    @Test public void desaturationPushesTowardGrey() {
        SkyRenderer r = new SkyRenderer(W, H);
        int[] colour = new int[W * H], grey = new int[W * H];
        r.render(colour, 12f, 0f, 0.62f, 0f);
        r.setDesaturation(1f);
        r.render(grey, 12f, 0f, 0.62f, 0f);
        assertTrue(spread(grey) < spread(colour));
    }

    private static float luma(int argb) {
        return ((argb >> 16) & 0xFF) * 0.299f
             + ((argb >> 8)  & 0xFF) * 0.587f
             + ( argb        & 0xFF) * 0.114f;
    }

    private static float meanLuma(int[] buf) {
        double sum = 0;
        for (int px : buf) sum += luma(px);
        return (float) (sum / buf.length);
    }

    /** Mean per-pixel channel spread — collapses toward zero as colour is lost. */
    private static float spread(int[] buf) {
        double sum = 0;
        for (int px : buf) {
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
            sum += Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        }
        return (float) (sum / buf.length);
    }
}
