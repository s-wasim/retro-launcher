package com.retro.launcher.ui;

import android.content.Context;
import android.widget.LinearLayout;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.UsageMath;
import com.retro.launcher.util.Haptics;

import java.util.function.IntConsumer;

/**
 * The daily-limit control from DESIGN_NOTES §7d, now a thin configuration of
 * {@link PixelSlider} (V9 §7b) over {@code [LIMIT_MIN, LIMIT_MAX]} stepped by
 * {@code LIMIT_STEP} — no behaviour change from before the extraction.
 */
public final class LimitSlider extends LinearLayout {

    private final PixelSlider slider;

    public LimitSlider(Context context, Metrics metrics) {
        super(context);
        slider = new PixelSlider(context, metrics,
                UsageMath.LIMIT_MIN, UsageMath.LIMIT_MAX, UsageMath.LIMIT_STEP,
                step -> (step > 0 ? "+" : "−") + Math.abs(step));
        slider.setValue(240);
        addView(slider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    public void setHaptics(Haptics haptics) { slider.setHaptics(haptics); }

    public void setValue(int minutes) { slider.setValue(minutes); }

    public int getValue() { return Math.round(slider.getValue()); }

    public void setOnValueChangeListener(IntConsumer l) {
        slider.setOnValueChangeListener(v -> l.accept(Math.round(v)));
    }

    public void setPalette(Palette p) { slider.setPalette(p); }
}
