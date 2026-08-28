# Retro Launcher — Tier 0 & 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a native Android launcher you can set as your home screen — an animated 24-hour pixel sky, a clock/weather widget with three tap targets, and a dock of pinned apps — with the four-panel swipe navigation skeleton already in place beneath it.

**Architecture:** One `Activity` hosting a custom `LauncherRoot` `ViewGroup` that holds four panels at fixed offsets and translates them on touch. A `SurfaceView` renders a 108×N pixel sky on a background thread at 30fps. All pure logic lives in a separate `:core` Java module with zero Android imports, so it is unit-testable on a bare JDK.

**Tech Stack:** Java 17, Android Gradle Plugin 8.5.2, `minSdk 26` / `targetSdk 34`, framework views only. No Kotlin, no AndroidX, no Material, no runtime dependencies, no image or font assets. JUnit 4.13.2 as a test-only dependency on `:core`.

**Spec:** `docs/superpowers/specs/2026-08-28-retro-launcher-design.md`
**Token audit:** `design/DESIGN_NOTES.md`
**Amended constraints:** `HANDOFF.md` §0

---

## Scope of this document

The spec defines six tiers. This plan covers **Tier 0 (Foundation)** and
**Tier 1 (Sky + Home)** at full step-by-step granularity — the work that
produces a launcher you can actually live on.

**Tiers 2–5 are decomposed at the end** with their files, interfaces and test
lists, but not step-level code. That is deliberate, not an omission: Tier 2
ends in a hard decision gate (which `IconSource` wins, judged on measured
device performance) whose outcome changes the shape of Tiers 3–5. Writing
literal code for those steps today would be fiction. **Each tier gets its own
plan document, written when its predecessor lands.**

---

## Global Constraints

Every task's requirements implicitly include all of these. Values are copied
verbatim from the spec and `HANDOFF.md` §0.

- **Java only.** No Kotlin anywhere.
- **No AndroidX, no Compose, no Material.** Framework classes only.
- **`ListView`, never `RecyclerView`.**
- **`minSdk 26`, `targetSdk 34`, `compileSdk 34`.** Java source/target 17.
- **Runtime dependencies: zero.** `:app`'s only `implementation` line is
  `project(':core')`. `testImplementation 'junit:junit:4.13.2'` on `:core`
  only — test-only, never in the APK.
- **Exactly three permissions**, no others:
  `android.permission.PACKAGE_USAGE_STATS`,
  `android.permission.INTERNET`,
  `android.permission.ACCESS_COARSE_LOCATION`.
- **No image assets** beyond the single adaptive launcher icon. **No font
  files** — `Typeface.MONOSPACE` throughout. **No WebView.**
- **R8 full mode + `shrinkResources true`** on both build types.
- **Package:** `com.retro.launcher` (`:app`), `com.retro.launcher.core`
  (`:core`). App label: `Retro Launcher`.
- **Portrait-locked, edge-to-edge.** Wallpaper draws behind the system bars;
  UI content is inset.
- **`:core` classes must not import anything from `android.*`.** This is
  load-bearing — it is what lets `gradle :core:test` run without an SDK.
- **All colour constants are ARGB ints** written as `0xFFRRGGBB`.
- **Report the APK size** in every CI run.

---

## File Structure

### New module: `:core` (plain `java-library`, no Android)

| File | Responsibility |
|---|---|
| `core/build.gradle` | `java-library` plugin, Java 17, JUnit test dep |
| `core/src/main/java/com/retro/launcher/core/Metrics.java` | `cqw` → px given width/density; text floor |
| `core/src/main/java/com/retro/launcher/core/Palette.java` | One resolved palette: 7 role colours + `veil()` |
| `core/src/main/java/com/retro/launcher/core/Palettes.java` | The 10 static role sets, by id and theme |
| `core/src/main/java/com/retro/launcher/core/PaletteResolver.java` | hour + choice + theme → `Palette` |
| `core/src/main/java/com/retro/launcher/core/Bayer.java` | 4×4 ordered-dither matrix |
| `core/src/main/java/com/retro/launcher/core/SkyKeyframes.java` | 14-entry gradient table + interpolation |
| `core/src/main/java/com/retro/launcher/core/SkyRenderer.java` | The `frame()` port into a caller-owned `int[]` |
| `core/src/main/java/com/retro/launcher/core/Weather.java` | Immutable `tempC` / `w` / condition label |
| `core/src/main/java/com/retro/launcher/core/SyntheticWeather.java` | The prototype's temperature formula + 10 bands |
| `core/src/main/java/com/retro/launcher/core/DateFormatter.java` | The 15-token date formatter |

Tests mirror each under `core/src/test/java/com/retro/launcher/core/`.

### Modified / new in `:app`

| File | Responsibility |
|---|---|
| `app/build.gradle` | namespace rename, `implementation project(':core')` |
| `app/src/main/AndroidManifest.xml` | home intent filter, `<queries>`, 3 permissions, portrait |
| `.../launcher/HomeActivity.java` | lifecycle, insets, minute tick, palette dispatch |
| `.../launcher/ui/LauncherRoot.java` | panel layout, gesture routing, transforms |
| `.../launcher/ui/HomePanel.java` | widget + dock host, double-tap detection |
| `.../launcher/ui/ClockWidget.java` | three tap regions, blinking colon |
| `.../launcher/ui/DockView.java` | up to 5 slots, launch, long-press |
| `.../launcher/sky/SkyView.java` | `SurfaceView` + render thread |
| `.../launcher/theme/Tint.java` | repaint a view tree against a `Palette` |
| `.../launcher/data/Prefs.java` | `SharedPreferences`, the 14 keys |
| `.../launcher/data/WeatherRepository.java` | cache + refresh policy |
| `.../launcher/util/Insets.java` | gesture-inset lookup, API-guarded |
| `.../res/layout/panel_home.xml` etc. | panel layouts |

### Deleted

`app/src/main/java/com/minimal/launcher/` — both files. `HomeActivity` is
rewritten; the old `ListView` behaviour belongs to the drawer (Tier 2), and
`AppEntry` is reintroduced there.

---

# TIER 0 — Foundation

## Task 1: Restructure into `:core` + `:app`, prove the test harness

**Files:**
- Create: `core/build.gradle`
- Create: `core/src/main/java/com/retro/launcher/core/Metrics.java`
- Test: `core/src/test/java/com/retro/launcher/core/MetricsTest.java`
- Modify: `settings.gradle`
- Modify: `app/build.gradle`
- Modify: `.github/workflows/build.yml`
- Move: `app/src/main/java/com/minimal/launcher/` → `app/src/main/java/com/retro/launcher/`

**Interfaces:**
- Consumes: nothing
- Produces: `Metrics(float widthPx, float density, float scaledDensity)` with
  `float cqw(float)`, `float dp(float)`, `float textPx(float cqwUnits, float minSp)`

- [ ] **Step 1: Install a JDK and Gradle so tests can run locally**

There is no JDK on this machine. Without one the TDD loop becomes
"push and wait for CI," which is unworkable.

```bash
sudo apt-get update && sudo apt-get install -y openjdk-17-jdk
java -version   # expect: openjdk version "17.x"
```

Gradle is fetched by the wrapper if present; otherwise install it:

```bash
sudo apt-get install -y gradle && gradle --version
```

Only the JDK is strictly required for `:core:test`. The Android SDK is
**not** needed for this plan's tests, and `:app` still builds only in CI.

- [ ] **Step 2: Create the `:core` module build file**

`core/build.gradle`:

```groovy
plugins { id 'java-library' }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Test-only. Never enters the APK. See HANDOFF.md §0 row 4.
    testImplementation 'junit:junit:4.13.2'
}

tasks.withType(Test).configureEach { useJUnit() }
```

- [ ] **Step 3: Register the module**

In `settings.gradle`, change the last line:

```groovy
rootProject.name = "RetroLauncher"
include ':core'
include ':app'
```

- [ ] **Step 4: Write the failing test**

`core/src/test/java/com/retro/launcher/core/MetricsTest.java`:

```java
package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MetricsTest {

    @Test public void cqwIsOnePercentOfScreenWidth() {
        Metrics m = new Metrics(1080f, 3f, 3f);
        assertEquals(43.2f, m.cqw(4f), 0.001f);   // dock/widget inset
        assertEquals(101.52f, m.cqw(9.4f), 0.001f); // clock digits
    }

    @Test public void cqwScalesWithScreenWidth() {
        assertEquals(14.4f, new Metrics(360f, 1f, 1f).cqw(4f), 0.001f);
        assertEquals(19.2f, new Metrics(480f, 1f, 1f).cqw(4f), 0.001f);
    }

    @Test public void dpMultipliesByDensity() {
        assertEquals(144f, new Metrics(1080f, 3f, 3f).dp(48f), 0.001f);
    }

    @Test public void textPxHonoursTheLegibilityFloor() {
        // 2.2cqw on a 360px-wide screen is 7.92px — below a 10sp floor.
        Metrics m = new Metrics(360f, 1f, 1f);
        assertEquals(10f, m.textPx(2.2f, 10f), 0.001f);
    }

    @Test public void textPxUsesCqwWhenAboveTheFloor() {
        Metrics m = new Metrics(1080f, 3f, 3f);
        assertEquals(101.52f, m.textPx(9.4f, 10f), 0.001f);
    }
}
```

- [ ] **Step 5: Run the test and confirm it fails**

```bash
gradle :core:test --no-daemon
```

Expected: compilation failure — `cannot find symbol: class Metrics`.

- [ ] **Step 6: Write the implementation**

`core/src/main/java/com/retro/launcher/core/Metrics.java`:

```java
package com.retro.launcher.core;

/**
 * The prototype sizes everything in {@code cqw} — percent of the screen's own
 * width. Freezing that into fixed dp breaks on narrow phones and foldables, so
 * every layout value in the launcher resolves through here at runtime instead.
 *
 * Takes width and density as arguments rather than reading Resources, so it
 * carries no Android dependency and can be tested at arbitrary screen sizes.
 */
public final class Metrics {

    private final float widthPx;
    private final float density;
    private final float scaledDensity;

    public Metrics(float widthPx, float density, float scaledDensity) {
        this.widthPx = widthPx;
        this.density = density;
        this.scaledDensity = scaledDensity;
    }

    /** One cqw is 1% of screen width. Returns pixels. */
    public float cqw(float units) {
        return widthPx * units / 100f;
    }

    /** Density-independent pixels to pixels. */
    public float dp(float units) {
        return units * density;
    }

    /**
     * A cqw-derived text size in pixels, never smaller than {@code minSp}.
     * The prototype's micro labels sit at 2.2cqw, which lands below Android's
     * comfortable reading floor on a narrow screen — see DESIGN_NOTES §4.
     */
    public float textPx(float cqwUnits, float minSp) {
        float px = cqw(cqwUnits);
        float floorPx = minSp * scaledDensity;
        return px < floorPx ? floorPx : px;
    }
}
```

- [ ] **Step 7: Run the test and confirm it passes**

```bash
gradle :core:test --no-daemon
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 8: Rename the app package**

```bash
cd /home/saad-waseem/Documents/minimal_launcher
mkdir -p app/src/main/java/com/retro
git mv app/src/main/java/com/minimal/launcher app/src/main/java/com/retro/launcher
rmdir app/src/main/java/com/minimal
```

Then in both moved `.java` files change the first line to
`package com.retro.launcher;`.

- [ ] **Step 9: Point `:app` at `:core` and rename its namespace**

In `app/build.gradle`, change `namespace` and `applicationId` to
`com.retro.launcher`, and replace the empty dependencies block:

```groovy
dependencies {
    implementation project(':core')
}
```

Note the `dependencies { }` block currently sits *inside* the `android { }`
block, which is wrong — Gradle silently ignores it there. Move it to the top
level of the file, as a sibling of `android { }`.

In `app/src/main/res/values/strings.xml`, set the app name to
`Retro Launcher`.

- [ ] **Step 10: Add a test step to CI**

In `.github/workflows/build.yml`, insert before the `Build` step:

```yaml
      - name: Unit tests
        run: gradle :core:test --no-daemon
```

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: split pure logic into :core module, add Metrics

Renames com.minimal.launcher to com.retro.launcher and introduces a plain
java-library :core module so pure logic is unit-testable on a bare JDK,
without an Android SDK or emulator. JUnit is test-only and never enters
the APK. See HANDOFF.md §0 rows 3 and 4."
```

---

## Task 2: Palettes and the auto-by-hour resolver

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/Palette.java`
- Create: `core/src/main/java/com/retro/launcher/core/Palettes.java`
- Create: `core/src/main/java/com/retro/launcher/core/PaletteResolver.java`
- Test: `core/src/test/java/com/retro/launcher/core/PaletteResolverTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `Palette` with public final ints `bg tile p a s h ink`, `String id`,
    `String label`, `boolean dark`, and `int veil()`
  - `Palettes.get(String id, boolean dark)` → `Palette`
  - `PaletteResolver.autoIdFor(float hour)` → `String`
  - `PaletteResolver.autoLabelFor(float hour)` → `String`
  - `PaletteResolver.resolve(String choice, String theme, float hour, boolean systemDark)` → `Palette`,
    where `choice` is `"auto"` or a palette id and `theme` is
    `"system"`, `"light"` or `"dark"`

- [ ] **Step 1: Write the failing test**

`core/src/test/java/com/retro/launcher/core/PaletteResolverTest.java`:

```java
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
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
gradle :core:test --no-daemon --tests '*PaletteResolverTest*'
```

Expected: compilation failure — `cannot find symbol: class Palette`.

- [ ] **Step 3: Write `Palette`**

`core/src/main/java/com/retro/launcher/core/Palette.java`:

```java
package com.retro.launcher.core;

/**
 * One resolved colour set. The prototype names six roles plus derived ink;
 * see DESIGN_NOTES §3 for the full table.
 */
public final class Palette {

    public final String id;
    public final String label;
    public final boolean dark;

    public final int bg;    // screen background
    public final int tile;  // icon body
    public final int p;     // primary accent — borders, active fills
    public final int a;     // secondary accent
    public final int s;     // shadow / recessed
    public final int h;     // highlight, near-white
    public final int ink;   // text

    Palette(String id, String label, boolean dark,
            int bg, int tile, int p, int a, int s, int h, int ink) {
        this.id = id; this.label = label; this.dark = dark;
        this.bg = bg; this.tile = tile; this.p = p;
        this.a = a; this.s = s; this.h = h; this.ink = ink;
    }

    /**
     * The translucent background used by the clock widget, dock and scrubber.
     * The prototype writes it as an 8-digit CSS hex — bg plus alpha D9 in dark
     * and E0 in light. Android wants ARGB, so the alpha moves to the front.
     */
    public int veil() {
        int alpha = dark ? 0xD9 : 0xE0;
        return (alpha << 24) | (bg & 0x00FFFFFF);
    }
}
```

- [ ] **Step 4: Write `Palettes`**

`core/src/main/java/com/retro/launcher/core/Palettes.java`:

```java
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
```

- [ ] **Step 5: Write `PaletteResolver`**

`core/src/main/java/com/retro/launcher/core/PaletteResolver.java`:

```java
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
```

- [ ] **Step 6: Run the tests and confirm they pass**

```bash
gradle :core:test --no-daemon
```

Expected: `BUILD SUCCESSFUL`, 16 tests passed.

- [ ] **Step 7: Commit**

```bash
git add core/
git commit -m "feat(core): palettes and the auto-by-hour resolver

Ten role sets from DESIGN_NOTES §3 and the autoPal() hour thresholds from
§2a, both verified against the prototype source. Boundary tests pin the
awkward decimal hours (04:36, 07:36, 18:36, 20:24)."
```

---

## Task 3: Bayer matrix and the sky gradient table

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/Bayer.java`
- Create: `core/src/main/java/com/retro/launcher/core/SkyKeyframes.java`
- Test: `core/src/test/java/com/retro/launcher/core/SkyKeyframesTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `Bayer.M` — `int[4][4]`; `Bayer.bias(int x, int y)` → `float` in `[-0.5, 0.5)`
  - `SkyKeyframes.at(float hour, float[] out6)` filling
    `{topR, topG, topB, botR, botG, botB}` as floats 0–255

- [ ] **Step 1: Write the failing test**

`core/src/test/java/com/retro/launcher/core/SkyKeyframesTest.java`:

```java
package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SkyKeyframesTest {

    private float[] at(float hour) {
        float[] out = new float[6];
        SkyKeyframes.at(hour, out);
        return out;
    }

    // DESIGN_NOTES §2b keyframe table.
    @Test public void midnightMatchesTheFirstKeyframe() {
        float[] c = at(0f);
        assertEquals(10f, c[0], 0.001f);  assertEquals(14f, c[1], 0.001f);
        assertEquals(38f, c[2], 0.001f);  assertEquals(22f, c[3], 0.001f);
        assertEquals(28f, c[4], 0.001f);  assertEquals(64f, c[5], 0.001f);
    }

    @Test public void noonMatchesItsKeyframe() {
        float[] c = at(12f);
        assertEquals(54f,  c[0], 0.001f); assertEquals(130f, c[1], 0.001f);
        assertEquals(228f, c[2], 0.001f); assertEquals(156f, c[3], 0.001f);
        assertEquals(208f, c[4], 0.001f); assertEquals(247f, c[5], 0.001f);
    }

    @Test public void sunriseKeyframeMatches() {
        float[] c = at(6.2f);   // #2e3e80 over #d67668
        assertEquals(46f,  c[0], 0.001f); assertEquals(62f,  c[1], 0.001f);
        assertEquals(128f, c[2], 0.001f); assertEquals(214f, c[3], 0.001f);
        assertEquals(118f, c[4], 0.001f); assertEquals(104f, c[5], 0.001f);
    }

    @Test public void interpolatesLinearlyBetweenKeyframes() {
        // Halfway between 22.0 [12,16,44] and 24.0 [10,14,38].
        float[] c = at(23f);
        assertEquals(11f, c[0], 0.001f);
        assertEquals(15f, c[1], 0.001f);
        assertEquals(41f, c[2], 0.001f);
    }

    @Test public void endOfDayMatchesStartOfDay() {
        float[] a = at(0f), b = at(24f);
        for (int i = 0; i < 6; i++) assertEquals(a[i], b[i], 0.001f);
    }

    @Test public void hoursOutsideTheRangeClampRatherThanCrash() {
        float[] lo = at(-1f), hi = at(25f), zero = at(0f);
        for (int i = 0; i < 6; i++) {
            assertEquals(zero[i], lo[i], 0.001f);
            assertEquals(zero[i], hi[i], 0.001f);
        }
    }

    @Test public void bayerBiasIsCenteredAndTiles() {
        assertEquals(-0.5f,   Bayer.bias(0, 0), 0.001f);   //  0/16 - 0.5
        assertEquals(0.4375f, Bayer.bias(0, 3), 0.001f);   // 15/16 - 0.5
        assertEquals(Bayer.bias(0, 0), Bayer.bias(4, 4), 0.001f);
        assertEquals(Bayer.bias(1, 2), Bayer.bias(9, 6), 0.001f);
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
gradle :core:test --no-daemon --tests '*SkyKeyframesTest*'
```

Expected: compilation failure — `cannot find symbol: class Bayer`.

- [ ] **Step 3: Write `Bayer`**

`core/src/main/java/com/retro/launcher/core/Bayer.java`:

```java
package com.retro.launcher.core;

/**
 * The 4x4 ordered-dither matrix the prototype uses in three places: sky
 * quantization, the sun and moon discs, and the optional palette tint.
 *
 * This dithering is not an artifact to be cleaned up — it is what produces the
 * retro banding. See DESIGN_NOTES §2b.
 */
public final class Bayer {

    private Bayer() {}

    public static final int[][] M = {
            { 0,  8,  2, 10},
            {12,  4, 14,  6},
            { 3, 11,  1,  9},
            {15,  7, 13,  5}
    };

    /** Threshold bias in [-0.5, 0.4375], tiling every 4 pixels. */
    public static float bias(int x, int y) {
        return M[y & 3][x & 3] / 16f - 0.5f;
    }
}
```

- [ ] **Step 4: Write `SkyKeyframes`**

`core/src/main/java/com/retro/launcher/core/SkyKeyframes.java`:

```java
package com.retro.launcher.core;

/**
 * The 24-hour sky gradient. Fourteen keyframes of (top, bottom) RGB,
 * linearly interpolated. Transcribed from the prototype's SKY table —
 * see DESIGN_NOTES §2b.
 */
public final class SkyKeyframes {

    private SkyKeyframes() {}

    private static final float[] H = {
            0f, 3.5f, 5f, 6.2f, 7f, 8.5f, 12f,
            15.5f, 17.2f, 18.4f, 19.3f, 20.4f, 22f, 24f
    };

    private static final int[][] TOP = {
            { 10, 14, 38}, { 11, 15, 42}, { 20, 24, 62}, { 46, 62,128},
            { 62,104,182}, { 68,136,216}, { 54,130,228}, { 64,134,224},
            { 78,132,206}, { 62, 84,164}, { 40, 44,110}, { 22, 27, 70},
            { 12, 16, 44}, { 10, 14, 38}
    };

    private static final int[][] BOT = {
            { 22, 28, 64}, { 34, 32, 76}, { 82, 56,104}, {214,118,104},
            {255,166,112}, {176,214,246}, {156,208,247}, {178,206,240},
            {248,196,152}, {255,142, 88}, {206, 84, 96}, { 96, 52,104},
            { 36, 34, 78}, { 22, 28, 64}
    };

    /**
     * Fills {@code out} with {topR, topG, topB, botR, botG, botB} as 0-255
     * floats. Hours outside [0, 24] clamp to the endpoints.
     */
    public static void at(float hour, float[] out) {
        if (hour <= H[0]) { copy(0, out); return; }
        if (hour >= H[H.length - 1]) { copy(H.length - 1, out); return; }

        for (int i = 0; i < H.length - 1; i++) {
            if (hour >= H[i] && hour <= H[i + 1]) {
                float t = (hour - H[i]) / (H[i + 1] - H[i]);
                for (int c = 0; c < 3; c++) {
                    out[c]     = lerp(TOP[i][c], TOP[i + 1][c], t);
                    out[c + 3] = lerp(BOT[i][c], BOT[i + 1][c], t);
                }
                return;
            }
        }
        copy(0, out);
    }

    private static void copy(int i, float[] out) {
        for (int c = 0; c < 3; c++) { out[c] = TOP[i][c]; out[c + 3] = BOT[i][c]; }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
```

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
gradle :core:test --no-daemon
```

Expected: `BUILD SUCCESSFUL`, 23 tests passed.

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -m "feat(core): Bayer matrix and 24-hour sky gradient table

Fourteen keyframes transcribed from the prototype's SKY constant, with
boundary and interpolation tests. The Bayer matrix is shared by sky
quantization, the sun/moon discs and the palette tint mode."
```

---

## Task 4: Manifest, theme and preferences

**Files:**
- Rewrite: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/retro/launcher/data/Prefs.java`
- Create: `app/src/main/java/com/retro/launcher/util/Insets.java`
- Modify: `app/src/main/res/values/styles.xml`
- Delete: `app/src/main/res/layout/row_app.xml`

**Interfaces:**
- Consumes: `PaletteResolver` constants from Task 2
- Produces: `Prefs` with typed getters/setters for the 14 keys, and
  `Insets.gestureLeftRight(View)` → `int[]{left, right}` in pixels

This task has no unit test — it is Android-only glue with no logic worth
pinning. Its deliverable is verified by Task 6's device check.

- [ ] **Step 1: Rewrite the manifest**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Required on API 30+. Without this, queryIntentActivities() returns an
         empty list and the launcher silently shows no apps. -->
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>

    <!-- Exactly three, per HANDOFF.md §0 row 1. Do not add a fourth. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission
        android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/AppTheme">

        <activity
            android:name=".HomeActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:stateNotNeeded="true"
            android:excludeFromRecents="true"
            android:screenOrientation="portrait"
            android:configChanges="uiMode|keyboardHidden|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

Add `xmlns:tools="http://schemas.android.com/tools"` to the `<manifest>` tag
alongside the android namespace — `PACKAGE_USAGE_STATS` is a signature-level
permission and lint flags it without the ignore.

`configChanges` includes `uiMode` so a system dark-mode switch does not
recreate the Activity and restart the render thread.

- [ ] **Step 2: Make the theme transparent and borderless**

`app/src/main/res/values/styles.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="@android:style/Theme.Material.NoActionBar">
        <item name="android:windowBackground">@android:color/black</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowDrawsSystemBarBackgrounds">true</item>
        <item name="android:windowShowWallpaper">false</item>
    </style>
</resources>
```

- [ ] **Step 3: Write `Prefs`**

`app/src/main/java/com/retro/launcher/data/Prefs.java`:

```java
package com.retro.launcher.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.retro.launcher.core.PaletteResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The prototype's fourteen persisted keys, one for one. Deliberately absent:
 * the current view, tab and sheet — the launcher always reopens on home.
 * See DESIGN_NOTES §8.
 *
 * Every getter falls back to its own default independently, so a corrupt dock
 * list cannot take the palette down with it.
 */
public final class Prefs {

    private static final String FILE = "retro-launcher-v1";

    public static final String K_PAL      = "pal";
    public static final String K_THEME    = "theme";
    public static final String K_TINT     = "tint";
    public static final String K_HOUR12   = "hour12";
    public static final String K_SECONDS  = "seconds";
    public static final String K_BLINK    = "blink";
    public static final String K_FMT_IDX  = "fmtIdx";
    public static final String K_CUSTOM   = "custom";
    public static final String K_UNIT     = "unit";
    public static final String K_DOCK     = "dock";
    public static final String K_CATS     = "cats";
    public static final String K_MEMBERS  = "memberships";
    public static final String K_LIMIT    = "limit";
    public static final String K_HINT     = "hint";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String  palette()   { return sp.getString(K_PAL, PaletteResolver.AUTO); }
    public String  theme()     { return sp.getString(K_THEME, PaletteResolver.SYSTEM); }
    public boolean tint()      { return sp.getBoolean(K_TINT, false); }
    public boolean hour12()    { return sp.getBoolean(K_HOUR12, true); }
    public boolean seconds()   { return sp.getBoolean(K_SECONDS, false); }
    public boolean blink()     { return sp.getBoolean(K_BLINK, true); }
    public int     fmtIdx()    { return sp.getInt(K_FMT_IDX, 0); }
    public String  custom()    { return sp.getString(K_CUSTOM, ""); }
    public String  unit()      { return sp.getString(K_UNIT, "C"); }
    public int     limit()     { return sp.getInt(K_LIMIT, 240); }
    public boolean hintShown() { return sp.getBoolean(K_HINT, false); }

    /** Dock is stored as a newline-joined component list, max 5. */
    public List<String> dock() {
        String raw = sp.getString(K_DOCK, "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String s : raw.split("\n")) if (!s.isEmpty()) out.add(s);
        while (out.size() > 5) out.remove(out.size() - 1);
        return out;
    }

    public void setDock(List<String> components) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < components.size() && i < 5; i++) {
            if (i > 0) b.append('\n');
            b.append(components.get(i));
        }
        sp.edit().putString(K_DOCK, b.toString()).apply();
    }

    public List<String> categories() {
        String raw = sp.getString(K_CATS, "SOCIAL\nWORK\nMEDIA\nUTILITY");
        return new ArrayList<>(Arrays.asList(raw.split("\n")));
    }

    public void putString(String key, String value)  { sp.edit().putString(key, value).apply(); }
    public void putBool(String key, boolean value)   { sp.edit().putBoolean(key, value).apply(); }
    public void putInt(String key, int value)        { sp.edit().putInt(key, value).apply(); }
}
```

- [ ] **Step 4: Write `Insets`**

`app/src/main/java/com/retro/launcher/util/Insets.java`:

```java
package com.retro.launcher.util;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Horizontal system-gesture insets, in pixels. Swipes that begin inside these
 * strips belong to Android's Back gesture, not to panel navigation —
 * see DESIGN_NOTES §9 delta 12.
 *
 * Gesture insets did not exist before API 29 (the three-button era), so they
 * are zero there, which is the correct answer rather than a fallback.
 */
public final class Insets {

    private Insets() {}

    public static int[] gestureLeftRight(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WindowInsets wi = v.getRootWindowInsets();
            if (wi != null) {
                android.graphics.Insets g = wi.getSystemGestureInsets();
                return new int[]{ g.left, g.right };
            }
        }
        return new int[]{ 0, 0 };
    }
}
```

- [ ] **Step 5: Remove the obsolete drawer row layout**

```bash
git rm app/src/main/res/layout/row_app.xml
```

It described a plain text row for the old single-screen design. The drawer
row is rebuilt in Tier 2 with an icon, label and category line.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: manifest, transparent theme, prefs and gesture insets

Declares the three approved permissions, locks portrait, and keeps uiMode
out of configChanges-triggered recreation so a dark-mode switch does not
restart the render thread. Prefs mirrors the prototype's fourteen keys."
```

---

## Task 5: `LauncherRoot` — four panels and swipe navigation

**Files:**
- Create: `app/src/main/java/com/retro/launcher/ui/LauncherRoot.java`

**Interfaces:**
- Consumes: `Insets.gestureLeftRight(View)` from Task 4
- Produces:
  - `LauncherRoot.VIEW_HOME | VIEW_SETTINGS | VIEW_DRAWER | VIEW_TIME` (ints)
  - `void setPanels(View home, View settings, View drawer, View time)`
  - `void goTo(int view)` — animated
  - `int currentView()`
  - `static void setNoSwipe(View v)` — marks a subtree as opting out
  - `void setDoubleTapListener(Runnable)` — fires on a home-screen double tap

- [ ] **Step 1: Write the class**

`app/src/main/java/com/retro/launcher/ui/LauncherRoot.java`:

```java
package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
        settings.animate().translationX(next == VIEW_SETTINGS ? 0 : -w)
                .setDuration(SETTLE_MS).setInterpolator(settle).start();
        drawer.animate().translationX(next == VIEW_DRAWER ? 0 : w)
                .setDuration(SETTLE_MS).setInterpolator(settle).start();
        time.animate().translationY(next == VIEW_TIME ? 0 : h)
                .setDuration(SETTLE_MS).setInterpolator(settle).start();
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/retro/launcher/ui/LauncherRoot.java
git commit -m "feat(ui): LauncherRoot with four-panel swipe navigation

Ports the prototype's axis-lock, 1:1 tracking and 260ms settle. Declines
gestures starting in the system gesture inset so Android's Back keeps
working, and gestures over no-swipe subtrees so inner scrolling wins."
```

---

## Task 6: Tier 0 assembly — four blank panels on the device

**Files:**
- Rewrite: `app/src/main/java/com/retro/launcher/HomeActivity.java`
- Delete: `app/src/main/java/com/retro/launcher/AppEntry.java`
- Delete: `app/src/main/res/layout/activity_home.xml`

**Interfaces:**
- Consumes: `LauncherRoot` (Task 5), `Prefs` (Task 4),
  `PaletteResolver`/`Palettes` (Task 2), `Metrics` (Task 1)
- Produces: a running launcher

- [ ] **Step 1: Rewrite `HomeActivity`**

`app/src/main/java/com/retro/launcher/HomeActivity.java`:

```java
package com.retro.launcher;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.PaletteResolver;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.ui.LauncherRoot;

import java.util.Calendar;

public class HomeActivity extends Activity {

    private LauncherRoot root;
    private Prefs prefs;
    private Metrics metrics;
    private Palette palette;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            refreshPalette();
            ticker.postDelayed(this, 60_000L);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        goEdgeToEdge();

        prefs = new Prefs(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        metrics = new Metrics(dm.widthPixels, dm.density, dm.scaledDensity);

        root = new LauncherRoot(this);
        // Tier 0: blank colour-filled panels prove navigation before any
        // content exists. Each is replaced by its real panel in later tiers.
        root.setPanels(blank(Color.TRANSPARENT), blank(0xFF202020),
                       blank(0xFF303030), blank(0xFF404040));
        setContentView(root);

        refreshPalette();
    }

    private View blank(int color) {
        View v = new View(this);
        v.setBackgroundColor(color);
        return v;
    }

    private void goEdgeToEdge() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** Current time as a decimal hour, the unit every time-driven system uses. */
    private float decimalHour() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY)
                + c.get(Calendar.MINUTE) / 60f
                + c.get(Calendar.SECOND) / 3600f;
    }

    private boolean systemDark() {
        int mode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void refreshPalette() {
        Palette next = PaletteResolver.resolve(
                prefs.palette(), prefs.theme(), decimalHour(), systemDark());
        if (palette == null || !palette.id.equals(next.id) || palette.dark != next.dark) {
            palette = next;
            // Tier 1 hooks Tint and the sky renderer in here.
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ticker.post(minuteTick);
    }

    @Override protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(minuteTick);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        refreshPalette();
    }

    /** Back must never leave the home screen. */
    @Override public void onBackPressed() {
        if (root.currentView() != LauncherRoot.VIEW_HOME) {
            root.goTo(LauncherRoot.VIEW_HOME);
        }
    }
}
```

- [ ] **Step 2: Delete the obsolete scaffold files**

```bash
git rm app/src/main/java/com/retro/launcher/AppEntry.java
git rm app/src/main/res/layout/activity_home.xml
```

`AppEntry` returns in Tier 2 with a category field; the old layout described
a single full-screen `ListView`, which is the drawer, not the home screen.

- [ ] **Step 3: Push and let CI build the APK**

```bash
git add -A
git commit -m "feat: Tier 0 assembly — four blank panels, minute tick, edge-to-edge

Back returns to home rather than exiting. Palette re-resolves on the minute
and on a uiMode change. Panels are placeholder colours until Tier 1."
git push
```

Watch the workflow; confirm it reports an APK size.

- [ ] **Step 4: Install and verify on the device**

Download the release asset, install, and set Retro Launcher as the home app.
Check all of:

- Swiping right from home reveals the dark-grey settings panel; left reveals
  the mid-grey drawer; up reveals the lightest panel.
- Each settles smoothly rather than snapping.
- A short drag that does not cross the threshold springs back.
- A swipe started **at the very screen edge** performs Android's Back, not a
  panel change.
- Pressing Back on a panel returns home; pressing Back on home does nothing.
- Rotating the phone does not rotate the launcher.

- [ ] **Step 5: Tag the tier**

```bash
git tag tier-0 && git push --tags
```

---

# TIER 1 — Sky and home screen

## Task 7: `SkyRenderer` — gradient, glows and quantization

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/SkyRenderer.java`
- Test: `core/src/test/java/com/retro/launcher/core/SkyRendererTest.java`

**Interfaces:**
- Consumes: `SkyKeyframes.at`, `Bayer.bias` (Task 3)
- Produces:
  - `new SkyRenderer(int w, int h)` — allocates the seeded cloud/star/drop tables
  - `void render(int[] argb, float hour, float weather, float moonPhase, float seconds)`
  - `void setTint(int[] rampArgb)` / `void setTint(null)` — palette posterize
  - `void setDesaturation(float amount)` — the over-limit nag, 0 = off
  - Static helpers, all pure: `sunAlt(float hour)`, `smooth(float e0, float e1, float x)`,
    `clamp01(float)`

**Note on scope:** this task builds the base pass only — gradient, sun and moon
glow, haze, and Bayer quantization. Discs, stars, clouds and precipitation
follow in Tasks 8 and 9, so each has its own test cycle.

- [ ] **Step 1: Write the failing test**

`core/src/test/java/com/retro/launcher/core/SkyRendererTest.java`:

```java
package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SkyRendererTest {

    private static final int W = 108, H = 234;

    private int[] renderAt(float hour, float weather) {
        SkyRenderer r = new SkyRenderer(W, H);
        int[] buf = new int[W * H];
        r.render(buf, hour, weather, 0.62f, 0f);
        return buf;
    }

    @Test public void sunAltitudePeaksAtNoonAndBottomsAtMidnight() {
        assertEquals(1f,  SkyRenderer.sunAlt(12f), 0.001f);
        assertEquals(0f,  SkyRenderer.sunAlt(6f),  0.001f);
        assertEquals(-1f, SkyRenderer.sunAlt(0f),  0.001f);
        assertEquals(0f,  SkyRenderer.sunAlt(18f), 0.001f);
    }

    @Test public void smoothstepIsClampedAndMonotonic() {
        assertEquals(0f,   SkyRenderer.smooth(0f, 1f, -1f), 0.001f);
        assertEquals(1f,   SkyRenderer.smooth(0f, 1f, 2f),  0.001f);
        assertEquals(0.5f, SkyRenderer.smooth(0f, 1f, 0.5f), 0.001f);
        assertTrue(SkyRenderer.smooth(0.1f, 0.66f, 0.3f)
                 < SkyRenderer.smooth(0.1f, 0.66f, 0.5f));
    }

    @Test public void everyPixelIsWrittenAndFullyOpaque() {
        int[] buf = renderAt(12f, 0f);
        for (int i = 0; i < buf.length; i++) {
            assertEquals("alpha at " + i, 0xFF, (buf[i] >>> 24));
        }
    }

    @Test public void nightIsDarkerThanNoon() {
        assertTrue(meanLuma(renderAt(0f, 0f)) < meanLuma(renderAt(12f, 0f)));
    }

    @Test public void theGradientRunsTopToBottom() {
        // Sample columns away from the sun disc so the gradient dominates.
        int[] buf = renderAt(12f, 0f);
        assertNotEquals(luma(buf[2 * W + 4]), luma(buf[(H - 3) * W + 4]), 0.5f);
    }

    @Test public void quantizationSnapsToFifteenLevelSteps() {
        // The prototype rounds each channel to multiples of 15 before dither.
        // Every produced channel value must therefore be a multiple of 15,
        // clamped into range.
        int[] buf = renderAt(3f, 0f);
        for (int px : buf) {
            for (int shift : new int[]{16, 8, 0}) {
                int v = (px >> shift) & 0xFF;
                assertEquals("channel " + v + " is not a multiple of 15",
                        0, v % 15);
            }
        }
    }

    @Test public void aStormDarkensTheSky() {
        assertTrue(meanLuma(renderAt(12f, 1f)) < meanLuma(renderAt(12f, 0f)));
    }

    @Test public void renderIsDeterministicForTheSameInputs() {
        assertArrayEquals(renderAt(9.5f, 0.4f), renderAt(9.5f, 0.4f));
    }

    @Test public void desaturationPushesTowardGrey() {
        SkyRenderer r = new SkyRenderer(W, H);
        int[] colour = new int[W * H], grey = new int[W * H];
        r.render(colour, 12f, 0f, 0.62f, 0f);
        r.setDesaturation(1f);
        r.render(grey, 12f, 0f, 0.62f, 0f);
        assertTrue(spread(grey) < spread(colour));
    }

    private static float luma(int argb) {
        return ((argb >> 16) & 0xFF) * 0.299f
             + ((argb >> 8)  & 0xFF) * 0.587f
             + ( argb        & 0xFF) * 0.114f;
    }

    private static float meanLuma(int[] buf) {
        double sum = 0;
        for (int px : buf) sum += luma(px);
        return (float) (sum / buf.length);
    }

    /** Mean per-pixel channel spread — collapses toward zero as colour is lost. */
    private static float spread(int[] buf) {
        double sum = 0;
        for (int px : buf) {
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
            sum += Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        }
        return (float) (sum / buf.length);
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
gradle :core:test --no-daemon --tests '*SkyRendererTest*'
```

Expected: compilation failure — `cannot find symbol: class SkyRenderer`.

- [ ] **Step 3: Write the base pass**

`core/src/main/java/com/retro/launcher/core/SkyRenderer.java`:

```java
package com.retro.launcher.core;

/**
 * The prototype's frame() as a pure function. Writes ARGB into a caller-owned
 * int[] — deliberately never touches android.graphics.Bitmap, because a Bitmap
 * cannot be instantiated in a plain JUnit test and this class is the one that
 * most needs testing. SkyView owns the Bitmap and calls setPixels().
 *
 * See DESIGN_NOTES §2b for the layer list and the derivation of every scalar.
 */
public final class SkyRenderer {

    private final int w, h;
    private final float[] sky = new float[6];

    private int[] tintRamp;          // null unless "tint wallpaper to palette"
    private float desaturation;      // 0 = off; the over-limit nag

    public SkyRenderer(int w, int h) {
        this.w = w;
        this.h = h;
    }

    public void setTint(int[] rampArgb) { this.tintRamp = rampArgb; }

    public void setDesaturation(float amount) {
        this.desaturation = clamp01(amount);
    }

    public void render(int[] out, float hour, float weather,
                       float moonPhase, float seconds) {

        final float sunAlt   = sunAlt(hour);
        final float day      = clamp01(sunAlt * 3f + 0.35f);
        final float twilight = smooth(0.45f, 0.02f, Math.abs(sunAlt));
        final float storm    = smooth(0.55f, 1.00f, weather);
        final float haze     = smooth(0.06f, 0.24f, weather)
                             * (1f - smooth(0.30f, 0.50f, weather));

        SkyKeyframes.at(hour, sky);
        final float dark = 1f - 0.42f * storm;
        final float topR = sky[0] * dark, topG = sky[1] * dark, topB = sky[2] * dark;
        final float botR = sky[3] * dark, botG = sky[4] * dark, botB = sky[5] * dark;

        // Body positions — DESIGN_NOTES §2b.
        final float thSun  = (hour - 6f) / 12f * (float) Math.PI;
        final float thMoon = thSun + (float) Math.PI;
        final float travel = 0.3125f * h;
        final float sunX  = 72f - (float) Math.cos(thSun)  * 60f;
        final float moonX = 36f - (float) Math.cos(thMoon) * 60f;
        final float sunY  = 0.667f * h + (1f - (float) Math.sin(thSun))  * travel;
        final float moonY = 0.333f * h - (1f - (float) Math.sin(thMoon)) * travel;

        final float litFrac  = 1f - Math.abs(moonPhase - 0.5f) * 2f;
        final float glowSun  = (0.20f + 0.62f * twilight)
                             * clamp01(sunAlt + 0.55f) * (1f - 0.75f * storm);
        final float glowMoon = 0.26f * clamp01(-sunAlt + 0.25f)
                             * (1f - 0.75f * storm) * (0.15f + 0.85f * litFrac);

        for (int y = 0; y < h; y++) {
            float ty = (float) y / (h - 1);
            float m  = (float) Math.pow(ty, 0.85);   // not linear — see §2b
            float baseR = topR + (botR - topR) * m;
            float baseG = topG + (botG - topG) * m;
            float baseB = topB + (botB - topB) * m;

            for (int x = 0; x < w; x++) {
                float r = baseR, g = baseG, b = baseB;

                float dsx = x - sunX, dsy = y - sunY;
                float ds = (float) Math.sqrt(dsx * dsx + dsy * dsy);
                if (ds < 78f) {
                    float k = (float) Math.pow(1f - ds / 78f, 2.2) * glowSun;
                    r += (255f - r) * k; g += (150f - g) * k; b += (70f - b) * k;
                }

                float dmx = x - moonX, dmy = y - moonY;
                float dm = (float) Math.sqrt(dmx * dmx + dmy * dmy);
                if (dm < 46f) {
                    float k = (float) Math.pow(1f - dm / 46f, 2.4) * glowMoon;
                    r += (140f - r) * k; g += (165f - g) * k; b += (220f - b) * k;
                }

                if (haze > 0.01f) {
                    float k  = haze * 0.30f * (0.25f + ty);
                    float hz = 190f * (0.25f + 0.75f * day);
                    r += (hz - r) * k; g += (hz + 4f - g) * k; b += (hz + 16f - b) * k;
                }

                float d = Bayer.bias(x, y) * 16f;
                out[y * w + x] = pack(quantize(r + d), quantize(g + d), quantize(b + d));
            }
        }

        if (desaturation > 0f) applyDesaturation(out);
        if (tintRamp != null) applyTint(out);
    }

    /** Snap to 15-level steps — the source of the retro banding. */
    private static int quantize(float v) {
        int q = Math.round(v / 15f) * 15;
        return q < 0 ? 0 : (q > 255 ? 255 : q);
    }

    private static int pack(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void applyDesaturation(int[] out) {
        float k = desaturation;
        for (int i = 0; i < out.length; i++) {
            int px = out[i];
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
            int l = Math.round(r * 0.299f + g * 0.587f + b * 0.114f);
            out[i] = pack(Math.round(r + (l - r) * k),
                          Math.round(g + (l - g) * k),
                          Math.round(b + (l - b) * k));
        }
    }

    /** Posterize to the palette's luminance-sorted ramp with ordered dither. */
    private void applyTint(int[] out) {
        int n = tintRamp.length;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x, px = out[i];
                float lum = (((px >> 16) & 0xFF) * 0.299f
                          +  ((px >> 8)  & 0xFF) * 0.587f
                          +  ( px        & 0xFF) * 0.114f) / 255f;
                lum += Bayer.bias(x, y) / n;
                int idx = (int) (lum * n);
                out[i] = tintRamp[idx < 0 ? 0 : (idx >= n ? n - 1 : idx)];
            }
        }
    }

    public static float sunAlt(float hour) {
        return (float) Math.sin((hour - 6f) / 12f * Math.PI);
    }

    public static float smooth(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
gradle :core:test --no-daemon
```

Expected: `BUILD SUCCESSFUL`, 32 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -m "feat(core): sky renderer base pass

Gradient with the pow(ty,0.85) curve, sun and moon glow, haze, storm
darkening, 15-level Bayer quantization, palette tint and the over-limit
desaturation hook. Pure function into a caller-owned int[] so it tests on
a bare JDK. Discs, stars, clouds and precipitation follow."
```

---

## Task 8: Sun and moon discs

**Files:**
- Modify: `core/src/main/java/com/retro/launcher/core/SkyRenderer.java`
- Modify: `core/src/test/java/com/retro/launcher/core/SkyRendererTest.java`

**Interfaces:**
- Consumes: everything from Task 7
- Produces: no new public API — `render` gains two layers. The internal
  `px(int[] out, float x, float y, int r, int g, int b, float alpha)` blend
  helper is introduced here and reused by Task 9.

Port, in this order, from `DESIGN_NOTES` §2b:

1. **Moon** (drawn before the sun, matching the prototype): radius 12, phase
   terminator `cos(2πq)·√(1−ny²)` where `q = ph ≤ 0.5 ? ph : 1−ph` and
   `sx = ph ≤ 0.5 ? 1 : −1`; unlit side blended at alpha 0.55; eight craters
   from the `CRATERS` table with lit rims; Bayer-jittered edge.
2. **Sun** — radius 13, three tone bands selected by a Bayer-jittered distance,
   colours lerped from horizon reds `[214,70,46] [255,128,56] [255,180,96]`
   to noon whites `[255,182,44] [255,226,120] [255,250,214]` by
   `clamp01(sunAlt·1.9 + 0.25)`; eight rays whose length pulses on
   `sin(T·1.6)`.

New tests to add:

- `sunDiscAppearsInTheSkyDuringDay` — a bright cluster exists near
  `(sunX, sunY)` at hour 12 that is absent at hour 0.
- `moonDiscAppearsAtNight` — likewise near `(moonX, moonY)` at hour 0.
- `fullMoonIsBrighterThanNewMoon` — mean luma in the moon's bounding box is
  higher at `moonPhase = 0.5` than at `moonPhase = 0.0`.
- `discsClipAtTheBufferEdgeWithoutCrashing` — render across all 24 hours in
  0.25 steps and assert no exception and full alpha coverage.
- `renderStaysDeterministic` — re-assert `assertArrayEquals` now that two more
  layers contribute.

Then: run, confirm pass, commit.

---

## Task 9: Stars, clouds, precipitation and lightning

**Files:**
- Modify: `core/src/main/java/com/retro/launcher/core/SkyRenderer.java`
- Modify: `core/src/test/java/com/retro/launcher/core/SkyRendererTest.java`

**Interfaces:**
- Consumes: the `px` helper from Task 8
- Produces: `SkyRenderer(int w, int h, long seed)` — a second constructor
  taking an explicit seed. **The single-argument constructor keeps seed 1337**,
  the prototype's value, so existing tests are unaffected.

The prototype seeds its cloud, star and drop tables from an LCG
(`seed = (seed·1103515245 + 12345) & 0x7fffffff`) initialised to 1337. Port
that generator exactly — the specific cloud shapes are part of the design.

Lightning uses `Math.random()` in the prototype. Replace it with a
`java.util.Random` field seeded from the same value so frames stay
reproducible under test.

Layers, per `DESIGN_NOTES` §2b: 130 stars (night only, twinkling, killed by
cover), 14 cloud clusters, 260 precipitation particles with a rain and a snow
branch, and the lightning bolt plus decaying full-frame flash.

New tests:

- `starsOnlyAppearAtNight` — bright single pixels in the upper region at
  hour 0 with `weather = 0`, none at hour 12.
- `cloudCoverIncreasesWithWeather` — count of pixels close to the cloud base
  tone rises monotonically across `weather` 0.1 → 0.5 → 0.9.
- `rainOnlyFallsAboveThePrecipThreshold` — no precipitation pixels at
  `weather = 0.5`, some at `weather = 0.9`.
- `sameSeedGivesTheSameFrame` — two renderers with seed 99 produce identical
  buffers for identical inputs.
- `differentSeedsGiveDifferentClouds` — seeds 1 and 2 differ.
- `renderNeverThrowsAcrossTheWholeDay` — 0 → 24 in 0.1 steps × weather
  0 → 1 in 0.25 steps.

Then: run, confirm pass, commit.

---

## Task 10: `SkyView` — SurfaceView and the render thread

**Files:**
- Create: `app/src/main/java/com/retro/launcher/sky/SkyView.java`
- Modify: `app/src/main/java/com/retro/launcher/HomeActivity.java`

**Interfaces:**
- Consumes: `SkyRenderer` (Tasks 7–9)
- Produces:
  - `SkyView(Context)` — a `SurfaceView`
  - `void setWeather(float w)`, `void setTint(int[] ramp)`,
    `void setDesaturation(float)`
  - `void pause()` / `void resume()`

Behaviour to implement:

- Buffer size `W = 108`, `H = clamp(round(108·viewH/viewW), 96, 320)`,
  recomputed in `surfaceChanged`.
- One `Thread` looping at **30fps** (`33ms` budget, sleeping the remainder).
  It calls `renderer.render(buf, hour, weather, moonPhase, t)`, then
  `bitmap.setPixels(buf, 0, W, 0, 0, W, H)`, then
  `canvas.drawBitmap(bitmap, srcRect, dstRect, paint)` with a `Paint` whose
  `isFilterBitmap` is **false** — nearest-neighbour is the aesthetic.
- Started in `surfaceCreated`, stopped by a volatile flag in
  `surfaceDestroyed`, and gated by `pause()`/`resume()` from the Activity so
  it never runs while another app is foreground.
- `hour` comes from a `Calendar` read once per frame — cheap, and it keeps the
  sky honest across midnight without a separate tick.

Wire into `HomeActivity`: construct a `SkyView`, add it to `LauncherRoot` as
the first child so it sits behind the panels, and call `pause()`/`resume()`
from the Activity's own lifecycle callbacks.

**Verification is on-device, not unit tests** — the renderer's logic is already
covered, and what remains is threading and surface lifecycle. Install and
confirm: the sky animates, it visibly differs morning vs. night, and
backgrounding the launcher stops the thread (check with
`adb shell dumpsys cpuinfo` or simply that the device stays cool and battery
use is unremarkable).

Commit.

---

## Task 11: `DateFormatter`

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/DateFormatter.java`
- Test: `core/src/test/java/com/retro/launcher/core/DateFormatterTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `DateFormatter.PRESETS` — `String[]` of the five preset format strings
  - `DateFormatter.TOKENS` — `String[]` of the fifteen builder chips
  - `DateFormatter.format(String pattern, int year, int month0, int dayOfMonth, int dayOfWeek0)` → `String`

Ports the prototype's `fmtDate`. Token precedence is **longest-first** and
matters: `DOY, DDDD, DDD, DD, D, MMMM, MMM, MM, YYYY, YY, WK`. Matching `DD`
before `DDD` would corrupt every weekday.

- `DOY` → `"DAY " + dayOfYear`; `WK` → `"WK " + ceil(dayOfYear / 7)`
- `DDDD`/`DDD` → `SUNDAY…` / `SUN…`; `MMMM`/`MMM` → `JANUARY…` / `JAN…`
- `DD`, `MM` zero-padded; `D` unpadded; `YYYY` full; `YY` last two
- Anything else passes through literally

Tests must cover: each of the five presets on a known date; every token
individually; day-of-year on 1 Jan and 31 Dec of both a leap and a common
year; week number at both ends; a custom pattern mixing tokens and literals;
and an empty pattern returning an empty string rather than throwing.

Then: write the failing test, run it, implement, run, commit.

---

## Task 12: `Tint` and `ClockWidget`

**Files:**
- Create: `app/src/main/java/com/retro/launcher/theme/Tint.java`
- Create: `app/src/main/java/com/retro/launcher/ui/ClockWidget.java`
- Create: `app/src/main/res/layout/widget_clock.xml`

**Interfaces:**
- Consumes: `Palette` (Task 2), `Metrics` (Task 1), `DateFormatter` (Task 11),
  `Prefs` (Task 4)
- Produces:
  - `Tint.apply(View root, Palette p)` — walks a tree, repainting by role tag
  - `Tint.setRole(View v, int role)` with
    `Tint.ROLE_INK | ROLE_P | ROLE_A | ROLE_S | ROLE_H | ROLE_TILE | ROLE_BG | ROLE_VEIL`
  - `ClockWidget.setPalette(Palette)`, `.setTime(Calendar)`,
    `.setWeather(Weather)`, `.setOnTimeTap/OnDateTap/OnWeatherTap(Runnable)`

`Tint` reads an integer role tag off each view and applies the matching palette
colour — text colour for text views, the stroke of a `GradientDrawable` for
bordered containers, and `veil()` for panel backgrounds. Views without a role
tag are skipped, so the walk is cheap.

`ClockWidget` reproduces `DESIGN_NOTES` §7a: `veil` background, `0.7cqw`
primary border, three stacked lines at `9.4 / 3.4 / 3.4 cqw` via
`Metrics.textPx`. The colon blinks on a 1-second handler when
`Prefs.blink()`. **Three independent tap regions**, per §9 delta 8:

| Region | Intent |
|---|---|
| Time | `new Intent(AlarmClock.ACTION_SHOW_ALARMS)` |
| Date | `new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)` |
| Weather | best-effort package query; no-op when nothing resolves |

Every `startActivity` is wrapped in a `try/catch (ActivityNotFoundException)`
that does nothing — a missing clock app must not crash the home screen.

Commit.

---

## Task 13: Synthetic weather

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/Weather.java`
- Create: `core/src/main/java/com/retro/launcher/core/SyntheticWeather.java`
- Test: `core/src/test/java/com/retro/launcher/core/SyntheticWeatherTest.java`
- Create: `app/src/main/java/com/retro/launcher/data/WeatherRepository.java`

**Interfaces:**
- Consumes: `SkyRenderer.sunAlt`, `.smooth`, `.clamp01` (Task 7)
- Produces:
  - `Weather(int tempC, String label, float w)` — immutable, with
    `int tempIn(String unit)` converting to F on demand
  - `SyntheticWeather.at(float hour, float w, boolean snow)` → `Weather`
  - `SyntheticWeather.label(float w, boolean snow)` → `String`
  - `WeatherRepository.current()` → `Weather` (never null)

Ports the prototype's `tempC()` and `weatherName()` — `DESIGN_NOTES` §9
delta 1:

```
rain: round(17 + 9·sunAlt − 5·cover − 4·precip)
snow: round(−2 − 6·precip − 3·cover + 4·clamp01(sunAlt))
```

with the ten condition bands at `0.07 / 0.18 / 0.30 / 0.42 / 0.54 / 0.64 /
0.76 / 0.87 / 0.95`, each having a snow variant.

Tests: every band boundary on both branches; noon warmer than midnight;
a storm colder than clear at the same hour; `°F` conversion at 0 °C and
100 °C; and that `w` is always returned in `[0,1]`.

`WeatherRepository` is Tier 1's thin wrapper — it returns `SyntheticWeather`
output and holds the seam that Tier 5's `OpenMeteoWeather` slots into.

Then: write the failing test, run it, implement, run, commit.

---

## Task 14: `DockView` and first-run seeding

**Files:**
- Create: `app/src/main/java/com/retro/launcher/ui/DockView.java`
- Create: `app/src/main/java/com/retro/launcher/data/DefaultDock.java`

**Interfaces:**
- Consumes: `Prefs` (Task 4), `Palette`, `Metrics`
- Produces:
  - `DockView.setPalette(Palette)`, `.setEntries(List<String> components)`
  - `DefaultDock.seed(PackageManager)` → `List<String>` of
    `"package/activity"` strings

`DefaultDock` resolves the prototype's three defaults to whatever this phone
actually uses — `DESIGN_NOTES` §9 delta 6:

| Slot | Resolution |
|---|---|
| Phone | `Intent.ACTION_DIAL` |
| Messages | `Intent(ACTION_MAIN).addCategory(CATEGORY_APP_MESSAGING)` |
| Camera | `MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA` |

A slot that resolves to nothing is simply omitted — a tablet without a dialer
gets a two-app dock, not a crash. Runs once, on first launch only, and the
result is written to `Prefs.setDock`.

`DockView` reproduces §7a: `veil` background, `0.7cqw` border, up to five
`13cqw` slots with `2.5cqw` captions, `2.6cqw` gaps, and a trailing dashed
`+` slot when under five. **Tap launches the app** (delta 7) with
`FLAG_ACTIVITY_NEW_TASK`, wrapped in the same `ActivityNotFoundException`
guard. Long-press is stubbed to a no-op with a `TODO(tier-2)` comment — the
picker sheet it opens does not exist until Tier 2.

Icons in this tier are plain letter tiles drawn directly by `DockView`; the
real `IconSource` seam arrives with the gate in Tier 2.

Commit.

---

## Task 15: Tier 1 assembly

**Files:**
- Create: `app/src/main/java/com/retro/launcher/ui/HomePanel.java`
- Modify: `app/src/main/java/com/retro/launcher/HomeActivity.java`

**Interfaces:**
- Consumes: everything from Tasks 7–14
- Produces: a launcher worth using

- [ ] **Step 1: Build `HomePanel`**

A `FrameLayout` holding `ClockWidget` at top-right and `DockView` at
bottom-left, both offset `4cqw` and inset by `WindowInsets` so neither lands
under a notch or the gesture bar. The wallpaper stays edge-to-edge behind
them.

- [ ] **Step 2: Wire it into `HomeActivity`**

Replace the Tier 0 blank home panel with `HomePanel`. Feed the minute tick
into `ClockWidget.setTime`, and route `refreshPalette()` into
`Tint.apply(homePanel, palette)`, `ClockWidget.setPalette` and
`DockView.setPalette`. Point `LauncherRoot.setDoubleTapListener` at a stub
that logs — `SearchOverlay` proper is Tier 5.

- [ ] **Step 3: Run the full test suite**

```bash
gradle :core:test --no-daemon
```

Expected: `BUILD SUCCESSFUL`, all tests passing.

- [ ] **Step 4: Push, install, and verify on the device**

- The sky animates and looks right at several times of day. Force a few by
  changing the phone's clock.
- Palette shifts at the §2a boundaries; setting the phone to 04:30 then 04:40
  visibly changes it.
- The clock ticks, the colon blinks, the date reads correctly.
- Tapping the time opens the clock app; the date opens the calendar.
- Dock icons launch their apps.
- Backgrounding the launcher stops the render thread.
- Panel swipes still work with the widget and dock present, and dragging
  *from* the widget or dock does not start a panel swipe.

- [ ] **Step 5: Tag the tier**

```bash
git add -A
git commit -m "feat: Tier 1 assembly — animated sky, clock widget, dock

The launcher is now usable as a daily home screen."
git tag tier-1 && git push --tags
```

---

# TIERS 2–5 — decomposition

Detailed step-level plans are written per tier, at each tier boundary. The
decomposition below fixes scope, files and interfaces so nothing is lost.

## Tier 2 — App drawer ⟵ **DECISION GATE**

| Task | Files | Tests |
|---|---|---|
| Category mapping | `core/CategoryMap.java` | All nine `ApplicationInfo.category` values → tab name; unknown → `UTILITY` |
| App repository | `data/AppEntry.java`, `data/AppRepository.java` | Sort stability; case-insensitive ordering; user overrides beat auto-categories |
| Pixel tile geometry | `icons/PixelTile.java` | `TILE_SPAN` silhouette; 12×12 glyph composited at (+2,+2); run-length encoding |
| Icon seam | `icons/IconSource.java`, `GeneratedTileIcons.java`, `PosterizedIcons.java`, `IconCache.java` | Posterize output uses only ramp colours; cache evicts on palette change |
| Drawer UI | `ui/DrawerPanel.java`, `ui/AlphaScrubber.java`, `res/layout/row_app.xml` | On-device |
| Sheet | `ui/BottomSheet.java` | On-device |
| Long-press routing | — | App row → App Info; tab strip → categories (delta 4) |

**Gate:** build both `IconSource` implementations behind a debug toggle with
frame-time and scroll instrumentation, ship that APK, and **stop**. The owner
picks on measured evidence. The loser is deleted, not left as dead code.

## Tier 3 — Settings

Panel with the four sections of `DESIGN_NOTES` §7c: palette grid (AUTO + 5
cards with colour chips), light/dark/system, tint toggle, the three clock
toggles, six date-format rows plus the 15-chip custom builder, °C/°F, the dock
editor, and a permissions block. New views: `ui/SettingsPanel.java`,
`ui/PixelToggle.java`. Every control writes through `Prefs` immediately, as
the prototype's `save()` does. Unit tests cover the custom-format builder's
append and clear behaviour; the rest is on-device.

## Tier 4 — Screen time

`core/UsageMath.java` (daily totals, 7-day window, limit state) is unit-tested
across a midnight boundary and a DST shift. `data/UsageRepository.java` wraps
`UsageStatsManager`. `ui/ScreenTimePanel.java`, `ui/LimitSlider.java`
(30–600 snapped to 15) and `ui/WeekChart.java` render §7d.
`ui/HintOverlay.java` and `ui/SetupScreen.java` ship here — the first-run flow,
including the `PACKAGE_USAGE_STATS` grant via `ACTION_USAGE_ACCESS_SETTINGS`.
The over-limit nag drives `SkyView.setDesaturation` and a widget marker
(delta 10).

## Tier 5 — Real weather and polish

`core/WeatherParser.java` parses Open-Meteo's response with
`android.util.JsonReader`— unit-tested against a recorded payload and against
every malformed shape, each of which must yield "no update" rather than throw.
`data/OpenMeteoWeather.java` performs the fetch on a background thread behind
the existing `WeatherSource` seam; `WeatherRepository` gains the 30-minute
cache and 10-minute floor. `SetupScreen` gains its location row.
`ui/SearchOverlay.java` replaces the Tier 1 double-tap stub.

---

## Self-review

**Spec coverage.** Every §8 tier item maps to a task: Tier 0 → Tasks 1–6,
Tier 1 → Tasks 7–15, Tiers 2–5 → the decomposition above. Spec §3.1
(`Metrics`) → Task 1. §3.2 (`SkyRenderer`, `SkyView`) → Tasks 7–10. §3.3
(`PaletteResolver`) → Task 2. §3.4 (`IconSource`) → Tier 2. §3.5
(`AppRepository`) → Tier 2. §3.6 (`WeatherRepository`) → Task 13 and Tier 5.
§5 (permissions) → Task 4 declares them, Tier 4 requests them. §6 error
handling → the `ActivityNotFoundException` guards in Tasks 12 and 14, the
`<queries>` block in Task 4, `Prefs`' independent defaults in Task 4, and the
surface-destroyed flag in Task 10. §7 testing → the `:core` module in Task 1.

**Known gap, deliberate:** spec §6 lists "`queryIntentActivities` returns
empty → show a diagnostic row." There is no app list until Tier 2, so that
check belongs to Tier 2's `AppRepository` task and is recorded there.

**Placeholder scan.** No "TBD", no "add error handling", no "similar to Task
N". Tasks 8, 9 and 11–15 give ports, algorithms, exact constants and test
lists rather than literal code — a deliberate density choice for work that is
transcription from a source already quoted in `DESIGN_NOTES` §2b, not novel
logic. Tiers 2–5 are scoped rather than stepped, and say so.

**Type consistency.** `Palette` field names (`bg tile p a s h ink`) are
identical in Tasks 2, 7, 12 and 14. `SkyRenderer.render(int[], float, float,
float, float)` has the same signature in Tasks 7, 8, 9 and 10.
`PaletteResolver.resolve(String, String, float, boolean)` matches between
Tasks 2 and 6. `Prefs` getter names match their use in Tasks 6, 12 and 14.
`Metrics.textPx(float, float)` matches between Tasks 1 and 12.
`LauncherRoot.setNoSwipe` is defined in Task 5 and consumed in Tier 2.
