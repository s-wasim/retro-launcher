package com.retro.launcher.core;

/**
 * Posterizes a colour to the nearest entry in a luminance-sorted ramp, biased
 * by the shared {@link Bayer} matrix so flat regions still band instead of
 * banding-then-snapping to one flat tone. Used by the wallpaper's optional
 * palette tint and by {@code PosterizedIcons} — same ramp, same matrix, so
 * icons and wallpaper agree on what "this palette's grey" looks like.
 */
public final class Quantize {

    private Quantize() {}

    public static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return Math.round(0.299f * r + 0.587f * g + 0.114f * b);
    }

    /** Index into {@code ramp} nearest {@code argb}'s luminance, dithered by (x, y). */
    public static int nearestIndex(int argb, int[] ramp, int x, int y) {
        float bias = Bayer.bias(x, y) * 32f;
        float lum = luminance(argb) + bias;

        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < ramp.length; i++) {
            float d = Math.abs(luminance(ramp[i]) - lum);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }
}
