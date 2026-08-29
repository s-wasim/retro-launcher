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
 *   - a gesture over a no-swipe subtree is left to that subtree, on the axes
 *     that subtree claims
 *
 * A panel closes with the reverse of the swipe that opened it, and that has to
 * work from anywhere on the panel, including on top of its list. So no-swipe
 * ownership is per-axis rather than per-subtree: a vertical list owns vertical
 * drags and lets sideways ones through to close the panel, and it owns a
 * vertical drag only while it still has somewhere to scroll — at the top of
 * its travel a downward drag belongs to the panel, which is how the screen
 * time panel is pulled shut. See {@link #setNoSwipe(View, int)}.
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

    /** Axis bits for {@link #setNoSwipe(View, int)}. The values double as the
     *  {@link #axis} codes, so ownership is one mask test. */
    public static final int AXIS_H = 1;
    public static final int AXIS_V = 2;

    /** Extra no-swipe bit: owns vertical drags conditionally — see
     *  {@link #setVerticalScroller(View)}. */
    private static final int SCROLLS_V = 4;

    private static final float H_THRESHOLD = 0.22f;  // fraction of width
    private static final float V_THRESHOLD = 0.16f;  // fraction of height
    private static final long  SETTLE_MS   = 260L;

    private View home, settings, drawer, time;

    /** Non-null while that panel's settle animator owns its translation.
     *  Indexed by VIEW_*; the home slot stays null, home never moves. */
    private final ViewPropertyAnimator[] running = new ViewPropertyAnimator[4];

    private int view = VIEW_HOME;
    private float downX, downY;
    private int axis;                 // 0 open, AXIS_H, AXIS_V, or -1 for not ours
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

    /** Marks a view and its descendants as owning their own drags on both
     *  axes — the prototype's [data-noswipe]. */
    public static void setNoSwipe(View v) { setNoSwipe(v, AXIS_H | AXIS_V); }

    /**
     * Marks a view as owning drags on {@code axes} only ({@link #AXIS_H},
     * {@link #AXIS_V}, or both). Drags on the other axis carry on to the
     * panel — that is what lets a horizontal swipe anywhere over a vertical
     * list close the panel the list lives in.
     *
     * Ownership does not stop the walk: a horizontal slider inside a
     * vertically-owned list is still found and still keeps its axis.
     */
    public static void setNoSwipe(View v, int axes) { v.setTag(NO_SWIPE_TAG, axes); }

    /**
     * Marks a vertically scrolling container: it owns a vertical drag only
     * while it can still scroll that way, so a drag that would scroll past
     * the end of its content becomes a panel gesture instead. Horizontal
     * drags always pass through.
     */
    public static void setVerticalScroller(View v) {
        v.setTag(NO_SWIPE_TAG, SCROLLS_V);
        // The stretch/glow at the end of the travel is off-style here, and now
        // actively misleading: it animates an edge that is in the middle of
        // handing the gesture over to the panel.
        v.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

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
                // A subtree that owns both axes is opaque to us from the first
                // event, taps included — that is what keeps a near-miss on the
                // dock or a panel header from reaching the double-tap
                // detector. Anything that owns only one axis has to wait for
                // the axis lock before we know whose gesture this is.
                if (inGestureInset(downX)
                        || ownedByChild(this, downX, downY, AXIS_H | AXIS_V, 0f)) {
                    axis = -1;   // this gesture is not ours
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (axis == -1) return false;
                if (axis == 0) {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (Math.abs(dx) < slop && Math.abs(dy) < slop) return false;
                    lockAxis(dx, dy);
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
                    lockAxis(dx, dy);
                    if (axis == -1) return false;
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

    /**
     * A scrolling child asks for this the instant it passes its own 8dp slop,
     * which is before our 12dp axis lock. Granting it there would hand every
     * drag that starts over a list to the list — including the sideways drag
     * that closes the panel the list is in. So we answer it ourselves: while
     * the axis is still open we keep receiving events and decide at 12dp;
     * once it is locked the decision cannot change, and the request passes
     * through as normal.
     */
    @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
        if (axis == 0) return;
        super.requestDisallowInterceptTouchEvent(disallow);
    }

    /**
     * Pick the gesture's axis from its first 12dp and decide who gets it:
     * this root, or the subtree under the finger. {@code axis} comes out 1, 2,
     * or -1 for "not ours".
     */
    private void lockAxis(float dx, float dy) {
        int a = Math.abs(dx) > Math.abs(dy) ? AXIS_H : AXIS_V;
        float delta = a == AXIS_H ? dx : dy;
        if (!moves(a, delta) || ownedByChild(this, downX, downY, a, delta)) {
            axis = -1;
            tracking = false;
            return;
        }
        axis = a;
        tracking = true;
    }

    /**
     * True if a drag of this sign on this axis takes the current view
     * somewhere. A gesture that cannot move anything is better left to
     * whatever is under it than claimed and swallowed — dragging up at the
     * bottom of the screen time list should keep scrolling the list, not
     * freeze it against a panel that is already home.
     */
    private boolean moves(int a, float delta) {
        if (a == AXIS_H) {
            switch (view) {
                case VIEW_HOME:     return true;      // right for settings, left for drawer
                case VIEW_SETTINGS: return delta < 0; // push it back off to the left
                case VIEW_DRAWER:   return delta > 0; // push it back off to the right
                default:            return false;
            }
        }
        switch (view) {
            case VIEW_HOME: return delta < 0;                 // swipe up for screen time
            case VIEW_TIME: return delta > 0;                 // pull down to close it
            default:        return false;
        }
    }

    private void drag(float dx, float dy, int w, int h) {
        if (axis == AXIS_H) {
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
        } else if (axis == AXIS_V) {
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
        if (axis == AXIS_H) {
            float th = w * H_THRESHOLD;
            if (view == VIEW_HOME) {
                if (dx > th)       next = VIEW_SETTINGS;
                else if (dx < -th) next = VIEW_DRAWER;
            } else if (view == VIEW_SETTINGS && dx < -th) next = VIEW_HOME;
            else if (view == VIEW_DRAWER && dx > th)      next = VIEW_HOME;
        } else if (axis == AXIS_V) {
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

    /**
     * True if some view under (x, y) — in {@code v}'s own coordinate space —
     * owns every axis in {@code wanted} for a drag of this sign. Pass a single
     * axis to ask who gets one gesture, or both to ask whether the subtree is
     * opaque to this root altogether.
     *
     * Coordinates are converted the same way {@code dispatchTouchEvent} does
     * it: add the parent's scroll, then subtract the child's layout position
     * and its translation. The scroll term is what matters here — the drawer's
     * app list is a scrolling container, so testing without it checked the
     * wrong rows the moment the list moved.
     *
     * A view that owns the other axis does not end the walk. The screen time
     * panel's slider sits inside its scroll view; the scroll view declines the
     * horizontal drag and the slider below it still has to claim it.
     */
    private static boolean ownedByChild(View v, float x, float y, int wanted, float delta) {
        Object tag = v.getTag(NO_SWIPE_TAG);
        if (tag instanceof Integer) {
            int owned = (Integer) tag;
            if ((owned & wanted) == wanted) return true;
            // canScrollVertically takes the scroll direction, which is the
            // opposite of the finger's: dragging down reveals content above.
            if (wanted == AXIS_V && (owned & SCROLLS_V) != 0
                    && v.canScrollVertically(delta > 0 ? -1 : 1)) {
                return true;
            }
        }
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != VISIBLE) continue;
            float cx = x + g.getScrollX() - c.getLeft() - c.getTranslationX();
            float cy = y + g.getScrollY() - c.getTop() - c.getTranslationY();
            if (cx < 0 || cx >= c.getWidth() || cy < 0 || cy >= c.getHeight()) continue;
            if (ownedByChild(c, cx, cy, wanted, delta)) return true;
        }
        return false;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
