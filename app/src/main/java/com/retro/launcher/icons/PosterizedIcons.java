package com.retro.launcher.icons;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import com.retro.launcher.core.Palette;
import com.retro.launcher.core.Quantize;
import com.retro.launcher.data.AppEntry;

/**
 * Loads the real system icon, downsamples it to 16x16, then posterizes each
 * pixel through the palette's luminance-sorted 6-colour ramp with the shared
 * Bayer bias — the same quantization the wallpaper's tint mode uses. See
 * design spec §3.4. One processing pass per app per palette, cached.
 */
public final class PosterizedIcons implements IconSource {

    private static final String SOURCE = "post";
    private static final int SRC = 16;

    private final PackageManager pm;
    private final IconCache cache;

    public PosterizedIcons(PackageManager pm, IconCache cache) {
        this.pm = pm;
        this.cache = cache;
    }

    @Override public Bitmap iconFor(AppEntry app, Palette palette, int sizePx) {
        String key = IconCache.key(app.component(), palette.id, palette.dark, SOURCE, sizePx);
        Bitmap cached = cache.get(key);
        if (cached != null) return cached;

        Drawable icon = loadIcon(app);
        Bitmap small = Bitmap.createBitmap(SRC, SRC, Bitmap.Config.ARGB_8888);
        if (icon != null) {
            Canvas canvas = new Canvas(small);
            icon.setBounds(0, 0, SRC, SRC);
            icon.draw(canvas);
        }

        int[] pixels = new int[SRC * SRC];
        small.getPixels(pixels, 0, SRC, 0, 0, SRC, SRC);
        int[] ramp = palette.ramp();
        for (int y = 0; y < SRC; y++) {
            for (int x = 0; x < SRC; x++) {
                int i = y * SRC + x;
                int argb = pixels[i];
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) continue;
                int idx = Quantize.nearestIndex(argb, ramp, x, y);
                pixels[i] = (alpha << 24) | (ramp[idx] & 0x00FFFFFF);
            }
        }
        small.setPixels(pixels, 0, SRC, 0, 0, SRC, SRC);

        Bitmap bmp = Bitmap.createScaledBitmap(small, sizePx, sizePx, false);
        cache.put(key, bmp);
        return bmp;
    }

    private Drawable loadIcon(AppEntry app) {
        try {
            return pm.getActivityIcon(new ComponentName(app.packageName, app.activityName));
        } catch (PackageManager.NameNotFoundException e) {
            try {
                return pm.getApplicationIcon(app.packageName);
            } catch (PackageManager.NameNotFoundException ignored) {
                return null;
            }
        }
    }

    @Override public void onPaletteChanged() { cache.evictAll(); }
}
