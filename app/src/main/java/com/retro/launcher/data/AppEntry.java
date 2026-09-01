package com.retro.launcher.data;

import java.util.List;

/**
 * One row of the app drawer. {@code component} is {@code pkg/activity}, the
 * same string {@link Prefs}'s dock list and {@code DockView} already use.
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
        this.label = label;
        this.packageName = packageName;
        this.activityName = activityName;
        this.categories = categories;
        this.diagnostic = diagnostic;
        this.systemApp = systemApp;
        this.updatedSystemApp = updatedSystemApp;
    }

    public String component() {
        return packageName + "/" + activityName;
    }

    public char firstLetter() {
        for (int i = 0; i < label.length(); i++) {
            char c = Character.toUpperCase(label.charAt(i));
            if (c >= 'A' && c <= 'Z') return c;
        }
        return '#';
    }
}
