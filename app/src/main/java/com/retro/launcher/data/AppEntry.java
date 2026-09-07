package com.retro.launcher.data;

import android.os.UserHandle;

import com.retro.launcher.core.ComponentKey;
import com.retro.launcher.core.DrawerSections;

import java.util.List;

/**
 * One row of the app drawer. {@code component} is {@code pkg/activity}, the
 * same string {@link Prefs}'s dock list and {@code DockView} already use — or
 * {@code pkg/activity@serial} for a clone, see {@link ComponentKey}.
 *
 * <p>V9 §11 added {@link #user}. An OEM clone lives in a secondary user
 * profile, and a plain {@code startActivity} cannot cross a profile boundary:
 * it fails silently for exactly the apps that section adds. So the handle
 * travels with the row and every launch path routes on it.
 */
public final class AppEntry {

    public final String label;
    public final String packageName;
    public final String activityName;
    public final List<String> categories;   // tab names this app belongs to; empty = UNSORTED
    public final boolean diagnostic;         // true only for the "queries block missing" row

    /** {@code ApplicationInfo.FLAG_SYSTEM}: preinstalled, cannot be uninstalled. */
    public final boolean systemApp;
    /** {@code ApplicationInfo.FLAG_UPDATED_SYSTEM_APP}: preinstalled, since updated. */
    public final boolean updatedSystemApp;

    /**
     * The profile this activity lives in. Null for the launcher's own
     * profile — and for entries built from a stored component string, which
     * resolve their profile from the key's serial at launch time instead.
     */
    public final UserHandle user;

    /**
     * {@code UserManager#getSerialNumberForUser(user)}, or
     * {@link ComponentKey#PRIMARY} for our own profile. The serial rather
     * than the handle because this ends up inside a persisted key and a
     * {@code UserHandle}'s identifier is not stable across reboots.
     */
    public final long userSerial;

    public AppEntry(String label, String packageName, String activityName,
                     List<String> categories, boolean diagnostic) {
        this(label, packageName, activityName, categories, diagnostic, false, false);
    }

    /**
     * The two flags decide which removal rows the drawer's quick-action box
     * offers — see {@code AppActionPolicy}. Entries built from a stored
     * component string rather than from PackageManager (the dock) use the
     * short constructor above and report neither flag; they never open that
     * box.
     */
    public AppEntry(String label, String packageName, String activityName,
                     List<String> categories, boolean diagnostic,
                     boolean systemApp, boolean updatedSystemApp) {
        this(label, packageName, activityName, categories, diagnostic,
                systemApp, updatedSystemApp, null, ComponentKey.PRIMARY);
    }

    /** The full form: everything above plus the profile the activity lives
     *  in (V9 §11). */
    public AppEntry(String label, String packageName, String activityName,
                     List<String> categories, boolean diagnostic,
                     boolean systemApp, boolean updatedSystemApp,
                     UserHandle user, long userSerial) {
        this.label = label;
        this.packageName = packageName;
        this.activityName = activityName;
        this.categories = categories;
        this.diagnostic = diagnostic;
        this.systemApp = systemApp;
        this.updatedSystemApp = updatedSystemApp;
        this.user = user;
        this.userSerial = userSerial;
    }

    /** The stored key for this row — unchanged for the primary profile, so
     *  every dock slot and category assignment already on the device keeps
     *  matching. */
    public String component() {
        return ComponentKey.format(packageName, activityName, userSerial);
    }

    /** Whether this row is an OEM clone rather than the app itself. Decides
     *  the drawer's badge and, more importantly, whether a launch has to
     *  cross a profile boundary. */
    public boolean isClone() {
        return userSerial != ComponentKey.PRIMARY;
    }

    /** The drawer section header this row sits under — see
     *  {@link DrawerSections#sectionFor(String)}, which is where the rule
     *  lives so it can be unit-tested. */
    public char firstLetter() {
        return DrawerSections.sectionFor(label);
    }
}
