package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.UsageMath;

import java.util.function.IntConsumer;

/**
 * The daily-limit control from DESIGN_NOTES §7d: a {@code −15} button, a
 * drag track, and a {@code +15} button, range 30–600 minutes snapped to 15.
 * All arithmetic (clamp + snap) is {@link UsageMath#snapLimit}, so the
 * behaviour here matches whatever the unit-tested core already guarantees.
 */
public final class LimitSlider extends LinearLayout {

    private final Metrics metrics;
    private final TextView minus, plus;
    private final View fill, thumb;
    private final FrameLayout trackWrap;

    private Palette palette;
    private int value = 240;
    private IntConsumer listener = m -> {};

    public LimitSlider(Context context, Metrics metrics) {
        super(context);
        this.metrics = metrics;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        LauncherRoot.setNoSwipe(this);

        minus = stepButton("−15");
        plus = stepButton("+15");

        trackWrap = new FrameLayout(context);
        int trackH = Math.round(metrics.cqw(4f));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(0, trackH, 1f);
        int sideGap = Math.round(metrics.cqw(2.5f));
        wrapLp.leftMargin = sideGap;
        wrapLp.rightMargin = sideGap;
        trackWrap.setLayoutParams(wrapLp);

        View track = new View(context);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(metrics.cqw(1f));
        track.setBackground(trackBg);
        trackWrap.addView(track, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        fill = new View(context);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setCornerRadius(metrics.cqw(1f));
        fill.setBackground(fillBg);
        FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT);
        fillLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        trackWrap.addView(fill, fillLp);

        int knob = Math.round(metrics.cqw(5f));
        thumb = new View(context);
        GradientDrawable thumbBg = new GradientDrawable();
        thumb.setBackground(thumbBg);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(knob, knob);
        thumbLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        trackWrap.addView(thumb, thumbLp);

        addView(minus);
        addView(trackWrap);
        addView(plus);

        minus.setOnClickListener(v -> setValue(value - UsageMath.LIMIT_STEP));
        plus.setOnClickListener(v -> setValue(value + UsageMath.LIMIT_STEP));
        trackWrap.setOnTouchListener(this::onTrackTouch);

        trackWrap.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> updateThumb());
    }

    private boolean onTrackTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                int w = trackWrap.getWidth();
                if (w <= 0) return true;
                float frac = clamp01(e.getX() / w);
                int minutes = UsageMath.LIMIT_MIN
                        + Math.round(frac * (UsageMath.LIMIT_MAX - UsageMath.LIMIT_MIN));
                setValue(minutes);
                return true;
        }
        return false;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private TextView stepButton(String label) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        int pad = Math.round(metrics.cqw(2f));
        t.setPadding(pad, pad, pad, pad);
        return t;
    }

    public void setValue(int minutes) {
        int next = UsageMath.snapLimit(minutes);
        boolean changed = next != value;
        value = next;
        updateThumb();
        if (changed) listener.accept(value);
    }

    public int getValue() { return value; }

    public void setOnValueChangeListener(IntConsumer l) { this.listener = l; }

    private void updateThumb() {
        int w = trackWrap.getWidth();
        if (w <= 0) return;
        float frac = (value - UsageMath.LIMIT_MIN) / (float) (UsageMath.LIMIT_MAX - UsageMath.LIMIT_MIN);
        int knob = thumb.getLayoutParams().width;
        int usable = w - knob;
        int x = Math.round(frac * usable);

        FrameLayout.LayoutParams fillLp = (FrameLayout.LayoutParams) fill.getLayoutParams();
        fillLp.width = x + knob / 2;
        fill.setLayoutParams(fillLp);

        thumb.setTranslationX(x);
    }

    public void setPalette(Palette p) {
        this.palette = p;
        ((GradientDrawable) trackWrap.getChildAt(0).getBackground())
                .setStroke(Math.max(1, Math.round(metrics.cqw(0.6f))), p.ink);
        ((GradientDrawable) fill.getBackground()).setColor(p.p);
        ((GradientDrawable) thumb.getBackground()).setColor(p.ink);
        minus.setTextColor(p.p);
        plus.setTextColor(p.p);
        updateThumb();
    }
}
