package com.retro.launcher.icons;

import android.graphics.Bitmap;
import android.util.LruCache;

/**
 * Bitmap cache keyed by {@code component + palette id + light/dark + source +
 * sizePx}, sized in bytes so it stays bounded regardless of icon size.
 */
public final class IconCache {

    private static final int BUDGET_BYTES = 4 * 1024 * 1024; // 4MB

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(BUDGET_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public static String key(String component, String paletteId, boolean dark, String source, int sizePx) {
        return component + '|' + paletteId + '|' + (dark ? 'd' : 'l') + '|' + source + '|' + sizePx;
    }

    public Bitmap get(String key) { return cache.get(key); }

    public void put(String key, Bitmap bitmap) { cache.put(key, bitmap); }

    public void evictAll() { cache.evictAll(); }
}
