package com.retro.launcher.util;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.Log;

/**
 * Best-effort activity launching for the intents this launcher fires at other
 * apps — the clock, the calendar, a web search.
 *
 * Every one of them is optional: the app may not be installed, may not declare
 * the action, or the device may refuse it outright on a managed profile. Spec
 * §6 says none of that may surface as a crash. But a bare try/catch around a
 * single intent is how the clock tap came to do nothing at all — the device's
 * clock app simply did not declare {@code ACTION_SHOW_ALARMS}, the exception
 * was swallowed, and the tap died in silence.
 *
 * So: try candidates in order until one starts, and log every failure under
 * {@code LaunchChain} so a device that still does nothing is diagnosable from
 * a logcat rather than by guesswork.
 */
public final class Launch {

    private static final String TAG = "LaunchChain";

    private Launch() {}

    /**
     * Starts the first candidate that the system accepts.
     *
     * Nulls are skipped, so callers can pass a lookup that came up empty
     * without guarding it first.
     *
     * @return true if something started
     */
    public static boolean first(Context ctx, Intent... candidates) {
        for (Intent i : candidates) {
            if (i == null) continue;
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.d(TAG, "no handler for " + describe(i)
                        + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        Log.w(TAG, "nothing on this device handled the request");
        return false;
    }

    /**
     * The launcher intent of the first installed package in {@code packages},
     * or null if none of them are present.
     *
     * This is the fallback that actually opens OEM apps which never declared
     * the standard action. It relies on the manifest's MAIN/LAUNCHER
     * {@code <queries>} entry for package visibility on API 30+.
     */
    public static Intent packageLauncher(Context ctx, String... packages) {
        PackageManager pm = ctx.getPackageManager();
        for (String p : packages) {
            Intent i = pm.getLaunchIntentForPackage(p);
            if (i != null) return i;
        }
        return null;
    }

    /**
     * Starts a launcher activity in a specific user profile (V9 §11).
     *
     * <p>This exists because {@code startActivity} cannot cross a profile
     * boundary: handed a clone's component it throws
     * {@code SecurityException}, or on some builds resolves nothing and dies
     * quietly — either way it fails for exactly the apps §11 makes visible.
     * {@link LauncherApps#startMainActivity} is the call that can cross, and
     * it needs no permission to do it.
     *
     * @param user null for our own profile, where the ordinary intent path is
     *             both sufficient and cheaper
     * @return true if something started
     */
    public static boolean mainActivity(Context ctx, ComponentName component, UserHandle user) {
        if (user == null) return false;
        try {
            LauncherApps launcher =
                    (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (launcher == null) return false;
            launcher.startMainActivity(component, user, null, null);
            return true;
        } catch (Exception e) {
            // The profile was removed or turned off since the drawer loaded,
            // or the ROM refuses the call. The caller's intent chain is next.
            Log.d(TAG, "cross-profile start refused for " + component.flattenToShortString()
                    + " (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    private static String describe(Intent i) {
        String action = i.getAction();
        return action != null ? action : String.valueOf(i.getComponent());
    }
}
