package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.retro.launcher.core.Metrics;

/**
 * The first-run swipe-hint overlay — DESIGN_NOTES §7f. Sits above
 * {@link LauncherRoot} in z-order and is itself no-swipe and fully
 * clickable, so it eats every touch while visible; a single tap anywhere
 * dismisses it (the caller is responsible for persisting that via
 * {@code Prefs.K_HINT}).
 */
public final class HintOverlay extends FrameLayout {

    private Runnable onDismiss = () -> {};

    public HintOverlay(Context context, Metrics metrics) {
        super(context);
        setBackgroundColor(0xD1040508); // rgba(4,5,8,.82)
        setClickable(true);
        setFocusable(true);
        LauncherRoot.setNoSwipe(this);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);

        // None of these gestures has a visible affordance, by design
        // (DESIGN_NOTES §5), so this screen is the only place anyone learns
        // they exist — which is why every one of them has to be listed here,
        // the two non-swipe ones included.
        String[] lines = {"SWIPE TO MOVE", "→ SETTINGS", "← APP DRAWER",
                "↑ SCREEN TIME", "↓ NOTIFICATIONS",
                "DOUBLE-TAP TO SEARCH", "LONG-PRESS TO LOCK"};
        for (String line : lines) {
            TextView t = new TextView(context);
            t.setText(line);
            t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            t.setAllCaps(true);
            t.setTextColor(0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    metrics.textPx(DrawerPanel.SIZE_TITLE_CQW, DrawerPanel.SIZE_TITLE_MIN));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(metrics.cqw(4f));
            column.addView(t, lp);
        }

        // Lock and notifications both need a one-time switch flipped before
        // the gesture above does anything, and being sent to Settings is a
        // worse first impression than being told to expect it. Same for the
        // default-launcher step, which is what makes the home gesture reach
        // this launcher at all.
        TextView setup = new TextView(context);
        setup.setText("LOCK AND NOTIFICATIONS NEED SWITCHING ON UNDER\n"
                + "SETTINGS → PERMISSIONS, WHERE YOU CAN ALSO MAKE\n"
                + "THIS YOUR DEFAULT LAUNCHER");
        setup.setTypeface(Typeface.MONOSPACE);
        setup.setAllCaps(true);
        setup.setTextColor(0xFF8A8A8A);
        setup.setGravity(Gravity.CENTER);
        setup.setLineSpacing(0f, 1.35f);
        setup.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        LinearLayout.LayoutParams setupLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        setupLp.topMargin = Math.round(metrics.cqw(8f));
        column.addView(setup, setupLp);

        TextView tap = new TextView(context);
        tap.setText("TAP ANYWHERE TO BEGIN");
        tap.setTypeface(Typeface.MONOSPACE);
        tap.setAllCaps(true);
        tap.setTextColor(0xFFAAAAAA);
        tap.setGravity(Gravity.CENTER);
        tap.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        LinearLayout.LayoutParams tapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tapLp.topMargin = Math.round(metrics.cqw(6f));
        column.addView(tap, tapLp);

        addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setOnClickListener(v -> onDismiss.run());
    }

    public void setOnDismissListener(Runnable r) { this.onDismiss = r; }
}
