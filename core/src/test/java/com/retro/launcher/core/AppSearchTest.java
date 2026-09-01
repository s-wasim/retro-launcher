package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppSearchTest {

    private static boolean matches(String label, String query) {
        return AppSearch.score(label, query) >= 0;
    }

    // ---- what counts as a match -----------------------------------------

    @Test public void aPrefixOfTheLabelMatches() {
        assertTrue(matches("Calculator", "cal"));
    }

    @Test public void matchingIgnoresCase() {
        assertTrue(matches("Calculator", "CAL"));
        assertTrue(matches("CALCULATOR", "cal"));
    }

    @Test public void aPrefixOfAnyWordMatches() {
        assertTrue(matches("Google Calendar", "cal"));
    }

    @Test public void aRunAnywhereInTheLabelMatches() {
        assertTrue(matches("Calculator", "lcul"));
    }

    @Test public void anUnrelatedQueryDoesNotMatch() {
        assertFalse(matches("Calculator", "zzz"));
    }

    @Test public void anEmptyQueryMatchesNothing() {
        // The overlay shows results only once you have typed something.
        assertFalse(matches("Calculator", ""));
        assertFalse(matches("Calculator", "   "));
    }

    @Test public void surroundingWhitespaceInTheQueryIsIgnored() {
        assertTrue(matches("Calculator", "  cal  "));
    }

    @Test public void aQueryLongerThanTheLabelDoesNotMatch() {
        assertFalse(matches("Cal", "calculator"));
    }

    // ---- ranking ---------------------------------------------------------

    @Test public void anExactLabelOutranksAMerePrefix() {
        assertTrue(AppSearch.score("Cal", "cal") > AppSearch.score("Calculator", "cal"));
    }

    @Test public void aLabelPrefixOutranksAWordPrefix() {
        assertTrue(AppSearch.score("Calculator", "cal")
                > AppSearch.score("Google Calendar", "cal"));
    }

    @Test public void aWordPrefixOutranksARunMidWord() {
        assertTrue(AppSearch.score("Google Calendar", "cal")
                > AppSearch.score("Vocal Remover", "cal"));
    }

    @Test public void theShorterOfTwoEquallyGoodLabelsWins() {
        assertTrue(AppSearch.score("Clock", "cl") > AppSearch.score("Clock Widget Pro", "cl"));
    }

    @Test public void aNonMatchScoresBelowEveryMatch() {
        assertTrue(AppSearch.score("Calculator", "zzz") < AppSearch.score("Vocal Remover", "cal"));
    }

    // ---- degenerate input -------------------------------------------------

    @Test public void nullInputsDoNotMatchAndDoNotThrow() {
        assertFalse(matches(null, "cal"));
        assertFalse(matches("Calculator", null));
        assertFalse(matches(null, null));
    }

    @Test public void punctuationInTheLabelStartsAWordToo() {
        assertTrue(matches("Files-by-Google", "goo"));
    }
}
