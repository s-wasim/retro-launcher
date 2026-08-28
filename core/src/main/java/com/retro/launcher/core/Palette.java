package com.retro.launcher.core;

/**
 * One resolved colour set. The prototype names six roles plus derived ink;
 * see DESIGN_NOTES §3 for the full table.
 */
public final class Palette {

    public final String id;
    public final String label;
    public final boolean dark;

    public final int bg;    // screen background
    public final int tile;  // icon body
    public final int p;     // primary accent — borders, active fills
    public final int a;     // secondary accent
    public final int s;     // shadow / recessed
    public final int h;     // highlight, near-white
    public final int ink;   // text

    Palette(String id, String label, boolean dark,
            int bg, int tile, int p, int a, int s, int h, int ink) {
        this.id = id; this.label = label; this.dark = dark;
        this.bg = bg; this.tile = tile; this.p = p;
        this.a = a; this.s = s; this.h = h; this.ink = ink;
    }

    /**
     * The translucent background used by the clock widget, dock and scrubber.
     * The prototype writes it as an 8-digit CSS hex — bg plus alpha D9 in dark
     * and E0 in light. Android wants ARGB, so the alpha moves to the front.
     */
    public int veil() {
        int alpha = dark ? 0xD9 : 0xE0;
        return (alpha << 24) | (bg & 0x00FFFFFF);
    }

    /**
     * The six role colours (excluding {@code ink}), sorted ascending by
     * luminance — the ramp {@link Quantize} posterizes wallpaper pixels and
     * app icons against.
     */
    public int[] ramp() {
        int[] r = { bg, tile, p, a, s, h };
        for (int i = 1; i < r.length; i++) {
            int v = r[i], j = i - 1;
            while (j >= 0 && Quantize.luminance(r[j]) > Quantize.luminance(v)) {
                r[j + 1] = r[j];
                j--;
            }
            r[j + 1] = v;
        }
        return r;
    }
}
