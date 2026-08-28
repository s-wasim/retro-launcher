package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class PaletteResolverTest {

    // DESIGN_NOTES §2a. Source thresholds are 4.6, 7.6, 11, 16, 18.6, 20.4.
    @Test public void autoPaletteFollowsTheHourTable() {
        assertEquals("c64",    PaletteResolver.autoIdFor(0f));
        assertEquals("c64",    PaletteResolver.autoIdFor(4.59f));
        assertEquals("amber",  PaletteResolver.autoIdFor(4.6f));   // 04:36
        assertEquals("amber",  PaletteResolver.autoIdFor(7.59f));
        assertEquals("gb",     PaletteResolver.autoIdFor(7.6f));   // 07:36
        assertEquals("gb",     PaletteResolver.autoIdFor(10.99f));
        assertEquals("mono",   PaletteResolver.autoIdFor(11f));
        assertEquals("mono",   PaletteResolver.autoIdFor(15.99f));
        assertEquals("amber",  PaletteResolver.autoIdFor(16f));
        assertEquals("amber",  PaletteResolver.autoIdFor(18.59f));
        assertEquals("plasma", PaletteResolver.autoIdFor(18.6f));  // 18:36
        assertEquals("plasma", PaletteResolver.autoIdFor(20.39f));
        assertEquals("c64",    PaletteResolver.autoIdFor(20.4f));  // 20:24
        assertEquals("c64",    PaletteResolver.autoIdFor(23.99f));
    }

    @Test public void autoLabelsMatchTheSettingsCopy() {
        assertEquals("NIGHT",        PaletteResolver.autoLabelFor(2f));
        assertEquals("SUNRISE",      PaletteResolver.autoLabelFor(5f));
        assertEquals("MORNING",      PaletteResolver.autoLabelFor(9f));
        assertEquals("MIDDAY",       PaletteResolver.autoLabelFor(13f));
        assertEquals("GOLDEN HOUR",  PaletteResolver.autoLabelFor(17f));
        assertEquals("DUSK",         PaletteResolver.autoLabelFor(19f));
        assertEquals("NIGHT",        PaletteResolver.autoLabelFor(22f));
    }

    @Test public void everyPaletteHasBothThemes() {
        for (String id : new String[]{"gb","amber","c64","mono","plasma"}) {
            assertNotNull(Palettes.get(id, true));
            assertNotNull(Palettes.get(id, false));
        }
    }

    @Test public void gameBoyDarkMatchesTheSource() {
        Palette p = Palettes.get("gb", true);
        assertEquals(0xFF0B1508, p.bg);
        assertEquals(0xFF1D3315, p.tile);
        assertEquals(0xFF8BAC0F, p.p);
        assertEquals(0xFFCFE89A, p.a);
        assertEquals(0xFF0F2408, p.s);
        assertEquals(0xFFEEFFC4, p.h);
        assertEquals(0xFFA4C93C, p.ink);
    }

    @Test public void plasmaLightMatchesTheSource() {
        Palette p = Palettes.get("plasma", false);
        assertEquals(0xFFF6DCD6, p.bg);
        assertEquals(0xFF4A1418, p.tile);
        assertEquals(0xFFE2464A, p.p);
        assertEquals(0xFFFF9A86, p.a);
        assertEquals(0xFF7D1418, p.s);
        assertEquals(0xFFFFE8DE, p.h);
        assertEquals(0xFF4A1418, p.ink);
    }

    // veil = bg with alpha D9 in dark, E0 in light. DESIGN_NOTES §3.
    @Test public void veilAppliesTheThemeAlphaToTheBackground() {
        assertEquals(0xD90B1508, Palettes.get("gb", true).veil());
        assertEquals(0xE0DCEBB4, Palettes.get("gb", false).veil());
    }

    @Test public void explicitChoiceOverridesTheClock() {
        // 02:00 would auto-resolve to c64; an explicit pick must win.
        Palette p = PaletteResolver.resolve("plasma", "dark", 2f, true);
        assertEquals("plasma", p.id);
    }

    @Test public void autoChoiceFollowsTheClock() {
        assertEquals("mono", PaletteResolver.resolve("auto", "dark", 13f, true).id);
    }

    @Test public void systemThemeDefersToTheOsFlag() {
        assertTrue(PaletteResolver.resolve("gb", "system", 13f, true).dark);
        assertFalse(PaletteResolver.resolve("gb", "system", 13f, false).dark);
    }

    @Test public void explicitThemeIgnoresTheOsFlag() {
        assertTrue(PaletteResolver.resolve("gb", "dark", 13f, false).dark);
        assertFalse(PaletteResolver.resolve("gb", "light", 13f, true).dark);
    }

    @Test public void unknownIdFallsBackToGameBoyRatherThanCrashing() {
        assertEquals("gb", PaletteResolver.resolve("nonsense", "dark", 13f, true).id);
    }
}
