package com.retro.launcher.data;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.retro.launcher.core.CategoryMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enumerates launchable apps via {@code queryIntentActivities}, sorts by
 * label, and assigns each one a category — auto from
 * {@code ApplicationInfo.category} (§9 delta 3), overridden by whatever the
 * user has assigned through the drawer's category sheet.
 */
public final class AppRepository {

    private final PackageManager pm;
    private final Prefs prefs;

    public AppRepository(PackageManager pm, Prefs prefs) {
        this.pm = pm;
        this.prefs = prefs;
    }

    public List<AppEntry> load() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(query, 0);

        if (resolved.isEmpty()) {
            List<AppEntry> diagnostic = new ArrayList<>();
            diagnostic.add(new AppEntry(
                    "NO APPS FOUND — ADD A <queries> BLOCK FOR MAIN/LAUNCHER TO AndroidManifest.xml",
                    "", "", Collections.emptyList(), true));
            return diagnostic;
        }

        Map<String, List<String>> overrides = prefs.memberships();
        List<AppEntry> out = new ArrayList<>(resolved.size());
        for (ResolveInfo info : resolved) {
            String label = info.loadLabel(pm).toString();
            String pkg = info.activityInfo.packageName;
            String activity = info.activityInfo.name;
            String component = pkg + "/" + activity;

            List<String> categories = overrides.get(component);
            if (categories == null) {
                categories = Collections.singletonList(
                        CategoryMap.forCategory(info.activityInfo.applicationInfo.category));
            }
            // Carried through so the quick-action box can offer rows the
            // launcher can actually honour — see AppActionPolicy.
            int flags = info.activityInfo.applicationInfo.flags;
            boolean system = (flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updated = (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            out.add(new AppEntry(label, pkg, activity, categories, false, system, updated));
        }

        Collections.sort(out, Comparator.comparing(
                e -> e.label.toLowerCase(Locale.ROOT)));
        return out;
    }
}
