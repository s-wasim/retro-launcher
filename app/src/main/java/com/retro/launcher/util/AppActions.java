package com.retro.launcher.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import com.retro.launcher.core.AppActionPolicy;
import com.retro.launcher.data.AppEntry;

/**
 * Turns an {@link AppActionPolicy.Action} into intents and fires them through
 * {@link Launch#first}.
 *
 * <p>The bug that produced this class: the drawer fired a bare
 * {@code ACTION_DELETE} inside {@code catch (ActivityNotFoundException
 * ignored) {}}, and on a device without
 * {@code android.permission.REQUEST_DELETE_PACKAGES} declared, that call
 * <em>succeeds</em>. The system's PackageInstallerActivity resolves, starts,
 * checks the caller for the permission, fails to find it, and finishes before
 * drawing a single pixel. Nothing throws, so the catch never runs, and from
 * the device the row simply does nothing at all. The permission is declared in
 * the manifest now; the chains below are the insurance for a device that fails
 * some other way, and every failure is logged and reported rather than
 * swallowed.
 */
public final class AppActions {

    private AppActions() {}

    /** True when {@code app} is this launcher — no removal rows for ourselves. */
    public static boolean isSelf(Context ctx, AppEntry app) {
        return ctx.getPackageName().equals(app.packageName);
    }

    /**
     * Runs the action.
     *
     * @return false when nothing on this device would take any candidate —
     *         the caller is expected to say so on screen rather than absorb it
     */
    public static boolean perform(Context ctx, AppActionPolicy.Action action, AppEntry app) {
        switch (action) {
            case LAUNCH:
                return Launch.first(ctx, launchIntent(app),
                        Launch.packageLauncher(ctx, app.packageName));
            case UNINSTALL:
            case UNINSTALL_UPDATES:
                // ACTION_DELETE is the one the system installer prefers;
                // ACTION_UNINSTALL_PACKAGE is its deprecated predecessor, kept
                // because some OEM builds still only register that one. App
                // Info is the last resort: it is a page with an uninstall
                // button on it rather than the flow itself, but it is visible.
                return Launch.first(ctx, deleteIntent(app), uninstallIntent(app), appInfoIntent(app));
            case DISABLE:
                // A launcher cannot disable an app; setApplicationEnabledSetting
                // is device-owner-or-self only. App Info is where the system's
                // own Disable button lives, so that is where the row goes.
            case APP_INFO:
            default:
                return Launch.first(ctx, appInfoIntent(app), manageAppsIntent());
        }
    }

    private static Intent launchIntent(AppEntry app) {
        if (app.activityName == null || app.activityName.isEmpty()) return null;
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(app.packageName, app.activityName));
    }

    private static Intent deleteIntent(AppEntry app) {
        return new Intent(Intent.ACTION_DELETE, packageUri(app));
    }

    @SuppressWarnings("deprecation") // ACTION_UNINSTALL_PACKAGE: fallback only
    private static Intent uninstallIntent(AppEntry app) {
        return new Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri(app));
    }

    private static Intent appInfoIntent(AppEntry app) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(app));
    }

    private static Intent manageAppsIntent() {
        return new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
    }

    private static Uri packageUri(AppEntry app) {
        return Uri.fromParts("package", app.packageName, null);
    }
}
