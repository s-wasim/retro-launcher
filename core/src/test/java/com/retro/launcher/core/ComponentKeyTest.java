package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * V9 §11. The dock and the category map are keyed on these strings and are
 * already full of them on every installed device, so the primary profile's
 * form is frozen: {@code pkg/activity}, byte for byte what it was. Only a
 * clone — an app living in a secondary user profile — carries the
 * {@code @serial} suffix, which is what makes an existing dock survive the
 * upgrade untouched.
 */
public class ComponentKeyTest {

    @Test public void thePrimaryProfileKeepsTheExistingTwoPartForm() {
        assertEquals("com.whatsapp/.Main",
                ComponentKey.format("com.whatsapp", ".Main", ComponentKey.PRIMARY));
    }

    @Test public void aCloneCarriesItsProfileSerial() {
        assertEquals("com.whatsapp/.Main@95",
                ComponentKey.format("com.whatsapp", ".Main", 95L));
    }

    @Test public void aPrimaryKeyRoundTrips() {
        String key = ComponentKey.format("com.whatsapp", ".Main", ComponentKey.PRIMARY);
        assertEquals("com.whatsapp", ComponentKey.packageOf(key));
        assertEquals(".Main", ComponentKey.activityOf(key));
        assertEquals(ComponentKey.PRIMARY, ComponentKey.serialOf(key));
        assertFalse(ComponentKey.isClone(key));
    }

    @Test public void aCloneKeyRoundTrips() {
        String key = ComponentKey.format("com.whatsapp", ".Main", 95L);
        assertEquals("com.whatsapp", ComponentKey.packageOf(key));
        assertEquals(".Main", ComponentKey.activityOf(key));
        assertEquals(95L, ComponentKey.serialOf(key));
        assertTrue(ComponentKey.isClone(key));
    }

    /** Every key already stored on a device predates the suffix, and none of
     *  them may start resolving to a profile that does not exist. */
    @Test public void keysWrittenBeforeThisChangeStillReadAsPrimary() {
        assertEquals("com.android.chrome", ComponentKey.packageOf("com.android.chrome/.Main"));
        assertEquals(".Main", ComponentKey.activityOf("com.android.chrome/.Main"));
        assertEquals(ComponentKey.PRIMARY, ComponentKey.serialOf("com.android.chrome/.Main"));
    }

    /** Serial 0 is a real profile id on some ROMs, so it must not be folded
     *  into "no suffix" — the suffix's presence is what decides, not its
     *  value. */
    @Test public void serialZeroIsStillAnExplicitProfile() {
        String key = ComponentKey.format("com.whatsapp", ".Main", 0L);
        assertEquals("com.whatsapp/.Main@0", key);
        assertEquals(0L, ComponentKey.serialOf(key));
        assertTrue(ComponentKey.isClone(key));
    }

    /** A key with no slash at all is not something this launcher writes, but
     *  Prefs is user-editable storage and a malformed entry must degrade to a
     *  dead row rather than an exception on the dock's draw path. */
    @Test public void aKeyWithNoActivityDegradesQuietly() {
        assertEquals("com.whatsapp", ComponentKey.packageOf("com.whatsapp"));
        assertEquals("", ComponentKey.activityOf("com.whatsapp"));
        assertEquals(ComponentKey.PRIMARY, ComponentKey.serialOf("com.whatsapp"));
    }

    @Test public void aNullKeyDegradesQuietly() {
        assertEquals("", ComponentKey.packageOf(null));
        assertEquals("", ComponentKey.activityOf(null));
        assertEquals(ComponentKey.PRIMARY, ComponentKey.serialOf(null));
        assertFalse(ComponentKey.isClone(null));
    }

    /** A trailing '@' with nothing after it, or a non-numeric suffix, is
     *  corruption; reading it as the primary profile launches the app the
     *  user most likely meant instead of nothing at all. */
    @Test public void anUnparseableSuffixReadsAsPrimary() {
        assertEquals(ComponentKey.PRIMARY, ComponentKey.serialOf("com.whatsapp/.Main@"));
        assertEquals(ComponentKey.PRIMARY, ComponentKey.serialOf("com.whatsapp/.Main@xy"));
        assertEquals(".Main", ComponentKey.activityOf("com.whatsapp/.Main@xy"));
    }

    /** The '@' only ever separates the suffix. An activity name cannot
     *  contain one — it is a Java identifier path — but a package name in a
     *  malformed stored key might, and the split must still come from the
     *  right-hand side. */
    @Test public void theSuffixIsTakenFromTheLastAt() {
        assertEquals(7L, ComponentKey.serialOf("com.a@b/.Main@7"));
        assertEquals(".Main", ComponentKey.activityOf("com.a@b/.Main@7"));
    }
}
