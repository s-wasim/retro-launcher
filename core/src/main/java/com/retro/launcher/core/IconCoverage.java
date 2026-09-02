package com.retro.launcher.core;

/**
 * Whether a rendered icon has anything in it.
 *
 * <p>Needed because "this app has no icon" is not an exception on Android —
 * {@code getApplicationIcon} hands back a generic placeholder rather than
 * throwing, so the only honest test is to render the thing and look. An app
 * whose icon converts to almost nothing is better served by a letter tile
 * than by a nearly-empty square.
 */
public final class IconCoverage {

    private IconCoverage() {}

    /** Above this fraction of fully transparent pixels, there is no icon
     *  worth showing. Set where a small mark — a thin glyph on a clear
     *  background — still counts as real content. */
    public static final float BLANK_THRESHOLD = 0.97f;

    /** An alpha at or below this is transparent for our purposes; anti-aliased
     *  edges leave a fringe of near-zero alpha that is not content. */
    private static final int ALPHA_FLOOR = 8;

    /**
     * True when more than {@link #BLANK_THRESHOLD} of {@code pixels} are
     * transparent. A null or empty array is blank.
     *
     * @param pixels ARGB_8888 pixels, in any layout — only the alpha channel
     *               and the count matter
     */
    public static boolean isBlank(int[] pixels) {
        if (pixels == null || pixels.length == 0) return true;
        int clear = 0;
        for (int argb : pixels) {
            if (((argb >>> 24) & 0xFF) <= ALPHA_FLOOR) clear++;
        }
        return clear > pixels.length * BLANK_THRESHOLD;
    }
}
