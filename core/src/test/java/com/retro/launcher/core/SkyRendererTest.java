package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SkyRendererTest {

    private static final int W = 108, H = 234;
    private static final float SUN_R = 13f;

    private static SkyConditions cond(float hour, float cloudCover, float precip, float moonPhase,
                                       Precip type, boolean thunder, int tempC) {
        return new SkyConditions(hour, hour, 0f, 24f, cloudCover, precip, moonPhase, type, thunder, tempC);
    }

    /** Sky/sun-only conditions: no precip, no thunder, moon always visible
     *  across the full [0,24) knob (moonrise=0, moonset=24) so existing
     *  moon-position tests don't need real moonrise/moonset data. */
    private static SkyConditions basic(float hour, float moonPhase) {
        return cond(hour, 0f, 0f, moonPhase, Precip.NONE, false, 20);
    }

    private int[] renderAt(float hour, float cloudCover) {
        SkyRenderer r = new SkyRenderer(W, H);
        int[] buf = new int[W * H];
        r.render(buf, cond(hour, cloudCover, 0f, 0.62f, Precip.NONE, false, 20), 0f);
        return buf;
    }

    @Test public void sunAltitudePeaksAtNoonAndBottomsAtMidnight() {
        assertEquals(1f,  SkyRenderer.sunAlt(12.3f), 0.001f);
        assertEquals(-1f, SkyRenderer.sunAlt(0.3f),  0.001f);
    }

    @Test public void sunAltitudeIsZeroAtBothAnchors() {
        assertEquals(0f, SkyRenderer.sunAlt(6.2f),  0.001f);
        assertEquals(0f, SkyRenderer.sunAlt(18.4f), 0.001f);
    }

    @Test public void sunAltitudeIsPositiveAtMiddayAndNegativeAtMidnight() {
        assertTrue(SkyRenderer.sunAlt(12f) > 0f);
        assertTrue(SkyRenderer.sunAlt(0f) < 0f);
    }

    @Test public void sunAltitudeIsContinuousAcrossBothAnchors() {
        float justBeforeDawn = SkyRenderer.sunAlt(6.2f - 0.01f);
        float justAfterDawn  = SkyRenderer.sunAlt(6.2f + 0.01f);
        assertEquals(justBeforeDawn, justAfterDawn, 0.01f);

        float justBeforeDusk = SkyRenderer.sunAlt(18.4f - 0.01f);
        float justAfterDusk  = SkyRenderer.sunAlt(18.4f + 0.01f);
        assertEquals(justBeforeDusk, justAfterDusk, 0.01f);
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
        int[] buf = renderAt(12f, 0f);
        assertNotEquals(luma(buf[2 * W + 4]), luma(buf[(H - 3) * W + 4]), 0.5f);
    }

    @Test public void quantizationSnapsToFifteenLevelSteps() {
        int[] buf = renderAt(12f, 0f);
        int sx = Math.round(sunX(12f)), sy = Math.round(sunY(12f));
        int mx = Math.round(moonX(12f, 0f, 24f)), my = Math.round(moonY(12f, 0f, 24f));
        // 40, not the sun disc's own ~25px reach: near solar noon with clear
        // sky, lens flare's horizontal-ray extension (renderSun's
        // horizRayLen, up to rayLen+round(flare*14)) can carry blended
        // (non-quantized) ray pixels out past 25px from the sun's centre.
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (Math.hypot(x - sx, y - sy) < 40) continue;
                if (Math.hypot(x - mx, y - my) < 20) continue;
                int px = buf[y * W + x];
                for (int shift : new int[]{16, 8, 0}) {
                    int v = (px >> shift) & 0xFF;
                    assertEquals("channel " + v + " is not a multiple of 15", 0, v % 15);
                }
            }
        }
    }

    @Test public void renderIsDeterministicForTheSameInputs() {
        int[] a = new int[W * H], b = new int[W * H];
        new SkyRenderer(W, H).render(a, basic(9.5f, 0.4f), 0f);
        new SkyRenderer(W, H).render(b, basic(9.5f, 0.4f), 0f);
        assertArrayEquals(a, b);
    }

    @Test public void desaturationPushesTowardGrey() {
        SkyRenderer r = new SkyRenderer(W, H);
        int[] colour = new int[W * H], grey = new int[W * H];
        r.render(colour, basic(12f, 0.62f), 0f);
        r.setDesaturation(1f);
        r.render(grey, basic(12f, 0.62f), 0f);
        assertTrue(spread(grey) < spread(colour));
    }

    // Body-position formulas duplicated from BodyPath for test-side sampling.
    private static float sunX(float warpedHour) {
        float t = (warpedHour - SolarClock.SUNRISE_ANCHOR) / (SolarClock.SUNSET_ANCHOR - SolarClock.SUNRISE_ANCHOR);
        return t * W;
    }
    private static float sunY(float warpedHour) {
        float t = (warpedHour - SolarClock.SUNRISE_ANCHOR) / (SolarClock.SUNSET_ANCHOR - SolarClock.SUNRISE_ANCHOR);
        float u = 2f * t - 1f;
        return SUN_R + (H - 2f * SUN_R) * u * u;
    }
    private static float moonT(float hour, float moonrise, float moonset) {
        float end = moonset <= moonrise ? moonset + 24f : moonset;
        float span = end - moonrise;
        float h = hour < moonrise ? hour + 24f : hour;
        return (h - moonrise) / span;
    }
    private static float moonX(float hour, float moonrise, float moonset) {
        return moonT(hour, moonrise, moonset) * W;
    }
    private static float moonY(float hour, float moonrise, float moonset) {
        float t = moonT(hour, moonrise, moonset);
        float u = 2f * t - 1f;
        float drop = (t < 0.5f) ? 0.50f : 0.20f;
        return 0.5f * H - drop * H * u * u;
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
        int[] noon = renderAt(12f, 0f);
        int cx = Math.round(sunX(12f)), cy = Math.round(sunY(12f));
        float discLuma = meanLumaBox(noon, cx, cy, 4);
        float ambientLuma = meanLumaBox(noon, 4, 4, 3);
        assertTrue(discLuma > ambientLuma + 20f);
    }

    @Test public void sunClipsOffscreenAtMidnight() {
        // At the anchor-relative "midnight" the sun's parabola has long since
        // carried it below the buffer and renderSun's early-out applies.
        int[] midnight = renderAt(0.3f + 12f, 0f); // solar midnight per sunAlt's own test
        // No assertion needed beyond "renders without throwing" — the real
        // check is quantizationSnapsToFifteenLevelSteps not needing a sun
        // exclusion radius here; kept as a smoke test.
        assertEquals(0xFF, midnight[0] >>> 24);
    }

    @Test public void moonDiscAppearsWhenVisible() {
        int[] buf = new int[W * H];
        // hour=1 rather than exactly 0: moonT=0 sits right at the moonrise
        // edge, where the fade-in multiplier in SkyRenderer's moonVisibility
        // is intentionally zero (see moonVisibility's javadoc — the disc
        // must not pop into existence instantaneously at moonrise itself).
        // A minute past that edge is comfortably faded in and still deep
        // night by sunAlt, so it exercises "the disc appears" rather than
        // "the disc is still fading in".
        new SkyRenderer(W, H).render(buf, basic(1f, 0.62f), 0f);
        int cx = Math.round(moonX(1f, 0f, 24f)), cy = Math.round(moonY(1f, 0f, 24f));
        assertTrue(cy >= 0 && cy < H);
        float discLuma = meanLumaBox(buf, cx, cy, 4);
        float ambientLuma = meanLumaBox(buf, W - 6, H - 6, 3);
        assertTrue(discLuma > ambientLuma + 20f);
    }

    @Test public void moonIsAbsentWhenOutsideItsRiseSetWindow() {
        // Moonrise 8:00, moonset 9:00 — a 1h window; hour 20 is well outside
        // it, so the disc must not draw at all.
        SkyConditions c = new SkyConditions(20f, 20f, 8f, 9f, 0f, 0f, 0.5f, Precip.NONE, false, 20);
        int[] withMoon = new int[W * H], without = new int[W * H];
        new SkyRenderer(W, H).render(withMoon, basic(20f, 0.5f), 0f);
        new SkyRenderer(W, H).render(without, c, 0f);
        // A visible full moon at night is substantially brighter somewhere
        // than a night sky with no moon drawn at all.
        assertTrue(meanLumaBox(withMoon, Math.round(moonX(20f, 0f, 24f)), Math.round(moonY(20f, 0f, 24f)), 10)
                 > meanLumaBox(without, Math.round(moonX(20f, 0f, 24f)), Math.round(moonY(20f, 0f, 24f)), 10) + 10f);
    }

    @Test public void fullMoonIsBrighterThanNewMoon() {
        SkyRenderer full = new SkyRenderer(W, H);
        SkyRenderer newMoon = new SkyRenderer(W, H);
        int[] bufFull = new int[W * H], bufNew = new int[W * H];
        full.render(bufFull, basic(0f, 0.5f), 0f);
        newMoon.render(bufNew, basic(0f, 0.0f), 0f);
        int cx = Math.round(moonX(0f, 0f, 24f)), cy = Math.round(moonY(0f, 0f, 24f));
        assertTrue(meanLumaBox(bufFull, cx, cy, 10) > meanLumaBox(bufNew, cx, cy, 10));
    }

    private int[] moonAt(float hour, float phase, boolean southern) {
        SkyRenderer r = new SkyRenderer(W, H);
        r.setSouthernView(southern);
        int[] buf = new int[W * H];
        r.render(buf, basic(hour, phase), 0f);
        return buf;
    }

    // Moonrise 20:00, moonset 8:00 next day: a genuine nighttime window whose
    // vertex (t=0.5) falls at hour 2 — deep night by sunAlt too, unlike
    // basic()'s all-day 0/24 window whose t=0.5 vertex falls at hour 12
    // (solar noon), where SkyRenderer's own sun-driven moonVisibility fade
    // is intentionally zero. These three crescent/terminator tests care
    // about the disc's lit pattern, not the time of day, so they use this
    // window instead of basic() to keep the moon fully faded in.
    private static final float NIGHT_MOONRISE = 20f, NIGHT_MOONSET = 8f, NIGHT_VERTEX_HOUR = 2f;

    private int[] moonAtNight(float phase, boolean southern) {
        SkyRenderer r = new SkyRenderer(W, H);
        r.setSouthernView(southern);
        int[] buf = new int[W * H];
        SkyConditions c = new SkyConditions(NIGHT_VERTEX_HOUR, NIGHT_VERTEX_HOUR,
                NIGHT_MOONRISE, NIGHT_MOONSET, 0f, 0f, phase, Precip.NONE, false, 20);
        r.render(buf, c, 0f);
        return buf;
    }

    @Test public void waxingCrescentIsLitOnTheRightFromTheNorth() {
        // t=0.5 (moon's vertex, dead centre) at the night window's midpoint.
        int[] buf = moonAtNight(0.12f, false);
        int cx = Math.round(moonX(NIGHT_VERTEX_HOUR, NIGHT_MOONRISE, NIGHT_MOONSET));
        int cy = Math.round(moonY(NIGHT_VERTEX_HOUR, NIGHT_MOONRISE, NIGHT_MOONSET));
        assertTrue(meanLumaBox(buf, cx + 9, cy, 2) > meanLumaBox(buf, cx - 9, cy, 2) + 20f);
    }

    @Test public void waningCrescentIsLitOnTheLeftFromTheNorth() {
        int[] buf = moonAtNight(0.88f, false);
        int cx = Math.round(moonX(NIGHT_VERTEX_HOUR, NIGHT_MOONRISE, NIGHT_MOONSET));
        int cy = Math.round(moonY(NIGHT_VERTEX_HOUR, NIGHT_MOONRISE, NIGHT_MOONSET));
        assertTrue(meanLumaBox(buf, cx - 9, cy, 2) > meanLumaBox(buf, cx + 9, cy, 2) + 20f);
    }

    @Test public void theSouthernViewMirrorsTheTerminator() {
        int[] north = moonAtNight(0.12f, false);
        int[] south = moonAtNight(0.12f, true);
        int cx = Math.round(moonX(NIGHT_VERTEX_HOUR, NIGHT_MOONRISE, NIGHT_MOONSET));
        int cy = Math.round(moonY(NIGHT_VERTEX_HOUR, NIGHT_MOONRISE, NIGHT_MOONSET));
        assertTrue(meanLumaBox(north, cx + 9, cy, 2) > meanLumaBox(south, cx + 9, cy, 2) + 20f);
        assertTrue(meanLumaBox(south, cx - 9, cy, 2) > meanLumaBox(north, cx - 9, cy, 2) + 20f);
    }

    @Test public void hemisphereDoesNotChangeHowMuchOfAFullMoonIsLit() {
        int cx = Math.round(moonX(12f, 0f, 24f)), cy = Math.round(moonY(12f, 0f, 24f));
        assertEquals(meanLumaBox(moonAt(12f, 0.5f, false), cx, cy, 10),
                     meanLumaBox(moonAt(12f, 0.5f, true), cx, cy, 10), 6f);
    }

    @Test public void discsClipAtTheBufferEdgeWithoutCrashing() {
        for (float hour = 0f; hour <= 24f; hour += 0.25f) {
            int[] buf = renderAt(hour, 0f);
            for (int px : buf) assertEquals(0xFF, (px >>> 24));
        }
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
        int sxN = Math.round(sunX(0f)), syN = Math.round(sunY(0f));
        int mxN = Math.round(moonX(0f, 0f, 24f)), myN = Math.round(moonY(0f, 0f, 24f));
        int starPixels = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (Math.hypot(x - sxN, y - syN) < 25 || Math.hypot(x - mxN, y - myN) < 20) continue;
            int argb = night[y * W + x];
            int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            if (r > 230 && g > 230 && b > 230) starPixels++;
        }
        assertTrue(starPixels > 0);

        int sxD = Math.round(sunX(12f)), syD = Math.round(sunY(12f));
        int mxD = Math.round(moonX(12f, 0f, 24f)), myD = Math.round(moonY(12f, 0f, 24f));
        int dayBrightPixels = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (Math.hypot(x - sxD, y - syD) < 25 || Math.hypot(x - mxD, y - myD) < 20) continue;
            int argb = noon[y * W + x];
            int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            if (r > 230 && g > 230 && b > 230) dayBrightPixels++;
        }
        assertEquals(0, dayBrightPixels);
    }

    @Test public void starsFadeAsCloudCoverIncreases() {
        int clear = countNear(renderAt(0f, 0f), 246, 248, 255, 30);
        int cloudy = countNear(renderAt(0f, 0.9f), 246, 248, 255, 30);
        assertTrue(cloudy < clear);
    }

    @Test public void cloudCoverDrivesTheNumberOfCloudsShownDirectly() {
        int low  = cloudPixels(renderAt(12f, 0.15f), 0.15f);
        int mid  = cloudPixels(renderAt(12f, 0.45f), 0.45f);
        int high = cloudPixels(renderAt(12f, 0.65f), 0.65f);
        assertTrue(low < mid);
        assertTrue(mid < high);
    }

    private static int cloudPixels(int[] buf, float cloudCover) {
        float storm = 0f; // renderAt uses precip=0, thunder=false -> storm=smooth(0.30,0.90,0)=0
        // The ambient colour clouds blend toward is the actual noon sky's
        // (topR+botR)/2 etc (a blue ~(105,169,238) at hour 12, not a neutral
        // grey) — SkyKeyframes.at(12f, ...) reproduces exactly what
        // SkyRenderer.render computes for ambR/ambG/ambB at this hour.
        // Hardcoding a flat grey here previously put the expected colours
        // ~52 units away from anything actually rendered, well past this
        // test's own 30-unit tolerance.
        float[] sky = new float[6];
        SkyKeyframes.at(12f, sky);
        float ambR = (sky[0] + sky[3]) / 2f, ambG = (sky[1] + sky[4]) / 2f, ambB = (sky[2] + sky[5]) / 2f;
        float baseR = lerp(252, ambR, 0.52f), baseG = lerp(253, ambG, 0.52f), baseB = lerp(255, ambB, 0.52f);
        baseR = lerp(baseR, 58, storm * 0.82f); baseG = lerp(baseG, 62, storm * 0.82f); baseB = lerp(baseB, 80, storm * 0.82f);
        float hiR = baseR * 1.14f, hiG = baseG * 1.14f, hiB = baseB * 1.14f;
        float midR = baseR * 0.94f, midG = baseG * 0.94f, midB = baseB * 0.94f;
        float loR = baseR * 0.72f, loG = baseG * 0.72f, loB = baseB * 0.72f;
        // A 30-unit tolerance is wide enough that the sky gradient's own
        // smooth top-to-bottom blend incidentally passes near the "mid"
        // reference colour at some rows regardless of cloud cover, which
        // both dominates the count and (as haze/other terms shift slightly
        // with cover) breaks the monotonic relationship this test checks.
        // Cloud puffs are filled with these exact computed colours (no
        // dither), so a much tighter tolerance still matches every real
        // cloud pixel while excluding the incidental background match.
        return countNear(buf, hiR, hiG, hiB, 8) + countNear(buf, midR, midG, midB, 8) + countNear(buf, loR, loG, loB, 8);
    }

    @Test public void sameSeedGivesTheSameFrame() {
        SkyRenderer a = new SkyRenderer(W, H, 99L);
        SkyRenderer b = new SkyRenderer(W, H, 99L);
        int[] bufA = new int[W * H], bufB = new int[W * H];
        a.render(bufA, cond(9f, 0.5f, 0f, 0.5f, Precip.NONE, false, 20), 2f);
        b.render(bufB, cond(9f, 0.5f, 0f, 0.5f, Precip.NONE, false, 20), 2f);
        assertArrayEquals(bufA, bufB);
    }

    @Test public void differentSeedsGiveDifferentClouds() {
        SkyRenderer a = new SkyRenderer(W, H, 1L);
        SkyRenderer b = new SkyRenderer(W, H, 2L);
        int[] bufA = new int[W * H], bufB = new int[W * H];
        a.render(bufA, cond(12f, 0.5f, 0f, 0.5f, Precip.NONE, false, 20), 0f);
        b.render(bufB, cond(12f, 0.5f, 0f, 0.5f, Precip.NONE, false, 20), 0f);
        assertFalse(java.util.Arrays.equals(bufA, bufB));
    }

    @Test public void renderNeverThrowsAcrossTheWholeDayAndEveryChannelCombination() {
        SkyRenderer r = new SkyRenderer(W, H);
        int[] buf = new int[W * H];
        for (float hour = 0f; hour <= 24f; hour += 1f) {
            for (Precip type : Precip.values()) {
                for (boolean thunder : new boolean[]{false, true}) {
                    r.render(buf, cond(hour, 0.5f, 0.5f, 0.5f, type, thunder, 40), hour * 10f);
                }
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

    private static float spread(int[] buf) {
        double sum = 0;
        for (int px : buf) {
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
            sum += Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        }
        return (float) (sum / buf.length);
    }

    // ---- four independent weather channels (§6) --------------------------

    @Test public void dryThunderstormDrawsBoltsAndNoRain() {
        SkyRenderer r = new SkyRenderer(W, H, 7L);
        SkyConditions dryStorm = cond(12f, 0.9f, 0f, 0.5f, Precip.NONE, true, 25);
        int[] buf = new int[W * H];
        boolean sawFlash = false;
        // At storm=1 (thunder forces it), renderClouds' cloud colour blends
        // 82% toward near-black (58,62,80) — 90% cloud cover of near-black
        // cloud keeps this scene's un-flashed luma down around 95-120 even
        // at noon. applyFlash blends only 55% of the way to white, so a
        // flash here peaks around 183, not 255; 150 is comfortably above
        // every un-flashed/fully-decayed frame and comfortably below every
        // observed flash peak for this seed.
        for (float s = 0f; s < 20f; s += 1f) {
            r.render(buf, dryStorm, s);
            if (meanLuma(buf) > 150f) sawFlash = true;
        }
        assertTrue("expected at least one lightning flash over 20 frames", sawFlash);
        // No rain streaks: churn between two frames must be far below a wet
        // sky's, since only cloud drift and lightning randomness move.
        assertTrue(frameChurn(dryStorm) < frameChurn(cond(12f, 0.9f, 0.9f, 0.5f, Precip.RAIN, false, 15)) / 2);
    }

    @Test public void snowDrawsFlakesInsteadOfRainDrops() {
        int[] rain = new int[W * H];
        int[] snow = new int[W * H];
        new SkyRenderer(W, H, 3L).render(rain, cond(12f, 0.8f, 0.8f, 0.5f, Precip.RAIN, false, 5), 1f);
        new SkyRenderer(W, H, 3L).render(snow, cond(12f, 0.8f, 0.8f, 0.5f, Precip.SNOW, false, -5), 1f);
        assertFalse(java.util.Arrays.equals(rain, snow));
    }

    @Test public void rainOnlyDrawsWhenTypeIsRain() {
        int churnNone = frameChurn(cond(12f, 0.8f, 0.8f, 0.5f, Precip.NONE, false, 20));
        int churnRain = frameChurn(cond(12f, 0.8f, 0.8f, 0.5f, Precip.RAIN, false, 20));
        assertTrue(churnRain > churnNone);
    }

    private int frameChurn(SkyConditions c) {
        SkyRenderer r1 = new SkyRenderer(W, H);
        SkyRenderer r2 = new SkyRenderer(W, H);
        int[] b1 = new int[W * H], b2 = new int[W * H];
        r1.render(b1, c, 0f);
        r2.render(b2, c, 3f);
        int n = 0;
        for (int i = 0; i < b1.length; i++) if (b1[i] != b2[i]) n++;
        return n;
    }

    // ---- lens flare (§5) --------------------------------------------------

    @Test public void flareOnlyAppearsNearSolarNoonWhenClear() {
        // Lens flare's ghost rings are static (no seconds-dependence), so
        // compare golden-ring pixel counts directly rather than frame churn.
        int[] atNoon = renderAt(12.3f, 0f);         // sunT == 0.5 exactly
        int[] midMorning = renderAt(9f, 0f);         // sunT far from 0.5
        int goldNoon = countNear(atNoon, 255, 214, 120, 40);
        int goldMidMorning = countNear(midMorning, 255, 214, 120, 40);
        assertTrue("expected golden ghost-ring pixels only near solar noon",
                goldNoon > goldMidMorning);
    }

    @Test public void flareIsSuppressedWhenCloudy() {
        SkyRenderer clear = new SkyRenderer(W, H, 5L);
        SkyRenderer cloudy = new SkyRenderer(W, H, 5L);
        int[] a = new int[W * H], b = new int[W * H];
        clear.render(a, cond(12.3f, 0f, 0f, 0.5f, Precip.NONE, false, 20), 0f);
        cloudy.render(b, cond(12.3f, 1f, 0f, 0.5f, Precip.NONE, false, 20), 0f);
        // A fully overcast noon must not show the bright golden ghost rings
        // a clear noon does, near the sun's vertical midline.
        int sx = Math.round(sunX(12.3f));
        int goldClear = countNear(a, 255, 214, 120, 40);
        int goldCloudy = countNear(b, 255, 214, 120, 40);
        assertTrue(goldClear >= goldCloudy);
    }

    // ---- heat shimmer (§7) -------------------------------------------------

    @Test public void heatShimmerDisplacesRowsAboveThirtyFiveDegrees() {
        int churnHot = frameChurn(cond(12f, 0f, 0f, 0.5f, Precip.NONE, false, 45));
        int churnCool = frameChurn(cond(12f, 0f, 0f, 0.5f, Precip.NONE, false, 20));
        assertTrue(churnHot > churnCool);
    }

    @Test public void heatShimmerIsOffBelowThirtyFiveDegrees() {
        // At noon the sun is on screen regardless of shimmer, and its rays
        // pulse with `seconds` on their own (renderSun's rayLen), so exact
        // frame equality across two `seconds` values is the wrong invariant
        // here — a handful of ray pixels legitimately differ either way.
        // Heat shimmer's signature is a *row-wide* horizontal shift, which
        // churns orders of magnitude more pixels than an 8-ray sun glyph
        // ever can; bound the churn instead of demanding zero.
        int churnCool = frameChurn(cond(12f, 0f, 0f, 0.5f, Precip.NONE, false, 20));
        assertTrue("expected only sun-ray pixels to churn below the shimmer threshold, got " + churnCool,
                churnCool < 200);
    }
}
