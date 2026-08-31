package com.retro.launcher.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which rows the drawer's long-press quick-action box offers for a given app.
 *
 * <p>This exists because the box used to offer one row labelled
 * "UNINSTALL / DISABLE", and a launcher can only ever do one of those two
 * things. It cannot disable anything —
 * {@code PackageManager#setApplicationEnabledSetting} is reserved for the
 * device owner and for an app acting on itself — and it cannot uninstall a
 * preinstalled app at all. So the row promised whichever outcome the user
 * happened to want, and on a preinstalled app it delivered neither.
 *
 * <p>What the launcher can honestly offer depends on two
 * {@code ApplicationInfo} flags, and that decision is the part worth testing
 * off-device, so it lives here rather than inside a view:
 *
 * <table>
 *   <tr><th>App kind</th><th>Removal rows</th></tr>
 *   <tr><td>User-installed</td><td>{@code UNINSTALL}</td></tr>
 *   <tr><td>Updated system app</td><td>{@code UNINSTALL_UPDATES}, {@code DISABLE}</td></tr>
 *   <tr><td>Preinstalled, not updated</td><td>{@code DISABLE}</td></tr>
 *   <tr><td>This launcher</td><td>none</td></tr>
 * </table>
 *
 * <p>{@link Action#LAUNCH} and {@link Action#APP_INFO} are offered for every
 * app including this one; the table governs the removal rows only.
 * {@link Action#DISABLE} is a deep link to the system App Info page, where the
 * platform's own Disable button lives — the launcher points at it rather than
 * pretending to be it.
 */
public final class AppActionPolicy {

    private AppActionPolicy() {}

    /** One row of the quick-action box, with the label it draws. */
    public enum Action {

        LAUNCH("LAUNCH"),

        /** Full removal. Only ever offered for a user-installed app. */
        UNINSTALL("UNINSTALL"),

        /** Rolls an updated system app back to its factory version. */
        UNINSTALL_UPDATES("UNINSTALL UPDATES"),

        /** Opens App Info, where the system's Disable button is. */
        DISABLE("DISABLE"),

        APP_INFO("MORE DETAILS");

        private final String label;

        Action(String label) { this.label = label; }

        public String label() { return label; }
    }

    /**
     * The rows to draw, in display order: {@code LAUNCH} first, the removal
     * rows next, {@code APP_INFO} last.
     *
     * @param system        the app carries {@code ApplicationInfo.FLAG_SYSTEM}
     * @param updatedSystem it carries {@code FLAG_UPDATED_SYSTEM_APP} — a
     *                      preinstalled app that has since taken an update
     * @param self          it is this launcher
     * @return a fresh mutable list; the caller may do as it likes with it
     */
    public static List<Action> actionsFor(boolean system, boolean updatedSystem, boolean self) {
        List<Action> out = new ArrayList<>(4);
        out.add(Action.LAUNCH);
        if (!self) {
            // The update flag decides first: an updated system app is still a
            // system app, so testing FLAG_SYSTEM ahead of it would offer only
            // DISABLE and hide the rollback the user actually wants.
            if (updatedSystem) {
                out.add(Action.UNINSTALL_UPDATES);
                out.add(Action.DISABLE);
            } else if (system) {
                out.add(Action.DISABLE);
            } else {
                out.add(Action.UNINSTALL);
            }
        }
        out.add(Action.APP_INFO);
        return out;
    }

    /** The labels for {@link #actionsFor}, in the same order. */
    public static List<String> labelsFor(boolean system, boolean updatedSystem, boolean self) {
        List<String> out = new ArrayList<>(4);
        for (Action a : actionsFor(system, updatedSystem, self)) out.add(a.label());
        return Collections.unmodifiableList(out);
    }
}
