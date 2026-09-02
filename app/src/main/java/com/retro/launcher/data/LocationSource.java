package com.retro.launcher.data;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The fix the weather fetch needs, and nothing more.
 *
 * <p>This never registers for continuous location updates. A launcher has no
 * business following you around. What it will do — once, when a fetch was
 * already going to happen anyway — is ask a provider for a single current
 * fix, because the alternative was reusing whatever fix some other app last
 * happened to cause, which on a phone that has not opened Maps in a week is
 * a different city.
 *
 * <p>Every failure — permission not granted, location off, no provider has
 * ever had a fix, the request timed out — is the same null.
 */
public final class LocationSource {

    /** Long enough for a warm GPS or a network fix, short enough that the
     *  weather line is not blank while the user looks at it. */
    private static final long TIMEOUT_MS = 8_000L;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    public LocationSource(Context context) {
        this.ctx = context.getApplicationContext();
    }

    /** True when either location permission is held. The user may grant only
     *  approximate location, and approximate weather is still weather. */
    public boolean hasPermission() {
        return granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                || granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    public boolean hasFinePermission() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean granted(String permission) {
        return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** @return {latitude, longitude} of the freshest known fix, or null. */
    public double[] lastKnown() {
        if (!hasPermission()) return null;

        LocationManager lm = manager();
        if (lm == null) return null;

        try {
            Location best = null;
            List<String> providers = lm.getProviders(true);
            for (int i = 0; i < providers.size(); i++) {
                Location l = lm.getLastKnownLocation(providers.get(i));
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            return best == null ? null : coords(best);
        } catch (SecurityException | IllegalArgumentException e) {
            // Revoked between the check and the read, or a provider the device
            // reported and then refused. Either way: no fix.
            return null;
        }
    }

    /**
     * Asks a provider for one current fix, calling back on the main thread
     * with {@code {latitude, longitude}} — or with {@link #lastKnown()}, or
     * null, if nothing arrives within {@value #TIMEOUT_MS}ms.
     *
     * <p>The callback runs exactly once. Nothing stays registered afterwards.
     */
    public void requestFresh(Consumer<double[]> callback) {
        if (callback == null) return;
        if (!hasPermission()) { callback.accept(null); return; }

        LocationManager lm = manager();
        String provider = bestProvider(lm);
        if (lm == null || provider == null) { callback.accept(lastKnown()); return; }

        AtomicBoolean done = new AtomicBoolean(false);
        // Only one of these is ever populated, depending on which branch below
        // runs; giveUp cleans up whichever it is so a timeout never leaves a
        // registration or an in-flight OS request behind.
        LocationListener[] pendingListener = new LocationListener[1];
        CancellationSignal[] pendingCancel = new CancellationSignal[1];
        Runnable giveUp = () -> {
            if (!done.compareAndSet(false, true)) return;
            if (pendingListener[0] != null) lm.removeUpdates(pendingListener[0]);
            if (pendingCancel[0] != null) pendingCancel[0].cancel();
            callback.accept(lastKnown());
        };
        main.postDelayed(giveUp, TIMEOUT_MS);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                CancellationSignal cancel = new CancellationSignal();
                pendingCancel[0] = cancel;
                lm.getCurrentLocation(provider, cancel, ctx.getMainExecutor(),
                        location -> {
                            if (!done.compareAndSet(false, true)) return;
                            main.removeCallbacks(giveUp);
                            callback.accept(location == null ? lastKnown() : coords(location));
                        });
            } else {
                // 26–29. requestSingleUpdate is deprecated on R+ but is the
                // only single-shot API below it, and — unlike getCurrentLocation
                // above — does not unregister itself on timeout, so giveUp must
                // call removeUpdates explicitly.
                LocationListener listener = new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        if (!done.compareAndSet(false, true)) return;
                        main.removeCallbacks(giveUp);
                        callback.accept(coords(location));
                    }
                    @Override public void onStatusChanged(String p, int s, android.os.Bundle x) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                };
                pendingListener[0] = listener;
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            }
        } catch (RuntimeException e) {
            main.removeCallbacks(giveUp);
            if (done.compareAndSet(false, true)) callback.accept(lastKnown());
        }
    }

    /**
     * GPS when we hold FINE and the device has it, network otherwise. Not
     * {@code getBestProvider} with a Criteria: that can pick PASSIVE, which
     * never produces a fix on its own and would burn the whole timeout.
     */
    private String bestProvider(LocationManager lm) {
        if (lm == null) return null;
        try {
            if (hasFinePermission() && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        return null;
    }

    private LocationManager manager() {
        return (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    private static double[] coords(Location l) {
        return new double[]{l.getLatitude(), l.getLongitude()};
    }
}
