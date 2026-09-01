package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class LockRouteTest {

    /** The bug this class exists to prevent: with both routes available the
     *  launcher used to lock through the device admin, which makes Android
     *  demand the PIN on the next unlock and refuse the fingerprint. */
    @Test public void prefersAccessibilityWhenBothRoutesAreAvailable() {
        assertEquals(LockRoute.ACCESSIBILITY, LockRoute.choose(true, true));
    }

    @Test public void fallsBackToTheAdminWhenAccessibilityIsOff() {
        assertEquals(LockRoute.ADMIN, LockRoute.choose(false, true));
    }

    @Test public void usesAccessibilityWhenTheAdminIsNotActive() {
        assertEquals(LockRoute.ACCESSIBILITY, LockRoute.choose(true, false));
    }

    @Test public void reportsNoneWhenNeitherIsSetUp() {
        assertEquals(LockRoute.NONE, LockRoute.choose(false, false));
    }

    /** The DEVICE LOCK row has to distinguish "locks, keeps your fingerprint"
     *  from "locks, but forces the PIN", or nobody already on the admin route
     *  ever finds out there is a better one. */
    @Test public void statusWordsSeparateTheTwoWorkingRoutes() {
        assertEquals("ON",       LockRoute.ACCESSIBILITY.status());
        assertEquals("PIN ONLY", LockRoute.ADMIN.status());
        assertEquals("ENABLE",   LockRoute.NONE.status());
    }

    /** The admin route locks, but it is not finished business: it costs the
     *  fingerprint, so the row has to stay live and reachable. */
    @Test public void onlyTheAccessibilityRouteIsSettled() {
        assertTrue(LockRoute.ACCESSIBILITY.settled());
        assertFalse(LockRoute.ADMIN.settled());
        assertFalse(LockRoute.NONE.settled());
    }
}
