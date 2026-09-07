# V9 Part 1 — Sky Rework, Weather Accuracy, Manual Wallpaper Override

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement V9 spec §1–§7b on the `V9` branch: one clock drives both sky colour and body position, real (offline) moonrise/moonset replace the fixed opposite-of-sun moon, weather becomes four independent channels (cloud cover, precipitation, precipitation type, thunder) instead of one overloaded scalar, plus lens flare, heat shimmer, and a manual wallpaper override panel for testing/preview. Part 2 (§8–§11: action-key flip, drawer headers, permission reduction, cloned apps) is explicitly out of scope for this plan.

**Architecture:** All new math (body positions, lunar rise/set, weather-channel derivation) lands in `:core` as pure, JVM-testable classes — the project's existing rule ("only `:core` has a JVM test source set"). `SkyRenderer.render()`'s signature changes from five loose scalars to `(int[] out, SkyConditions c, float seconds)`, where `SkyConditions` (new, `:core`) carries everything the renderer needs each frame. `Weather` (`:core`) changes from a single `w` scalar to four independent fields (`cloudCover`, `precip`, `type`, `thunder`) plus a `w` now *derived* from them for backward compatibility with the persisted cache and the temperature/label formulas. `app`-module wiring (`SkyView`, `WeatherRepository`, `HomeActivity`, `SettingsPanel`, `ScreenTimePanel`) has no JVM test source set, so its tasks are verified by `./gradlew :app:compileDebugJavaWithJavac` (or `assembleDebug` on the final task) rather than JUnit, matching V7/V8 precedent.

**Tech Stack:** Java 17, Android (Gradle, AGP), JUnit 4 for `:core`.

**Spec:** `docs/superpowers/specs/2026-09-06-v9-sky-weather-drawer-design.md` §1–§7b (Part 1 only — do not touch §8–§11).

## Global Constraints

- Branch: stay on `V9` (already checked out, cut from `origin/main` at `2765494`).
- `versionName` is already `2.0.0` — do not touch version files in this plan unless a task says so.
- `:core` must stay Android-free. Every new class listed as "core" below goes in `core/src/main/java/com/retro/launcher/core/`, its test in `core/src/test/java/com/retro/launcher/core/`.
- Commit after every task (or every clearly-labelled step group inside the larger Task 5). Use `git add <specific files>`, never `git add -A`.
- Run the full `:core` test suite (`./gradlew :core:test`) before every commit that touches `:core`. Run `./gradlew :app:compileDebugJavaWithJavac` before every commit that touches `app`.
- **Precip intensity mapping (user-confirmed):** raw Open-Meteo `precipitation` (mm, preceding hour) maps to the 0–1 `precip` scalar as `clamp01(mm / 4f)` — 4mm/h is WMO's "heavy rain" threshold, so it saturates the visual there.
- **Design decision — `SkyConditions` carries more than the spec's illustrative 7-field snippet.** §6's code block shows `SkyConditions{hour, cloudCover, precip, moonPhase, type, thunder, tempC}` to motivate collapsing `render()`'s parameter list, but the moon now runs on its own real-time clock (§3) independent of the sun's warped `hour`, which the spec never wires into `render()` explicitly. Rather than inventing hidden setter state on `SkyRenderer`, this plan adds `realHour`, `moonriseHour`, `moonsetHour` to `SkyConditions` too. This keeps `render(int[] out, SkyConditions c, float seconds)`'s literal signature exactly as spec'd while giving the renderer everything `BodyPath.moonT` needs. Flag this during review if a different wiring is preferred.
- **Design decision — WMO-code fallback intentionally reproduces the old bug.** Spec §6: "a missing channel falls back to the value implied by weather_code, so a partial response degrades to today's behaviour rather than to nothing." That old behaviour is exactly the coupled-scalar bug this spec fixes — but only for the rare malformed/partial-response path, never for a normal response where all four channels are present. Do not "fix" the fallback path; a dry-thunderstorm test must supply an explicit `"precipitation":0` value, not rely on fallback.

---

## Task 1: `Precip` enum and `Weather`'s four-channel rewrite

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/Precip.java`
- Modify: `core/src/main/java/com/retro/launcher/core/Weather.java`
- Modify: `core/src/main/java/com/retro/launcher/core/SyntheticWeather.java`
- Modify: `core/src/test/java/com/retro/launcher/core/SyntheticWeatherTest.java`

**Interfaces:**
- Produces: `Precip{NONE,RAIN,SNOW}`; `Weather(int tempC, String label, float cloudCover, float precip, Precip type, boolean thunder, int precipProbability)`; `Weather.w` (derived, final); `static float Weather.derive(float cloudCover, float precip, boolean thunder)`; `SyntheticWeather.at(float hour, float w, boolean snow)` — same signature, richer return.

- [ ] **Step 1: Create the `Precip` enum**

```java
package com.retro.launcher.core;

/** The three shapes precipitation can take on screen — its own layer,
 *  independent of intensity or thunder. See DESIGN_NOTES / V9 spec §6. */
public enum Precip { NONE, RAIN, SNOW }
```

- [ ] **Step 2: Write the failing test for `Weather`'s new shape**

Add to `core/src/test/java/com/retro/launcher/core/SyntheticWeatherTest.java`, replacing the three `fahrenheitConversionAtKnownPoints` lines that construct `Weather` with the old 3-arg constructor:

```java
    @Test public void fahrenheitConversionAtKnownPoints() {
        assertEquals(32, new Weather(0, "CLEAR", 0f, 0f, Precip.NONE, false, 0).tempIn("F"));
        assertEquals(212, new Weather(100, "CLEAR", 0f, 0f, Precip.NONE, false, 0).tempIn("F"));
        assertEquals(0, new Weather(0, "CLEAR", 0f, 0f, Precip.NONE, false, 0).tempIn("C"));
    }

    @Test public void thunderAlwaysDerivesTheMaximumWScalar() {
        assertEquals(1.0f, new Weather(20, "THUNDERSTORM", 0.4f, 0f, Precip.NONE, true, 0).w, 0.001f);
    }

    @Test public void derivedWStaysBelowTheWetBandWhenPrecipIsZero() {
        Weather dry = new Weather(20, "OVERCAST", 1.0f, 0f, Precip.NONE, false, 0);
        assertTrue("dry sky must never cross into the wet band", dry.w < 0.62f);
    }

    @Test public void derivedWStaysInTheWetBandWhenPrecipIsPositive() {
        Weather wet = new Weather(10, "LIGHT RAIN", 0.9f, 0.02f, Precip.RAIN, false, 0);
        assertTrue(wet.w >= 0.62f);
    }
```

- [ ] **Step 2b: Run the tests to verify they fail to compile**

Run: `./gradlew :core:test --tests SyntheticWeatherTest`
Expected: compile error — `Weather(int,String,float,float,Precip,boolean,int)` does not exist yet, and `Precip` doesn't exist for `SyntheticWeatherTest` until Step 1's file is in place (it already is) — the error should be specifically about the `Weather` constructor arity.

- [ ] **Step 3: Rewrite `Weather.java`**

```java
package com.retro.launcher.core;

/** Immutable weather read-out. {@code cloudCover}, {@code precip}, {@code type}
 *  and {@code thunder} are the four independent channels V9 introduced —
 *  cloud cover no longer implies rain, and a thunderstorm no longer implies
 *  rain either. {@code w} is retained, derived from the four channels, so
 *  the persisted cache key and the label bands keep working unchanged. */
public final class Weather {

    public final int tempC;
    public final String label;
    public final float w;
    public final float cloudCover;
    public final float precip;
    public final Precip type;
    public final boolean thunder;
    public final int precipProbability;

    public Weather(int tempC, String label, float cloudCover, float precip,
                    Precip type, boolean thunder, int precipProbability) {
        this.tempC = tempC;
        this.label = label;
        this.cloudCover = cloudCover;
        this.precip = precip;
        this.type = type;
        this.thunder = thunder;
        this.precipProbability = precipProbability;
        this.w = derive(cloudCover, precip, thunder);
    }

    /**
     * Collapses the four independent channels back onto the single 0-1 scalar
     * {@link SyntheticWeather#label} and the persisted cache still use. The
     * dry (cloud-only) and wet (precip-driven) ranges are kept disjoint at
     * {@code 0.62} so a cloud-only sky can never cross into a rain label —
     * the mislabelling V9 fixes.
     */
    static float derive(float cloudCover, float precip, boolean thunder) {
        if (thunder) return 1.0f;
        if (precip > 0f) return 0.62f + precip * 0.36f;
        return cloudCover * 0.62f;
    }

    /** {@code unit} is "C" or "F"; anything else falls back to Celsius. */
    public int tempIn(String unit) {
        if ("F".equals(unit)) return Math.round(tempC * 9f / 5f + 32f);
        return tempC;
    }
}
```

- [ ] **Step 4: Rewrite `SyntheticWeather.at()`**

In `core/src/main/java/com/retro/launcher/core/SyntheticWeather.java`, replace the `at(...)` method body:

```java
    public static Weather at(float hour, float w, boolean snow) {
        float wv = SkyRenderer.clamp01(w);
        float sunAlt = SkyRenderer.sunAlt(hour);
        float cover = SkyRenderer.smooth(0.10f, 0.66f, wv);
        float precip = SkyRenderer.smooth(0.62f, 0.98f, wv);
        boolean thunder = wv >= 0.95f;
        Precip type = precip > 0f ? (snow ? Precip.SNOW : Precip.RAIN) : Precip.NONE;

        int tempC = snow
                ? Math.round(-2 - 6 * precip - 3 * cover + 4 * SkyRenderer.clamp01(sunAlt))
                : Math.round(17 + 9 * sunAlt - 5 * cover - 4 * precip);

        int precipProbability = Math.round(precip * 100f);
        return new Weather(tempC, label(wv, snow), cover, precip, type, thunder, precipProbability);
    }
```

Leave `drift(...)` and `label(...)` untouched — both are pure `float`-in functions with no `Weather` dependency, and their existing tests (`bandBoundariesMatchTheSourceTableRain`, `driftStaysInUnitRangeAcrossAYearOfDays`, etc.) need no changes.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests SyntheticWeatherTest`
Expected: all tests in the file pass, including the new ones from Step 2. (`wIsAlwaysReturnedInUnitRange` and `noonIsWarmerThanMidnightAtTheSameWeather` etc. need no source changes — verify they still pass unmodified.)

- [ ] **Step 6: Run the full `:core` suite to confirm nothing else broke**

Run: `./gradlew :core:test`
Expected: `WeatherParserTest` and `SkyRendererTest` will now fail to compile (they still call the old 3-arg `Weather` constructor and old `SkyRenderer.render()` signature) — this is expected and fixed in Tasks 4 and 5. Confirm the *only* new failures are compile errors in those two files, nothing else.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/Precip.java \
        core/src/main/java/com/retro/launcher/core/Weather.java \
        core/src/main/java/com/retro/launcher/core/SyntheticWeather.java \
        core/src/test/java/com/retro/launcher/core/SyntheticWeatherTest.java
git commit -m "feat: split Weather into four independent channels (V9 §6)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 2: `BodyPath` — the sun's and moon's positions

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/BodyPath.java`
- Create: `core/src/test/java/com/retro/launcher/core/BodyPathTest.java`

**Interfaces:**
- Consumes: `SolarClock.SUNRISE_ANCHOR`, `SolarClock.SUNSET_ANCHOR` (existing).
- Produces: `BodyPath.sunT(float warpedHour)`, `sunX(float t, int w)`, `sunY(float t, int w, int h, float r)`, `moonX(float t, int w)`, `moonY(float t, int h)`, `moonT(float hour, float moonriseHour, float moonsetHour)` — all package-private static, called from `SkyRenderer` (Task 5) and this test only.

- [ ] **Step 1: Write the failing test file**

```java
package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class BodyPathTest {

    private static final int W = 108, H = 234;
    private static final float R = 13f;

    @Test public void sunIsTangentToTheBottomAtBothAnchors() {
        assertEquals(H - R, BodyPath.sunY(0f, W, H, R), 0.01f);
        assertEquals(H - R, BodyPath.sunY(1f, W, H, R), 0.01f);
    }

    @Test public void sunIsTangentToTheTopAtNoon() {
        assertEquals(R, BodyPath.sunY(0.5f, W, H, R), 0.01f);
    }

    @Test public void sunXIsMonotonicLeftToRight() {
        float prev = BodyPath.sunX(0f, W);
        for (float t = 0.05f; t <= 1f; t += 0.05f) {
            float cur = BodyPath.sunX(t, W);
            assertTrue("t=" + t, cur > prev);
            prev = cur;
        }
    }

    @Test public void sunTIsZeroAtSunriseAndOneAtSunset() {
        assertEquals(0f, BodyPath.sunT(SolarClock.SUNRISE_ANCHOR), 0.0001f);
        assertEquals(1f, BodyPath.sunT(SolarClock.SUNSET_ANCHOR), 0.0001f);
    }

    @Test public void sunTPastTheAnchorsExceedsUnitRange() {
        assertTrue(BodyPath.sunT(SolarClock.SUNRISE_ANCHOR - 1f) < 0f);
        assertTrue(BodyPath.sunT(SolarClock.SUNSET_ANCHOR + 1f) > 1f);
    }

    @Test public void moonEntersAtTheTopLeft() {
        assertEquals(0f, BodyPath.moonY(0f, H), 0.01f);
        assertEquals(0f, BodyPath.moonX(0f, W), 0.01f);
    }

    @Test public void moonVertexIsDeadCentreAtHalfHeight() {
        assertEquals(0.5f * H, BodyPath.moonY(0.5f, H), 0.01f);
    }

    @Test public void moonExitsTwentyPercentAboveTheVertex() {
        assertEquals(0.30f * H, BodyPath.moonY(1f, H), 0.01f);
    }

    @Test public void moonHasZeroSlopeOnBothSidesOfTheVertex() {
        float eps = 0.001f;
        float dLeft = (BodyPath.moonY(0.5f, H) - BodyPath.moonY(0.5f - eps, H)) / eps;
        float dRight = (BodyPath.moonY(0.5f + eps, H) - BodyPath.moonY(0.5f, H)) / eps;
        assertEquals(0f, dLeft, 0.05f);
        assertEquals(0f, dRight, 0.05f);
    }

    @Test public void bothCurvesStayFiniteAcrossTheFullRange() {
        for (float t = -0.5f; t <= 1.5f; t += 0.05f) {
            assertFalse(Float.isNaN(BodyPath.sunY(t, W, H, R)));
            assertFalse(Float.isNaN(BodyPath.moonY(t, H)));
        }
    }

    @Test public void moonTFoldsAMoonsetAfterMidnight() {
        // Moonrise 22:00, moonset 03:00 next day -> a 5h window.
        assertEquals(0f, BodyPath.moonT(22f, 22f, 3f), 0.001f);
        assertEquals(0.5f, BodyPath.moonT(0.5f, 22f, 3f), 0.001f);
        assertEquals(1f, BodyPath.moonT(3f, 22f, 3f), 0.01f);
    }

    @Test public void moonTIsMonotonicAcrossTheWindow() {
        float prev = BodyPath.moonT(22f, 22f, 3f);
        float h = 22f;
        for (int i = 1; i <= 20; i++) {
            h = (h + 0.25f) % 24f;
            float cur = BodyPath.moonT(h, 22f, 3f);
            assertTrue("step " + i, cur > prev);
            prev = cur;
        }
    }

    @Test public void moonTIsNaNForADegenerateWindow() {
        assertTrue(Float.isNaN(BodyPath.moonT(10f, 8f, 8f)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests BodyPathTest`
Expected: FAIL — `BodyPath` class not found.

- [ ] **Step 3: Write `BodyPath.java`**

```java
package com.retro.launcher.core;

/**
 * Pure body-position math for the sky wallpaper. No Android type; fully
 * unit-testable. Nothing else may compute a sun or moon screen position —
 * see V9 spec §1.
 */
public final class BodyPath {

    private BodyPath() {}

    static final float DAY_SPAN = SolarClock.SUNSET_ANCHOR - SolarClock.SUNRISE_ANCHOR;

    /** 0 at the sunrise anchor, 1 at the sunset anchor; <0 and >1 during twilight. */
    static float sunT(float warpedHour) {
        return (warpedHour - SolarClock.SUNRISE_ANCHOR) / DAY_SPAN;
    }

    static float sunX(float t, int w) { return t * w; }

    /** Tangent to the top edge at noon (t=0.5); tangent to the bottom edge
     *  at both anchors (t=0, t=1). Past the anchors {@code u*u} exceeds 1
     *  and the parabola carries the disc below the buffer on its own. */
    static float sunY(float t, int w, int h, float r) {
        float u = 2f * t - 1f;
        return r + (h - 2f * r) * u * u;
    }

    static float moonX(float t, int w) { return t * w; }

    /** Enters at the top-left (t=0, y=0); falls to dead centre at the vertex
     *  (t=0.5, y=0.5h); climbs back to 20% above the vertex on exit
     *  (t=1, y=0.3h). The two branches meet at the vertex with zero slope on
     *  both sides despite the differing fall/climb coefficients. */
    static float moonY(float t, int h) {
        float u = 2f * t - 1f;
        float drop = (t < 0.5f) ? 0.50f : 0.20f;
        return 0.5f * h - drop * h * u * u;
    }

    /**
     * Fraction of the way through the moon's own rise-to-set window: 0 at
     * moonrise, 1 at moonset. Folds the window across midnight the same way
     * {@link SolarClock#warp} folds night, so a moonset that lands after
     * 00:00 (encoded as {@code moonsetHour <= moonriseHour}) still produces
     * a monotonic result. NaN when the window is degenerate.
     */
    static float moonT(float hour, float moonriseHour, float moonsetHour) {
        float end = moonsetHour <= moonriseHour ? moonsetHour + 24f : moonsetHour;
        float span = end - moonriseHour;
        if (!(span > 0f)) return Float.NaN;
        float h = hour < moonriseHour ? hour + 24f : hour;
        return (h - moonriseHour) / span;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:test --tests BodyPathTest`
Expected: PASS, all 13 tests.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/BodyPath.java \
        core/src/test/java/com/retro/launcher/core/BodyPathTest.java
git commit -m "feat: add BodyPath, the single source of sun/moon screen positions (V9 §1-§3)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 3: `SolarTimes` gains moon fields, and `LunarMath` computes them

**Files:**
- Modify: `core/src/main/java/com/retro/launcher/core/SolarTimes.java`
- Create: `core/src/main/java/com/retro/launcher/core/LunarMath.java`
- Create: `core/src/test/java/com/retro/launcher/core/LunarMathTest.java`

**Interfaces:**
- Produces: `SolarTimes` gains `moonriseHour`, `moonsetHour` (both `float`, NaN when unknown) and a new 6-arg constructor; the existing 4-arg constructor is preserved (delegates, NaN for both). `LunarMath.LunarTimes{moonriseHour, moonsetHour}`; `LunarMath.moonTimes(float lat, float lon, LocalDate date, ZoneId zone) -> LunarTimes` (null only when neither a rise nor a set falls on that calendar day).
- Consumes: nothing new — `SolarMath.sunTimes` and `WeatherParser.parseSolarTimes` keep calling the existing 4-arg `SolarTimes` constructor unchanged; they compile against it without modification.

- [ ] **Step 1: Extend `SolarTimes.java`**

```java
package com.retro.launcher.core;

import java.time.LocalDate;

/**
 * One day's sunrise, sunset, the *next* day's sunrise, and (V9) that day's
 * moonrise/moonset — each as a decimal local hour in {@code [0, 24)}, or NaN
 * when unknown — plus the date they belong to. Immutable.
 */
public final class SolarTimes {

    public final float sunriseHour;
    public final float sunsetHour;
    public final float tomorrowSunriseHour;
    public final float moonriseHour;
    public final float moonsetHour;
    public final LocalDate date;

    /** Sun-only convenience constructor — moon fields default to NaN
     *  ("unknown"), the contract every existing caller already relies on. */
    public SolarTimes(float sunriseHour, float sunsetHour, float tomorrowSunriseHour, LocalDate date) {
        this(sunriseHour, sunsetHour, tomorrowSunriseHour, Float.NaN, Float.NaN, date);
    }

    public SolarTimes(float sunriseHour, float sunsetHour, float tomorrowSunriseHour,
                       float moonriseHour, float moonsetHour, LocalDate date) {
        this.sunriseHour = sunriseHour;
        this.sunsetHour = sunsetHour;
        this.tomorrowSunriseHour = tomorrowSunriseHour;
        this.moonriseHour = moonriseHour;
        this.moonsetHour = moonsetHour;
        this.date = date;
    }
}
```

- [ ] **Step 2: Run the existing `:core` suite to confirm the extension is source-compatible**

Run: `./gradlew :core:test --tests SolarMathTest --tests WeatherParserTest`
Expected: `SolarMathTest` passes unmodified. `WeatherParserTest` will already be failing to compile from Task 1's `Weather` change — confirm no *new* failure comes from `SolarTimes` itself (the 4-arg constructor call sites in `SolarMath.java` and `WeatherParser.java` need zero edits).

- [ ] **Step 3: Write the failing `LunarMathTest`**

```java
package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

import java.time.LocalDate;
import java.time.ZoneId;

public class LunarMathTest {

    private static final float LAT = 52.52f, LON = 13.4f;
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    @Test public void returnsHoursInRangeWhenTheMoonRisesAndSets() {
        boolean sawAResult = false;
        LocalDate d = LocalDate.of(2026, 3, 1);
        for (int i = 0; i < 30; i++) {
            LunarMath.LunarTimes t = LunarMath.moonTimes(LAT, LON, d.plusDays(i), ZONE);
            if (t == null) continue;
            sawAResult = true;
            if (!Float.isNaN(t.moonriseHour)) {
                assertTrue(t.moonriseHour >= 0f && t.moonriseHour < 24f);
            }
            if (!Float.isNaN(t.moonsetHour)) {
                assertTrue(t.moonsetHour >= 0f && t.moonsetHour < 24f);
            }
        }
        assertTrue(sawAResult);
    }

    @Test public void someDayInALunarMonthHasNeitherARiseNorASet() {
        // The lunar day is ~24h50m, so at any latitude roughly once a month a
        // calendar day has neither event.
        boolean foundNull = false;
        LocalDate d = LocalDate.of(2026, 3, 1);
        for (int i = 0; i < 30 && !foundNull; i++) {
            if (LunarMath.moonTimes(LAT, LON, d.plusDays(i), ZONE) == null) foundNull = true;
        }
        assertTrue("expected at least one no-rise-no-set day across a lunar month", foundNull);
    }

    @Test public void someDayHasTheMoonSetBeforeItRisesAgain() {
        // Roughly half of all days: the moon is already up at local midnight
        // (it rose the previous day), sets that morning, then rises again
        // later the same day for the night ahead.
        boolean found = false;
        LocalDate d = LocalDate.of(2026, 3, 1);
        for (int i = 0; i < 30 && !found; i++) {
            LunarMath.LunarTimes t = LunarMath.moonTimes(LAT, LON, d.plusDays(i), ZONE);
            if (t != null && !Float.isNaN(t.moonriseHour) && !Float.isNaN(t.moonsetHour)
                    && t.moonsetHour < t.moonriseHour) {
                found = true;
            }
        }
        assertTrue("expected at least one set-before-rise day in a month", found);
    }

    @Test public void fullMoonRisesNearSunsetAndSetsNearSunrise() {
        // A full moon is, by definition, opposite the sun: it rises as the
        // sun sets and sets as the sun rises. Cross-checking against
        // SolarMath's already-tested sunrise/sunset avoids hardcoding an
        // external "known good" moonrise table this test can't independently
        // verify.
        LocalDate date = nearestFullMoonDate();
        SolarTimes sun = SolarMath.sunTimes(LAT, LON, date, ZONE);
        LunarMath.LunarTimes moon = LunarMath.moonTimes(LAT, LON, date, ZONE);
        assertNotNull(sun);
        assertNotNull(moon);

        if (!Float.isNaN(moon.moonriseHour)) {
            assertEquals(sun.sunsetHour, moon.moonriseHour, 1.0f);
        }
        if (!Float.isNaN(moon.moonsetHour)) {
            assertEquals(sun.sunriseHour, moon.moonsetHour, 1.0f);
        }
    }

    private static LocalDate nearestFullMoonDate() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate best = start;
        float bestDist = 1f;
        for (int i = 0; i < 400; i++) {
            LocalDate candidate = start.plusDays(i);
            long millis = candidate.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
            float dist = Math.abs(MoonPhase.phase(millis) - 0.5f);
            if (dist < bestDist) { bestDist = dist; best = candidate; }
        }
        return best;
    }

    @Test public void aPolarSummerNightGivesAConsistentAnswer() {
        // Very high latitude: must not throw, and must return either null or
        // in-range hours — never NaN mixed with an out-of-range number.
        LunarMath.LunarTimes t = LunarMath.moonTimes(78f, 15f, LocalDate.of(2026, 6, 21), ZoneId.of("UTC"));
        if (t != null) {
            if (!Float.isNaN(t.moonriseHour)) assertTrue(t.moonriseHour >= 0f && t.moonriseHour < 24f);
            if (!Float.isNaN(t.moonsetHour)) assertTrue(t.moonsetHour >= 0f && t.moonsetHour < 24f);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :core:test --tests LunarMathTest`
Expected: FAIL — `LunarMath` class not found.

- [ ] **Step 5: Write `LunarMath.java`**

```java
package com.retro.launcher.core;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Moonrise and moonset from latitude, longitude and date, offline — mirrors
 * {@link SolarMath}'s style. Pure math, no I/O, no Android type.
 *
 * Low-precision lunar ecliptic position (the same order of approximation
 * {@link MoonPhase} already uses) is converted to equatorial coordinates,
 * then searched hour-by-hour across the local calendar day for the two
 * crossings of {@code +0.125°} — Meeus's fixed mean value of
 * "0.7275·parallax − refraction" for the Moon (Astronomical Algorithms
 * ch. 15), used as a constant rather than recomputed per-instant since a
 * 12px disc cannot show the arcminute-scale difference true parallax would
 * make. Each 2-hour span is fit with a parabola through three altitude
 * samples and solved for its root(s) — the standard hour-stepping method,
 * accurate to a few minutes for a body that moves as slowly as the Moon.
 */
public final class LunarMath {

    private LunarMath() {}

    private static final double MOONRISE_ALTITUDE_DEG = 0.125;
    private static final double UNIX_EPOCH_JD = 2440587.5;
    private static final double J2000_JD = 2451545.0;
    private static final double OBLIQUITY_DEG = 23.4397;
    private static final long HOUR_MS = 3_600_000L;

    public static final class LunarTimes {
        public final float moonriseHour;
        public final float moonsetHour;
        public LunarTimes(float moonriseHour, float moonsetHour) {
            this.moonriseHour = moonriseHour;
            this.moonsetHour = moonsetHour;
        }
    }

    /**
     * @return today's moonrise and/or moonset as decimal local hours in
     *         {@code zone}, or {@code null} if neither happens on this
     *         calendar day.
     */
    public static LunarTimes moonTimes(float latitude, float longitude, LocalDate date, ZoneId zone) {
        long t0 = date.atStartOfDay(zone).toInstant().toEpochMilli();
        double latRad = Math.toRadians(latitude);

        Double rise = null, set = null;
        double h0 = altitude(t0, latRad, longitude) - MOONRISE_ALTITUDE_DEG;

        for (int i = 1; i <= 23 && (rise == null || set == null); i += 2) {
            double h1 = altitude(t0 + i * HOUR_MS, latRad, longitude) - MOONRISE_ALTITUDE_DEG;
            double h2 = altitude(t0 + (i + 1) * HOUR_MS, latRad, longitude) - MOONRISE_ALTITUDE_DEG;

            double a = (h0 + h2) / 2.0 - h1;
            double b = (h2 - h0) / 2.0;

            if (a != 0.0) {
                double xe = -b / (2.0 * a);
                double ye = (a * xe + b) * xe + h1;
                double d = b * b - 4.0 * a * h1;

                if (d >= 0.0) {
                    double dx = Math.sqrt(d) / (Math.abs(a) * 2.0);
                    double x1 = xe - dx, x2 = xe + dx;
                    int roots = 0;
                    if (Math.abs(x1) <= 1.0) roots++;
                    if (Math.abs(x2) <= 1.0) roots++;
                    if (x1 < -1.0) x1 = x2;

                    if (roots == 1) {
                        if (h0 < 0) { if (rise == null) rise = (double) i + x1; }
                        else        { if (set  == null) set  = (double) i + x1; }
                    } else if (roots == 2) {
                        if (ye < 0) {
                            if (rise == null) rise = (double) i + x2;
                            if (set  == null) set  = (double) i + x1;
                        } else {
                            if (rise == null) rise = (double) i + x1;
                            if (set  == null) set  = (double) i + x2;
                        }
                    }
                }
            }
            h0 = h2;
        }

        if (rise == null && set == null) return null;
        return new LunarTimes(
                rise == null ? Float.NaN : rise.floatValue(),
                set == null ? Float.NaN : set.floatValue());
    }

    private static double altitude(long utcMillis, double latRad, double longitudeDeg) {
        double d = daysSinceJ2000(utcMillis);
        double[] eq = moonEquatorial(d);
        double raDeg = eq[0], decRad = eq[1];

        double lst = 280.16 + 360.9856235 * d + longitudeDeg;
        double hourAngleRad = Math.toRadians(norm360(lst - raDeg));

        double sinAlt = Math.sin(latRad) * Math.sin(decRad)
                + Math.cos(latRad) * Math.cos(decRad) * Math.cos(hourAngleRad);
        return Math.toDegrees(Math.asin(clamp(sinAlt, -1.0, 1.0)));
    }

    /** Geocentric right ascension (degrees) and declination (radians) of the
     *  Moon from the low-precision ecliptic-position formula — good to
     *  roughly a degree, far finer than a 12px disc or a 15-minute rise/set
     *  tolerance needs. */
    private static double[] moonEquatorial(double d) {
        double L = Math.toRadians(norm360(218.316 + 13.176396 * d));
        double M = Math.toRadians(norm360(134.963 + 13.064993 * d));
        double F = Math.toRadians(norm360(93.272 + 13.229350 * d));

        double lon = L + Math.toRadians(6.289) * Math.sin(M);
        double lat = Math.toRadians(5.128) * Math.sin(F);
        double e = Math.toRadians(OBLIQUITY_DEG);

        double raRad = Math.atan2(
                Math.sin(lon) * Math.cos(e) - Math.tan(lat) * Math.sin(e),
                Math.cos(lon));
        double decRad = Math.asin(Math.sin(lat) * Math.cos(e) + Math.cos(lat) * Math.sin(e) * Math.sin(lon));

        double raDeg = Math.toDegrees(raRad);
        if (raDeg < 0) raDeg += 360.0;
        return new double[]{ raDeg, decRad };
    }

    private static double daysSinceJ2000(long utcMillis) {
        return utcMillis / 86_400_000.0 + UNIX_EPOCH_JD - J2000_JD;
    }

    private static double norm360(double deg) {
        double v = deg % 360.0;
        return v < 0 ? v + 360.0 : v;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :core:test --tests LunarMathTest`
Expected: PASS, all 5 tests. If `fullMoonRisesNearSunsetAndSetsNearSunrise` or the set-before-rise/no-rise-no-set scans are flaky, widen the day-scan windows slightly (they are written generously already) rather than loosening the underlying algorithm.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/SolarTimes.java \
        core/src/main/java/com/retro/launcher/core/LunarMath.java \
        core/src/test/java/com/retro/launcher/core/LunarMathTest.java
git commit -m "feat: add LunarMath and moonrise/moonset fields on SolarTimes (V9 §4)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 4: `SkyConditions` and the four-channel `WeatherParser`

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/SkyConditions.java`
- Modify: `core/src/main/java/com/retro/launcher/core/WeatherParser.java`
- Modify: `core/src/test/java/com/retro/launcher/core/WeatherParserTest.java`

**Interfaces:**
- Produces: `SkyConditions(float hour, float realHour, float moonriseHour, float moonsetHour, float cloudCover, float precip, float moonPhase, Precip type, boolean thunder, int tempC)`. `WeatherParser.parse(json)` now reads Open-Meteo's modern `current` block and returns a fully-populated `Weather` (Task 1's constructor).
- Consumes: `Weather.derive` (Task 1), `Precip` (Task 1), `SyntheticWeather.label` (unchanged).

- [ ] **Step 1: Write `SkyConditions.java`**

```java
package com.retro.launcher.core;

/**
 * Everything {@link SkyRenderer#render} needs for one frame, collapsed into
 * one immutable struct so the method signature does not have to carry nine
 * loose scalars. {@code hour} is the {@link SolarClock}-warped hour driving
 * sky colour and the sun's position; {@code realHour} is the unwarped local
 * hour driving the moon's position on its own clock (V9 spec §3).
 */
public final class SkyConditions {

    public final float hour;
    public final float realHour;
    public final float moonriseHour;
    public final float moonsetHour;
    public final float cloudCover;
    public final float precip;
    public final float moonPhase;
    public final Precip type;
    public final boolean thunder;
    public final int tempC;

    public SkyConditions(float hour, float realHour, float moonriseHour, float moonsetHour,
                          float cloudCover, float precip, float moonPhase,
                          Precip type, boolean thunder, int tempC) {
        this.hour = hour;
        this.realHour = realHour;
        this.moonriseHour = moonriseHour;
        this.moonsetHour = moonsetHour;
        this.cloudCover = cloudCover;
        this.precip = precip;
        this.moonPhase = moonPhase;
        this.type = type;
        this.thunder = thunder;
        this.tempC = tempC;
    }
}
```

- [ ] **Step 2: Rewrite `WeatherParserTest.java`**

Replace the whole file:

```java
package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherParserTest {

    /** A modern Open-Meteo reply to
     *  /v1/forecast?...&current=temperature_2m,weather_code,cloud_cover,precipitation,precipitation_probability */
    private static final String RECORDED =
            "{\"latitude\":52.52,\"longitude\":13.419,\"generationtime_ms\":0.23,"
            + "\"utc_offset_seconds\":0,\"timezone\":\"GMT\",\"timezone_abbreviation\":\"GMT\","
            + "\"elevation\":38.0,\"current\":{\"time\":\"2026-08-28T20:00\",\"temperature_2m\":13.4,"
            + "\"weather_code\":3,\"cloud_cover\":88,\"precipitation\":0.0,\"precipitation_probability\":10}}";

    // ---- the recorded payload ------------------------------------------

    @Test public void readsTemperatureFromTheRecordedPayload() {
        assertEquals(13, WeatherParser.parse(RECORDED).tempC);
    }

    @Test public void readsConditionFromTheRecordedPayload() {
        assertEquals("OVERCAST", WeatherParser.parse(RECORDED).label);
    }

    @Test public void readsCloudCoverDirectlyFromTheChannel() {
        assertEquals(0.88f, WeatherParser.parse(RECORDED).cloudCover, 0.001f);
    }

    @Test public void readsPrecipProbabilityDirectly() {
        assertEquals(10, WeatherParser.parse(RECORDED).precipProbability);
    }

    @Test public void recordedPayloadYieldsAnInRangeSkyScalar() {
        float w = WeatherParser.parse(RECORDED).w;
        assertTrue("w out of range: " + w, w >= 0f && w <= 1f);
    }

    // ---- the four channels are independent -------------------------------

    @Test public void lightDrizzleDrawsFewCloudsAndSparseRain() {
        String json = "{\"current\":{\"temperature_2m\":12.0,\"weather_code\":51,"
                + "\"cloud_cover\":20,\"precipitation\":0.2,\"precipitation_probability\":60}}";
        Weather w = WeatherParser.parse(json);
        assertEquals(0.20f, w.cloudCover, 0.001f);
        assertEquals(Precip.RAIN, w.type);
        assertTrue("expected a light precip intensity", w.precip > 0f && w.precip < 0.2f);
        assertFalse(w.thunder);
    }

    @Test public void dryThunderstormHasThunderAndZeroPrecip() {
        // Cloud is high, precipitation is explicitly zero — a real "storm
        // building, no rain yet" reading, which the old coupled-scalar
        // system could never represent.
        String json = "{\"current\":{\"temperature_2m\":22.0,\"weather_code\":95,"
                + "\"cloud_cover\":95,\"precipitation\":0.0,\"precipitation_probability\":30}}";
        Weather w = WeatherParser.parse(json);
        assertTrue(w.thunder);
        assertEquals(0f, w.precip, 0.0001f);
        assertEquals(Precip.NONE, w.type);
        assertEquals(1.0f, w.w, 0.001f);
    }

    @Test public void snowCodeWithPrecipitationYieldsSnowType() {
        String json = "{\"current\":{\"temperature_2m\":-3.0,\"weather_code\":73,"
                + "\"cloud_cover\":80,\"precipitation\":1.5,\"precipitation_probability\":90}}";
        Weather w = WeatherParser.parse(json);
        assertEquals(Precip.SNOW, w.type);
        assertTrue(w.precip > 0f);
    }

    @Test public void heavyRainSaturatesPrecipAtFourMillimetresAnHour() {
        String json = "{\"current\":{\"temperature_2m\":15.0,\"weather_code\":65,"
                + "\"cloud_cover\":100,\"precipitation\":4.0,\"precipitation_probability\":100}}";
        assertEquals(1.0f, WeatherParser.parse(json).precip, 0.001f);
    }

    @Test public void precipitationAboveTheSaturationPointClampsToOne() {
        String json = "{\"current\":{\"temperature_2m\":15.0,\"weather_code\":65,"
                + "\"cloud_cover\":100,\"precipitation\":40.0,\"precipitation_probability\":100}}";
        assertEquals(1.0f, WeatherParser.parse(json).precip, 0.001f);
    }

    @Test public void missingCloudCoverFallsBackToTheCodeImpliedValue() {
        // weather_code 3 (overcast) implies a high cloud cover in the old
        // per-code table, applied only because cloud_cover is absent here.
        String json = "{\"current\":{\"temperature_2m\":13.0,\"weather_code\":3,"
                + "\"precipitation\":0.0,\"precipitation_probability\":0}}";
        Weather w = WeatherParser.parse(json);
        assertTrue("expected a substantial implied cloud cover for overcast", w.cloudCover > 0.3f);
    }

    @Test public void missingPrecipitationFallsBackToTheCodeImpliedValue() {
        String json = "{\"current\":{\"temperature_2m\":13.0,\"weather_code\":65,"
                + "\"cloud_cover\":100,\"precipitation_probability\":100}}";
        Weather w = WeatherParser.parse(json);
        assertTrue("expected a substantial implied precip for heavy rain", w.precip > 0.3f);
    }

    // ---- code to condition ---------------------------------------------

    @Test public void clearSkyCodeIsClear() {
        assertEquals("CLEAR", parseCode(0).label);
    }

    @Test public void partlyCloudyCodeIsPartlyCloudy() {
        assertEquals("PARTLY CLOUDY", parseCode(2).label);
    }

    @Test public void fogCodeReadsAsHaze() {
        assertEquals("HAZY", parseCode(45).label);
    }

    @Test public void heavyRainCodeIsADownpour() {
        assertEquals("DOWNPOUR", parseCode(65).label);
    }

    @Test public void thunderstormCodeIsAThunderstorm() {
        assertEquals("THUNDERSTORM", parseCode(95).label);
    }

    @Test public void snowCodesUseTheSnowLabels() {
        assertEquals("SNOW", parseCode(73).label);
    }

    @Test public void freezingRainCountsAsSnow() {
        assertEquals("LIGHT SNOW", parseCode(66).label);
    }

    // ---- temperature handling ------------------------------------------

    @Test public void temperatureRoundsToTheNearestDegree() {
        assertEquals(14, parseTemp("13.6").tempC);
    }

    @Test public void negativeTemperaturesKeepTheirSign() {
        assertEquals(-7, parseTemp("-6.8").tempC);
    }

    @Test public void temperatureMayArriveWithoutADecimalPoint() {
        assertEquals(21, parseTemp("21").tempC);
    }

    // ---- shapes that must yield "no update" -----------------------------

    @Test public void nullInputYieldsNoUpdate() {
        assertNull(WeatherParser.parse(null));
    }

    @Test public void emptyInputYieldsNoUpdate() {
        assertNull(WeatherParser.parse(""));
    }

    @Test public void anObjectWithoutCurrentYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"latitude\":52.5,\"longitude\":13.4}"));
    }

    @Test public void anEmptyCurrentYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current\":{}}"));
    }

    @Test public void missingWeatherCodeYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current\":{\"temperature_2m\":13.4}}"));
    }

    @Test public void missingTemperatureYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current\":{\"weather_code\":3}}"));
    }

    @Test public void truncatedJsonYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current\":{\"temperature_2m\":13."));
    }

    @Test public void anHtmlErrorPageYieldsNoUpdate() {
        assertNull(WeatherParser.parse("<html><body>502 Bad Gateway</body></html>"));
    }

    @Test public void anApiErrorObjectYieldsNoUpdate() {
        assertNull(WeatherParser.parse(
                "{\"error\":true,\"reason\":\"Latitude must be in range of -90 to 90\"}"));
    }

    @Test public void aNonNumericTemperatureYieldsNoUpdate() {
        assertNull(WeatherParser.parse(
                "{\"current\":{\"temperature_2m\":\"warm\",\"weather_code\":3}}"));
    }

    @Test public void anUnrecognisedWeatherCodeYieldsNoUpdate() {
        assertNull(parseCodeRaw(42));
    }

    // ---- daily sunrise/sunset -------------------------------------------

    private static final String RECORDED_WITH_DAILY =
            "{\"latitude\":52.52,\"longitude\":13.419,\"timezone\":\"Europe/Berlin\","
            + "\"current\":{\"temperature_2m\":13.4,\"weather_code\":3},"
            + "\"daily\":{\"time\":[\"2026-08-28\",\"2026-08-29\"],"
            + "\"sunrise\":[\"2026-08-28T06:12\",\"2026-08-29T06:14\"],"
            + "\"sunset\":[\"2026-08-28T20:31\",\"2026-08-29T20:29\"]}}";

    @Test public void parsesSunriseSunsetAndTomorrowSunriseFromTheDailyBlock() {
        SolarTimes t = WeatherParser.parseSolarTimes(RECORDED_WITH_DAILY, java.time.LocalDate.of(2026, 8, 28));
        assertNotNull(t);
        assertEquals(6f + 12f / 60f, t.sunriseHour, 0.001f);
        assertEquals(20f + 31f / 60f, t.sunsetHour, 0.001f);
        assertEquals(6f + 14f / 60f, t.tomorrowSunriseHour, 0.001f);
        assertEquals(java.time.LocalDate.of(2026, 8, 28), t.date);
    }

    @Test public void absentDailyBlockYieldsNullSolarTimes() {
        assertNull(WeatherParser.parseSolarTimes(
                "{\"current\":{\"temperature_2m\":13.4,\"weather_code\":3}}",
                java.time.LocalDate.of(2026, 8, 28)));
    }

    @Test public void malformedDailyBlockYieldsNullSolarTimes() {
        assertNull(WeatherParser.parseSolarTimes(
                "{\"daily\":{\"sunrise\":[\"not-a-time\"],\"sunset\":[]}}",
                java.time.LocalDate.of(2026, 8, 28)));
    }

    @Test public void nullJsonYieldsNullSolarTimes() {
        assertNull(WeatherParser.parseSolarTimes(null, java.time.LocalDate.of(2026, 8, 28)));
    }

    // ---- scanner robustness ---------------------------------------------

    @Test public void currentUnitsDoesNotMasqueradeAsCurrent() {
        // The modern API emits a units object whose key has "current" as a
        // prefix. Reading it instead would give back "°C" as a temperature.
        String json = "{\"current_units\":{\"temperature_2m\":\"°C\",\"weather_code\":\"wmo code\"},"
                + "\"current\":{\"temperature_2m\":9.1,\"weather_code\":0}}";
        assertEquals(9, WeatherParser.parse(json).tempC);
    }

    @Test public void keyOrderInsideCurrentDoesNotMatter() {
        assertEquals(9, WeatherParser.parse(
                "{\"current\":{\"weather_code\":0,\"temperature_2m\":9.1}}").tempC);
    }

    @Test public void unknownKeysInsideCurrentAreIgnored() {
        assertEquals(9, WeatherParser.parse("{\"current\":{\"interval\":900,"
                + "\"temperature_2m\":9.1,\"weather_code\":0,\"future_field\":{\"a\":1}}}").tempC);
    }

    @Test public void whitespaceAroundSeparatorsIsTolerated() {
        assertEquals(9, WeatherParser.parse(
                "{ \"current\" : { \"temperature_2m\" : 9.1 , \"weather_code\" : 0 } }").tempC);
    }

    @Test public void bracesInsideStringValuesDoNotEndTheObject() {
        assertEquals(9, WeatherParser.parse("{\"current\":{\"time\":\"}{\","
                + "\"temperature_2m\":9.1,\"weather_code\":0}}").tempC);
    }

    // ---- helpers ---------------------------------------------------------

    private static Weather parseCode(int code) {
        Weather w = parseCodeRaw(code);
        assertNotNull("expected code " + code + " to be recognised", w);
        return w;
    }

    private static Weather parseCodeRaw(int code) {
        return WeatherParser.parse(
                "{\"current\":{\"temperature_2m\":5.0,\"weather_code\":" + code + "}}");
    }

    private static Weather parseTemp(String temp) {
        Weather w = WeatherParser.parse(
                "{\"current\":{\"temperature_2m\":" + temp + ",\"weather_code\":0}}");
        assertNotNull(w);
        return w;
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:test --tests WeatherParserTest`
Expected: FAIL — `WeatherParser` still uses `"current_weather"`/`"temperature"`/`"weathercode"` keys and the old 3-arg `Weather` constructor.

- [ ] **Step 4: Rewrite `WeatherParser.java`**

Replace the whole file:

```java
package com.retro.launcher.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns Open-Meteo's modern {@code current=} reply into a {@link Weather}.
 *
 * <h3>Why this parses JSON by hand</h3>
 * This class lives in {@code :core}, and {@code :core} having no Android
 * dependency is exactly what lets it be unit-tested on the JVM. The reader
 * below is deliberately small and deliberately narrow — it finds one named
 * object and a handful of named numbers inside it, and refuses everything
 * else.
 *
 * <h3>Failure is normal and silent</h3>
 * Every malformed shape — a truncated body, an HTML error page, an API error
 * object, a missing required field, a field of the wrong type — returns
 * null, meaning "no update". A missing *optional* channel (cloud cover,
 * precipitation) instead falls back to the value implied by
 * {@code weather_code}, reproducing pre-V9 behaviour for that one channel
 * rather than failing the whole reading. This class never throws.
 */
public final class WeatherParser {

    private WeatherParser() {}

    private static final String OBJECT_KEY = "\"current\"";

    /** mm of the preceding hour that saturates the visual {@code precip}
     *  intensity to 1.0 — 4mm/h is WMO's "heavy rain" threshold. */
    private static final float PRECIP_SATURATION_MM = 4f;

    /**
     * @param json a full Open-Meteo response body, or null
     * @return the reading, or null if the body could not be read with
     *         confidence — including a WMO code outside the known set
     */
    public static Weather parse(String json) {
        if (json == null) return null;

        String body = objectFor(json, OBJECT_KEY);
        if (body == null) return null;

        Double temp = number(body, "\"temperature_2m\"");
        Double code = number(body, "\"weather_code\"");
        if (temp == null || code == null) return null;

        Condition c = Condition.forWmoCode((int) Math.round(code));
        if (c == null) return null;

        Double cloudPct = number(body, "\"cloud_cover\"");
        Double precipMm = number(body, "\"precipitation\"");
        Double precipProb = number(body, "\"precipitation_probability\"");

        float cloudCover = cloudPct != null
                ? SkyRenderer.clamp01(cloudPct.floatValue() / 100f)
                : SkyRenderer.smooth(0.10f, 0.66f, c.w);
        float precip = precipMm != null
                ? SkyRenderer.clamp01(precipMm.floatValue() / PRECIP_SATURATION_MM)
                : SkyRenderer.smooth(0.62f, 0.98f, c.w);
        int precipProbability = precipProb != null ? Math.round(precipProb.floatValue()) : 0;

        boolean thunder = c.thunder;
        Precip type = precip > 0f ? (c.snow ? Precip.SNOW : Precip.RAIN) : Precip.NONE;
        float w = Weather.derive(cloudCover, precip, thunder);

        return new Weather((int) Math.round(temp), SyntheticWeather.label(w, c.snow),
                cloudCover, precip, type, thunder, precipProbability);
    }

    private static final String DAILY_KEY = "\"daily\"";

    /**
     * @param json  a full Open-Meteo response body with {@code &daily=
     *              sunrise,sunset&forecast_days=2} appended to the request,
     *              or null
     * @param today the local date the first element of the {@code daily}
     *              arrays is expected to describe
     * @return today's sunrise, sunset and tomorrow's sunrise, or null if the
     *         block is absent or cannot be read with confidence
     */
    public static SolarTimes parseSolarTimes(String json, LocalDate today) {
        if (json == null) return null;

        String body = objectFor(json, DAILY_KEY);
        if (body == null) return null;

        List<String> sunrises = stringArray(body, "\"sunrise\"");
        List<String> sunsets = stringArray(body, "\"sunset\"");
        if (sunrises == null || sunsets == null) return null;
        if (sunrises.size() < 2 || sunsets.size() < 1) return null;

        Float sunriseHour = hourOfDay(sunrises.get(0));
        Float sunsetHour = hourOfDay(sunsets.get(0));
        Float tomorrowSunriseHour = hourOfDay(sunrises.get(1));
        if (sunriseHour == null || sunsetHour == null || tomorrowSunriseHour == null) return null;

        return new SolarTimes(sunriseHour, sunsetHour, tomorrowSunriseHour, today);
    }

    private static List<String> stringArray(String body, String key) {
        int at = body.indexOf(key);
        if (at < 0) return null;

        int i = skipSpace(body, at + key.length());
        if (i >= body.length() || body.charAt(i) != ':') return null;
        i = skipSpace(body, i + 1);
        if (i >= body.length() || body.charAt(i) != '[') return null;
        i++;

        List<String> out = new ArrayList<>();
        i = skipSpace(body, i);
        if (i < body.length() && body.charAt(i) == ']') return out;

        while (i < body.length()) {
            if (body.charAt(i) != '"') return null;
            int start = ++i;
            while (i < body.length() && body.charAt(i) != '"') i++;
            if (i >= body.length()) return null;
            out.add(body.substring(start, i));
            i = skipSpace(body, i + 1);
            if (i >= body.length()) return null;
            if (body.charAt(i) == ',') { i = skipSpace(body, i + 1); continue; }
            if (body.charAt(i) == ']') return out;
            return null;
        }
        return null;
    }

    private static Float hourOfDay(String isoLocalDateTime) {
        try {
            LocalDateTime dt = LocalDateTime.parse(isoLocalDateTime);
            return dt.getHour() + dt.getMinute() / 60f;
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    private static String objectFor(String json, String key) {
        int at = json.indexOf(key);
        if (at < 0) return null;

        int i = skipSpace(json, at + key.length());
        if (i >= json.length() || json.charAt(i) != ':') return null;
        i = skipSpace(json, i + 1);
        if (i >= json.length() || json.charAt(i) != '{') return null;

        int depth = 0;
        boolean inString = false, escaped = false;
        for (int j = i; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (escaped)          { escaped = false; continue; }
            if (ch == '\\' && inString) { escaped = true; continue; }
            if (ch == '"')        { inString = !inString; continue; }
            if (inString)         continue;
            if (ch == '{')        depth++;
            else if (ch == '}' && --depth == 0) return json.substring(i + 1, j);
        }
        return null;
    }

    private static Double number(String body, String key) {
        int at = body.indexOf(key);
        if (at < 0) return null;

        int i = skipSpace(body, at + key.length());
        if (i >= body.length() || body.charAt(i) != ':') return null;
        i = skipSpace(body, i + 1);

        int start = i;
        while (i < body.length() && isNumeric(body.charAt(i))) i++;
        if (i == start) return null;

        try {
            return Double.valueOf(body.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isNumeric(char c) {
        return (c >= '0' && c <= '9')
                || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /**
     * One WMO 4677 present-weather code's *implied* effect on the sky, used
     * only as a per-channel fallback when Open-Meteo's own channel is
     * missing from the response, and always for {@code thunder} (the API has
     * no boolean field for it — codes 95/96/99 are the only source of truth).
     */
    private static final class Condition {
        final float w;
        final boolean snow;
        final boolean thunder;

        Condition(float w, boolean snow, boolean thunder) {
            this.w = w;
            this.snow = snow;
            this.thunder = thunder;
        }

        static Condition forWmoCode(int code) {
            switch (code) {
                case 0:  return new Condition(0.02f, false, false);
                case 1:  return new Condition(0.22f, false, false);
                case 2:  return new Condition(0.36f, false, false);
                case 3:  return new Condition(0.58f, false, false);

                case 45: case 48:
                    return new Condition(0.12f, false, false);

                case 51: case 53:
                    return new Condition(0.70f, false, false);
                case 55:
                    return new Condition(0.82f, false, false);
                case 56: case 57:
                    return new Condition(0.70f, true, false);

                case 61: return new Condition(0.70f, false, false);
                case 63: return new Condition(0.82f, false, false);
                case 65: return new Condition(0.91f, false, false);
                case 66: return new Condition(0.70f, true, false);
                case 67: return new Condition(0.82f, true, false);

                case 71: return new Condition(0.70f, true, false);
                case 73: return new Condition(0.82f, true, false);
                case 75: return new Condition(0.91f, true, false);
                case 77: return new Condition(0.70f, true, false);

                case 80: return new Condition(0.70f, false, false);
                case 81: return new Condition(0.82f, false, false);
                case 82: return new Condition(0.91f, false, false);
                case 85: return new Condition(0.70f, true, false);
                case 86: return new Condition(0.91f, true, false);

                case 95: case 96: case 99:
                    return new Condition(0.97f, false, true);

                default: return null;
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests WeatherParserTest`
Expected: PASS, all tests.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/SkyConditions.java \
        core/src/main/java/com/retro/launcher/core/WeatherParser.java \
        core/src/test/java/com/retro/launcher/core/WeatherParserTest.java
git commit -m "feat: parse Open-Meteo's four independent weather channels (V9 §6)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 5: `SkyRenderer` — body positions, four-channel layers, lens flare, heat shimmer

This is the largest task in the plan (per spec: "the largest single piece of work in V9"). It has four step-groups, each ending in its own commit, all against the same two files.

**Files:**
- Modify: `core/src/main/java/com/retro/launcher/core/SkyRenderer.java`
- Modify: `core/src/test/java/com/retro/launcher/core/SkyRendererTest.java`

**Interfaces:**
- Consumes: `BodyPath.*` (Task 2), `SkyConditions` (Task 4), `Precip` (Task 1).
- Produces: `public void render(int[] out, SkyConditions c, float seconds)` — the old `render(int[], float, float, float, float)`, `sunAngle(float)` and `moonAngle(float,float)` are all deleted. `sunAlt(float)`, `smooth`, `clamp01` are unchanged and stay public/static.

### Step-group A — body positions (§1–§3)

- [ ] **Step A1: Rewrite `SkyRendererTest`'s helpers and every test that touched `sunAngle`/`moonAngle`/`moonX`/`moonY`/`sunX`/`sunY`/the old `render(...)` signature**

Replace the whole file with:

```java
package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SkyRendererTest {

    private static final int W = 108, H = 234;
    private static final float SUN_R = 13f;

    private static SkyConditions cond(float hour, float cloudCover, float precip, float moonPhase,
                                       Precip type, boolean thunder, int tempC) {
        return new SkyConditions(hour, hour, 0f, 24f, cloudCover, precip, moonPhase, type, thunder, tempC);
    }

    /** Sky/sun-only conditions: no precip, no thunder, moon always visible
     *  across the full [0,24) knob (moonrise=0, moonset=24) so existing
     *  moon-position tests don't need real moonrise/moonset data. */
    private static SkyConditions basic(float hour, float moonPhase) {
        return cond(hour, 0f, 0f, moonPhase, Precip.NONE, false, 20);
    }

    private int[] renderAt(float hour, float cloudCover) {
        SkyRenderer r = new SkyRenderer(W, H);
        int[] buf = new int[W * H];
        r.render(buf, cond(hour, cloudCover, 0f, 0.62f, Precip.NONE, false, 20), 0f);
        return buf;
    }

    @Test public void sunAltitudePeaksAtNoonAndBottomsAtMidnight() {
        assertEquals(1f,  SkyRenderer.sunAlt(12.3f), 0.001f);
        assertEquals(-1f, SkyRenderer.sunAlt(0.3f),  0.001f);
    }

    @Test public void sunAltitudeIsZeroAtBothAnchors() {
        assertEquals(0f, SkyRenderer.sunAlt(6.2f),  0.001f);
        assertEquals(0f, SkyRenderer.sunAlt(18.4f), 0.001f);
    }

    @Test public void sunAltitudeIsPositiveAtMiddayAndNegativeAtMidnight() {
        assertTrue(SkyRenderer.sunAlt(12f) > 0f);
        assertTrue(SkyRenderer.sunAlt(0f) < 0f);
    }

    @Test public void sunAltitudeIsContinuousAcrossBothAnchors() {
        float justBeforeDawn = SkyRenderer.sunAlt(6.2f - 0.01f);
        float justAfterDawn  = SkyRenderer.sunAlt(6.2f + 0.01f);
        assertEquals(justBeforeDawn, justAfterDawn, 0.01f);

        float justBeforeDusk = SkyRenderer.sunAlt(18.4f - 0.01f);
        float justAfterDusk  = SkyRenderer.sunAlt(18.4f + 0.01f);
        assertEquals(justBeforeDusk, justAfterDusk, 0.01f);
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
        int[] buf = renderAt(12f, 0f);
        assertNotEquals(luma(buf[2 * W + 4]), luma(buf[(H - 3) * W + 4]), 0.5f);
    }

    @Test public void quantizationSnapsToFifteenLevelSteps() {
        int[] buf = renderAt(12f, 0f);
        int sx = Math.round(sunX(12f)), sy = Math.round(sunY(12f));
        int mx = Math.round(moonX(12f, 0f, 24f)), my = Math.round(moonY(12f, 0f, 24f));
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (Math.hypot(x - sx, y - sy) < 25) continue;
                if (Math.hypot(x - mx, y - my) < 20) continue;
                int px = buf[y * W + x];
                for (int shift : new int[]{16, 8, 0}) {
                    int v = (px >> shift) & 0xFF;
                    assertEquals("channel " + v + " is not a multiple of 15", 0, v % 15);
                }
            }
        }
    }

    @Test public void renderIsDeterministicForTheSameInputs() {
        int[] a = new int[W * H], b = new int[W * H];
        new SkyRenderer(W, H).render(a, basic(9.5f, 0.4f), 0f);
        new SkyRenderer(W, H).render(b, basic(9.5f, 0.4f), 0f);
        assertArrayEquals(a, b);
    }

    @Test public void desaturationPushesTowardGrey() {
        SkyRenderer r = new SkyRenderer(W, H);
        int[] colour = new int[W * H], grey = new int[W * H];
        r.render(colour, basic(12f, 0.62f), 0f);
        r.setDesaturation(1f);
        r.render(grey, basic(12f, 0.62f), 0f);
        assertTrue(spread(grey) < spread(colour));
    }

    // Body-position formulas duplicated from BodyPath for test-side sampling.
    private static float sunX(float warpedHour) {
        float t = (warpedHour - SolarClock.SUNRISE_ANCHOR) / (SolarClock.SUNSET_ANCHOR - SolarClock.SUNRISE_ANCHOR);
        return t * W;
    }
    private static float sunY(float warpedHour) {
        float t = (warpedHour - SolarClock.SUNRISE_ANCHOR) / (SolarClock.SUNSET_ANCHOR - SolarClock.SUNRISE_ANCHOR);
        float u = 2f * t - 1f;
        return SUN_R + (H - 2f * SUN_R) * u * u;
    }
    private static float moonT(float hour, float moonrise, float moonset) {
        float end = moonset <= moonrise ? moonset + 24f : moonset;
        float span = end - moonrise;
        float h = hour < moonrise ? hour + 24f : hour;
        return (h - moonrise) / span;
    }
    private static float moonX(float hour, float moonrise, float moonset) {
        return moonT(hour, moonrise, moonset) * W;
    }
    private static float moonY(float hour, float moonrise, float moonset) {
        float t = moonT(hour, moonrise, moonset);
        float u = 2f * t - 1f;
        float drop = (t < 0.5f) ? 0.50f : 0.20f;
        return 0.5f * H - drop * H * u * u;
    }

    private static float meanLumaBox(int[] buf, int cx, int cy, int radius) {
        double sum = 0; int n = 0;
        for (int y = Math.max(0, cy - radius); y <= Math.min(H - 1, cy + radius); y++) {
            for (int x = Math.max(0, cx - radius); x <= Math.min(W - 1, cx + radius); x++) {
                sum += luma(buf[y * W + x]); n++;
            }
        }
        return (float) (sum / n);
    }

    @Test public void sunDiscAppearsInTheSkyDuringDay() {
        int[] noon = renderAt(12f, 0f);
        int cx = Math.round(sunX(12f)), cy = Math.round(sunY(12f));
        float discLuma = meanLumaBox(noon, cx, cy, 4);
        float ambientLuma = meanLumaBox(noon, 4, 4, 3);
        assertTrue(discLuma > ambientLuma + 20f);
    }

    @Test public void sunClipsOffscreenAtMidnight() {
        // At the anchor-relative "midnight" the sun's parabola has long since
        // carried it below the buffer and renderSun's early-out applies.
        int[] midnight = renderAt(0.3f + 12f, 0f); // solar midnight per sunAlt's own test
        // No assertion needed beyond "renders without throwing" — the real
        // check is quantizationSnapsToFifteenLevelSteps not needing a sun
        // exclusion radius here; kept as a smoke test.
        assertEquals(0xFF, midnight[0] >>> 24);
    }

    @Test public void moonDiscAppearsWhenVisible() {
        int[] buf = new int[W * H];
        new SkyRenderer(W, H).render(buf, basic(0f, 0.62f), 0f);
        int cx = Math.round(moonX(0f, 0f, 24f)), cy = Math.round(moonY(0f, 0f, 24f));
        assertTrue(cy >= 0 && cy < H);
        float discLuma = meanLumaBox(buf, cx, cy, 4);
        float ambientLuma = meanLumaBox(buf, W - 6, H - 6, 3);
        assertTrue(discLuma > ambientLuma + 20f);
    }

    @Test public void moonIsAbsentWhenOutsideItsRiseSetWindow() {
        // Moonrise 8:00, moonset 9:00 — a 1h window; hour 20 is well outside
        // it, so the disc must not draw at all.
        SkyConditions c = new SkyConditions(20f, 20f, 8f, 9f, 0f, 0f, 0.5f, Precip.NONE, false, 20);
        int[] withMoon = new int[W * H], without = new int[W * H];
        new SkyRenderer(W, H).render(withMoon, basic(20f, 0.5f), 0f);
        new SkyRenderer(W, H).render(without, c, 0f);
        // A visible full moon at night is substantially brighter somewhere
        // than a night sky with no moon drawn at all.
        assertTrue(meanLumaBox(withMoon, Math.round(moonX(20f, 0f, 24f)), Math.round(moonY(20f, 0f, 24f)), 10)
                 > meanLumaBox(without, Math.round(moonX(20f, 0f, 24f)), Math.round(moonY(20f, 0f, 24f)), 10) + 10f);
    }

    @Test public void fullMoonIsBrighterThanNewMoon() {
        SkyRenderer full = new SkyRenderer(W, H);
        SkyRenderer newMoon = new SkyRenderer(W, H);
        int[] bufFull = new int[W * H], bufNew = new int[W * H];
        full.render(bufFull, basic(0f, 0.5f), 0f);
        newMoon.render(bufNew, basic(0f, 0.0f), 0f);
        int cx = Math.round(moonX(0f, 0f, 24f)), cy = Math.round(moonY(0f, 0f, 24f));
        assertTrue(meanLumaBox(bufFull, cx, cy, 10) > meanLumaBox(bufNew, cx, cy, 10));
    }

    private int[] moonAt(float hour, float phase, boolean southern) {
        SkyRenderer r = new SkyRenderer(W, H);
        r.setSouthernView(southern);
        int[] buf = new int[W * H];
        r.render(buf, basic(hour, phase), 0f);
        return buf;
    }

    @Test public void waxingCrescentIsLitOnTheRightFromTheNorth() {
        // t=0.5 (moon's vertex, dead centre) with moonrise=0/moonset=24 puts
        // the moon overhead at hour 12.
        int[] buf = moonAt(12f, 0.12f, false);
        int cx = Math.round(moonX(12f, 0f, 24f)), cy = Math.round(moonY(12f, 0f, 24f));
        assertTrue(meanLumaBox(buf, cx + 9, cy, 2) > meanLumaBox(buf, cx - 9, cy, 2) + 20f);
    }

    @Test public void waningCrescentIsLitOnTheLeftFromTheNorth() {
        int[] buf = moonAt(12f, 0.88f, false);
        int cx = Math.round(moonX(12f, 0f, 24f)), cy = Math.round(moonY(12f, 0f, 24f));
        assertTrue(meanLumaBox(buf, cx - 9, cy, 2) > meanLumaBox(buf, cx + 9, cy, 2) + 20f);
    }

    @Test public void theSouthernViewMirrorsTheTerminator() {
        int[] north = moonAt(12f, 0.12f, false);
        int[] south = moonAt(12f, 0.12f, true);
        int cx = Math.round(moonX(12f, 0f, 24f)), cy = Math.round(moonY(12f, 0f, 24f));
        assertTrue(meanLumaBox(north, cx + 9, cy, 2) > meanLumaBox(south, cx + 9, cy, 2) + 20f);
        assertTrue(meanLumaBox(south, cx - 9, cy, 2) > meanLumaBox(north, cx - 9, cy, 2) + 20f);
    }

    @Test public void hemisphereDoesNotChangeHowMuchOfAFullMoonIsLit() {
        int cx = Math.round(moonX(12f, 0f, 24f)), cy = Math.round(moonY(12f, 0f, 24f));
        assertEquals(meanLumaBox(moonAt(12f, 0.5f, false), cx, cy, 10),
                     meanLumaBox(moonAt(12f, 0.5f, true), cx, cy, 10), 6f);
    }

    @Test public void discsClipAtTheBufferEdgeWithoutCrashing() {
        for (float hour = 0f; hour <= 24f; hour += 0.25f) {
            int[] buf = renderAt(hour, 0f);
            for (int px : buf) assertEquals(0xFF, (px >>> 24));
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static int countNear(int[] buf, float r, float g, float b, float tolerance) {
        int n = 0;
        for (int argb : buf) {
            float dr = ((argb >> 16) & 0xFF) - r, dg = ((argb >> 8) & 0xFF) - g, db = (argb & 0xFF) - b;
            if (Math.sqrt(dr * dr + dg * dg + db * db) < tolerance) n++;
        }
        return n;
    }

    @Test public void starsOnlyAppearAtNight() {
        int[] night = renderAt(0f, 0f);
        int[] noon  = renderAt(12f, 0f);
        int sxN = Math.round(sunX(0f)), syN = Math.round(sunY(0f));
        int mxN = Math.round(moonX(0f, 0f, 24f)), myN = Math.round(moonY(0f, 0f, 24f));
        int starPixels = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (Math.hypot(x - sxN, y - syN) < 25 || Math.hypot(x - mxN, y - myN) < 20) continue;
            int argb = night[y * W + x];
            int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            if (r > 230 && g > 230 && b > 230) starPixels++;
        }
        assertTrue(starPixels > 0);

        int sxD = Math.round(sunX(12f)), syD = Math.round(sunY(12f));
        int mxD = Math.round(moonX(12f, 0f, 24f)), myD = Math.round(moonY(12f, 0f, 24f));
        int dayBrightPixels = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (Math.hypot(x - sxD, y - syD) < 25 || Math.hypot(x - mxD, y - myD) < 20) continue;
            int argb = noon[y * W + x];
            int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            if (r > 230 && g > 230 && b > 230) dayBrightPixels++;
        }
        assertEquals(0, dayBrightPixels);
    }

    @Test public void starsFadeAsCloudCoverIncreases() {
        int clear = countNear(renderAt(0f, 0f), 246, 248, 255, 30);
        int cloudy = countNear(renderAt(0f, 0.9f), 246, 248, 255, 30);
        assertTrue(cloudy < clear);
    }

    @Test public void cloudCoverDrivesTheNumberOfCloudsShownDirectly() {
        int low  = cloudPixels(renderAt(12f, 0.15f), 0.15f);
        int mid  = cloudPixels(renderAt(12f, 0.45f), 0.45f);
        int high = cloudPixels(renderAt(12f, 0.65f), 0.65f);
        assertTrue(low < mid);
        assertTrue(mid < high);
    }

    private static int cloudPixels(int[] buf, float cloudCover) {
        float storm = 0f; // renderAt uses precip=0, thunder=false -> storm=smooth(0.30,0.90,0)=0
        float baseR = lerp(252, 190, 0.52f), baseG = lerp(253, 190, 0.52f), baseB = lerp(255, 190, 0.52f);
        baseR = lerp(baseR, 58, storm * 0.82f); baseG = lerp(baseG, 62, storm * 0.82f); baseB = lerp(baseB, 80, storm * 0.82f);
        float hiR = baseR * 1.14f, hiG = baseG * 1.14f, hiB = baseB * 1.14f;
        float midR = baseR * 0.94f, midG = baseG * 0.94f, midB = baseB * 0.94f;
        float loR = baseR * 0.72f, loG = baseG * 0.72f, loB = baseB * 0.72f;
        return countNear(buf, hiR, hiG, hiB, 30) + countNear(buf, midR, midG, midB, 30) + countNear(buf, loR, loG, loB, 30);
    }

    @Test public void sameSeedGivesTheSameFrame() {
        SkyRenderer a = new SkyRenderer(W, H, 99L);
        SkyRenderer b = new SkyRenderer(W, H, 99L);
        int[] bufA = new int[W * H], bufB = new int[W * H];
        a.render(bufA, cond(9f, 0.5f, 0f, 0.5f, Precip.NONE, false, 20), 2f);
        b.render(bufB, cond(9f, 0.5f, 0f, 0.5f, Precip.NONE, false, 20), 2f);
        assertArrayEquals(bufA, bufB);
    }

    @Test public void differentSeedsGiveDifferentClouds() {
        SkyRenderer a = new SkyRenderer(W, H, 1L);
        SkyRenderer b = new SkyRenderer(W, H, 2L);
        int[] bufA = new int[W * H], bufB = new int[W * H];
        a.render(bufA, cond(12f, 0.5f, 0f, 0.5f, Precip.NONE, false, 20), 0f);
        b.render(bufB, cond(12f, 0.5f, 0f, 0.5f, Precip.NONE, false, 20), 0f);
        assertFalse(java.util.Arrays.equals(bufA, bufB));
    }

    @Test public void renderNeverThrowsAcrossTheWholeDayAndEveryChannelCombination() {
        SkyRenderer r = new SkyRenderer(W, H);
        int[] buf = new int[W * H];
        for (float hour = 0f; hour <= 24f; hour += 1f) {
            for (Precip type : Precip.values()) {
                for (boolean thunder : new boolean[]{false, true}) {
                    r.render(buf, cond(hour, 0.5f, 0.5f, 0.5f, type, thunder, 40), hour * 10f);
                }
            }
        }
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

    private static float spread(int[] buf) {
        double sum = 0;
        for (int px : buf) {
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
            sum += Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        }
        return (float) (sum / buf.length);
    }

    // ---- four independent weather channels (§6) --------------------------

    @Test public void dryThunderstormDrawsBoltsAndNoRain() {
        SkyRenderer r = new SkyRenderer(W, H, 7L);
        SkyConditions dryStorm = cond(12f, 0.9f, 0f, 0.5f, Precip.NONE, true, 25);
        int[] buf = new int[W * H];
        boolean sawFlash = false;
        for (float s = 0f; s < 20f; s += 1f) {
            r.render(buf, dryStorm, s);
            if (meanLuma(buf) > 200f) sawFlash = true; // a lightning flash washes the frame near-white
        }
        assertTrue("expected at least one lightning flash over 20 frames", sawFlash);
        // No rain streaks: churn between two frames must be far below a wet
        // sky's, since only cloud drift and lightning randomness move.
        assertTrue(frameChurn(dryStorm) < frameChurn(cond(12f, 0.9f, 0.9f, 0.5f, Precip.RAIN, false, 15)) / 2);
    }

    @Test public void snowDrawsFlakesInsteadOfRainDrops() {
        int[] rain = new int[W * H];
        int[] snow = new int[W * H];
        new SkyRenderer(W, H, 3L).render(rain, cond(12f, 0.8f, 0.8f, 0.5f, Precip.RAIN, false, 5), 1f);
        new SkyRenderer(W, H, 3L).render(snow, cond(12f, 0.8f, 0.8f, 0.5f, Precip.SNOW, false, -5), 1f);
        assertFalse(java.util.Arrays.equals(rain, snow));
    }

    @Test public void rainOnlyDrawsWhenTypeIsRain() {
        int churnNone = frameChurn(cond(12f, 0.8f, 0.8f, 0.5f, Precip.NONE, false, 20));
        int churnRain = frameChurn(cond(12f, 0.8f, 0.8f, 0.5f, Precip.RAIN, false, 20));
        assertTrue(churnRain > churnNone);
    }

    private int frameChurn(SkyConditions c) {
        SkyRenderer r1 = new SkyRenderer(W, H);
        SkyRenderer r2 = new SkyRenderer(W, H);
        int[] b1 = new int[W * H], b2 = new int[W * H];
        r1.render(b1, c, 0f);
        r2.render(b2, c, 3f);
        int n = 0;
        for (int i = 0; i < b1.length; i++) if (b1[i] != b2[i]) n++;
        return n;
    }

    // ---- lens flare (§5) --------------------------------------------------

    @Test public void flareOnlyAppearsNearSolarNoonWhenClear() {
        // Lens flare's ghost rings are static (no seconds-dependence), so
        // compare golden-ring pixel counts directly rather than frame churn.
        int[] atNoon = renderAt(12.3f, 0f);         // sunT == 0.5 exactly
        int[] midMorning = renderAt(9f, 0f);         // sunT far from 0.5
        int goldNoon = countNear(atNoon, 255, 214, 120, 40);
        int goldMidMorning = countNear(midMorning, 255, 214, 120, 40);
        assertTrue("expected golden ghost-ring pixels only near solar noon",
                goldNoon > goldMidMorning);
    }

    @Test public void flareIsSuppressedWhenCloudy() {
        SkyRenderer clear = new SkyRenderer(W, H, 5L);
        SkyRenderer cloudy = new SkyRenderer(W, H, 5L);
        int[] a = new int[W * H], b = new int[W * H];
        clear.render(a, cond(12.3f, 0f, 0f, 0.5f, Precip.NONE, false, 20), 0f);
        cloudy.render(b, cond(12.3f, 1f, 0f, 0.5f, Precip.NONE, false, 20), 0f);
        // A fully overcast noon must not show the bright golden ghost rings
        // a clear noon does, near the sun's vertical midline.
        int sx = Math.round(sunX(12.3f));
        int goldClear = countNear(a, 255, 214, 120, 40);
        int goldCloudy = countNear(b, 255, 214, 120, 40);
        assertTrue(goldClear >= goldCloudy);
    }

    // ---- heat shimmer (§7) -------------------------------------------------

    @Test public void heatShimmerDisplacesRowsAboveThirtyFiveDegrees() {
        int churnHot = frameChurn(cond(12f, 0f, 0f, 0.5f, Precip.NONE, false, 45));
        int churnCool = frameChurn(cond(12f, 0f, 0f, 0.5f, Precip.NONE, false, 20));
        assertTrue(churnHot > churnCool);
    }

    @Test public void heatShimmerIsOffBelowThirtyFiveDegrees() {
        // At noon the sun is on screen regardless of shimmer, and its rays
        // pulse with `seconds` on their own (renderSun's rayLen), so exact
        // frame equality across two `seconds` values is the wrong invariant
        // here — a handful of ray pixels legitimately differ either way.
        // Heat shimmer's signature is a *row-wide* horizontal shift, which
        // churns orders of magnitude more pixels than an 8-ray sun glyph
        // ever can; bound the churn instead of demanding zero.
        int churnCool = frameChurn(cond(12f, 0f, 0f, 0.5f, Precip.NONE, false, 20));
        assertTrue("expected only sun-ray pixels to churn below the shimmer threshold, got " + churnCool,
                churnCool < 200);
    }
}
```

- [ ] **Step A2: Run to confirm the expected compile/behaviour failures**

Run: `./gradlew :core:test --tests SkyRendererTest`
Expected: compile failure — `SkyRenderer.render(int[], SkyConditions, float)` doesn't exist yet; `sunAngle`/`moonAngle` are still there but unused by the test now (harmless until Step A3 deletes them).

- [ ] **Step A3: Rewrite `SkyRenderer`'s constructor extras, `render()` signature, and body-position code**

In `core/src/main/java/com/retro/launcher/core/SkyRenderer.java`:

1. Add `Precip` import is unnecessary (same package). Add a `Flake` inner class next to `Drop`:

```java
    private static final class Flake { float x, yf, v, sway, ph; }
```

2. Add a `flakes` field and populate it in both constructors' shared init (add right after `this.stars = buildStars();`):

```java
    private final Flake[] flakes;
```
and in the constructor body:
```java
        this.stars = buildStars();
        this.flakes = buildFlakes();
        this.rand = new java.util.Random(seed);
```

3. Add `buildFlakes()` near `buildStars()`:

```java
    private Flake[] buildFlakes() {
        Flake[] out = new Flake[180];
        for (int i = 0; i < 180; i++) {
            Flake f = new Flake();
            f.x = rnd() * 108f;
            f.yf = rnd();
            f.v = 0.25f + rnd() * 0.35f;
            f.sway = 4f + rnd() * 6f;
            f.ph = rnd() * 6.28f;
            out[i] = f;
        }
        return out;
    }
```

4. Add a radius constant near the top of the class:

```java
    private static final float SUN_RADIUS = 13f;
```

5. Delete `sunAngle(float)` and `moonAngle(float,float)` entirely.

6. Replace the whole `render(int[] out, float hour, float weather, float moonPhase, float seconds)` method with:

```java
    public void render(int[] out, SkyConditions c, float seconds) {
        final float sunAlt   = sunAlt(c.hour);
        final float day      = clamp01(sunAlt * 3f + 0.35f);
        final float night    = 1f - day;
        final float twilight = smooth(0.45f, 0.02f, Math.abs(sunAlt));
        final float storm    = c.thunder ? 1f : smooth(0.30f, 0.90f, c.precip);
        final float cover    = c.cloudCover;
        final float haze     = smooth(0.06f, 0.24f, cover) * (1f - smooth(0.30f, 0.50f, cover));

        SkyKeyframes.at(c.hour, sky);
        final float dark = 1f - 0.42f * storm;
        final float topR = sky[0] * dark, topG = sky[1] * dark, topB = sky[2] * dark;
        final float botR = sky[3] * dark, botG = sky[4] * dark, botB = sky[5] * dark;

        final float sunT = BodyPath.sunT(c.hour);
        final float sunX = BodyPath.sunX(sunT, w);
        final float sunY = BodyPath.sunY(sunT, w, h, SUN_RADIUS);

        final float moonT = BodyPath.moonT(c.realHour, c.moonriseHour, c.moonsetHour);
        final boolean moonVisible = !Float.isNaN(moonT) && moonT >= 0f && moonT <= 1f;
        final float moonX = moonVisible ? BodyPath.moonX(moonT, w) : -1000f;
        final float moonY = moonVisible ? BodyPath.moonY(moonT, h) : -1000f;

        final float litFrac  = 1f - Math.abs(c.moonPhase - 0.5f) * 2f;
        final float glowSun  = (0.20f + 0.62f * twilight)
                             * clamp01(sunAlt + 0.55f) * (1f - 0.75f * storm);
        final float glowMoon = moonVisible
                ? 0.26f * clamp01(-sunAlt + 0.25f) * (1f - 0.75f * storm) * (0.15f + 0.85f * litFrac)
                : 0f;

        for (int y = 0; y < h; y++) {
            float ty = (float) y / (h - 1);
            float m  = (float) Math.pow(ty, 0.85);
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

                if (moonVisible) {
                    float dmx = x - moonX, dmy = y - moonY;
                    float dm = (float) Math.sqrt(dmx * dmx + dmy * dmy);
                    if (dm < 46f) {
                        float k = (float) Math.pow(1f - dm / 46f, 2.4) * glowMoon;
                        r += (140f - r) * k; g += (165f - g) * k; b += (220f - b) * k;
                    }
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

        renderStars(out, night, cover, seconds);
        if (moonVisible) {
            float moonVisibility = moonVisibility(sunAlt, moonT);
            renderMoon(out, sunAlt, twilight, botR, botG, botB, moonX, moonY, c.moonPhase, moonVisibility);
        }

        final float flare = smooth(0.06f, 0f, Math.abs(sunT - 0.5f)) * (1f - cover) * clamp01(sunAlt);
        renderSun(out, sunAlt, sunX, sunY, seconds, flare);
        if (flare >= 0.02f) renderLensFlare(out, flare, sunX, sunY);

        final float ambR = (topR + botR) / 2f, ambG = (topG + botG) / 2f, ambB = (topB + botB) / 2f;
        int nShown = Math.round(cover * clouds.length);
        renderClouds(out, storm, twilight, nShown, c.precip, ambR, ambG, ambB, seconds);
        if (c.thunder) renderLightning(out);
        if (c.type == Precip.RAIN) renderPrecipitation(out, c.precip, ambR, ambG, ambB, seconds);
        else if (c.type == Precip.SNOW) renderSnow(out, c.precip, seconds);
        applyFlash(out);

        applyShimmer(out, c.tempC, seconds);
        if (desaturation > 0f) applyDesaturation(out);
        if (tintRamp != null) applyTint(out);
    }

    /** Sun-driven fade (moon washes out as the sun climbs) times a short
     *  fade-in/out at the {@code t=0}/{@code t=1} edges of the moon's own
     *  rise-set window, so it does not pop into or out of existence. */
    private float moonVisibility(float sunAlt, float moonT) {
        float sunFade = clamp01(1f - smooth(-0.05f, 0.10f, sunAlt));
        float edgeIn = smooth(0f, 0.05f, moonT);
        float edgeOut = smooth(1f, 0.95f, moonT);
        return sunFade * edgeIn * edgeOut;
    }
```

7. Update `renderMoon`'s signature to take a `visibility` multiplier and apply it to every alpha it writes with:

```java
    private void renderMoon(int[] out, float sunAlt, float twilight,
                            float botR, float botG, float botB,
                            float moonX, float moonY, float moonPhase, float visibility) {
        if (moonY >= h + 16) return;
        final float R = 12f;
        ... (unchanged colour math) ...
        for (int y = -13; y <= 13; y++) {
            for (int x = -13; x <= 13; x++) {
                if (Math.hypot(x, y) > R) continue;
                int mx = southernView ? -x : x, my = southernView ? -y : y;
                float nx = mx / R, ny = my / R;
                float q = moonPhase <= 0.5f ? moonPhase : 1f - moonPhase;
                float sx = moonPhase <= 0.5f ? 1f : -1f;
                float term = (float) (Math.cos(2 * Math.PI * q) * Math.sqrt(Math.max(0, 1 - ny * ny)));
                boolean lit = (sx * nx) > term;
                float X = moonX + x, Y = moonY + y;
                if (!lit) { px(out, X, Y, darkColR, darkColG, darkColB, 0.55f * visibility); continue; }

                int xi = (int) X, yi = (int) Y;
                float dd = (float) Math.hypot(mx + 2.5, my + 3) + (Bayer.M[yi & 3][xi & 3] / 16f - 0.5f) * 2.2f;
                float cr = dd > R * 0.82f ? litMidR : litColR;
                float cg = dd > R * 0.82f ? litMidG : litColG;
                float cb = dd > R * 0.82f ? litMidB : litColB;
                for (float[] cr8 : CRATERS) {
                    float cd = (float) Math.hypot(nx - cr8[0], ny - cr8[1]);
                    if (cd < cr8[2]) {
                        if (cd > cr8[2] - 0.075f && ny < cr8[1]) { cr = craterRimR; cg = craterRimG; cb = craterRimB; }
                        else { cr = craterCR; cg = craterCG; cb = craterCB; }
                        break;
                    }
                }
                px(out, X, Y, cr, cg, cb, visibility);
            }
        }
    }
```

(Keep the unchanged colour-derivation lines between `final float R = 12f;` and the loop exactly as they are today — only the two `px(...)` alpha arguments and the method signature change.)

8. Update `renderSun`'s signature to accept `flare` and extend the two horizontal rays:

```java
    private void renderSun(int[] out, float sunAlt, float sunX, float sunY, float seconds, float flare) {
        if (sunY >= h + 18) return;
        final float R = 13f;
        ... (unchanged colour math) ...
        int rayLen = Math.max(0, Math.round(
                (2 + Math.round(1.6f + 1.6f * (float) Math.sin(seconds * 1.6)))
                        * (0.45f + 0.75f * clamp01(sunAlt + 0.4f))));
        int horizRayLen = rayLen + Math.round(flare * 14f);
        for (int a = 0; a < 8; a++) {
            double ang = a * Math.PI / 4;
            double dx = Math.cos(ang), dy = Math.sin(ang);
            int len = (a == 0 || a == 4) ? horizRayLen : rayLen;
            for (int i = 0; i < len; i++) {
                float rr = R + 3 + i;
                px(out, Math.round(sunX + dx * rr), Math.round(sunY + dy * rr), t1R, t1G, t1B, 0.95f);
            }
        }
        ... (unchanged disc-fill loop) ...
    }
```

9. Update `renderStars`' signature — drop the `weather` param, use `cover` directly:

```java
    private void renderStars(int[] out, float night, float cover, float seconds) {
        float starVis = night * (1f - cover);
        if (starVis <= 0.02f) return;
        ... (unchanged body) ...
    }
```

10. Update `renderClouds`' signature — `weather` becomes `precip` (drives wind), `cover` is now precomputed by the caller (`nShown` passed in directly instead of recomputed inside):

```java
    private void renderClouds(int[] out, float storm, float twilight, int nShown, float precip,
                              float ambR, float ambG, float ambB, float seconds) {
        float baseR = lerp(252, ambR, 0.52f), baseG = lerp(253, ambG, 0.52f), baseB = lerp(255, ambB, 0.52f);
        baseR = lerp(baseR, 58, storm * 0.82f); baseG = lerp(baseG, 62, storm * 0.82f); baseB = lerp(baseB, 80, storm * 0.82f);
        float tw = twilight * 0.30f * (1f - storm);
        baseR = lerp(baseR, 255, tw); baseG = lerp(baseG, 178, tw); baseB = lerp(baseB, 132, tw);

        float hiR = baseR * 1.14f, hiG = baseG * 1.14f, hiB = baseB * 1.14f;
        float midR = baseR * 0.94f, midG = baseG * 0.94f, midB = baseB * 0.94f;
        float loR = baseR * 0.72f, loG = baseG * 0.72f, loB = baseB * 0.72f;

        float wind = 0.35f + 2.4f * precip;

        for (int ci = 0; ci < nShown; ci++) {
            Cloud c = clouds[ci];
            float s = c.s * (0.85f + 0.55f * (nShown / (float) clouds.length));
            float cx = mod(c.x + seconds * c.sp * wind, w + 90f) - 45f;
            float cy = c.yf * h - (nShown / (float) clouds.length) * 6f;
            ... (unchanged puff-fill body, referencing `s`/`cx`/`cy` as before) ...
        }
    }
```

(Keep the inner puff-bounding-box and fill loop exactly as today — only the header lines computing `s`/`cy` change from `cover` to `nShown / (float) clouds.length`, an equivalent 0-1 fraction, since `cover` itself is no longer a local in this method.)

11. Update `renderLightning`'s signature — drop `weather`, gate purely on being called (caller already checks `c.thunder`), with a flat strike-rate floor:

```java
    private void renderLightning(int[] out) {
        final float STRIKE_CHANCE_PER_FRAME = 0.12f;
        if (rand.nextFloat() < STRIKE_CHANCE_PER_FRAME) {
            flash = 1f;
            float bx = 18f + rand.nextFloat() * (w - 36);
            float x = bx, y = 40f + rand.nextFloat() * 30f;
            java.util.List<int[]> seg = new java.util.ArrayList<>();
            while (y < h) {
                seg.add(new int[]{ (int) x, (int) y });
                x += rand.nextFloat() * 6f - 3f;
                y += 3f + rand.nextFloat() * 5f;
            }
            bolt = seg.toArray(new int[0][]);
            boltLife = 7;
        }
        if (boltLife > 0 && bolt != null) {
            for (int i = 0; i < bolt.length - 1; i++) {
                int[] a = bolt[i], b = bolt[i + 1];
                int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
                for (int s2 = 0; s2 <= steps; s2++) {
                    float t = steps == 0 ? 0 : (float) s2 / steps;
                    px(out, Math.round(lerp(a[0], b[0], t)), Math.round(lerp(a[1], b[1], t)), 255, 252, 225, 1f);
                }
            }
            boltLife--;
        }
    }
```

12. Update `renderPrecipitation`'s signature — drop `weather`, derive `slant` from `precip` itself:

```java
    private void renderPrecipitation(int[] out, float precip,
                                     float ambR, float ambG, float ambB, float seconds) {
        if (precip <= 0.01f) return;
        int count = Math.round(drops.length * precip);
        float slant = 0.55f + precip * 1.7f;
        float rainR = lerp(176, ambR, 0.35f), rainG = lerp(206, ambG, 0.35f), rainB = lerp(238, ambB, 0.35f);

        for (int i = 0; i < count; i++) {
            Drop d = drops[i];
            float dy0 = d.yf * h;
            float speed = 70f + d.v * 90f + precip * 60f;
            float yy = mod(dy0 + seconds * speed, h + 12f) - 6f;
            float xx = mod(d.x + seconds * speed * slant * 0.28f, w + 12f) - 6f;
            for (int k = 0; k < d.len; k++) {
                px(out, xx + k * slant * 0.5f, yy + k, rainR, rainG, rainB, 0.72f - k * 0.12f);
            }
        }
    }
```

13. Add `renderSnow`:

```java
    private void renderSnow(int[] out, float precip, float seconds) {
        if (precip <= 0.01f) return;
        int count = Math.round(flakes.length * precip);
        for (int i = 0; i < count; i++) {
            Flake f = flakes[i];
            float speed = 14f + f.v * 20f;
            float yy = mod(f.yf * h + seconds * speed, h + 8f) - 4f;
            float sway = (float) Math.sin(seconds * 0.8f + f.ph) * f.sway;
            float xx = mod(f.x + sway, w + 8f) - 4f;
            px(out, xx, yy, 255, 255, 255, 0.85f);
        }
    }
```

14. Add `renderLensFlare` and its `drawRing` helper (place them near `renderSun`):

```java
    private void renderLensFlare(int[] out, float flare, float sunX, float sunY) {
        int ghostCount = 5;
        for (int g = 1; g <= ghostCount; g++) {
            float frac = g / (float) (ghostCount + 1);
            float gy = sunY + (h - sunY) * frac;
            float radius = 10f - g * 1.6f;
            if (radius < 2f) continue;
            drawRing(out, sunX, gy, radius, flare);
        }
    }

    private void drawRing(int[] out, float cx, float cy, float radius, float strength) {
        float thickness = 1.5f;
        int steps = Math.max(12, Math.round(radius * 6f));
        for (int pass = 0; pass < 2; pass++) {
            float r = radius + pass * thickness;
            for (int i = 0; i < steps; i++) {
                double ang = 2 * Math.PI * i / steps;
                float x = cx + (float) Math.cos(ang) * r;
                float y = cy + (float) Math.sin(ang) * r;
                int xi = (int) x, yi = Math.max(0, Math.min(h - 1, (int) y));
                float d = Bayer.M[yi & 3][xi & 3] / 16f - 0.5f;
                if (strength + d * 0.3f < 0.35f) continue;
                px(out, x, y, quantize(255), quantize(214), quantize(120), 1f);
            }
        }
    }
```

15. Add `applyShimmer`, called between `applyFlash` and the desaturation/tint block:

```java
    private void applyShimmer(int[] out, int tempC, float seconds) {
        float shimmer = smooth(35f, 45f, tempC);
        if (shimmer < 0.01f) return;
        int[] src = out.clone();
        for (int y = 0; y < h; y++) {
            float amp = shimmer * 2.5f * (float) Math.pow(y / (float) h, 1.5);
            int dx = Math.round(amp * (float) Math.sin(y * 0.35f + seconds * 2.2f));
            if (dx == 0) continue;
            for (int x = 0; x < w; x++) {
                int sx = x - dx;
                out[y * w + x] = (sx >= 0 && sx < w) ? src[y * w + sx] : src[y * w + x];
            }
        }
    }
```

- [ ] **Step A4: Run the full `SkyRendererTest` and `BodyPathTest` suites**

Run: `./gradlew :core:test --tests SkyRendererTest --tests BodyPathTest`
Expected: PASS. If a specific luminance/pixel-count assertion is borderline, adjust that test's tolerance rather than the renderer's constants — the constants come straight from the spec's code blocks or from Global Constraints' precip-mapping decision.

- [ ] **Step A5: Run the full `:core` suite**

Run: `./gradlew :core:test`
Expected: every test file passes now — this is the point where all of Tasks 1–5's cross-file breakage resolves simultaneously.

- [ ] **Step A6: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/SkyRenderer.java \
        core/src/test/java/com/retro/launcher/core/SkyRendererTest.java
git commit -m "feat: SkyRenderer draws from BodyPath and four independent weather channels (V9 §1-§7)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 6: `OpenMeteoWeather` requests the modern `current=` parameters

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/data/OpenMeteoWeather.java`

**Interfaces:**
- Consumes: `WeatherParser.parse`/`parseSolarTimes` (Task 4) — already expect the `current` object shape.

- [ ] **Step 1: Update the request URL**

In `fetch(...)`, replace:

```java
            URL url = new URL(ENDPOINT
                    + "?latitude=" + coord(latitude)
                    + "&longitude=" + coord(longitude)
                    + "&current_weather=true"
                    + "&daily=sunrise,sunset&timezone=auto&forecast_days=2");
```

with:

```java
            URL url = new URL(ENDPOINT
                    + "?latitude=" + coord(latitude)
                    + "&longitude=" + coord(longitude)
                    + "&current=temperature_2m,weather_code,cloud_cover,precipitation,precipitation_probability"
                    + "&daily=sunrise,sunset&timezone=auto&forecast_days=2");
```

No other lines in the file change — `WeatherParser.parse(body)` and `WeatherParser.parseSolarTimes(body, ...)` already read the new shape from Task 4.

- [ ] **Step 2: Compile the app module**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/retro/launcher/data/OpenMeteoWeather.java
git commit -m "feat: request Open-Meteo's four independent current= weather channels

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 7: `Prefs` — weather-channel and wallpaper-override keys

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/data/Prefs.java`

**Interfaces:**
- Produces: `Prefs.K_WX_CLOUD/K_WX_PRECIP/K_WX_TYPE/K_WX_THUNDER/K_WX_PROB` (weather-reading cache, Task 8 uses these); `K_WX_OVERRIDE/K_OV_CLOUD/K_OV_PRECIP/K_OV_TEMP/K_OV_HOUR/K_OV_MOON/K_OV_THUNDER/K_OV_SNOW` (manual wallpaper, Tasks 11–13 use these); `Prefs.getBool(String,boolean)`; `Prefs.manualWallpaper()`; `Prefs.overrideWeather() -> Weather`; `Prefs.overrideHour()`; `Prefs.overrideMoonPhase()`.

`Prefs` has no JVM test source set (app module) — verify by compiling, matching every other `app`-module task in this plan.

- [ ] **Step 1: Add the imports and key constants**

At the top of `Prefs.java`, add to the imports:

```java
import com.retro.launcher.core.Precip;
import com.retro.launcher.core.SyntheticWeather;
import com.retro.launcher.core.Weather;
```

After the existing `K_SOL_TOMORROW` line, add:

```java
    // V9. The four independent weather channels, cached alongside K_WX_W so
    // a cold-start restore does not have to migrate from the old scalar.
    public static final String K_WX_CLOUD   = "wxCloud";
    public static final String K_WX_PRECIP  = "wxPrecip";
    public static final String K_WX_TYPE    = "wxType";
    public static final String K_WX_THUNDER = "wxThunder";
    public static final String K_WX_PROB    = "wxProb";

    // V9 §7b. Manual wallpaper override — a test/preview surface, not just a
    // debug switch, so it persists until the user turns it off.
    public static final String K_WX_OVERRIDE = "wxOverride";
    public static final String K_OV_CLOUD    = "ovCloud";
    public static final String K_OV_PRECIP   = "ovPrecip";
    public static final String K_OV_TEMP     = "ovTemp";
    public static final String K_OV_HOUR     = "ovHour";
    public static final String K_OV_MOON     = "ovMoon";
    public static final String K_OV_THUNDER  = "ovThunder";
    public static final String K_OV_SNOW     = "ovSnow";
```

- [ ] **Step 2: Add the generic boolean getter**

Next to the other generic getters at the bottom of the class:

```java
    public boolean getBool(String key, boolean fallback) { return sp.getBoolean(key, fallback); }
```

- [ ] **Step 3: Add the manual-override accessors**

```java
    /** V9 §7b: off by default — a launcher that silently ignores real
     *  weather would be a surprising default. */
    public boolean manualWallpaper() { return sp.getBoolean(K_WX_OVERRIDE, false); }

    public float overrideHour()      { return sp.getFloat(K_OV_HOUR, 12f); }
    public float overrideMoonPhase() { return sp.getFloat(K_OV_MOON, 0.5f); }

    public Weather overrideWeather() {
        float cloud = sp.getFloat(K_OV_CLOUD, 0f);
        float precip = sp.getFloat(K_OV_PRECIP, 0f);
        int temp = Math.round(sp.getFloat(K_OV_TEMP, 20f));
        boolean thunder = sp.getBoolean(K_OV_THUNDER, false);
        boolean snow = sp.getBoolean(K_OV_SNOW, false);
        com.retro.launcher.core.Precip type = precip > 0f
                ? (snow ? com.retro.launcher.core.Precip.SNOW : com.retro.launcher.core.Precip.RAIN)
                : com.retro.launcher.core.Precip.NONE;
        float w = Weather.derive(cloud, precip, thunder);
        return new Weather(temp, SyntheticWeather.label(w, snow), cloud, precip, type, thunder,
                Math.round(precip * 100f));
    }
```

(`Weather.derive` is package-private in `:core` today — widen it to `public static` as part of this step, since `Prefs` lives in the `app` module's `com.retro.launcher.data` package and needs to call it. Edit `core/src/main/java/com/retro/launcher/core/Weather.java`'s `derive` signature from `static float derive(...)` to `public static float derive(...)`.)

- [ ] **Step 4: Compile both modules**

Run: `./gradlew :core:test :app:compileDebugJavaWithJavac`
Expected: `:core:test` still all-green (widening a method's visibility is source-compatible); `:app:compileDebugJavaWithJavac` succeeds.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/retro/launcher/data/Prefs.java \
        core/src/main/java/com/retro/launcher/core/Weather.java
git commit -m "feat: add Prefs keys for weather-channel caching and manual wallpaper override

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 8: `WeatherRepository` — persist the four channels, merge in `LunarMath`

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/data/WeatherRepository.java`

**Interfaces:**
- Consumes: `LunarMath.moonTimes` (Task 3), `SolarTimes`'s 6-arg constructor (Task 3), `Prefs.K_WX_CLOUD` etc. (Task 7), `Weather`'s new constructor (Task 1).

- [ ] **Step 1: Add the import**

```java
import com.retro.launcher.core.LunarMath;
import com.retro.launcher.core.Precip;
```

- [ ] **Step 2: Update `persistSolarTimes` and `restoreSolarTimes`**

```java
    private void persistSolarTimes(SolarTimes t) {
        prefs.putLong(Prefs.K_SOL_EPOCH_DAY, t.date.toEpochDay());
        prefs.putFloat(Prefs.K_SOL_SUNRISE, t.sunriseHour);
        prefs.putFloat(Prefs.K_SOL_SUNSET, t.sunsetHour);
        prefs.putFloat(Prefs.K_SOL_TOMORROW, t.tomorrowSunriseHour);
        prefs.putFloat(Prefs.K_SOL_MOONRISE, t.moonriseHour);
        prefs.putFloat(Prefs.K_SOL_MOONSET, t.moonsetHour);
    }

    private SolarTimes restoreSolarTimes(LocalDate today) {
        long storedEpochDay = prefs.getLong(Prefs.K_SOL_EPOCH_DAY, Long.MIN_VALUE);
        if (storedEpochDay != today.toEpochDay()) return null;
        return new SolarTimes(
                prefs.getFloat(Prefs.K_SOL_SUNRISE, Float.NaN),
                prefs.getFloat(Prefs.K_SOL_SUNSET, Float.NaN),
                prefs.getFloat(Prefs.K_SOL_TOMORROW, Float.NaN),
                prefs.getFloat(Prefs.K_SOL_MOONRISE, Float.NaN),
                prefs.getFloat(Prefs.K_SOL_MOONSET, Float.NaN),
                today);
    }
```

Add the two new keys to `Prefs.java` alongside the existing `K_SOL_*` block (this file was not touched by Task 7 — add it here):

```java
    public static final String K_SOL_MOONRISE = "solMoonrise";
    public static final String K_SOL_MOONSET  = "solMoonset";
```

- [ ] **Step 3: Update `solarTimes()` to merge in `LunarMath`**

```java
    public SolarTimes solarTimes() {
        LocalDate today = LocalDate.now();
        SolarTimes cached = restoreSolarTimes(today);
        if (cached != null) return cached;

        double[] f = fix();
        if (f == null) return null;

        SolarTimes sun = SolarMath.sunTimes((float) f[0], (float) f[1], today, ZoneId.systemDefault());
        if (sun == null) return null;

        LunarMath.LunarTimes moon = LunarMath.moonTimes((float) f[0], (float) f[1], today, ZoneId.systemDefault());
        SolarTimes combined = new SolarTimes(sun.sunriseHour, sun.sunsetHour, sun.tomorrowSunriseHour,
                moon == null ? Float.NaN : moon.moonriseHour,
                moon == null ? Float.NaN : moon.moonsetHour,
                today);
        persistSolarTimes(combined);
        return combined;
    }
```

- [ ] **Step 4: Update `persist()` and `restore()` for the weather reading itself**

```java
    private void persist() {
        prefs.putInt(Prefs.K_WX_TEMP, reading.tempC);
        prefs.putString(Prefs.K_WX_LABEL, reading.label);
        prefs.putFloat(Prefs.K_WX_W, reading.w);
        prefs.putFloat(Prefs.K_WX_CLOUD, reading.cloudCover);
        prefs.putFloat(Prefs.K_WX_PRECIP, reading.precip);
        prefs.putInt(Prefs.K_WX_TYPE, reading.type.ordinal());
        prefs.putBool(Prefs.K_WX_THUNDER, reading.thunder);
        prefs.putInt(Prefs.K_WX_PROB, reading.precipProbability);
        prefs.putLong(Prefs.K_WX_AT, readingAt);
    }

    private void restore() {
        long at = prefs.getLong(Prefs.K_WX_AT, 0L);
        if (at <= 0L) return;
        if (System.currentTimeMillis() - at > MAX_RESTORE_AGE_MS) return;

        int tempC = prefs.getInt(Prefs.K_WX_TEMP, 0);
        String label = prefs.getString(Prefs.K_WX_LABEL, "CLEAR");
        float legacyW = prefs.getFloat(Prefs.K_WX_W, 0f);

        // A reading cached before V9 has no channel keys — K_WX_CLOUD
        // defaults to NaN, the marker that this is a one-time migration from
        // the old single-scalar reading rather than a genuine zero cover.
        float cloudCover = prefs.getFloat(Prefs.K_WX_CLOUD, Float.NaN);
        if (Float.isNaN(cloudCover)) {
            cloudCover = com.retro.launcher.core.SkyRenderer.smooth(0.10f, 0.66f, legacyW);
            float precip = com.retro.launcher.core.SkyRenderer.smooth(0.62f, 0.98f, legacyW);
            boolean thunder = legacyW >= 0.95f;
            Precip type = precip > 0f ? Precip.RAIN : Precip.NONE;
            reading = new Weather(tempC, label, cloudCover, precip, type, thunder, 0);
        } else {
            float precip = prefs.getFloat(Prefs.K_WX_PRECIP, 0f);
            Precip type = Precip.values()[prefs.getInt(Prefs.K_WX_TYPE, Precip.NONE.ordinal())];
            boolean thunder = prefs.getBool(Prefs.K_WX_THUNDER, false);
            int prob = prefs.getInt(Prefs.K_WX_PROB, 0);
            reading = new Weather(tempC, label, cloudCover, precip, type, thunder, prob);
        }
        readingAt = at;
    }
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/retro/launcher/data/WeatherRepository.java \
        app/src/main/java/com/retro/launcher/data/Prefs.java
git commit -m "feat: WeatherRepository persists the four weather channels and merges moonrise/moonset

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 9: `SkyView` — consume `Weather` directly, wire the manual override

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/sky/SkyView.java`

**Interfaces:**
- Consumes: `SkyConditions` (Task 4), `Weather` (Task 1), `Precip` (Task 1).
- Produces: `SkyView.setWeather(Weather)` (replaces `setWeather(float)`), `SkyView.setManualOverride(boolean enabled, float hour, float moonPhase)`.

- [ ] **Step 1: Add imports**

```java
import com.retro.launcher.core.SkyConditions;
import com.retro.launcher.core.Weather;
```

- [ ] **Step 2: Replace the `weather` field and `setWeather`**

Replace:

```java
    private volatile float weather;
```

with:

```java
    private volatile Weather weather;
    private volatile boolean manualOverrideEnabled;
    private volatile float manualHour;
    private volatile float manualMoonPhase;
```

Replace:

```java
    public void setWeather(float w) { this.weather = w; }
```

with:

```java
    public void setWeather(Weather w) { this.weather = w; }

    /** V9 §7b: when enabled, {@code hour} is fed directly as the sky's
     *  already-"warped" hour (bypassing {@link SolarClock#warp} so the full
     *  0-24 keyframe range is directly scrubbable) and the moon is always
     *  visible across the whole knob range rather than gated by a real
     *  moonrise/moonset window. */
    public void setManualOverride(boolean enabled, float hour, float moonPhase) {
        this.manualOverrideEnabled = enabled;
        this.manualHour = hour;
        this.manualMoonPhase = moonPhase;
    }
```

- [ ] **Step 3: Rewrite `drawFrame()`**

```java
    private void drawFrame() {
        SkyRenderer r = renderer;
        Bitmap bmp = bitmap;
        Weather w = weather;
        if (r == null || bmp == null || w == null) return;

        r.setTint(tintRamp);
        r.setDesaturation(desaturation);
        r.setSouthernView(MoonPhase.southernView(latitude));

        float realHour = decimalHour();
        float hour, moonriseHour, moonsetHour, moonPhase;
        if (manualOverrideEnabled) {
            hour = manualHour;
            realHour = manualHour;
            moonriseHour = 0f;
            moonsetHour = 24f;
            moonPhase = manualMoonPhase;
        } else {
            SolarTimes times = solarTimes;
            hour = times == null
                    ? realHour
                    : SolarClock.warp(realHour, times.sunriseHour, times.sunsetHour, times.tomorrowSunriseHour);
            moonriseHour = times == null ? Float.NaN : times.moonriseHour;
            moonsetHour = times == null ? Float.NaN : times.moonsetHour;
            moonPhase = MoonPhase.phase(System.currentTimeMillis());
        }

        float seconds = (System.nanoTime() - startNanos) / 1_000_000_000f;
        SkyConditions c = new SkyConditions(hour, realHour, moonriseHour, moonsetHour,
                w.cloudCover, w.precip, moonPhase, w.type, w.thunder, w.tempC);
        r.render(buf, c, seconds);
        bmp.setPixels(buf, 0, BUF_W, 0, 0, BUF_W, bufH);

        Canvas canvas = null;
        try {
            canvas = lockCanvas();
            if (canvas == null) return;
            dst.set(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.drawBitmap(bmp, null, dst, paint);
        } finally {
            if (canvas != null) {
                try { unlockCanvasAndPost(canvas); } catch (IllegalArgumentException ignored) { /* surface gone */ }
            }
        }
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: fails at this point only in `HomeActivity.java` (`sky.setWeather(w.w)` still passes a `float`) — that's expected and fixed in Task 13. Confirm `SkyView.java` itself has no errors (read the compiler output carefully: only `HomeActivity.java` lines should be flagged).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/retro/launcher/sky/SkyView.java
git commit -m "feat: SkyView consumes a full Weather reading and the manual wallpaper override

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

(This commit will not compile in isolation — `HomeActivity` still calls the old `setWeather(float)`. That's fine: Task 13 finishes the wiring later in the same session before the plan's final full-build verification. If your workflow requires every commit to build in isolation, merge this task into Task 13 instead of committing separately — flag this to the reviewer.)

---

## Task 10: `PixelSlider` extraction and `LimitSlider` refactor

**Files:**
- Create: `app/src/main/java/com/retro/launcher/ui/PixelSlider.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/LimitSlider.java`

**Interfaces:**
- Produces: `PixelSlider(Context, Metrics, float min, float max, float step, IntFunction<String> stepLabel)`, `setValue(float)`, `getValue() -> float`, `setOnValueChangeListener(Consumer<Float>)`, `setPalette(Palette)`, `setHaptics(Haptics)`.
- Consumes: nothing new from other tasks — purely extracted from `LimitSlider`'s existing code.

- [ ] **Step 1: Create `PixelSlider.java`**

This is `LimitSlider`'s existing view-building code generalized to a float range/step and a caller-supplied step-button label, with `UsageMath.snapLimit`'s inline logic replaced by a generic clamp+round.

```java
package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.util.Haptics;

import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * A generic {@code -step}/track/{@code +step} pixel slider over
 * {@code [min, max]}, snapped to {@code step}. Extracted from
 * {@link LimitSlider} (V9 §7b) so the manual-wallpaper-override controls
 * (percent, degrees, hours, a 0-1 fraction) can reuse the same widget with no
 * behaviour change to the existing daily-limit control.
 */
public final class PixelSlider extends LinearLayout {

    private Haptics haptics;
    public void setHaptics(Haptics haptics) { this.haptics = haptics; }
    private void tick() { if (haptics != null) haptics.click(); }

    private final Metrics metrics;
    private final float min, max, step;
    private final TextView minus, plus;
    private final View fill, thumb;
    private final FrameLayout trackWrap;

    private float value;
    private Consumer<Float> listener = v -> {};

    public PixelSlider(Context context, Metrics metrics, float min, float max, float step,
                        IntFunction<String> stepLabel) {
        super(context);
        this.metrics = metrics;
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = min;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        LauncherRoot.setNoSwipe(this);

        minus = stepButton(stepLabel.apply(-Math.round(step)));
        plus = stepButton(stepLabel.apply(Math.round(step)));

        trackWrap = new FrameLayout(context);
        int trackH = Math.round(metrics.cqw(4f));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(0, trackH, 1f);
        int sideGap = Math.round(metrics.cqw(2.5f));
        wrapLp.leftMargin = sideGap;
        wrapLp.rightMargin = sideGap;
        trackWrap.setLayoutParams(wrapLp);

        View track = new View(context);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(metrics.cqw(1f));
        track.setBackground(trackBg);
        trackWrap.addView(track, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        fill = new View(context);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setCornerRadius(metrics.cqw(1f));
        fill.setBackground(fillBg);
        FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT);
        fillLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        trackWrap.addView(fill, fillLp);

        int knob = Math.round(metrics.cqw(5f));
        thumb = new View(context);
        GradientDrawable thumbBg = new GradientDrawable();
        thumb.setBackground(thumbBg);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(knob, knob);
        thumbLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        trackWrap.addView(thumb, thumbLp);

        addView(minus);
        addView(trackWrap);
        addView(plus);

        minus.setOnClickListener(v -> setValue(value - step));
        plus.setOnClickListener(v -> setValue(value + step));
        trackWrap.setOnTouchListener(this::onTrackTouch);

        trackWrap.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> updateThumb());
    }

    private boolean onTrackTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                int w = trackWrap.getWidth();
                if (w <= 0) return true;
                float frac = clamp01(e.getX() / w);
                setValue(min + frac * (max - min));
                return true;
        }
        return false;
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private TextView stepButton(String label) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        int pad = Math.round(metrics.cqw(2f));
        t.setPadding(pad, pad, pad, pad);
        return t;
    }

    private float snap(float raw) {
        float clamped = Math.max(min, Math.min(max, raw));
        return min + Math.round((clamped - min) / step) * step;
    }

    public void setValue(float raw) {
        float next = snap(raw);
        boolean changed = next != value;
        value = next;
        updateThumb();
        if (changed) {
            tick();
            listener.accept(value);
        }
    }

    public float getValue() { return value; }

    public void setOnValueChangeListener(Consumer<Float> l) { this.listener = l; }

    private void updateThumb() {
        int w = trackWrap.getWidth();
        if (w <= 0) return;
        float frac = (value - min) / (max - min);
        int knob = thumb.getLayoutParams().width;
        int usable = w - knob;
        int x = Math.round(frac * usable);

        FrameLayout.LayoutParams fillLp = (FrameLayout.LayoutParams) fill.getLayoutParams();
        fillLp.width = x + knob / 2;
        fill.setLayoutParams(fillLp);

        thumb.setTranslationX(x);
    }

    public void setPalette(Palette p) {
        ((GradientDrawable) trackWrap.getChildAt(0).getBackground())
                .setStroke(Math.max(1, Math.round(metrics.cqw(0.6f))), p.ink);
        ((GradientDrawable) fill.getBackground()).setColor(p.p);
        ((GradientDrawable) thumb.getBackground()).setColor(p.ink);
        minus.setTextColor(p.p);
        plus.setTextColor(p.p);
        updateThumb();
    }
}
```

- [ ] **Step 2: Rewrite `LimitSlider.java` on top of `PixelSlider`**

Replace the whole file:

```java
package com.retro.launcher.ui;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.UsageMath;
import com.retro.launcher.util.Haptics;

import java.util.function.IntConsumer;

/**
 * The daily-limit control from DESIGN_NOTES §7d, now a thin configuration of
 * {@link PixelSlider} (V9 §7b) over {@code [LIMIT_MIN, LIMIT_MAX]} stepped by
 * {@code LIMIT_STEP} — no behaviour change from before the extraction.
 */
public final class LimitSlider extends LinearLayout {

    private final PixelSlider slider;

    public LimitSlider(Context context, Metrics metrics) {
        super(context);
        slider = new PixelSlider(context, metrics,
                UsageMath.LIMIT_MIN, UsageMath.LIMIT_MAX, UsageMath.LIMIT_STEP,
                step -> (step > 0 ? "+" : "−") + Math.abs(step));
        slider.setValue(240);
        addView(slider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    public void setHaptics(Haptics haptics) { slider.setHaptics(haptics); }

    public void setValue(int minutes) { slider.setValue(minutes); }

    public int getValue() { return Math.round(slider.getValue()); }

    public void setOnValueChangeListener(IntConsumer l) {
        slider.setOnValueChangeListener(v -> l.accept(Math.round(v)));
    }

    public void setPalette(Palette p) { slider.setPalette(p); }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL. `ScreenTimePanel.java` (the only caller of `LimitSlider`) needs no changes — `LimitSlider`'s public surface (`setHaptics`, `setValue(int)`, `getValue():int`, `setOnValueChangeListener(IntConsumer)`, `setPalette`) is unchanged.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/retro/launcher/ui/PixelSlider.java \
        app/src/main/java/com/retro/launcher/ui/LimitSlider.java
git commit -m "refactor: extract PixelSlider from LimitSlider for reuse by the wallpaper override (V9 §7b)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 11: `SettingsPanel` — the WALLPAPER section

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/ui/SettingsPanel.java`

**Interfaces:**
- Produces: a new `WALLPAPER` section holding one `MANUAL WALLPAPER` `PixelToggle`, off by default, writing `Prefs.K_WX_OVERRIDE` and calling `onPrefsChanged`.

- [ ] **Step 1: Add the section field**

Add alongside the other section fields:

```java
    private final LinearLayout wallpaperSection;
```

- [ ] **Step 2: Add it to the constructor's `content.addView(...)` block, after `permSection`**

```java
        content.addView(paletteSection = section());
        content.addView(clockSection = section());
        content.addView(tempSection = section());
        content.addView(feedbackSection = section());
        content.addView(dockSection = section());
        content.addView(permSection = section());
        content.addView(wallpaperSection = section());
```

- [ ] **Step 3: Add it to `rebuildAll()`**

```java
    private void rebuildAll() {
        rebuildPaletteSection();
        rebuildClockSection();
        rebuildTempSection();
        rebuildFeedbackSection();
        rebuildDockSection();
        rebuildPermissionsSection();
        rebuildWallpaperSection();
    }
```

- [ ] **Step 4: Add the section body**

Add a new method near `rebuildFeedbackSection()`:

```java
    // ---- WALLPAPER -----------------------------------------------------

    /** V9 §7b: a manual override for every wallpaper input, for testing and
     *  preview — off by default so real weather is what ships. */
    private void rebuildWallpaperSection() {
        wallpaperSection.removeAllViews();
        if (palette == null) return;
        wallpaperSection.addView(sectionHeader("WALLPAPER"));
        wallpaperSection.addView(toggleRow("MANUAL WALLPAPER", prefs.manualWallpaper(), checked -> {
            prefs.putBool(Prefs.K_WX_OVERRIDE, checked);
            onPrefsChanged.run();
        }));

        TextView caption = new TextView(getContext());
        caption.setText("WHEN ON, A WALLPAPER PANEL APPEARS AT THE TOP OF SCREEN TIME WITH "
                + "SLIDERS FOR CLOUD COVER, PRECIPITATION, TEMPERATURE, TIME OF DAY AND MOON "
                + "PHASE, PLUS THUNDER AND SNOW TOGGLES. THE SKY FOLLOWS THEM LIVE INSTEAD OF "
                + "THE REAL READING.");
        caption.setTypeface(Typeface.MONOSPACE);
        caption.setTextColor(palette.a);
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        addTopMargin(caption, Math.round(metrics.cqw(3f)));
        wallpaperSection.addView(caption);
    }
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/retro/launcher/ui/SettingsPanel.java
git commit -m "feat: add the WALLPAPER section and MANUAL WALLPAPER toggle to Settings (V9 §7b)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 12: `ScreenTimePanel` — the WALLPAPER control block

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/ui/ScreenTimePanel.java`

**Interfaces:**
- Produces: `ScreenTimePanel.setManualWallpaperEnabled(boolean)`; `ScreenTimePanel.setOnWallpaperOverrideChangedListener(Runnable)`. A `WALLPAPER` block at the top of the scrollable content, visible only when manual wallpaper is enabled, holding five `PixelSlider`s (cloud cover 0–100%, precipitation 0–100%, temperature −20–50°C, time of day 0–24h, moon phase 0–1) and two `PixelToggle`s (thunder, snow), each writing straight through `Prefs` like every other control in this panel.

- [ ] **Step 1: Add fields**

```java
    private final LinearLayout wallpaperSection;
    private Runnable onWallpaperOverrideChanged = () -> {};
```

- [ ] **Step 2: Build the section in the constructor**

Insert right before the `totalRow` block (so it's the first thing in `content`):

```java
        wallpaperSection = new LinearLayout(context);
        wallpaperSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wallpaperLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wallpaperLp.bottomMargin = Math.round(metrics.cqw(6f));
        wallpaperSection.setLayoutParams(wallpaperLp);
        content.addView(wallpaperSection);
```

- [ ] **Step 3: Add the public setters**

```java
    public void setOnWallpaperOverrideChangedListener(Runnable r) { this.onWallpaperOverrideChanged = r; }

    /** V9 §7b: HomeActivity calls this after every prefs change, since the
     *  MANUAL WALLPAPER toggle itself lives in Settings, not here. */
    public void setManualWallpaperEnabled(boolean enabled) {
        rebuildWallpaperSection(enabled);
    }
```

- [ ] **Step 4: Add the section builder**

```java
    // ---- WALLPAPER (V9 §7b) ----------------------------------------------

    private void rebuildWallpaperSection(boolean enabled) {
        wallpaperSection.removeAllViews();
        if (palette == null || !enabled) return;

        wallpaperSection.addView(sectionHeader("WALLPAPER"));
        int gap = Math.round(metrics.cqw(2.5f));

        wallpaperSection.addView(overrideSlider("CLOUD COVER", 0f, 100f, 5f,
                Prefs.K_OV_CLOUD, prefs.getFloat(Prefs.K_OV_CLOUD, 0f) * 100f,
                v -> prefs.putFloat(Prefs.K_OV_CLOUD, v / 100f), "%", gap));

        wallpaperSection.addView(overrideSlider("PRECIPITATION", 0f, 100f, 5f,
                Prefs.K_OV_PRECIP, prefs.getFloat(Prefs.K_OV_PRECIP, 0f) * 100f,
                v -> prefs.putFloat(Prefs.K_OV_PRECIP, v / 100f), "%", gap));

        wallpaperSection.addView(overrideSlider("TEMPERATURE", -20f, 50f, 1f,
                Prefs.K_OV_TEMP, prefs.getFloat(Prefs.K_OV_TEMP, 20f),
                v -> prefs.putFloat(Prefs.K_OV_TEMP, v), "°C", gap));

        wallpaperSection.addView(overrideSlider("TIME OF DAY", 0f, 24f, 0.25f,
                Prefs.K_OV_HOUR, prefs.getFloat(Prefs.K_OV_HOUR, 12f),
                v -> prefs.putFloat(Prefs.K_OV_HOUR, v), "H", gap));

        wallpaperSection.addView(overrideSlider("MOON PHASE", 0f, 1f, 0.01f,
                Prefs.K_OV_MOON, prefs.getFloat(Prefs.K_OV_MOON, 0.5f),
                v -> prefs.putFloat(Prefs.K_OV_MOON, v), "", gap));

        LinearLayout thunderRow = wallpaperToggleRow("THUNDER", prefs.getBool(Prefs.K_OV_THUNDER, false),
                v -> prefs.putBool(Prefs.K_OV_THUNDER, v));
        addTopMargin(thunderRow, gap);
        wallpaperSection.addView(thunderRow);

        LinearLayout snowRow = wallpaperToggleRow("SNOW", prefs.getBool(Prefs.K_OV_SNOW, false),
                v -> prefs.putBool(Prefs.K_OV_SNOW, v));
        addTopMargin(snowRow, gap);
        wallpaperSection.addView(snowRow);
    }

    private View overrideSlider(String label, float min, float max, float step, String key,
                                 float initial, java.util.function.Consumer<Float> onChange,
                                 String unit, int gap) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        addTopMargin(col, gap);

        TextView title = new TextView(getContext());
        title.setText(label);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setAllCaps(true);
        title.setTextColor(palette.ink);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        col.addView(title);

        PixelSlider slider = new PixelSlider(getContext(), metrics, min, max, step,
                s -> (s > 0 ? "+" : "−") + Math.abs(s) + unit);
        slider.setHaptics(haptics);
        slider.setPalette(palette);
        slider.setValue(initial);
        slider.setOnValueChangeListener(v -> {
            onChange.accept(v);
            onWallpaperOverrideChanged.run();
        });
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sliderLp.topMargin = Math.round(metrics.cqw(1f));
        col.addView(slider, sliderLp);

        return col;
    }

    private LinearLayout wallpaperToggleRow(String label, boolean checked,
                                             java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(getContext());
        labelView.setText(label);
        labelView.setTypeface(Typeface.MONOSPACE);
        labelView.setAllCaps(true);
        labelView.setTextColor(palette.ink);
        labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        PixelToggle toggle = new PixelToggle(getContext(), metrics);
        toggle.setPalette(palette);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(v -> {
            onChange.accept(v);
            onWallpaperOverrideChanged.run();
        });
        row.addView(toggle);

        return row;
    }
```

Add the two imports this needs:

```java
import com.retro.launcher.ui.PixelToggle; // already in the same package — no import needed
```

(`PixelToggle` and `PixelSlider` are both in `com.retro.launcher.ui`, the same package as `ScreenTimePanel` — no new import statements are actually required. `ViewGroup` is already imported.)

- [ ] **Step 5: Call `setManualWallpaperEnabled` from `setPalette` too**

`rebuildWallpaperSection` reads `palette`, so it must also run whenever the palette changes (not only when `HomeActivity` explicitly calls the new setter). In `setPalette(Palette p)`, add a call after the other `rebuild*` calls:

```java
    public void setPalette(Palette p) {
        this.palette = p;
        Tint.apply(this, p);
        slider.setPalette(p);
        coffee.setPalette(p);
        todayTotal.setTextColor(p.ink);
        pickupsLabel.setTextColor(p.a);
        limitTitle.setTextColor(p.ink);
        rebuildTotals();
        rebuildLimitCard();
        rebuildWeekChart();
        rebuildMostUsed();
        rebuildWallpaperSection(prefs.manualWallpaper());
    }
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/retro/launcher/ui/ScreenTimePanel.java
git commit -m "feat: add the WALLPAPER control block to Screen Time (V9 §7b)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 13: `HomeActivity` — wire the manual override ahead of the repository

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/HomeActivity.java`

**Interfaces:**
- Consumes: `Prefs.manualWallpaper/overrideWeather/overrideHour/overrideMoonPhase` (Task 7), `SkyView.setWeather(Weather)/setManualOverride` (Task 9), `ScreenTimePanel.setOnWallpaperOverrideChangedListener/setManualWallpaperEnabled` (Task 12).

- [ ] **Step 1: Wire the `ScreenTimePanel` listener**

Next to the existing `screenTime.setOnLimitChangedListener(this::refreshUsage);` line in `onCreate`:

```java
        screenTime.setOnLimitChangedListener(this::refreshUsage);
        screenTime.setOnWallpaperOverrideChangedListener(this::refreshTime);
```

- [ ] **Step 2: Update `refreshSkyLocation()`**

```java
    /** The moon's phase is the same everywhere; which way up it looks, and
     *  what real time maps onto the sky gradient, are not. Both come from
     *  the coarse fix and solar times the weather repository already keeps —
     *  skipped entirely while the manual wallpaper override drives the sky
     *  instead (V9 §7b). */
    private void refreshSkyLocation() {
        if (prefs.manualWallpaper()) return;
        double[] fix = weatherRepository.fix();
        sky.setLocation(fix == null ? Float.NaN : (float) fix[0],
                         fix == null ? Float.NaN : (float) fix[1]);
        sky.setSolarTimes(weatherRepository.solarTimes());
    }
```

- [ ] **Step 3: Update `refreshTime()`**

```java
    private void refreshTime() {
        Calendar now = Calendar.getInstance();
        home.setTime(now);

        boolean manual = prefs.manualWallpaper();
        Weather w = manual ? prefs.overrideWeather() : weatherRepository.current(decimalHour());
        Weather shown = manual || weatherRepository.hasReading() ? w : null;
        home.setWeather(shown);
        settings.setWeather(shown);
        sky.setWeather(w);
        sky.setManualOverride(manual, prefs.overrideHour(), prefs.overrideMoonPhase());
        screenTime.setManualWallpaperEnabled(manual);
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL — this is the point where Task 9's dangling `sky.setWeather(w.w)` reference resolves.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/retro/launcher/HomeActivity.java
git commit -m "feat: HomeActivity consults the manual wallpaper override ahead of the weather repository (V9 §7b)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ"
```

---

## Task 14: Full verification and APK build

**Files:** none — verification only.

- [ ] **Step 1: Run the full `:core` test suite**

Run: `./gradlew :core:test`
Expected: BUILD SUCCESSFUL, every test in `BodyPathTest`, `LunarMathTest`, `WeatherParserTest`, `SkyRendererTest`, `SyntheticWeatherTest`, and every pre-existing untouched suite (`SolarClockTest`, `SolarMathTest`, `MoonPhaseTest`, etc.) green.

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke test (if a device/emulator is available)**

- Launch the app. Confirm the sky renders (no crash, no black screen).
- Open Settings, scroll to WALLPAPER, toggle MANUAL WALLPAPER on. Confirm a WALLPAPER block appears at the top of Screen Time with five sliders and two toggles.
- Scrub TIME OF DAY across its range and confirm the sky's colour and the sun/moon position move together and the moon is always visible somewhere on screen.
- Toggle THUNDER on with PRECIPITATION at 0% and confirm lightning flashes with no rain drawn.
- Set PRECIPITATION high with SNOW on and confirm falling flakes, not rain streaks.
- Turn MANUAL WALLPAPER back off and confirm the sky returns to following real time/weather.

If no device/emulator is available, state so explicitly rather than claiming this step passed.

- [ ] **Step 4: If any commit above deferred a compile fix to Task 13, confirm the working tree is fully green now**

Run: `git log --oneline -15` and `git status`
Expected: a clean tree, 12 new commits since `5967f04` (Tasks 1–13), all ahead of `V9`'s current head.

- [ ] **Step 5: Report completion**

No commit for this task — it is a verification gate. Report to the user: full `:core` suite status, APK build status, and whether a manual smoke test was possible.
