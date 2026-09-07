package com.retro.launcher.data;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import com.retro.launcher.core.CategoryMap;
import com.retro.launcher.core.ComponentKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enumerates launchable apps, sorts by label, and assigns each one a
 * category — auto from {@code ApplicationInfo.category} (§9 delta 3),
 * overridden by whatever the user has assigned through the drawer's category
 * sheet.
 *
 * <p><b>V9 §11.</b> This used to enumerate through
 * {@code PackageManager#queryIntentActivities}, which only ever returns
 * activities in the <em>calling user's own profile</em>. OEM clones — Samsung
 * Dual Messenger, Xiaomi Dual Apps, OnePlus Parallel Apps — live in a
 * secondary user profile, so that call could not see them: the drawer was
 * never deduplicating clones away, it had simply never been shown them.
 * {@link LauncherApps#getActivityList} asked once per profile is the call
 * that can. It has been available since API 21 against a {@code minSdk} of
 * 26 and needs no new permission, which is why it does not undo §10's
 * permission reduction — in particular it does not need
 * {@code QUERY_ALL_PACKAGES}, which this app does not declare and must not.
 *
 * <p>{@code getUserProfiles()} returns every profile associated with this
 * user, so a work profile's apps arrive here too and are badged the same way
 * a clone is. That is the right outcome for a launcher — a work app the user
 * could not reach at all is worse than one marked with a star — and it is the
 * same launch path either way.
 *
 * <p>Some locked-down OEM builds restrict profile enumeration. Every failure
 * here falls back to the old {@code queryIntentActivities} path, because the
 * failure mode that matters is not "no clones" — it is an empty drawer.
 */
public final class AppRepository {

    private final Context context;
    private final PackageManager pm;
    private final Prefs prefs;

    public AppRepository(Context context, PackageManager pm, Prefs prefs) {
        this.context = context;
        this.pm = pm;
        this.prefs = prefs;
    }

    public List<AppEntry> load() {
        Map<String, List<String>> overrides = prefs.memberships();

        List<AppEntry> out = loadViaLauncherApps(overrides);
        if (out.isEmpty()) out = loadViaPackageManager(overrides);

        if (out.isEmpty()) {
            List<AppEntry> diagnostic = new ArrayList<>();
            diagnostic.add(new AppEntry(
                    "NO APPS FOUND — ADD A <queries> BLOCK FOR MAIN/LAUNCHER TO AndroidManifest.xml",
                    "", "", Collections.emptyList(), true));
            return diagnostic;
        }

        sortByLabel(out);
        return out;
    }

    /**
     * The profile-aware path. Returns an empty list — never a partial one —
     * if anything at all goes wrong, so the caller's fallback is a clean
     * either/or rather than a merge of two enumerations that might double up
     * the primary profile's rows.
     */
    private List<AppEntry> loadViaLauncherApps(Map<String, List<String>> overrides) {
        List<AppEntry> out = new ArrayList<>();
        try {
            LauncherApps launcher =
                    (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (launcher == null || users == null) return Collections.emptyList();

            UserHandle self = Process.myUserHandle();
            List<UserHandle> profiles = users.getUserProfiles();
            if (profiles == null || profiles.isEmpty()) return Collections.emptyList();

            for (UserHandle user : profiles) {
                // Our own profile keeps the historical key form; only a
                // secondary profile takes a serial suffix, which is what lets
                // every dock slot already stored on the device keep matching.
                boolean primary = self.equals(user);
                long serial = primary
                        ? ComponentKey.PRIMARY : users.getSerialNumberForUser(user);
                for (LauncherActivityInfo info : launcher.getActivityList(null, user)) {
                    out.add(entryFor(info, primary ? null : user, serial, overrides));
                }
            }
        } catch (Exception e) {
            // A ROM that restricts profile enumeration, or a service that is
            // not there at all. Degrade to exactly the old behaviour.
            return Collections.emptyList();
        }
        return out;
    }

    private AppEntry entryFor(LauncherActivityInfo info, UserHandle user, long serial,
                              Map<String, List<String>> overrides) {
        String pkg = info.getComponentName().getPackageName();
        String activity = info.getComponentName().getClassName();
        ApplicationInfo app = info.getApplicationInfo();

        int flags = app.flags;
        return new AppEntry(
                info.getLabel().toString(), pkg, activity,
                categoriesFor(ComponentKey.format(pkg, activity, serial), app.category, overrides),
                false,
                (flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                user, serial);
    }

    /** The pre-V9 enumeration, kept verbatim as the fallback. Sees only our
     *  own profile, so every row it produces is a primary-profile row. */
    private List<AppEntry> loadViaPackageManager(Map<String, List<String>> overrides) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(query, 0);
        if (resolved == null || resolved.isEmpty()) return Collections.emptyList();

        List<AppEntry> out = new ArrayList<>(resolved.size());
        for (ResolveInfo info : resolved) {
            String pkg = info.activityInfo.packageName;
            String activity = info.activityInfo.name;
            ApplicationInfo app = info.activityInfo.applicationInfo;

            // Carried through so the quick-action box can offer rows the
            // launcher can actually honour — see AppActionPolicy.
            int flags = app.flags;
            out.add(new AppEntry(
                    info.loadLabel(pm).toString(), pkg, activity,
                    categoriesFor(pkg + "/" + activity, app.category, overrides),
                    false,
                    (flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                    (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0));
        }
        return out;
    }

    private static List<String> categoriesFor(String component, int autoCategory,
                                              Map<String, List<String>> overrides) {
        List<String> assigned = overrides.get(component);
        return assigned != null
                ? assigned
                : Collections.singletonList(CategoryMap.forCategory(autoCategory));
    }

    /**
     * By lowercased label, then by the stored key.
     *
     * <p>The tiebreak is V9 §11: a clone and its original share a label
     * exactly, and {@code Collections.sort} being stable would otherwise
     * order the pair by whichever profile the platform happened to enumerate
     * first — so the same two rows could swap places between launches. The
     * key differs (only the clone carries a serial) and is stable, so the
     * pair holds still.
     */
    private static void sortByLabel(List<AppEntry> apps) {
        Collections.sort(apps, Comparator
                .comparing((AppEntry e) -> e.label.toLowerCase(Locale.ROOT))
                .thenComparing(AppEntry::component));
    }
}
