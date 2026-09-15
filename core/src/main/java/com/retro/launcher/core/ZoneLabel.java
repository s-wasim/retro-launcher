package com.retro.launcher.core;

/**
 * The short name the clock shows for a second time zone, derived from its
 * IANA id.
 *
 * <p>Deliberately not {@code TimeZone.getDisplayName()}. That returns either
 * a localised long name ("Pakistan Standard Time") — too wide for a clock line
 * at any font size the design uses — or an abbreviation the JDK will happily
 * render as "GMT+05:00" when it has no short form, which tells the user
 * nothing about where it is. The id's own last segment is the city, it is
 * already unique, it is ASCII, and it needs no locale data: {@code Asia/Karachi}
 * reads as KARACHI.
 *
 * <p>Pure, and no Android types, so the tokenising is testable — an
 * underscore left in a label, or a three-segment id like
 * {@code America/Argentina/Buenos_Aires} losing its city, are exactly the
 * mistakes that only show up on the one zone nobody tested.
 */
public final class ZoneLabel {

    private ZoneLabel() {}

    /** Above this, a label would crowd the time next to it. Chosen against
     *  the longest real city segment, {@code Port-au-Prince} at 14. */
    public static final int MAX_LENGTH = 14;

    /**
     * The display label for an IANA zone id.
     *
     * <p>The last {@code /}-separated segment, underscores turned to spaces,
     * upper-cased. A region-only id ({@code UTC}, {@code GMT}) has no segment
     * to drop and is returned as it stands. Anything longer than
     * {@link #MAX_LENGTH} is truncated on a word boundary where there is one.
     *
     * @param zoneId an IANA id such as {@code Europe/London}; null or blank
     *               yields an empty label rather than an exception, because
     *               the caller's alternative is a crash on a corrupt pref
     */
    public static String of(String zoneId) {
        if (zoneId == null) return "";
        String id = zoneId.trim();
        if (id.isEmpty()) return "";

        int lastSlash = id.lastIndexOf('/');
        String segment = lastSlash < 0 ? id : id.substring(lastSlash + 1);
        if (segment.isEmpty()) return "";

        String label = segment.replace('_', ' ').toUpperCase(java.util.Locale.ROOT);
        return label.length() <= MAX_LENGTH ? label : truncate(label);
    }

    /**
     * The region an id sits in — {@code Asia} for {@code Asia/Karachi} — used
     * to group the picker's list. A region-only id reports an empty region
     * rather than naming itself, so it sorts into the picker's catch-all
     * instead of inventing a one-entry group.
     */
    public static String regionOf(String zoneId) {
        if (zoneId == null) return "";
        int slash = zoneId.indexOf('/');
        return slash <= 0 ? "" : zoneId.substring(0, slash);
    }

    /**
     * The offset from UTC as the clock writes it: {@code +5:30}, {@code -8},
     * {@code +0}. Minutes appear only when there are any, because most zones
     * are whole hours and "+5:00" is noise on a line this small.
     *
     * @param offsetMillis a raw offset as {@code TimeZone.getOffset} returns
     *                     it — already including DST if the caller asked for
     *                     an instant
     */
    public static String offsetLabel(int offsetMillis) {
        int totalMinutes = offsetMillis / 60_000;
        char sign = totalMinutes < 0 ? '-' : '+';
        int abs = Math.abs(totalMinutes);
        int hours = abs / 60;
        int minutes = abs % 60;
        if (minutes == 0) return sign + Integer.toString(hours);
        return sign + Integer.toString(hours) + ':' + (minutes < 10 ? "0" : "") + minutes;
    }

    /**
     * The marker for a zone whose calendar date differs from the local one:
     * {@code "+1"} for tomorrow there, {@code "-1"} for yesterday, empty for
     * the same day.
     *
     * <p>Compared as a day delta rather than by date arithmetic because that
     * is all the clock shows, and because the two dates can straddle a month
     * or year boundary where naive subtraction gives nonsense.
     *
     * @param dayDelta the other zone's day minus the local day, clamped by the
     *                 caller to the -1..1 a time zone can actually produce
     */
    public static String dayMarker(int dayDelta) {
        if (dayDelta > 0) return "+1";
        if (dayDelta < 0) return "-1";
        return "";
    }

    /** Cuts at the last space inside the limit, so "PORT AU PRINCE" loses a
     *  whole word rather than becoming "PORT AU PRIN". Falls back to a hard
     *  cut for a single word with no break in it. */
    private static String truncate(String label) {
        String head = label.substring(0, MAX_LENGTH);
        int space = head.lastIndexOf(' ');
        return space > 0 ? head.substring(0, space) : head;
    }
}
