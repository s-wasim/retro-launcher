package com.retro.launcher.core;

/**
 * What an icon is cached under, and for how long.
 *
 * <p>Pure string and time arithmetic so the parts that are easy to get
 * silently wrong — a filename that collides, a TTL that never expires because
 * of a clock that went backwards — are testable on a bare JDK. The bitmaps
 * themselves live in {@code icons/IconCache} and {@code icons/DiskIconCache},
 * which are the only things here that need an Android type.
 *
 * <p><b>{@link #key} deliberately carries no pixel size.</b> Before 2.1.2 it
 * did, because the cache stored icons already upscaled to whatever the drawer,
 * the dock or the sheet had asked for — three or four entries per app, each
 * one a 130x130 ARGB_8888 bitmap at 67 KB. The cache now stores the icon at
 * the resolution it was <em>drawn</em> at (24x24 converted, 16x16 mark),
 * 2.3 KB, and the upscale happens in the ImageView with filtering off. Same
 * nearest-neighbour pixels on screen, one entry per app instead of four, and
 * a whole device's worth of apps fits in the budget instead of sixty.
 *
 * <p>That works because both of those stages are grid-snapped pixel art: a
 * mark is whole {@code drawRect}s on a 16x16 grid and a converted icon is a
 * 24x24 quantized square, so upscaling either by nearest-neighbour in the
 * ImageView reproduces {@code createScaledBitmap(…, false)} pixel for pixel.
 * The letter tile is not — it is antialiased text, whose glyph shape at 130px
 * is not recoverable from a smaller bitmap — so it keeps the size in its key
 * and uses {@link #sizedKey}. That is stage 3, which only fires for an app
 * with no icon of its own at all, so on a normal device it is a handful of
 * entries and usually none.
 */
public final class IconCacheKey {

    private IconCacheKey() {}

    /** Bump when the stored bitmap's meaning changes — a different source
     *  resolution, a different quantizer — so old files are ignored rather
     *  than decoded into something that no longer matches. The directory name
     *  carries it, so a bump orphans the old tree and {@code sweep()} reclaims
     *  it. */
    public static final int FORMAT_VERSION = 1;

    /** 24 hours. An app's icon changes when the app updates, and an update the
     *  cache has not noticed is at worst a day of the previous icon — against
     *  a full re-render of every icon on every cold start, which is what the
     *  launcher did before. {@code PACKAGE_ADDED}/{@code REMOVED} already
     *  invalidates the obvious case; this covers the rest. */
    public static final long TTL_MILLIS = 24L * 60L * 60L * 1000L;

    /** Stage 1 of {@code PixelArtIcons}: a hand-drawn mark. */
    public static final String STAGE_MARK   = "m";
    /** Stage 2: the app's own icon, quantized through the palette ramp. */
    public static final String STAGE_ICON   = "i";
    /** Stage 3: the letter tile, for an app with no icon at all. */
    public static final String STAGE_LETTER = "t";

    /**
     * The in-memory cache key.
     *
     * @param component {@code pkg/activity}, or {@code pkg/activity@serial}
     * @param paletteId the resolved palette, because every stage colours
     *                  through it — a cached icon from another palette is the
     *                  wrong icon, not a stale one
     * @param dark      light/dark within that palette
     * @param stage     one of the {@code STAGE_*} constants
     */
    public static String key(String component, String paletteId, boolean dark, String stage) {
        return nz(component) + '|' + nz(paletteId) + '|' + (dark ? 'd' : 'l') + '|' + nz(stage);
    }

    /**
     * {@link #key} plus the pixel size, for {@link #STAGE_LETTER} — the one
     * stage whose output is not grid-snapped pixel art and so cannot be
     * reconstructed by upscaling a smaller cached copy. See the class note.
     */
    public static String sizedKey(String component, String paletteId, boolean dark,
                                  String stage, int sizePx) {
        return key(component, paletteId, dark, stage) + '|' + sizePx;
    }

    /**
     * A filename for {@link #key}. Component keys contain {@code /}, {@code .}
     * and {@code @}, none of which belong in a filename, so the name is a
     * 64-bit FNV-1a hash of the key rendered as hex — fixed length, no
     * separator to escape, and no path component that could escape the cache
     * directory.
     *
     * <p>A hash can collide. At 64 bits and a few hundred icons the odds are
     * around one in 10^14, and the cost of a collision is one app wearing
     * another's icon until the TTL expires — not a crash, and not a security
     * boundary, since everything in this directory is our own.
     */
    public static String fileName(String key) {
        return hex(fnv1a(nz(key))) + ".png";
    }

    /** The cache subdirectory, versioned so {@link #FORMAT_VERSION} bumps
     *  orphan the old tree rather than mixing formats inside one. */
    public static String directoryName() {
        return "icons-v" + FORMAT_VERSION;
    }

    /**
     * Whether a file written at {@code writtenAtMillis} may still be used.
     *
     * <p>A timestamp in the future is <em>not</em> fresh. It means the clock
     * moved backwards — a reboot without a network time fix, a user changing
     * the date — and treating it as fresh would pin a stale icon for however
     * far ahead the clock had been. Re-rendering is cheap; being stuck is not.
     */
    public static boolean isFresh(long writtenAtMillis, long nowMillis) {
        if (writtenAtMillis <= 0L) return false;       // unreadable mtime
        long age = nowMillis - writtenAtMillis;
        return age >= 0L && age < TTL_MILLIS;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static long fnv1a(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** Zero-padded to 16 so every name is the same length. */
    private static String hex(long v) {
        StringBuilder b = new StringBuilder(16);
        for (int shift = 60; shift >= 0; shift -= 4) {
            b.append(Character.forDigit((int) ((v >>> shift) & 0xF), 16));
        }
        return b.toString();
    }
}
