package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.ZoneLabel;
import com.retro.launcher.core.ZoneList;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.theme.Tint;
import com.retro.launcher.util.Haptics;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Where the second time zone is chosen (2.2.1).
 *
 * <p><b>Why this exists rather than reading the clock app's world clock.</b>
 * There is no API for that. Google's and Samsung's DeskClock keep their saved
 * cities in their own private {@code SharedPreferences}; no content provider
 * exposes them, and nothing short of {@code QUERY_ALL_PACKAGES} — which this
 * app does not declare and, per HANDOFF §0 row 10, must not — would even make
 * the packages visible. So the launcher keeps its own shortlist. The upside is
 * that it costs no permission and no asset: {@code TimeZone.getAvailableIDs()}
 * is already on the device.
 *
 * <p>Built like {@link SearchOverlay} rather than on {@link BottomSheet}: the
 * sheet builds a real view per row, and there are around six hundred zones.
 * Here nothing is listed until something is typed, so the view count is
 * bounded by {@link #MAX_RESULTS} however large the table gets.
 *
 * <p>Matching is on the city and the region, not the raw id, because
 * {@code America/Argentina/Buenos_Aires} should be found by typing "buenos"
 * and not by typing "argentina/buenos". Offsets are shown against the current
 * instant, so a zone on summer time reads as the offset it is actually on
 * today rather than its standard one.
 */
public final class TimeZonePicker extends FrameLayout {

    /** Enough to find the city without scrolling past near-duplicates —
     *  "Europe" alone matches over fifty zones. */
    private static final int MAX_RESULTS = 12;

    /** Null until HomeActivity supplies one. */
    private Haptics haptics;

    public void setHaptics(Haptics haptics) { this.haptics = haptics; }

    private void tick() { if (haptics != null) haptics.click(); }

    private final Metrics metrics;
    private final Prefs prefs;

    private final LinearLayout column;
    private final PixelField field;
    private final LinearLayout body;
    private final int columnPad;

    private Palette palette;
    private Runnable onClose;
    private Runnable onZonesChanged;

    /**
     * The zone table, read once. {@code getAvailableIDs()} allocates a fresh
     * array of every id on each call, and this rebuilds on every keystroke.
     */
    private final List<String> catalogue = new ArrayList<>();

    public TimeZonePicker(Context context, Metrics metrics, Prefs prefs) {
        super(context);
        this.metrics = metrics;
        this.prefs = prefs;

        setVisibility(GONE);
        LauncherRoot.setNoSwipe(this);
        setOnClickListener(v -> { tick(); close(); });

        for (String id : TimeZone.getAvailableIDs()) catalogue.add(id);
        java.util.Collections.sort(catalogue);

        columnPad = Math.round(metrics.cqw(6f));

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(columnPad, columnPad, columnPad, columnPad);
        // Taps inside the column must not fall through to the dismissing
        // backdrop behind it.
        column.setOnClickListener(v -> { });

        field = new PixelField(context, metrics,
                DrawerPanel.SIZE_TITLE_CQW, DrawerPanel.SIZE_TITLE_MIN);
        field.setHint("FIND A CITY");
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { rebuild(); }
        });
        column.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroller = new ScrollView(context);
        scroller.setFillViewport(true);
        scroller.addView(body, new ScrollView.LayoutParams(
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

    /** The keyboard is in the inset set for the same reason as in
     *  {@link SearchOverlay}: the window is laid out fullscreen, so without it
     *  the list runs on underneath the keys. */
    @Override public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.graphics.Insets sys = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.ime());
            column.setPadding(columnPad + sys.left, columnPad + sys.top,
                    columnPad + sys.right, columnPad + sys.bottom);
        }
        return super.onApplyWindowInsets(insets);
    }

    public void setOnCloseListener(Runnable r) { this.onClose = r; }

    /** Told whenever the saved list or the shown index changes, so the clock
     *  can redraw without waiting for the next minute tick. */
    public void setOnZonesChanged(Runnable r) { this.onZonesChanged = r; }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    public void open() {
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
        field.setPalette(p);
        rebuild();
    }

    // ---- the list --------------------------------------------------------

    private void rebuild() {
        body.removeAllViews();
        if (palette == null) return;

        List<String> saved = prefs.secondZones();
        String query = field.getText().toString().trim();

        if (query.isEmpty()) {
            renderSaved(saved);
            return;
        }

        body.addView(heading("MATCHES"));
        body.addView(rule());
        List<String> matches = matching(query);
        if (matches.isEmpty()) {
            body.addView(caption("NO ZONE MATCHES"));
            return;
        }
        for (String id : matches) {
            boolean alreadySaved = saved.contains(id);
            body.addView(row(describe(id), alreadySaved ? "SAVED" : "", () -> choose(id)));
        }
    }

    private void renderSaved(List<String> saved) {
        body.addView(heading("SAVED ZONES"));
        body.addView(rule());
        if (saved.isEmpty()) {
            body.addView(caption("NONE YET — TYPE A CITY ABOVE. "
                    + "THE CLOCK SHOWS ONE AT A TIME; TAP IT TO CYCLE."));
            return;
        }

        int shown = ZoneList.clamp(prefs.secondZoneIndex(), saved.size());
        for (int i = 0; i < saved.size(); i++) {
            String id = saved.get(i);
            // The row's action is remove, so the trailing word has to say so
            // rather than repeat what the row already shows.
            body.addView(row(describe(id), i == shown ? "ON CLOCK · REMOVE" : "REMOVE",
                    () -> remove(id)));
        }
        body.addView(caption("TAP A ZONE TO REMOVE IT. "
                + "MAXIMUM " + ZoneList.MAX + "; ADDING A SEVENTH DROPS THE OLDEST."));
    }

    /**
     * Matches on the city and the region rather than the raw id, so
     * {@code America/Argentina/Buenos_Aires} is found by typing "buenos".
     * Cities that start with the query sort above ones that merely contain it,
     * which is what puts "LONDON" above "LONDONDERRY".
     */
    private List<String> matching(String query) {
        String q = query.toLowerCase(Locale.ROOT).replace('_', ' ');
        List<String> prefix = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String id : catalogue) {
            String city = ZoneLabel.of(id).toLowerCase(Locale.ROOT);
            String region = ZoneLabel.regionOf(id).toLowerCase(Locale.ROOT);
            if (city.startsWith(q) || region.startsWith(q)) prefix.add(id);
            else if (city.contains(q) || region.contains(q)) contains.add(id);
            if (prefix.size() >= MAX_RESULTS) break;
        }
        for (String id : contains) {
            if (prefix.size() >= MAX_RESULTS) break;
            prefix.add(id);
        }
        return prefix;
    }

    /** {@code KARACHI +5} — the offset is against the current instant, so a
     *  zone on summer time reads as what it is on today rather than as its
     *  standard offset. */
    private String describe(String id) {
        TimeZone zone = TimeZone.getTimeZone(id);
        long now = Calendar.getInstance().getTimeInMillis();
        return ZoneLabel.of(id) + "  " + ZoneLabel.offsetLabel(zone.getOffset(now));
    }

    // ---- actions ---------------------------------------------------------

    /**
     * Saves a zone and shows it.
     *
     * <p>{@code add} puts it at the front, so storing index 0 makes the zone
     * just chosen the one on the clock — which is the whole reason anyone
     * opened this.
     */
    private void choose(String id) {
        prefs.setSecondZones(ZoneList.add(prefs.secondZones(), id));
        prefs.setSecondZoneIndex(0);
        // Choosing a zone is also the moment to turn the line on: someone who
        // has just picked a city plainly wants to see it, and leaving them to
        // find a separate toggle afterwards reads as the feature not working.
        prefs.setSecondZoneEnabled(true);
        notifyChanged();
        close();
    }

    /**
     * Removes a zone, then repairs the shown index.
     *
     * <p>The index is stored rather than the id, so removing an entry can
     * leave it pointing past the end or at a different zone than before.
     * {@code clamp} handles the first; re-deriving from the id that was on
     * screen handles the second, which is what stops removing the first of
     * three zones from silently switching the clock to a different city.
     */
    private void remove(String id) {
        List<String> before = prefs.secondZones();
        String shownId = before.isEmpty()
                ? null : before.get(ZoneList.clamp(prefs.secondZoneIndex(), before.size()));

        List<String> after = ZoneList.remove(before, id);
        prefs.setSecondZones(after);
        prefs.setSecondZoneIndex(
                shownId == null || shownId.equals(id)
                        ? 0
                        : ZoneList.indexOf(after, shownId));
        notifyChanged();
        rebuild();
    }

    private void notifyChanged() {
        if (onZonesChanged != null) onZonesChanged.run();
    }

    // ---- retro furniture, as SearchOverlay ---------------------------------

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

    /** A row with an optional trailing word, in the accent — the drawer's
     *  "IN DOCK" pattern, so a state marker reads the same everywhere. */
    private LinearLayout row(CharSequence text, String trailing, Runnable onTap) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int padV = Math.round(metrics.cqw(3f));
        r.setPadding(0, padV, 0, padV);
        r.setOnClickListener(v -> { tick(); onTap.run(); });

        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTypeface(Typeface.MONOSPACE);
        label.setAllCaps(true);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        Tint.setRole(label, Tint.ROLE_INK);
        Tint.apply(label, palette);
        r.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!trailing.isEmpty()) {
            TextView mark = new TextView(getContext());
            mark.setText(trailing);
            mark.setTypeface(Typeface.MONOSPACE);
            mark.setAllCaps(true);
            mark.setTextColor(palette.a);
            mark.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
            r.addView(mark);
        }

        r.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
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
