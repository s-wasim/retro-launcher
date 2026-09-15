package com.retro.launcher.core;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 2.1.2. The cache key decides two things that fail quietly if they are
 * wrong: whether two different icons can land on one filename, and whether an
 * entry ever expires. Both are pure string and time arithmetic, so both are
 * testable without an emulator — which is the point of keeping them out of
 * {@code icons/}.
 */
public class IconCacheKeyTest {

    private static final String PKG = "com.whatsapp/.Main";

    // ---- the key ---------------------------------------------------------

    @Test public void theKeyCarriesNoPixelSize() {
        // The whole 2.1.2 memory win: one entry per app, not one per size the
        // drawer, the dock and the sheet each happen to ask for.
        String key = IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON);
        assertFalse(key.contains("130"));
        assertFalse(key.matches(".*\\d{2,}.*"));
    }

    @Test public void theLetterTileKeepsItsSizeBecauseItIsNotGridSnapped() {
        // Stage 3 is antialiased text: a 130px glyph is not recoverable by
        // upscaling a 64px one, so it is the one stage that stays per-size.
        assertNotEquals(
                IconCacheKey.sizedKey(PKG, "dusk", true, IconCacheKey.STAGE_LETTER, 130),
                IconCacheKey.sizedKey(PKG, "dusk", true, IconCacheKey.STAGE_LETTER, 140));
    }

    @Test public void aSizedKeyNeverCollidesWithItsUnsizedForm() {
        assertNotEquals(
                IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_LETTER),
                IconCacheKey.sizedKey(PKG, "dusk", true, IconCacheKey.STAGE_LETTER, 130));
    }

    @Test public void adifferentPaletteIsADifferentIcon() {
        assertNotEquals(
                IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON),
                IconCacheKey.key(PKG, "noon", true, IconCacheKey.STAGE_ICON));
    }

    @Test public void lightAndDarkWithinOnePaletteAreDifferentIcons() {
        assertNotEquals(
                IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON),
                IconCacheKey.key(PKG, "dusk", false, IconCacheKey.STAGE_ICON));
    }

    @Test public void eachStageIsItsOwnEntry() {
        Set<String> keys = new HashSet<>();
        keys.add(IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_MARK));
        keys.add(IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON));
        keys.add(IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_LETTER));
        assertEquals(3, keys.size());
    }

    @Test public void aCloneDoesNotShareItsOriginalsEntry() {
        assertNotEquals(
                IconCacheKey.key("com.whatsapp/.Main", "dusk", true, IconCacheKey.STAGE_ICON),
                IconCacheKey.key("com.whatsapp/.Main@95", "dusk", true, IconCacheKey.STAGE_ICON));
    }

    @Test public void nullsDoNotThrow() {
        assertNotNull(IconCacheKey.key(null, null, false, null));
        assertNotNull(IconCacheKey.fileName(null));
    }

    // ---- the filename ----------------------------------------------------

    @Test public void theFilenameHasNoPathSeparatorsLeftInIt() {
        // The component key is full of '/', '.' and '@'; none may survive
        // into a name that gets resolved against the cache directory.
        String name = IconCacheKey.fileName(
                IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON));
        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
        assertFalse(name.contains(".."));
        assertTrue(name.endsWith(".png"));
    }

    @Test public void everyFilenameIsTheSameLength() {
        assertEquals(
                IconCacheKey.fileName("a").length(),
                IconCacheKey.fileName("a very much longer key than that one").length());
        assertEquals(20, IconCacheKey.fileName("a").length()); // 16 hex + ".png"
    }

    @Test public void theFilenameIsStableAcrossCalls() {
        String key = IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON);
        assertEquals(IconCacheKey.fileName(key), IconCacheKey.fileName(key));
    }

    @Test public void distinctKeysGetDistinctFilenames() {
        Set<String> names = new HashSet<>();
        String[] palettes = { "dawn", "noon", "dusk", "night", "storm" };
        String[] stages = {
                IconCacheKey.STAGE_MARK, IconCacheKey.STAGE_ICON, IconCacheKey.STAGE_LETTER };
        int expected = 0;
        for (int i = 0; i < 200; i++) {
            for (String palette : palettes) {
                for (String stage : stages) {
                    for (boolean dark : new boolean[] { true, false }) {
                        names.add(IconCacheKey.fileName(
                                IconCacheKey.key("com.app" + i + "/.Main", palette, dark, stage)));
                        expected++;
                    }
                }
            }
        }
        assertEquals("a collision in 6000 realistic keys", expected, names.size());
    }

    @Test public void theDirectoryCarriesTheFormatVersion() {
        assertEquals("icons-v" + IconCacheKey.FORMAT_VERSION, IconCacheKey.directoryName());
    }

    // ---- the TTL ---------------------------------------------------------

    private static final long NOW = 1_800_000_000_000L;

    @Test public void aFileWrittenJustNowIsFresh() {
        assertTrue(IconCacheKey.isFresh(NOW, NOW));
    }

    @Test public void aFileStaysFreshForTheWholeDay() {
        assertTrue(IconCacheKey.isFresh(NOW - IconCacheKey.TTL_MILLIS + 1, NOW));
    }

    @Test public void aFileExpiresExactlyAtTheTtl() {
        assertFalse(IconCacheKey.isFresh(NOW - IconCacheKey.TTL_MILLIS, NOW));
    }

    @Test public void theTtlIsTwentyFourHours() {
        assertEquals(86_400_000L, IconCacheKey.TTL_MILLIS);
    }

    @Test public void anUnreadableTimestampIsNotFresh() {
        // File.lastModified() returns 0 for a file it cannot stat.
        assertFalse(IconCacheKey.isFresh(0L, NOW));
        assertFalse(IconCacheKey.isFresh(-1L, NOW));
    }

    @Test public void aTimestampFromTheFutureIsNotFresh() {
        // A clock that went backwards — a reboot with no network time, or the
        // user changing the date. Treating this as fresh would pin the icon
        // for however far ahead the clock had been.
        assertFalse(IconCacheKey.isFresh(NOW + 1, NOW));
        assertFalse(IconCacheKey.isFresh(NOW + IconCacheKey.TTL_MILLIS * 400, NOW));
    }
}
