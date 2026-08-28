package com.retro.launcher.ui;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.data.AppEntry;
import com.retro.launcher.data.AppRepository;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.icons.IconSource;
import com.retro.launcher.theme.Tint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * The app drawer: tab strip, alphabetical list with section headers, and the
 * A-Z scrubber. See DESIGN_NOTES §7b. Long-press a row opens a small
 * quick-action box (Launch / Uninstall-Disable / More Details, issue #5);
 * long-press a category tab opens the membership sheet for that category,
 * which replaces the prototype's per-app category sheet.
 */
public final class DrawerPanel extends FrameLayout {

    /**
     * Shared type/icon scale for the app drawer and every sheet it opens
     * (dock picker, category membership). One set of sizes so every
     * sub-setting reads at the same scale — see fix "consistent text/icon
     * sizes across sub-settings" (issue #3).
     */
    public static final float SIZE_TITLE_CQW   = 5.2f, SIZE_TITLE_MIN   = 16f;
    public static final float SIZE_ACTION_CQW  = 3.8f, SIZE_ACTION_MIN  = 13f;
    public static final float SIZE_TAB_CQW     = 3.6f, SIZE_TAB_MIN     = 12f;
    public static final float SIZE_ROW_CQW     = 4.0f, SIZE_ROW_MIN     = 13f;
    public static final float SIZE_CAPTION_CQW = 3.0f, SIZE_CAPTION_MIN = 10f;
    public static final float SIZE_ICON_CQW    = 12f;

    private final Metrics metrics;
    private final Prefs prefs;
    private final AppRepository repository;
    private final IconSource icons;
    private final BottomSheet sheet;

    private final LinearLayout header;
    private final LinearLayout tabStrip;
    private final ListView listView;
    private final AlphaScrubber scrubber;
    private final DrawerAdapter adapter;
    private final int headerPadTop;

    private List<AppEntry> allApps = new ArrayList<>();
    private String activeTab = "ALL";
    private Palette palette;
    private Runnable onHome;

    public DrawerPanel(Context context, Metrics metrics, Prefs prefs,
                        AppRepository repository, IconSource icons, BottomSheet sheet) {
        super(context);
        this.metrics = metrics;
        this.prefs = prefs;
        this.repository = repository;
        this.icons = icons;
        this.sheet = sheet;

        Tint.setRole(this, Tint.ROLE_BG);
        setBackgroundColor(0xFF000000);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        column.addView(header = buildHeader());
        headerPadTop = header.getPaddingTop();

        HorizontalScrollView tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(context);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Math.round(metrics.cqw(4.5f));
        tabStrip.setPadding(pad, 0, pad, pad);
        tabScroll.addView(tabStrip);
        LauncherRoot.setNoSwipe(tabScroll);
        column.addView(tabScroll);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);

        adapter = new DrawerAdapter();
        listView = new ListView(context);
        listView.setAdapter(adapter);
        listView.setDivider(null);
        LauncherRoot.setNoSwipe(listView);
        listView.setOnItemClickListener((AdapterView<?> parent, View v, int position, long id) -> {
            Object item = adapter.getItem(position);
            if (item instanceof AppEntry) launch((AppEntry) item);
        });
        listView.setOnItemLongClickListener((AdapterView<?> parent, View v, int position, long id) -> {
            Object item = adapter.getItem(position);
            if (item instanceof AppEntry) { showAppActions((AppEntry) item, v); return true; }
            return false;
        });
        row.addView(listView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        scrubber = new AlphaScrubber(context, metrics);
        scrubber.setOnLetterListener(letter -> {
            Integer pos = adapter.positionForLetter(letter);
            if (pos != null) listView.setSelection(pos);
        });
        row.addView(scrubber, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        column.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(metrics.cqw(4.5f));
        int padTop = Math.round(metrics.cqw(5f));
        int padBottom = Math.round(metrics.cqw(3f));
        header.setPadding(padH, padTop, padH, padBottom);

        TextView headerTitle = new TextView(getContext());
        headerTitle.setText("APPS");
        headerTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        headerTitle.setAllCaps(true);
        headerTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.textPx(SIZE_TITLE_CQW, SIZE_TITLE_MIN));
        Tint.setRole(headerTitle, Tint.ROLE_INK);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView homeButton = new TextView(getContext());
        homeButton.setText("HOME");
        homeButton.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        homeButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.textPx(SIZE_ACTION_CQW, SIZE_ACTION_MIN));
        int homePad = Math.round(metrics.cqw(3f));
        homeButton.setPadding(homePad, homePad, homePad, homePad);
        Tint.setRole(homeButton, Tint.ROLE_P);
        homeButton.setOnClickListener(v -> { if (onHome != null) onHome.run(); });
        header.addView(homeButton);

        // The header sits above the no-swipe tab strip/list below it — mark
        // it no-swipe too, otherwise a tap that lands a hair off HOME reads
        // as the start of a horizontal drag and snaps the drawer home anyway.
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

    public void setOnHomeListener(Runnable r) { this.onHome = r; }

    public void setPalette(Palette p) {
        this.palette = p;
        Tint.apply(this, p);
        scrubber.setPalette(p);
        rebuildTabs();
        adapter.notifyDataSetChanged();
    }

    /** Re-queries installed apps. Call on resume and on package add/remove. */
    public void refresh() {
        allApps = repository.load();
        rebuildTabs();
        applyFilter();
    }

    private void rebuildTabs() {
        tabStrip.removeAllViews();
        List<String> cats = prefs.categories();
        addTabChip("ALL", false);
        for (String cat : cats) addTabChip(cat, true);
        addAddTabChip();
    }

    private void addTabChip(String name, boolean deletable) {
        LinearLayout chip = new LinearLayout(getContext());
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(metrics.cqw(3f));
        int padV = Math.round(metrics.cqw(1.5f));
        chip.setPadding(padH, padV, padH, padV);

        TextView label = new TextView(getContext());
        label.setText(name);
        label.setTypeface(Typeface.MONOSPACE, name.equals(activeTab) ? Typeface.BOLD : Typeface.NORMAL);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.textPx(SIZE_TAB_CQW, SIZE_TAB_MIN));
        if (palette != null) label.setTextColor(name.equals(activeTab) ? palette.p : palette.ink);
        chip.addView(label);

        if (deletable) {
            TextView close = new TextView(getContext());
            close.setText(" ×");
            close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            if (palette != null) close.setTextColor(palette.a);
            close.setOnClickListener(v -> deleteCategory(name));
            chip.addView(close);
            chip.setOnLongClickListener(v -> { openMembershipSheet(name); return true; });
        }

        chip.setOnClickListener(v -> { activeTab = name; rebuildTabs(); applyFilter(); });
        tabStrip.addView(chip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addAddTabChip() {
        TextView plus = new TextView(getContext());
        plus.setText("+");
        plus.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        if (palette != null) plus.setTextColor(palette.p);
        int padH = Math.round(metrics.cqw(3f));
        plus.setPadding(padH, 0, padH, 0);
        plus.setOnClickListener(v -> {
            if (palette != null) sheet.setPalette(palette);
            sheet.open("NEW CATEGORY");
            sheet.showAddField("CATEGORY NAME", this::addCategory);
        });
        tabStrip.addView(plus);
    }

    private void addCategory(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        List<String> cats = new ArrayList<>(prefs.categories());
        if (!cats.contains(upper)) cats.add(upper);
        prefs.setCategories(cats);
        activeTab = upper;
        rebuildTabs();
        applyFilter();
        // Stay in the sheet and let the user tap apps straight into the new
        // category — no separate window, same BottomSheet (issue #4).
        openMembershipSheet(upper);
    }

    private void deleteCategory(String name) {
        List<String> cats = new ArrayList<>(prefs.categories());
        cats.remove(name);
        prefs.setCategories(cats);
        if (activeTab.equals(name)) activeTab = "ALL";
        rebuildTabs();
        applyFilter();
    }

    /** Long-press a category tab: assign/remove apps in that category. */
    private void openMembershipSheet(String category) {
        if (palette != null) sheet.setPalette(palette);
        sheet.open("CATEGORIES — " + category);
        for (AppEntry app : allApps) {
            if (app.diagnostic) continue;
            boolean in = app.categories.contains(category);
            sheet.addRow(app.label, in, "IN", () -> toggleMembership(app, category));
        }
    }

    private void toggleMembership(AppEntry app, String category) {
        List<String> cats = new ArrayList<>(app.categories);
        if (cats.contains(category)) cats.remove(category); else cats.add(category);
        prefs.setMembership(app.component(), cats);
        refresh();
        openMembershipSheet(category);
    }

    private void applyFilter() {
        List<AppEntry> filtered = new ArrayList<>();
        for (AppEntry app : allApps) {
            if (app.diagnostic || activeTab.equals("ALL") || app.categories.contains(activeTab)) {
                filtered.add(app);
            }
        }
        adapter.setApps(filtered);
    }

    private void launch(AppEntry app) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(app.packageName, app.activityName));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            refresh(); // the app was uninstalled since the drawer was last loaded
        }
    }

    private void openAppInfo(AppEntry app) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", app.packageName, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            // No Settings app to resolve this — nothing we can do.
        }
    }

    private void openUninstall(AppEntry app) {
        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.fromParts("package", app.packageName, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            // No uninstaller to resolve this — nothing we can do.
        }
    }

    /**
     * Long-press quick-action box (issue #5): a small popup anchored just
     * below the pressed row, offering Launch / Uninstall / More Details —
     * replacing the old behaviour of jumping straight to system App Info.
     */
    private void showAppActions(AppEntry app, View anchor) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable boxBg = new android.graphics.drawable.GradientDrawable();
        int borderPx = Math.round(Math.max(1, metrics.cqw(0.8f)));
        boxBg.setStroke(borderPx, palette != null ? palette.p : 0xFF888888);
        boxBg.setColor(palette != null ? palette.bg : 0xFF202020);
        box.setBackground(boxBg);
        int padH = Math.round(metrics.cqw(4.5f));
        int padV = Math.round(metrics.cqw(2f));

        android.widget.PopupWindow popup = new android.widget.PopupWindow(box,
                Math.round(metrics.cqw(38f)), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(Math.round(metrics.cqw(1f)));

        box.addView(actionRow("LAUNCH", padH, padV, () -> { popup.dismiss(); launch(app); }));
        box.addView(actionRow("UNINSTALL / DISABLE", padH, padV, () -> { popup.dismiss(); openUninstall(app); }));
        box.addView(actionRow("MORE DETAILS", padH, padV, () -> { popup.dismiss(); openAppInfo(app); }));

        popup.showAsDropDown(anchor, 0, 0);
    }

    private View actionRow(String text, int padH, int padV, Runnable onClick) {
        TextView row = new TextView(getContext());
        row.setText(text);
        row.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.textPx(SIZE_TAB_CQW, SIZE_TAB_MIN));
        row.setPadding(padH, padV, padH, padV);
        row.setTextColor(palette != null ? palette.ink : 0xFFFFFFFF);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private static final int TYPE_SECTION = 0;
    private static final int TYPE_APP = 1;

    private final class DrawerAdapter extends BaseAdapter {

        private final List<Object> rows = new ArrayList<>();
        private final java.util.Map<Character, Integer> letterPositions = new TreeMap<>();

        void setApps(List<AppEntry> apps) {
            rows.clear();
            letterPositions.clear();
            char lastLetter = 0;
            for (AppEntry app : apps) {
                char letter = app.diagnostic ? 0 : app.firstLetter();
                if (!app.diagnostic && letter != lastLetter) {
                    letterPositions.put(letter, rows.size());
                    rows.add(letter);
                    lastLetter = letter;
                }
                rows.add(app);
            }
            notifyDataSetChanged();
            Set<Character> present = new LinkedHashSet<>(letterPositions.keySet());
            scrubber.setPresentLetters(present);
        }

        Integer positionForLetter(char letter) {
            Integer exact = letterPositions.get(letter);
            if (exact != null) return exact;
            Integer best = null;
            for (java.util.Map.Entry<Character, Integer> e : letterPositions.entrySet()) {
                if (e.getKey() <= letter) best = e.getValue(); else break;
            }
            return best;
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) {
            return rows.get(position) instanceof Character ? TYPE_SECTION : TYPE_APP;
        }
        @Override public boolean isEnabled(int position) { return getItemViewType(position) == TYPE_APP; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Object item = rows.get(position);
            if (item instanceof Character) {
                return sectionView((Character) item, convertView);
            }
            return appView((AppEntry) item, convertView);
        }

        private View sectionView(char letter, View convertView) {
            LinearLayout v = convertView instanceof LinearLayout && convertView.getTag() == null
                    ? (LinearLayout) convertView : null;
            if (v == null) {
                v = new LinearLayout(getContext());
                v.setOrientation(LinearLayout.VERTICAL);
                int padH = Math.round(metrics.cqw(4.5f));
                v.setPadding(padH, Math.round(metrics.cqw(2f)), padH, Math.round(metrics.cqw(1f)));
            } else {
                v.removeAllViews();
            }
            TextView label = new TextView(getContext());
            label.setText(String.valueOf(letter));
            label.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.textPx(SIZE_TAB_CQW, SIZE_TAB_MIN));
            if (palette != null) label.setTextColor(palette.ink);
            v.addView(label);
            View rule = new View(getContext());
            if (palette != null) rule.setBackgroundColor(palette.s);
            v.addView(rule, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(metrics.cqw(0.5f)))));
            return v;
        }

        private View appView(AppEntry app, View convertView) {
            LinearLayout row;
            ImageView icon;
            LinearLayout textCol;
            TextView label, caption;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof View[]) {
                row = (LinearLayout) convertView;
                View[] tag = (View[]) convertView.getTag();
                icon = (ImageView) tag[0];
                label = (TextView) tag[1];
                caption = (TextView) tag[2];
            } else {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int padH = Math.round(metrics.cqw(4.5f));
                int padV = Math.round(metrics.cqw(1.8f));
                row.setPadding(padH, padV, padH, padV);

                icon = new ImageView(getContext());
                int iconSize = Math.round(metrics.cqw(SIZE_ICON_CQW));
                row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

                textCol = new LinearLayout(getContext());
                textCol.setOrientation(LinearLayout.VERTICAL);
                int gap = Math.round(metrics.cqw(2.6f));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tp.leftMargin = gap;

                label = new TextView(getContext());
                label.setTypeface(Typeface.MONOSPACE);
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.textPx(SIZE_ROW_CQW, SIZE_ROW_MIN));

                caption = new TextView(getContext());
                caption.setTypeface(Typeface.MONOSPACE);
                caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.textPx(SIZE_CAPTION_CQW, SIZE_CAPTION_MIN));

                textCol.addView(label);
                textCol.addView(caption);
                row.addView(textCol, tp);
                row.setTag(new View[] { icon, label, caption });
            }

            if (app.diagnostic) {
                icon.setImageBitmap(null);
                label.setText(app.label);
                caption.setText("");
            } else {
                icon.setImageBitmap(icons.iconFor(app, palette, Math.round(metrics.cqw(SIZE_ICON_CQW))));
                label.setText(app.label);
                caption.setText(app.categories.isEmpty()
                        ? "UNSORTED"
                        : String.join(" · ", app.categories));
            }
            if (palette != null) {
                label.setTextColor(palette.ink);
                caption.setTextColor(withAlpha(palette.ink, 115));
            }
            return row;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
