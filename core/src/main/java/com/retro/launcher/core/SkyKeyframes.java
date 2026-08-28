package com.retro.launcher.core;

/**
 * The 24-hour sky gradient. Fourteen keyframes of (top, bottom) RGB,
 * linearly interpolated. Transcribed from the prototype's SKY table —
 * see DESIGN_NOTES §2b.
 */
public final class SkyKeyframes {

    private SkyKeyframes() {}

    private static final float[] H = {
            0f, 3.5f, 5f, 6.2f, 7f, 8.5f, 12f,
            15.5f, 17.2f, 18.4f, 19.3f, 20.4f, 22f, 24f
    };

    private static final int[][] TOP = {
            { 10, 14, 38}, { 11, 15, 42}, { 20, 24, 62}, { 46, 62,128},
            { 62,104,182}, { 68,136,216}, { 54,130,228}, { 64,134,224},
            { 78,132,206}, { 62, 84,164}, { 40, 44,110}, { 22, 27, 70},
            { 12, 16, 44}, { 10, 14, 38}
    };

    private static final int[][] BOT = {
            { 22, 28, 64}, { 34, 32, 76}, { 82, 56,104}, {214,118,104},
            {255,166,112}, {176,214,246}, {156,208,247}, {178,206,240},
            {248,196,152}, {255,142, 88}, {206, 84, 96}, { 96, 52,104},
            { 36, 34, 78}, { 22, 28, 64}
    };

    /**
     * Fills {@code out} with {topR, topG, topB, botR, botG, botB} as 0-255
     * floats. Hours outside [0, 24] clamp to the endpoints.
     */
    public static void at(float hour, float[] out) {
        if (hour <= H[0]) { copy(0, out); return; }
        if (hour >= H[H.length - 1]) { copy(H.length - 1, out); return; }

        for (int i = 0; i < H.length - 1; i++) {
            if (hour >= H[i] && hour <= H[i + 1]) {
                float t = (hour - H[i]) / (H[i + 1] - H[i]);
                for (int c = 0; c < 3; c++) {
                    out[c]     = lerp(TOP[i][c], TOP[i + 1][c], t);
                    out[c + 3] = lerp(BOT[i][c], BOT[i + 1][c], t);
                }
                return;
            }
        }
        copy(0, out);
    }

    private static void copy(int i, float[] out) {
        for (int c = 0; c < 3; c++) { out[c] = TOP[i][c]; out[c + 3] = BOT[i][c]; }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
