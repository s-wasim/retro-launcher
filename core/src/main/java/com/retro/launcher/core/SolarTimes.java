package com.retro.launcher.core;

import java.time.LocalDate;

/**
 * One day's sunrise, sunset, the *next* day's sunrise, and (V9) that day's
 * moonrise/moonset — each as a decimal local hour in {@code [0, 24)}, or NaN
 * when unknown — plus the date they belong to. Immutable.
 */
public final class SolarTimes {

    public final float sunriseHour;
    public final float sunsetHour;
    public final float tomorrowSunriseHour;
    public final float moonriseHour;
    public final float moonsetHour;
    public final LocalDate date;

    /** Sun-only convenience constructor — moon fields default to NaN
     *  ("unknown"), the contract every existing caller already relies on. */
    public SolarTimes(float sunriseHour, float sunsetHour, float tomorrowSunriseHour, LocalDate date) {
        this(sunriseHour, sunsetHour, tomorrowSunriseHour, Float.NaN, Float.NaN, date);
    }

    public SolarTimes(float sunriseHour, float sunsetHour, float tomorrowSunriseHour,
                       float moonriseHour, float moonsetHour, LocalDate date) {
        this.sunriseHour = sunriseHour;
        this.sunsetHour = sunsetHour;
        this.tomorrowSunriseHour = tomorrowSunriseHour;
        this.moonriseHour = moonriseHour;
        this.moonsetHour = moonsetHour;
        this.date = date;
    }

    /**
     * True when this day carries a moon window both ends of which are known.
     *
     * <p>Both, deliberately: {@link BodyPath#moonT} needs a rise *and* a set
     * to have a window at all, and answers NaN — "the moon is not up" — if
     * either is missing. A half-known window is therefore worth exactly as
     * much to the renderer as no window, so it reads as absent here too.
     */
    public boolean hasMoonTimes() {
        return !Float.isNaN(moonriseHour) && !Float.isNaN(moonsetHour);
    }

    /** The same day's sun times carrying the given moon window in place of
     *  whatever this instance holds. */
    public SolarTimes withMoonTimes(float moonriseHour, float moonsetHour) {
        return new SolarTimes(sunriseHour, sunsetHour, tomorrowSunriseHour,
                moonriseHour, moonsetHour, date);
    }
}
