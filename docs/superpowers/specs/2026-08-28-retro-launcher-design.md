# Retro Launcher — design spec

**Date:** 2026-08-28
**Status:** approved, not yet implemented
**Companions:** `design/DESIGN_NOTES.md` (prototype token audit) ·
`HANDOFF.md` §0 (amended constraints)

---

## 1. What we are building

A native Android launcher reproducing the "Retro Launcher" prototype: four
swipe-connected screens over a per-pixel animated pixel-art sky that cycles
through a 24-hour day, with a colour palette that follows the clock.

Java only, no Kotlin, no AndroidX, no Material, no third-party runtime
dependencies, no image or font assets. `minSdk 26`, `targetSdk 34`,
portrait-locked, edge-to-edge.

**Renamed from the scaffold:** package and namespace
`com.minimal.launcher` → `com.retro.launcher`; label → `Retro Launcher`.

### Not in scope

- A `WallpaperService` live wallpaper. The renderer is isolated enough to add
  one later, but the launcher draws its own sky, as the prototype does.
- Landscape layouts.
- Per-app screen-time limits (the prototype has a single daily total).
- Widgets, folders, gestures beyond those specified, icon packs.

---

## 2. Architecture

### 2.1 The container model

One `Activity`. One custom `ViewGroup` (`LauncherRoot`) holding four sibling
panels laid out at fixed offsets:

```
LauncherRoot  (measures children at full size, positions them offscreen)
├── SkyView          z=0, behind everything, never moves
├── HomePanel        at ( 0,  0 )
├── SettingsPanel    at (-W,  0 )   enters on swipe right
├── DrawerPanel      at (+W,  0 )   enters on swipe left
├── ScreenTimePanel  at ( 0, +H )   enters on swipe up
├── BottomSheet      overlay, hidden
└── HintOverlay      overlay, first run only
```

`LauncherRoot` owns navigation and nothing else. It:

1. Intercepts touch in `onInterceptTouchEvent`, applying the prototype's rules
   from `DESIGN_NOTES` §1 — 12px slop before axis lock, then the locked axis
   for the rest of the gesture.
2. **Declines to intercept** when the down event falls inside
   `WindowInsets.getSystemGestureInsets()` (API 29+; zero below), so Android's
   Back gesture keeps working.
3. **Declines to intercept** when the touch is over a view marked
   `noSwipe` — the native equivalent of `[data-noswipe]`. Implemented as a
   tag checked while walking up from the hit view, so every scrolling list,
   the tab strip, the scrubber and the limit slider opt out by setting one tag.
4. Translates panels 1:1 during a drag, then settles with a `260ms`
   `PathInterpolator(.2f,.7f,.2f,1f)` — the framework class, available since
   API 21.

**Why not four Activities:** activity transitions cannot follow a finger, and
the sky renderer would tear down and restart on every navigation. **Why not a
pager:** `ViewPager` is AndroidX; hand-rolling one reproduces `LauncherRoot`.

### 2.2 Panel content

XML layouts inflated into framework widgets (`LinearLayout`, `ScrollView`,
`HorizontalScrollView`, `ListView`, `TextView`, `EditText`), **not** custom
canvas drawing. The retro appearance comes from `Tint`, not from static styles
— because the palette changes with the hour, ten palette sets × light/dark
cannot be baked into XML.

Custom `View` subclasses are used only where a framework widget genuinely
cannot express the design: the sky, the pixel icons, the A–Z scrubber, the
limit slider, the weekly bar chart, and the pixel toggle switch.

### 2.3 Gradle modules

Two modules, so that the pure logic is testable without an Android SDK:

| Module | Plugin | Package | Contents |
|---|---|---|---|
| `:core` | `java-library` | `com.retro.launcher.core` | Every class with zero Android imports — sizing, palettes, the sky renderer, date formatting, weather parsing, usage maths, category mapping |
| `:app` | `com.android.application` | `com.retro.launcher.*` | Activity, views, repositories, resources |

`:app` declares `implementation project(':core')`. That is our own source, not a
third-party dependency, so `HANDOFF.md` §1's rule is untouched and the APK gains
nothing it would not have had anyway.

**Why it matters:** `gradle :core:test` runs on a bare JDK — no Android SDK, no
emulator, no CI round-trip. Without this split, every unit test in §7 would
require the full Android toolchain, and the TDD loop on this machine would be
"push and wait." The split is what makes §7 real rather than aspirational.

### 2.4 Class layout

```
com.retro.launcher
├── HomeActivity.java            Activity lifecycle, permission results, insets
│
├── ui/
│   ├── LauncherRoot.java        panel layout, gesture routing, transforms
│   ├── HomePanel.java           widget + dock + double-tap search trigger
│   ├── ClockWidget.java         three tap regions, blinking colon
│   ├── DockView.java            up to 5 slots + add slot, long-press
│   ├── DrawerPanel.java         tabs, ListView, scrubber
│   ├── AlphaScrubber.java       custom View: 26 letters, drag-to-jump
│   ├── SettingsPanel.java       four settings sections
│   ├── ScreenTimePanel.java     totals, limit card, week, most-used
│   ├── LimitSlider.java         custom View: 30–600 snapped to 15
│   ├── WeekChart.java           custom View: 7 bars
│   ├── PixelToggle.java         custom View: the switch in Settings
│   ├── SearchOverlay.java       filter field + results list
│   ├── BottomSheet.java         dock picker / category picker
│   ├── HintOverlay.java         first-run gesture hint
│   └── SetupScreen.java         first-run permission explainer
│
├── sky/
│   └── SkyView.java             SurfaceView + render thread lifecycle
│
├── theme/
│   └── Tint.java                repaints a view tree against a Palette
│
├── icons/
│   ├── IconSource.java          interface — the Tier 2 decision seam
│   ├── GeneratedTileIcons.java  letter tile in palette colours
│   ├── PosterizedIcons.java     system icon → Bayer → 6-colour ramp
│   ├── PixelTile.java           16×16 tile silhouette + run encoding
│   └── IconCache.java           LruCache keyed by package + palette
│
├── data/
│   ├── AppEntry.java            label, package, activity, category
│   ├── AppRepository.java       PackageManager query, sort, categorise
│   ├── Prefs.java               SharedPreferences, the 14 keys
│   ├── UsageRepository.java     UsageStatsManager + weekly aggregation
│   ├── WeatherRepository.java   cache + refresh policy
│   ├── WeatherSource.java       interface
│   └── OpenMeteoWeather.java    HTTPS fetch, delegates parsing to core
│
└── util/
    └── Insets.java              system gesture inset lookup, API-guarded
```

```
com.retro.launcher.core            ← :core module, zero Android imports
├── Metrics.java                 cqw → px, given width and density
├── Palette.java                 10 role sets + veil derivation
├── PaletteResolver.java         auto-by-hour, manual, light/dark/system
├── Bayer.java                   the shared 4×4 ordered-dither matrix
├── SkyKeyframes.java            14-entry table + interpolation
├── SkyRenderer.java             the frame() port — pure pixel math into int[]
├── SyntheticWeather.java        the prototype's formula (Tier 1)
├── WeatherParser.java           Open-Meteo response → Weather (Tier 5)
├── Weather.java                 tempC, condition band, w scalar
├── DateFormatter.java           DD/MMM/YYYY/DOY/WK tokeniser
├── CategoryMap.java             ApplicationInfo.category int → tab name
└── UsageMath.java               daily totals, 7-day window, limit state
```

Each unit answers the three questions: what it does, how you use it, what it
depends on. The `data/` and `util/` packages depend on nothing in `ui/`;
`theme/` and `icons/` depend on `util/` only. Dependencies point one way.

---

## 3. Key components

### 3.1 `Metrics` — the sizing foundation

The prototype sizes everything in `cqw` (percent of screen width). Freezing
that into fixed dp breaks on narrow phones and foldables. `Metrics.cqw(4f)`
resolves against `displayMetrics.widthPixels` at construction, and every
layout value in the app goes through it. Text sizes go through `Metrics.sp()`
with a floor so micro-labels never drop below legibility.

It takes `widthPixels` and `density` as constructor arguments rather than
reading `Resources` itself, so it carries no Android dependency and can be
unit-tested at arbitrary screen sizes.

### 3.2 `SkyRenderer` — the animated wallpaper

A faithful port of the prototype's `frame()` (`DESIGN_NOTES` §2b).

- **Zero Android imports.** Its only entry point is
  `render(int[] argb, int w, int h, float hour, float weather, float moonPhase, float t)`,
  writing into a caller-owned `int[]`. It is a pure function of its arguments
  plus the seeded cloud/star/drop tables. This is what makes it unit-testable
  on the JVM — a `Bitmap` cannot be instantiated in a plain JUnit test, so the
  renderer must never touch one.
- `SkyView` owns the buffer and the `Bitmap` of
  `108 × clamp(round(108·h/w), 96, 320)`, calling `setPixels` after each
  `render` and blitting to the surface.
- Driven by one render thread at
  **30fps**, blitted with `FILTER_BITMAP = false` for nearest-neighbour
  upscale.
- The thread starts in `surfaceCreated`, is paused in `onPause` and resumed in
  `onResume`, so it never runs while another app is foreground.
- `SurfaceView` sits behind the window by default — exactly the layering the
  design needs, with no z-order fiddling.

**Budget:** ~25K pixels/frame × 30fps ≈ 760K px/s. Well within a single
thread's reach on any device we target.

**Over-limit desaturation** (delta 10) folds into the existing per-pixel pass:
lerp each pixel toward its own luminance by a factor derived from the overage,
capped. No extra traversal.

### 3.3 `PaletteResolver` — what colour is it right now

Resolves three inputs into one `Palette`:

| Input | Values | Default |
|---|---|---|
| Palette choice | `auto` \| one of 5 ids | `auto` |
| Theme | `system` \| `light` \| `dark` | `system` |
| Hour | 0.0–24.0 | — |

`auto` maps hour to palette id through the §2a table. `system` reads
`Configuration.uiMode & UI_MODE_NIGHT_MASK`. Pure logic, no Android
dependencies except that one bitmask read — which is passed in as a boolean,
keeping the class testable.

Re-resolved on a **minute tick** and on `onConfigurationChanged`. When the
resolved palette differs from the current one, `Tint.apply()` walks the
visible panel. Panels not visible are marked dirty and repainted on entry.

### 3.4 `IconSource` — the Tier 2 decision seam

```java
public interface IconSource {
    Bitmap iconFor(AppEntry app, Palette palette, int sizePx);
    void   onPaletteChanged();
}
```

Two implementations, both built:

- **`GeneratedTileIcons`** — the prototype's 16×16 tile silhouette
  (`PixelTile`) filled with `tile`, carrying the app's first letter rendered
  in `p`. Cost: a handful of draw calls, cached.
- **`PosterizedIcons`** — loads the system icon via `PackageManager`,
  downsamples to 16×16, then quantizes through the same luminance-sorted
  6-colour ramp and Bayer matrix the wallpaper's tint mode uses. Cost: one
  processing pass per app per palette, cached in `IconCache`.

Both are wired behind a debug toggle, with frame-time and drawer-scroll
instrumentation. **The build stops at this point** and the owner chooses on
measured evidence. Whichever loses is deleted, not left as dead weight.

### 3.5 `AppRepository`

`queryIntentActivities(MAIN/LAUNCHER)` → sort by label, case-insensitive →
assign categories from `ApplicationInfo.category` per `DESIGN_NOTES` §9
delta 3, with user overrides from `Prefs` layered on top.

Refreshed in `onResume` and on a `PACKAGE_ADDED`/`PACKAGE_REMOVED` receiver so
installs appear without leaving the launcher.

### 3.6 `WeatherRepository`

Holds a cached `Weather{tempC, conditionCode, w}` with a timestamp.

- **Refresh policy:** on resume if older than 30 minutes; never more than once
  per 10 minutes; never while the screen is off.
- **Tier 1** is backed by `SyntheticWeather` (the prototype's formula), so the
  home screen is complete before any network code exists.
- **Tier 5** swaps in `OpenMeteoWeather`: one HTTPS GET to
  `api.open-meteo.com` (no key, no account), parsed with `android.util.JsonReader`
  — framework, streaming, no dependency.
- The `w` scalar it produces is what drives the sky's clouds, rain and storm,
  so real conditions reach the wallpaper through the existing path.

**Failure is normal and silent:** no network, no permission, no location fix,
or a malformed response all leave the last good value in place. If there has
never been a good value, the widget shows `--°` and the sky uses `w = 0`
(clear). No dialogs, no toasts, no retry storms — one attempt per policy
window.

---

## 4. Data flow

```
       minute tick ─────┐
       config change ───┼──▶ PaletteResolver ──▶ Palette ──┬──▶ Tint.apply(panel)
       user override ───┘                                  ├──▶ IconSource.onPaletteChanged
                                                           └──▶ SkyRenderer.palette (tint mode)

       system clock ────────────────────────────────────────▶ SkyRenderer.hour
       WeatherRepository ──▶ w ────────────────────────────▶ SkyRenderer.weather
       UsageRepository ────▶ overage ──────────────────────▶ SkyRenderer.desaturation

       touch ──▶ LauncherRoot ──┬──▶ panel transform
                                └──▶ GestureDetector.onDoubleTap ──▶ SearchOverlay

       any settings change ──▶ Prefs.write() (immediate, like the prototype's save())
```

State lives in `Prefs` and is read by whoever needs it. There is no central
mutable model object and no observer framework — a launcher this size does not
need one, and the prototype's own `setState` + `save()` discipline maps
directly onto "write the pref, tell the affected view to refresh."

---

## 5. Permissions and onboarding

Three permissions, per `HANDOFF.md` §0 row 1. The owner chose a **first-run
setup screen**.

| Permission | Kind | Needed for | Absent behaviour |
|---|---|---|---|
| `PACKAGE_USAGE_STATS` | special (Settings → Special app access) | screen time | The screen shows a single `GRANT ACCESS` row. Nothing else breaks. |
| `INTERNET` | normal, granted at install | weather | — |
| `ACCESS_COARSE_LOCATION` | runtime | weather | Widget shows `--°`; sky stays clear. |

**Flow:** first launch shows `HintOverlay` (the prototype's swipe hint, plus a
line for double-tap search), then `SetupScreen` — a retro-styled explainer with
one row per permission and a button opening the right Settings page
(`ACTION_USAGE_ACCESS_SETTINGS` / a runtime request). It can be skipped.
Both are shown once and the fact is persisted.

Settings also gets a `PERMISSIONS` block showing live status with a fix
button, so a skipped setup is recoverable without a reinstall.

**The launcher is fully functional with neither permission granted.** Nothing
blocks on them.

---

## 6. Error handling

| Failure | Response |
|---|---|
| `queryIntentActivities` returns empty | Show a diagnostic row naming the missing `<queries>` block rather than an empty screen — this is the classic API 30+ silent failure. |
| An app is uninstalled between listing and launch | Catch `ActivityNotFoundException`, refresh the repository, no crash. |
| Weather fetch fails at any stage | Keep the last good value; `--°` if none. Silent. |
| Usage stats permission revoked while running | Screen time falls back to the `GRANT ACCESS` row on next resume. |
| No dialer / SMS / camera resolves at first run | That dock slot stays an empty `+`. |
| No weather app installed | The weather tap is a no-op. Documented as best-effort — there is no framework `CATEGORY_APP_WEATHER`. |
| Surface destroyed while rendering | Render thread exits its loop cleanly on the `surfaceDestroyed` flag; no drawing to a dead surface. |
| Corrupt or partial `SharedPreferences` | Each key falls back to its documented default independently; a bad `dock` list does not take the palette with it. |

---

## 7. Testing

`testImplementation 'junit:junit:4.13.2'` on `:core` (approved, `HANDOFF.md`
§0 row 4) — **test-only, never in the APK.**

Run with `gradle :core:test`. Because `:core` is a plain `java-library`
module (§2.3), this needs **only a JDK** — no Android SDK, no emulator, no CI
round-trip. Everything below lives in that module for exactly this reason:

| Class | What the tests pin down |
|---|---|
| `SkyKeyframes` | Exact colours at each of the 14 keyframes; interpolation midpoints; wraparound at 24:00 |
| `PaletteResolver` | Every boundary in the §2a table, including the awkward ones — 04:36, 07:36, 18:36, 20:24 |
| `SkyRenderer` | Pure scalars (`sunAlt`, `storm`, `cover`, `precip`, `twilight`) and body positions at known hours; frame determinism given a fixed seed |
| `DateFormatter` | All 15 tokens, all 5 presets, day-of-year and week number at year boundaries |
| `Bayer` / posterize | Quantization is stable and the ramp sorts by luminance |
| `OpenMeteoWeather` | Parsing a recorded response; every malformed-input path yields "no update", never a crash |
| `UsageRepository` | Aggregation into daily totals and a 7-day window across a midnight boundary |
| `AppRepository` | Category mapping for all nine `ApplicationInfo.category` values; sort stability |
| `Metrics` | `cqw`/`sp` conversions at several densities and widths |

Everything else — gestures, layout, rendering *appearance* — is verified on
the device, per tier. CI builds the APK and reports its size on every push.

**TDD applies to the table above:** those tests are written before their
implementations.

---

## 8. Build tiers

Every tier ends with an installable APK. Tiers are sequential; each is
reviewed before the next begins.

| Tier | Contents | Verifiable by |
|---|---|---|
| **0 — Foundation** | Package rename, manifest (home intent filter, `<queries>`, three permissions), edge-to-edge portrait lock, `Metrics`, `Palette`, `PaletteResolver`, `Prefs`, `Bayer`, `LauncherRoot` with four blank colour-filled panels and complete swipe navigation | Swiping between four screens feels right; Back doesn't exit; system Back gesture still works at the edges |
| **1 — Sky + Home** | `SkyKeyframes`, `SkyRenderer`, `SkyView`, `Tint`, `ClockWidget` (three tap regions, blinking colon), `DockView` seeded from dialer/SMS/camera intents, `SyntheticWeather`, `DateFormatter`, double-tap search stub | **Usable as a daily-driver home screen.** Watch the sky through a full day. |
| **2 — Drawer** ⟵ **GATE** | `AppEntry`, `AppRepository`, auto-categories, `DrawerPanel`, `AlphaScrubber`, tabs, long-press → App Info, tab long-press → categories, `BottomSheet`, `PixelTile`, `IconCache`, **both** `IconSource` implementations + instrumentation | All apps reachable. **Then stop:** owner picks the icon strategy from measured frame times and scroll feel. |
| **3 — Settings** | `SettingsPanel` with all four sections, `PixelToggle`, the date-format token builder, °C/°F, dock editor, permissions block; full `Prefs` persistence | Every prototype setting works and survives a restart |
| **4 — Screen time** | `HintOverlay`, `SetupScreen`, `UsageRepository`, `ScreenTimePanel`, `LimitSlider`, `WeekChart`, home-screen over-limit nag + wallpaper desaturation | Real usage numbers; the nag appears when you pass your limit |
| **5 — Weather + polish** | `OpenMeteoWeather`, coarse location, cache and refresh policy, `SearchOverlay` proper, `SetupScreen`'s location row, final pass | Real conditions visibly driving the sky's clouds and rain |

`HintOverlay` and `SetupScreen` are the same first-run flow and ship together
in Tier 4, when the first permission becomes necessary. Tier 5 adds the
location row to the existing setup screen rather than introducing a second one.

**The Tier 2 gate is a hard stop.** Work does not continue past it without an
explicit decision from the owner.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Render thread drains battery | Paused whenever the launcher is not visible; 30fps not 60; if it still costs too much, drop to 15fps — one constant. |
| Posterized icons stutter the drawer | Precisely what the Tier 2 gate measures. The alternative implementation is already built. |
| `Tint` walking deep view trees on every palette change | Palette changes at most once a minute, and only the visible panel is walked; others are marked dirty. |
| Micro type below 12sp is unreadable on a real phone | Flagged in `DESIGN_NOTES` §4. `Metrics.sp()` carries a floor; the owner decides at Tier 1 whether to raise it and accept looser fidelity. |
| Gesture navigation conflicts vary by OEM skin | Inset-aware from Tier 0, so it is exercised from the first installable build rather than discovered at the end. |
| APK grows well past 80 KB | Accepted (`HANDOFF.md` §0 row 2); size reported every build so the trend stays visible. |
| CI needs a git remote that does not exist yet | Owner wires it when ready; tiers are verifiable by local inspection until then. |

---

## 10. Definition of done

The launcher is done when `design/DESIGN_NOTES.md` §11 is fully checked, every
delta in §9 of that document is either implemented or explicitly reported as
cut, all tiers are merged, and the unit tests in §7 above pass in CI.
