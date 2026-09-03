package com.retro.launcher.lock;

import android.content.Context;

import androidx.annotation.Keep;

/**
 * Runs in the privileged shell process Shizuku's server spawns — not in this
 * app's own process. {@link ShizukuLock} binds to it once permission is
 * granted and calls {@link #lockScreen()} like an ordinary (synchronous)
 * Binder call from then on.
 *
 * <p>Both constructors are required by Shizuku's own instantiation
 * contract: the no-arg one is the baseline, and the {@link Context}-taking
 * one — available since Shizuku API v13 — is preferred when offered. Both
 * are reached only by Shizuku's reflection, invisible to R8's reachability
 * analysis, so {@link Keep} plus this app's own proguard-rules.pro entry
 * are both needed to survive minification.
 */
public class ShizukuLockService extends IShizukuLockService.Stub {

    public ShizukuLockService() {
    }

    @Keep
    public ShizukuLockService(Context context) {
    }

    /** Reserved destroy method — Shizuku's server calls this to tear the
     *  process down; it is not something this app's own code invokes. */
    @Override public void destroy() {
        System.exit(0);
    }

    @Override public boolean lockScreen() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"input", "keyevent", "26"});
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
