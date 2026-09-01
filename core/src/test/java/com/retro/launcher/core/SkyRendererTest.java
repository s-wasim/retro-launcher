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
        // unquantized, exactly as the prototype's frame() does. Hour 12 has no
        // stars (daytime) and weather 0 has no clouds or rain, so sampling away
        // from the sun and moon discs isolates pixels the invariant covers.
        int[] buf = renderAt(12f, 0f);
        int sx = Math.round(sunX(12f)), sy = Math.round(sunY(12f, H));
        int mx = Math.round(moonX(12f)), my = Math.round(moonY(12f, H));
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                // Sun rays extend up to R+3+rayLen (~20px) beyond the disc centre.
                if (Math.hypot(x - sx, y - sy) < 25) continue;
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

    /** Moon disc rendered at midnight for a given phase and hemisphere. */
    private int[] moonAt(float phase, boolean southern) {
        SkyRenderer r = new SkyRenderer(W, H);
        r.setSouthernView(southern);
        int[] buf = new int[W * H];
        r.render(buf, 0f, 0f, phase, 0f);
        return buf;
    }

    @Test public void waxingCrescentIsLitOnTheRightFromTheNorth() {
        int[] buf = moonAt(0.12f, false);
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
        assertTrue(meanLumaBox(buf, cx + 9, cy, 2) > meanLumaBox(buf, cx - 9, cy, 2) + 20f);
    }

    @Test public void waningCrescentIsLitOnTheLeftFromTheNorth() {
        int[] buf = moonAt(0.88f, false);
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
        assertTrue(meanLumaBox(buf, cx - 9, cy, 2) > meanLumaBox(buf, cx + 9, cy, 2) + 20f);
    }

    /** South of the equator the same crescent hangs the other way round. */
    @Test public void theSouthernViewMirrorsTheTerminator() {
        int[] north = moonAt(0.12f, false);
        int[] south = moonAt(0.12f, true);
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
        assertTrue(meanLumaBox(north, cx + 9, cy, 2) > meanLumaBox(south, cx + 9, cy, 2) + 20f);
        assertTrue(meanLumaBox(south, cx - 9, cy, 2) > meanLumaBox(north, cx - 9, cy, 2) + 20f);
    }

    /** A full moon has no terminator, so hemisphere cannot change its brightness. */
    @Test public void hemisphereDoesNotChangeHowMuchOfAFullMoonIsLit() {
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
        assertEquals(meanLumaBox(moonAt(0.5f, false), cx, cy, 10),
                     meanLumaBox(moonAt(0.5f, true), cx, cy, 10), 6f);
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

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static int countNear(int[] buf, float r, float g, float b, float tolerance) {
        int n = 0;
        for (int argb : buf) {
            float dr = ((argb >> 16) & 0xFF) - r, dg = ((argb >> 8) & 0xFF) - g, db = (argb & 0xFF) - b;
            if (Math.sqrt(dr * dr + dg * dg + db * db) < tolerance) n++;
        }
        return n;
    }

    @Test public void starsOnlyAppearAtNight() {
        int[] night = renderAt(0f, 0f);
        int[] noon  = renderAt(12f, 0f);
        // Stars are near-white single pixels; count bright near-white pixels
        // away from the sun/moon discs at each hour.
        int sxN = Math.round(sunX(0f)), syN = Math.round(sunY(0f, H));
        int mxN = Math.round(moonX(0f)), myN = Math.round(moonY(0f, H));
        int starPixels = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (Math.hypot(x - sxN, y - syN) < 25 || Math.hypot(x - mxN, y - myN) < 20) continue;
            int argb = night[y * W + x];
            int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            if (r > 230 && g > 230 && b > 230) starPixels++;
        }
        assertTrue(starPixels > 0);

        int sxD = Math.round(sunX(12f)), syD = Math.round(sunY(12f, H));
        int mxD = Math.round(moonX(12f)), myD = Math.round(moonY(12f, H));
        int dayBrightPixels = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (Math.hypot(x - sxD, y - syD) < 25 || Math.hypot(x - mxD, y - myD) < 20) continue;
            int argb = noon[y * W + x];
            int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            if (r > 230 && g > 230 && b > 230) dayBrightPixels++;
        }
        assertEquals(0, dayBrightPixels);
    }

    /** Noon ambient = mix(top, bot, 0.5) from the SKY table, storm-darkened for
     *  the given weather — mirrors SkyRenderer's own `dark` / ambient math. */
    private static float[] ambientAtNoon(float weather) {
        float storm = SkyRenderer.smooth(0.55f, 1.00f, weather);
        float dark = 1f - 0.42f * storm;
        float topR = 54 * dark, topG = 130 * dark, topB = 228 * dark;
        float botR = 156 * dark, botG = 208 * dark, botB = 247 * dark;
        return new float[]{ (topR + botR) / 2f, (topG + botG) / 2f, (topB + botB) / 2f };
    }

    @Test public void cloudCoverIncreasesWithWeather() {
        // Cloud shading is a lightened/darkened blend of the (storm-adjusted)
        // ambient sky tone — DESIGN_NOTES §2b clouds layer. Reproduce the three
        // exact bands per weather and count matches as a proxy for cover extent.
        int low  = cloudPixels(renderAt(12f, 0.15f), 0.15f);
        int mid  = cloudPixels(renderAt(12f, 0.45f), 0.45f);
        int high = cloudPixels(renderAt(12f, 0.65f), 0.65f);
        assertTrue(low < mid);
        assertTrue(mid < high);
    }

    private static int cloudPixels(int[] buf, float weather) {
        float[] amb = ambientAtNoon(weather);
        float storm = SkyRenderer.smooth(0.55f, 1.00f, weather);
        // Noon has |sunAlt| = 1, so twilight = smooth(0.45, 0.02, 1) = 0 always;
        // only the storm mix (cBase toward [58,62,80]) applies at noon.
        float baseR = lerp(252, amb[0], 0.52f), baseG = lerp(253, amb[1], 0.52f), baseB = lerp(255, amb[2], 0.52f);
        baseR = lerp(baseR, 58, storm * 0.82f); baseG = lerp(baseG, 62, storm * 0.82f); baseB = lerp(baseB, 80, storm * 0.82f);
        float hiR = baseR * 1.14f, hiG = baseG * 1.14f, hiB = baseB * 1.14f;
        float midR = baseR * 0.94f, midG = baseG * 0.94f, midB = baseB * 0.94f;
        float loR = baseR * 0.72f, loG = baseG * 0.72f, loB = baseB * 0.72f;
        return countNear(buf, hiR, hiG, hiB, 8) + countNear(buf, midR, midG, midB, 8) + countNear(buf, loR, loG, loB, 8);
    }

    @Test public void rainOnlyFallsAboveThePrecipThreshold() {
        // precip = smooth(0.62, 0.98, weather) is 0 at weather 0.5, so any
        // frame-to-frame change there comes only from cloud drift; above the
        // floor, 260 moving rain streaks dwarf that. Compare frame-to-frame
        // pixel churn rather than matching an exact (background-adjacent)
        // rain colour, which the retro palette makes too fragile to pin down.
        int churnDry = frameChurn(12f, 0.5f);
        int churnWet = frameChurn(12f, 0.95f);
        assertTrue(churnWet > churnDry * 2);
    }

    private static int frameChurn(float hour, float weather) {
        SkyRenderer r1 = new SkyRenderer(W, H);
        SkyRenderer r2 = new SkyRenderer(W, H);
        int[] b1 = new int[W * H], b2 = new int[W * H];
        r1.render(b1, hour, weather, 0.62f, 0f);
        r2.render(b2, hour, weather, 0.62f, 3f);
        int n = 0;
        for (int i = 0; i < b1.length; i++) if (b1[i] != b2[i]) n++;
        return n;
    }

    @Test public void sameSeedGivesTheSameFrame() {
        SkyRenderer a = new SkyRenderer(W, H, 99L);
        SkyRenderer b = new SkyRenderer(W, H, 99L);
        int[] bufA = new int[W * H], bufB = new int[W * H];
        a.render(bufA, 9f, 0.3f, 0.5f, 2f);
        b.render(bufB, 9f, 0.3f, 0.5f, 2f);
        assertArrayEquals(bufA, bufB);
    }

    @Test public void differentSeedsGiveDifferentClouds() {
        SkyRenderer a = new SkyRenderer(W, H, 1L);
        SkyRenderer b = new SkyRenderer(W, H, 2L);
        int[] bufA = new int[W * H], bufB = new int[W * H];
        a.render(bufA, 12f, 0.5f, 0.5f, 0f);
        b.render(bufB, 12f, 0.5f, 0.5f, 0f);
        assertFalse(java.util.Arrays.equals(bufA, bufB));
    }

    @Test public void renderNeverThrowsAcrossTheWholeDay() {
        SkyRenderer r = new SkyRenderer(W, H);
        int[] buf = new int[W * H];
        for (float hour = 0f; hour <= 24f; hour += 0.5f) {
            for (float weather = 0f; weather <= 1f; weather += 0.25f) {
                r.render(buf, hour, weather, 0.5f, hour * 10f);
            }
        }
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
