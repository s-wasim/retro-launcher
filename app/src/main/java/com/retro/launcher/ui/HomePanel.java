package com.retro.launcher.ui;

import android.content.Context;
import android.os.Build;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.Weather;
import com.retro.launcher.data.DefaultDock;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.icons.IconSource;

import java.util.Calendar;
import java.util.List;

/**
 * Wallpaper stays edge-to-edge behind two floating blocks: the clock widget
 * top-right and the dock bottom-left, both offset 4cqw and inset so neither
 * lands under a notch or the gesture bar. See DESIGN_NOTES §7a.
 */
public final class HomePanel extends FrameLayout {

    public final ClockWidget clock;
    public final DockView dock;

    private final Metrics metrics;
    private final int baseOffset;

    public HomePanel(Context context, Metrics metrics, Prefs prefs, IconSource icons) {
        super(context);
        this.metrics = metrics;
        this.baseOffset = Math.round(metrics.cqw(4f));

        clock = new ClockWidget(context);
        dock = new DockView(context, metrics, icons);

        addView(clock, topRightParams());
        addView(dock, bottomLeftParams());

        clock.applyMetrics(metrics);

        List<String> savedDock = prefs.dock();
        if (savedDock.isEmpty()) {
            savedDock = DefaultDock.seed(context.getPackageManager());
            prefs.setDock(savedDock);
        }
        dock.setEntries(savedDock);
    }

    private LayoutParams topRightParams() {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        lp.topMargin = baseOffset;
        lp.rightMargin = baseOffset;
        return lp;
    }

    private LayoutParams bottomLeftParams() {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        lp.bottomMargin = baseOffset;
        lp.leftMargin = baseOffset;
        return lp;
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
            setMargins(clock, baseOffset + sys.top, 0, baseOffset + sys.right, 0);
            setMargins(dock, 0, baseOffset + sys.bottom, 0, baseOffset + sys.left);
        }
        return super.onApplyWindowInsets(insets);
    }

    /** Absolute (not additive) so repeated inset dispatches never compound. */
    private static void setMargins(android.view.View v, int top, int bottom, int end, int start) {
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        if (lp == null) return;
        if (top != 0)    lp.topMargin = top;
        if (bottom != 0) lp.bottomMargin = bottom;
        if (end != 0)    lp.rightMargin = end;
        if (start != 0)  lp.leftMargin = start;
        v.setLayoutParams(lp);
    }

    public void setPalette(Palette p) {
        clock.setPalette(p);
        dock.setPalette(p);
    }

    public void setTime(Calendar c) {
        clock.setTime(c);
    }

    public void setWeather(Weather w) {
        clock.setWeather(w);
    }
}
