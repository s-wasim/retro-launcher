package com.retro.launcher.core;

/**
 * Turns (user choice, theme preference, clock, OS dark flag) into one Palette.
 *
 * The hour thresholds are the prototype's autoPal() verbatim — see
 * DESIGN_NOTES §2a. They are decimal hours, so 4.6 means 04:36, not 04:60.
 */
public final class PaletteResolver {

    private PaletteResolver() {}

    public static final String AUTO   = "auto";
    public static final String SYSTEM = "system";
    public static final String LIGHT  = "light";
    public static final String DARK   = "dark";

    public static String autoIdFor(float hour) {
        if (hour < 4.6f)  return Palettes.C64;
        if (hour < 7.6f)  return Palettes.AMBER;
        if (hour < 11f)   return Palettes.GB;
        if (hour < 16f)   return Palettes.MONO;
        if (hour < 18.6f) return Palettes.AMBER;
        if (hour < 20.4f) return Palettes.PLASMA;
        return Palettes.C64;
    }

    /** The note shown on the AUTO / TIME card in Settings. */
    public static String autoLabelFor(float hour) {
        if (hour < 4.6f)  return "NIGHT";
        if (hour < 7.6f)  return "SUNRISE";
        if (hour < 11f)   return "MORNING";
        if (hour < 16f)   return "MIDDAY";
        if (hour < 18.6f) return "GOLDEN HOUR";
        if (hour < 20.4f) return "DUSK";
        return "NIGHT";
    }

    public static Palette resolve(String choice, String theme,
                                  float hour, boolean systemDark) {
        String id = (choice == null || AUTO.equals(choice))
                ? autoIdFor(hour) : choice;
        boolean dark;
        if (DARK.equals(theme))       dark = true;
        else if (LIGHT.equals(theme)) dark = false;
        else                          dark = systemDark;
        return Palettes.get(id, dark);
    }
}
