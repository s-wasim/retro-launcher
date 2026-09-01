package com.retro.launcher.ui;

import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.PopupWindow;

import com.retro.launcher.core.PopupPlacement;

/**
 * Places every long-press popup in the app at the point that was actually
 * pressed, kept on-screen. See {@link PopupPlacement} for the rules; this
 * class only supplies the measurements they need — the content's measured
 * height, the display size, and the system-bar insets.
 */
public final class AnchoredPopup {

    private AnchoredPopup() {}

    /**
     * Attaches a pass-through touch listener that records where each gesture
     * started, and hands back the array it writes into. The listener always
     * returns {@code false}, so the view's own click, long-click and
     * scrolling behaviour is untouched — this only watches.
     *
     * <p>Needed because {@code OnItemLongClickListener} and
     * {@code OnLongClickListener} carry no coordinates: by the time they fire
     * the {@code MotionEvent} is gone.
     *
     * @return a 2-element {@code {rawX, rawY}} array, updated on every
     *         {@code ACTION_DOWN}, initialised to {@code {-1, -1}}
     */
    public static float[] trackTouchPoint(View view) {
        final float[] point = {-1f, -1f};
        view.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                point[0] = e.getRawX();
                point[1] = e.getRawY();
            }
            return false;
        });
        return point;
    }

    /**
     * Shows {@code popup} with its top-left corner at the placement
     * {@link PopupPlacement} computes for this touch point.
     *
     * <p>A touch point of {@code (-1, -1)} — no {@code ACTION_DOWN} was seen,
     * which happens for a keyboard- or accessibility-driven long press —
     * falls back to the anchor's own top-left corner, which is the old
     * behaviour and still on-screen.
     */
    public static void showAt(PopupWindow popup, View anchor, View content,
                              int widthPx, float touchScreenX, float touchScreenY) {
        content.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = content.getMeasuredHeight();

        float x = touchScreenX, y = touchScreenY;
        if (x < 0f || y < 0f) {
            int[] loc = new int[2];
            anchor.getLocationOnScreen(loc);
            x = loc[0];
            y = loc[1];
        }

        Rect screen = displayBounds(anchor);
        int[] insets = systemInsets(anchor);

        int[] at = PopupPlacement.place(x, y, widthPx, height,
                screen.width(), screen.height(),
                insets[0], insets[1], insets[2], insets[3]);

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0], at[1]);
    }

    private static Rect displayBounds(View view) {
        WindowManager wm = (WindowManager) view.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            return new Rect(wm.getCurrentWindowMetrics().getBounds());
        }
        // 26–29: the root view fills the display, since HomeActivity is
        // edge-to-edge and fullscreen.
        View root = view.getRootView();
        return new Rect(0, 0, root.getWidth(), root.getHeight());
    }

    /** {@code {left, top, right, bottom}} of the system bars, in pixels. */
    private static int[] systemInsets(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets wi = view.getRootWindowInsets();
            if (wi != null) {
                android.graphics.Insets i =
                        wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                return new int[]{i.left, i.top, i.right, i.bottom};
            }
        } else {
            WindowInsets wi = view.getRootWindowInsets();
            if (wi != null) {
                return new int[]{wi.getSystemWindowInsetLeft(), wi.getSystemWindowInsetTop(),
                        wi.getSystemWindowInsetRight(), wi.getSystemWindowInsetBottom()};
            }
        }
        return new int[]{0, 0, 0, 0};
    }

    /** Convenience for callers that build their own content view. */
    public static PopupWindow window(View content, int widthPx, float elevationPx) {
        PopupWindow popup = new PopupWindow(content, widthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(elevationPx);
        return popup;
    }
}
