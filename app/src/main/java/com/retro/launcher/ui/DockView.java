package com.retro.launcher.ui;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.data.AppEntry;
import com.retro.launcher.icons.IconSource;
import com.retro.launcher.theme.Tint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Up to five pinned apps plus a trailing "add" slot. See DESIGN_NOTES §7a and
 * §9 delta 6/7. Slots draw through the same {@link IconSource} the drawer
 * uses, so a dock tile and its drawer row are the same icon.
 */
public final class DockView extends LinearLayout {

    private static final int MAX_SLOTS = 5;

    /** Long-pressing a filled slot requests its replacement; tapping the
     *  trailing dashed slot requests an addition. Both hand off to whoever
     *  owns the dock-picker {@code BottomSheet} — see HomeActivity. */
    public interface SlotActionListener {
        void onReplace(int slotIndex);
        void onAdd();
    }

    private final GradientDrawable background = new GradientDrawable();
    private final Metrics metrics;
    private final IconSource icons;
    private Palette palette;
    private List<String> entries = new ArrayList<>();
    private SlotActionListener slotActionListener;

    public DockView(Context context, Metrics metrics, IconSource icons) {
        super(context);
        this.metrics = metrics;
        this.icons = icons;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        background.setShape(GradientDrawable.RECTANGLE);
        setBackground(background);
        int pad = Math.round(metrics.cqw(3.2f));
        setPadding(pad, pad, pad, pad);
        LauncherRoot.setNoSwipe(this);
    }

    public void setEntries(List<String> components) {
        this.entries = components;
        rebuild();
    }

    public List<String> entries() { return entries; }

    public void setOnSlotActionListener(SlotActionListener l) { this.slotActionListener = l; }

    public void setPalette(Palette p) {
        this.palette = p;
        background.setColor(p.veil());
        background.setStroke(Math.round(Math.max(1, metrics.cqw(0.7f))), p.p);
        rebuild();
    }

    private void rebuild() {
        removeAllViews();
        if (palette == null) return;

        int gap = Math.round(metrics.cqw(2.6f));
        int slotSize = Math.round(metrics.cqw(13f));

        for (int i = 0; i < entries.size() && i < MAX_SLOTS; i++) {
            addView(buildSlot(entries.get(i), i), slotParams(slotSize, i == 0 ? 0 : gap));
        }
        if (entries.size() < MAX_SLOTS) {
            addView(buildAddSlot(), slotParams(slotSize, entries.isEmpty() ? 0 : gap));
        }
    }

    private LayoutParams slotParams(int size, int marginStart) {
        LayoutParams lp = new LayoutParams(size, LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(marginStart);
        return lp;
    }

    private View buildSlot(String component, int index) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        int tileSize = Math.round(metrics.cqw(13f));
        ImageView tile = new ImageView(getContext());
        tile.setImageBitmap(icons.iconFor(entryFor(component), palette, tileSize));
        tile.setLayoutParams(new LinearLayout.LayoutParams(tileSize, tileSize));

        TextView caption = new TextView(getContext());
        caption.setTypeface(Typeface.MONOSPACE);
        caption.setTextColor(palette.ink);
        caption.setAllCaps(true);
        caption.setGravity(Gravity.CENTER);
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.textPx(2.5f, 9f));
        caption.setText(labelFor(component));
        caption.setMaxLines(1);

        col.addView(tile);
        col.addView(caption);

        col.setOnClickListener(v -> launch(component));
        col.setOnLongClickListener(v -> {
            if (slotActionListener != null) slotActionListener.onReplace(index);
            return true;
        });

        return col;
    }

    private View buildAddSlot() {
        TextView plus = new TextView(getContext());
        plus.setGravity(Gravity.CENTER);
        plus.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        plus.setText("+");
        plus.setTextColor(palette.p);
        GradientDrawable dashed = new GradientDrawable();
        dashed.setShape(GradientDrawable.RECTANGLE);
        dashed.setStroke(Math.round(Math.max(1, metrics.cqw(0.7f))), palette.p, metrics.dp(3f), metrics.dp(3f));
        dashed.setCornerRadius(metrics.cqw(1.5f));
        plus.setBackground(dashed);
        plus.setLayoutParams(new LinearLayout.LayoutParams(
                Math.round(metrics.cqw(13f)), Math.round(metrics.cqw(13f))));
        plus.setOnClickListener(v -> {
            if (slotActionListener != null) slotActionListener.onAdd();
        });
        return plus;
    }

    /** The dock stores components, not drawer rows; the icon source wants a
     *  row. Only the package name and the letter matter to it. */
    private static AppEntry entryFor(String component) {
        int slash = component.indexOf('/');
        String pkg = slash >= 0 ? component.substring(0, slash) : component;
        String activity = slash >= 0 ? component.substring(slash + 1) : "";
        return new AppEntry(labelFor(component), pkg, activity, Collections.emptyList(), false);
    }

    /** Shared with SettingsPanel's dock editor so both list the same names. */
    public static String labelFor(String component) {
        int slash = component.indexOf('/');
        String pkg = slash >= 0 ? component.substring(0, slash) : component;
        int dot = pkg.lastIndexOf('.');
        String tail = dot >= 0 ? pkg.substring(dot + 1) : pkg;
        return tail.isEmpty() ? "?" : tail.toUpperCase(java.util.Locale.ROOT);
    }

    private void launch(String component) {
        int slash = component.indexOf('/');
        if (slash < 0) return;
        String pkg = component.substring(0, slash), activity = component.substring(slash + 1);
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(pkg, activity));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            // The app was uninstalled since the dock was last saved.
        }
    }
}
