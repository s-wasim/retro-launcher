package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.retro.launcher.core.DateFormatter;
import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.PaletteResolver;
import com.retro.launcher.core.Palettes;
import com.retro.launcher.core.Weather;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.theme.Tint;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tier 3 — the fourth panel. Four DESIGN_NOTES §7c sections (PALETTE,
 * CLOCK &amp; DATE, TEMPERATURE, DOCK) plus a native-only PERMISSIONS block
 * (spec §5) with live status and a fix button, so a skipped first-run setup
 * is recoverable without a reinstall. Every control writes straight through
 * {@link Prefs} and calls {@code onPrefsChanged} so the caller can refresh
 * whatever else depends on it (palette, clock, dock).
 */
public final class SettingsPanel extends FrameLayout {

    public interface DockActionListener {
        void onReplace(int slotIndex);
        void onAdd();
    }

    public interface PermissionActionListener {
        void onRequestLocation();
        void onOpenUsageAccessSettings();
        void onEnableDeviceLock();
        void onSetDefaultLauncher();
    }

    private static final int CUSTOM_IDX = DateFormatter.PRESETS.length;

    private final Metrics metrics;
    private final Prefs prefs;

    private final LinearLayout header;
    private final int headerPadTop;
    private final LinearLayout paletteSection;
    private final LinearLayout clockSection;
    private final LinearLayout tempSection;
    private final LinearLayout dockSection;
    private final LinearLayout permSection;

    private Runnable onPrefsChanged = () -> {};
    private Runnable onClose = () -> {};
    private DockActionListener dockListener;
    private PermissionActionListener permissionListener;

    private Palette palette;
    private Weather weather;
    private List<String> dockEntries = new ArrayList<>();
    private boolean locationGranted;
    private boolean usageGranted;
    private boolean deviceLockActive;
    private boolean isDefaultLauncher;

    public SettingsPanel(Context context, Metrics metrics, Prefs prefs) {
        super(context);
        this.metrics = metrics;
        this.prefs = prefs;

        Tint.setRole(this, Tint.ROLE_BG);
        setBackgroundColor(0xFF000000);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        column.addView(header = buildHeader());
        headerPadTop = header.getPaddingTop();

        ScrollView scroll = new ScrollView(context);
        // Vertical only: a swipe left across the settings body closes the
        // panel. The scrubber and toggles inside still claim their own drags.
        LauncherRoot.setVerticalScroller(scroll);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int sidePad = Math.round(metrics.cqw(4.5f));
        content.setPadding(sidePad, 0, sidePad, Math.round(metrics.cqw(8f)));

        content.addView(paletteSection = section());
        content.addView(clockSection = section());
        content.addView(tempSection = section());
        content.addView(dockSection = section());
        content.addView(permSection = section());

        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout section() {
        LinearLayout s = new LinearLayout(getContext());
        s.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(metrics.cqw(6f));
        s.setLayoutParams(lp);
        return s;
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(metrics.cqw(4.5f));
        int padTop = Math.round(metrics.cqw(5f));
        int padBottom = Math.round(metrics.cqw(3f));
        header.setPadding(padH, padTop, padH, padBottom);

        TextView title = new TextView(getContext());
        title.setText("SETTINGS");
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setAllCaps(true);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_TITLE_CQW, DrawerPanel.SIZE_TITLE_MIN));
        Tint.setRole(title, Tint.ROLE_INK);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(getContext());
        close.setText("CLOSE");
        close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        int closePad = Math.round(metrics.cqw(3f));
        close.setPadding(closePad, closePad, closePad, closePad);
        Tint.setRole(close, Tint.ROLE_P);
        close.setOnClickListener(v -> onClose.run());
        header.addView(close);

        // The header sits outside the no-swipe scroll content below it — mark
        // it no-swipe too, otherwise a tap that lands a hair off CLOSE reads
        // as the start of a horizontal drag and snaps the panel home anyway.
        LauncherRoot.setNoSwipe(header);

        return header;
    }

    @Override public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.graphics.Insets sys = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            header.setPadding(header.getPaddingLeft(), headerPadTop + sys.top,
                    header.getPaddingRight(), header.getPaddingBottom());
        }
        return super.onApplyWindowInsets(insets);
    }

    public void setOnCloseListener(Runnable r) { this.onClose = r; }
    public void setOnPrefsChangedListener(Runnable r) { this.onPrefsChanged = r; }
    public void setDockActionListener(DockActionListener l) { this.dockListener = l; }
    public void setPermissionActionListener(PermissionActionListener l) { this.permissionListener = l; }

    public void setPalette(Palette p) {
        this.palette = p;
        Tint.apply(this, p);
        rebuildAll();
    }

    public void setWeather(Weather w) {
        this.weather = w;
        rebuildTempSection();
    }

    public void setDockEntries(List<String> entries) {
        this.dockEntries = entries;
        rebuildDockSection();
    }

    public void setPermissionStatus(boolean locationGranted, boolean usageGranted) {
        this.locationGranted = locationGranted;
        this.usageGranted = usageGranted;
        rebuildPermissionsSection();
    }

    /** DESIGN_NOTES §9 delta 19: whether long-press-home-to-lock is armed. */
    public void setDeviceLockStatus(boolean active) {
        this.deviceLockActive = active;
        rebuildPermissionsSection();
    }

    /** DESIGN_NOTES §9 delta 20: whether this app is the default launcher. */
    public void setDefaultLauncherStatus(boolean isDefault) {
        this.isDefaultLauncher = isDefault;
        rebuildPermissionsSection();
    }

    private void rebuildAll() {
        rebuildPaletteSection();
        rebuildClockSection();
        rebuildTempSection();
        rebuildDockSection();
        rebuildPermissionsSection();
    }

    // ---- PALETTE -----------------------------------------------------

    private void rebuildPaletteSection() {
        paletteSection.removeAllViews();
        if (palette == null) return;

        paletteSection.addView(sectionHeader("PALETTE"));

        List<View> cards = new ArrayList<>();
        cards.add(paletteCard(PaletteResolver.AUTO, true));
        for (String id : Palettes.IDS) cards.add(paletteCard(id, false));
        addGrid(paletteSection, cards, 2);

        int gap = Math.round(metrics.cqw(3f));
        LinearLayout themeRow = singleSelectRow(
                new String[]{"AUTO", "LIGHT", "DARK"},
                new String[]{PaletteResolver.SYSTEM, PaletteResolver.LIGHT, PaletteResolver.DARK},
                prefs.theme(),
                value -> { prefs.putString(Prefs.K_THEME, value); onPrefsChanged.run(); });
        addTopMargin(themeRow, gap);
        paletteSection.addView(themeRow);

        LinearLayout tintRow = toggleRow("TINT WALLPAPER TO PALETTE", prefs.tint(),
                value -> { prefs.putBool(Prefs.K_TINT, value); onPrefsChanged.run(); });
        addTopMargin(tintRow, gap);
        paletteSection.addView(tintRow);
    }

    private View paletteCard(String id, boolean auto) {
        boolean selected = auto
                ? PaletteResolver.AUTO.equals(prefs.palette())
                : id.equals(prefs.palette());
        Palette shown = auto
                ? Palettes.get(PaletteResolver.autoIdFor(decimalHour()), palette.dark)
                : Palettes.get(id, palette.dark);

        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(metrics.cqw(3f));
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(metrics.cqw(1.5f));
        int border = Math.max(1, Math.round(metrics.cqw(0.7f)));
        bg.setStroke(border, selected ? palette.p : ((0x55 << 24) | (palette.ink & 0x00FFFFFF)));
        bg.setColor(selected ? ((0x2E << 24) | (palette.p & 0x00FFFFFF)) : 0x00000000);
        card.setBackground(bg);

        TextView name = new TextView(getContext());
        name.setText(auto ? "AUTO / TIME" : shown.label);
        name.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        name.setAllCaps(true);
        name.setTextColor(palette.ink);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        card.addView(name);

        if (auto) {
            TextView note = new TextView(getContext());
            note.setText(PaletteResolver.autoLabelFor(decimalHour()) + " · " + shown.label);
            note.setTypeface(Typeface.MONOSPACE);
            note.setAllCaps(true);
            note.setTextColor(palette.p);
            note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
            card.addView(note);
        }

        LinearLayout ramp = new LinearLayout(getContext());
        ramp.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rampLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rampLp.topMargin = Math.round(metrics.cqw(1.5f));
        ramp.setLayoutParams(rampLp);
        int[] chips = { shown.bg, shown.tile, shown.p, shown.a, shown.s, shown.h };
        int chipSize = Math.round(metrics.cqw(3.2f));
        int chipGap = Math.round(metrics.cqw(0.8f));
        for (int i = 0; i < chips.length; i++) {
            View chip = new View(getContext());
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setColor(chips[i]);
            chip.setBackground(chipBg);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(chipSize, chipSize);
            if (i > 0) clp.leftMargin = chipGap;
            ramp.addView(chip, clp);
        }
        card.addView(ramp);

        card.setOnClickListener(v -> {
            prefs.putString(Prefs.K_PAL, auto ? PaletteResolver.AUTO : id);
            onPrefsChanged.run();
        });
        return card;
    }

    // ---- CLOCK & DATE --------------------------------------------------

    private void rebuildClockSection() {
        clockSection.removeAllViews();
        if (palette == null) return;

        clockSection.addView(sectionHeader("CLOCK & DATE"));

        int gap = Math.round(metrics.cqw(3f));
        LinearLayout hourRow = singleSelectRow(
                new String[]{"12-HOUR", "24-HOUR"}, new String[]{"12", "24"},
                prefs.hour12() ? "12" : "24",
                value -> { prefs.putBool(Prefs.K_HOUR12, "12".equals(value)); onPrefsChanged.run(); });
        addTopMargin(hourRow, gap);
        clockSection.addView(hourRow);

        Calendar now = Calendar.getInstance();
        int fmtIdx = prefs.fmtIdx();
        for (int i = 0; i < DateFormatter.PRESETS.length; i++) {
            String pattern = DateFormatter.PRESETS[i];
            View row = formatRow(pattern, pattern, fmtIdx == i, now, i);
            addTopMargin(row, gap);
            clockSection.addView(row);
        }
        View customRow = formatRow("CUSTOM", prefs.custom().isEmpty() ? "—" : prefs.custom(),
                fmtIdx == CUSTOM_IDX, now, CUSTOM_IDX);
        addTopMargin(customRow, gap);
        clockSection.addView(customRow);

        if (fmtIdx == CUSTOM_IDX) {
            View builder = customBuilder(now);
            addTopMargin(builder, gap);
            clockSection.addView(builder);
        }
    }

    private View formatRow(String left, String pattern, boolean selected, Calendar now, int idx) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padV = Math.round(metrics.cqw(1.5f));
        row.setPadding(0, padV, 0, padV);

        TextView leftView = new TextView(getContext());
        leftView.setText(left);
        leftView.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        leftView.setTextColor(selected ? palette.p : palette.ink);
        leftView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        row.addView(leftView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView preview = new TextView(getContext());
        String previewText = idx == CUSTOM_IDX
                ? (prefs.custom().isEmpty() ? "—" : DateFormatter.format(prefs.custom(),
                        now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH),
                        now.get(Calendar.DAY_OF_WEEK) - 1))
                : DateFormatter.format(pattern, now.get(Calendar.YEAR), now.get(Calendar.MONTH),
                        now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.DAY_OF_WEEK) - 1);
        preview.setText(previewText);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextColor(palette.a);
        preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        row.addView(preview);

        row.setOnClickListener(v -> {
            prefs.putInt(Prefs.K_FMT_IDX, idx);
            onPrefsChanged.run();
        });
        return row;
    }

    private View customBuilder(Calendar now) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(metrics.cqw(3f));
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(metrics.cqw(1.5f));
        bg.setStroke(Math.max(1, Math.round(metrics.cqw(0.7f))), palette.p,
                metrics.dp(3f), metrics.dp(3f));
        box.setBackground(bg);

        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView preview = new TextView(getContext());
        String custom = prefs.custom();
        preview.setText(custom.isEmpty() ? "—" : DateFormatter.format(custom,
                now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.DAY_OF_WEEK) - 1));
        preview.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        preview.setTextColor(palette.ink);
        preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        top.addView(preview, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = new TextView(getContext());
        clear.setText("CLEAR");
        clear.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clear.setTextColor(palette.a);
        clear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        clear.setOnClickListener(v -> {
            prefs.putString(Prefs.K_CUSTOM, "");
            onPrefsChanged.run();
        });
        top.addView(clear);
        box.addView(top);

        HorizontalScrollView tokenScroll = new HorizontalScrollView(getContext());
        tokenScroll.setHorizontalScrollBarEnabled(false);
        LauncherRoot.setNoSwipe(tokenScroll, LauncherRoot.AXIS_H);
        LinearLayout tokenRow = new LinearLayout(getContext());
        tokenRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tokenRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tokenRowLp.topMargin = Math.round(metrics.cqw(2f));
        tokenRow.setLayoutParams(tokenRowLp);
        int tokenGap = Math.round(metrics.cqw(2f));
        for (String token : DateFormatter.TOKENS) {
            TextView chip = new TextView(getContext());
            chip.setText(token.equals(" ") ? "␣" : token);
            chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            chip.setTextColor(palette.p);
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    metrics.textPx(DrawerPanel.SIZE_TAB_CQW, DrawerPanel.SIZE_TAB_MIN));
            int chipPad = Math.round(metrics.cqw(1.8f));
            chip.setPadding(chipPad, chipPad, chipPad, chipPad);
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setStroke(Math.max(1, Math.round(metrics.cqw(0.5f))), palette.p);
            chipBg.setCornerRadius(metrics.cqw(1f));
            chip.setBackground(chipBg);
            chip.setOnClickListener(v -> {
                prefs.putString(Prefs.K_CUSTOM, prefs.custom() + token);
                onPrefsChanged.run();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = tokenGap;
            tokenRow.addView(chip, clp);
        }
        tokenScroll.addView(tokenRow);
        box.addView(tokenScroll);

        return box;
    }

    // ---- TEMPERATURE -----------------------------------------------------

    private void rebuildTempSection() {
        tempSection.removeAllViews();
        if (palette == null) return;

        tempSection.addView(sectionHeader("TEMPERATURE"));

        int gap = Math.round(metrics.cqw(3f));
        LinearLayout unitRow = singleSelectRow(
                new String[]{"CELSIUS °C", "FAHRENHEIT °F"}, new String[]{"C", "F"},
                prefs.unit(),
                value -> { prefs.putString(Prefs.K_UNIT, value); onPrefsChanged.run(); });
        addTopMargin(unitRow, gap);
        tempSection.addView(unitRow);

        TextView caption = new TextView(getContext());
        // Null means no live reading — the sky is running on the synthetic
        // stand-in, and saying so beats implying the dash is a measurement.
        caption.setText(weather != null
                ? "OPEN-METEO — " + weather.label + " · " + weather.tempIn(prefs.unit()) + "°"
                : "NO READING — SYNTHETIC SKY · NEEDS LOCATION");
        caption.setTypeface(Typeface.MONOSPACE);
        caption.setTextColor(palette.a);
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        addTopMargin(caption, gap);
        tempSection.addView(caption);
    }

    // ---- DOCK --------------------------------------------------------

    private void rebuildDockSection() {
        dockSection.removeAllViews();
        if (palette == null) return;

        dockSection.addView(sectionHeader("DOCK — BOTTOM LEFT"));

        int gap = Math.round(metrics.cqw(2.5f));
        for (int i = 0; i < dockEntries.size(); i++) {
            View row = dockRow(DockView.labelFor(dockEntries.get(i)), false, i);
            addTopMargin(row, gap);
            dockSection.addView(row);
        }
        if (dockEntries.size() < 5) {
            View row = dockRow("EMPTY SLOT", true, -1);
            addTopMargin(row, gap);
            dockSection.addView(row);
        }

        TextView caption = new TextView(getContext());
        caption.setText("LONG-PRESS A DOCK SLOT ON THE HOME SCREEN TO REPLACE IT. MAX 5.");
        caption.setTypeface(Typeface.MONOSPACE);
        caption.setTextColor(palette.a);
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        addTopMargin(caption, Math.round(metrics.cqw(3f)));
        dockSection.addView(caption);
    }

    private View dockRow(String label, boolean empty, int slotIndex) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView glyph = new TextView(getContext());
        glyph.setGravity(Gravity.CENTER);
        glyph.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        glyph.setText(empty ? "+" : label.substring(0, 1));
        int glyphSize = Math.round(metrics.cqw(9f));
        GradientDrawable glyphBg = new GradientDrawable();
        glyphBg.setCornerRadius(metrics.cqw(1.2f));
        if (empty) {
            glyphBg.setStroke(Math.max(1, Math.round(metrics.cqw(0.6f))), palette.p,
                    metrics.dp(3f), metrics.dp(3f));
            glyph.setTextColor(palette.p);
        } else {
            glyphBg.setColor(palette.tile);
            glyph.setTextColor(palette.p);
        }
        glyph.setBackground(glyphBg);
        glyph.setLayoutParams(new LinearLayout.LayoutParams(glyphSize, glyphSize));
        row.addView(glyph);

        TextView labelView = new TextView(getContext());
        labelView.setText(label);
        labelView.setTypeface(Typeface.MONOSPACE);
        labelView.setAllCaps(true);
        labelView.setTextColor(palette.ink);
        labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = Math.round(metrics.cqw(3f));
        row.addView(labelView, labelLp);

        TextView action = new TextView(getContext());
        action.setText(empty ? "ADD" : "REPLACE");
        action.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        action.setTextColor(palette.p);
        action.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        row.addView(action);

        row.setOnClickListener(v -> {
            if (dockListener == null) return;
            if (empty) dockListener.onAdd();
            else dockListener.onReplace(slotIndex);
        });
        return row;
    }

    // ---- PERMISSIONS -----------------------------------------------------

    private void rebuildPermissionsSection() {
        permSection.removeAllViews();
        if (palette == null) return;

        permSection.addView(sectionHeader("PERMISSIONS"));

        int gap = Math.round(metrics.cqw(2.5f));
        View locationRow = permissionRow("LOCATION", locationGranted,
                () -> { if (permissionListener != null) permissionListener.onRequestLocation(); });
        addTopMargin(locationRow, gap);
        permSection.addView(locationRow);

        View usageRow = permissionRow("USAGE ACCESS", usageGranted,
                () -> { if (permissionListener != null) permissionListener.onOpenUsageAccessSettings(); });
        addTopMargin(usageRow, gap);
        permSection.addView(usageRow);

        View lockRow = permissionRow("DEVICE LOCK", deviceLockActive, "ON", "ENABLE",
                () -> { if (permissionListener != null) permissionListener.onEnableDeviceLock(); });
        addTopMargin(lockRow, gap);
        permSection.addView(lockRow);

        View defaultRow = permissionRow("DEFAULT LAUNCHER", isDefaultLauncher, "DEFAULT", "SET",
                () -> { if (permissionListener != null) permissionListener.onSetDefaultLauncher(); });
        addTopMargin(defaultRow, gap);
        permSection.addView(defaultRow);

        TextView caption = new TextView(getContext());
        caption.setText("WEATHER NEEDS LOCATION · SCREEN TIME NEEDS USAGE ACCESS · "
                + "DEVICE LOCK NEEDS ADMIN ACCESS. THE LAUNCHER WORKS WITHOUT ANY OF THEM. "
                + "LONG-PRESS THE HOME SCREEN TO LOCK ONCE ENABLED.");
        caption.setTypeface(Typeface.MONOSPACE);
        caption.setTextColor(palette.a);
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        addTopMargin(caption, Math.round(metrics.cqw(3f)));
        permSection.addView(caption);
    }

    private View permissionRow(String label, boolean granted, Runnable onFix) {
        return permissionRow(label, granted, "GRANTED", "FIX", onFix);
    }

    /** {@code grantedText}/{@code fixText} generalize this beyond runtime
     *  permissions — DEVICE LOCK and DEFAULT LAUNCHER read "ON"/"ENABLE" and
     *  "DEFAULT"/"SET" through the same row rather than "GRANTED"/"FIX". */
    private View permissionRow(String label, boolean granted,
                                String grantedText, String fixText, Runnable onFix) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(getContext());
        labelView.setText(label);
        labelView.setTypeface(Typeface.MONOSPACE);
        labelView.setTextColor(palette.ink);
        labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = new TextView(getContext());
        status.setText(granted ? grantedText : fixText);
        status.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        status.setTextColor(granted ? palette.p : palette.a);
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        if (!granted) status.setOnClickListener(v -> onFix.run());
        row.addView(status);

        return row;
    }

    // ---- shared row builders -----------------------------------------

    private TextView sectionHeader(String title) {
        TextView t = new TextView(getContext());
        t.setText(title);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setAllCaps(true);
        t.setTextColor(palette.p);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        return t;
    }

    /** One tappable value out of {@code values}, highlighted when it matches {@code current}. */
    private LinearLayout singleSelectRow(String[] labels, String[] values, String current,
                                          Consumer<String> onSelect) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        int gap = Math.round(metrics.cqw(2.5f));
        for (int i = 0; i < labels.length; i++) {
            boolean selected = values[i].equals(current);
            String value = values[i];
            View chip = buildChip(labels[i], selected, () -> onSelect.accept(value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = gap;
            row.addView(chip, lp);
        }
        return row;
    }

    private View buildChip(String label, boolean selected, Runnable onClick) {
        TextView chip = new TextView(getContext());
        chip.setText(label);
        chip.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        chip.setAllCaps(true);
        chip.setTextColor(selected ? palette.p : palette.ink);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_TAB_CQW, DrawerPanel.SIZE_TAB_MIN));
        int padH = Math.round(metrics.cqw(3f));
        int padV = Math.round(metrics.cqw(1.8f));
        chip.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(metrics.cqw(1f));
        bg.setStroke(Math.max(1, Math.round(metrics.cqw(0.6f))), selected ? palette.p : palette.ink);
        bg.setColor(selected ? ((0x2E << 24) | (palette.p & 0x00FFFFFF)) : 0x00000000);
        chip.setBackground(bg);
        chip.setOnClickListener(v -> onClick.run());
        return chip;
    }

    private LinearLayout toggleRow(String label, boolean checked, Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(getContext());
        labelView.setText(label);
        labelView.setTypeface(Typeface.MONOSPACE);
        labelView.setAllCaps(true);
        labelView.setTextColor(palette.ink);
        labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        PixelToggle toggle = new PixelToggle(getContext(), metrics);
        toggle.setPalette(palette);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(onChange);
        row.addView(toggle);

        return row;
    }

    private void addGrid(LinearLayout parent, List<View> cards, int columns) {
        int gap = Math.round(metrics.cqw(3f));
        for (int i = 0; i < cards.size(); i += columns) {
            LinearLayout gridRow = new LinearLayout(getContext());
            gridRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) rowLp.topMargin = gap;
            gridRow.setLayoutParams(rowLp);
            for (int c = 0; c < columns; c++) {
                int idx = i + c;
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (c > 0) cellLp.leftMargin = gap;
                if (idx < cards.size()) gridRow.addView(cards.get(idx), cellLp);
                else gridRow.addView(new View(getContext()), cellLp);
            }
            parent.addView(gridRow);
        }
    }

    private void addTopMargin(View v, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (v.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams existing = (LinearLayout.LayoutParams) v.getLayoutParams();
            existing.topMargin = margin;
            v.setLayoutParams(existing);
            return;
        }
        lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
        lp.topMargin = margin;
        v.setLayoutParams(lp);
    }

    /** Current time as a decimal hour, matching HomeActivity's own helper. */
    private float decimalHour() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY)
                + c.get(Calendar.MINUTE) / 60f
                + c.get(Calendar.SECOND) / 3600f;
    }
}
