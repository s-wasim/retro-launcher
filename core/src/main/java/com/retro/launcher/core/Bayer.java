package com.retro.launcher.core;

/**
 * The 4x4 ordered-dither matrix the prototype uses in three places: sky
 * quantization, the sun and moon discs, and the optional palette tint.
 *
 * This dithering is not an artifact to be cleaned up — it is what produces the
 * retro banding. See DESIGN_NOTES §2b.
 */
public final class Bayer {

    private Bayer() {}

    public static final int[][] M = {
            { 0,  8,  2, 10},
            {12,  4, 14,  6},
            { 3, 11,  1,  9},
            {15,  7, 13,  5}
    };

    /** Threshold bias in [-0.5, 0.4375], tiling every 4 pixels. */
    public static float bias(int x, int y) {
        return M[y & 3][x & 3] / 16f - 0.5f;
    }
}
