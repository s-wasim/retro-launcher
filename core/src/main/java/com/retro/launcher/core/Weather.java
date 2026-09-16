package com.retro.launcher.core;

/** Immutable weather read-out. {@code cloudCover}, {@code precip}, {@code type}
 *  and {@code thunder} are the four independent channels V9 introduced —
 *  cloud cover no longer implies rain, and a thunderstorm no longer implies
 *  rain either. {@code w} is retained, derived from the four channels, so
 *  the persisted cache key and the label bands keep working unchanged. */
public final class Weather {

    public final int tempC;
    public final String label;
    public final float w;
    public final float cloudCover;
    public final float precip;
    public final Precip type;
    public final boolean thunder;
    public final int precipProbability;

    /**
     * 2.3.1. How strong the storm is, 0-5 — see {@link ThunderIntensity}.
     * Always 0 when {@link #thunder} is false and always non-zero when it is
     * true, so the boolean is exactly {@code thunderLevel > 0} and the two
     * cannot drift apart.
     */
    public final int thunderLevel;

    /** Two-arg form kept for every caller that has no intensity to supply —
     *  the manual override and a restored pre-2.3.1 cache. A storm with no
     *  level reads as {@link ThunderIntensity#MODERATE}: it is the middle of
     *  the scale, so an unknown storm looks like a storm without claiming to
     *  be a notable one. */
    public Weather(int tempC, String label, float cloudCover, float precip,
                    Precip type, boolean thunder, int precipProbability) {
        this(tempC, label, cloudCover, precip, type, precipProbability,
                thunder ? ThunderIntensity.MODERATE : ThunderIntensity.NONE);
    }

    /**
     * @param thunderLevel 0-5; clamped, and the sole source of
     *                     {@link #thunder}
     */
    public Weather(int tempC, String label, float cloudCover, float precip,
                    Precip type, int precipProbability, int thunderLevel) {
        this.tempC = tempC;
        this.label = label;
        this.cloudCover = cloudCover;
        this.precip = precip;
        this.type = type;
        this.thunderLevel = ThunderIntensity.clampLevel(thunderLevel);
        this.thunder = this.thunderLevel > ThunderIntensity.NONE;
        this.precipProbability = precipProbability;
        this.w = derive(cloudCover, precip, this.thunder);
    }

    /** The storm's strength as 0..1, for the renderer. 0 when there is no
     *  storm. */
    public float thunderScalar() {
        return ThunderIntensity.scalar(thunderLevel);
    }

    /**
     * Collapses the four independent channels back onto the single 0-1 scalar
     * {@link SyntheticWeather#label} and the persisted cache still use. The
     * dry (cloud-only) and wet (precip-driven) ranges are kept disjoint at
     * {@code 0.62} so a cloud-only sky can never cross into a rain label —
     * the mislabelling V9 fixes.
     */
    public static float derive(float cloudCover, float precip, boolean thunder) {
        if (thunder) return 1.0f;
        if (precip > 0f) return 0.62f + precip * 0.36f;
        return cloudCover * 0.62f;
    }

    /** {@code unit} is "C" or "F"; anything else falls back to Celsius. */
    public int tempIn(String unit) {
        if ("F".equals(unit)) return Math.round(tempC * 9f / 5f + 32f);
        return tempC;
    }
}
