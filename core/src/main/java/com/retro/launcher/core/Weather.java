package com.retro.launcher.core;

/** Immutable weather read-out: temperature, a condition label, and the {@code w}
 *  scalar (0-1) that also drives the sky's clouds and precipitation. */
public final class Weather {

    public final int tempC;
    public final String label;
    public final float w;

    public Weather(int tempC, String label, float w) {
        this.tempC = tempC;
        this.label = label;
        this.w = w;
    }

    /** {@code unit} is "C" or "F"; anything else falls back to Celsius. */
    public int tempIn(String unit) {
        if ("F".equals(unit)) return Math.round(tempC * 9f / 5f + 32f);
        return tempC;
    }
}
