package com.retro.launcher;

/** One launchable app: display label plus the component to start it. */
final class AppEntry {

    final String label;
    final String packageName;
    final String activityName;

    AppEntry(String label, String packageName, String activityName) {
        this.label = label;
        this.packageName = packageName;
        this.activityName = activityName;
    }
}
