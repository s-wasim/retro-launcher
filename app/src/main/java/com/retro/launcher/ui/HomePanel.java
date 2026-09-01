package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

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
 *
 * A third, conditional block — the default-launcher prompt, top-center —
 * only ever appears while this app isn't the default launcher, and
 * disappears the moment it is; see DESIGN_NOTES §9 delta 20.
 */
public final class HomePanel extends FrameLayout {

    public final ClockWidget clock;
    public final DockView dock;
    private final TextView defaultLauncherPrompt;

    private final Metrics metrics;
    private final int baseOffset;

    private Runnable onRequestDefaultLauncher = () -> {};

    public HomePanel(Context context, Metrics metrics, Prefs prefs, IconSource icons) {
        super(context);
        this.metrics = metrics;
        this.baseOffset = Math.round(metrics.cqw(4f));

        clock = new ClockWidget(context);
        dock = new DockView(context, metrics, icons);
        defaultLauncherPrompt = buildDefaultLauncherPrompt(context);

        addView(clock, topRightParams());
        addView(dock, bottomLeftParams());
        addView(defaultLauncherPrompt, topCenterParams());

        clock.applyMetrics(metrics);

        List<String> savedDock = prefs.dock();
        if (savedDock.isEmpty()) {
            savedDock = DefaultDock.seed(context.getPackageManager());
            prefs.setDock(savedDock);
        }
        dock.setEntries(savedDock);
    }

    private TextView buildDefaultLauncherPrompt(Context context) {
        TextView v = new TextView(context);
        v.setText("SET AS DEFAULT LAUNCHER");
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        v.setAllCaps(true);
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        int padH = Math.round(metrics.cqw(3.5f));
        int padV = Math.round(metrics.cqw(2f));
        v.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(metrics.cqw(1.5f));
        v.setBackground(bg);
        v.setVisibility(GONE);
        LauncherRoot.setNoSwipe(v);
        v.setOnClickListener(view -> onRequestDefaultLauncher.run());
        return v;
    }

    private LayoutParams topCenterParams() {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = baseOffset;
        return lp;
    }

    public void setOnRequestDefaultLauncherListener(Runnable r) { this.onRequestDefaultLauncher = r; }

    /** Shown only while this app is not the current default launcher —
     *  the home screen nags about it, Settings carries the durable status
     *  row instead. See DESIGN_NOTES §9 delta 20. */
    public void setDefaultLauncherPromptVisible(boolean visible) {
        defaultLauncherPrompt.setVisibility(visible ? VISIBLE : GONE);
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
            setMargins(defaultLauncherPrompt, baseOffset + sys.top, 0, 0, 0);
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
        GradientDrawable promptBg = (GradientDrawable) defaultLauncherPrompt.getBackground();
        promptBg.setColor(p.veil());
        promptBg.setStroke(Math.max(1, Math.round(metrics.cqw(0.7f))), p.p);
        defaultLauncherPrompt.setTextColor(p.ink);
    }

    public void setTime(Calendar c) {
        clock.setTime(c);
    }

    public void setWeather(Weather w) {
        clock.setWeather(w);
    }
}
