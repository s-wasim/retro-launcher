package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class LockRouteTest {

    /** Shizuku avoids the accessibility service, which some banking apps
     *  flag, so it outranks it when available. */
    @Test public void shizukuIsPreferredWhenBothAreAvailable() {
        assertEquals(LockRoute.SHIZUKU, LockRoute.choose(true, true));
    }

    @Test public void shizukuAloneIsChosen() {
        assertEquals(LockRoute.SHIZUKU, LockRoute.choose(true, false));
    }

    @Test public void accessibilityIsTheFallback() {
        assertEquals(LockRoute.ACCESSIBILITY, LockRoute.choose(false, true));
    }

    @Test public void reportsNoneWhenNeitherIsSetUp() {
        assertEquals(LockRoute.NONE, LockRoute.choose(false, false));
    }

    @Test public void statusWordsSeparateTheTwoWorkingRoutesFromNone() {
        assertEquals("ON",     LockRoute.SHIZUKU.status());
        assertEquals("ON",     LockRoute.ACCESSIBILITY.status());
        assertEquals("ENABLE", LockRoute.NONE.status());
    }

    /** Both surviving routes leave the fingerprint working, so both are
     *  finished business; only NONE still has something for the user to do. */
    @Test public void bothWorkingRoutesAreSettled() {
        assertTrue(LockRoute.SHIZUKU.settled());
        assertTrue(LockRoute.ACCESSIBILITY.settled());
        assertFalse(LockRoute.NONE.settled());
    }

    /**
     * V9 §10. The admin route locked, but through {@code lockNow()}, which
     * raises the strong-auth flag and makes Android demand the PIN instead of
     * the fingerprint on the next unlock — and activating a device admin is
     * the largest trust signal the app asked for. It is gone, and this asserts
     * it stays gone rather than quietly returning under another name.
     */
    @Test public void onlyTwoRoutesAndAFallbackRemain() {
        assertEquals(3, LockRoute.values().length);
        for (LockRoute route : LockRoute.values()) {
            assertNotEquals("ADMIN", route.name());
        }
    }
}
