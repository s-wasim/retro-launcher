package com.retro.launcher.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.retro.launcher.core.LunarMath;
import com.retro.launcher.core.Precip;
import com.retro.launcher.core.SolarMath;
import com.retro.launcher.core.SolarTimes;
import com.retro.launcher.core.SyntheticWeather;
import com.retro.launcher.core.Weather;
import com.retro.launcher.core.WeatherFetch;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the one weather reading the launcher shows, and decides when it is
 * worth going to the network for a new one.
 *
 * <h3>Refresh policy</h3>
 * A reading is good for 30 minutes. Below that nothing is fetched. A forced
 * refresh — tapping the weather line, granting location — bypasses the 30
 * minutes but not the 10-minute floor, so no sequence of taps can turn this
 * into a poll. One attempt per window, and a failed attempt burns the window
 * exactly like a successful one: there are no retry storms.
 * A fetch now begins by asking a provider for one current fix, falling back
 * to the last known fix and then to the remembered one; the 10-minute floor
 * bounds how often that can happen.
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

        if (!inFlight.compareAndSet(false, true)) return;
        lastAttemptAt = now;

        // Stage one: ask a provider for a current fix. The attempt window is
        // already burned above, so this costs at most one location request
        // every ten minutes, and only when a fetch was going to happen anyway.
        location.requestFresh(fresh -> {
            double[] fix = fresh;
            if (fix == null) fix = location.lastKnown();
            if (fix != null) rememberFix(fix);
            else fix = lastRememberedFix();

            if (fix == null) {          // never had a fix; nothing to ask about
                inFlight.set(false);
                return;
            }

            final double lat = fix[0], lon = fix[1];
            new Thread(() -> {
                final WeatherFetch fetched = source.fetch(lat, lon);
                main.post(() -> {
                    inFlight.set(false);
                    if (fetched == null || fetched.weather == null) return;   // silent; the last good value stands
                    reading = fetched.weather;
                    readingAt = System.currentTimeMillis();
                    persist();
                    if (fetched.solarTimes != null) persistSolarTimes(fetched.solarTimes);
                    if (onUpdated != null) onUpdated.run();
                });
            }, "weather-fetch").start();
        });
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

    /**
     * Today's sunrise, sunset, tomorrow's sunrise, and the moon window the
     * sky needs to place the disc. The sun half comes from the cache (today's
     * entry, if present) or a local {@link SolarMath} computation from the
     * current fix, cached for the rest of the day; the moon half is computed
     * fresh on every call. Null when there is no fix and no cached day, which
     * is exactly the signal {@link com.retro.launcher.core.SolarClock} treats
     * as "no data — draw the fixed table". Safe to call from the main thread:
     * the cache read is a SharedPreferences read, and SolarMath and
     * {@link LunarMath} are pure local arithmetic, not network calls.
     */
    public SolarTimes solarTimes() {
        LocalDate today = LocalDate.now();
        SolarTimes cached = restoreSolarTimes(today);

        double[] f = fix();
        if (f == null) return cached;   // sun only, or nothing at all

        SolarTimes sun = cached;
        if (sun == null) {
            sun = SolarMath.sunTimes((float) f[0], (float) f[1], today, ZoneId.systemDefault());
            if (sun == null) return null;
            persistSolarTimes(sun);
        }
        return withMoonWindow(sun, f[0], f[1]);
    }

    /**
     * {@code day} carrying the moon window that covers this moment, or
     * unchanged when the moon is neither up nor due to rise.
     *
     * <h3>Why this is computed and not stored</h3>
     * A moon window is a property of an instant, not of a calendar day. The
     * moon rises about 50 minutes later each time, so roughly half of all
     * days hold the tail of one up-period in the small hours and the start of
     * the next later on, and no single stored pair describes both — see
     * {@link LunarMath#moonWindow}. Recomputing costs a few dozen trig calls
     * with no I/O and no allocation worth counting, which is less than the
     * SharedPreferences read that caching it needed.
     *
     * <p>It also retires a whole class of bug at the root. The network cannot
     * supply a moon window — Open-Meteo's daily block has sunrise and sunset
     * and no moon at all, so
     * {@link com.retro.launcher.core.WeatherParser#parseSolarTimes} can only
     * build the sun-only form, whose moon fields are NaN. Persisting that as
     * it came overwrote the computed window with the NaN pair, and because
     * the cache was keyed by date alone the NaN pair was then handed back for
     * the rest of the day while every subsequent fetch renewed it: the moon
     * vanished from the sky entirely and stayed gone. 2.3.2 patched that by
     * repairing the cached day; 2.3.4 stops storing the thing that could be
     * wrong.
     */
    private static SolarTimes withMoonWindow(SolarTimes day, double lat, double lon) {
        LunarMath.LunarTimes moon = LunarMath.moonWindow(
                (float) lat, (float) lon, System.currentTimeMillis(), ZoneId.systemDefault());
        if (moon == null) return day;
        return day.withMoonTimes(moon.moonriseHour, moon.moonsetHour);
    }

    /** The sun half only. The moon window is never stored — see
     *  {@link #withMoonWindow}. */
    private void persistSolarTimes(SolarTimes t) {
        prefs.putLong(Prefs.K_SOL_EPOCH_DAY, t.date.toEpochDay());
        prefs.putFloat(Prefs.K_SOL_SUNRISE, t.sunriseHour);
        prefs.putFloat(Prefs.K_SOL_SUNSET, t.sunsetHour);
        prefs.putFloat(Prefs.K_SOL_TOMORROW, t.tomorrowSunriseHour);
    }

    private SolarTimes restoreSolarTimes(LocalDate today) {
        long storedEpochDay = prefs.getLong(Prefs.K_SOL_EPOCH_DAY, Long.MIN_VALUE);
        if (storedEpochDay != today.toEpochDay()) return null; // missing, or a stale past date
        return new SolarTimes(
                prefs.getFloat(Prefs.K_SOL_SUNRISE, Float.NaN),
                prefs.getFloat(Prefs.K_SOL_SUNSET, Float.NaN),
                prefs.getFloat(Prefs.K_SOL_TOMORROW, Float.NaN),
                today);
    }

    // ---- last good value, across restarts --------------------------------

    private void persist() {
        prefs.putInt(Prefs.K_WX_TEMP, reading.tempC);
        prefs.putString(Prefs.K_WX_LABEL, reading.label);
        prefs.putFloat(Prefs.K_WX_W, reading.w);
        prefs.putFloat(Prefs.K_WX_CLOUD, reading.cloudCover);
        prefs.putFloat(Prefs.K_WX_PRECIP, reading.precip);
        prefs.putInt(Prefs.K_WX_TYPE, reading.type.ordinal());
        prefs.putBool(Prefs.K_WX_THUNDER, reading.thunder);
        prefs.putInt(Prefs.K_WX_THUNDER_LVL, reading.thunderLevel);
        prefs.putInt(Prefs.K_WX_PROB, reading.precipProbability);
        prefs.putLong(Prefs.K_WX_AT, readingAt);
    }

    private void restore() {
        long at = prefs.getLong(Prefs.K_WX_AT, 0L);
        if (at <= 0L) return;
        if (System.currentTimeMillis() - at > MAX_RESTORE_AGE_MS) return;

        int tempC = prefs.getInt(Prefs.K_WX_TEMP, 0);
        String label = prefs.getString(Prefs.K_WX_LABEL, "CLEAR");
        float legacyW = prefs.getFloat(Prefs.K_WX_W, 0f);

        // A reading cached before V9 has no channel keys — K_WX_CLOUD
        // defaults to NaN, the marker that this is a one-time migration from
        // the old single-scalar reading rather than a genuine zero cover.
        float cloudCover = prefs.getFloat(Prefs.K_WX_CLOUD, Float.NaN);
        if (Float.isNaN(cloudCover)) {
            cloudCover = com.retro.launcher.core.SkyRenderer.smooth(0.10f, 0.66f, legacyW);
            float precip = com.retro.launcher.core.SkyRenderer.smooth(0.62f, 0.98f, legacyW);
            boolean thunder = legacyW >= 0.95f;
            Precip type = precip > 0f ? Precip.RAIN : Precip.NONE;
            reading = new Weather(tempC, label, cloudCover, precip, type, thunder, 0);
        } else {
            float precip = prefs.getFloat(Prefs.K_WX_PRECIP, 0f);
            Precip type = Precip.values()[prefs.getInt(Prefs.K_WX_TYPE, Precip.NONE.ordinal())];
            boolean thunder = prefs.getBool(Prefs.K_WX_THUNDER, false);
            int prob = prefs.getInt(Prefs.K_WX_PROB, 0);
            // A cache written before 2.3.1 has the boolean but no level. -1
            // is the marker for that, and the boolean-taking constructor
            // supplies the mid-scale default rather than a storm that
            // renders as nothing.
            int level = prefs.getInt(Prefs.K_WX_THUNDER_LVL, -1);
            reading = level < 0
                    ? new Weather(tempC, label, cloudCover, precip, type, thunder, prob)
                    : new Weather(tempC, label, cloudCover, precip, type, prob,
                            thunder ? Math.max(1, level) : 0);
        }
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
