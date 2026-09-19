package com.retro.launcher.core;

/**
 * How strong a thunderstorm is, on a 0-5 scale, from what Open-Meteo actually
 * gives us for free.
 *
 * <h3>What the API offers, and what it does not</h3>
 * There is no lightning-strike count and no flash-density field on the free
 * global forecast endpoint. {@code lightning_potential} (LPI, J/kg) exists and
 * is the most direct measure there is, but only on the {@code dwd-icon}
 * endpoint and only over Central Europe and North America — a second request,
 * for a number most of the world would get as null. It is deliberately not
 * used. Two things that <em>are</em> free, global and on the request we
 * already make:
 *
 * <ul>
 *   <li><b>CAPE</b> — Convective Available Potential Energy, in J/kg. The
 *       standard physical measure of how much energy a storm has to work
 *       with, and what forecasters actually grade severity on. It rides along
 *       on the existing {@code current=} list, so it costs no extra call.</li>
 *   <li><b>The WMO code itself.</b> 95, 96 and 99 are not one condition:
 *       95 is a slight or moderate thunderstorm, 96 adds slight hail, 99 adds
 *       heavy hail. Hail is the field's own severity marker. Before 2.3.1
 *       {@code WeatherParser} mapped all three onto one identical
 *       {@code Condition}, which threw that gradation away.</li>
 * </ul>
 *
 * <h3>CAPE grades a storm; it never declares one</h3>
 * This is the load-bearing rule here. CAPE is <em>potential</em> energy — the
 * fuel available to convection, not an observation that anything has ignited.
 * A hot, humid, perfectly clear afternoon routinely reads 2000+ J/kg with no
 * storm anywhere. So {@link #levelFor} returns {@link #NONE} unless the WMO
 * code says there is a thunderstorm, whatever CAPE says. Reading it the other
 * way round would put lightning on the wallpaper on a cloudless day, which is
 * worse than having no intensity at all.
 *
 * <h3>The floor, for when CAPE is missing</h3>
 * When {@code cape} is absent — an older cached reading, a model that did not
 * supply it, a partial response — the code's own ordinal is used instead, so
 * a purely textual reading still yields a realistic number rather than a
 * degenerate one. When both are present the answer is the <em>higher</em> of
 * the two: a storm reporting heavy hail is severe regardless of what the
 * CAPE field says, because hail is an observation and CAPE is a forecast
 * quantity.
 *
 * <p>The thresholds are the conventional operational bands (SPC/NWS
 * convective outlook language), not invented ones.
 */
public final class ThunderIntensity {

    private ThunderIntensity() {}

    /** No thunderstorm. The only level for which {@code Weather.thunder} is
     *  false, so the two can never disagree. */
    public static final int NONE = 0;
    /** Thunder reported, but very little energy behind it. */
    public static final int DISTANT = 1;
    /** A light thunderstorm. */
    public static final int LIGHT = 2;
    /** A moderate thunderstorm. */
    public static final int MODERATE = 3;
    /** Severe — the band where hail is reported. */
    public static final int SEVERE = 4;
    /** Extreme. */
    public static final int EXTREME = 5;

    public static final int MAX = EXTREME;

    // ---- CAPE bands, J/kg -------------------------------------------------

    /** Below this, there is thunder but almost no convective energy. */
    public static final float CAPE_DISTANT = 500f;
    /** Conventional floor of "moderate instability". */
    public static final float CAPE_LIGHT = 1500f;
    /** Conventional floor of "strong instability". */
    public static final float CAPE_MODERATE = 2500f;
    /** Conventional floor of "extreme instability". */
    public static final float CAPE_SEVERE = 4000f;

    /**
     * The storm's level.
     *
     * @param thunder whether the WMO code reported a thunderstorm at all.
     *                False short-circuits to {@link #NONE}: see the class note
     *                on why CAPE may not declare a storm.
     * @param wmoCode the raw WMO 4677 code, used for its hail gradation
     * @param cape    CAPE in J/kg, or null when the field was absent
     */
    public static int levelFor(boolean thunder, int wmoCode, Float cape) {
        if (!thunder) return NONE;

        int fromCode = codeFloor(wmoCode);
        if (cape == null || cape.isNaN() || cape < 0f) return fromCode;

        return Math.max(fromCode, capeLevel(cape));
    }

    /**
     * The level a WMO code implies on its own.
     *
     * <p>{@link #LIGHT} for a plain thunderstorm rather than
     * {@link #MODERATE}: 95 covers "slight <em>or</em> moderate", and
     * understating a storm we cannot measure is the safer error — the sky
     * flickers less than reality rather than more.
     */
    public static int codeFloor(int wmoCode) {
        switch (wmoCode) {
            case 95: return LIGHT;    // thunderstorm, slight or moderate
            case 96: return SEVERE;   // thunderstorm with slight hail
            case 99: return EXTREME;  // thunderstorm with heavy hail
            default: return NONE;
        }
    }

    /** The level CAPE alone implies, ignoring whether a storm was reported. */
    public static int capeLevel(float cape) {
        if (cape < CAPE_DISTANT)  return DISTANT;
        if (cape < CAPE_LIGHT)    return LIGHT;
        if (cape < CAPE_MODERATE) return MODERATE;
        if (cape < CAPE_SEVERE)   return SEVERE;
        return EXTREME;
    }

    /**
     * The level as a 0..1 scalar for the renderer to interpolate on.
     *
     * <p>{@link #NONE} is exactly 0 and {@link #EXTREME} exactly 1, so a
     * caller can multiply by it without special-casing either end. The steps
     * in between are linear — the perceptual shaping belongs in the renderer,
     * which is where the strike rate and the flash curve are chosen.
     */
    public static float scalar(int level) {
        return clampLevel(level) / (float) MAX;
    }

    /** Brings any stored or computed level into 0..{@link #MAX}. Used on
     *  every read of a persisted value, which outlives the code that wrote
     *  it. */
    public static int clampLevel(int level) {
        if (level < NONE) return NONE;
        return Math.min(level, MAX);
    }

    /** A short name, for logs and for the manual override's own labelling.
     *  Not shown on the clock face — 2.3.1 keeps the reading internal. */
    public static String name(int level) {
        switch (clampLevel(level)) {
            case DISTANT:  return "DISTANT";
            case LIGHT:    return "LIGHT";
            case MODERATE: return "MODERATE";
            case SEVERE:   return "SEVERE";
            case EXTREME:  return "EXTREME";
            default:       return "NONE";
        }
    }
}
