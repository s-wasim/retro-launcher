package com.retro.launcher.icons;

import android.graphics.Bitmap;
import android.util.LruCache;

import com.retro.launcher.core.IconCacheKey;

/**
 * Bitmap cache keyed by {@code component + palette id + light/dark + stage},
 * sized in bytes so it stays bounded regardless of icon size.
 *
 * <p><b>2.1.2 changed what is stored, not just where.</b> This used to hold
 * icons already upscaled to the pixel size the caller asked for, which made
 * the size part of the key and put a 130x130 ARGB_8888 bitmap — 67 KB — in
 * the cache three or four times per app, once for each of the drawer, dock
 * and sheet sizes. A 4 MB budget therefore held about sixty entries on a
 * device with several hundred apps, so scrolling the drawer evicted and
 * re-rendered continuously: that was both the memory pressure that got the
 * launcher killed and the scroll jank.
 *
 * <p>It now holds the bitmap at the resolution it was <em>drawn</em> at —
 * 24x24 for a converted icon, 16x16 for a mark — at 2.3 KB and 1 KB, and the
 * upscale happens in the ImageView with filtering off. That is the same
 * nearest-neighbour result {@code createScaledBitmap(…, false)} produced, so
 * nothing on screen changes; what changes is that a whole device's icons now
 * fit in a fraction of the budget and never evict in normal use.
 *
 * <p>The budget is kept at a ceiling rather than cut to match, because the
 * letter tile is still rendered at a readable size and an unusually large
 * install should have room. In practice the cache now sits far under it.
 *
 * <p>Behind the memory tier sits {@link DiskIconCache}, which survives the
 * process. A miss in both is the only path that re-renders.
 */
public final class IconCache {

    private static final int BUDGET_BYTES = 4 * 1024 * 1024; // 4MB

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(BUDGET_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** Null when no cache directory was available; every disk path then
     *  no-ops and the cache is memory-only, exactly as it was before 2.1.2. */
    private final DiskIconCache disk;

    /** Memory-only. Kept for tests and for any caller with nowhere to write. */
    public IconCache() {
        this(null);
    }

    public IconCache(DiskIconCache disk) {
        this.disk = disk;
        if (disk != null) disk.sweep();
    }

    /** @see IconCacheKey#key(String, String, boolean, String) */
    public static String key(String component, String paletteId, boolean dark, String stage) {
        return IconCacheKey.key(component, paletteId, dark, stage);
    }

    /**
     * Memory first, then disk. A disk hit is promoted into memory so the
     * second look costs nothing.
     */
    public Bitmap get(String key) {
        Bitmap hit = cache.get(key);
        if (hit != null) return hit;
        if (disk == null) return null;

        Bitmap fromDisk = disk.read(key);
        if (fromDisk != null) cache.put(key, fromDisk);
        return fromDisk;
    }

    /** Memory only. For a stage whose render is cheaper than a PNG decode —
     *  see {@link DiskIconCache}'s note on the hand-drawn marks. */
    public void putMemory(String key, Bitmap bitmap) {
        cache.put(key, bitmap);
    }

    /** Memory and, when there is one, disk. */
    public void put(String key, Bitmap bitmap) {
        cache.put(key, bitmap);
        if (disk != null) disk.write(key, bitmap);
    }

    /**
     * Drops the memory tier and keeps the disk tier.
     *
     * <p>Called on a palette change and from {@code onTrimMemory}. In both
     * cases the stored files are still valid — the palette is part of the key,
     * so switching palettes does not invalidate anything, it just stops asking
     * for those entries — and throwing them away would mean re-rendering every
     * icon on the way back. Reclaiming the bytes is the point; re-fetching
     * them from disk costs a ~300-byte decode.
     */
    public void evictAll() {
        cache.evictAll();
    }

    /** Drops both tiers. Nothing calls this today; it exists so a future
     *  "reset icons" affordance has one honest entry point. */
    public void clearAll() {
        cache.evictAll();
        if (disk != null) disk.clear();
    }
}
