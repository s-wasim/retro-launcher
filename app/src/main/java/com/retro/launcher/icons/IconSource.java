package com.retro.launcher.icons;

import android.graphics.Bitmap;

import com.retro.launcher.core.Palette;
import com.retro.launcher.data.AppEntry;

/**
 * How an app becomes a bitmap. One implementation —
 * {@link PixelArtIcons} — and one debug-only measuring wrapper,
 * {@link InstrumentedIconSource}. The seam stays because the drawer, the dock
 * and the search overlay all draw through it, and swapping the implementation
 * for a measurement or an experiment should not touch any of them.
 */
public interface IconSource {
    Bitmap iconFor(AppEntry app, Palette palette, int sizePx);
    void onPaletteChanged();
}
