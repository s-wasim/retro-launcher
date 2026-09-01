package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.util.Haptics;

import java.util.HashSet;
import java.util.Set;

/**
 * Fixed 7cqw right rail: all 26 letters, present ones at full opacity, absent
 * ones dimmed. Press-and-drag jumps the drawer list to that letter's header.
 * See DESIGN_NOTES §7b.
 *
 * Measures shorter than the row it sits in ({@link #LENGTH_FRACTION}) and is
 * centered vertically ({@code DrawerPanel}'s scrubber LayoutParams) rather
 * than stretched edge-to-edge: a right-edge rail spanning the full height
 * runs its first and last letters into the corners, which a circular
 * display clips (A/Z were getting cropped) — see DESIGN_NOTES §9 delta 22.
 */
public final class AlphaScrubber extends View {

    /** Null until HomeActivity supplies one. Every call site null-checks
     *  rather than requiring construction order to guarantee it. */
    private Haptics haptics;

    public void setHaptics(Haptics haptics) { this.haptics = haptics; }

    private void tick() { if (haptics != null) haptics.click(); }

    /** Fraction of the row's available height the rail actually occupies —
     *  leaves clearance top and bottom so the end letters clear a circular
     *  display's curvature, and stays centered via the parent LayoutParams. */
    private static final float LENGTH_FRACTION = 0.86f;

    public interface OnLetterListener { void onLetter(char letter); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Metrics metrics;
    private Palette palette;
    private Set<Character> present = new HashSet<>();
    private OnLetterListener listener;

    public AlphaScrubber(Context context, Metrics metrics) {
        super(context);
        this.metrics = metrics;
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextAlign(Paint.Align.CENTER);
        LauncherRoot.setNoSwipe(this);
    }

    public void setPalette(Palette p) { this.palette = p; invalidate(); }
    public void setPresentLetters(Set<Character> letters) { this.present = letters; invalidate(); }
    public void setOnLetterListener(OnLetterListener l) { this.listener = l; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int width = Math.round(metrics.cqw(8.5f));
        int height = Math.round(MeasureSpec.getSize(hSpec) * LENGTH_FRACTION);
        setMeasuredDimension(width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (palette == null) return;
        canvas.drawColor(palette.veil());

        int h = getHeight(), w = getWidth();
        float rowHeight = h / 26f;
        paint.setTextSize(Math.min(w * 0.7f, rowHeight * 0.8f));
        for (int i = 0; i < 26; i++) {
            char letter = (char) ('A' + i);
            paint.setColor(present.contains(letter) ? withAlpha(palette.ink, 242) : withAlpha(palette.ink, 64));
            float y = rowHeight * i + rowHeight / 2f - (paint.descent() + paint.ascent()) / 2f;
            canvas.drawText(String.valueOf(letter), w / 2f, y, paint);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /** The last letter reported to the listener. ACTION_MOVE fires many times
     *  within one letter's band; without this the list would be told to jump
     *  to the same position dozens of times per drag, and the haptic would be
     *  a continuous rattle rather than one tick per letter. */
    private char lastLetter = 0;

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // A new gesture may start on the same letter the last one
                // ended on, and that still has to scroll there — so the gate
                // resets per gesture, not per view.
                lastLetter = 0;
                fireLetterAt(e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                fireLetterAt(e.getY());
                return true;
        }
        return true;
    }

    private void fireLetterAt(float y) {
        if (listener == null || getHeight() == 0) return;
        int index = (int) (y / (getHeight() / 26f));
        index = Math.max(0, Math.min(25, index));
        char letter = (char) ('A' + index);
        if (letter == lastLetter) return;
        lastLetter = letter;
        tick();
        listener.onLetter(letter);
    }
}
