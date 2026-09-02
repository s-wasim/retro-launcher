package com.retro.launcher.core;

/**
 * How hard the vibrator runs as a panel is dragged.
 *
 * <p>Two jobs. The first is feel: a squared ramp from a floor amplitude to
 * full, so a drag gains weight as it approaches its snap threshold rather
 * than buzzing at one flat level the whole way.
 *
 * <p>The second is the reason this is its own unit. A drag emits a frame
 * every 16ms, and re-commanding the vibrator sixty times a second is both
 * wasteful and audibly wrong — each new waveform restarts the motor. So the
 * amplitude is quantized into {@link #BUCKETS} steps and the caller only
 * re-commands when the bucket changes: at most eight times across a full
 * drag, however fast or slow the finger moves.
 */
public final class HapticCurve {

    private HapticCurve() {}

    /** Eight steps is enough for the swell to read as continuous and few
     *  enough that the motor is never restarted mid-buzz. */
    public static final int BUCKETS = 8;

    /** Perceptible on every device tested, and quiet enough that a drag the
     *  user abandons at 5% does not feel like a mistake. */
    public static final int FLOOR_AMPLITUDE = 40;

    /** {@code VibrationEffect}'s maximum. */
    public static final int MAX_AMPLITUDE = 255;

    /**
     * Which of the {@link #BUCKETS} steps {@code progress} falls in.
     * {@code progress} outside {@code [0, 1]} clamps; NaN reads as 0.
     */
    public static int bucket(float progress) {
        float p = clamp01(progress);
        int b = (int) (p * BUCKETS);
        return b >= BUCKETS ? BUCKETS - 1 : b;
    }

    /** The quantized amplitude for this drag progress. */
    public static int amplitude(float progress) {
        return amplitudeForBucket(bucket(progress));
    }

    /**
     * The amplitude for a bucket index, on a squared ramp so the last
     * quarter of the drag carries most of the swell. Bucket 0 is exactly
     * {@link #FLOOR_AMPLITUDE}; bucket {@code BUCKETS - 1} is exactly
     * {@link #MAX_AMPLITUDE}. Out-of-range indexes clamp.
     */
    public static int amplitudeForBucket(int bucket) {
        int b = bucket < 0 ? 0 : (bucket > BUCKETS - 1 ? BUCKETS - 1 : bucket);
        float t = b / (float) (BUCKETS - 1);
        return Math.round(FLOOR_AMPLITUDE + (MAX_AMPLITUDE - FLOOR_AMPLITUDE) * t * t);
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
