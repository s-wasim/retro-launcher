package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class DetentsTest {

    @Test public void levelIsZeroBelowTheFirstThreshold() {
        assertEquals(0, Detents.level(0f));
        assertEquals(0, Detents.level(0.19f));
    }

    @Test public void levelIsOneAtExactlyTheFirstThreshold() {
        assertEquals(1, Detents.level(0.2f));
    }

    @Test public void levelIsFiveAtOne() {
        assertEquals(5, Detents.level(1f));
    }

    @Test public void progressOutsideZeroToOneClamps() {
        assertEquals(0, Detents.level(-1f));
        assertEquals(5, Detents.level(2f));
    }

    @Test public void nanReadsAsZero() {
        assertEquals(0, Detents.level(Float.NaN));
    }

    @Test public void risingThrough0Point2EntersLevel1() {
        Detents d = new Detents();
        assertEquals(0, d.next(0.19f));
        assertEquals(1, d.next(0.20f));
    }

    @Test public void fallingTo0Point19StaysAtLevel1() {
        Detents d = new Detents();
        d.next(0.20f);
        assertEquals(1, d.next(0.19f));
    }

    @Test public void fallingTo0Point18ReturnsToLevel0() {
        Detents d = new Detents();
        d.next(0.20f);
        d.next(0.19f);
        assertEquals(0, d.next(0.18f));
    }

    @Test public void aMonotonicSweepProducesExactlyFiveLevelChanges() {
        Detents d = new Detents();
        int changes = 0;
        int last = d.next(0f);
        for (int i = 1; i <= 120; i++) {
            int lvl = d.next(i / 120f);
            if (lvl != last) { changes++; last = lvl; }
        }
        assertEquals(5, changes);
    }

    @Test public void aSweepUpThenDownProducesExactlyTenLevelChanges() {
        Detents d = new Detents();
        int changes = 0;
        int last = d.next(0f);
        for (int i = 1; i <= 120; i++) {
            int lvl = d.next(i / 120f);
            if (lvl != last) { changes++; last = lvl; }
        }
        for (int i = 119; i >= 0; i--) {
            int lvl = d.next(i / 120f);
            if (lvl != last) { changes++; last = lvl; }
        }
        assertEquals(10, changes);
    }
}
