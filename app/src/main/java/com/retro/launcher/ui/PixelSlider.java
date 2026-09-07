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
import com.retro.launcher.util.Haptics;

import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * A generic {@code -step}/track/{@code +step} pixel slider over
 * {@code [min, max]}, snapped to {@code step}. Extracted from
 * {@link LimitSlider} (V9 §7b) so the manual-wallpaper-override controls
 * (percent, degrees, hours, a 0-1 fraction) can reuse the same widget with no
 * behaviour change to the existing daily-limit control.
 */
public final class PixelSlider extends LinearLayout {

    private Haptics haptics;
    public void setHaptics(Haptics haptics) { this.haptics = haptics; }
    private void tick() { if (haptics != null) haptics.click(); }

    private final Metrics metrics;
    private final float min, max, step;
    private final TextView minus, plus;
    private final View fill, thumb;
    private final FrameLayout trackWrap;

    private float value;
    private Consumer<Float> listener = v -> {};

    public PixelSlider(Context context, Metrics metrics, float min, float max, float step,
                        IntFunction<String> stepLabel) {
        super(context);
        this.metrics = metrics;
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = min;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        LauncherRoot.setNoSwipe(this);

        minus = stepButton(stepLabel.apply(-Math.round(step)));
        plus = stepButton(stepLabel.apply(Math.round(step)));

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

        minus.setOnClickListener(v -> setValue(value - step));
        plus.setOnClickListener(v -> setValue(value + step));
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
                setValue(min + frac * (max - min));
                return true;
        }
        return false;
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

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

    private float snap(float raw) {
        float clamped = Math.max(min, Math.min(max, raw));
        return min + Math.round((clamped - min) / step) * step;
    }

    public void setValue(float raw) {
        float next = snap(raw);
        boolean changed = next != value;
        value = next;
        updateThumb();
        if (changed) {
            tick();
            listener.accept(value);
        }
    }

    public float getValue() { return value; }

    public void setOnValueChangeListener(Consumer<Float> l) { this.listener = l; }

    private void updateThumb() {
        int w = trackWrap.getWidth();
        if (w <= 0) return;
        float frac = (value - min) / (max - min);
        int knob = thumb.getLayoutParams().width;
        int usable = w - knob;
        int x = Math.round(frac * usable);

        FrameLayout.LayoutParams fillLp = (FrameLayout.LayoutParams) fill.getLayoutParams();
        fillLp.width = x + knob / 2;
        fill.setLayoutParams(fillLp);

        thumb.setTranslationX(x);
    }

    public void setPalette(Palette p) {
        ((GradientDrawable) trackWrap.getChildAt(0).getBackground())
                .setStroke(Math.max(1, Math.round(metrics.cqw(0.6f))), p.ink);
        ((GradientDrawable) fill.getBackground()).setColor(p.p);
        ((GradientDrawable) thumb.getBackground()).setColor(p.ink);
        minus.setTextColor(p.p);
        plus.setTextColor(p.p);
        updateThumb();
    }
}
