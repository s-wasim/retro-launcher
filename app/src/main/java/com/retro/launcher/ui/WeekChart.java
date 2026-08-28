package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;

import java.util.List;

/**
 * The "LAST 7 DAYS" bar row from DESIGN_NOTES §7d: 7 columns, bars up to
 * {@code 26cqw} tall scaled to the loudest day in the window, each labelled
 * with hours and a weekday. A day over the limit paints {@code a} instead of
 * {@code p}.
 */
public final class WeekChart extends LinearLayout {

    public static final class Bar {
        public final String weekday;
        public final int minutes;
        public final boolean overLimit;
        public Bar(String weekday, int minutes, boolean overLimit) {
            this.weekday = weekday;
            this.minutes = minutes;
            this.overLimit = overLimit;
        }
    }

    private static final float MAX_HEIGHT_CQW = 26f;

    private final Metrics metrics;
    private Palette palette;
    private List<Bar> bars;

    public WeekChart(Context context, Metrics metrics) {
        super(context);
        this.metrics = metrics;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.BOTTOM);
    }

    public void setPalette(Palette p) {
        this.palette = p;
        rebuild();
    }

    public void setBars(List<Bar> bars) {
        this.bars = bars;
        rebuild();
    }

    private void rebuild() {
        removeAllViews();
        if (palette == null || bars == null) return;

        int max = 1;
        for (Bar b : bars) max = Math.max(max, b.minutes);

        int gap = Math.round(metrics.cqw(2f));
        int maxHeightPx = Math.round(metrics.cqw(MAX_HEIGHT_CQW));

        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            LinearLayout col = new LinearLayout(getContext());
            col.setOrientation(VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            LayoutParams colLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) colLp.leftMargin = gap;
            col.setLayoutParams(colLp);

            TextView hours = new TextView(getContext());
            hours.setText(minutesLabel(b.minutes));
            hours.setTypeface(Typeface.MONOSPACE);
            hours.setTextColor(palette.ink);
            hours.setGravity(Gravity.CENTER);
            hours.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
            col.addView(hours);

            android.view.View bar = new android.view.View(getContext());
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(b.overLimit ? palette.a : palette.p);
            bg.setCornerRadius(metrics.cqw(0.5f));
            bar.setBackground(bg);
            int h = Math.max(Math.round(metrics.dp(3f)), Math.round(maxHeightPx * (b.minutes / (float) max)));
            LayoutParams barLp = new LayoutParams(Math.round(metrics.cqw(6f)), h);
            barLp.topMargin = Math.round(metrics.cqw(1f));
            barLp.bottomMargin = Math.round(metrics.cqw(1f));
            col.addView(bar, barLp);

            TextView weekday = new TextView(getContext());
            weekday.setText(b.weekday);
            weekday.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            weekday.setAllCaps(true);
            weekday.setTextColor(palette.ink);
            weekday.setGravity(Gravity.CENTER);
            weekday.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
            col.addView(weekday);

            addView(col);
        }
    }

    private static String minutesLabel(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + "M";
        return h + "H" + (m > 0 ? pad2(m) + "M" : "");
    }

    private static String pad2(int m) {
        return m < 10 ? "0" + m : String.valueOf(m);
    }
}
