package com.retro.launcher.ui;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.retro.launcher.core.AppSearch;
import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.data.AppEntry;
import com.retro.launcher.data.AppRepository;
import com.retro.launcher.theme.Tint;
import com.retro.launcher.util.Launch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The double-tap search overlay (DESIGN_NOTES §5) — no visible affordance on
 * the home screen, no search bar taking up wallpaper.
 *
 * Results come back under two headings, apps first and the web row last, so
 * the common case is always the top item and the escape hatch is always in the
 * same place. Nothing is shown until something is typed.
 */
public final class SearchOverlay extends FrameLayout {

    /** Enough to cover the app you meant without turning into a second
     *  drawer — that is what the drawer is for. */
    private static final int MAX_APP_RESULTS = 8;

    private final Metrics metrics;
    private final AppRepository apps;

    private final LinearLayout column;
    private final EditText field;
    private final LinearLayout results;

    private final int columnPad;
    private Palette palette;
    private Runnable onClose;

    /** Snapshotted when the overlay opens. AppRepository.load() re-queries
     *  PackageManager and calls loadLabel() for every installed app — far too
     *  much to repeat on each keystroke. */
    private List<AppEntry> catalogue = Collections.emptyList();

    public SearchOverlay(Context context, Metrics metrics, AppRepository apps) {
        super(context);
        this.metrics = metrics;
        this.apps = apps;

        setVisibility(GONE);
        LauncherRoot.setNoSwipe(this);
        // Clickable so taps never reach the panel underneath, and a tap on the
        // empty backdrop dismisses — the same way the double tap opened it.
        setOnClickListener(v -> close());

        columnPad = Math.round(metrics.cqw(6f));

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(columnPad, columnPad, columnPad, columnPad);

        field = new EditText(context);
        field.setSingleLine(true);
        field.setHint("SEARCH");
        field.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        field.setAllCaps(false);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setBackground(null);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_TITLE_CQW, DrawerPanel.SIZE_TITLE_MIN));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { rebuild(); }
        });
        // Enter takes the top result — the app if there is one, the web
        // otherwise, matching the order on screen.
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                takeFirstResult();
                return true;
            }
            return false;
        });
        column.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroller = new ScrollView(context);
        scroller.setFillViewport(true);
        scroller.addView(results, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = Math.round(metrics.cqw(4f));
        column.addView(scroller, scrollLp);

        addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    @Override public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.graphics.Insets sys =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            column.setPadding(columnPad + sys.left, columnPad + sys.top,
                    columnPad + sys.right, columnPad + sys.bottom);
        }
        return super.onApplyWindowInsets(insets);
    }

    public void setOnCloseListener(Runnable r) { this.onClose = r; }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    public void open() {
        catalogue = apps.load();
        field.setText("");
        rebuild();
        setVisibility(VISIBLE);
        field.requestFocus();
        InputMethodManager imm =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    }

    public void close() {
        InputMethodManager imm =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.setText("");
        setVisibility(GONE);
        if (onClose != null) onClose.run();
    }

    public void setPalette(Palette p) {
        this.palette = p;
        setBackgroundColor(p.veil());
        field.setTextColor(p.ink);
        field.setHintTextColor(p.a);
        rebuild();
    }

    // ---- results ---------------------------------------------------------

    private void rebuild() {
        results.removeAllViews();
        if (palette == null) return;

        String query = field.getText().toString();
        if (query.trim().isEmpty()) return;

        List<AppEntry> matches = matchingApps(query);

        results.addView(heading("APPS"));
        results.addView(rule());
        if (matches.isEmpty()) {
            results.addView(caption("NO APPS MATCH"));
        } else {
            for (AppEntry app : matches) {
                results.addView(row(app.label, () -> { launch(app); close(); }));
            }
        }

        results.addView(heading("WEB"));
        results.addView(rule());
        results.addView(row("SEARCH FOR \"" + query.trim() + "\"",
                () -> { web(query.trim()); close(); }));
    }

    /** Highest-scoring first, capped. */
    private List<AppEntry> matchingApps(String query) {
        List<Scored> scored = new ArrayList<>();
        for (AppEntry app : catalogue) {
            if (app.diagnostic) continue;
            int s = AppSearch.score(app.label, query);
            if (s >= 0) scored.add(new Scored(app, s));
        }
        Collections.sort(scored, (a, b) -> b.score - a.score);

        List<AppEntry> out = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < MAX_APP_RESULTS; i++) {
            out.add(scored.get(i).app);
        }
        return out;
    }

    private void takeFirstResult() {
        String query = field.getText().toString().trim();
        if (query.isEmpty()) return;
        List<AppEntry> matches = matchingApps(query);
        if (matches.isEmpty()) web(query);
        else launch(matches.get(0));
        close();
    }

    private static final class Scored {
        final AppEntry app;
        final int score;
        Scored(AppEntry app, int score) { this.app = app; this.score = score; }
    }

    // ---- actions ---------------------------------------------------------

    private void launch(AppEntry app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(app.packageName, app.activityName));
        Launch.first(getContext(), intent);
    }

    private void web(String query) {
        Intent search = new Intent(Intent.ACTION_WEB_SEARCH)
                .putExtra(SearchManager.QUERY, query);
        // No search app declares WEB_SEARCH on some builds; a browser will
        // always take a plain https URL.
        Intent browse = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(query)));
        Launch.first(getContext(), search, browse);
    }

    // ---- retro furniture --------------------------------------------------

    private TextView heading(String text) {
        TextView h = new TextView(getContext());
        h.setText(text);
        h.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        h.setAllCaps(true);
        h.setLetterSpacing(0.16f);
        h.setTextColor(palette.a);
        h.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));

        int padV = Math.round(metrics.cqw(3f));
        h.setPadding(0, padV, 0, Math.round(metrics.cqw(1.5f)));
        return h;
    }

    /** The hairline under each heading — the drawer's separator, same role
     *  colour, so the two panels read as one system. */
    private android.view.View rule() {
        android.view.View v = new android.view.View(getContext());
        v.setBackgroundColor(palette.s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, Math.round(metrics.cqw(0.5f))));
        lp.bottomMargin = Math.round(metrics.cqw(1.5f));
        v.setLayoutParams(lp);
        return v;
    }

    private TextView row(String text, Runnable onTap) {
        TextView r = new TextView(getContext());
        r.setText(text);
        r.setTypeface(Typeface.MONOSPACE);
        r.setAllCaps(true);
        r.setSingleLine(true);
        r.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        int padV = Math.round(metrics.cqw(3f));
        r.setPadding(0, padV, 0, padV);
        r.setOnClickListener(v -> onTap.run());
        // Full width, so the tap target is the row and not just the glyphs.
        r.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        Tint.setRole(r, Tint.ROLE_INK);
        Tint.apply(r, palette);
        return r;
    }

    private TextView caption(String text) {
        TextView c = new TextView(getContext());
        c.setText(text);
        c.setTypeface(Typeface.MONOSPACE);
        c.setAllCaps(true);
        c.setTextColor(palette.a);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        int padV = Math.round(metrics.cqw(3f));
        c.setPadding(0, padV, 0, padV);
        return c;
    }
}
