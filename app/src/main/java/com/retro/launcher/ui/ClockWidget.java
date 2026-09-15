package com.retro.launcher.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.AlarmClock;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.retro.launcher.R;
import com.retro.launcher.core.ClockText;
import com.retro.launcher.core.DateFormatter;
import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.Weather;
import com.retro.launcher.core.ZoneLabel;
import com.retro.launcher.core.ZoneList;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.theme.Tint;
import com.retro.launcher.util.Haptics;
import com.retro.launcher.util.Launch;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * The clock/weather widget: three independent tap regions (time, date,
 * weather). See DESIGN_NOTES §7a; per the "no permission blocks anything"
 * rule, every intent here is best-effort.
 *
 * The colon is always solid and seconds are never shown — this is a fixed
 * design decision (issue #6, 2026-08-28), not a user preference. Do not
 * reintroduce blinking or a seconds display without updating DESIGN_NOTES.md
 * first.
 *
 * <p><b>2.2.1 adds a fourth region: the second time zone.</b> The big line
 * stays the device's own time — that is the one the home screen exists to
 * answer — and a smaller line under it carries one saved zone: its city, its
 * time, and a {@code +1}/{@code -1} when the date there is not today's.
 * Tapping it cycles through the saved shortlist; long-pressing opens the
 * picker. It is {@code GONE} unless the toggle is on and at least one zone is
 * saved, so a user who never opens that setting sees the clock exactly as it
 * was.
 */
public final class ClockWidget extends FrameLayout {

    /** Null until HomeActivity supplies one. Every call site null-checks
     *  rather than requiring construction order to guarantee it. */
    private Haptics haptics;

    public void setHaptics(Haptics haptics) { this.haptics = haptics; }

    private void tick() { if (haptics != null) haptics.click(); }
    private void thud() { if (haptics != null) haptics.longPress(); }

    private final TextView timeView;
    private final TextView dateView;
    private final TextView weatherView;
    private final TextView zoneNameView;
    private final TextView zoneTimeView;
    private final TextView zoneDayView;
    private final android.view.View zoneRow;
    private final android.view.View weatherDot;
    private final android.view.View overLimitMarker;
    private final GradientDrawable background = new GradientDrawable();
    private final GradientDrawable overLimitBg = new GradientDrawable();

    private final Prefs prefs;
    private Calendar lastTime;

    private Runnable onNoWeatherApp;
    private Runnable onWeatherLongPress;
    private Runnable onZoneLongPress;

    public ClockWidget(Context context) {
        super(context);
        this.prefs = new Prefs(context);

        LayoutInflater.from(context).inflate(R.layout.widget_clock, this, true);
        timeView    = findViewById(R.id.clock_time);
        dateView    = findViewById(R.id.clock_date);
        weatherView = findViewById(R.id.clock_weather);
        weatherDot  = findViewById(R.id.clock_weather_dot);
        zoneRow      = findViewById(R.id.clock_zone_row);
        zoneNameView = findViewById(R.id.clock_zone_name);
        zoneTimeView = findViewById(R.id.clock_zone_time);
        zoneDayView  = findViewById(R.id.clock_zone_day);

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

        timeView.setOnClickListener(v -> { tick(); openClock(); });
        dateView.setOnClickListener(v -> { tick(); openCalendar(); });
        weatherView.setOnClickListener(v -> { tick(); openWeather(); });
        weatherView.setOnLongClickListener(v -> {
            thud();
            if (onWeatherLongPress != null) onWeatherLongPress.run();
            return true;
        });

        // 2.2.1. Tap cycles the saved shortlist, long-press opens the picker.
        // The gestures are the same shape as the weather region's — tap does
        // the thing you want most often, long-press goes and configures it —
        // so there is nothing new to learn.
        zoneRow.setOnClickListener(v -> { tick(); cycleZone(); });
        zoneRow.setOnLongClickListener(v -> {
            thud();
            if (onZoneLongPress != null) onZoneLongPress.run();
            return true;
        });
    }

    /** Opens the zone picker; null until HomeActivity supplies one. */
    public void setOnZoneLongPress(Runnable r) { this.onZoneLongPress = r; }

    /**
     * Moves the second line to the next saved zone and redraws it.
     *
     * <p>A no-op with fewer than two zones saved rather than a redraw of the
     * same thing, so a single-zone setup does not answer a tap with a haptic
     * tick and no visible change.
     */
    private void cycleZone() {
        List<String> zones = prefs.secondZones();
        if (zones.size() < 2) return;
        prefs.setSecondZoneIndex(ZoneList.next(prefs.secondZoneIndex(), zones.size()));
        if (lastTime != null) renderSecondZone(lastTime);
    }

    /** Clock apps that ship without declaring ACTION_SHOW_ALARMS, in rough
     *  order of how many devices carry them. */
    private static final String[] CLOCK_PACKAGES = {
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.oneplus.deskclock",
            "com.coloros.alarmclock",
            "com.oppo.alarmclock",
            "com.android.BBKClock",
            "com.huawei.deskclock",
            "com.transsion.deskclock",
            "com.asus.deskclock",
            "com.zui.deskclock",
            "com.lge.clock",
            "com.htc.android.worldclock",
            "com.sonyericsson.organizer",
    };

    private static final String[] CALENDAR_PACKAGES = {
            "com.google.android.calendar",
            "com.android.calendar",
            "com.samsung.android.calendar",
    };

    /** Android has no weather intent or category, so the weather region can
     *  only go by package name (DESIGN_NOTES §9 row 8). */
    private static final String[] WEATHER_PACKAGES = {
            "com.google.android.apps.weather",
            "com.sec.android.daemonapp",
            "com.samsung.android.weather",
            "com.miui.weather2",
            "com.huawei.android.totemweather",
            "com.coloros.weather2",
            "com.oneplus.weather",
            "com.weather.Weather",
            "com.accuweather.android",
    };

    private void openClock() {
        Launch.first(getContext(),
                new Intent(AlarmClock.ACTION_SHOW_ALARMS),
                Launch.packageLauncher(getContext(), CLOCK_PACKAGES));
    }

    /** DESIGN_NOTES §9 row 8: open a weather app if one is installed. Where
     *  row 8 said "no-op if none found", the launcher now has a reading of its
     *  own to refresh instead — see {@link #setOnNoWeatherApp}. */
    private void openWeather() {
        boolean opened = Launch.first(getContext(),
                Launch.packageLauncher(getContext(), WEATHER_PACKAGES));
        if (!opened && onNoWeatherApp != null) onNoWeatherApp.run();
    }

    private void openCalendar() {
        long now = System.currentTimeMillis();
        Launch.first(getContext(),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
                new Intent(Intent.ACTION_VIEW,
                        Uri.parse("content://com.android.calendar/time/" + now)),
                Launch.packageLauncher(getContext(), CALENDAR_PACKAGES));
    }

    private int borderPx = 2;
    private int borderColor = 0;

    public void applyMetrics(Metrics m) {
        timeView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.textPx(9.4f, 24f));
        dateView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.textPx(3.4f, 10f));
        weatherView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.textPx(3.4f, 10f));
        // The second zone sits at the date and weather lines' size, not the
        // big one's: it is a supporting reading, and matching the local time
        // would make the eye ask which of the two is "the" time.
        zoneNameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.textPx(3.4f, 10f));
        zoneTimeView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.textPx(3.4f, 10f));
        zoneDayView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.textPx(2.8f, 8f));
        borderPx = Math.round(Math.max(1, m.cqw(0.7f)));
        background.setStroke(borderPx, borderColor);
    }

    /** Runs when the weather region was tapped and no weather app is
     *  installed to open. */
    public void setOnNoWeatherApp(Runnable r) { this.onNoWeatherApp = r; }

    /** Long-press always forces a fresh reading, whether or not a weather app
     *  is installed — the tap is for opening one, and there was no gesture
     *  that simply meant "go and look again". */
    public void setOnWeatherLongPress(Runnable r) { this.onWeatherLongPress = r; }

    public void setPalette(Palette p) {
        background.setColor(p.veil());
        borderColor = p.p;
        background.setStroke(borderPx, borderColor);
        Tint.setRole(timeView, Tint.ROLE_INK);
        Tint.setRole(dateView, Tint.ROLE_INK);
        Tint.setRole(weatherView, Tint.ROLE_INK);
        Tint.setRole(zoneTimeView, Tint.ROLE_INK);
        Tint.apply(this, p);
        // The city and the day marker take the accent rather than the ink, so
        // the eye reads the second line as "elsewhere" at a glance instead of
        // parsing it as a second local reading.
        zoneNameView.setTextColor(p.a);
        zoneDayView.setTextColor(p.a);
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
        renderSecondZone(c);
    }

    private void renderTime(Calendar c) {
        timeView.setText(ClockText.time(
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), prefs.hour12()));
    }

    /**
     * The second line, or nothing at all.
     *
     * <p>Built from the same instant as the local line — {@code c}'s millis
     * rather than a fresh {@code System.currentTimeMillis()} — so the two can
     * never disagree about which minute it is, which is precisely the bug that
     * would show up as a bogus {@code +1} for one tick a day.
     *
     * <p>A zone id the device does not recognise is skipped rather than shown:
     * {@code TimeZone.getTimeZone} answers an unknown id with GMT, silently,
     * so a list carried over from another device would otherwise put a
     * confident wrong time on the home screen.
     */
    private void renderSecondZone(Calendar c) {
        List<String> zones = prefs.secondZones();
        if (!prefs.secondZoneEnabled() || zones.isEmpty()) {
            zoneRow.setVisibility(GONE);
            return;
        }

        String zoneId = zones.get(ZoneList.clamp(prefs.secondZoneIndex(), zones.size()));
        TimeZone zone = resolve(zoneId);
        if (zone == null) {
            zoneRow.setVisibility(GONE);
            return;
        }

        Calendar there = Calendar.getInstance(zone);
        there.setTimeInMillis(c.getTimeInMillis());

        zoneNameView.setText(ZoneLabel.of(zoneId));
        zoneTimeView.setText(ClockText.time(
                there.get(Calendar.HOUR_OF_DAY), there.get(Calendar.MINUTE), prefs.hour12()));
        zoneDayView.setText(ZoneLabel.dayMarker(ClockText.dayDelta(
                c.get(Calendar.YEAR), c.get(Calendar.DAY_OF_YEAR),
                there.get(Calendar.YEAR), there.get(Calendar.DAY_OF_YEAR))));
        zoneRow.setVisibility(VISIBLE);
    }

    /** @return the zone, or null when the device does not know this id —
     *          {@code getTimeZone} returns GMT for an unknown id rather than
     *          failing, so the id has to be checked against the table. */
    private static TimeZone resolve(String zoneId) {
        if (zoneId == null || zoneId.isEmpty()) return null;
        TimeZone zone = TimeZone.getTimeZone(zoneId);
        if (zone == null) return null;
        return zone.getID().equals(zoneId) ? zone : null;
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
}
