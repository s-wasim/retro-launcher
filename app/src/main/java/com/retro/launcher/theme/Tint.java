package com.retro.launcher.theme;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.retro.launcher.core.Palette;

/**
 * Repaints a view tree against a {@link Palette}. Static XML styles can't
 * express this — the palette changes with the hour, so ten palette sets times
 * light/dark would otherwise have to be baked into resources. Instead every
 * view that needs colour carries one role tag, and {@link #apply} walks the
 * tree once per palette change, painting text colour, a bordered
 * {@link GradientDrawable}'s stroke, or a plain background fill depending on
 * what the view actually is.
 *
 * Palette changes at most once a minute, and only the currently visible panel
 * is walked — see DESIGN_NOTES §3 and the spec's §3.3 data flow.
 */
public final class Tint {

    private Tint() {}

    private static final int ROLE_TAG_KEY = 0x7E100002;

    public static final int ROLE_INK  = 1;
    public static final int ROLE_P    = 2;
    public static final int ROLE_A    = 3;
    public static final int ROLE_S    = 4;
    public static final int ROLE_H    = 5;
    public static final int ROLE_TILE = 6;
    public static final int ROLE_BG   = 7;
    public static final int ROLE_VEIL = 8;

    public static void setRole(View v, int role) {
        v.setTag(ROLE_TAG_KEY, role);
    }

    public static void apply(View root, Palette p) {
        Object tag = root.getTag(ROLE_TAG_KEY);
        if (tag instanceof Integer) paint(root, (Integer) tag, p);

        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                apply(g.getChildAt(i), p);
            }
        }
    }

    private static void paint(View v, int role, Palette p) {
        int color = colorFor(role, p);

        if (v instanceof TextView && (role == ROLE_INK || role == ROLE_P || role == ROLE_A
                || role == ROLE_S || role == ROLE_H || role == ROLE_TILE)) {
            ((TextView) v).setTextColor(color);
            return;
        }

        if (role == ROLE_VEIL) {
            paintBackground(v, p.veil());
            return;
        }

        if (role == ROLE_BG) {
            paintBackground(v, color);
            return;
        }

        // A border role on a non-text view: tint a GradientDrawable's stroke,
        // falling back to a background fill if the view has none.
        Drawable bg = v.getBackground();
        if (bg instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) bg;
            gd.setStroke((int) Math.max(1, gd.getGradientRadius()), color);
        } else {
            paintBackground(v, color);
        }
    }

    private static void paintBackground(View v, int color) {
        Drawable bg = v.getBackground();
        if (bg instanceof GradientDrawable) {
            ((GradientDrawable) bg).setColor(color);
        } else {
            v.setBackgroundColor(color);
        }
    }

    private static int colorFor(int role, Palette p) {
        switch (role) {
            case ROLE_INK:  return p.ink;
            case ROLE_P:    return p.p;
            case ROLE_A:    return p.a;
            case ROLE_S:    return p.s;
            case ROLE_H:    return p.h;
            case ROLE_TILE: return p.tile;
            case ROLE_BG:   return p.bg;
            case ROLE_VEIL: return p.veil();
            default:        return p.ink;
        }
    }
}
