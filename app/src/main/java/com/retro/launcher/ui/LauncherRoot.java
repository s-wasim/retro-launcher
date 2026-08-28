package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import com.retro.launcher.util.Insets;

/**
 * Holds the four panels at fixed offsets and moves them with the finger.
 *
 * Navigation model, ported from the prototype's onDown/onMove/onUp — see
 * DESIGN_NOTES §1:
 *   - no axis is chosen until the finger travels 12dp; the larger of |dx|/|dy|
 *     wins and is locked for the rest of the gesture
 *   - panels track 1:1 while dragging, then settle over 260ms
 *   - a gesture starting inside the system gesture inset is left to Android
 *   - a gesture starting over a no-swipe subtree is left to that subtree
 */
public final class LauncherRoot extends ViewGroup {

    public static final int VIEW_HOME     = 0;
    public static final int VIEW_SETTINGS = 1;
    public static final int VIEW_DRAWER   = 2;
    public static final int VIEW_TIME     = 3;

    private static final int NO_SWIPE_TAG = 0x7E100001;

    private static final float H_THRESHOLD = 0.22f;  // fraction of width
    private static final float V_THRESHOLD = 0.16f;  // fraction of height
    private static final long  SETTLE_MS   = 260L;

    private View home, settings, drawer, time;

    private int view = VIEW_HOME;
    private float downX, downY;
    private int axis;                 // 0 none, 1 horizontal, 2 vertical
    private boolean tracking;
    private final int slop;
    private final PathInterpolator settle = new PathInterpolator(.2f, .7f, .2f, 1f);
    private final GestureDetector doubleTap;
    private Runnable onDoubleTap;

    public LauncherRoot(Context c) {
        super(c);
        setChildrenDrawingOrderEnabled(false);
        this.slop = (int) (12 * c.getResources().getDisplayMetrics().density);
        this.doubleTap = new GestureDetector(c,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        if (view == VIEW_HOME && onDoubleTap != null) {
                            onDoubleTap.run();
                            return true;
                        }
                        return false;
                    }
                });
    }

    /** Marks a view and its descendants as owning their own horizontal or
     *  vertical drags — the prototype's [data-noswipe]. */
    public static void setNoSwipe(View v) { v.setTag(NO_SWIPE_TAG, Boolean.TRUE); }

    public void setDoubleTapListener(Runnable r) { this.onDoubleTap = r; }

    public void setPanels(View home, View settings, View drawer, View time) {
        this.home = home; this.settings = settings;
        this.drawer = drawer; this.time = time;
        removeAllViews();
        addView(home); addView(settings); addView(drawer); addView(time);
    }

    public int currentView() { return view; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        int cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(cw, ch);
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(0, 0, w, h);
        applyRest(w, h);
    }

    /** Snap every panel to its resting offset for the current view. */
    private void applyRest(int w, int h) {
        if (home == null) return;
        settings.setTranslationX(view == VIEW_SETTINGS ? 0 : -w);
        drawer.setTranslationX(view == VIEW_DRAWER ? 0 : w);
        time.setTranslationY(view == VIEW_TIME ? 0 : h);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                axis = 0; tracking = false;
                if (inGestureInset(downX) || overNoSwipe((int) downX, (int) downY)) {
                    axis = -1;   // this gesture is not ours
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (axis == -1) return false;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (axis == 0) {
                    if (Math.abs(dx) < slop && Math.abs(dy) < slop) return false;
                    axis = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                    tracking = true;
                }
                return tracking;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        doubleTap.onTouchEvent(e);
        if (axis == -1) return false;

        int w = getWidth(), h = getHeight();
        float dx = e.getX() - downX, dy = e.getY() - downY;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (axis == 0) {
                    if (Math.abs(dx) < slop && Math.abs(dy) < slop) return true;
                    axis = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                    tracking = true;
                }
                drag(dx, dy, w, h);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (tracking) release(dx, dy, w, h);
                axis = 0; tracking = false;
                return true;
        }
        return true;
    }

    private void drag(float dx, float dy, int w, int h) {
        if (axis == 1) {
            if (view == VIEW_HOME) {
                settings.setTranslationX(clamp(dx, 0, w) - w);
                drawer.setTranslationX(w + clamp(dx, -w, 0));
            } else if (view == VIEW_SETTINGS) {
                settings.setTranslationX(clamp(dx, -w, 0));
            } else if (view == VIEW_DRAWER) {
                drawer.setTranslationX(clamp(dx, 0, w));
            }
        } else if (axis == 2) {
            if (view == VIEW_HOME)      time.setTranslationY(h + clamp(dy, -h, 0));
            else if (view == VIEW_TIME) time.setTranslationY(clamp(dy, 0, h));
        }
    }

    private void release(float dx, float dy, int w, int h) {
        int next = view;
        if (axis == 1) {
            float th = w * H_THRESHOLD;
            if (view == VIEW_HOME) {
                if (dx > th)       next = VIEW_SETTINGS;
                else if (dx < -th) next = VIEW_DRAWER;
            } else if (view == VIEW_SETTINGS && dx < -th) next = VIEW_HOME;
            else if (view == VIEW_DRAWER && dx > th)      next = VIEW_HOME;
        } else if (axis == 2) {
            float th = h * V_THRESHOLD;
            if (view == VIEW_HOME && dy < -th)      next = VIEW_TIME;
            else if (view == VIEW_TIME && dy > th)  next = VIEW_HOME;
        }
        goTo(next);
    }

    public void goTo(int next) {
        int w = getWidth(), h = getHeight();
        this.view = next;
        // withLayer() caches each panel as a hardware bitmap for the
        // duration of the slide instead of re-rasterising the whole subtree
        // every frame — without it, panels with lots of text/content visibly
        // smear into place instead of snapping cleanly.
        settings.animate().translationX(next == VIEW_SETTINGS ? 0 : -w)
                .setDuration(SETTLE_MS).setInterpolator(settle).withLayer().start();
        drawer.animate().translationX(next == VIEW_DRAWER ? 0 : w)
                .setDuration(SETTLE_MS).setInterpolator(settle).withLayer().start();
        time.animate().translationY(next == VIEW_TIME ? 0 : h)
                .setDuration(SETTLE_MS).setInterpolator(settle).withLayer().start();
    }

    private boolean inGestureInset(float x) {
        int[] g = Insets.gestureLeftRight(this);
        return x < g[0] || x > getWidth() - g[1];
    }

    private boolean overNoSwipe(int x, int y) {
        return hitsNoSwipe(this, x, y);
    }

    private static boolean hitsNoSwipe(View v, int x, int y) {
        if (Boolean.TRUE.equals(v.getTag(NO_SWIPE_TAG))) return true;
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        Rect r = new Rect();
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != VISIBLE) continue;
            c.getHitRect(r);
            int tx = (int) c.getTranslationX(), ty = (int) c.getTranslationY();
            r.offset(tx - c.getLeft() + c.getLeft(), ty - c.getTop() + c.getTop());
            if (r.contains(x, y) && hitsNoSwipe(c, x - r.left, y - r.top)) return true;
        }
        return false;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
