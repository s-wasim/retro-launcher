package com.retro.launcher.icons;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import com.retro.launcher.core.IconCacheKey;
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
 * <p><b>2.1.2 — what each stage costs, and therefore what is cached where.</b>
 * The three stages are nothing like each other in price, so they are not
 * cached alike:
 *
 * <ul>
 *   <li><b>Stage 1</b> is a few dozen {@code drawRect} calls against a 16x16
 *       grid, with no {@code PackageManager} round-trip. Re-rendering it is
 *       cheaper than decoding a PNG of it, so it is held in memory and never
 *       written to disk — which is the "when a mark is available, nothing
 *       happens" case.</li>
 *   <li><b>Stage 2</b> is the expensive one: a drawable load across a Binder
 *       call, an adaptive-icon crop, 576 nearest-ramp lookups and a scale.
 *       Memory <em>and</em> disk, with the 24-hour TTL from
 *       {@link IconCacheKey}.</li>
 *   <li><b>Stage 3</b> is cheap to draw but is only reached after stage 2 has
 *       already paid its full cost to discover there was no usable icon — the
 *       drawable load and the blank test are the price of getting here at
 *       all. Caching it is therefore caching that failed lookup, which is the
 *       point. Memory and disk.</li>
 * </ul>
 *
 * <p>Stages 1 and 2 are cached at their own source resolution — 16x16 and
 * 24x24 — and upscaled by the returned {@link BitmapDrawable} with filtering
 * off, at 1/29th of the memory. Stage 2 is pixel-for-pixel identical to what
 * it produced before: {@code createScaledBitmap(…, false)} is itself a
 * filterless matrix scale, so moving it from eager to draw-time changes when
 * it happens and not what it computes. Stage 1 is a grid rather than a
 * rescale — it used to round each run's edges to device pixels itself, and
 * now lets the same nearest-neighbour upscale do it — so a cell boundary can
 * land one device pixel either side of where it used to at sizes that are not
 * a whole multiple of 16. Same cells, same colours, same hard edges. Stage 3
 * is antialiased text and cannot be reconstructed from a smaller copy at all,
 * so it alone is still rendered and cached per size.
 *
 * <p>This replaces the {@code GeneratedTileIcons} / {@code PosterizedIcons}
 * either-or, which was wired behind a debug flag and made the two mutually
 * exclusive — so a hand-drawn mark and a converted real icon could never
 * appear in the same drawer. They are stages of one chain, not rivals.
 */
public final class PixelArtIcons implements IconSource {

    /** The conversion resolution. Coarse enough to read as pixel art at any
     *  size, fine enough that a logo survives it — 16 lost too much. */
    private static final int SRC = 24;

    /** An adaptive icon's centre safe zone, per the platform's own spec: the
     *  logo occupies 72 of the 108 units, the rest is a background plate that
     *  would otherwise fill the whole converted square with one flat colour. */
    private static final float SAFE_ZONE = 72f / 108f;

    private final PackageManager pm;
    private final IconCache cache;
    private final Resources resources;

    public PixelArtIcons(Resources resources, PackageManager pm, IconCache cache) {
        this.resources = resources;
        this.pm = pm;
        this.cache = cache;
    }

    /**
     * The three stages, each asked of the cache before it is rendered.
     *
     * <p>The order matters for more than appearance: a stage-1 hit never
     * touches {@code PackageManager} at all, and a stage-2 <em>or</em> stage-3
     * hit skips the drawable load that both of them otherwise require. So a
     * warm cache turns the whole method into one lookup, which is what makes
     * scrolling the drawer cost nothing.
     */
    @Override public Drawable iconFor(AppEntry app, Palette palette, int sizePx) {
        String component = app.component();

        // Stage 1 — a hand-drawn mark. Memory-only: see the class note.
        String mark = PixelGlyphs.forPackage(app.packageName);
        if (mark != null) {
            String key = IconCacheKey.key(
                    component, palette.id, palette.dark, IconCacheKey.STAGE_MARK);
            Bitmap hit = cache.get(key);
            if (hit == null) {
                hit = drawMark(mark, palette);
                cache.putMemory(key, hit);
            }
            return pixels(hit);
        }

        // Stage 2 — the app's own icon, quantized. Memory and disk.
        String iconKey = IconCacheKey.key(
                component, palette.id, palette.dark, IconCacheKey.STAGE_ICON);
        Bitmap cachedIcon = cache.get(iconKey);
        if (cachedIcon != null) return pixels(cachedIcon);

        // Stage 3's key is checked before stage 2 is rendered, because
        // reaching stage 3 at all means stage 2 already ran and failed — and
        // that failed drawable load is exactly the cost worth not repeating.
        String letterKey = IconCacheKey.sizedKey(
                component, palette.id, palette.dark, IconCacheKey.STAGE_LETTER, sizePx);
        Bitmap cachedLetter = cache.get(letterKey);
        if (cachedLetter != null) return pixels(cachedLetter);

        Bitmap converted = convertRealIcon(app, palette);
        if (converted != null) {
            cache.put(iconKey, converted);
            return pixels(converted);
        }

        Bitmap letter = drawLetterTile(app.firstLetter(), palette, sizePx);
        cache.put(letterKey, letter);
        return pixels(letter);
    }

    /**
     * Wraps a cached bitmap for drawing without resampling it.
     *
     * <p>Filtering off is what keeps this pixel art: the ImageView scales the
     * 16x16 or 24x24 source up to the row height with nearest-neighbour,
     * which is the identical operation {@code createScaledBitmap(…, false)}
     * used to perform eagerly into a 67 KB bitmap. Dithering off for the same
     * reason — these pixels are exact palette entries and must stay exact.
     */
    private Drawable pixels(Bitmap bmp) {
        BitmapDrawable d = new BitmapDrawable(resources, bmp);
        d.setFilterBitmap(false);
        d.setAntiAlias(false);
        d.setDither(false);
        return d;
    }

    // ---- stage 1: hand-drawn marks ---------------------------------------

    /**
     * Marks are pixel art: no antialiasing, and every rect snapped to the
     * 16x16 grid, or the edges turn to mush at small icon sizes.
     *
     * <p>2.1.2 draws at the grid's own resolution rather than at the caller's
     * pixel size: one bitmap pixel per grid cell, upscaled nearest-neighbour
     * by the ImageView. 1 KB instead of 67 KB, and the size drops out of the
     * cache key. The rounding of a cell boundary to a device pixel moves from
     * this method's {@code Math.round} to that upscale, which can place an
     * edge one pixel either side of where it used to sit when the icon size
     * is not a whole multiple of 16 — the cells, their colours and their hard
     * edges are unchanged.
     */
    private static Bitmap drawMark(String mark, Palette palette) {
        int size = PixelGlyphs.SIZE;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint();
        paint.setAntiAlias(false);
        for (int[] run : PixelGlyphs.runs(mark)) {
            int row = run[0], start = run[1], end = run[2];
            paint.setColor(colorFor((char) run[3], palette));
            canvas.drawRect(start, row, end + 1, row + 1, paint);
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
    private Bitmap convertRealIcon(AppEntry app, Palette palette) {
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

        // 2.1.2: the 24x24 square is what gets cached and returned. The
        // upscale that used to happen here — createScaledBitmap(…, false),
        // eagerly, into a bitmap 29x this size — now happens in the ImageView
        // at draw time through the filterless drawable from pixels(), which is
        // the same nearest-neighbour operation and allocates nothing.
        return small;
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

    /**
     * Every stage colours through the palette, so nothing cached under the old
     * one is reusable. Only the memory tier is dropped: the palette is part of
     * the key, so the stored files are not stale — they are simply not being
     * asked for — and a user switching back gets them from disk instead of
     * re-rendering the lot. {@code DiskIconCache.sweep()} reclaims whatever
     * really is abandoned when its TTL runs out.
     */
    @Override public void onPaletteChanged() { cache.evictAll(); }

    /**
     * Under memory pressure the memory tier is exactly the right thing to give
     * back: every byte of it is reconstructible, stages 2 and 3 from a
     * ~300-byte PNG decode and stage 1 from a few {@code drawRect} calls.
     */
    @Override public void onTrimMemory() { cache.evictAll(); }
}
