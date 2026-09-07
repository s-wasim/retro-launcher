package com.retro.launcher.core;

/**
 * The string a stored app reference is written as (V9 §11).
 *
 * <p>The dock list and the category map are both keyed on these, and every
 * device that has ever run this launcher already holds a set of them in
 * {@code Prefs}. So the primary profile's form is frozen exactly as it was —
 * {@code pkg/activity} — and only an app that lives in a <em>secondary user
 * profile</em> (an OEM clone: Samsung Dual Messenger, Xiaomi Dual Apps,
 * OnePlus Parallel Apps) takes the {@code @serial} suffix. That asymmetry is
 * the whole point: it is what lets clones become addressable without
 * rewriting or invalidating a single dock slot or category assignment that
 * already exists.
 *
 * <p>The serial is {@code UserManager#getSerialNumberForUser}, which is
 * stable across reboots — unlike the {@code UserHandle} identifier, which is
 * not safe to persist.
 *
 * <p>{@code @} is unambiguous as a separator: neither a package name nor an
 * activity name may contain one, both being dotted Java identifier paths.
 */
public final class ComponentKey {

    /** The serial of a key with no suffix: the launcher's own profile. */
    public static final long PRIMARY = -1L;

    private ComponentKey() {}

    /**
     * @param serial {@link #PRIMARY} for the calling user's own profile,
     *               which writes the historical two-part form
     */
    public static String format(String pkg, String activity, long serial) {
        String base = pkg + "/" + activity;
        return serial == PRIMARY ? base : base + "@" + serial;
    }

    /** Everything before the first {@code /}; the whole key if there is none. */
    public static String packageOf(String key) {
        if (key == null) return "";
        int slash = key.indexOf('/');
        return slash < 0 ? key : key.substring(0, slash);
    }

    /** Between the first {@code /} and the {@code @} suffix, if any. */
    public static String activityOf(String key) {
        if (key == null) return "";
        int slash = key.indexOf('/');
        if (slash < 0) return "";
        int at = key.lastIndexOf('@');
        return at > slash ? key.substring(slash + 1, at) : key.substring(slash + 1);
    }

    /**
     * The profile serial, or {@link #PRIMARY} when the key carries no
     * suffix — which is also what a corrupt suffix reads as, since resolving
     * a damaged key in the user's own profile launches the app they most
     * likely meant rather than nothing at all.
     */
    public static long serialOf(String key) {
        if (key == null) return PRIMARY;
        int slash = key.indexOf('/');
        int at = key.lastIndexOf('@');
        if (slash < 0 || at <= slash) return PRIMARY;
        try {
            return Long.parseLong(key.substring(at + 1));
        } catch (NumberFormatException e) {
            return PRIMARY;
        }
    }

    /** Whether the key names an app in a profile other than our own. */
    public static boolean isClone(String key) {
        return serialOf(key) != PRIMARY;
    }
}
