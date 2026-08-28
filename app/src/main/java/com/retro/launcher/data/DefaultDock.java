package com.retro.launcher.data;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the prototype's three default dock slots (phone, messages,
 * camera) to whatever this device actually has — DESIGN_NOTES §9 delta 6.
 * A slot that resolves to nothing is simply omitted; runs once, on first
 * launch only.
 */
public final class DefaultDock {

    private DefaultDock() {}

    public static List<String> seed(PackageManager pm) {
        List<String> out = new ArrayList<>();
        addIfResolves(pm, out, new Intent(Intent.ACTION_DIAL));
        addIfResolves(pm, out, new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_MESSAGING"));
        addIfResolves(pm, out, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        return out;
    }

    private static void addIfResolves(PackageManager pm, List<String> out, Intent intent) {
        ResolveInfo info = pm.resolveActivity(intent, 0);
        if (info != null && info.activityInfo != null) {
            out.add(info.activityInfo.packageName + "/" + info.activityInfo.name);
        }
    }
}
