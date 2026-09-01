package com.retro.launcher.core;

/**
 * The mug on the BUY ME A COFFEE row in the screen time panel.
 *
 * Drawn the same way every app icon is — a 12x12 grid of colour <em>roles</em>
 * stamped into the {@link PixelTile} silhouette, so the cup picks up whichever
 * palette is in force instead of carrying colours of its own. It lives here
 * rather than in {@link PixelGlyphs} because that table is the design sheet's
 * 51 app logos, transcribed verbatim, and a piece of launcher chrome is not
 * one of them.
 *
 * Rows 0-2 are steam, 4-9 the mug and its handle, 10-11 the base and saucer.
 */
public final class CoffeeGlyph {

    private CoffeeGlyph() {}

    private static final String[] ROWS = {
            "..a...a.....",
            "...a...a....",
            "..a...a.....",
            "............",
            "ppppppppp...",
            "phhhhhhhp...",
            "pssssssspppp",
            "psssssssp..p",
            "pssssssspppp",
            "psssssssp...",
            ".ppppppp....",
            "aaaaaaaaaaaa",
    };

    /** The mark stamped into the tile: a 16x16 grid of role chars. */
    public static char[][] compose() {
        return PixelGlyphs.composeRows(ROWS);
    }

    /** Horizontal runs of one role: {@code {row, startCol, endColInclusive,
     *  role}}, exactly what {@link PixelGlyphs#runs} yields for an app mark. */
    public static int[][] runs() {
        return PixelGlyphs.runsRows(ROWS);
    }
}
