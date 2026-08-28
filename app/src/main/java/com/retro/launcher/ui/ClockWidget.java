package com.retro.launcher.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
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
 * weather) and a blinking colon. See DESIGN_NOTES §7a and spec §5's
 * "no permission blocks anything" rule — every intent here is best-effort.
 */
public final class ClockWidget extends FrameLayout {

    private final TextView timeView;
    private final TextView dateView;
    private final TextView weatherView;
    private final android.view.View weatherDot;
    private final GradientDrawable background = new GradientDrawable();

    private final Prefs prefs;
    private final Handler blinkHandler = new Handler(Looper.getMainLooper());
    private boolean colonOn = true;
    private Calendar lastTime;

    private Runnable onTimeTap, onDateTap, onWeatherTap;

    private final Runnable blinkTick = new Runnable() {
        @Override public void run() {
            if (prefs.blink()) {
                colonOn = !colonOn;
                if (lastTime != null) renderTime(lastTime);
            }
            blinkHandler.postDelayed(this, 1000L);
        }
    };

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

        LauncherRoot.setNoSwipe(this);

        timeView.setOnClickListener(v -> tap(onTimeTap, new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        dateView.setOnClickListener(v -> tap(onDateTap,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)));
        weatherView.setOnClickListener(v -> {
            if (onWeatherTap != null) onWeatherTap.run();
        });

        blinkHandler.post(blinkTick);
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
        String colon = colonOn ? ":" : " ";
        StringBuilder sb = new StringBuilder();
        sb.append(hour < 10 && !prefs.hour12() ? "0" + hour : String.valueOf(hour));
        sb.append(colon);
        sb.append(minute < 10 ? "0" + minute : String.valueOf(minute));
        if (prefs.seconds()) {
            int sec = c.get(Calendar.SECOND);
            sb.append(colon).append(sec < 10 ? "0" + sec : String.valueOf(sec));
        }
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
