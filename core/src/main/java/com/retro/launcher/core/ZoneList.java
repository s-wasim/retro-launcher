package com.retro.launcher.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The saved second-time-zone list: how it is stored, and how tapping the
 * second clock line moves through it.
 *
 * <p>Stored the way {@code Prefs} already stores the dock — one id per line in
 * a single string — so this adds a key and no new storage shape. The parsing
 * lives here rather than in {@code Prefs} because it has real rules (a cap, no
 * duplicates, no blanks) and those rules are where a stored list quietly
 * becomes a list the clock cannot cycle.
 *
 * <p>Pure: {@code java.util.TimeZone} is JDK, not Android, so everything here
 * runs under a bare-JDK unit test.
 */
public final class ZoneList {

    private ZoneList() {}

    /**
     * How many zones may be saved. The clock shows two lines and cycles the
     * second through this list one tap at a time, so a long list stops being
     * navigable — six taps is already the far side of what anyone will do to
     * get back to where they started.
     */
    public static final int MAX = 6;

    private static final char SEPARATOR = '\n';

    /**
     * Parses the stored form.
     *
     * <p>Blank lines are dropped, duplicates collapse to their first
     * appearance, and anything past {@link #MAX} is discarded. A null or empty
     * string is an empty list. Ids are <em>not</em> validated against the
     * platform's zone table here — that needs {@code TimeZone} and belongs at
     * the point of use, where an id the device no longer knows should be
     * skipped rather than take the whole list down.
     */
    public static List<String> parse(String stored) {
        List<String> out = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return out;
        for (String line : stored.split("\n")) {
            String id = line.trim();
            if (id.isEmpty() || out.contains(id)) continue;
            out.add(id);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    /** The stored form. Applies the same rules as {@link #parse}, so a list
     *  that goes through here comes back unchanged. */
    public static String format(List<String> zones) {
        if (zones == null || zones.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        List<String> seen = new ArrayList<>();
        for (String zone : zones) {
            if (zone == null) continue;
            String id = zone.trim();
            if (id.isEmpty() || seen.contains(id)) continue;
            if (b.length() > 0) b.append(SEPARATOR);
            b.append(id);
            seen.add(id);
            if (seen.size() >= MAX) break;
        }
        return b.toString();
    }

    /**
     * Adds {@code zoneId}, or moves it to the front if it is already there.
     *
     * <p>Front rather than back because the index the clock is showing is
     * stored separately and a newly chosen zone should be the one that
     * appears — see {@link #indexOf}. Dropping the oldest at the cap is the
     * only thing that can remove an entry the user did not remove themselves.
     */
    public static List<String> add(List<String> zones, String zoneId) {
        List<String> out = new ArrayList<>();
        if (zoneId == null || zoneId.trim().isEmpty()) {
            return zones == null ? out : new ArrayList<>(zones);
        }
        String id = zoneId.trim();
        out.add(id);
        if (zones != null) {
            for (String existing : zones) {
                if (existing == null || existing.equals(id)) continue;
                out.add(existing);
                if (out.size() >= MAX) break;
            }
        }
        return out;
    }

    /** Removes {@code zoneId} if present. */
    public static List<String> remove(List<String> zones, String zoneId) {
        List<String> out = new ArrayList<>();
        if (zones == null) return out;
        for (String existing : zones) {
            if (existing != null && !existing.equals(zoneId)) out.add(existing);
        }
        return out;
    }

    /**
     * Where {@code zoneId} sits, or 0 when it is absent — which is where
     * {@link #add} just put it.
     */
    public static int indexOf(List<String> zones, String zoneId) {
        if (zones == null || zoneId == null) return 0;
        int i = zones.indexOf(zoneId);
        return i < 0 ? 0 : i;
    }

    /**
     * The index one tap past {@code index}, wrapping.
     *
     * <p>Takes any stored index rather than trusting it: the list can shrink
     * under a saved index when a zone is removed, and a negative one can only
     * come from a corrupt pref. Both resolve into range instead of throwing.
     * An empty list stays at 0, which the clock reads as "nothing to show".
     */
    public static int next(int index, int size) {
        if (size <= 0) return 0;
        int from = index < 0 ? 0 : index % size;
        return (from + 1) % size;
    }

    /** {@code index} brought into range for a list of {@code size}. */
    public static int clamp(int index, int size) {
        if (size <= 0) return 0;
        return index < 0 ? 0 : index % size;
    }
}
