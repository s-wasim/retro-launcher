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

        String[] lines = {"SWIPE TO MOVE", "→ SETTINGS", "← APP DRAWER", "↑ SCREEN TIME"};
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
        tapLp.topMargin = Math.round(metrics.cqw(8f));
        column.addView(tap, tapLp);

        addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setOnClickListener(v -> onDismiss.run());
    }

    public void setOnDismissListener(Runnable r) { this.onDismiss = r; }
}
