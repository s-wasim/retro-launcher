package com.retro.launcher.icons;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import com.retro.launcher.core.IconCoverage;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.PixelGlyphs;
import com.retro.launcher.core.PixelTile;
import com.retro.launcher.core.Quantize;
import com.retro.launcher.data.AppEntry;

/**
 * Every app icon in the launcher, as pixel art, in three stages evaluated per
 * app:
 *
 * <ol>
 *   <li><b>Hand-drawn mark.</b> {@link PixelGlyphs} — 16x16, palette-role
 *       coloured. Big-name apps keep their crafted marks.</li>
 *   <li><b>Converted real icon.</b> The app's own icon rendered at 24x24 and
 *       quantized through the palette's ramp with the shared Bayer bias, so
 *       icons and wallpaper speak one colour language. Upscaled
 *       nearest-neighbour, which is what keeps it pixel art rather than a
 *       blurry small icon.</li>
 *   <li><b>Letter tile.</b> Only when the app genuinely has no icon.</li>
 * </ol>
 *
 * <p>This replaces the {@code GeneratedTileIcons} / {@code PosterizedIcons}
 * either-or, which was wired behind a debug flag and made the two mutually
 * exclusive — so a hand-drawn mark and a converted real icon could never
 * appear in the same drawer. They are stages of one chain, not rivals.
 */
public final class PixelArtIcons implements IconSource {

    private static final String SOURCE = "pixart";

    /** The conversion resolution. Coarse enough to read as pixel art at any
     *  size, fine enough that a logo survives it — 16 lost too much. */
    private static final int SRC = 24;

    /** An adaptive icon's centre safe zone, per the platform's own spec: the
     *  logo occupies 72 of the 108 units, the rest is a background plate that
     *  would otherwise fill the whole converted square with one flat colour. */
    private static final float SAFE_ZONE = 72f / 108f;

    private final PackageManager pm;
    private final IconCache cache;

    public PixelArtIcons(PackageManager pm, IconCache cache) {
        this.pm = pm;
        this.cache = cache;
    }

    @Override public Bitmap iconFor(AppEntry app, Palette palette, int sizePx) {
        String key = IconCache.key(app.component(), palette.id, palette.dark, SOURCE, sizePx);
        Bitmap cached = cache.get(key);
        if (cached != null) return cached;

        Bitmap bmp = render(app, palette, sizePx);
        cache.put(key, bmp);
        return bmp;
    }

    private Bitmap render(AppEntry app, Palette palette, int sizePx) {
        // Stage 1.
        String mark = PixelGlyphs.forPackage(app.packageName);
        if (mark != null) return drawMark(mark, palette, sizePx);

        // Stage 2.
        Bitmap converted = convertRealIcon(app, palette, sizePx);
        if (converted != null) return converted;

        // Stage 3.
        return drawLetterTile(app.firstLetter(), palette, sizePx);
    }

    // ---- stage 1: hand-drawn marks ---------------------------------------

    /** Marks are pixel art: no antialiasing, and every rect snapped to the
     *  16x16 grid, or the edges turn to mush at small icon sizes. */
    private static Bitmap drawMark(String mark, Palette palette, int sizePx) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float scale = sizePx / (float) PixelTile.SIZE;
        Paint paint = new Paint();
        paint.setAntiAlias(false);
        for (int[] run : PixelGlyphs.runs(mark)) {
            int row = run[0], start = run[1], end = run[2];
            paint.setColor(colorFor((char) run[3], palette));
            canvas.drawRect(Math.round(start * scale), Math.round(row * scale),
                    Math.round((end + 1) * scale), Math.round((row + 1) * scale), paint);
        }
        return bmp;
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

    // ---- stage 2: the real icon, converted --------------------------------

    /** @return the converted icon, or null when the app has no real icon —
     *          which is a rendered test, not an exception check, because the
     *          platform hands back a placeholder rather than throwing. */
    private Bitmap convertRealIcon(AppEntry app, Palette palette, int sizePx) {
        Drawable icon = loadIcon(app);
        if (icon == null || isPlatformDefault(icon)) return null;

        Bitmap small = Bitmap.createBitmap(SRC, SRC, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        drawCropped(icon, canvas);

        int[] pixels = new int[SRC * SRC];
        small.getPixels(pixels, 0, SRC, 0, 0, SRC, SRC);
        if (IconCoverage.isBlank(pixels)) return null;

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

        // false: nearest-neighbour. Filtering here would turn pixel art back
        // into a blurry small icon, which is the whole thing we are avoiding.
        return Bitmap.createScaledBitmap(small, sizePx, sizePx, false);
    }

    /**
     * Draws {@code icon} into the canvas, cropping an adaptive icon to its
     * centre safe zone first. Without the crop the conversion sees mostly the
     * full-bleed background plate and every adaptive icon quantizes to the
     * same flat square.
     */
    private static void drawCropped(Drawable icon, Canvas canvas) {
        boolean adaptive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && icon instanceof AdaptiveIconDrawable;
        if (!adaptive) {
            icon.setBounds(0, 0, SRC, SRC);
            icon.draw(canvas);
            return;
        }
        // Draw at the inflated size the crop implies, offset so the safe zone
        // lands on the canvas.
        int inflated = Math.round(SRC / SAFE_ZONE);
        int offset = (inflated - SRC) / 2;
        icon.setBounds(new Rect(-offset, -offset, inflated - offset, inflated - offset));
        icon.draw(canvas);
    }

    private Drawable loadIcon(AppEntry app) {
        try {
            return pm.getActivityIcon(new ComponentName(app.packageName, app.activityName));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            try {
                return pm.getApplicationIcon(app.packageName);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                return null;
            }
        }
    }

    /** The framework's generic placeholder, which is what an app with no
     *  icon of its own resolves to. */
    private boolean isPlatformDefault(Drawable icon) {
        try {
            Drawable fallback = pm.getDefaultActivityIcon();
            return fallback != null
                    && fallback.getConstantState() != null
                    && fallback.getConstantState().equals(icon.getConstantState());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    // ---- stage 3: the letter tile -----------------------------------------

    private static Bitmap drawLetterTile(char letter, Palette palette, int sizePx) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float scale = sizePx / (float) PixelTile.SIZE;

        Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tilePaint.setColor(palette.tile);
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
        canvas.drawText(String.valueOf(letter), sizePx / 2f, sizePx / 2f - baselineOffset, letterPaint);
        return bmp;
    }

    @Override public void onPaletteChanged() { cache.evictAll(); }
}
