package com.retro.launcher.core;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PixelGlyphsTest {

    @Test public void carriesEveryMarkFromTheDesignSheet() {
        assertEquals(51, PixelGlyphs.names().size());
    }

    @Test public void knownPackagesResolveToTheirMark() {
        assertEquals("whatsapp", PixelGlyphs.forPackage("com.whatsapp"));
        assertEquals("chrome", PixelGlyphs.forPackage("com.android.chrome"));
        assertEquals("camera", PixelGlyphs.forPackage("com.sec.android.app.camera"));
        assertEquals("settings", PixelGlyphs.forPackage("com.android.settings"));
        assertEquals("gmaps", PixelGlyphs.forPackage("com.google.android.apps.maps"));
    }

    /** The whole point of the fallback: anything unlisted gets a letter. */
    @Test public void unknownPackagesResolveToNothing() {
        assertNull(PixelGlyphs.forPackage("com.example.some.random.app"));
        assertNull(PixelGlyphs.forPackage(""));
        assertNull(PixelGlyphs.forPackage(null));
    }

    @Test public void everyMappedPackageNamesAMarkThatExists() {
        for (String name : PixelGlyphs.names()) assertTrue(name, PixelGlyphs.has(name));
        assertFalse(PixelGlyphs.has("no-such-mark"));
        assertFalse(PixelGlyphs.has(null));
    }

    @Test public void composedMarkFillsTheSharedTileSilhouette() {
        char[][] grid = PixelGlyphs.compose("clock");
        assertEquals(PixelTile.SIZE, grid.length);
        for (int y = 0; y < PixelTile.SIZE; y++) {
            assertEquals(PixelTile.SIZE, grid[y].length);
            int[] span = PixelTile.rowSpan(y);
            for (int x = 0; x < PixelTile.SIZE; x++) {
                boolean inTile = x >= span[0] && x <= span[1];
                boolean painted = grid[y][x] != PixelGlyphs.ROLE_EMPTY;
                assertEquals("row " + y + " col " + x, inTile, painted);
            }
        }
    }

    /** The 12x12 art lands at (2,2); the two-pixel border stays tile-coloured. */
    @Test public void markSitsInsetInsideTheTile() {
        char[][] grid = PixelGlyphs.compose("calculator");
        // calculator's first row is all 'p', so (2,2)..(2,13) must be primary.
        for (int x = 2; x <= 13; x++) {
            assertEquals(PixelGlyphs.ROLE_PRIMARY, grid[2][x]);
        }
        // Row 1 is entirely tile — inside the silhouette, above the art.
        int[] span = PixelTile.rowSpan(1);
        for (int x = span[0]; x <= span[1]; x++) {
            assertEquals(PixelGlyphs.ROLE_TILE, grid[1][x]);
        }
    }

    /** A dot in the art means "tile shows through", not "punch a hole". */
    @Test public void transparentArtPixelsFallBackToTheTileBody() {
        char[][] grid = PixelGlyphs.compose("instagram");
        // instagram row 3 is "pppp....pppp": cols 4-7 of the art are dots.
        for (int x = 2 + 4; x <= 2 + 7; x++) {
            assertEquals(PixelGlyphs.ROLE_TILE, grid[3 + 2][x]);
        }
    }

    @Test public void everyMarkUsesOnlyKnownRoles() {
        for (String name : PixelGlyphs.names()) {
            for (char[] row : PixelGlyphs.compose(name)) {
                for (char c : row) {
                    assertTrue(name + " uses '" + c + "'",
                            c == PixelGlyphs.ROLE_EMPTY || c == PixelGlyphs.ROLE_TILE
                                    || c == PixelGlyphs.ROLE_PRIMARY || c == PixelGlyphs.ROLE_ACCENT
                                    || c == PixelGlyphs.ROLE_SHADE || c == PixelGlyphs.ROLE_HIGHLIGHT);
                }
            }
        }
    }

    @Test public void runsRebuildTheGridExactly() {
        for (String name : PixelGlyphs.names()) {
            char[][] grid = PixelGlyphs.compose(name);
            char[][] painted = new char[PixelGlyphs.SIZE][PixelGlyphs.SIZE];
            for (char[] row : painted) java.util.Arrays.fill(row, PixelGlyphs.ROLE_EMPTY);
            for (int[] run : PixelGlyphs.runs(name)) {
                int y = run[0], from = run[1], to = run[2];
                char role = (char) run[3];
                assertTrue(name, from <= to);
                for (int x = from; x <= to; x++) {
                    assertEquals(name + " overlapping run", PixelGlyphs.ROLE_EMPTY, painted[y][x]);
                    painted[y][x] = role;
                }
            }
            for (int y = 0; y < PixelGlyphs.SIZE; y++) {
                assertArrayEquals(name + " row " + y, grid[y], painted[y]);
            }
        }
    }

    /** Runs must be maximal, or the renderer draws more rects than it needs. */
    @Test public void adjacentRunsNeverShareARole() {
        int[][] runs = PixelGlyphs.runs("gallery");
        for (int i = 1; i < runs.length; i++) {
            int[] prev = runs[i - 1], cur = runs[i];
            if (prev[0] == cur[0] && prev[2] + 1 == cur[1]) {
                assertTrue("run " + i + " should have merged", prev[3] != cur[3]);
            }
        }
    }

    @Test public void unknownMarkIsRejectedRatherThanDrawnBlank() {
        try {
            PixelGlyphs.compose("nope");
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
