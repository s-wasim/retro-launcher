package com.retro.launcher.icons;

import android.graphics.drawable.Drawable;

import com.retro.launcher.core.Palette;
import com.retro.launcher.data.AppEntry;

/**
 * How an app becomes something an {@code ImageView} can draw. One
 * implementation — {@link PixelArtIcons} — and one debug-only measuring
 * wrapper, {@link InstrumentedIconSource}. The seam stays because the drawer,
 * the dock and the search overlay all draw through it, and swapping the
 * implementation for a measurement or an experiment should not touch any of
 * them.
 *
 * <p><b>2.1.2: a Drawable, not a Bitmap.</b> This returned a Bitmap already
 * scaled to {@code sizePx}, which meant the cache had to hold one upscaled
 * copy per size per app — see {@link IconCache} for what that cost. It now
 * returns a drawable wrapping the icon at its own small source resolution,
 * with filtering off, so the ImageView performs the nearest-neighbour upscale
 * at draw time for free. {@code sizePx} is still passed because stage 3, the
 * letter tile, genuinely renders differently at different sizes.
 */
public interface IconSource {

    /**
     * @param sizePx the size the caller will draw at. A hint for every stage
     *               but the letter tile, which renders to it exactly.
     * @return never null — the pipeline always has a last resort
     */
    Drawable iconFor(AppEntry app, Palette palette, int sizePx);

    void onPaletteChanged();

    /** Release what can be re-derived. Called from {@code onTrimMemory}. */
    void onTrimMemory();
}
