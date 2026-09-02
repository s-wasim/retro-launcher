package com.retro.launcher.core;

/**
 * The prototype's frame() as a pure function. Writes ARGB into a caller-owned
 * int[] — deliberately never touches android.graphics.Bitmap, because a Bitmap
 * cannot be instantiated in a plain JUnit test and this class is the one that
 * most needs testing. SkyView owns the Bitmap and calls setPixels().
 *
 * See DESIGN_NOTES §2b for the layer list and the derivation of every scalar.
 */
public final class SkyRenderer {

    /** Moon crater centres (nx, ny, radius) in unit-disc space. Prototype's CRATERS table. */
    private static final float[][] CRATERS = {
            {-0.30f,-0.28f,0.26f}, {0.26f,0.12f,0.30f}, {0.04f,-0.48f,0.17f}, {-0.46f,0.34f,0.20f},
            {0.38f,-0.38f,0.15f}, {-0.06f,0.46f,0.21f}, {0.52f,0.44f,0.13f}, {-0.62f,-0.02f,0.14f}
    };

    private static final class Puff { float dx, dy, r; }
    private static final class Cloud { float x, yf, s, sp; Puff[] puffs; }
    private static final class Drop { float x, yf, v, ph; int len; }
    private static final class Star { int x; float yf, b, ph; boolean big; }

    private final int w, h;
    private final float[] sky = new float[6];

    private int lcgSeed;
    private final Cloud[] clouds;
    private final Drop[] drops;
    private final Star[] stars;
    private final java.util.Random rand;

    private float flash;
    private int[][] bolt;
    private int boltLife;

    private int[] tintRamp;          // null unless "tint wallpaper to palette"
    private float desaturation;      // 0 = off; the over-limit nag
    private boolean southernView;    // the moon, seen from below the equator

    public SkyRenderer(int w, int h) {
        this(w, h, 1337L);
    }

    /** The seed drives the cloud/star/rain tables (LCG, prototype-exact) and
     *  the lightning trigger (java.util.Random, standing in for the
     *  prototype's Math.random() so frames stay reproducible under test). */
    public SkyRenderer(int w, int h, long seed) {
        this.w = w;
        this.h = h;
        this.lcgSeed = (int) seed;
        this.clouds = buildClouds();
        this.drops = buildDrops();
        this.stars = buildStars();
        this.rand = new java.util.Random(seed);
    }

    /** The prototype's LCG: {@code seed = (seed*1103515245 + 12345) & 0x7fffffff}. */
    private float rnd() {
        lcgSeed = (lcgSeed * 1103515245 + 12345) & 0x7fffffff;
        return lcgSeed / (float) 0x7fffffff;
    }

    private Cloud[] buildClouds() {
        Cloud[] out = new Cloud[14];
        for (int i = 0; i < 14; i++) {
            int n = 4 + (int) (rnd() * 3);
            Puff[] puffs = new Puff[n];
            float x = 0;
            for (int j = 0; j < n; j++) {
                Puff p = new Puff();
                p.dx = x;
                p.dy = (int) (rnd() * 4 - 2);
                p.r = 4f + rnd() * 4f;
                puffs[j] = p;
                x += 4f + rnd() * 4f;
            }
            float wide = x;
            for (Puff p : puffs) p.dx -= wide / 2f;

            Cloud c = new Cloud();
            c.x = rnd() * 188f - 40f;
            c.yf = (18f + rnd() * 118f) / 192f;
            c.s = 0.7f + rnd() * 0.9f;
            c.sp = 0.9f + rnd() * 1.6f;
            c.puffs = puffs;
            out[i] = c;
        }
        return out;
    }

    private Drop[] buildDrops() {
        Drop[] out = new Drop[260];
        for (int i = 0; i < 260; i++) {
            Drop d = new Drop();
            d.x = rnd() * 108f;
            d.yf = rnd();
            d.v = 0.6f + rnd() * 0.6f;
            d.ph = rnd() * 6.28f;
            d.len = 2 + (int) (rnd() * 3);
            out[i] = d;
        }
        return out;
    }

    private Star[] buildStars() {
        Star[] out = new Star[130];
        for (int i = 0; i < 130; i++) {
            Star s = new Star();
            s.x = (int) (rnd() * 108);
            s.yf = rnd() * 0.74f;
            s.b = 0.35f + rnd() * 0.65f;
            s.ph = rnd() * 6.28f;
            s.big = rnd() > 0.9f;
            out[i] = s;
        }
        return out;
    }

    public void setTint(int[] rampArgb) { this.tintRamp = rampArgb; }

    public void setDesaturation(float amount) {
        this.desaturation = clamp01(amount);
    }

    /**
     * Below the equator the moon rides the sky upside down: a waxing crescent
     * is lit on the left, and the maria sit the other way up. Rotating the
     * disc 180° is the whole of the difference — the phase itself is the same
     * number everywhere on Earth. Driven by the latitude of the same coarse
     * fix the weather uses; see {@link MoonPhase#southernView}.
     */
    public void setSouthernView(boolean southern) {
        this.southernView = southern;
    }

    public void render(int[] out, float hour, float weather,
                       float moonPhase, float seconds) {

        final float sunAlt   = sunAlt(hour);
        final float day      = clamp01(sunAlt * 3f + 0.35f);
        final float night    = 1f - day;
        final float twilight = smooth(0.45f, 0.02f, Math.abs(sunAlt));
        final float storm    = smooth(0.55f, 1.00f, weather);
        final float cover    = smooth(0.10f, 0.66f, weather);
        final float haze     = smooth(0.06f, 0.24f, weather)
                             * (1f - smooth(0.30f, 0.50f, weather));
        final float precip   = smooth(0.62f, 0.98f, weather);

        SkyKeyframes.at(hour, sky);
        final float dark = 1f - 0.42f * storm;
        final float topR = sky[0] * dark, topG = sky[1] * dark, topB = sky[2] * dark;
        final float botR = sky[3] * dark, botG = sky[4] * dark, botB = sky[5] * dark;

        // Body positions — DESIGN_NOTES §2b.
        final float thSun  = sunAngle(hour);
        final float thMoon = moonAngle(hour, moonPhase);
        final float travel = 0.3125f * h;
        final float sunX  = 72f - (float) Math.cos(thSun)  * 60f;
        final float moonX = 36f - (float) Math.cos(thMoon) * 60f;
        final float sunY  = 0.667f * h + (1f - (float) Math.sin(thSun))  * travel;
        final float moonY = 0.333f * h - (1f - (float) Math.sin(thMoon)) * travel;

        final float litFrac  = 1f - Math.abs(moonPhase - 0.5f) * 2f;
        final float glowSun  = (0.20f + 0.62f * twilight)
                             * clamp01(sunAlt + 0.55f) * (1f - 0.75f * storm);
        final float glowMoon = 0.26f * clamp01(-sunAlt + 0.25f)
                             * (1f - 0.75f * storm) * (0.15f + 0.85f * litFrac);

        for (int y = 0; y < h; y++) {
            float ty = (float) y / (h - 1);
            float m  = (float) Math.pow(ty, 0.85);   // not linear — see §2b
            float baseR = topR + (botR - topR) * m;
            float baseG = topG + (botG - topG) * m;
            float baseB = topB + (botB - topB) * m;

            for (int x = 0; x < w; x++) {
                float r = baseR, g = baseG, b = baseB;

                float dsx = x - sunX, dsy = y - sunY;
                float ds = (float) Math.sqrt(dsx * dsx + dsy * dsy);
                if (ds < 78f) {
                    float k = (float) Math.pow(1f - ds / 78f, 2.2) * glowSun;
                    r += (255f - r) * k; g += (150f - g) * k; b += (70f - b) * k;
                }

                float dmx = x - moonX, dmy = y - moonY;
                float dm = (float) Math.sqrt(dmx * dmx + dmy * dmy);
                if (dm < 46f) {
                    float k = (float) Math.pow(1f - dm / 46f, 2.4) * glowMoon;
                    r += (140f - r) * k; g += (165f - g) * k; b += (220f - b) * k;
                }

                if (haze > 0.01f) {
                    float k  = haze * 0.30f * (0.25f + ty);
                    float hz = 190f * (0.25f + 0.75f * day);
                    r += (hz - r) * k; g += (hz + 4f - g) * k; b += (hz + 16f - b) * k;
                }

                float d = Bayer.bias(x, y) * 16f;
                out[y * w + x] = pack(quantize(r + d), quantize(g + d), quantize(b + d));
            }
        }

        renderStars(out, night, weather, cover, seconds);
        renderMoon(out, sunAlt, twilight, botR, botG, botB, moonX, moonY, moonPhase);
        renderSun(out, sunAlt, sunX, sunY, seconds);

        final float ambR = (topR + botR) / 2f, ambG = (topG + botG) / 2f, ambB = (topB + botB) / 2f;
        renderClouds(out, storm, twilight, cover, weather, ambR, ambG, ambB, seconds);
        renderLightning(out, weather);
        // The public render() signature has no snow flag (see data flow in
        // the design spec: only `weather` reaches the renderer); rain is the
        // only precipitation visual for Tier 1.
        renderPrecipitation(out, precip, weather, ambR, ambG, ambB, seconds);
        applyFlash(out);

        if (desaturation > 0f) applyDesaturation(out);
        if (tintRamp != null) applyTint(out);
    }

    private void renderMoon(int[] out, float sunAlt, float twilight,
                            float botR, float botG, float botB,
                            float moonX, float moonY, float moonPhase) {
        if (moonY >= h + 16) return;
        final float R = 12f;

        float mt = clamp01(-sunAlt);
        float litBaseR = lerp(214, 244, mt), litBaseG = lerp(222, 246, mt), litBaseB = lerp(238, 252, mt);
        float lt = twilight * 0.45f;
        float litColR = lerp(litBaseR, 255, lt), litColG = lerp(litBaseG, 206, lt), litColB = lerp(litBaseB, 150, lt);
        float litMidR = litColR * 0.86f, litMidG = litColG * 0.86f, litMidB = litColB * 0.86f;
        float craterCR = litColR * 0.70f, craterCG = litColG * 0.70f, craterCB = litColB * 0.70f;
        float craterRimR = litColR * 1.06f, craterRimG = litColG * 1.06f, craterRimB = litColB * 1.06f;
        float darkColR = lerp(botR * 0.9f, 46, 0.5f), darkColG = lerp(botG * 0.9f, 52, 0.5f), darkColB = lerp(botB * 0.9f, 80, 0.5f);

        for (int y = -13; y <= 13; y++) {
            for (int x = -13; x <= 13; x++) {
                if (Math.hypot(x, y) > R) continue;
                // The disc rotates 180° south of the equator: the terminator,
                // the craters and the limb shading all read these, never x/y.
                int mx = southernView ? -x : x, my = southernView ? -y : y;
                float nx = mx / R, ny = my / R;
                float q = moonPhase <= 0.5f ? moonPhase : 1f - moonPhase;
                float sx = moonPhase <= 0.5f ? 1f : -1f;
                float term = (float) (Math.cos(2 * Math.PI * q) * Math.sqrt(Math.max(0, 1 - ny * ny)));
                boolean lit = (sx * nx) > term;
                float X = moonX + x, Y = moonY + y;
                if (!lit) { px(out, X, Y, darkColR, darkColG, darkColB, 0.55f); continue; }

                int xi = (int) X, yi = (int) Y;
                float dd = (float) Math.hypot(mx + 2.5, my + 3) + (Bayer.M[yi & 3][xi & 3] / 16f - 0.5f) * 2.2f;
                float cr = dd > R * 0.82f ? litMidR : litColR;
                float cg = dd > R * 0.82f ? litMidG : litColG;
                float cb = dd > R * 0.82f ? litMidB : litColB;
                for (float[] cr8 : CRATERS) {
                    float cd = (float) Math.hypot(nx - cr8[0], ny - cr8[1]);
                    if (cd < cr8[2]) {
                        if (cd > cr8[2] - 0.075f && ny < cr8[1]) { cr = craterRimR; cg = craterRimG; cb = craterRimB; }
                        else { cr = craterCR; cg = craterCG; cb = craterCB; }
                        break;
                    }
                }
                px(out, X, Y, cr, cg, cb, 1f);
            }
        }
    }

    private void renderSun(int[] out, float sunAlt, float sunX, float sunY, float seconds) {
        if (sunY >= h + 18) return;
        final float R = 13f;

        float k = clamp01(sunAlt * 1.9f + 0.25f);
        float t0R = lerp(214, 255, k), t0G = lerp(70, 182, k), t0B = lerp(46, 44, k);
        float t1R = lerp(255, 255, k), t1G = lerp(128, 226, k), t1B = lerp(56, 120, k);
        float t2R = lerp(255, 255, k), t2G = lerp(180, 250, k), t2B = lerp(96, 214, k);

        int rayLen = Math.max(0, Math.round(
                (2 + Math.round(1.6f + 1.6f * (float) Math.sin(seconds * 1.6)))
                        * (0.45f + 0.75f * clamp01(sunAlt + 0.4f))));
        for (int a = 0; a < 8; a++) {
            double ang = a * Math.PI / 4;
            double dx = Math.cos(ang), dy = Math.sin(ang);
            for (int i = 0; i < rayLen; i++) {
                float rr = R + 3 + i;
                px(out, Math.round(sunX + dx * rr), Math.round(sunY + dy * rr), t1R, t1G, t1B, 0.95f);
            }
        }

        for (int y = -13; y <= 13; y++) {
            for (int x = -13; x <= 13; x++) {
                if (Math.hypot(x, y) > R) continue;
                float X = sunX + x, Y = sunY + y;
                int xi = (int) X, yi = (int) Y;
                float dd = (float) Math.hypot(x + 2, y + 3) + (Bayer.M[yi & 3][xi & 3] / 16f - 0.5f) * 2.4f;
                if (dd > R * 0.88f)      px(out, X, Y, t0R, t0G, t0B, 1f);
                else if (dd > R * 0.50f) px(out, X, Y, t1R, t1G, t1B, 1f);
                else                     px(out, X, Y, t2R, t2G, t2B, 1f);
            }
        }
    }

    private void renderStars(int[] out, float night, float weather, float cover, float seconds) {
        float starVis = night * (1f - clamp01(weather / 0.30f)) * (1f - cover);
        if (starVis <= 0.02f) return;
        for (Star st : stars) {
            float tw = 0.55f + 0.45f * (float) Math.sin(seconds * 1.7 + st.ph);
            float a = clamp01(starVis * st.b * tw);
            float sy = st.yf * h;
            px(out, st.x, sy, 246, 248, 255, a);
            if (st.big) {
                px(out, st.x + 1, sy, 216, 226, 255, a * 0.5f);
                px(out, st.x, sy + 1, 216, 226, 255, a * 0.5f);
            }
        }
    }

    private void renderClouds(int[] out, float storm, float twilight, float cover, float weather,
                              float ambR, float ambG, float ambB, float seconds) {
        float baseR = lerp(252, ambR, 0.52f), baseG = lerp(253, ambG, 0.52f), baseB = lerp(255, ambB, 0.52f);
        baseR = lerp(baseR, 58, storm * 0.82f); baseG = lerp(baseG, 62, storm * 0.82f); baseB = lerp(baseB, 80, storm * 0.82f);
        float tw = twilight * 0.30f * (1f - storm);
        baseR = lerp(baseR, 255, tw); baseG = lerp(baseG, 178, tw); baseB = lerp(baseB, 132, tw);

        float hiR = baseR * 1.14f, hiG = baseG * 1.14f, hiB = baseB * 1.14f;
        float midR = baseR * 0.94f, midG = baseG * 0.94f, midB = baseB * 0.94f;
        float loR = baseR * 0.72f, loG = baseG * 0.72f, loB = baseB * 0.72f;

        float wind = 0.35f + 2.4f * weather;
        int nShown = Math.round(cover * clouds.length);

        for (int ci = 0; ci < nShown; ci++) {
            Cloud c = clouds[ci];
            float s = c.s * (0.85f + 0.55f * cover);
            float cx = mod(c.x + seconds * c.sp * wind, w + 90f) - 45f;
            float cy = c.yf * h - cover * 6f;

            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (Puff p : c.puffs) {
                minX = Math.min(minX, p.dx * s - p.r * s); maxX = Math.max(maxX, p.dx * s + p.r * s);
                minY = Math.min(minY, p.dy * s - p.r * s); maxY = Math.max(maxY, p.dy * s + p.r * s);
            }
            float spanH = maxY - minY;

            for (int y = (int) Math.floor(minY); y <= (int) Math.ceil(maxY); y++) {
                float ay = cy + y;
                if (ay < -6 || ay >= h) continue;
                for (int x = (int) Math.floor(minX); x <= (int) Math.ceil(maxX); x++) {
                    float ax = cx + x;
                    if (ax < -6 || ax >= w) continue;
                    boolean inside = false;
                    for (Puff p : c.puffs) {
                        float dx = x - p.dx * s, dy = y - p.dy * s;
                        if (dx * dx + dy * dy <= (p.r * s) * (p.r * s)) { inside = true; break; }
                    }
                    if (!inside) continue;
                    int axi = (int) ax, ayi = (int) ay;
                    float rel = spanH == 0 ? (y - minY) : (y - minY) / spanH;
                    float jit = Bayer.M[ayi & 3][axi & 3] / 16f - 0.5f;
                    float band = rel + jit * 0.18f;
                    if (band < 0.34f)      px(out, ax, ay, hiR, hiG, hiB, 1f);
                    else if (band < 0.68f) px(out, ax, ay, midR, midG, midB, 1f);
                    else                   px(out, ax, ay, loR, loG, loB, 1f);
                }
            }
        }
    }

    private void renderLightning(int[] out, float weather) {
        if (weather > 0.90f && rand.nextFloat() < (weather - 0.90f) * 0.9f) {
            flash = 1f;
            float bx = 18f + rand.nextFloat() * (w - 36);
            float x = bx, y = 40f + rand.nextFloat() * 30f;
            java.util.List<int[]> seg = new java.util.ArrayList<>();
            while (y < h) {
                seg.add(new int[]{ (int) x, (int) y });
                x += rand.nextFloat() * 6f - 3f;
                y += 3f + rand.nextFloat() * 5f;
            }
            bolt = seg.toArray(new int[0][]);
            boltLife = 7;
        }
        if (boltLife > 0 && bolt != null) {
            for (int i = 0; i < bolt.length - 1; i++) {
                int[] a = bolt[i], b = bolt[i + 1];
                int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
                for (int s2 = 0; s2 <= steps; s2++) {
                    float t = steps == 0 ? 0 : (float) s2 / steps;
                    px(out, Math.round(lerp(a[0], b[0], t)), Math.round(lerp(a[1], b[1], t)), 255, 252, 225, 1f);
                }
            }
            boltLife--;
        }
    }

    private void renderPrecipitation(int[] out, float precip, float weather,
                                     float ambR, float ambG, float ambB, float seconds) {
        if (precip <= 0.01f) return;
        int count = Math.round(drops.length * precip);
        float slant = 0.55f + weather * 1.7f;
        float rainR = lerp(176, ambR, 0.35f), rainG = lerp(206, ambG, 0.35f), rainB = lerp(238, ambB, 0.35f);

        for (int i = 0; i < count; i++) {
            Drop d = drops[i];
            float dy0 = d.yf * h;
            float speed = 70f + d.v * 90f + weather * 60f;
            float yy = mod(dy0 + seconds * speed, h + 12f) - 6f;
            float xx = mod(d.x + seconds * speed * slant * 0.28f, w + 12f) - 6f;
            for (int k = 0; k < d.len; k++) {
                px(out, xx + k * slant * 0.5f, yy + k, rainR, rainG, rainB, 0.72f - k * 0.12f);
            }
        }
    }

    private void applyFlash(int[] out) {
        if (flash <= 0.01f) return;
        float a = flash * 0.55f;
        for (int i = 0; i < out.length; i++) {
            int px = out[i];
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
            out[i] = pack(clampByte(r + (255 - r) * a),
                          clampByte(g + (255 - g) * a),
                          clampByte(b + (250 - b) * a));
        }
        flash *= 0.72f;
    }

    private static float mod(float v, float m) {
        float r = v % m;
        return r < 0 ? r + m : r;
    }

    /** Writes (or alpha-blends) one pixel; a no-op outside the buffer. Coordinates
     *  truncate toward zero, matching the prototype's {@code |0}. */
    private void px(int[] out, float xf, float yf, float r, float g, float b, float alpha) {
        int x = (int) xf, y = (int) yf;
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int i = y * w + x;
        if (alpha >= 1f) {
            out[i] = pack(clampByte(r), clampByte(g), clampByte(b));
        } else {
            int prev = out[i];
            int or = (prev >> 16) & 0xFF, og = (prev >> 8) & 0xFF, ob = prev & 0xFF;
            out[i] = pack(clampByte(or + (r - or) * alpha),
                          clampByte(og + (g - og) * alpha),
                          clampByte(ob + (b - ob) * alpha));
        }
    }

    private static int clampByte(float v) {
        int q = Math.round(v);
        return q < 0 ? 0 : (q > 255 ? 255 : q);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Snap to 15-level steps — the source of the retro banding. */
    private static int quantize(float v) {
        int q = Math.round(v / 15f) * 15;
        return q < 0 ? 0 : (q > 255 ? 255 : q);
    }

    private static int pack(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void applyDesaturation(int[] out) {
        float k = desaturation;
        for (int i = 0; i < out.length; i++) {
            int px = out[i];
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
            int l = Math.round(r * 0.299f + g * 0.587f + b * 0.114f);
            out[i] = pack(Math.round(r + (l - r) * k),
                          Math.round(g + (l - g) * k),
                          Math.round(b + (l - b) * k));
        }
    }

    /** Posterize to the palette's luminance-sorted ramp with ordered dither. */
    private void applyTint(int[] out) {
        int n = tintRamp.length;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x, px = out[i];
                float lum = (((px >> 16) & 0xFF) * 0.299f
                          +  ((px >> 8)  & 0xFF) * 0.587f
                          +  ( px        & 0xFF) * 0.114f) / 255f;
                lum += Bayer.bias(x, y) / n;
                int idx = (int) (lum * n);
                out[i] = tintRamp[idx < 0 ? 0 : (idx >= n ? n - 1 : idx)];
            }
        }
    }

    /** The sun's hour angle: 0 at 06:00, π at 18:00. */
    static float sunAngle(float hour) {
        return (hour - 6f) / 12f * (float) Math.PI;
    }

    /**
     * The moon's hour angle. The moon lags the sun by exactly its
     * elongation: new moon ({@code phase 0}) puts it on the sun; full
     * ({@code phase 0.5}) puts it opposite — the one case the old fixed
     * {@code thSun + π} formula got right, since it assumed every night was
     * a full moon. The quarters sit a quarter turn off, matching them
     * rising/setting roughly six hours from the sun.
     *
     * <p>Ignores lunar declination and the parallactic angle, so this is
     * right to roughly the hour rather than the minute — see MoonPhase's
     * own javadoc for the same limit on the phase itself. Against a 12px
     * disc that is the correct place to stop.
     */
    static float moonAngle(float hour, float phase) {
        return sunAngle(hour) + 2f * (float) Math.PI * phase;
    }

    /** Day span, in hours, between the sky gradient's dawn and dusk
     *  anchors — see {@link SolarClock}. */
    private static final float DAY_SPAN_HOURS = SolarClock.SUNSET_ANCHOR - SolarClock.SUNRISE_ANCHOR;
    private static final float NIGHT_SPAN_HOURS = 24f - DAY_SPAN_HOURS;

    /**
     * Sun altitude proxy: {@code 0} at both {@link SolarClock#SUNRISE_ANCHOR}
     * and {@link SolarClock#SUNSET_ANCHOR}, {@code 1} at solar noon
     * (midway between them), {@code -1} at solar midnight. Piecewise so it
     * stays continuous and consistent with the anchors {@code SolarClock}
     * warps real time onto, rather than the fixed 6/18 the table used to
     * assume.
     */
    public static float sunAlt(float hour) {
        if (hour >= SolarClock.SUNRISE_ANCHOR && hour <= SolarClock.SUNSET_ANCHOR) {
            return (float) Math.sin(Math.PI * (hour - SolarClock.SUNRISE_ANCHOR) / DAY_SPAN_HOURS);
        }
        float h = hour < SolarClock.SUNRISE_ANCHOR ? hour + 24f : hour;
        return -(float) Math.sin(Math.PI * (h - SolarClock.SUNSET_ANCHOR) / NIGHT_SPAN_HOURS);
    }

    public static float smooth(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
