package com.retro.launcher.icons;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.retro.launcher.core.Palette;
import com.retro.launcher.core.PixelTile;
import com.retro.launcher.data.AppEntry;

/**
 * The prototype's 16x16 tile silhouette ({@link PixelTile}) filled with
 * {@code tile}, carrying the app's first letter in {@code p}. See design spec
 * §3.4. Cheap: a handful of draw calls per icon, cached by {@link IconCache}.
 */
public final class GeneratedTileIcons implements IconSource {

    private static final String SOURCE = "gen";

    private final IconCache cache;

    public GeneratedTileIcons(IconCache cache) {
        this.cache = cache;
    }

    @Override public Bitmap iconFor(AppEntry app, Palette palette, int sizePx) {
        String key = IconCache.key(app.component(), palette.id, palette.dark, SOURCE, sizePx);
        Bitmap cached = cache.get(key);
        if (cached != null) return cached;

        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tilePaint.setColor(palette.tile);
        float scale = sizePx / (float) PixelTile.SIZE;
        for (int[] run : PixelTile.runs()) {
            int row = run[0], start = run[1], end = run[2];
            canvas.drawRect(start * scale, row * scale, (end + 1) * scale, (row + 1) * scale, tilePaint);
        }

        Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        letterPaint.setColor(palette.p);
        letterPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        letterPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setTextSize(sizePx * 0.5f);
        float baselineOffset = (letterPaint.descent() + letterPaint.ascent()) / 2f;
        canvas.drawText(String.valueOf(app.firstLetter()), sizePx / 2f, sizePx / 2f - baselineOffset, letterPaint);

        cache.put(key, bmp);
        return bmp;
    }

    @Override public void onPaletteChanged() { cache.evictAll(); }
}
