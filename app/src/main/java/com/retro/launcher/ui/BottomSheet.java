package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.theme.Tint;

/**
 * The dock-picker / category-picker overlay (DESIGN_NOTES §7e). One reusable
 * shell — scrim, header with title + DONE, a scrollable row list, and an
 * optional trailing add-field — populated differently by each caller rather
 * than split into two subclasses.
 */
public final class BottomSheet extends FrameLayout {

    private final Metrics metrics;
    private final TextView titleView;
    private final LinearLayout header;
    private final LinearLayout rows;
    private final LinearLayout panel;
    private final LinearLayout addRow;
    private final PixelField addField;
    private final GradientDrawable addButtonBg;
    private final int borderPx;
    private final int headerPadTop;
    private Palette palette;

    public BottomSheet(Context context, Metrics metrics) {
        super(context);
        this.metrics = metrics;
        LauncherRoot.setNoSwipe(this);
        setVisibility(GONE);

        View scrim = new View(context);
        scrim.setBackgroundColor(0x8C000000);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panel.setBackground(panelBg);
        Tint.setRole(panel, Tint.ROLE_BG);
        borderPx = Math.round(Math.max(1, metrics.cqw(0.8f)));
        panelBg.setStroke(borderPx, 0);

        header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(metrics.cqw(4.5f));
        header.setPadding(pad, pad, pad, pad);
        headerPadTop = pad;

        titleView = new TextView(context);
        titleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        titleView.setAllCaps(true);
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_TITLE_CQW, DrawerPanel.SIZE_TITLE_MIN));
        Tint.setRole(titleView, Tint.ROLE_INK);
        header.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = new TextView(context);
        done.setText("DONE");
        done.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        done.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        Tint.setRole(done, Tint.ROLE_P);
        done.setOnClickListener(v -> close());
        header.addView(done);

        ScrollView scroll = new ScrollView(context);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LauncherRoot.setNoSwipe(scroll);

        addRow = new LinearLayout(context);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(pad, pad, pad, pad);

        addField = new PixelField(context, metrics,
                DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN);
        addField.setHint("NEW CATEGORY");
        // A category name becomes a chip in the drawer's tab strip and a key
        // in Prefs, both upper case; raise it as it is typed so the two
        // cannot drift apart.
        addField.setAllCapsInput(true);

        // The ADD button takes the same border so the pair reads as one
        // control, and a real touch target — it was text-sized, which is a
        // hard tap beside a field.
        TextView add = new TextView(context);
        add.setText("ADD");
        add.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        add.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        add.setGravity(Gravity.CENTER);
        int addPadH = Math.round(metrics.cqw(4f));
        add.setPadding(addPadH, 0, addPadH, 0);
        int target = Math.round(metrics.cqw(11f));
        add.setMinHeight(target);
        add.setMinWidth(target);
        addButtonBg = new GradientDrawable();
        addButtonBg.setStroke(borderPx, 0);
        add.setBackground(addButtonBg);
        Tint.setRole(add, Tint.ROLE_P);

        addField.setMinimumHeight(target);
        LinearLayout.LayoutParams fieldLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fieldLp.rightMargin = Math.round(metrics.cqw(2.5f));
        addRow.addView(addField, fieldLp);
        addRow.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, target));
        addRow.setVisibility(GONE);
        this.addButton = add;

        panel.addView(header);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        panel.addView(addRow);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.BOTTOM;
        addView(panel, panelParams);
    }

    private final TextView addButton;

    @Override public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.graphics.Insets sys = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            header.setPadding(header.getPaddingLeft(), headerPadTop + sys.top,
                    header.getPaddingRight(), header.getPaddingBottom());
            // The sheet is anchored to the bottom edge, so without this the
            // ADD row and the last list row sit under the gesture pill.
            panel.setPadding(sys.left, panel.getPaddingTop(), sys.right, sys.bottom);
        }
        return super.onApplyWindowInsets(insets);
    }

    public void setPalette(Palette p) {
        this.palette = p;
        Tint.apply(this, p);
        // Tint stops at the text colour for a TextView; the field's border,
        // caret and handles and the ADD button's border need the rest of it.
        addField.setPalette(p);
        addButtonBg.setColor(p.bg);
        addButtonBg.setStroke(borderPx, p.p);
    }

    /** Clears prior rows and opens the sheet with a fresh title. */
    public void open(String title) {
        titleView.setText(title);
        rows.removeAllViews();
        addRow.setVisibility(GONE);
        setVisibility(VISIBLE);
    }

    public void addRow(String label, boolean marked, String markedText, Runnable onClick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(metrics.cqw(4.5f));
        int vpad = Math.round(metrics.cqw(1.8f));
        row.setPadding(pad, vpad, pad, vpad);

        TextView labelView = new TextView(getContext());
        labelView.setText(label);
        labelView.setTypeface(Typeface.MONOSPACE);
        labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        if (palette != null) labelView.setTextColor(palette.ink);
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (marked) {
            TextView marker = new TextView(getContext());
            marker.setText(markedText);
            marker.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            marker.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
            if (palette != null) marker.setTextColor(palette.p);
            row.addView(marker);
        }

        row.setOnClickListener(v -> onClick.run());
        rows.addView(row);
    }

    /** Shows the trailing "new item" field, e.g. NEW CATEGORY + ADD. */
    public void showAddField(String hint, java.util.function.Consumer<String> onAdd) {
        addField.setHint(hint);
        addField.setText("");
        addButton.setOnClickListener(v -> {
            String text = addField.getText().toString().trim();
            if (!text.isEmpty()) onAdd.accept(text);
            addField.setText("");
        });
        addRow.setVisibility(VISIBLE);
    }

    public void close() {
        setVisibility(GONE);
        rows.removeAllViews();
    }

    public boolean isOpen() { return getVisibility() == VISIBLE; }
}
