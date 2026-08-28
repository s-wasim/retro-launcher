package com.retro.launcher.icons;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.retro.launcher.core.Palette;
import com.retro.launcher.core.PixelGlyphs;
import com.retro.launcher.core.PixelTile;
import com.retro.launcher.data.AppEntry;

/**
 * The prototype's 16x16 tile silhouette ({@link PixelTile}) filled with
 * {@code tile}. An app the design sheet drew a mark for gets that mark
 * ({@link PixelGlyphs}); everything else carries its first letter in
 * {@code p}. See design spec §3.4. Cheap either way: a few dozen filled rects
 * at worst, cached by {@link IconCache}.
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
        float scale = sizePx / (float) PixelTile.SIZE;

        String mark = PixelGlyphs.forPackage(app.packageName);
        if (mark != null) {
            drawMark(canvas, mark, palette, scale);
        } else {
            drawTile(canvas, palette, scale);
            drawLetter(canvas, app.firstLetter(), palette, sizePx);
        }

        cache.put(key, bmp);
        return bmp;
    }

    /** Marks are pixel art: no antialiasing, and every rect snapped to the
     *  16x16 grid, or the edges turn to mush at small icon sizes. */
    private static void drawMark(Canvas canvas, String mark, Palette palette, float scale) {
        Paint paint = new Paint();
        paint.setAntiAlias(false);
        for (int[] run : PixelGlyphs.runs(mark)) {
            int row = run[0], start = run[1], end = run[2];
            paint.setColor(colorFor((char) run[3], palette));
            canvas.drawRect(Math.round(start * scale), Math.round(row * scale),
                    Math.round((end + 1) * scale), Math.round((row + 1) * scale), paint);
        }
    }

    private static int colorFor(char role, Palette palette) {
        switch (role) {
            case PixelGlyphs.ROLE_PRIMARY:   return palette.p;
            case PixelGlyphs.ROLE_ACCENT:    return palette.a;
            case PixelGlyphs.ROLE_SHADE:     return palette.s;
            case PixelGlyphs.ROLE_HIGHLIGHT: return palette.h;
            case PixelGlyphs.ROLE_TILE:
            default:                         return palette.tile;
        }
    }

    private static void drawTile(Canvas canvas, Palette palette, float scale) {
        Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tilePaint.setColor(palette.tile);
        for (int[] run : PixelTile.runs()) {
            int row = run[0], start = run[1], end = run[2];
            canvas.drawRect(start * scale, row * scale, (end + 1) * scale, (row + 1) * scale, tilePaint);
        }
    }

    private static void drawLetter(Canvas canvas, char letter, Palette palette, int sizePx) {
        Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        letterPaint.setColor(palette.p);
        letterPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        letterPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setTextSize(sizePx * 0.5f);
        float baselineOffset = (letterPaint.descent() + letterPaint.ascent()) / 2f;
        canvas.drawText(String.valueOf(letter), sizePx / 2f, sizePx / 2f - baselineOffset, letterPaint);
    }

    @Override public void onPaletteChanged() { cache.evictAll(); }
}
