package com.retro.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.PaletteResolver;
import com.retro.launcher.core.Weather;
import com.retro.launcher.data.AppEntry;
import com.retro.launcher.data.AppRepository;
import com.retro.launcher.data.Prefs;
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
import com.retro.launcher.ui.HomePanel;
import com.retro.launcher.ui.LauncherRoot;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class HomeActivity extends Activity {

    /** Tier 2 gate switch — see design spec §3.4. Flip and re-measure via
     *  the "IconBench" logcat tag, then delete whichever loses. */
    private static final boolean USE_POSTERIZED_ICONS = false;

    private LauncherRoot root;
    private SkyView sky;
    private HomePanel home;
    private DrawerPanel drawer;
    private BottomSheet sheet;
    private AppRepository appRepository;
    private Prefs prefs;
    private Metrics metrics;
    private WeatherRepository weatherRepository;
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
            ticker.postDelayed(this, 60_000L);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        goEdgeToEdge();

        prefs = new Prefs(this);
        weatherRepository = new WeatherRepository();
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

        home.dock.setOnSlotActionListener(new DockView.SlotActionListener() {
            @Override public void onReplace(int slotIndex) { openDockSheet(slotIndex); }
            @Override public void onAdd() { openDockSheet(-1); }
        });

        root.setPanels(home, blank(0xFF202020), drawer, blank(0xFF404040));
        root.setDoubleTapListener(() -> Log.d("HomeActivity", "double-tap search stub — real overlay lands in Tier 5"));

        FrameLayout stack = new FrameLayout(this);
        stack.addView(sky);   // z=0, behind everything, never moves
        stack.addView(root);
        stack.addView(sheet); // overlay, above every panel
        setContentView(stack);

        refreshPalette();
        refreshTime();
        drawer.refresh();

        registerReceiver(packageReceiver, packageChangeFilter());
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
                sheet.close();
            });
        }
    }

    private View blank(int color) {
        View v = new View(this);
        v.setBackgroundColor(color);
        return v;
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
        }
    }

    private void refreshTime() {
        Calendar now = Calendar.getInstance();
        home.setTime(now);
        Weather w = weatherRepository.current(decimalHour());
        home.setWeather(w);
        sky.setWeather(w.w);
    }

    @Override protected void onResume() {
        super.onResume();
        ticker.post(minuteTick);
        sky.resume();
        drawer.refresh();
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
        if (sheet.isOpen()) {
            sheet.close();
        } else if (root.currentView() != LauncherRoot.VIEW_HOME) {
            root.goTo(LauncherRoot.VIEW_HOME);
        }
    }
}
