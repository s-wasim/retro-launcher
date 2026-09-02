package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.UsageMath;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.data.UsageRepository;
import com.retro.launcher.theme.Tint;
import com.retro.launcher.util.Haptics;

import java.util.ArrayList;

/**
 * Tier 4's fifth panel — DESIGN_NOTES §7d. TODAY total + pickups, a daily
 * limit card ({@link LimitSlider}), the LAST 7 DAYS bar chart
 * ({@link WeekChart}), and the top 6 MOST USED rows. All the day/limit
 * arithmetic is {@link UsageMath}; this class only lays the numbers out and
 * writes the limit straight through {@link Prefs} on every drag, matching
 * the rest of the settings surfaces.
 */
public final class ScreenTimePanel extends FrameLayout {

    /** Null until HomeActivity supplies one. Every call site null-checks
     *  rather than requiring construction order to guarantee it. */
    private Haptics haptics;

    public void setHaptics(Haptics haptics) {
        this.haptics = haptics;
        slider.setHaptics(haptics);
        coffee.setHaptics(haptics);
    }

    private void tick() { if (haptics != null) haptics.click(); }

    private static final int MOST_USED_ROWS = 6;

    private final Metrics metrics;
    private final Prefs prefs;

    private final LinearLayout header;
    private final int headerPadTop;
    private final ScrollView scroll;
    private final TextView todayTotal;
    private final CoffeeButton coffee;
    private final TextView pickupsLabel;
    private final LinearLayout limitCard;
    private final TextView limitTitle;
    private final TextView limitState;
    private final View limitBar;
    private final View limitBarSpacer;
    private final LimitSlider slider;
    private final LinearLayout weekSection;
    private final WeekChart weekChart;
    private final LinearLayout mostUsedSection;

    private Runnable onClose = () -> {};
    private Runnable onLimitChanged = () -> {};

    private Palette palette;
    private long todayMillis;
    private long[] last7Millis = new long[7];
    private int pickups;
    private java.util.List<UsageRepository.AppUsage> mostUsed = new ArrayList<>();

    public ScreenTimePanel(Context context, Metrics metrics, Prefs prefs) {
        super(context);
        this.metrics = metrics;
        this.prefs = prefs;

        Tint.setRole(this, Tint.ROLE_BG);
        setBackgroundColor(0xFF000000);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        column.addView(header = buildHeader());
        headerPadTop = header.getPaddingTop();

        scroll = new ScrollView(context);
        // Vertical only, and only while there is still content above: once the
        // list is at the top, a downward drag pulls the panel shut instead —
        // the reverse of the swipe up that opened it.
        LauncherRoot.setVerticalScroller(scroll);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int sidePad = Math.round(metrics.cqw(4.5f));
        content.setPadding(sidePad, 0, sidePad, Math.round(metrics.cqw(8f)));

        // The day's total, with the coffee button next to it. The total takes the
        // slack so the button keeps its measured width on a narrow screen
        // rather than clipping the words off its label.
        LinearLayout totalRow = new LinearLayout(context);
        totalRow.setOrientation(LinearLayout.HORIZONTAL);
        totalRow.setGravity(Gravity.CENTER_VERTICAL);

        todayTotal = new TextView(context);
        todayTotal.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        todayTotal.setSingleLine(true);
        todayTotal.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.textPx(8f, 24f));
        totalRow.addView(todayTotal, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        coffee = new CoffeeButton(context, metrics);
        totalRow.addView(coffee, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        content.addView(totalRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        pickupsLabel = new TextView(context);
        pickupsLabel.setTypeface(Typeface.MONOSPACE);
        pickupsLabel.setAllCaps(true);
        pickupsLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        LinearLayout.LayoutParams pickupsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pickupsLp.topMargin = Math.round(metrics.cqw(1f));
        content.addView(pickupsLabel, pickupsLp);

        limitCard = new LinearLayout(context);
        limitCard.setOrientation(LinearLayout.VERTICAL);
        int cardPad = Math.round(metrics.cqw(3.5f));
        limitCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = Math.round(metrics.cqw(6f));
        limitCard.setLayoutParams(cardLp);

        limitTitle = new TextView(context);
        limitTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        limitTitle.setAllCaps(true);
        limitTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        limitCard.addView(limitTitle);

        limitState = new TextView(context);
        limitState.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        limitState.setAllCaps(true);
        limitState.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stateLp.topMargin = Math.round(metrics.cqw(1.5f));
        limitCard.addView(limitState, stateLp);

        LinearLayout barTrack = new LinearLayout(context);
        barTrack.setOrientation(LinearLayout.HORIZONTAL);
        barTrack.setWeightSum(1f);
        int barH = Math.round(metrics.cqw(2f));
        LinearLayout.LayoutParams barTrackLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, barH);
        barTrackLp.topMargin = Math.round(metrics.cqw(2.5f));
        limitBar = new View(context);
        limitBar.setBackground(new GradientDrawable());
        barTrack.addView(limitBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f));
        limitBarSpacer = new View(context);
        barTrack.addView(limitBarSpacer, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        limitCard.addView(barTrack, barTrackLp);

        slider = new LimitSlider(context, metrics);
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sliderLp.topMargin = Math.round(metrics.cqw(3f));
        slider.setValue(prefs.limit());
        slider.setOnValueChangeListener(minutes -> {
            prefs.putInt(Prefs.K_LIMIT, minutes);
            rebuildLimitCard();
            onLimitChanged.run();
        });
        limitCard.addView(slider, sliderLp);

        content.addView(limitCard);

        weekSection = new LinearLayout(context);
        weekSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams weekLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        weekLp.topMargin = Math.round(metrics.cqw(6f));
        weekSection.setLayoutParams(weekLp);

        weekChart = new WeekChart(context, metrics);
        content.addView(weekSection);

        mostUsedSection = new LinearLayout(context);
        mostUsedSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams muLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        muLp.topMargin = Math.round(metrics.cqw(6f));
        mostUsedSection.setLayoutParams(muLp);
        content.addView(mostUsedSection);

        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(metrics.cqw(4.5f));
        int padTop = Math.round(metrics.cqw(5f));
        int padBottom = Math.round(metrics.cqw(3f));
        header.setPadding(padH, padTop, padH, padBottom);

        TextView title = new TextView(getContext());
        title.setText("SCREEN TIME");
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setAllCaps(true);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_TITLE_CQW, DrawerPanel.SIZE_TITLE_MIN));
        Tint.setRole(title, Tint.ROLE_INK);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(getContext());
        close.setText("CLOSE");
        close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        int closePad = Math.round(metrics.cqw(3f));
        close.setPadding(closePad, closePad, closePad, closePad);
        Tint.setRole(close, Tint.ROLE_P);
        close.setOnClickListener(v -> { tick(); onClose.run(); });
        header.addView(close);

        // The header sits outside the no-swipe scroll content below it — mark
        // it no-swipe too, otherwise a tap that lands a hair off CLOSE reads
        // as the start of a horizontal drag and snaps the panel home anyway.
        LauncherRoot.setNoSwipe(header);

        return header;
    }

    @Override public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.graphics.Insets sys = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            header.setPadding(header.getPaddingLeft(), headerPadTop + sys.top,
                    header.getPaddingRight(), header.getPaddingBottom());
            // Otherwise the bottom of the body rests under the gesture pill.
            com.retro.launcher.util.Insets.padScrollerForSystemBars(scroll, insets, 0);
        }
        return super.onApplyWindowInsets(insets);
    }

    public void setOnCloseListener(Runnable r) { this.onClose = r; }
    public void setOnLimitChangedListener(Runnable r) { this.onLimitChanged = r; }

    public void setPalette(Palette p) {
        this.palette = p;
        Tint.apply(this, p);
        slider.setPalette(p);
        coffee.setPalette(p);
        todayTotal.setTextColor(p.ink);
        pickupsLabel.setTextColor(p.a);
        limitTitle.setTextColor(p.ink);
        rebuildTotals();
        rebuildLimitCard();
        rebuildWeekChart();
        rebuildMostUsed();
    }

    /** Pulled fresh from {@link UsageRepository} on every resume. */
    public void setUsage(long todayMillis, long[] last7Millis, int pickups,
                          java.util.List<UsageRepository.AppUsage> mostUsed) {
        this.todayMillis = todayMillis;
        this.last7Millis = last7Millis;
        this.pickups = pickups;
        this.mostUsed = mostUsed;
        rebuildAll();
    }

    private void rebuildAll() {
        if (palette == null) return;
        rebuildTotals();
        rebuildLimitCard();
        rebuildWeekChart();
        rebuildMostUsed();
    }

    private void rebuildTotals() {
        int minutes = (int) (todayMillis / 60_000L);
        todayTotal.setText(hm(minutes));
        todayTotal.setTextColor(palette.ink);
        pickupsLabel.setText(pickups + " PICKUPS");
    }

    private void rebuildLimitCard() {
        if (palette == null) return;
        int limit = prefs.limit();
        boolean over = UsageMath.isOverLimit(todayMillis, limit);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(metrics.cqw(1.5f));
        cardBg.setStroke(Math.max(1, Math.round(metrics.cqw(0.7f))), palette.ink);
        cardBg.setColor(over ? ((0x33 << 24) | (palette.s & 0x00FFFFFF)) : 0x00000000);
        limitCard.setBackground(cardBg);

        limitTitle.setText("DAILY LIMIT — " + hm(limit));
        limitTitle.setTextColor(palette.ink);

        limitState.setText(UsageMath.stateLabel(todayMillis, limit));
        limitState.setTextColor(palette.h);

        GradientDrawable barBg = (GradientDrawable) limitBar.getBackground();
        barBg.setCornerRadius(metrics.cqw(1f));
        barBg.setColor(over ? palette.a : palette.p);
        float frac = Math.min(1f, UsageMath.usageFraction(todayMillis, limit));
        LinearLayout.LayoutParams barLp = (LinearLayout.LayoutParams) limitBar.getLayoutParams();
        barLp.weight = frac;
        limitBar.setLayoutParams(barLp);
        LinearLayout.LayoutParams spacerLp = (LinearLayout.LayoutParams) limitBarSpacer.getLayoutParams();
        spacerLp.weight = 1f - frac;
        limitBarSpacer.setLayoutParams(spacerLp);

        if (slider.getValue() != limit) slider.setValue(limit);
    }

    private static final String[] WEEKDAY = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    private void rebuildWeekChart() {
        if (palette == null || last7Millis == null) return;

        weekSection.removeAllViews();
        weekSection.addView(sectionHeader("LAST 7 DAYS"));

        int limit = prefs.limit();
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.List<WeekChart.Bar> bars = new ArrayList<>(7);
        for (int i = 0; i < last7Millis.length; i++) {
            java.util.Calendar day = (java.util.Calendar) today.clone();
            day.add(java.util.Calendar.DAY_OF_MONTH, -(last7Millis.length - 1 - i));
            String weekday = WEEKDAY[day.get(java.util.Calendar.DAY_OF_WEEK) - 1];
            int minutes = (int) (last7Millis[i] / 60_000L);
            bars.add(new WeekChart.Bar(weekday, minutes, minutes > limit));
        }
        weekChart.setPalette(palette);
        weekChart.setBars(bars);

        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chartLp.topMargin = Math.round(metrics.cqw(3f));
        weekSection.addView(weekChart, chartLp);
    }

    private void rebuildMostUsed() {
        mostUsedSection.removeAllViews();
        if (palette == null) return;
        mostUsedSection.addView(sectionHeader("MOST USED"));

        long max = 1;
        for (UsageRepository.AppUsage u : mostUsed) max = Math.max(max, u.millis);

        int gap = Math.round(metrics.cqw(2.5f));
        int rows = Math.min(MOST_USED_ROWS, mostUsed.size());
        if (rows == 0) {
            TextView empty = new TextView(getContext());
            empty.setText("NO USAGE RECORDED YET TODAY.");
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setTextColor(palette.a);
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
            addTopMargin(empty, gap);
            mostUsedSection.addView(empty);
            return;
        }
        for (int i = 0; i < rows; i++) {
            UsageRepository.AppUsage u = mostUsed.get(i);
            View row = mostUsedRow(u, max);
            addTopMargin(row, gap);
            mostUsedSection.addView(row);
        }
    }

    private View mostUsedRow(UsageRepository.AppUsage usage, long max) {
        String label = DockView.labelFor(usage.pkg);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView glyph = new TextView(getContext());
        glyph.setGravity(Gravity.CENTER);
        glyph.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        glyph.setText(label.isEmpty() ? "?" : label.substring(0, 1));
        glyph.setTextColor(palette.p);
        int glyphSize = Math.round(metrics.cqw(7f));
        GradientDrawable glyphBg = new GradientDrawable();
        glyphBg.setCornerRadius(metrics.cqw(1f));
        glyphBg.setColor(palette.tile);
        glyph.setBackground(glyphBg);
        glyph.setLayoutParams(new LinearLayout.LayoutParams(glyphSize, glyphSize));
        row.addView(glyph);

        LinearLayout mid = new LinearLayout(getContext());
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        midLp.leftMargin = Math.round(metrics.cqw(2.5f));
        midLp.rightMargin = Math.round(metrics.cqw(2.5f));

        TextView name = new TextView(getContext());
        name.setText(label);
        name.setTypeface(Typeface.MONOSPACE);
        name.setAllCaps(true);
        name.setTextColor(palette.ink);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        mid.addView(name);

        FrameLayout barTrack = new FrameLayout(getContext());
        int barH = Math.round(metrics.cqw(1.2f));
        LinearLayout.LayoutParams barTrackLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, barH);
        barTrackLp.topMargin = Math.round(metrics.cqw(1f));
        View bar = new View(getContext());
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(palette.p);
        barBg.setCornerRadius(metrics.cqw(0.6f));
        bar.setBackground(barBg);
        float frac = usage.millis / (float) max;
        int fullW = Math.round(metrics.cqw(40f));
        barTrack.addView(bar, new FrameLayout.LayoutParams(Math.round(fullW * frac), FrameLayout.LayoutParams.MATCH_PARENT));
        mid.addView(barTrack, barTrackLp);

        row.addView(mid, midLp);

        TextView minutes = new TextView(getContext());
        minutes.setText(hm((int) (usage.millis / 60_000L)));
        minutes.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        minutes.setTextColor(palette.a);
        minutes.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        row.addView(minutes);

        return row;
    }

    private TextView sectionHeader(String title) {
        TextView t = new TextView(getContext());
        t.setText(title);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setAllCaps(true);
        t.setTextColor(palette.p);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        return t;
    }

    private void addTopMargin(View v, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = margin;
        v.setLayoutParams(lp);
    }

    private static String hm(int totalMinutes) {
        int h = totalMinutes / 60, m = totalMinutes % 60;
        String mm = m < 10 ? "0" + m : String.valueOf(m);
        return h + "H " + mm + "M";
    }
}
