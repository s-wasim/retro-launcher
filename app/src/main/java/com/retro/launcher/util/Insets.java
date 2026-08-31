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

    /**
     * The top system-bar inset in pixels — the status bar's height. Used to
     * tell a swipe meant for the notification shade apart from one meant for
     * panel navigation; see {@code LauncherRoot#lockAxis}.
     */
    public static int systemTop(View v) {
        WindowInsets wi = v.getRootWindowInsets();
        if (wi == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wi.getInsets(WindowInsets.Type.systemBars()).top;
        }
        //noinspection deprecation
        return wi.getSystemWindowInsetTop();
    }
}
