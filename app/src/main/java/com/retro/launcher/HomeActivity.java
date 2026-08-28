package com.retro.launcher;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;

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
import com.retro.launcher.sky.SkyView;
import com.retro.launcher.ui.BottomSheet;
import com.retro.launcher.ui.DockView;
import com.retro.launcher.ui.DrawerPanel;
import com.retro.launcher.ui.HintOverlay;
import com.retro.launcher.ui.HomePanel;
import com.retro.launcher.ui.LauncherRoot;
import com.retro.launcher.ui.ScreenTimePanel;
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
        home = new HomePanel(this, metrics, prefs);
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
        });

        screenTime = new ScreenTimePanel(this, metrics, prefs);
        screenTime.setOnCloseListener(() -> root.goTo(LauncherRoot.VIEW_HOME));
        screenTime.setOnLimitChangedListener(this::refreshUsage);

        // Tapping the weather line asks for a fresh reading. The repository's
        // 10-minute floor means leaning on it cannot become a poll.
        home.clock.setOnWeatherTap(() -> weatherRepository.refresh(true, this::refreshTime));

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
    }

    private void requestLocation() {
        requestPermissions(
                new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
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
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ticker.post(minuteTick);
        sky.resume();
        drawer.refresh();
        refreshPermissionStatus();
        refreshUsage();
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
