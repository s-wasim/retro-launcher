package com.retro.launcher.core;

import org.junit.After;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.*;

public class CapsTextTest {

    private final Locale original = Locale.getDefault();

    @After public void restoreLocale() { Locale.setDefault(original); }

    private static String upper(String s) {
        return CapsText.upperOrNull(s, 0, s.length());
    }

    @Test public void raisesLowercase() {
        assertEquals("WORK", upper("work"));
    }

    @Test public void raisesTheLowercasePartOfMixedText() {
        assertEquals("WORK STUFF", upper("Work stuff"));
    }

    /** The one that matters for an InputFilter: returning a replacement for
     *  text that needs none discards the spans on it, and the composing span
     *  is how the IME tracks the word it is still assembling. Dropping it
     *  mid-word makes the keyboard restart its suggestion on every keystroke. */
    @Test public void returnsNullWhenNothingWouldChange() {
        assertNull(upper("WORK"));
        assertNull(upper("WORK 2"));
        assertNull(upper("123 —·—"));
        assertNull(upper(""));
    }

    @Test public void leavesDigitsAndPunctuationAlone() {
        assertEquals("A-1 B_2", upper("a-1 b_2"));
    }

    /** InputFilter hands over a window into a longer sequence, not the whole
     *  thing; only that window may be raised or the field duplicates text. */
    @Test public void raisesOnlyTheRequestedRange() {
        assertEquals("ORK", CapsText.upperOrNull("work", 1, 4));
        assertNull(CapsText.upperOrNull("wORK", 1, 4));
    }

    @Test public void anEmptyRangeIsNoChange() {
        assertNull(CapsText.upperOrNull("work", 2, 2));
    }

    /**
     * Category names are compared and stored as plain strings, so the mapping
     * has to be the same everywhere. Under a Turkish default locale
     * {@code String.toUpperCase()} turns "i" into a dotted "İ", which would
     * make a category typed on a Turkish phone unmatchable by the same word
     * typed anywhere else.
     */
    @Test public void doesNotFollowTheDefaultLocale() {
        Locale.setDefault(new Locale("tr", "TR"));
        assertEquals("MINI", upper("mini"));
    }

    /** The field takes anything the keyboard offers, including text that has
     *  no uppercase form at all. */
    @Test public void passesThroughScriptsWithoutCase() {
        assertNull(upper("日本語"));
    }
}
