package com.retro.launcher.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.retro.launcher.core.CoffeeGlyph;
import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.PixelGlyphs;
import com.retro.launcher.core.PixelTile;
import com.retro.launcher.theme.Tint;
import com.retro.launcher.util.Launch;

/**
 * A pixel mug and the words BUY ME A COFFEE, sitting beside the day's total in
 * {@link ScreenTimePanel}.
 *
 * The mug is drawn from {@link CoffeeGlyph} in palette roles, the same way app
 * icons are, so it recolours with the hour instead of being a fixed-colour
 * asset. Tapping it opens {@link #DONATION_URL} — which is empty, so today the
 * tap does nothing at all.
 */
public final class CoffeeButton extends LinearLayout {

    /**
     * ---- PLUG THE DONATION LINK IN HERE ----
     *
     * Put the full URL of the donation page between the quotes, e.g.
     * "https://buymeacoffee.com/yourname" or "https://ko-fi.com/yourname",
     * and the button starts working — nothing else needs changing. While it
     * is empty the button is inert by design: {@link #onClick} returns early
     * rather than firing an intent no browser can answer.
     */
    private static final String DONATION_URL = "";

    private final Mug mug;
    private final TextView label;
    private final GradientDrawable border = new GradientDrawable();
    private final int strokeWidth;

    public CoffeeButton(Context context, Metrics metrics) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        int pad = Math.round(metrics.cqw(2f));
        setPadding(pad, pad, pad, pad);

        // Bordered like the daily limit card, so it reads as something to
        // press even while it is unwired.
        strokeWidth = Math.max(1, Math.round(metrics.cqw(0.5f)));
        border.setCornerRadius(metrics.cqw(1.5f));
        setBackground(border);

        int mugSize = Math.round(metrics.cqw(8f));
        mug = new Mug(context);
        addView(mug, new LayoutParams(mugSize, mugSize));

        label = new TextView(context);
        label.setText("BUY ME A COFFEE");
        label.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        label.setAllCaps(true);
        label.setSingleLine(true);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        Tint.setRole(label, Tint.ROLE_A);
        LayoutParams labelLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = Math.round(metrics.cqw(2f));
        addView(label, labelLp);

        setOnClickListener(v -> onClick());
    }

    private void onClick() {
        if (DONATION_URL.isEmpty()) return;   // no link configured yet
        Launch.first(getContext(), new Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL)));
    }

    public void setPalette(Palette p) {
        mug.setPalette(p);
        label.setTextColor(p.a);
        border.setStroke(strokeWidth, p.a);
    }

    /** The mug itself: {@link CoffeeGlyph}'s runs scaled to whatever box the
     *  row gives us, unantialiased and grid-snapped like every other tile. */
    private static final class Mug extends View {

        private final Paint paint = new Paint();
        private final int[][] runs = CoffeeGlyph.runs();
        private Palette palette;

        Mug(Context c) {
            super(c);
            paint.setAntiAlias(false);
        }

        void setPalette(Palette p) {
            this.palette = p;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (palette == null) return;
            float scale = Math.min(getWidth(), getHeight()) / (float) PixelTile.SIZE;
            for (int[] run : runs) {
                int row = run[0], start = run[1], end = run[2];
                paint.setColor(colorFor((char) run[3]));
                canvas.drawRect(Math.round(start * scale), Math.round(row * scale),
                        Math.round((end + 1) * scale), Math.round((row + 1) * scale), paint);
            }
        }

        private int colorFor(char role) {
            switch (role) {
                case PixelGlyphs.ROLE_PRIMARY:   return palette.p;
                case PixelGlyphs.ROLE_ACCENT:    return palette.a;
                case PixelGlyphs.ROLE_SHADE:     return palette.s;
                case PixelGlyphs.ROLE_HIGHLIGHT: return palette.h;
                case PixelGlyphs.ROLE_TILE:
                default:                         return palette.tile;
            }
        }
    }
}
