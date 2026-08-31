package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;

import com.retro.launcher.core.CapsText;
import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.theme.Tint;

/**
 * The launcher's text field.
 *
 * A bare {@code EditText} arrives wearing Material: an accent underline, a
 * thin accent caret, teardrop selection handles and system text colours —
 * every one of them the wrong colour on a palette that changes with the hour,
 * and none of them square. That is what the NEW CATEGORY field looked like,
 * and it was the one place in the launcher where the platform showed through.
 *
 * <p>So the underline goes (the background is replaced outright by the same
 * bordered {@link GradientDrawable} the drawer's action box and the bottom
 * sheet's panel already use), the caret becomes a block, and the handles and
 * the selection highlight take the palette.
 *
 * <p>The system keyboard stays. A drawn pixel keypad was considered and cut:
 * it is a large component with a real accessibility cost — no IME switching,
 * no autocorrect, no other languages, no clipboard — for a field the user
 * touches a handful of times.
 *
 * <p>Caret and handle theming are API 29+. Below that the platform's own thin
 * accent caret is left alone; a hairline on a three-generation-old device is
 * a fair floor, and the alternative is drawing a caret by hand in
 * {@code onDraw} against a text layout that moves under it.
 */
public final class PixelField extends EditText {

    /** Chunky enough to read as a block rather than a line. */
    private static final float CARET_CQW = 1.2f;
    private static final float HANDLE_CQW = 3.6f;
    private static final int HIGHLIGHT_ALPHA = 90;
    private static final int HINT_ALPHA = 110;

    private final Metrics metrics;
    private final GradientDrawable box;
    private final int borderPx;

    /**
     * Raises what is typed rather than merely drawing it raised:
     * {@code setAllCaps} is display-only, so a lower-case category name would
     * still reach {@code Prefs} and then fail to match the upper-case chip it
     * had become. Null-for-unchanged keeps the IME's composing span intact —
     * see {@link CapsText}.
     */
    private static final InputFilter CAPS =
            (source, start, end, dest, dstart, dend) -> CapsText.upperOrNull(source, start, end);

    public PixelField(Context context, Metrics metrics, float sizeCqw, float sizeMin) {
        super(context);
        this.metrics = metrics;

        setSingleLine(true);
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.textPx(sizeCqw, sizeMin));
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        borderPx = Math.round(Math.max(1, metrics.cqw(0.8f)));
        box = new GradientDrawable();
        box.setStroke(borderPx, 0);
        setBackground(box);

        int padH = Math.round(metrics.cqw(3f));
        int padV = Math.round(metrics.cqw(2.2f));
        setPadding(padH, padV, padH, padV);

        // ROLE_INK carries the text colour through the existing Tint.apply
        // walk; everything else here needs the whole palette, hence
        // setPalette below rather than a role tag.
        Tint.setRole(this, Tint.ROLE_INK);
    }

    /** Turns the field into an upper-case-only one. Off by default: search
     *  should match what the user actually typed. */
    public void setAllCapsInput(boolean on) {
        setFilters(on ? new InputFilter[] { CAPS } : new InputFilter[0]);
    }

    public void setPalette(Palette p) {
        box.setColor(p.bg);
        box.setStroke(borderPx, p.p);
        setTextColor(p.ink);
        setHintTextColor(withAlpha(p.ink, HINT_ALPHA));
        setHighlightColor(withAlpha(p.p, HIGHLIGHT_ALPHA));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTextCursorDrawable(caret(p));
            setTextSelectHandle(handle(p));
            setTextSelectHandleLeft(handle(p));
            setTextSelectHandleRight(handle(p));
        }
    }

    /**
     * A solid block. Its width is the drawable's intrinsic width — the editor
     * stretches it to the line's height itself — so the height passed to
     * {@code setSize} only has to be non-zero.
     */
    private GradientDrawable caret(Palette p) {
        GradientDrawable c = new GradientDrawable();
        c.setColor(p.p);
        c.setSize(Math.round(Math.max(2, metrics.cqw(CARET_CQW))), 1);
        return c;
    }

    /** A square, where the platform's is a teardrop. */
    private GradientDrawable handle(Palette p) {
        GradientDrawable h = new GradientDrawable();
        h.setColor(p.p);
        int size = Math.round(metrics.cqw(HANDLE_CQW));
        h.setSize(size, size);
        return h;
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
