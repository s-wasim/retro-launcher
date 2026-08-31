package com.retro.launcher;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManager;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;

import com.retro.launcher.admin.LockAdminReceiver;
import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.PaletteResolver;
import com.retro.launcher.core.UsageMath;
import com.retro.launcher.core.Weather;
import com.retro.launcher.data.AppEntry;
import com.retro.launcher.data.AppRepository;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.data.UsageRepository;
import com.retro.launcher.data.WeatherRepository;
import com.retro.launcher.icons.GeneratedTileIcons;
import com.retro.launcher.icons.IconCache;
import com.retro.launcher.icons.IconSource;
import com.retro.launcher.icons.InstrumentedIconSource;
import com.retro.launcher.icons.PosterizedIcons;
import com.retro.launcher.shade.ShadeService;
import com.retro.launcher.sky.SkyView;
import com.retro.launcher.ui.BottomSheet;
import com.retro.launcher.ui.DockView;
import com.retro.launcher.ui.DrawerPanel;
import com.retro.launcher.ui.HintOverlay;
import com.retro.launcher.ui.HomePanel;
import com.retro.launcher.ui.LauncherRoot;
import com.retro.launcher.ui.ScreenTimePanel;
import com.retro.launcher.ui.SearchOverlay;
import com.retro.launcher.ui.SettingsPanel;
import com.retro.launcher.ui.SetupScreen;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class HomeActivity extends Activity {

    /** Tier 2 gate switch — see design spec §3.4. Flip and re-measure via
     *  the "IconBench" logcat tag, then delete whichever loses. */
    private static final boolean USE_POSTERIZED_ICONS = false;

    private static final int REQ_LOCATION = 1;

    private LauncherRoot root;
    private SkyView sky;
    private HomePanel home;
    private DrawerPanel drawer;
    private SettingsPanel settings;
    private ScreenTimePanel screenTime;
    private SearchOverlay search;
    private BottomSheet sheet;
    private SetupScreen setupScreen;
    private HintOverlay hintOverlay;
    private AppRepository appRepository;
    private Prefs prefs;
    private Metrics metrics;
    private WeatherRepository weatherRepository;
    private UsageRepository usageRepository;
    private Palette palette;
    private DevicePolicyManager dpm;
    private ComponentName lockAdmin;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (drawer != null) drawer.refresh();
        }
    };

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            refreshPalette();
            refreshTime();
            // Cheap: the repository's own policy decides whether this minute
            // is one where a fetch is actually due.
            weatherRepository.refresh(false, HomeActivity.this::refreshTime);
            ticker.postDelayed(this, 60_000L);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        goEdgeToEdge();

        prefs = new Prefs(this);
        weatherRepository = new WeatherRepository(this, prefs);
        usageRepository = new UsageRepository(this);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        lockAdmin = new ComponentName(this, LockAdminReceiver.class);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        metrics = new Metrics(dm.widthPixels, dm.density, dm.scaledDensity);

        appRepository = new AppRepository(getPackageManager(), prefs);
        IconCache iconCache = new IconCache();
        IconSource rawIcons = USE_POSTERIZED_ICONS
                ? new PosterizedIcons(getPackageManager(), iconCache)
                : new GeneratedTileIcons(iconCache);
        IconSource icons = new InstrumentedIconSource(rawIcons, USE_POSTERIZED_ICONS ? "posterized" : "generated");

        sky = new SkyView(this);

        root = new LauncherRoot(this);
        home = new HomePanel(this, metrics, prefs, icons);
        sheet = new BottomSheet(this, metrics);
        drawer = new DrawerPanel(this, metrics, prefs, appRepository, icons, sheet);
        drawer.setOnHomeListener(() -> root.goTo(LauncherRoot.VIEW_HOME));

        settings = new SettingsPanel(this, metrics, prefs);
        settings.setOnCloseListener(() -> root.goTo(LauncherRoot.VIEW_HOME));
        settings.setOnPrefsChangedListener(() -> {
            refreshPalette();
            refreshTime();
            // Re-render selection state even when the resolved palette itself
            // didn't change (e.g. AUTO -> an explicit choice that resolves
            // to the same colours right now).
            settings.setPalette(palette);
        });
        settings.setDockActionListener(new SettingsPanel.DockActionListener() {
            @Override public void onReplace(int slotIndex) { openDockSheet(slotIndex); }
            @Override public void onAdd() { openDockSheet(-1); }
        });
        settings.setPermissionActionListener(new SettingsPanel.PermissionActionListener() {
            @Override public void onRequestLocation() { requestLocation(); }
            @Override public void onOpenUsageAccessSettings() { openUsageAccessSettings(); }
            @Override public void onEnableDeviceLock() { lockOrRequestAdmin(); }
            @Override public void onSetDefaultLauncher() { requestDefaultLauncher(); }
            @Override public void onEnableNotificationShade() { openAccessibilitySettings(); }
        });

        screenTime = new ScreenTimePanel(this, metrics, prefs);
        screenTime.setOnCloseListener(() -> root.goTo(LauncherRoot.VIEW_HOME));
        screenTime.setOnLimitChangedListener(this::refreshUsage);

        // The weather region opens a weather app (DESIGN_NOTES §9 row 8). With
        // none installed it used to do nothing; now it asks for a fresh
        // reading instead. The repository's 10-minute floor means leaning on
        // it cannot turn into a poll.
        home.clock.setOnNoWeatherApp(() -> weatherRepository.refresh(true, this::refreshTime));

        home.dock.setOnSlotActionListener(new DockView.SlotActionListener() {
            @Override public void onReplace(int slotIndex) { openDockSheet(slotIndex); }
            @Override public void onAdd() { openDockSheet(-1); }
        });

        root.setPanels(home, settings, drawer, screenTime);

        search = new SearchOverlay(this, metrics, appRepository);
        root.setDoubleTapListener(() -> {
            search.setPalette(palette);
            search.open();
        });
        root.setLongPressListener(this::lockOrRequestAdmin);
        root.setOnStatusBarSwipeListener(this::expandStatusBar);

        home.setOnRequestDefaultLauncherListener(this::requestDefaultLauncher);

        setupScreen = new SetupScreen(this, metrics);
        setupScreen.setListener(new SetupScreen.Listener() {
            @Override public void onGrantUsageAccess() { openUsageAccessSettings(); }
            @Override public void onGrantLocation() { requestLocation(); }
            @Override public void onContinue() { showHint(); }
        });

        hintOverlay = new HintOverlay(this, metrics);
        hintOverlay.setOnDismissListener(this::dismissFirstRun);

        FrameLayout stack = new FrameLayout(this);
        stack.addView(sky);   // z=0, behind everything, never moves
        stack.addView(root);
        stack.addView(sheet);   // overlay, above every panel
        stack.addView(search);  // above the sheet: double-tap wins
        stack.addView(setupScreen);
        stack.addView(hintOverlay);
        setContentView(stack);

        refreshPalette();
        refreshTime();
        refreshSkyLocation();
        drawer.refresh();
        settings.setDockEntries(home.dock.entries());
        refreshPermissionStatus();
        refreshUsage();

        hintOverlay.setVisibility(View.GONE);
        setupScreen.setVisibility(prefs.hintShown() ? View.GONE : View.VISIBLE);

        registerReceiver(packageReceiver, packageChangeFilter());
    }

    private void openUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            // No Settings app to resolve it — nothing else we can do.
        }
    }

    private void showHint() {
        setupScreen.setVisibility(View.GONE);
        hintOverlay.setVisibility(View.VISIBLE);
    }

    private void dismissFirstRun() {
        hintOverlay.setVisibility(View.GONE);
        prefs.putBool(Prefs.K_HINT, true);
    }

    private static IntentFilter packageChangeFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        return f;
    }

    /** slotIndex -1 means "add"; otherwise the slot being replaced. */
    private void openDockSheet(int slotIndex) {
        sheet.setPalette(palette);
        sheet.open(slotIndex < 0 ? "ADD TO DOCK" : "REPLACE DOCK SLOT " + (slotIndex + 1));

        List<String> current = new ArrayList<>(home.dock.entries());
        if (slotIndex >= 0 && slotIndex < current.size()) {
            String removed = current.get(slotIndex);
            sheet.addRow("REMOVE FROM DOCK", false, "", () -> {
                List<String> next = new ArrayList<>(home.dock.entries());
                next.remove(removed);
                prefs.setDock(next);
                home.dock.setEntries(next);
                settings.setDockEntries(next);
                sheet.close();
            });
        }

        for (AppEntry app : appRepository.load()) {
            if (app.diagnostic) continue;
            String component = app.component();
            boolean inDock = current.contains(component);
            sheet.addRow(app.label, inDock, "IN DOCK", () -> {
                List<String> next = new ArrayList<>(home.dock.entries());
                if (slotIndex >= 0 && slotIndex < next.size()) {
                    next.set(slotIndex, component);
                } else if (!next.contains(component) && next.size() < 5) {
                    next.add(component);
                }
                prefs.setDock(next);
                home.dock.setEntries(next);
                settings.setDockEntries(next);
                sheet.close();
            });
        }
    }

    private void goEdgeToEdge() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** Current time as a decimal hour, the unit every time-driven system uses. */
    private float decimalHour() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY)
                + c.get(Calendar.MINUTE) / 60f
                + c.get(Calendar.SECOND) / 3600f;
    }

    private boolean systemDark() {
        int mode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void refreshPalette() {
        Palette next = PaletteResolver.resolve(
                prefs.palette(), prefs.theme(), decimalHour(), systemDark());
        if (palette == null || !palette.id.equals(next.id) || palette.dark != next.dark) {
            palette = next;
            home.setPalette(palette);
            drawer.setPalette(palette);
            settings.setPalette(palette);
            screenTime.setPalette(palette);
            if (search != null) search.setPalette(palette);
        }
        applySkyTint();
    }

    /**
     * Settings' "TINT WALLPAPER TO PALETTE" switch: on, the sky posterizes to
     * the palette's six-colour ramp; off, it keeps its own colours.
     *
     * <p>Outside the palette-changed guard above on purpose. Toggling the
     * switch does not change which palette resolves, so anything inside that
     * guard would never run — which is exactly why the switch used to look
     * dead: nothing had ever called {@code sky.setTint}.
     */
    private void applySkyTint() {
        sky.setTint(prefs.tint() ? palette.ramp() : null);
    }

    /** The moon's phase is the same everywhere; which way up it looks is not.
     *  Latitude comes from the coarse fix the weather already keeps. */
    private void refreshSkyLocation() {
        double[] fix = weatherRepository.fix();
        sky.setLatitude(fix == null ? Float.NaN : (float) fix[0]);
    }

    private void refreshTime() {
        Calendar now = Calendar.getInstance();
        home.setTime(now);

        // The sky always gets a value — a synthetic one when we have no
        // reading — but the widget must not present invented weather as a
        // measurement, so it gets null and renders "--°" instead (spec §3.6).
        Weather w = weatherRepository.current(decimalHour());
        Weather shown = weatherRepository.hasReading() ? w : null;
        home.setWeather(shown);
        settings.setWeather(shown);
        sky.setWeather(w.w);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void refreshPermissionStatus() {
        boolean usageGranted = hasUsageAccess();
        boolean locationGranted = hasLocationPermission();
        settings.setPermissionStatus(locationGranted, usageGranted);
        setupScreen.setGranted(usageGranted, locationGranted);

        settings.setDeviceLockStatus(canLockDevice());
        settings.setNotificationShadeStatus(ShadeService.isEnabled(this));

        boolean defaultLauncher = isDefaultLauncher();
        settings.setDefaultLauncherStatus(defaultLauncher);
        home.setDefaultLauncherPromptVisible(!defaultLauncher);
    }

    private void requestLocation() {
        requestPermissions(
                new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    /**
     * True only when we can actually lock: the admin is active *and* it holds
     * {@code USES_POLICY_FORCE_LOCK}.
     *
     * <p>Both halves matter. {@code lockNow()} throws SecurityException for an
     * active admin that never declared force-lock, which is precisely how the
     * previous build crashed — {@code device_admin.xml} shipped an empty
     * {@code <uses-policies/>}. Anyone who activated that admin still has it
     * active after the update, so "active" alone is not enough to trust.
     */
    private boolean canLockDevice() {
        return dpm.isAdminActive(lockAdmin)
                && dpm.hasGrantedPolicy(lockAdmin, DeviceAdminInfo.USES_POLICY_FORCE_LOCK);
    }

    /** Long-press-home-to-lock (DESIGN_NOTES §9 delta 19): lock instantly if
     *  the admin is already active and armed, otherwise ask Android to show
     *  the one-time activation dialog. Re-checked on every {@link #onResume()},
     *  same as the other permission-adjacent flows in this activity. */
    private void lockOrRequestAdmin() {
        if (canLockDevice()) {
            try {
                dpm.lockNow();
                return;
            } catch (SecurityException ignored) {
                // The policy set disagrees with what the framework will honour
                // (an OEM restriction, a stale grant across the update that
                // added force-lock). Fall through and re-ask rather than die.
            }
        }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, lockAdmin);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets long-pressing the home screen lock your device instantly.");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            // No admin-activation screen to resolve it — nothing else we can do.
        }
    }

    /** DESIGN_NOTES §9 delta 20: RoleManager on 29+, a PackageManager
     *  comparison below that — API 26's floor predates RoleManager. */
    private boolean isDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            return rm != null && rm.isRoleHeld(RoleManager.ROLE_HOME);
        }
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = getPackageManager().resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && getPackageName().equals(resolved.activityInfo.packageName);
    }

    /**
     * Opens the system screen where this app can be picked as the home app.
     *
     * <p>Not {@code RoleManager.createRequestRoleIntent(ROLE_HOME)}, which is
     * what the previous build used and why the button appeared dead: ROLE_HOME
     * is marked non-requestable in the platform's role definitions, so the
     * system's request-role activity finishes immediately without ever drawing
     * a dialog. Third-party launchers have to send the user to the settings
     * screen instead, on every API level.
     *
     * <p>Three tries, narrowest first: the dedicated home-app screen, then the
     * default-apps list, then Settings itself. OEM builds vary in which of the
     * first two they ship.
     */
    private void requestDefaultLauncher() {
        if (startSafely(new Intent(Settings.ACTION_HOME_SETTINGS))) return;
        if (startSafely(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) return;
        startSafely(new Intent(Settings.ACTION_SETTINGS));
    }

    /**
     * Starts {@code intent}, reporting whether it went, so a caller can fall
     * through to its next candidate.
     *
     * <p>Deliberately no {@code resolveActivity} pre-check. Under API 30+
     * package visibility that call can answer null for an activity that would
     * have launched perfectly well, and a false negative here means a dead
     * button — the exact failure this method exists to end. Attempting the
     * start and catching is both the honest test and what the rest of this
     * activity already does.
     */
    private boolean startSafely(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    /** Sends the user to Accessibility settings to switch on the shade
     *  fallback — see {@link ShadeService}. */
    private void openAccessibilitySettings() {
        startSafely(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    /**
     * Swipe-down-opens-the-shade (DESIGN_NOTES §9 delta 21). Two routes, in
     * order of how little they ask of the user.
     *
     * <p>First the reflection into {@code StatusBarManager}, guarded by the
     * EXPAND_STATUS_BAR permission in the manifest. It needs no setup at all,
     * and still works on pre-Android-12 and on a number of OEM builds. It is
     * also a denylisted non-SDK interface, so at this app's targetSdk the
     * lookup itself throws on a current AOSP device — which is exactly why the
     * previous build's swipe did nothing, silently.
     *
     * <p>Then {@link ShadeService}, the accessibility fallback, which does
     * work everywhere but only once the user has switched it on. While it is
     * off this method still ends in a no-op; the Settings row is where that
     * gets explained and fixed.
     */
    private void expandStatusBar() {
        if (expandViaStatusBarManager()) return;
        ShadeService.expandNotificationShade();
    }

    private boolean expandViaStatusBarManager() {
        try {
            Object statusBarService = getSystemService("statusbar");
            if (statusBarService == null) return false;
            Class<?> statusBarManager = Class.forName("android.app.StatusBarManager");
            statusBarManager.getMethod("expandNotificationsPanel").invoke(statusBarService);
            return true;
        } catch (Throwable ignored) {
            // Blocked, absent, or refused — fall through to the service.
            return false;
        }
    }

    /** Pulls fresh device usage data and drives the panel, the over-limit
     *  wallpaper desaturation, and the clock widget's marker from it. */
    private void refreshUsage() {
        long now = System.currentTimeMillis();
        long today = usageRepository.todayMillis(now);
        long[] last7 = usageRepository.last7DaysMillis(now);
        int pickups = usageRepository.pickupsToday(now);
        List<UsageRepository.AppUsage> mostUsed = usageRepository.mostUsedToday(now, 6);
        screenTime.setUsage(today, last7, pickups, mostUsed);

        int limit = prefs.limit();
        boolean over = UsageMath.isOverLimit(today, limit);
        float overage = UsageMath.usageFraction(today, limit) - 1f;
        sky.setDesaturation(Math.max(0f, Math.min(1f, overage)));
        home.clock.setOverLimit(over);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION) {
            refreshPermissionStatus();
            // Just granted: go and get a reading now rather than waiting out
            // the freshness window with an empty widget.
            weatherRepository.refresh(true, this::refreshTime);
            refreshSkyLocation();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ticker.post(minuteTick);
        sky.resume();
        drawer.refresh();
        refreshPermissionStatus();
        refreshUsage();
        refreshSkyLocation();
        weatherRepository.refresh(false, this::refreshTime);
    }

    @Override protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(minuteTick);
        sky.pause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        refreshPalette();
    }

    /** Back must never leave the home screen. */
    @Override public void onBackPressed() {
        if (search.isOpen()) {
            search.close();
        } else if (sheet.isOpen()) {
            sheet.close();
        } else if (root.currentView() != LauncherRoot.VIEW_HOME) {
            root.goTo(LauncherRoot.VIEW_HOME);
        }
    }
}
