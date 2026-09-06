package com.retro.launcher.core;

/**
 * Everything {@link SkyRenderer#render} needs for one frame, collapsed into
 * one immutable struct so the method signature does not have to carry nine
 * loose scalars. {@code hour} is the {@link SolarClock}-warped hour driving
 * sky colour and the sun's position; {@code realHour} is the unwarped local
 * hour driving the moon's position on its own clock (V9 spec §3).
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

    public SkyConditions(float hour, float realHour, float moonriseHour, float moonsetHour,
                          float cloudCover, float precip, float moonPhase,
                          Precip type, boolean thunder, int tempC) {
        this.hour = hour;
        this.realHour = realHour;
        this.moonriseHour = moonriseHour;
        this.moonsetHour = moonsetHour;
        this.cloudCover = cloudCover;
        this.precip = precip;
        this.moonPhase = moonPhase;
        this.type = type;
        this.thunder = thunder;
        this.tempC = tempC;
    }
}
