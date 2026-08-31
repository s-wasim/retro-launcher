package com.retro.launcher.core;

import java.util.Locale;

/**
 * The all-caps rule behind the category field's input filter.
 *
 * <p>Category names are drawn as chips in the drawer's tab strip, where every
 * other word is upper case, and they are also compared and stored as plain
 * strings. Both want the text raised as it is typed rather than merely drawn
 * that way: {@code setAllCaps} is display-only, so a lower-case name would
 * still reach {@code Prefs} and fail to match the chip it became.
 *
 * <p>Two details make this worth a class of its own rather than a lambda in a
 * view:
 *
 * <ul>
 *   <li>{@link Locale#ROOT}, not the default locale. Under a Turkish locale
 *       {@code "mini".toUpperCase()} is {@code "MİNİ"}, and a category typed
 *       on that phone would not match the same word typed anywhere else.</li>
 *   <li>Null for "no change". An {@code InputFilter} that returns a
 *       replacement discards the spans on the text it replaces, and one of
 *       them is the IME's composing span — the keyboard's own record of the
 *       word it is still assembling. Returning a fresh string on every
 *       keystroke restarts that word each time.</li>
 * </ul>
 */
public final class CapsText {

    private CapsText() {}

    /**
     * The upper-case form of {@code source[start, end)}, or null if raising it
     * would change nothing.
     */
    public static String upperOrNull(CharSequence source, int start, int end) {
        boolean changed = false;
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if (c != Character.toUpperCase(c)) { changed = true; break; }
        }
        if (!changed) return null;
        return source.subSequence(start, end).toString().toUpperCase(Locale.ROOT);
    }
}
