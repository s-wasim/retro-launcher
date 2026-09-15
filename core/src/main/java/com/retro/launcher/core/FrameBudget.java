package com.retro.launcher.core;

/**
 * How often the sky is worth redrawing, given what is actually in it.
 *
 * <p>Until 2.1.3 {@code SkyView} ran a fixed ~30fps for as long as the
 * launcher was foreground: a 108x230 buffer, several full-buffer passes,
 * thirty times a second, forever. On a clear afternoon almost none of that
 * produced a different frame, so the cost was thirty CPU wake-ups a second to
 * redraw the same picture — which is the launcher's battery footprint
 * essentially in its entirety. Nothing about the renderer needed to change;
 * what it needed was to be asked less often.
 *
 * <p>The two rates are derived from the speeds the layers in
 * {@link SkyRenderer} actually move at, and the fastest layer present wins:
 *
 * <table>
 *   <caption>Measured layer speeds against the buffer</caption>
 *   <tr><th>Layer</th><th>Speed</th><th>At {@link #FAST_MS}</th>
 *       <th>At {@link #SLOW_MS}</th></tr>
 *   <tr><td>Rain</td><td>124–238 px/s</td><td>5–10 px/frame</td>
 *       <td>31–60 px/frame</td></tr>
 *   <tr><td>Snow</td><td>14–34 px/s</td><td>0.6–1.4 px/frame</td>
 *       <td>3.5–8.5 px/frame</td></tr>
 *   <tr><td>Cloud drift (calm)</td><td>0.3–0.9 px/s</td><td>0.01–0.04</td>
 *       <td>0.08–0.22 px/frame</td></tr>
 *   <tr><td>Star twinkle</td><td>3.7 s period</td><td>88 samples/cycle</td>
 *       <td>15 samples/cycle</td></tr>
 *   <tr><td>Sun ray pulse</td><td>3.9 s period, 4 integer lengths</td>
 *       <td>93</td><td>16 samples/cycle</td></tr>
 * </table>
 *
 * <p>Precipitation is the only thing that needs frames: a snowflake is one
 * pixel and at the slow rate it would jump several of its own widths, which
 * reads as a dotted trail rather than a fall. Everything else is far slower
 * than it looks. A cloud crosses a pixel roughly every two seconds, so
 * drawing it four times a second versus thirty produces the identical
 * sequence of frames — the step happens when the cloud crosses the pixel, not
 * when we ask. The twinkle and the ray pulse are the binding constraint on the
 * slow rate, and 15 samples a cycle is comfortably above where either starts
 * to step.
 *
 * <p>So there is no middle tier: a scene either has precipitation in it or it
 * does not. An earlier cut of this class had one for clouds, on the assumption
 * that visible drift needed more than 4fps. The numbers above say it does not.
 *
 * <p>Every threshold here mirrors the gate its layer applies in
 * {@link SkyRenderer#render}, so the two cannot drift apart without a test
 * failing. That coupling is the point: a budget that disagreed with the
 * renderer about whether a layer is drawn is how you get frozen rain.
 */
public final class FrameBudget {

    private FrameBudget() {}

    /** ~24fps. Rain, snow, lightning — see the table above. */
    public static final long FAST_MS = 42L;

    /** 4fps. Cloud drift, star twinkle, the sun's ray pulse, heat shimmer:
     *  every animation in the sky that is not falling out of it. */
    public static final long SLOW_MS = 250L;

    /**
     * 0.2fps. Nothing is animating at all.
     *
     * <p>In practice this is the no-weather-yet case rather than a weather
     * condition: a real sky almost always has either the sun up or the stars
     * out, and either one earns {@link #SLOW_MS}. It is still worth having as
     * its own answer, because the alternative is pretending a scene with
     * nothing in it needs 4fps.
     */
    public static final long IDLE_MS = 5_000L;

    /** 1fps, as a floor rather than a rate: the sky is behind a panel. Not
     *  zero, because a panel can be dragged away in a single frame and the
     *  sky underneath should already be current when it is — and because a
     *  translucent panel would otherwise freeze visibly. */
    public static final long OBSCURED_FLOOR_MS = 1_000L;

    /** {@link SkyRenderer}'s own gate on the rain and snow layers. */
    private static final float PRECIP_FLOOR = 0.01f;

    /** {@link SkyRenderer}'s star gate: {@code night * (1 - cover)}. */
    private static final float STAR_VISIBILITY_FLOOR = 0.02f;

    /** {@link SkyRenderer}'s shimmer gate, {@code smooth(35, 45, tempC)},
     *  which is zero at or below 35°C. */
    private static final int SHIMMER_FLOOR_C = 35;

    /**
     * The sleep between frames for a sky in {@code c}.
     *
     * @param bufW     the render buffer's width, which decides where the sun
     *                 sits on its arc
     * @param bufH     the render buffer's height, same
     * @param obscured whether a panel is covering the sky right now
     */
    public static long intervalMs(SkyConditions c, int bufW, int bufH, boolean obscured) {
        long interval = animationIntervalMs(c, bufW, bufH);
        return obscured ? Math.max(interval, OBSCURED_FLOOR_MS) : interval;
    }

    /** {@link #intervalMs} without the panel check — the scene's own rate. */
    public static long animationIntervalMs(SkyConditions c, int bufW, int bufH) {
        if (c == null) return IDLE_MS;
        if (falling(c) || c.thunder) return FAST_MS;
        if (cloudsShown(c.cloudCover) > 0
                || starsVisible(c)
                || sunVisible(c, bufW, bufH)
                || shimmering(c)) {
            return SLOW_MS;
        }
        return IDLE_MS;
    }

    /** True when {@link #animationIntervalMs} found nothing moving at all. */
    public static boolean isStill(SkyConditions c, int bufW, int bufH) {
        return animationIntervalMs(c, bufW, bufH) == IDLE_MS;
    }

    // ---- the layer gates, mirrored from SkyRenderer ------------------------

    /** {@code Math.round(cover * clouds.length)} — below half a cloud, the
     *  cloud layer draws nothing and therefore animates nothing. */
    public static int cloudsShown(float cloudCover) {
        return Math.round(cloudCover * SkyRenderer.CLOUD_COUNT);
    }

    private static boolean falling(SkyConditions c) {
        return c.type != Precip.NONE && c.precip > PRECIP_FLOOR;
    }

    private static boolean starsVisible(SkyConditions c) {
        float sunAlt = SkyRenderer.sunAlt(c.hour);
        float night = 1f - SkyRenderer.clamp01(sunAlt * 3f + 0.35f);
        return night * (1f - c.cloudCover) > STAR_VISIBILITY_FLOOR;
    }

    /** {@code renderSun} returns immediately once the disc has set below
     *  {@code h + 18}; above that its rays pulse and the frame changes. */
    private static boolean sunVisible(SkyConditions c, int bufW, int bufH) {
        float sunT = BodyPath.sunT(c.hour);
        return BodyPath.sunY(sunT, bufW, bufH, SkyRenderer.SUN_RADIUS) < bufH + 18;
    }

    private static boolean shimmering(SkyConditions c) {
        return c.tempC > SHIMMER_FLOOR_C;
    }
}
