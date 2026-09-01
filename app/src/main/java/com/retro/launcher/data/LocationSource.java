package com.retro.launcher.data;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import java.util.List;

/**
 * The coarse fix the weather fetch needs, and nothing more.
 *
 * This never registers for location updates. A launcher has no business
 * following you around; the most recent fix some other app already caused is
 * good enough to name a city's weather, and it costs no battery to read.
 *
 * Every failure — permission not granted, location off, no provider has ever
 * had a fix — is the same null.
 */
public final class LocationSource {

    private final Context ctx;

    public LocationSource(Context context) {
        this.ctx = context.getApplicationContext();
    }

    public boolean hasPermission() {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** @return {latitude, longitude} of the freshest known fix, or null. */
    public double[] lastKnown() {
        if (!hasPermission()) return null;

        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;

        try {
            Location best = null;
            List<String> providers = lm.getProviders(true);
            for (int i = 0; i < providers.size(); i++) {
                Location l = lm.getLastKnownLocation(providers.get(i));
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            return best == null ? null : new double[]{best.getLatitude(), best.getLongitude()};
        } catch (SecurityException | IllegalArgumentException e) {
            // Revoked between the check and the read, or a provider the device
            // reported and then refused. Either way: no fix.
            return null;
        }
    }
}
