package com.retro.launcher.core;

/**
 * Where a popup goes when the user long-presses at a point.
 *
 * Android's {@code showAsDropDown(anchor, 0, 0)} can only say "below this
 * view, at its left edge", which is why the drawer's quick-action box opened
 * on the far left of whichever row was pressed and ran off the bottom of the
 * screen for rows near it. Expressing "at this point, and on-screen" needs
 * {@code showAtLocation} and an explicit coordinate, and that coordinate is
 * pure arithmetic — so it lives here, testable, rather than in the view.
 */
public final class PopupPlacement {

    private PopupPlacement() {}

    /**
     * The top-left corner to show a {@code width x height} popup at, for a
     * long-press at {@code (touchX, touchY)} in screen coordinates.
     *
     * <p>Four rules, in order:
     * <ol>
     *   <li>Start at the touch point.</li>
     *   <li>If the popup would cross the bottom inset, flip it so its
     *       <em>bottom</em> edge sits on the touch point.</li>
     *   <li>If it would cross the right inset, shift left until its right
     *       edge is inset-aligned.</li>
     *   <li>Clamp to the top and left insets, so content taller or wider
     *       than the space still starts on-screen rather than above it.</li>
     * </ol>
     *
     * @return {@code {x, y}} in screen coordinates
     */
    public static int[] place(float touchX, float touchY,
                              int width, int height,
                              int screenWidth, int screenHeight,
                              int insetLeft, int insetTop,
                              int insetRight, int insetBottom) {
        float x = touchX;
        float y = touchY;

        if (y + height > screenHeight - insetBottom) y = touchY - height;
        if (x + width > screenWidth - insetRight) x = screenWidth - insetRight - width;

        if (x < insetLeft) x = insetLeft;
        if (y < insetTop) y = insetTop;

        return new int[]{Math.round(x), Math.round(y)};
    }
}
