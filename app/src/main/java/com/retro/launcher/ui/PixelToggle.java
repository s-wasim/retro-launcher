package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;

import java.util.function.Consumer;

/**
 * A two-state pixel switch: a bordered pill track and a square knob that
 * jumps from one end to the other with no easing, matching the prototype's
 * blocky UI rather than a Material switch. See DESIGN_NOTES §7c (the
 * TINT WALLPAPER TO PALETTE row).
 */
public final class PixelToggle extends FrameLayout {

    private final GradientDrawable track = new GradientDrawable();
    private final View knob;
    private final int trackW, trackH, knobSize, knobPad;
    private boolean checked;
    private Palette palette;
    private Consumer<Boolean> listener;

    public PixelToggle(Context context, Metrics metrics) {
        super(context);
        trackW = Math.round(metrics.cqw(11f));
        trackH = Math.round(metrics.cqw(5.5f));
        knobPad = Math.max(1, Math.round(metrics.cqw(0.8f)));
        knobSize = trackH - knobPad * 2;

        track.setShape(GradientDrawable.RECTANGLE);
        track.setCornerRadius(metrics.cqw(1f));
        setBackground(track);

        knob = new View(context);
        GradientDrawable knobBg = new GradientDrawable();
        knobBg.setShape(GradientDrawable.RECTANGLE);
        knobBg.setCornerRadius(Math.max(0f, metrics.cqw(0.6f)));
        knob.setBackground(knobBg);
        FrameLayout.LayoutParams knobParams = new FrameLayout.LayoutParams(knobSize, knobSize);
        knobParams.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        knobParams.setMargins(knobPad, 0, knobPad, 0);
        addView(knob, knobParams);

        setOnClickListener(v -> setChecked(!checked, true));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int knobSpec = MeasureSpec.makeMeasureSpec(knobSize, MeasureSpec.EXACTLY);
        knob.measure(knobSpec, knobSpec);
        setMeasuredDimension(trackW, trackH);
    }

    public void setPalette(Palette p) {
        this.palette = p;
        refreshColors();
    }

    public void setChecked(boolean value) { setChecked(value, false); }

    public boolean isChecked() { return checked; }

    public void setOnCheckedChangeListener(Consumer<Boolean> l) { this.listener = l; }

    private void setChecked(boolean value, boolean notify) {
        this.checked = value;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) knob.getLayoutParams();
        lp.gravity = Gravity.CENTER_VERTICAL | (checked ? Gravity.END : Gravity.START);
        knob.setLayoutParams(lp);
        refreshColors();
        if (notify && listener != null) listener.accept(value);
    }

    private void refreshColors() {
        if (palette == null) return;
        track.setStroke(Math.max(1, knobPad), palette.p);
        track.setColor(checked ? palette.p : palette.s);
        ((GradientDrawable) knob.getBackground()).setColor(palette.h);
    }
}
