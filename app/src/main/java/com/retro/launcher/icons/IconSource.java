package com.retro.launcher.icons;

import android.graphics.Bitmap;

import com.retro.launcher.core.Palette;
import com.retro.launcher.data.AppEntry;

/**
 * The Tier 2 decision seam (design spec §3.4). Two implementations exist —
 * {@link GeneratedTileIcons} and {@link PosterizedIcons} — wired behind a
 * debug toggle so the owner can measure frame time and drawer-scroll feel
 * before picking one. Whichever loses gets deleted, not left as dead weight.
 */
public interface IconSource {
    Bitmap iconFor(AppEntry app, Palette palette, int sizePx);
    void onPaletteChanged();
}
