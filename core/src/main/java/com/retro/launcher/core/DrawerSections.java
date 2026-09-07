package com.retro.launcher.core;

/**
 * Which alphabetical section a drawer row belongs to (V9 §9).
 *
 * <p>The rule this replaces scanned <em>forward</em> through the label for the
 * first {@code A-Z} and returned that, so {@code "1Password"} found the {@code
 * P} of "Password" and was filed under <b>P</b> — a header several screens
 * away from where the row actually sits, because the drawer sorts by the
 * lowercased label and digits precede letters in ASCII. Every leading-digit
 * and leading-symbol app in the list was misplaced the same way.
 *
 * <p>The fix is to look at character zero and nothing else. That is the only
 * rule that agrees with the sort: a row's header is decided by exactly the
 * character the ordering is decided by, so a section can never be split or
 * stranded. Everything that is not {@code A-Z} — digits, punctuation,
 * whitespace, non-Latin scripts, an empty or absent label — collects in one
 * {@code '#'} group, which sorts to the top of the list on its own.
 *
 * <p>This lives in {@code core} rather than beside {@code AppEntry} because
 * the {@code app} module has no unit-test source set; {@code
 * AppEntry.firstLetter()} delegates here.
 */
public final class DrawerSections {

    /** The section every non-{@code A-Z} label collects under. */
    public static final char OTHER = '#';

    private DrawerSections() {}

    /**
     * @param label the row's display label; null and empty are tolerated
     *              because {@code PackageManager} can hand back either for a
     *              damaged package
     * @return {@code 'A'}-{@code 'Z'} when the label starts with a Latin
     *         letter in either case, {@link #OTHER} otherwise
     */
    public static char sectionFor(String label) {
        if (label == null || label.isEmpty()) return OTHER;
        char c = Character.toUpperCase(label.charAt(0));
        return (c >= 'A' && c <= 'Z') ? c : OTHER;
    }
}
