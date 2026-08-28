package com.retro.launcher.core;

/** The ten role sets from DESIGN_NOTES §3, verified against the prototype. */
public final class Palettes {

    private Palettes() {}

    public static final String GB     = "gb";
    public static final String AMBER  = "amber";
    public static final String C64    = "c64";
    public static final String MONO   = "mono";
    public static final String PLASMA = "plasma";

    public static final String[] IDS = { GB, AMBER, C64, MONO, PLASMA };

    private static final Palette GB_L = new Palette(GB, "GAME BOY", false,
            0xFFDCEBB4, 0xFF33552A, 0xFFA4C93C, 0xFFEAF8A8, 0xFF1B3311, 0xFFF6FFDC, 0xFF1B3311);
    private static final Palette GB_D = new Palette(GB, "GAME BOY", true,
            0xFF0B1508, 0xFF1D3315, 0xFF8BAC0F, 0xFFCFE89A, 0xFF0F2408, 0xFFEEFFC4, 0xFFA4C93C);

    private static final Palette AM_L = new Palette(AMBER, "CRT AMBER", false,
            0xFFF2E5C8, 0xFF3B2612, 0xFFE79A20, 0xFFFFD873, 0xFF7D3F0C, 0xFFFFF3D2, 0xFF3B2612);
    private static final Palette AM_D = new Palette(AMBER, "CRT AMBER", true,
            0xFF140C05, 0xFF2B1A0A, 0xFFFFB020, 0xFFFFD873, 0xFF6D3407, 0xFFFFF0C8, 0xFFE79A20);

    private static final Palette C6_L = new Palette(C64, "C64 BLUE", false,
            0xFFDADEF8, 0xFF3A2F8F, 0xFF7C70DA, 0xFFB9C8FF, 0xFF221A5E, 0xFFF2F4FF, 0xFF221A5E);
    private static final Palette C6_D = new Palette(C64, "C64 BLUE", true,
            0xFF0B0820, 0xFF221A5E, 0xFF7C70DA, 0xFFB9C8FF, 0xFF140F3A, 0xFFEEF1FF, 0xFF9A90FF);

    private static final Palette MO_L = new Palette(MONO, "MONO GREY", false,
            0xFFE4E4E6, 0xFF3A3C40, 0xFF8E9196, 0xFFC6C9CE, 0xFF22242A, 0xFFFBFBFD, 0xFF22242A);
    private static final Palette MO_D = new Palette(MONO, "MONO GREY", true,
            0xFF0C0D0F, 0xFF24262B, 0xFF9AA0A8, 0xFFC6C9CE, 0xFF15171B, 0xFFF2F4F8, 0xFFC6C9CE);

    private static final Palette PL_L = new Palette(PLASMA, "PLASMA RED", false,
            0xFFF6DCD6, 0xFF4A1418, 0xFFE2464A, 0xFFFF9A86, 0xFF7D1418, 0xFFFFE8DE, 0xFF4A1418);
    private static final Palette PL_D = new Palette(PLASMA, "PLASMA RED", true,
            0xFF140507, 0xFF33090D, 0xFFFF4A4A, 0xFFFF9A86, 0xFF6D0D10, 0xFFFFDCD2, 0xFFFF9A86);

    /** Falls back to Game Boy for an unknown id — never returns null. */
    public static Palette get(String id, boolean dark) {
        if (AMBER.equals(id))  return dark ? AM_D : AM_L;
        if (C64.equals(id))    return dark ? C6_D : C6_L;
        if (MONO.equals(id))   return dark ? MO_D : MO_L;
        if (PLASMA.equals(id)) return dark ? PL_D : PL_L;
        return dark ? GB_D : GB_L;
    }
}
