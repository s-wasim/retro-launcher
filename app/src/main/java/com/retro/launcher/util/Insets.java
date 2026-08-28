package com.retro.launcher.util;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Horizontal system-gesture insets, in pixels. Swipes that begin inside these
 * strips belong to Android's Back gesture, not to panel navigation —
 * see DESIGN_NOTES §9 delta 12.
 *
 * Gesture insets did not exist before API 29 (the three-button era), so they
 * are zero there, which is the correct answer rather than a fallback.
 */
public final class Insets {

    private Insets() {}

    public static int[] gestureLeftRight(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WindowInsets wi = v.getRootWindowInsets();
            if (wi != null) {
                android.graphics.Insets g = wi.getSystemGestureInsets();
                return new int[]{ g.left, g.right };
            }
        }
        return new int[]{ 0, 0 };
    }
}
