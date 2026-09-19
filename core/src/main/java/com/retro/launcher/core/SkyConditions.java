package com.retro.launcher.core;

/**
 * Everything {@link SkyRenderer#render} needs for one frame, collapsed into
 * one immutable struct so the method signature does not have to carry nine
 * loose scalars. {@code hour} is the {@link SolarClock}-warped hour driving
 * sky colour and the sun's position; {@code realHour} is the unwarped local
 * hour driving the moon's position on its own clock.
 *
 * <p>{@code moonriseHour} and {@code moonsetHour} are that window's ends as
 * hours relative to today's local midnight — offsets, so they may be negative
 * or past 24 — exactly as {@link LunarMath#moonWindow} reports them.
 */
public final class SkyConditions {

    public final float hour;
    public final float realHour;
    public final float moonriseHour;
    public final float moonsetHour;
    public final float cloudCover;
    public final float precip;
    public final float moonPhase;
    public final Precip type;
    public final boolean thunder;
    public final int tempC;

    /**
     * 2.3.1. How hard the storm is working, 0..1 — {@link ThunderIntensity}'s
     * 0-5 level as a scalar. Zero whenever {@link #thunder} is false.
     *
     * <p>A storm with {@code thunder} true always has a non-zero intensity:
     * the old boolean-only constructor supplies the middle of the scale, so
     * no caller can produce a storm that renders as nothing.
     */
    public final float thunderIntensity;

    /** Boolean-only form, for callers with no intensity to give — the manual
     *  wallpaper override and the tests that predate 2.3.1. Reads as a
     *  mid-scale storm. */
    public SkyConditions(float hour, float realHour, float moonriseHour, float moonsetHour,
                          float cloudCover, float precip, float moonPhase,
                          Precip type, boolean thunder, int tempC) {
        this(hour, realHour, moonriseHour, moonsetHour, cloudCover, precip, moonPhase,
                type, tempC,
                thunder ? ThunderIntensity.scalar(ThunderIntensity.MODERATE) : 0f);
    }

    /** @param thunderIntensity 0..1; anything above 0 is a storm */
    public SkyConditions(float hour, float realHour, float moonriseHour, float moonsetHour,
                          float cloudCover, float precip, float moonPhase,
                          Precip type, int tempC, float thunderIntensity) {
        boolean thunder = thunderIntensity > 0f;
        this.hour = hour;
        this.realHour = realHour;
        this.moonriseHour = moonriseHour;
        this.moonsetHour = moonsetHour;
        this.cloudCover = cloudCover;
        this.precip = precip;
        this.moonPhase = moonPhase;
        this.type = type;
        this.thunder = thunder;
        this.thunderIntensity = SkyRenderer.clamp01(thunderIntensity);
        this.tempC = tempC;
    }
}
