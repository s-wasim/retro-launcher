package com.retro.launcher.core;

/**
 * The prototype sizes everything in {@code cqw} — percent of the screen's own
 * width. Freezing that into fixed dp breaks on narrow phones and foldables, so
 * every layout value in the launcher resolves through here at runtime instead.
 *
 * Takes width and density as arguments rather than reading Resources, so it
 * carries no Android dependency and can be tested at arbitrary screen sizes.
 */
public final class Metrics {

    private final float widthPx;
    private final float density;
    private final float scaledDensity;

    public Metrics(float widthPx, float density, float scaledDensity) {
        this.widthPx = widthPx;
        this.density = density;
        this.scaledDensity = scaledDensity;
    }

    /** One cqw is 1% of screen width. Returns pixels. */
    public float cqw(float units) {
        return widthPx * units / 100f;
    }

    /** Density-independent pixels to pixels. */
    public float dp(float units) {
        return units * density;
    }

    /**
     * A cqw-derived text size in pixels, never smaller than {@code minSp}.
     * The prototype's micro labels sit at 2.2cqw, which lands below Android's
     * comfortable reading floor on a narrow screen — see DESIGN_NOTES §4.
     */
    public float textPx(float cqwUnits, float minSp) {
        float px = cqw(cqwUnits);
        float floorPx = minSp * scaledDensity;
        return px < floorPx ? floorPx : px;
    }
}
