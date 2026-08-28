package com.retro.launcher;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.PaletteResolver;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.sky.SkyView;
import com.retro.launcher.ui.LauncherRoot;

import java.util.Calendar;

public class HomeActivity extends Activity {

    private LauncherRoot root;
    private SkyView sky;
    private Prefs prefs;
    private Metrics metrics;
    private Palette palette;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            refreshPalette();
            ticker.postDelayed(this, 60_000L);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        goEdgeToEdge();

        prefs = new Prefs(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        metrics = new Metrics(dm.widthPixels, dm.density, dm.scaledDensity);

        sky = new SkyView(this);

        root = new LauncherRoot(this);
        // Tier 0: blank colour-filled panels prove navigation before any
        // content exists. Each is replaced by its real panel in later tiers.
        // The home panel stays transparent so the sky shows through it.
        root.setPanels(blank(Color.TRANSPARENT), blank(0xFF202020),
                       blank(0xFF303030), blank(0xFF404040));

        FrameLayout stack = new FrameLayout(this);
        stack.addView(sky);   // z=0, behind everything, never moves
        stack.addView(root);
        setContentView(stack);

        refreshPalette();
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
            // Tier 1 hooks Tint and the sky renderer in here.
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ticker.post(minuteTick);
        sky.resume();
    }

    @Override protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(minuteTick);
        sky.pause();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        refreshPalette();
    }

    /** Back must never leave the home screen. */
    @Override public void onBackPressed() {
        if (root.currentView() != LauncherRoot.VIEW_HOME) {
            root.goTo(LauncherRoot.VIEW_HOME);
        }
    }
}
