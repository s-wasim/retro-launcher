package com.retro.launcher.shade;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * The only reliable way a third-party launcher can open the notification
 * shade on a modern Android — see DESIGN_NOTES §9 delta 21.
 *
 * <p>The documented-looking route, reflecting into
 * {@code StatusBarManager#expandNotificationsPanel()}, is a non-SDK interface
 * on the denylist. At this app's targetSdk the reflective lookup throws
 * outright, which is why the swipe used to be a silent no-op: the call failed
 * and the catch-all swallowed it. It still works on older and on some OEM
 * builds, so {@code HomeActivity} tries it first and only falls back here.
 *
 * <p>This service reads nothing. It declares no
 * {@code canRetrieveWindowContent}, requests the narrowest event type the
 * framework will accept, and never looks at the events it receives — its
 * entire purpose is to be a live {@link AccessibilityService} instance that
 * {@link #performGlobalAction} can be called on. It is opt-in: until the user
 * enables it from Settings the fallback simply reports failure and the swipe
 * stays inert.
 */
public final class ShadeService extends AccessibilityService {

    /** The connected instance, or null when the service is off. Written on the
     *  main thread by the framework and read there by the launcher. */
    private static volatile ShadeService instance;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Nothing to do — see the class javadoc. We need the connection, not
        // the events.
    }

    @Override public void onInterrupt() {
        // Nothing to interrupt.
    }

    /**
     * Opens the notification shade. Returns false when the service is not
     * enabled, or when the platform refuses the action, so the caller can tell
     * "did nothing" apart from "worked".
     */
    public static boolean expandNotificationShade() {
        ShadeService s = instance;
        if (s == null) return false;
        try {
            return s.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether the user has enabled this service. Asks
     * {@link AccessibilityManager} rather than trusting {@link #instance},
     * because the status row has to be right the instant we return from
     * Accessibility settings — before the framework has bound us.
     */
    public static boolean isEnabled(Context context) {
        AccessibilityManager am =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        ComponentName self = new ComponentName(context, ShadeService.class);
        List<AccessibilityServiceInfo> enabled =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabled == null) return false;
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getId() != null && info.getId().contains(self.flattenToString())) return true;
        }
        return false;
    }
}
