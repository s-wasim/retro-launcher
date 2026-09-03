package com.retro.launcher.lock;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

/**
 * The only file in the app that imports a Shizuku type — see the V8 design
 * spec item 5. Every method swallows every {@code Throwable}, not just
 * {@code RuntimeException}: if the AAR is somehow absent at runtime a call
 * into it throws {@code NoClassDefFoundError}, which is not a
 * {@code RuntimeException}, and that has to read as "unavailable" exactly
 * like every other failure here, not crash the launcher.
 *
 * <p>{@code Shizuku.newProcess} — the simple one-shot shell helper an
 * earlier draft of this class used — is retired as of the pinned API
 * version; the sanctioned replacement is a {@link ShizukuLockService}
 * bound once permission is granted and reused as an ordinary Binder call
 * from then on. Binding is asynchronous, so {@link #lock()} does not block
 * waiting for it: a fresh grant's very first lock attempt can return false
 * while the bind is still in flight, exactly like every other "not
 * available yet" case here, and every subsequent attempt is fast because
 * the connection is already warm.
 */
public final class ShizukuLock {

    private ShizukuLock() {}

    private static final Shizuku.UserServiceArgs SERVICE_ARGS =
            new Shizuku.UserServiceArgs(new ComponentName("com.retro.launcher", ShizukuLockService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("shizuku_lock")
                    .debuggable(false)
                    .version(1);

    private static volatile IShizukuLockService service;
    private static volatile boolean binding;

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = (binder != null && binder.pingBinder())
                    ? IShizukuLockService.Stub.asInterface(binder) : null;
            binding = false;
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
        }
    };

    /**
     * @param toggleOn the Settings "SHIZUKU LOCK" preference
     * @return true only when the toggle is on, the Shizuku app's binder is
     *         reachable, and this app currently holds its permission
     */
    public static boolean isAvailable(boolean toggleOn) {
        boolean available = toggleOn && hasPermission();
        if (available) ensureBound();
        return available;
    }

    /** Permission state alone, ignoring the Settings toggle — what the
     *  SHIZUKU LOCK row's status text needs to distinguish "not permitted
     *  yet" from "not enabled at all". */
    public static boolean hasPermission() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether the Shizuku app's binder is reachable at all, regardless of
     *  whether this app has been granted permission yet. */
    public static boolean isServiceRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** For the Settings row's fix action. A no-op if the service is not
     *  running or permission is already granted — otherwise, once granted,
     *  warms the connection immediately rather than waiting for the first
     *  lock attempt to discover it is not bound yet. */
    public static void requestPermission(int requestCode) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode);
            } else {
                ensureBound();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Idempotent: a no-op once already bound or while a bind is already
     *  in flight. */
    private static void ensureBound() {
        if (service != null || binding) return;
        binding = true;
        try {
            Shizuku.bindUserService(SERVICE_ARGS, CONNECTION);
        } catch (Throwable t) {
            binding = false;
        }
    }

    /**
     * Locks the screen via the remote {@link ShizukuLockService} — literally
     * the power button. Returns false on any failure, including "not bound
     * yet", so the caller can fall through to the next
     * {@link com.retro.launcher.core.LockRoute}.
     */
    public static boolean lock() {
        if (!hasPermission()) return false;
        IShizukuLockService s = service;
        if (s == null) {
            ensureBound();
            return false;
        }
        try {
            return s.lockScreen();
        } catch (Throwable t) {
            service = null;
            return false;
        }
    }
}
