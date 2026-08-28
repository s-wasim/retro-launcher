package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class PixelTileTest {

    @Test public void gridIsSixteenBySixteen() {
        boolean[][] grid = PixelTile.silhouette();
        assertEquals(16, grid.length);
        for (boolean[] row : grid) assertEquals(16, row.length);
    }

    @Test public void centerIsFilled() {
        boolean[][] grid = PixelTile.silhouette();
        assertTrue(grid[8][8]);
    }

    @Test public void extremeCornersAreCutForRoundedShape() {
        boolean[][] grid = PixelTile.silhouette();
        assertFalse(grid[0][0]);
        assertFalse(grid[0][15]);
        assertFalse(grid[15][0]);
        assertFalse(grid[15][15]);
    }

    @Test public void middleRowsSpanFullWidth() {
        int[] span = PixelTile.rowSpan(8);
        assertEquals(0, span[0]);
        assertEquals(15, span[1]);
    }

    @Test public void topRowIsMostInset() {
        int[] span = PixelTile.rowSpan(0);
        assertTrue(span[0] > PixelTile.rowSpan(2)[0]);
    }

    @Test public void runsEncodeOneSpanPerFilledRow() {
        int[][] runs = PixelTile.runs();
        assertEquals(16, runs.length);
        for (int row = 0; row < 16; row++) {
            int[] span = PixelTile.rowSpan(row);
            assertEquals(row, runs[row][0]);
            assertEquals(span[0], runs[row][1]);
            assertEquals(span[1], runs[row][2]);
        }
    }
}
