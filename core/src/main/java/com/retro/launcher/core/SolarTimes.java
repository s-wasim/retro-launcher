package com.retro.launcher.core;

import java.time.LocalDate;

/**
 * One day's sunrise, sunset and the *next* day's sunrise — each as a decimal
 * local hour in {@code [0, 24)} — plus (V9) the moon window to draw against,
 * as hours relative to that day's local midnight, which 2.3.4 allows to fall
 * outside {@code [0, 24)} so a window crossing midnight needs no encoding.
 * Any of them is NaN when unknown. Plus the date they belong to. Immutable.
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

    /** The same day's sun times carrying the given moon window in place of
     *  whatever this instance holds. */
    public SolarTimes withMoonTimes(float moonriseHour, float moonsetHour) {
        return new SolarTimes(sunriseHour, sunsetHour, tomorrowSunriseHour,
                moonriseHour, moonsetHour, date);
    }
}
