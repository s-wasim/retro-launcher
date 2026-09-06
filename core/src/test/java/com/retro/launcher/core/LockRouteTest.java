package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class LockRouteTest {

    /** Shizuku avoids both the accessibility service (flagged by some
     *  banking apps) and the device admin (costs the fingerprint), so it
     *  outranks both when available. */
    @Test public void shizukuIsPreferredWhenAllThreeAreAvailable() {
        assertEquals(LockRoute.SHIZUKU, LockRoute.choose(true, true, true));
    }

    @Test public void shizukuIsPreferredOverAccessibilityAlone() {
        assertEquals(LockRoute.SHIZUKU, LockRoute.choose(true, true, false));
    }

    @Test public void shizukuIsPreferredOverAdminAlone() {
        assertEquals(LockRoute.SHIZUKU, LockRoute.choose(true, false, true));
    }

    @Test public void shizukuAloneIsChosen() {
        assertEquals(LockRoute.SHIZUKU, LockRoute.choose(true, false, false));
    }

    /** The bug the two-route version existed to prevent, still true without
     *  Shizuku: the device admin locks through the framework, which forces
     *  the next unlock to PIN and refuses the fingerprint. */
    @Test public void prefersAccessibilityWhenBothRoutesAreAvailableAndShizukuIsNot() {
        assertEquals(LockRoute.ACCESSIBILITY, LockRoute.choose(false, true, true));
    }

    @Test public void fallsBackToTheAdminWhenNeitherShizukuNorAccessibilityIsOn() {
        assertEquals(LockRoute.ADMIN, LockRoute.choose(false, false, true));
    }

    @Test public void usesAccessibilityWhenTheAdminIsNotActive() {
        assertEquals(LockRoute.ACCESSIBILITY, LockRoute.choose(false, true, false));
    }

    @Test public void reportsNoneWhenNoneIsSetUp() {
        assertEquals(LockRoute.NONE, LockRoute.choose(false, false, false));
    }

    @Test public void statusWordsSeparateTheThreeWorkingRoutes() {
        assertEquals("ON",       LockRoute.SHIZUKU.status());
        assertEquals("ON",       LockRoute.ACCESSIBILITY.status());
        assertEquals("PIN ONLY", LockRoute.ADMIN.status());
        assertEquals("ENABLE",   LockRoute.NONE.status());
    }

    /** Shizuku costs nothing extra to unlock again, same as accessibility —
     *  only the admin route is unsettled business. */
    @Test public void shizukuAndAccessibilityAreBothSettled() {
        assertTrue(LockRoute.SHIZUKU.settled());
        assertTrue(LockRoute.ACCESSIBILITY.settled());
        assertFalse(LockRoute.ADMIN.settled());
        assertFalse(LockRoute.NONE.settled());
    }
}
