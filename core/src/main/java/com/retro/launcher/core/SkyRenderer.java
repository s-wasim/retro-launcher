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

    private final int w, h;
    private final float[] sky = new float[6];

    private int[] tintRamp;          // null unless "tint wallpaper to palette"
    private float desaturation;      // 0 = off; the over-limit nag

    public SkyRenderer(int w, int h) {
        this.w = w;
        this.h = h;
    }

    public void setTint(int[] rampArgb) { this.tintRamp = rampArgb; }

    public void setDesaturation(float amount) {
        this.desaturation = clamp01(amount);
    }

    public void render(int[] out, float hour, float weather,
                       float moonPhase, float seconds) {

        final float sunAlt   = sunAlt(hour);
        final float day      = clamp01(sunAlt * 3f + 0.35f);
        final float twilight = smooth(0.45f, 0.02f, Math.abs(sunAlt));
        final float storm    = smooth(0.55f, 1.00f, weather);
        final float haze     = smooth(0.06f, 0.24f, weather)
                             * (1f - smooth(0.30f, 0.50f, weather));

        SkyKeyframes.at(hour, sky);
        final float dark = 1f - 0.42f * storm;
        final float topR = sky[0] * dark, topG = sky[1] * dark, topB = sky[2] * dark;
        final float botR = sky[3] * dark, botG = sky[4] * dark, botB = sky[5] * dark;

        // Body positions — DESIGN_NOTES §2b.
        final float thSun  = (hour - 6f) / 12f * (float) Math.PI;
        final float thMoon = thSun + (float) Math.PI;
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

        renderMoon(out, sunAlt, twilight, botR, botG, botB, moonX, moonY, moonPhase);
        renderSun(out, sunAlt, sunX, sunY, seconds);

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
                float nx = x / R, ny = y / R;
                float q = moonPhase <= 0.5f ? moonPhase : 1f - moonPhase;
                float sx = moonPhase <= 0.5f ? 1f : -1f;
                float term = (float) (Math.cos(2 * Math.PI * q) * Math.sqrt(Math.max(0, 1 - ny * ny)));
                boolean lit = (sx * nx) > term;
                float X = moonX + x, Y = moonY + y;
                if (!lit) { px(out, X, Y, darkColR, darkColG, darkColB, 0.55f); continue; }

                int xi = (int) X, yi = (int) Y;
                float dd = (float) Math.hypot(x + 2.5, y + 3) + (Bayer.M[yi & 3][xi & 3] / 16f - 0.5f) * 2.2f;
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

    public static float sunAlt(float hour) {
        return (float) Math.sin((hour - 6f) / 12f * Math.PI);
    }

    public static float smooth(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
