package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class DateFormatterTest {

    // Wednesday, 5 March 2025. dayOfWeek0 follows Calendar.DAY_OF_WEEK - 1
    // (0 = Sunday), matching the prototype's Date#getDay().
    private static final int Y = 2025, M0 = 2, D = 5, DOW0 = 3;

    private String fmt(String pattern) {
        return DateFormatter.format(pattern, Y, M0, D, DOW0);
    }

    @Test public void presetsMatchTheirTokenStrings() {
        assertEquals("DD MMM YYYY", DateFormatter.PRESETS[0]);
        assertEquals("MMM DD",      DateFormatter.PRESETS[1]);
        assertEquals("DDD DD/MM",   DateFormatter.PRESETS[2]);
        assertEquals("YYYY-MM-DD",  DateFormatter.PRESETS[3]);
        assertEquals("DOY / WK",    DateFormatter.PRESETS[4]);
    }

    @Test public void everyPresetFormatsTheKnownDate() {
        assertEquals("05 MAR 2025", fmt(DateFormatter.PRESETS[0]));
        assertEquals("MAR 05",      fmt(DateFormatter.PRESETS[1]));
        assertEquals("WED 05/03",   fmt(DateFormatter.PRESETS[2]));
        assertEquals("2025-03-05",  fmt(DateFormatter.PRESETS[3]));
        assertEquals("DAY 64 / WK 10", fmt(DateFormatter.PRESETS[4]));
    }

    @Test public void everyTokenFormatsIndividually() {
        assertEquals("05",       fmt("DD"));
        assertEquals("5",        fmt("D"));
        assertEquals("MAR",      fmt("MMM"));
        assertEquals("MARCH",    fmt("MMMM"));
        assertEquals("03",       fmt("MM"));
        assertEquals("2025",     fmt("YYYY"));
        assertEquals("25",       fmt("YY"));
        assertEquals("WED",      fmt("DDD"));
        assertEquals("WEDNESDAY",fmt("DDDD"));
        assertEquals("DAY 64",   fmt("DOY"));
        assertEquals("WK 10",    fmt("WK"));
    }

    @Test public void longestTokenWinsFirst() {
        // DD must not be matched inside DDD/DDDD, nor D inside DD.
        assertEquals("WEDNESDAY 05", fmt("DDDD DD"));
        assertEquals("WED 05",       fmt("DDD DD"));
        assertEquals("MARCH 05",     fmt("MMMM DD"));
        assertEquals("MAR 05",       fmt("MMM DD"));
    }

    @Test public void literalsPassThroughUnchanged() {
        assertEquals("2025/03/05, hi", fmt("YYYY/MM/DD, hi"));
        assertEquals("day off", fmt("day off"));
    }

    @Test public void dayOfYearAtBothEndsOfACommonYear() {
        assertEquals("DAY 1",   DateFormatter.format("DOY", 2025, 0, 1, 3));
        assertEquals("DAY 365", DateFormatter.format("DOY", 2025, 11, 31, 3));
    }

    @Test public void dayOfYearAtBothEndsOfALeapYear() {
        assertEquals("DAY 1",   DateFormatter.format("DOY", 2024, 0, 1, 1));
        assertEquals("DAY 366", DateFormatter.format("DOY", 2024, 11, 31, 2));
    }

    @Test public void weekNumberAtYearBoundaries() {
        assertEquals("WK 1",  DateFormatter.format("WK", 2025, 0, 1, 3));
        assertEquals("WK 53", DateFormatter.format("WK", 2025, 11, 31, 3));
    }

    @Test public void customPatternMixesTokensAndLiterals() {
        assertEquals("WED, 05 MAR - 2025 (WK 10)",
                fmt("DDD, DD MMM - YYYY (WK)"));
    }

    @Test public void emptyPatternReturnsEmptyString() {
        assertEquals("", fmt(""));
    }
}
