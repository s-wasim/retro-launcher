package com.retro.launcher.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

/**
 * 2.2.1. The saved zone list and the label derived from an id.
 *
 * <p>{@code java.util.TimeZone} is JDK rather than Android, so the real zone
 * table is available here — which is worth using: the labelling only breaks on
 * the handful of ids shaped unlike the rest, and those are findable by walking
 * the table rather than by guessing which ones they are.
 */
public class ZoneListTest {

    // ---- the stored form -------------------------------------------------

    @Test public void anEmptyStoreIsAnEmptyList() {
        assertTrue(ZoneList.parse(null).isEmpty());
        assertTrue(ZoneList.parse("").isEmpty());
    }

    @Test public void theListRoundTrips() {
        List<String> zones = Arrays.asList("Asia/Karachi", "Europe/London", "America/New_York");
        assertEquals(zones, ZoneList.parse(ZoneList.format(zones)));
    }

    @Test public void blankLinesAreDropped() {
        assertEquals(Arrays.asList("Asia/Karachi", "Europe/London"),
                ZoneList.parse("Asia/Karachi\n\n  \nEurope/London\n"));
    }

    @Test public void duplicatesCollapseToTheFirstAppearance() {
        assertEquals(Arrays.asList("Asia/Karachi", "Europe/London"),
                ZoneList.parse("Asia/Karachi\nEurope/London\nAsia/Karachi"));
    }

    @Test public void theStoredListIsCappedOnTheWayInAndOut() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < ZoneList.MAX + 4; i++) tooMany.add("Etc/GMT+" + i);
        assertEquals(ZoneList.MAX, ZoneList.parse(ZoneList.format(tooMany)).size());

        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < ZoneList.MAX + 4; i++) raw.append("Etc/GMT+").append(i).append('\n');
        assertEquals(ZoneList.MAX, ZoneList.parse(raw.toString()).size());
    }

    @Test public void formatSkipsNullsRatherThanWritingTheWordNull() {
        assertEquals("Asia/Karachi",
                ZoneList.format(Arrays.asList(null, "Asia/Karachi", null)));
    }

    // ---- add and remove --------------------------------------------------

    @Test public void aNewZoneGoesToTheFront() {
        List<String> zones = ZoneList.add(Arrays.asList("Europe/London"), "Asia/Karachi");
        assertEquals(Arrays.asList("Asia/Karachi", "Europe/London"), zones);
    }

    @Test public void reAddingMovesToTheFrontRatherThanDuplicating() {
        List<String> zones = ZoneList.add(
                Arrays.asList("Europe/London", "Asia/Karachi"), "Asia/Karachi");
        assertEquals(Arrays.asList("Asia/Karachi", "Europe/London"), zones);
    }

    @Test public void addingAtTheCapDropsTheOldest() {
        List<String> full = new ArrayList<>();
        for (int i = 0; i < ZoneList.MAX; i++) full.add("Etc/GMT+" + i);
        List<String> after = ZoneList.add(full, "Asia/Karachi");
        assertEquals(ZoneList.MAX, after.size());
        assertEquals("Asia/Karachi", after.get(0));
        assertFalse("the oldest should have been dropped",
                after.contains("Etc/GMT+" + (ZoneList.MAX - 1)));
    }

    @Test public void addingNothingLeavesTheListAlone() {
        List<String> zones = Arrays.asList("Europe/London");
        assertEquals(zones, ZoneList.add(zones, null));
        assertEquals(zones, ZoneList.add(zones, "   "));
    }

    @Test public void removeTakesOutExactlyTheOneNamed() {
        assertEquals(Arrays.asList("Europe/London"),
                ZoneList.remove(Arrays.asList("Asia/Karachi", "Europe/London"), "Asia/Karachi"));
    }

    @Test public void removingSomethingAbsentIsANoOp() {
        List<String> zones = Arrays.asList("Europe/London");
        assertEquals(zones, ZoneList.remove(zones, "Asia/Karachi"));
    }

    // ---- cycling ---------------------------------------------------------

    @Test public void tappingWalksTheListAndWraps() {
        assertEquals(1, ZoneList.next(0, 3));
        assertEquals(2, ZoneList.next(1, 3));
        assertEquals(0, ZoneList.next(2, 3));
    }

    @Test public void oneSavedZoneCyclesToItself() {
        assertEquals(0, ZoneList.next(0, 1));
    }

    @Test public void anEmptyListCannotBeCycledOffTheEnd() {
        assertEquals(0, ZoneList.next(0, 0));
        assertEquals(0, ZoneList.next(5, 0));
    }

    @Test public void anIndexLeftOverFromALongerListResolvesIntoRange() {
        // Removing a zone leaves the stored index pointing past the end.
        assertEquals(0, ZoneList.clamp(4, 2));
        assertEquals(1, ZoneList.clamp(3, 2));
        assertTrue(ZoneList.next(9, 2) < 2);
    }

    @Test public void aCorruptNegativeIndexResolvesToTheFirstZone() {
        assertEquals(0, ZoneList.clamp(-3, 4));
        assertEquals(1, ZoneList.next(-3, 4));
    }

    @Test public void indexOfAnAbsentZoneIsWhereAddPutsIt() {
        List<String> zones = Arrays.asList("Europe/London");
        assertEquals(0, ZoneList.indexOf(zones, "Asia/Karachi"));
        assertEquals(0, ZoneList.indexOf(ZoneList.add(zones, "Asia/Karachi"), "Asia/Karachi"));
    }

    // ---- labels ----------------------------------------------------------

    @Test public void theCityIsWhatShows() {
        assertEquals("KARACHI", ZoneLabel.of("Asia/Karachi"));
        assertEquals("LONDON", ZoneLabel.of("Europe/London"));
    }

    @Test public void underscoresBecomeSpaces() {
        assertEquals("NEW YORK", ZoneLabel.of("America/New_York"));
        assertEquals("LOS ANGELES", ZoneLabel.of("America/Los_Angeles"));
    }

    @Test public void aThreeSegmentIdKeepsItsCityNotItsMiddle() {
        assertEquals("BUENOS AIRES", ZoneLabel.of("America/Argentina/Buenos_Aires"));
        assertEquals("NORTH DAKOTA", ZoneLabel.of("America/North_Dakota"));
    }

    @Test public void aRegionlessIdIsItsOwnLabel() {
        assertEquals("UTC", ZoneLabel.of("UTC"));
        assertEquals("GMT", ZoneLabel.of("GMT"));
    }

    @Test public void aBadIdYieldsAnEmptyLabelRatherThanAnException() {
        assertEquals("", ZoneLabel.of(null));
        assertEquals("", ZoneLabel.of(""));
        assertEquals("", ZoneLabel.of("   "));
        assertEquals("", ZoneLabel.of("Asia/"));
    }

    @Test public void theRegionIsTheFirstSegment() {
        assertEquals("Asia", ZoneLabel.regionOf("Asia/Karachi"));
        assertEquals("America", ZoneLabel.regionOf("America/Argentina/Buenos_Aires"));
        assertEquals("", ZoneLabel.regionOf("UTC"));
        assertEquals("", ZoneLabel.regionOf(null));
    }

    @Test public void everyZoneThePlatformKnowsProducesAUsableLabel() {
        // The labelling only breaks on ids shaped unlike the rest, and the
        // zone table is the only honest way to find those.
        for (String id : TimeZone.getAvailableIDs()) {
            String label = ZoneLabel.of(id);
            assertFalse(id + " produced no label", label.isEmpty());
            assertFalse(id + " kept an underscore", label.contains("_"));
            assertFalse(id + " kept a slash", label.contains("/"));
            assertTrue(id + " produced an over-long label: " + label,
                    label.length() <= ZoneLabel.MAX_LENGTH);
            assertEquals(id + " was not upper-cased", label.toUpperCase(java.util.Locale.ROOT), label);
        }
    }

    // ---- the offset label ------------------------------------------------

    @Test public void awholeHourOffsetShowsNoMinutes() {
        assertEquals("+5", ZoneLabel.offsetLabel(5 * 3_600_000));
        assertEquals("-8", ZoneLabel.offsetLabel(-8 * 3_600_000));
        assertEquals("+0", ZoneLabel.offsetLabel(0));
    }

    @Test public void aHalfHourOffsetShowsItsMinutes() {
        assertEquals("+5:30", ZoneLabel.offsetLabel(5 * 3_600_000 + 30 * 60_000));
        assertEquals("+5:45", ZoneLabel.offsetLabel(5 * 3_600_000 + 45 * 60_000));
    }

    @Test public void aNegativeHalfHourOffsetKeepsItsSignAndItsMinutes() {
        // Newfoundland is -3:30; the sign must not be lost in the division.
        assertEquals("-3:30", ZoneLabel.offsetLabel(-(3 * 3_600_000 + 30 * 60_000)));
    }

    @Test public void everyRealZoneOffsetFormatsWithoutSurprises() {
        long instant = 1_800_000_000_000L;
        for (String id : TimeZone.getAvailableIDs()) {
            String label = ZoneLabel.offsetLabel(TimeZone.getTimeZone(id).getOffset(instant));
            assertTrue(id + " -> " + label, label.matches("[+-]\\d{1,2}(:\\d{2})?"));
        }
    }
}
