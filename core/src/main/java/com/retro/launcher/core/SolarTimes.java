package com.retro.launcher.core;

import java.time.LocalDate;

/**
 * One day's sunrise, sunset, and the *next* day's sunrise — the last one is
 * what {@link SolarClock}'s night warp needs to know where "night" ends —
 * each as a decimal local hour in {@code [0, 24)}, plus the date they
 * belong to. Immutable.
 */
public final class SolarTimes {

    public final float sunriseHour;
    public final float sunsetHour;
    public final float tomorrowSunriseHour;
    public final LocalDate date;

    public SolarTimes(float sunriseHour, float sunsetHour, float tomorrowSunriseHour, LocalDate date) {
        this.sunriseHour = sunriseHour;
        this.sunsetHour = sunsetHour;
        this.tomorrowSunriseHour = tomorrowSunriseHour;
        this.date = date;
    }
}
