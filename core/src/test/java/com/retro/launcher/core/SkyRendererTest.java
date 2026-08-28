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
        // The prototype rounds each channel to multiples of 15 before dither —
        // but only the base gradient; sun/moon/star/cloud layers paint over it
        // unquantized, exactly as the prototype's frame() does. Sample pixels
        // away from both bodies so the invariant is checked where it applies.
        int[] buf = renderAt(3f, 0f);
        int sx = Math.round(sunX(3f)), sy = Math.round(sunY(3f, H));
        int mx = Math.round(moonX(3f)), my = Math.round(moonY(3f, H));
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (Math.hypot(x - sx, y - sy) < 20) continue;
                if (Math.hypot(x - mx, y - my) < 20) continue;
                int px = buf[y * W + x];
                for (int shift : new int[]{16, 8, 0}) {
                    int v = (px >> shift) & 0xFF;
                    assertEquals("channel " + v + " is not a multiple of 15",
                            0, v % 15);
                }
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

    // Body-position formulas duplicated from SkyRenderer for test-side sampling —
    // see DESIGN_NOTES §2b "Body positions".
    private static float sunX(float hour) {
        float thSun = (float) ((hour - 6) / 12.0 * Math.PI);
        return 72f - (float) Math.cos(thSun) * 60f;
    }
    private static float sunY(float hour, int h) {
        float thSun = (float) ((hour - 6) / 12.0 * Math.PI);
        float travel = 0.3125f * h;
        return 0.667f * h + (1f - (float) Math.sin(thSun)) * travel;
    }
    private static float moonX(float hour) {
        float thMoon = (float) ((hour - 6) / 12.0 * Math.PI) + (float) Math.PI;
        return 36f - (float) Math.cos(thMoon) * 60f;
    }
    private static float moonY(float hour, int h) {
        float thMoon = (float) ((hour - 6) / 12.0 * Math.PI) + (float) Math.PI;
        float travel = 0.3125f * h;
        return 0.333f * h - (1f - (float) Math.sin(thMoon)) * travel;
    }

    private static float meanLumaBox(int[] buf, int cx, int cy, int radius) {
        double sum = 0; int n = 0;
        for (int y = Math.max(0, cy - radius); y <= Math.min(H - 1, cy + radius); y++) {
            for (int x = Math.max(0, cx - radius); x <= Math.min(W - 1, cx + radius); x++) {
                sum += luma(buf[y * W + x]); n++;
            }
        }
        return (float) (sum / n);
    }

    @Test public void sunDiscAppearsInTheSkyDuringDay() {
        // At hour 0 the sun's geometric position is well below the buffer and
        // is clipped entirely; at hour 12 it sits mid-frame and its bright
        // disc pushes the local mean luma well above the ambient sky there.
        int[] noon = renderAt(12f, 0f);
        int cx = Math.round(sunX(12f)), cy = Math.round(sunY(12f, H));
        float discLuma = meanLumaBox(noon, cx, cy, 4);
        float ambientLuma = meanLumaBox(noon, 4, 4, 3);
        assertTrue(discLuma > ambientLuma + 20f);
    }

    @Test public void moonDiscAppearsAtNight() {
        int[] midnight = renderAt(0f, 0f);
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
        assertTrue(cy >= 0 && cy < H);
        float discLuma = meanLumaBox(midnight, cx, cy, 4);
        float ambientLuma = meanLumaBox(midnight, W - 6, H - 6, 3);
        assertTrue(discLuma > ambientLuma + 20f);
    }

    @Test public void fullMoonIsBrighterThanNewMoon() {
        SkyRenderer full = new SkyRenderer(W, H);
        SkyRenderer newMoon = new SkyRenderer(W, H);
        int[] bufFull = new int[W * H], bufNew = new int[W * H];
        full.render(bufFull, 0f, 0f, 0.5f, 0f);
        newMoon.render(bufNew, 0f, 0f, 0.0f, 0f);
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
        assertTrue(meanLumaBox(bufFull, cx, cy, 10) > meanLumaBox(bufNew, cx, cy, 10));
    }

    @Test public void discsClipAtTheBufferEdgeWithoutCrashing() {
        for (float hour = 0f; hour <= 24f; hour += 0.25f) {
            int[] buf = renderAt(hour, 0f);
            for (int px : buf) assertEquals(0xFF, (px >>> 24));
        }
    }

    @Test public void renderStaysDeterministicWithDiscsAdded() {
        assertArrayEquals(renderAt(9.5f, 0.4f), renderAt(9.5f, 0.4f));
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
