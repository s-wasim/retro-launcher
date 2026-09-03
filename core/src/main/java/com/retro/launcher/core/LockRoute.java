package com.retro.launcher.core;

/**
 * Which of the three ways of locking the screen the launcher should use, and
 * what the DEVICE LOCK / SHIZUKU LOCK rows in Settings should say about it.
 *
 * <p>The choice is not cosmetic, which is the whole reason this lives in
 * {@code core} with a test around it. Locking through the device admin —
 * {@code DevicePolicyManager#lockNow()} — makes the framework raise the
 * "strong auth required after DPM lock" flag on the user, and while that flag
 * is set Android refuses every biometric: the next unlock has to be the PIN,
 * pattern or password. There is no flag or policy that relaxes it; it is what
 * a device-admin lock means. The accessibility global action
 * ({@code GLOBAL_ACTION_LOCK_SCREEN}, API 28+) is the same lock the power
 * button performs and leaves biometrics untouched, so the fingerprint still
 * opens the phone — and Shizuku's {@code input keyevent 26} is, literally,
 * the power button, with the same result.
 *
 * <p>Shizuku ranks first when available: it is the only route that needs
 * neither an active accessibility service (flagged by some banking apps as
 * suspicious on a non-system app) nor the device admin's fingerprint cost.
 * Accessibility is the fallback for anyone who has not paired Shizuku (it
 * must be re-paired after every reboot on an unrooted device), and the admin
 * route survives only as the last resort for devices that cannot offer
 * either of the better two, or users who have not switched one on yet.
 */
public enum LockRoute {

    /** A Shizuku (ADB-shell) session running {@code input keyevent 26}.
     *  Locks; fingerprint still unlocks; needs no accessibility service. */
    SHIZUKU,

    /** Accessibility global action. Locks; fingerprint still unlocks. */
    ACCESSIBILITY,

    /** Device admin. Locks; Android then demands the PIN on the next unlock. */
    ADMIN,

    /** None of the three is set up — the gesture has nothing to call. */
    NONE;

    /**
     * @param shizuku       the Shizuku toggle is on and a permitted session
     *                      is currently reachable
     * @param accessibility the service is connected and the platform is new
     *                      enough for {@code GLOBAL_ACTION_LOCK_SCREEN}
     * @param admin         the device admin is active and holds force-lock
     */
    public static LockRoute choose(boolean shizuku, boolean accessibility, boolean admin) {
        if (shizuku) return SHIZUKU;
        if (accessibility) return ACCESSIBILITY;
        if (admin) return ADMIN;
        return NONE;
    }

    /**
     * Whether there is nothing left for the user to do — the state the
     * DEVICE LOCK row draws as done and inert.
     *
     * <p>{@link #ADMIN} deliberately does not count. It locks, so it is
     * tempting to call it finished, but it costs the fingerprint, and drawing
     * it as settled would leave everybody already on it with no way to find
     * out there is a better route.
     */
    public boolean settled() {
        return this == SHIZUKU || this == ACCESSIBILITY;
    }

    /** Status word for the DEVICE LOCK row. */
    public String status() {
        switch (this) {
            case SHIZUKU:
            case ACCESSIBILITY: return "ON";
            case ADMIN:         return "PIN ONLY";
            default:            return "ENABLE";
        }
    }
}
