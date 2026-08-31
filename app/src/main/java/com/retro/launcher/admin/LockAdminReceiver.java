package com.retro.launcher.admin;

import android.app.admin.DeviceAdminReceiver;

/**
 * Empty admin receiver — its only purpose is to exist so
 * {@link android.app.admin.DevicePolicyManager#lockNow()} is available once
 * the user activates it, for the home-screen long-press-to-lock gesture
 * (DESIGN_NOTES §9 delta 19). Requests no policies beyond the ability to
 * lock the screen, which every active admin already has.
 */
public final class LockAdminReceiver extends DeviceAdminReceiver {
}
