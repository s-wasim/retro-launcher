package com.retro.launcher.icons;

import android.graphics.Bitmap;
import android.util.Log;

import com.retro.launcher.core.Palette;
import com.retro.launcher.data.AppEntry;

/**
 * Wraps an {@link IconSource} with per-call timing, logged as a running
 * average every 30 calls (about one drawer screenful). This is what the
 * Tier 2 gate reads to pick between {@link GeneratedTileIcons} and
 * {@link PosterizedIcons} — see design spec §3.4 and §9's icon risk row.
 * Debug-only; never wired in a release build.
 */
public final class InstrumentedIconSource implements IconSource {

    private static final String TAG = "IconBench";
    private static final int WINDOW = 30;

    private final IconSource delegate;
    private final String label;
    private long sumNanos = 0L;
    private int count = 0;

    public InstrumentedIconSource(IconSource delegate, String label) {
        this.delegate = delegate;
        this.label = label;
    }

    @Override public Bitmap iconFor(AppEntry app, Palette palette, int sizePx) {
        long start = System.nanoTime();
        Bitmap bmp = delegate.iconFor(app, palette, sizePx);
        long elapsed = System.nanoTime() - start;

        sumNanos += elapsed;
        count++;
        if (count >= WINDOW) {
            double avgMs = (sumNanos / (double) count) / 1_000_000.0;
            Log.d(TAG, label + ": avg " + avgMs + "ms/icon over " + count + " calls");
            sumNanos = 0L;
            count = 0;
        }
        return bmp;
    }

    @Override public void onPaletteChanged() { delegate.onPaletteChanged(); }
}
