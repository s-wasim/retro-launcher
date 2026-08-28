package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.retro.launcher.core.Metrics;

/**
 * First-run screen, shown once ahead of {@link HintOverlay}: one row per
 * optional permission, each opening the right grant flow.
 *
 * Usage Access feeds Screen Time; coarse location feeds the weather reading
 * (Tier 5). Continuing works with neither — no permission blocks the launcher
 * (spec §5, DESIGN_NOTES §9 row 10's permissions caption), and a skipped setup
 * stays recoverable from Settings' permissions block.
 */
public final class SetupScreen extends FrameLayout {

    public interface Listener {
        void onGrantUsageAccess();
        void onGrantLocation();
        void onContinue();
    }

    private static final int COLOR_GRANTED = 0xFF6FE38A;
    private static final int COLOR_NOT_GRANTED = 0xFFE3A66F;

    private final TextView usageRow;
    private final TextView locationRow;
    private Listener listener = new Listener() {
        @Override public void onGrantUsageAccess() {}
        @Override public void onGrantLocation() {}
        @Override public void onContinue() {}
    };

    public SetupScreen(Context context, Metrics metrics) {
        super(context);
        setBackgroundColor(0xFF08090C);
        LauncherRoot.setNoSwipe(this);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int pad = Math.round(metrics.cqw(8f));
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(context);
        title.setText("WELCOME");
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setAllCaps(true);
        title.setTextColor(0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_TITLE_CQW, DrawerPanel.SIZE_TITLE_MIN));
        column.addView(title);

        TextView body = new TextView(context);
        body.setText("SCREEN TIME NEEDS USAGE ACCESS. WEATHER NEEDS A COARSE "
                + "LOCATION. YOU CAN SKIP BOTH AND GRANT THEM LATER FROM SETTINGS.");
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(0xFFAAAAAA);
        body.setGravity(Gravity.CENTER);
        body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = Math.round(metrics.cqw(3f));
        column.addView(body, bodyLp);

        usageRow = permissionRow(context, metrics, () -> listener.onGrantUsageAccess());
        column.addView(usageRow, rowParams(metrics, 6f));

        locationRow = permissionRow(context, metrics, () -> listener.onGrantLocation());
        column.addView(locationRow, rowParams(metrics, 3f));

        setGranted(false, false);

        TextView continueBtn = new TextView(context);
        continueBtn.setText("CONTINUE");
        continueBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        continueBtn.setAllCaps(true);
        continueBtn.setTextColor(0xFFFFFFFF);
        continueBtn.setGravity(Gravity.CENTER);
        int btnPadH = Math.round(metrics.cqw(6f));
        int btnPadV = Math.round(metrics.cqw(2.5f));
        continueBtn.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setStroke(Math.max(1, Math.round(metrics.cqw(0.7f))), 0xFFFFFFFF);
        btnBg.setCornerRadius(metrics.cqw(1.5f));
        continueBtn.setBackground(btnBg);
        continueBtn.setOnClickListener(v -> listener.onContinue());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = Math.round(metrics.cqw(6f));
        column.addView(continueBtn, btnLp);

        addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private TextView permissionRow(Context context, Metrics metrics, Runnable onTap) {
        TextView row = new TextView(context);
        row.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.setAllCaps(true);
        row.setGravity(Gravity.CENTER);
        row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        // A bare line of text is a small target; the padding widens the tap
        // region without moving the type.
        int padV = Math.round(metrics.cqw(2f));
        row.setPadding(0, padV, 0, padV);
        row.setOnClickListener(v -> onTap.run());
        return row;
    }

    private static LinearLayout.LayoutParams rowParams(Metrics metrics, float topCqw) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(metrics.cqw(topCqw));
        return lp;
    }

    public void setListener(Listener l) { this.listener = l; }

    public void setGranted(boolean usageGranted, boolean locationGranted) {
        state(usageRow, usageGranted, "USAGE ACCESS");
        state(locationRow, locationGranted, "LOCATION");
    }

    private static void state(TextView row, boolean granted, String label) {
        row.setText(granted ? label + " — GRANTED" : label + " — TAP TO GRANT");
        row.setTextColor(granted ? COLOR_GRANTED : COLOR_NOT_GRANTED);
    }
}
