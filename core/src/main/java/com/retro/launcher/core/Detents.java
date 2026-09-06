package com.retro.launcher.core;

/**
 * How many of a panel drag's five haptic thresholds have been crossed.
 *
 * <p>Replaces {@code HapticCurve}. That class drove a continuously repeating
 * vibration waveform for the whole length of a drag, which is what tripped
 * the platform's per-app vibration cutoff on a slow expansion — see the V8
 * design spec item 2. This class only ever says which threshold band the
 * drag is currently in; {@code Haptics} turns each *change* of band into one
 * short, non-repeating pulse.
 *
 * <p>{@link #level(float)} is the stateless function: five thresholds at
 * {@code 0.2, 0.4, 0.6, 0.8, 1.0}, so {@code level} runs {@code 0..5}.
 * {@code progress} outside {@code [0, 1]} clamps; {@code NaN} reads as 0 —
 * the same contract {@code HapticCurve.bucket} had, so callers need no new
 * NaN handling.
 *
 * <p>An instance additionally applies {@link #HYSTERESIS}: a level is only
 * entered once progress passes {@code 0.02} beyond its threshold, and only
 * left once progress falls {@code 0.02} back below it. Without this a finger
 * resting exactly on a boundary would chatter the vibrator on every frame of
 * sensor jitter.
 */
public final class Detents {

    /** Five bands, thresholds at 20% intervals. */
    private static final float[] THRESHOLDS = {0.2f, 0.4f, 0.6f, 0.8f, 1.0f};

    /** How far past a threshold progress must move before the level change
     *  commits, in either direction. */
    public static final float HYSTERESIS = 0.02f;

    private int currentLevel = 0;

    /** Stateless: which band {@code progress} falls in, with no hysteresis. */
    public static int level(float progress) {
        float p = clamp01(progress);
        int level = 0;
        for (float t : THRESHOLDS) {
            if (p >= t) level++;
        }
        return level;
    }

    /**
     * Stateful: the level after this reading, applying hysteresis against
     * whatever level the previous call to {@link #next} settled on. The
     * first call on a fresh instance has no prior level to hold onto, so it
     * behaves exactly like {@link #level}.
     */
    public int next(float progress) {
        float p = clamp01(progress);
        int rising = level(p);
        if (rising > currentLevel) {
            // Moving up: commit as soon as the plain threshold is crossed —
            // hysteresis only guards the downward re-cross.
            currentLevel = rising;
            return currentLevel;
        }
        if (rising < currentLevel) {
            // Moving down: only leave the current level once progress has
            // fallen HYSTERESIS below the threshold that entered it.
            float enteringThreshold = currentLevel == 0 ? 0f : THRESHOLDS[currentLevel - 1];
            if (p <= enteringThreshold - HYSTERESIS) {
                currentLevel = rising;
            }
        }
        return currentLevel;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
