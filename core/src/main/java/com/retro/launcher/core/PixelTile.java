package com.retro.launcher.core;

/**
 * The 16x16 rounded-square icon silhouette both {@code GeneratedTileIcons}
 * and {@code PosterizedIcons} fill. Ported in spirit, not in exact pixels,
 * from the prototype's {@code TILE_SPAN} table (DESIGN_NOTES §6) — the
 * prototype's own values are per hand-authored 12x12 demo glyph, which does
 * not generalize to arbitrary installed apps (§6, §9 delta 2), so this
 * reconstructs the same "rounded square, corners cut more the further out"
 * shape from a per-row inset that shrinks 3, 2, 1, 0 toward the middle.
 */
public final class PixelTile {

    private PixelTile() {}

    public static final int SIZE = 16;

    private static final int[] CORNER_INSET = { 3, 2, 1, 0 };

    /** Inclusive [start, end] column span filled at the given row. */
    public static int[] rowSpan(int row) {
        int inset = insetFor(row);
        return new int[] { inset, SIZE - 1 - inset };
    }

    private static int insetFor(int row) {
        int fromTop = row;
        int fromBottom = SIZE - 1 - row;
        int edge = Math.min(fromTop, fromBottom);
        return edge < CORNER_INSET.length ? CORNER_INSET[edge] : 0;
    }

    public static boolean[][] silhouette() {
        boolean[][] grid = new boolean[SIZE][SIZE];
        for (int row = 0; row < SIZE; row++) {
            int[] span = rowSpan(row);
            for (int col = span[0]; col <= span[1]; col++) grid[row][col] = true;
        }
        return grid;
    }

    /** One row-run per row: {row, startCol, endCol}. */
    public static int[][] runs() {
        int[][] out = new int[SIZE][];
        for (int row = 0; row < SIZE; row++) {
            int[] span = rowSpan(row);
            out[row] = new int[] { row, span[0], span[1] };
        }
        return out;
    }
}
