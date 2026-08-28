package com.retro.launcher.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.provider.AlarmClock;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.retro.launcher.R;
import com.retro.launcher.core.DateFormatter;
import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.Weather;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.theme.Tint;

import java.util.Calendar;

/**
 * The clock/weather widget: three independent tap regions (time, date,
 * weather). See DESIGN_NOTES §7a and spec §5's "no permission blocks
 * anything" rule — every intent here is best-effort.
 *
 * The colon is always solid and seconds are never shown — this is a fixed
 * design decision (issue #6, 2026-08-28), not a user preference. Do not
 * reintroduce blinking or a seconds display without updating DESIGN_NOTES.md
 * first.
 */
public final class ClockWidget extends FrameLayout {

    private final TextView timeView;
    private final TextView dateView;
    private final TextView weatherView;
    private final android.view.View weatherDot;
    private final android.view.View overLimitMarker;
    private final GradientDrawable background = new GradientDrawable();
    private final GradientDrawable overLimitBg = new GradientDrawable();

    private final Prefs prefs;
    private Calendar lastTime;

    private Runnable onTimeTap, onDateTap, onWeatherTap;

    public ClockWidget(Context context) {
        super(context);
        this.prefs = new Prefs(context);

        LayoutInflater.from(context).inflate(R.layout.widget_clock, this, true);
        timeView    = findViewById(R.id.clock_time);
        dateView    = findViewById(R.id.clock_date);
        weatherView = findViewById(R.id.clock_weather);
        weatherDot  = findViewById(R.id.clock_weather_dot);

        background.setShape(GradientDrawable.RECTANGLE);
        setBackground(background);

        // Persistent over-limit marker (§9 delta 10) — a corner dot, no
        // notification permission needed. Hidden until setOverLimit(true).
        overLimitMarker = new android.view.View(context);
        overLimitBg.setShape(GradientDrawable.OVAL);
        overLimitMarker.setBackground(overLimitBg);
        overLimitMarker.setVisibility(GONE);
        int dot = (int) (8 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams markerLp = new FrameLayout.LayoutParams(dot, dot);
        markerLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        addView(overLimitMarker, markerLp);

        LauncherRoot.setNoSwipe(this);

        timeView.setOnClickListener(v -> tap(onTimeTap, new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        dateView.setOnClickListener(v -> tap(onDateTap,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)));
        weatherView.setOnClickListener(v -> {
            if (onWeatherTap != null) onWeatherTap.run();
        });
    }

    private int borderPx = 2;
    private int borderColor = 0;

    public void applyMetrics(Metrics m) {
        timeView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.textPx(9.4f, 24f));
        dateView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.textPx(3.4f, 10f));
        weatherView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.textPx(3.4f, 10f));
        borderPx = Math.round(Math.max(1, m.cqw(0.7f)));
        background.setStroke(borderPx, borderColor);
    }

    public void setOnTimeTap(Runnable r) { this.onTimeTap = r; }
    public void setOnDateTap(Runnable r) { this.onDateTap = r; }
    public void setOnWeatherTap(Runnable r) { this.onWeatherTap = r; }

    public void setPalette(Palette p) {
        background.setColor(p.veil());
        borderColor = p.p;
        background.setStroke(borderPx, borderColor);
        Tint.setRole(timeView, Tint.ROLE_INK);
        Tint.setRole(dateView, Tint.ROLE_INK);
        Tint.setRole(weatherView, Tint.ROLE_INK);
        Tint.apply(this, p);
        weatherDot.setBackgroundColor(p.a);
        overLimitBg.setColor(p.a);
    }

    /** Persistent over-limit marker — no notification permission needed. */
    public void setOverLimit(boolean over) {
        overLimitMarker.setVisibility(over ? VISIBLE : GONE);
    }

    public void setTime(Calendar c) {
        this.lastTime = c;
        renderTime(c);
        renderDate(c);
    }

    private void renderTime(Calendar c) {
        int hour24 = c.get(Calendar.HOUR_OF_DAY);
        int hour = prefs.hour12() ? (hour24 % 12 == 0 ? 12 : hour24 % 12) : hour24;
        int minute = c.get(Calendar.MINUTE);
        StringBuilder sb = new StringBuilder();
        sb.append(hour < 10 && !prefs.hour12() ? "0" + hour : String.valueOf(hour));
        sb.append(":");
        sb.append(minute < 10 ? "0" + minute : String.valueOf(minute));
        if (prefs.hour12()) {
            sb.append(hour24 < 12 ? " AM" : " PM");
        }
        timeView.setText(sb.toString());
    }

    private void renderDate(Calendar c) {
        String pattern = DateFormatter.PRESETS[Math.min(prefs.fmtIdx(), DateFormatter.PRESETS.length - 1)];
        if (prefs.fmtIdx() >= DateFormatter.PRESETS.length) pattern = prefs.custom();
        int dow0 = c.get(Calendar.DAY_OF_WEEK) - 1;
        dateView.setText(DateFormatter.format(pattern,
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH), dow0));
    }

    public void setWeather(Weather w) {
        if (w == null) {
            weatherView.setText("--°");
            return;
        }
        String unit = prefs.unit();
        weatherView.setText(w.tempIn(unit) + "° " + w.label);
    }

    private void tap(Runnable custom, Intent fallback) {
        if (custom != null) { custom.run(); return; }
        try {
            fallback.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(fallback);
        } catch (ActivityNotFoundException ignored) {
            // No clock/calendar app installed — a missing intent target must
            // never crash the home screen. See spec §6.
        }
    }
}
