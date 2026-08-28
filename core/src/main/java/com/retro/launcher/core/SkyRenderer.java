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

        if (desaturation > 0f) applyDesaturation(out);
        if (tintRamp != null) applyTint(out);
    }

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
