package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * V9 §9. The old rule scanned forward for the first A-Z, so any label that
 * merely *contained* a letter was filed under it — "1Password" landed under
 * P, several rows away from where anyone would look for it. The section a row
 * belongs to is decided by character zero and nothing else.
 */
public class DrawerSectionsTest {

    /** The bug, stated directly: a leading digit is a '#' row, not a 'P' row. */
    @Test public void aLeadingDigitFilesUnderHash() {
        assertEquals('#', DrawerSections.sectionFor("1Password"));
    }

    @Test public void aLeadingDigitFilesUnderHashEvenWithPunctuation() {
        assertEquals('#', DrawerSections.sectionFor("7-Zip"));
    }

    @Test public void anOrdinaryLabelFilesUnderItsOwnLetter() {
        assertEquals('Z', DrawerSections.sectionFor("Zoom"));
    }

    @Test public void lowercaseIsFoldedUp() {
        assertEquals('S', DrawerSections.sectionFor("signal"));
    }

    @Test public void leadingPunctuationFilesUnderHash() {
        assertEquals('#', DrawerSections.sectionFor("…"));
    }

    /** Non-Latin scripts have no A-Z row to sit in, and inventing one by
     *  transliterating would be worse than one honest '#' group. */
    @Test public void nonLatinScriptsFileUnderHash() {
        assertEquals('#', DrawerSections.sectionFor("微信"));
    }

    @Test public void emptyLabelFilesUnderHash() {
        assertEquals('#', DrawerSections.sectionFor(""));
    }

    /** load() builds labels from PackageManager, which can hand back null for
     *  a broken package rather than throw. */
    @Test public void nullLabelFilesUnderHash() {
        assertEquals('#', DrawerSections.sectionFor(null));
    }

    /**
     * Character zero and nothing else — not "the first letter anywhere in the
     * string, skipping what precedes it". The drawer sorts by the lowercased
     * label, so a rule that looks past leading characters would file a row
     * under a header it does not sort next to: "  Notes" sorts above every
     * digit, and giving it an 'N' would strand an N header at the top of the
     * list, far from the other N rows.
     */
    @Test public void onlyCharacterZeroDecidesTheSection() {
        assertEquals('#', DrawerSections.sectionFor("  Notes"));
        assertEquals('#', DrawerSections.sectionFor("   "));
    }
}
