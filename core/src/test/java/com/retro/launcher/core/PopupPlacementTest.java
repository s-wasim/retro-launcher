package com.retro.launcher.core;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class PopupPlacementTest {

    // A 1080x2400 portrait screen with a 60px status inset and a 130px
    // gesture-navigation inset, which is what an API 36 phone reports.
    private static int[] place(float x, float y, int w, int h) {
        return PopupPlacement.place(x, y, w, h, 1080, 2400, 0, 60, 0, 130);
    }

    @Test public void opensAtTheTouchPointWhenThereIsRoom() {
        assertArrayEquals(new int[]{300, 400}, place(300, 400, 410, 300));
    }

    @Test public void flipsAboveTheTouchPointWhenItWouldCrossTheBottomInset() {
        // 2100 + 300 = 2400, past the 2270 usable bottom, so the popup's
        // bottom edge sits on the touch point instead.
        assertArrayEquals(new int[]{300, 1800}, place(300, 2100, 410, 300));
    }

    @Test public void shiftsLeftWhenItWouldCrossTheRightInset() {
        // 900 + 410 = 1310, past 1080; the right edge lands on 1080.
        assertArrayEquals(new int[]{670, 400}, place(900, 400, 410, 300));
    }

    @Test public void clampsToTheTopInsetWhenFlippingWouldGoOffTheTop() {
        // Flipping 2200-height content above y=300 gives -1900; clamp to 60.
        assertArrayEquals(new int[]{300, 60}, place(300, 300, 410, 2200));
    }

    @Test public void clampsToTheLeftInsetWhenTheContentIsWiderThanTheScreen() {
        assertArrayEquals(new int[]{0, 400}, place(50, 400, 1200, 300));
    }

    @Test public void honoursNonZeroLeftAndRightInsets() {
        // A 40px left inset and 40px right inset — a landscape cutout.
        // 900 + 410 = 1310, past 1040 (screenWidth - insetRight); the right
        // edge lands on 1040, so x = 1040 - 410 = 630.
        assertArrayEquals(new int[]{630, 400},
                PopupPlacement.place(900, 400, 410, 300, 1080, 2400, 40, 60, 40, 130));
        assertArrayEquals(new int[]{40, 400},
                PopupPlacement.place(10, 400, 410, 300, 1080, 2400, 40, 60, 40, 130));
    }

    @Test public void treatsAZeroSizedScreenAsTheInsetOrigin() {
        assertArrayEquals(new int[]{0, 0},
                PopupPlacement.place(0, 0, 410, 300, 0, 0, 0, 0, 0, 0));
    }
}
