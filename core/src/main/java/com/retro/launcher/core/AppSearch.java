package com.retro.launcher.core;

/**
 * Scores an app label against what the user typed, for the double-tap search
 * overlay (DESIGN_NOTES §5).
 *
 * Ranking is by where the query lands, not by how many characters it shares:
 * someone typing "cal" wants Calculator before Google Calendar, and Google
 * Calendar before Vocal Remover. Ties break toward the shorter label, which is
 * almost always the app you meant.
 */
public final class AppSearch {

    private AppSearch() {}

    /** Returned for anything that does not match at all. */
    public static final int NO_MATCH = -1;

    private static final int EXACT       = 4000;
    private static final int PREFIX      = 3000;
    private static final int WORD_PREFIX = 2000;
    private static final int CONTAINS    = 1000;

    /** Labels longer than this get no length bonus; the bonus only has to
     *  separate near-identical candidates. */
    private static final int LENGTH_BONUS_CAP = 64;

    /**
     * @return a score where higher is a better match, or {@link #NO_MATCH}
     *         (negative) if the label does not match at all. Null or blank
     *         input never matches.
     */
    public static int score(String label, String query) {
        if (label == null || query == null) return NO_MATCH;

        String q = query.trim().toLowerCase();
        if (q.isEmpty()) return NO_MATCH;

        String l = label.toLowerCase();
        if (q.length() > l.length()) return NO_MATCH;

        int base;
        if (l.equals(q))              base = EXACT;
        else if (l.startsWith(q))     base = PREFIX;
        else if (startsAWord(l, q))   base = WORD_PREFIX;
        else if (l.contains(q))       base = CONTAINS;
        else                          return NO_MATCH;

        return base + Math.max(0, LENGTH_BONUS_CAP - l.length());
    }

    /**
     * True if the query begins some word of the label. A word starts after any
     * non-letter-or-digit, so "Files-by-Google" is three words, not one.
     */
    private static boolean startsAWord(String label, String query) {
        for (int i = 1; i < label.length(); i++) {
            if (Character.isLetterOrDigit(label.charAt(i - 1))) continue;
            if (label.startsWith(query, i)) return true;
        }
        return false;
    }
}
