package com.retro.launcher.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
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
 *
 * Animation discipline (each rule exists because breaking it is visible):
 *   1. Exactly one writer owns a panel's translation at a time. A running
 *      settle animator owns it; onLayout and drag() must yield to it or cancel
 *      it, never write underneath it.
 *   2. Only panels that actually move are animated at all.
 *   3. A panel is VISIBLE only while on-screen or moving. Off-screen panels
 *      are opaque and full-screen; leaving them VISIBLE is pure waste.
 *   4. No hardware layers. A translation-only animation over an unchanging
 *      subtree is a transform on an already-recorded display list — the
 *      RenderThread composites it for free. withLayer() cannot make that
 *      cheaper, and it costs a full-screen offscreen texture allocated on the
 *      first frame of every slide, which is what made panels blink.
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

    /** Non-null while that panel's settle animator owns its translation.
     *  Indexed by VIEW_*; the home slot stays null, home never moves. */
    private final ViewPropertyAnimator[] running = new ViewPropertyAnimator[4];

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

    /**
     * Snap every panel that no animator owns to its resting offset.
     *
     * onLayout fires whenever any descendant calls requestLayout — a minute
     * tick relabelling the clock, an inset dispatch, a palette rebuild. Those
     * happen freely mid-slide, so this must leave an animating panel alone;
     * writing the resting offset underneath a running animator teleports the
     * panel to its destination while the slide is still playing out.
     */
    private void applyRest(int w, int h) {
        if (home == null) return;
        if (running[VIEW_SETTINGS] == null) rest(settings, view == VIEW_SETTINGS, -w, 0);
        if (running[VIEW_DRAWER]   == null) rest(drawer,   view == VIEW_DRAWER,    w, 0);
        if (running[VIEW_TIME]     == null) rest(time,     view == VIEW_TIME,      0, h);
    }

    private static void rest(View v, boolean shown, float offX, float offY) {
        v.setTranslationX(shown ? 0 : offX);
        v.setTranslationY(shown ? 0 : offY);
        v.setVisibility(shown ? VISIBLE : INVISIBLE);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                axis = 0; tracking = false;
                if (inGestureInset(downX) || overNoSwipe(downX, downY)) {
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
                seize(VIEW_SETTINGS, settings);
                seize(VIEW_DRAWER, drawer);
                settings.setTranslationX(clamp(dx, 0, w) - w);
                drawer.setTranslationX(w + clamp(dx, -w, 0));
            } else if (view == VIEW_SETTINGS) {
                seize(VIEW_SETTINGS, settings);
                settings.setTranslationX(clamp(dx, -w, 0));
            } else if (view == VIEW_DRAWER) {
                seize(VIEW_DRAWER, drawer);
                drawer.setTranslationX(clamp(dx, 0, w));
            }
        } else if (axis == 2) {
            if (view == VIEW_HOME) {
                seize(VIEW_TIME, time);
                time.setTranslationY(h + clamp(dy, -h, 0));
            } else if (view == VIEW_TIME) {
                seize(VIEW_TIME, time);
                time.setTranslationY(clamp(dy, 0, h));
            }
        }
    }

    /**
     * Take a panel's translation away from whatever settle animator still owns
     * it, and make it visible so there is something to drag. Without this a
     * flick started during the previous flick's settle has two writers, and
     * the panel judders between the finger and the animator.
     */
    private void seize(int slot, View panel) {
        ViewPropertyAnimator a = running[slot];
        if (a != null) {
            running[slot] = null;
            a.cancel();
        }
        if (panel.getVisibility() != VISIBLE) panel.setVisibility(VISIBLE);
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
        this.view = next;
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;   // pre-layout; onLayout will snap us

        slide(VIEW_SETTINGS, settings, next == VIEW_SETTINGS ? 0 : -w, 0,
                next == VIEW_SETTINGS);
        slide(VIEW_DRAWER, drawer, next == VIEW_DRAWER ? 0 : w, 0,
                next == VIEW_DRAWER);
        slide(VIEW_TIME, time, 0, next == VIEW_TIME ? 0 : h,
                next == VIEW_TIME);
    }

    /**
     * Move one panel to its target, or settle its visibility and return if it
     * is already there. Skipping the no-op case matters: goTo touches all
     * three panels but at most two of them ever move, and starting an animator
     * on a full-screen panel that is not going anywhere costs a frame's work
     * and an invalidate for nothing.
     */
    private void slide(int slot, View panel, float tx, float ty, boolean shown) {
        ViewPropertyAnimator prev = running[slot];
        if (prev != null) {
            running[slot] = null;
            prev.cancel();
        }

        if (panel.getTranslationX() == tx && panel.getTranslationY() == ty) {
            panel.setVisibility(shown ? VISIBLE : INVISIBLE);
            return;
        }

        // Visible for the whole slide; hidden again only once it has left.
        panel.setVisibility(VISIBLE);

        // Bookkeeping goes in onAnimationEnd rather than withEndAction because
        // withEndAction is skipped when an animation is cancelled — including
        // the cancel the framework issues if the view is detached mid-slide.
        // Missing that would strand running[slot], and applyRest would then
        // never touch this panel again: it would sit off-screen for good.
        // onAnimationEnd fires on every termination path.
        final boolean[] cancelled = {false};
        ViewPropertyAnimator a = panel.animate()
                .translationX(tx).translationY(ty)
                .setDuration(SETTLE_MS)
                .setInterpolator(settle)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationCancel(Animator anim) {
                        cancelled[0] = true;
                    }
                    @Override public void onAnimationEnd(Animator anim) {
                        running[slot] = null;
                        // An interrupted slide must not hide a panel the
                        // finger is currently dragging back in.
                        if (!cancelled[0] && !shown) panel.setVisibility(INVISIBLE);
                    }
                });
        running[slot] = a;
        a.start();
    }

    private boolean inGestureInset(float x) {
        int[] g = Insets.gestureLeftRight(this);
        return x < g[0] || x > getWidth() - g[1];
    }

    private boolean overNoSwipe(float x, float y) {
        return hitsNoSwipe(this, x, y);
    }

    /**
     * True if (x, y) — in {@code v}'s own coordinate space — lands on a subtree
     * tagged no-swipe.
     *
     * Coordinates are converted the same way {@code dispatchTouchEvent} does
     * it: add the parent's scroll, then subtract the child's layout position
     * and its translation. The scroll term is what matters here — the drawer's
     * app list is a scrolling container, so testing without it checked the
     * wrong rows the moment the list moved.
     */
    private static boolean hitsNoSwipe(View v, float x, float y) {
        if (Boolean.TRUE.equals(v.getTag(NO_SWIPE_TAG))) return true;
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != VISIBLE) continue;
            float cx = x + g.getScrollX() - c.getLeft() - c.getTranslationX();
            float cy = y + g.getScrollY() - c.getTop() - c.getTranslationY();
            if (cx < 0 || cx >= c.getWidth() || cy < 0 || cy >= c.getHeight()) continue;
            if (hitsNoSwipe(c, cx, cy)) return true;
        }
        return false;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
