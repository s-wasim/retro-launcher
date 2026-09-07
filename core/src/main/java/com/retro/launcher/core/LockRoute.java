package com.retro.launcher.core;

/**
 * Which of the two ways of locking the screen the launcher should use, and
 * what the DEVICE LOCK / SHIZUKU LOCK rows in Settings should say about it.
 *
 * <p>The choice is not cosmetic, which is the whole reason this lives in
 * {@code core} with a test around it. The accessibility global action
 * ({@code GLOBAL_ACTION_LOCK_SCREEN}, API 28+) is the same lock the power
 * button performs and leaves biometrics untouched, so the fingerprint still
 * opens the phone — and Shizuku's {@code input keyevent 26} is, literally,
 * the power button, with the same result.
 *
 * <p>Shizuku ranks first when available: it is the only route that needs no
 * active accessibility service, which some banking apps flag as suspicious on
 * a non-system app. Accessibility is the fallback for anyone who has not
 * paired Shizuku (it must be re-paired after every reboot on an unrooted
 * device).
 *
 * <p><b>V9 §10 removed the third route, the device admin.</b> Locking through
 * {@code DevicePolicyManager#lockNow()} makes the framework raise the "strong
 * auth required after DPM lock" flag on the user, and while that flag is set
 * Android refuses every biometric: the next unlock has to be the PIN, pattern
 * or password. There is no flag or policy that relaxes it; it is what a
 * device-admin lock means. So the route cost the fingerprint on every use,
 * was never {@link #settled()} for exactly that reason, and — being a
 * device-admin activation — was the single largest trust signal the app
 * asked for. Both better routes remain, and neither costs the fingerprint.
 */
public enum LockRoute {

    /** A Shizuku (ADB-shell) session running {@code input keyevent 26}.
     *  Locks; fingerprint still unlocks; needs no accessibility service. */
    SHIZUKU,

    /** Accessibility global action. Locks; fingerprint still unlocks. */
    ACCESSIBILITY,

    /** Neither is set up — the gesture has nothing to call. */
    NONE;

    /**
     * @param shizuku       the Shizuku toggle is on and a permitted session
     *                      is currently reachable
     * @param accessibility the service is connected and the platform is new
     *                      enough for {@code GLOBAL_ACTION_LOCK_SCREEN}
     */
    public static LockRoute choose(boolean shizuku, boolean accessibility) {
        if (shizuku) return SHIZUKU;
        if (accessibility) return ACCESSIBILITY;
        return NONE;
    }

    /**
     * Whether there is nothing left for the user to do — the state the
     * DEVICE LOCK row draws as done and inert.
     *
     * <p>Now that the admin route is gone, every route that locks at all also
     * leaves the fingerprint working, so this is true of both of them.
     */
    public boolean settled() {
        return this == SHIZUKU || this == ACCESSIBILITY;
    }

    /** Status word for the DEVICE LOCK row. */
    public String status() {
        switch (this) {
            case SHIZUKU:
            case ACCESSIBILITY: return "ON";
            default:            return "ENABLE";
        }
    }
}
