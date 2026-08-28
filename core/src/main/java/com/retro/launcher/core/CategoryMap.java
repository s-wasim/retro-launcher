package com.retro.launcher.core;

/**
 * Maps {@code ApplicationInfo.category} (API 26) to one of the four tabs the
 * drawer ships with. See DESIGN_NOTES §9 delta 3: SOCIAL stays SOCIAL;
 * GAME/AUDIO/VIDEO/IMAGE fold into MEDIA (the prototype ships no GAMES tab);
 * NEWS/PRODUCTIVITY fold into WORK; MAPS and UNDEFINED fall back to UTILITY.
 *
 * The constants mirror {@code android.content.pm.ApplicationInfo}'s category
 * ints so this stays testable on a bare JDK — see the class layout in the
 * design spec §2.3.
 */
public final class CategoryMap {

    private CategoryMap() {}

    public static final int CATEGORY_UNDEFINED    = -1;
    public static final int CATEGORY_GAME         = 0;
    public static final int CATEGORY_AUDIO        = 1;
    public static final int CATEGORY_VIDEO        = 2;
    public static final int CATEGORY_IMAGE        = 3;
    public static final int CATEGORY_SOCIAL       = 4;
    public static final int CATEGORY_NEWS         = 5;
    public static final int CATEGORY_MAPS         = 6;
    public static final int CATEGORY_PRODUCTIVITY = 7;

    public static String forCategory(int category) {
        switch (category) {
            case CATEGORY_SOCIAL:
                return "SOCIAL";
            case CATEGORY_GAME:
            case CATEGORY_AUDIO:
            case CATEGORY_VIDEO:
            case CATEGORY_IMAGE:
                return "MEDIA";
            case CATEGORY_NEWS:
            case CATEGORY_PRODUCTIVITY:
                return "WORK";
            case CATEGORY_MAPS:
            case CATEGORY_UNDEFINED:
            default:
                return "UTILITY";
        }
    }
}
