package com.retro.launcher.util;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.retro.launcher.core.HapticCurve;

/**
 * Every vibration the launcher makes.
 *
 * <p>The master preference is checked on <em>every</em> entry point rather
 * than at construction, so a single {@code setEnabled(false)} silences the
 * whole app the instant the toggle moves, including a drag already in flight.
 *
 * <p>Taps are one-shots. A drag is a repeating waveform whose amplitude is
 * re-commanded only when {@link HapticCurve}'s bucket changes — see that
 * class for why that matters at 60fps.
 *
 * <p>Every failure is silence. A device with no vibrator, a vibrator the
 * system has muted, an amplitude the motor cannot express: none of them are
 * worth an exception on a launcher's touch path.
 */
public final class Haptics {

    /** One pulse of the repeating drag waveform. Short enough that the swell
     *  tracks the finger, long enough that the motor actually spins up. */
    private static final long DRAG_PULSE_MS = 40L;

    private final Vibrator vibrator;
    private boolean enabled;

    /** -1 while no drag is running. */
    private int dragBucket = -1;

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

    /** Begin the repeating drag buzz at the floor amplitude. */
    public void dragStart() {
        if (unavailable()) return;
        dragBucket = -1;
        applyDragBucket(0);
    }

    /**
     * Re-command the motor only if the bucket moved. Safe and cheap to call
     * from every frame of a drag — that is what it is for.
     *
     * @param progress 0 at rest, 1 at the snap threshold
     */
    public void dragProgress(float progress) {
        if (unavailable()) return;
        int bucket = HapticCurve.bucket(progress);
        if (bucket == dragBucket) return;
        applyDragBucket(bucket);
    }

    /** Stop the drag buzz. Idempotent, and safe when no drag is running. */
    public void dragEnd() {
        dragBucket = -1;
        if (vibrator == null) return;
        try {
            vibrator.cancel();
        } catch (RuntimeException ignored) {
        }
    }

    private void applyDragBucket(int bucket) {
        dragBucket = bucket;
        int amplitude = HapticCurve.amplitudeForBucket(bucket);
        try {
            VibrationEffect effect;
            if (hasAmplitudeControl()) {
                // A pulse then a gap, repeating from index 0, so the buzz
                // holds for as long as the finger is down.
                effect = VibrationEffect.createWaveform(
                        new long[]{0L, DRAG_PULSE_MS},
                        new int[]{0, amplitude},
                        /* repeat from */ 0);
            } else {
                // No amplitude control: a fixed duty cycle instead, so the
                // drag still buzzes — it just does not swell.
                effect = VibrationEffect.createWaveform(
                        new long[]{0L, DRAG_PULSE_MS, DRAG_PULSE_MS},
                        /* repeat from */ 0);
            }
            vibrator.cancel();
            vibrator.vibrate(effect);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean hasAmplitudeControl() {
        try {
            return vibrator.hasAmplitudeControl();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
