package com.retro.launcher.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.retro.launcher.core.SyntheticWeather;
import com.retro.launcher.core.Weather;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the one weather reading the launcher shows, and decides when it is
 * worth going to the network for a new one.
 *
 * <h3>Refresh policy (spec §3.6)</h3>
 * A reading is good for 30 minutes. Below that nothing is fetched. A forced
 * refresh — tapping the weather line, granting location — bypasses the 30
 * minutes but not the 10-minute floor, so no sequence of taps can turn this
 * into a poll. One attempt per window, and a failed attempt burns the window
 * exactly like a successful one: there are no retry storms.
 *
 * <h3>When there is no reading</h3>
 * {@link #current} is never null, so the sky always has something to draw. It
 * falls back to {@link SyntheticWeather#drift} — a deterministic per-day
 * stand-in — rather than a flat clear sky, so a launcher that never gets
 * location still has cloudy mornings. {@link #hasReading} tells the widget
 * whether that number is real; when it is not, the widget shows "--°" per
 * spec rather than presenting invented weather as fact.
 */
public final class WeatherRepository {

    private static final long FRESH_MS = 30 * 60_000L;
    private static final long FLOOR_MS = 10 * 60_000L;

    /** A reading older than this is not worth restoring at startup — a
     *  temperature from yesterday is misinformation, not a stale cache. */
    private static final long MAX_RESTORE_AGE_MS = 12 * 60 * 60_000L;

    private final Prefs prefs;
    private final WeatherSource source;
    private final LocationSource location;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    private Weather reading;
    private long readingAt;
    private long lastAttemptAt;

    public WeatherRepository(Context context, Prefs prefs) {
        this(prefs, new OpenMeteoWeather(), new LocationSource(context));
    }

    /** Seam for swapping either half in isolation. */
    WeatherRepository(Prefs prefs, WeatherSource source, LocationSource location) {
        this.prefs = prefs;
        this.source = source;
        this.location = location;
        restore();
    }

    /** The reading to draw with. Never null. */
    public Weather current(float hour) {
        if (reading != null) return reading;
        long day = System.currentTimeMillis() / 86_400_000L;
        return SyntheticWeather.at(hour, SyntheticWeather.drift(day, hour), false);
    }

    /** False while {@link #current} is a stand-in rather than a real reading. */
    public boolean hasReading() {
        return reading != null;
    }

    /**
     * Fetches a new reading if the policy allows one, then runs
     * {@code onUpdated} on the main thread — only if something actually
     * changed. Cheap and safe to call every minute.
     *
     * @param force skip the 30-minute freshness check (never the 10-minute floor)
     */
    public void refresh(boolean force, Runnable onUpdated) {
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < FLOOR_MS) return;
        if (!force && reading != null && now - readingAt < FRESH_MS) return;

        double[] fix = location.lastKnown();
        if (fix != null) rememberFix(fix);
        else fix = lastRememberedFix();
        if (fix == null) return;   // never had a fix; nothing to ask about

        if (!inFlight.compareAndSet(false, true)) return;
        lastAttemptAt = now;

        final double lat = fix[0], lon = fix[1];
        new Thread(() -> {
            final Weather fetched = source.fetch(lat, lon);
            main.post(() -> {
                inFlight.set(false);
                if (fetched == null) return;   // silent; the last good value stands
                reading = fetched;
                readingAt = System.currentTimeMillis();
                persist();
                if (onUpdated != null) onUpdated.run();
            });
        }, "weather-fetch").start();
    }

    /**
     * The coarse fix as {@code {latitude, longitude}}, or null if we have
     * never had one. Costs a {@code getLastKnownLocation} read — no provider
     * is ever started — so callers on the minute tick are fine.
     *
     * <p>Public because the fix is not only the weather's business: the sky's
     * moon needs the latitude to know which way up to draw the phase.
     */
    public double[] fix() {
        double[] live = location.lastKnown();
        if (live != null) {
            rememberFix(live);
            return live;
        }
        return lastRememberedFix();
    }

    // ---- last good value, across restarts --------------------------------

    private void persist() {
        prefs.putInt(Prefs.K_WX_TEMP, reading.tempC);
        prefs.putString(Prefs.K_WX_LABEL, reading.label);
        prefs.putFloat(Prefs.K_WX_W, reading.w);
        prefs.putLong(Prefs.K_WX_AT, readingAt);
    }

    private void restore() {
        long at = prefs.getLong(Prefs.K_WX_AT, 0L);
        if (at <= 0L) return;
        if (System.currentTimeMillis() - at > MAX_RESTORE_AGE_MS) return;
        reading = new Weather(
                prefs.getInt(Prefs.K_WX_TEMP, 0),
                prefs.getString(Prefs.K_WX_LABEL, "CLEAR"),
                prefs.getFloat(Prefs.K_WX_W, 0f));
        readingAt = at;
    }

    /** Keeping the last fix means weather survives the location provider
     *  going quiet — common indoors — without us subscribing to updates. */
    private void rememberFix(double[] fix) {
        prefs.putFloat(Prefs.K_WX_LAT, (float) fix[0]);
        prefs.putFloat(Prefs.K_WX_LON, (float) fix[1]);
    }

    private double[] lastRememberedFix() {
        float lat = prefs.getFloat(Prefs.K_WX_LAT, Float.NaN);
        float lon = prefs.getFloat(Prefs.K_WX_LON, Float.NaN);
        if (Float.isNaN(lat) || Float.isNaN(lon)) return null;
        return new double[]{lat, lon};
    }
}
