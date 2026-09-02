package com.retro.launcher.util;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.retro.launcher.core.Detents;

/**
 * Every vibration the launcher makes.
 *
 * <p>The master preference is checked on <em>every</em> entry point rather
 * than at construction, so a single {@code setEnabled(false)} silences the
 * whole app the instant the toggle moves, including a drag already in flight.
 *
 * <p>Taps are one-shots. A drag used to be a repeating waveform re-commanded
 * as its amplitude bucket changed — up to 40ms of continuous motor time per
 * commit, held open for the whole drag. That is what tripped the platform's
 * per-app vibration cutoff on a slow panel expansion (V8 design spec item
 * 2). A drag is now a sequence of short, non-repeating "detent" pulses, one
 * per {@link Detents} threshold crossed, in either direction, capped at
 * {@link #MAX_TICKS_PER_GESTURE} per gesture so an oscillating drag cannot
 * re-drive the motor indefinitely.
 *
 * <p>Every failure is silence. A device with no vibrator, a vibrator the
 * system has muted, an amplitude the motor cannot express: none of them are
 * worth an exception on a launcher's touch path.
 */
public final class Haptics {

    /** A single expansion costs 5 ticks; six deliberate up-down sweeps still
     *  feel right at 10 ticks each. Only pathological jitter reaches this. */
    private static final int MAX_TICKS_PER_GESTURE = 12;

    private final Vibrator vibrator;
    private boolean enabled;

    private final Detents detents = new Detents();
    /** -1 while no drag is running — matches {@code Detents}'s own "nothing
     *  entered yet" state so the first frame of a drag always ticks. */
    private int dragLevel = -1;
    private int ticksThisGesture;

    public Haptics(Context context, boolean enabled) {
        this.enabled = enabled;
        this.vibrator = resolveVibrator(context.getApplicationContext());
    }

    private static Vibrator resolveVibrator(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm == null ? null : vm.getDefaultVibrator();
        }
        return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /** Turning haptics off stops anything already running. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) dragEnd();
    }

    public boolean isEnabled() { return enabled; }

    private boolean unavailable() {
        return !enabled || vibrator == null || !vibrator.hasVibrator();
    }

    /** Every interactive tap in the launcher. */
    public void click() {
        if (unavailable()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(12L, 60));
            }
        } catch (RuntimeException ignored) {
            // A vendor vibrator that refuses the effect. Silence is fine.
        }
    }

    /** The two long-press surfaces and the category-tab long-press. */
    public void longPress() {
        if (unavailable()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(24L, 140));
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Resets detent state for a new drag. Commands nothing. */
    public void dragStart() {
        dragLevel = -1;
        ticksThisGesture = 0;
    }

    /**
     * Re-derives the detent level from {@code progress} and fires one
     * {@link #detentTick()} if it moved — in either direction, so pulling a
     * panel back down ticks as it re-crosses a threshold same as opening it.
     * Safe and cheap to call from every frame of a drag — that is what it is
     * for.
     *
     * @param progress 0 at rest, 1 at the snap threshold
     */
    public void dragProgress(float progress) {
        if (unavailable()) return;
        int level = detents.next(progress);
        if (level == dragLevel) return;
        dragLevel = level;
        if (ticksThisGesture >= MAX_TICKS_PER_GESTURE) return;
        ticksThisGesture++;
        detentTick();
    }

    /** Ends the drag. Idempotent, and safe when no drag is running. Retains
     *  {@code vibrator.cancel()} for safety even though a detent tick is a
     *  one-shot with nothing left running to cancel. */
    public void dragEnd() {
        dragLevel = -1;
        ticksThisGesture = 0;
        if (vibrator == null) return;
        try {
            vibrator.cancel();
        } catch (RuntimeException ignored) {
        }
    }

    /** One short pulse marking a detent crossing. Never a repeating waveform. */
    private void detentTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(10L, 80));
            }
        } catch (RuntimeException ignored) {
        }
    }
}
