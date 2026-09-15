package com.retro.launcher.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 2.2.1. These rules were inside {@code ClockWidget} and therefore untestable;
 * the second time zone gave them a second caller, which is the point at which
 * an off-by-one in the 12-hour wrap stops being one wrong clock and becomes
 * two.
 */
public class ClockTextTest {

    // ---- 12-hour ---------------------------------------------------------

    @Test public void midnightIsTwelveAmNotZero() {
        assertEquals("12:00 AM", ClockText.time(0, 0, true));
    }

    @Test public void noonIsTwelvePmNotZero() {
        assertEquals("12:00 PM", ClockText.time(12, 0, true));
    }

    @Test public void oneMinuteBeforeNoonIsStillAm() {
        assertEquals("11:59 AM", ClockText.time(11, 59, true));
    }

    @Test public void oneMinuteAfterNoonIsPm() {
        assertEquals("12:01 PM", ClockText.time(12, 1, true));
    }

    @Test public void lateEveningWrapsToTheTwelveHourFace() {
        assertEquals("11:59 PM", ClockText.time(23, 59, true));
        assertEquals("1:00 PM", ClockText.time(13, 0, true));
    }

    @Test public void theTwelveHourFaceHasNoLeadingZero() {
        assertEquals("9:05 AM", ClockText.time(9, 5, true));
    }

    // ---- 24-hour ---------------------------------------------------------

    @Test public void theTwentyFourHourFaceIsZeroPadded() {
        assertEquals("09:05", ClockText.time(9, 5, false));
        assertEquals("00:00", ClockText.time(0, 0, false));
    }

    @Test public void theTwentyFourHourFaceCarriesNoMeridiem() {
        assertFalse(ClockText.time(13, 0, false).contains("PM"));
        assertEquals("13:00", ClockText.time(13, 0, false));
    }

    // ---- both ------------------------------------------------------------

    @Test public void minutesAreAlwaysPadded() {
        assertEquals("1:00 AM", ClockText.time(1, 0, true));
        assertEquals("01:00", ClockText.time(1, 0, false));
    }

    @Test public void everyHourOfTheDayRendersOnBothFaces() {
        for (int h = 0; h < 24; h++) {
            String twelve = ClockText.time(h, 30, true);
            String twentyFour = ClockText.time(h, 30, false);
            assertTrue(h + " 12h: " + twelve, twelve.matches("\\d{1,2}:30 [AP]M"));
            assertTrue(h + " 24h: " + twentyFour, twentyFour.matches("\\d{2}:30"));
            // The 12-hour face never shows 0 and never shows 13+.
            int shown = Integer.parseInt(twelve.substring(0, twelve.indexOf(':')));
            assertTrue("12-hour face showed " + shown, shown >= 1 && shown <= 12);
        }
    }

    @Test public void outOfRangeFieldsWrapRatherThanThrow() {
        // The caller is a Calendar field; a throw here takes the home screen
        // down, which is never the better failure.
        assertEquals("00:00", ClockText.time(24, 0, false));
        assertEquals("23:00", ClockText.time(-1, 0, false));
        assertEquals("01:00", ClockText.time(1, 60, false));
    }

    // ---- the day marker --------------------------------------------------

    @Test public void theSameDayHasNoDelta() {
        assertEquals(0, ClockText.dayDelta(2026, 258, 2026, 258));
    }

    @Test public void aheadByADayReadsAsPlusOne() {
        assertEquals(1, ClockText.dayDelta(2026, 258, 2026, 259));
    }

    @Test public void behindByADayReadsAsMinusOne() {
        assertEquals(-1, ClockText.dayDelta(2026, 258, 2026, 257));
    }

    @Test public void newYearsEveAgainstNewYearsDayIsOneDayNotThreeHundred() {
        // The case plain day-number subtraction gets wrong: local is 31 Dec
        // (day 365), the other zone is already 1 Jan (day 1).
        assertEquals(1, ClockText.dayDelta(2026, 365, 2027, 1));
        assertEquals(-1, ClockText.dayDelta(2027, 1, 2026, 365));
    }

    @Test public void aNonsensicalGapStillCollapsesToASign() {
        // No zone pair is this far apart, so this only happens if the two
        // readings did not come from one instant — "+1" is a better failure
        // on a clock face than "+180".
        assertEquals(1, ClockText.dayDelta(2026, 1, 2026, 180));
        assertEquals(-1, ClockText.dayDelta(2026, 180, 2026, 1));
    }

    @Test public void theMarkerTextMatchesTheDelta() {
        assertEquals("+1", ZoneLabel.dayMarker(ClockText.dayDelta(2026, 365, 2027, 1)));
        assertEquals("-1", ZoneLabel.dayMarker(ClockText.dayDelta(2027, 1, 2026, 365)));
        assertEquals("", ZoneLabel.dayMarker(ClockText.dayDelta(2026, 258, 2026, 258)));
    }
}
