package com.retro.launcher.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class AppActionPolicyTest {

    private static List<AppActionPolicy.Action> user() {
        return AppActionPolicy.actionsFor(false, false, false);
    }

    private static List<AppActionPolicy.Action> preinstalled() {
        return AppActionPolicy.actionsFor(true, false, false);
    }

    private static List<AppActionPolicy.Action> updatedSystem() {
        return AppActionPolicy.actionsFor(true, true, false);
    }

    private static List<AppActionPolicy.Action> self() {
        return AppActionPolicy.actionsFor(false, false, true);
    }

    // ---- the removal table -------------------------------------------------

    /** A normally installed app is the only kind a launcher can actually
     *  remove, so it is the only kind that gets a bare UNINSTALL row. */
    @Test public void userInstalledAppsOfferUninstallOnly() {
        assertEquals(1, removals(user()).size());
        assertEquals(AppActionPolicy.Action.UNINSTALL, removals(user()).get(0));
    }

    /** A preinstalled app cannot be uninstalled at all. Offering the row and
     *  having nothing happen is the bug this whole class exists to end; the
     *  honest row is DISABLE, which deep-links to where the system's own
     *  Disable button lives. */
    @Test public void preinstalledAppsOfferDisableOnly() {
        assertEquals(1, removals(preinstalled()).size());
        assertEquals(AppActionPolicy.Action.DISABLE, removals(preinstalled()).get(0));
    }

    /** An updated system app can shed its update and then be disabled, so it
     *  is the one kind that gets two removal rows — in that order, because
     *  uninstalling updates is the reversible one. */
    @Test public void updatedSystemAppsOfferUninstallUpdatesThenDisable() {
        assertEquals(
                java.util.Arrays.asList(
                        AppActionPolicy.Action.UNINSTALL_UPDATES,
                        AppActionPolicy.Action.DISABLE),
                removals(updatedSystem()));
    }

    /** Nothing offers to remove the launcher you are standing in. */
    @Test public void thisLauncherOffersNoRemovalRows() {
        assertTrue(removals(self()).isEmpty());
    }

    /** FLAG_UPDATED_SYSTEM_APP without FLAG_SYSTEM does not happen on a real
     *  device, but the update flag is the one that decides. */
    @Test public void theUpdateFlagDecidesEvenWithoutTheSystemFlag() {
        assertEquals(
                java.util.Arrays.asList(
                        AppActionPolicy.Action.UNINSTALL_UPDATES,
                        AppActionPolicy.Action.DISABLE),
                removals(AppActionPolicy.actionsFor(false, true, false)));
    }

    /** Self wins over every flag combination. */
    @Test public void selfExclusionOutranksTheFlags() {
        assertTrue(removals(AppActionPolicy.actionsFor(true, true, true)).isEmpty());
    }

    // ---- the universal rows ------------------------------------------------

    /** LAUNCH and MORE DETAILS are offered for every app, this one included —
     *  the table above governs the removal rows and nothing else. */
    @Test public void launchAndAppInfoAreOfferedForEveryKindOfApp() {
        for (List<AppActionPolicy.Action> actions :
                java.util.Arrays.asList(user(), preinstalled(), updatedSystem(), self())) {
            assertTrue(actions.contains(AppActionPolicy.Action.LAUNCH));
            assertTrue(actions.contains(AppActionPolicy.Action.APP_INFO));
        }
    }

    /** LAUNCH first, MORE DETAILS last, removals in between — the order the
     *  popup has had since it was three hardcoded rows. */
    @Test public void launchLeadsAndAppInfoTrails() {
        List<AppActionPolicy.Action> actions = updatedSystem();
        assertEquals(AppActionPolicy.Action.LAUNCH, actions.get(0));
        assertEquals(AppActionPolicy.Action.APP_INFO, actions.get(actions.size() - 1));
        assertEquals(4, actions.size());
    }

    /** The launcher's own row is two entries and no more. */
    @Test public void thisLauncherGetsLaunchAndDetailsOnly() {
        assertEquals(
                java.util.Arrays.asList(
                        AppActionPolicy.Action.LAUNCH,
                        AppActionPolicy.Action.APP_INFO),
                self());
    }

    // ---- labels ------------------------------------------------------------

    /** The labels are the promise the row makes. "UNINSTALL / DISABLE" was one
     *  row promising two things, only one of which could ever happen. */
    @Test public void everyActionCarriesItsOwnLabel() {
        assertEquals("LAUNCH",            AppActionPolicy.Action.LAUNCH.label());
        assertEquals("UNINSTALL",         AppActionPolicy.Action.UNINSTALL.label());
        assertEquals("UNINSTALL UPDATES", AppActionPolicy.Action.UNINSTALL_UPDATES.label());
        assertEquals("DISABLE",           AppActionPolicy.Action.DISABLE.label());
        assertEquals("MORE DETAILS",      AppActionPolicy.Action.APP_INFO.label());
    }

    @Test public void noTwoActionsShareALabel() {
        java.util.Set<String> labels = new java.util.HashSet<>();
        for (AppActionPolicy.Action a : AppActionPolicy.Action.values()) {
            assertTrue("duplicate label " + a.label(), labels.add(a.label()));
        }
    }

    // ---- the returned list is the caller's ---------------------------------

    /** DrawerPanel builds views straight off this list; a shared instance
     *  would let one popup mutate the next one's rows. */
    @Test public void eachCallReturnsAnIndependentList() {
        List<AppActionPolicy.Action> first = user();
        first.clear();
        assertFalse(user().isEmpty());
    }

    private static List<AppActionPolicy.Action> removals(List<AppActionPolicy.Action> all) {
        List<AppActionPolicy.Action> out = new java.util.ArrayList<>();
        for (AppActionPolicy.Action a : all) {
            if (a != AppActionPolicy.Action.LAUNCH && a != AppActionPolicy.Action.APP_INFO) {
                out.add(a);
            }
        }
        return out;
    }
}
