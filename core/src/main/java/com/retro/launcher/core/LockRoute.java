package com.retro.launcher.core;

/**
 * Which of the two ways of locking the screen the launcher should use, and
 * what the DEVICE LOCK row in Settings should say about it.
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
 * opens the phone.
 *
 * <p>Hence the ordering below: whenever the accessibility route is available
 * we take it, and the admin route survives only as the fallback for devices
 * that cannot offer the better one (API 26–27) or for users who have not
 * switched the service on yet.
 */
public enum LockRoute {

    /** Accessibility global action. Locks; fingerprint still unlocks. */
    ACCESSIBILITY,

    /** Device admin. Locks; Android then demands the PIN on the next unlock. */
    ADMIN,

    /** Neither is set up — the gesture has nothing to call. */
    NONE;

    /**
     * @param accessibility the service is connected and the platform is new
     *                      enough for {@code GLOBAL_ACTION_LOCK_SCREEN}
     * @param admin         the device admin is active and holds force-lock
     */
    public static LockRoute choose(boolean accessibility, boolean admin) {
        if (accessibility) return ACCESSIBILITY;
        if (admin) return ADMIN;
        return NONE;
    }

    /**
     * Whether there is nothing left for the user to do — the one state the
     * DEVICE LOCK row draws as done and inert.
     *
     * <p>{@link #ADMIN} deliberately does not count. It locks, so it is
     * tempting to call it finished, but it costs the fingerprint, and drawing
     * it as settled would leave everybody already on it with no way to find
     * out there is a better route.
     */
    public boolean settled() {
        return this == ACCESSIBILITY;
    }

    /** Status word for the DEVICE LOCK row. */
    public String status() {
        switch (this) {
            case ACCESSIBILITY: return "ON";
            case ADMIN:         return "PIN ONLY";
            default:            return "ENABLE";
        }
    }
}
