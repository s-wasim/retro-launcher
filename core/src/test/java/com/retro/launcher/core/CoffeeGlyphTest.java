package com.retro.launcher.core;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The mug is chrome, not an app logo, so it is not in the {@link PixelGlyphs}
 * table — but it goes through the same stamping and the same run flattening,
 * and it has to hold to the same invariants the renderer relies on.
 */
public class CoffeeGlyphTest {

    @Test public void staysOutOfTheAppLogoTable() {
        assertEquals(51, PixelGlyphs.names().size());
        org.junit.Assert.assertFalse(PixelGlyphs.has("coffee"));
    }

    @Test public void usesOnlyKnownRoles() {
        for (int[] run : CoffeeGlyph.runs()) {
            char role = (char) run[3];
            assertTrue("uses '" + role + "'",
                    role == PixelGlyphs.ROLE_TILE || role == PixelGlyphs.ROLE_PRIMARY
                            || role == PixelGlyphs.ROLE_ACCENT || role == PixelGlyphs.ROLE_SHADE
                            || role == PixelGlyphs.ROLE_HIGHLIGHT);
        }
    }

    /** Nothing may spill outside the tile, or the mug grows corners the app
     *  icons beside it do not have. */
    @Test public void everyRunLiesInsideTheTileSilhouette() {
        for (int[] run : CoffeeGlyph.runs()) {
            int y = run[0], from = run[1], to = run[2];
            assertTrue("row " + y, y >= 0 && y < PixelTile.SIZE);
            assertTrue("empty run at row " + y, from <= to);
            int[] span = PixelTile.rowSpan(y);
            assertTrue("row " + y + " starts at " + from, from >= span[0]);
            assertTrue("row " + y + " ends at " + to, to <= span[1]);
        }
    }

    /** Same contract as {@link PixelGlyphs#runs}: the runs tile the grid
     *  exactly once, with no overlap and no gap. */
    @Test public void runsCoverTheTileExactlyOnce() {
        char[][] painted = new char[PixelTile.SIZE][PixelTile.SIZE];
        for (char[] row : painted) java.util.Arrays.fill(row, PixelGlyphs.ROLE_EMPTY);

        for (int[] run : CoffeeGlyph.runs()) {
            char role = (char) run[3];
            for (int x = run[1]; x <= run[2]; x++) {
                assertEquals("overlapping run", PixelGlyphs.ROLE_EMPTY, painted[run[0]][x]);
                painted[run[0]][x] = role;
            }
        }

        // Painted exactly where the silhouette is, and nowhere else.
        for (int y = 0; y < PixelTile.SIZE; y++) {
            int[] span = PixelTile.rowSpan(y);
            for (int x = 0; x < PixelTile.SIZE; x++) {
                boolean inTile = x >= span[0] && x <= span[1];
                assertEquals("row " + y + " col " + x,
                        inTile, painted[y][x] != PixelGlyphs.ROLE_EMPTY);
            }
        }
        assertArrayEquals("the runs must rebuild the composed grid",
                CoffeeGlyph.compose(), painted);
    }

    /** A mug with no mug in it would still pass every structural check above. */
    @Test public void actuallyDrawsSomethingOnTheTile() {
        boolean marked = false;
        for (int[] run : CoffeeGlyph.runs()) {
            if ((char) run[3] != PixelGlyphs.ROLE_TILE) { marked = true; break; }
        }
        assertTrue("the mark is entirely tile-coloured", marked);
    }

    @Test public void aMarkOfTheWrongHeightIsRejected() {
        try {
            PixelGlyphs.composeRows(new String[]{"............"});
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            org.junit.Assert.assertNotNull(expected.getMessage());
        }
    }
}
