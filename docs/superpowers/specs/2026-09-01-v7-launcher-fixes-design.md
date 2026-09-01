# V7 — Launcher fixes: popups, haptics, usage accuracy, location, icons, versioning

**Date:** 2026-09-01
**Branch:** `V7`
**Status:** approved, ready for an implementation plan

Six changes, each independently shippable. They share no state, so the order
below is the order of least risk rather than a dependency chain — versioning
and signing (§6) go first, because until they land nothing else can be
verified on the owner's phone.

---

## 0. What the investigation changed

Three of the six requests turned out to describe a different defect than the
one the symptom suggested. Recording that here so the plan does not go and
build something that already exists:

| Request | What is actually wrong |
|---|---|
| "Weather uses the generic city" | It does not. `OpenMeteoWeather` already sends `latitude`/`longitude`. The limits are that the app holds only `ACCESS_COARSE_LOCATION`, never asks a provider for a fix (it reuses whatever fix another app last caused), and rounds to 3 decimals before sending. |
| "Tapping weather should open the weather app" | `ClockWidget.openWeather()` already tries, against nine known weather packages. It always fails on API 30+ because those packages are not declared in `<queries>`, so `getLaunchIntentForPackage` returns `null` for every one of them. |
| "Create pixel art icons from real icons" | `PosterizedIcons` already does exactly this. It is switched off behind `USE_POSTERIZED_ICONS = false` in `HomeActivity`, and it is wired as an *alternative* to the letter tiles rather than a stage between the hand-drawn glyphs and them. |

A fourth: updates fail for **two** reasons, not one. `versionCode` is frozen
at 1, and CI signs every build with a debug keystore the runner generates
fresh, so consecutive builds do not share a signature. Fixing only the first
would leave "App not installed" exactly where it is.

---

## 1. Long-press popup positioning

**Defect.** `DrawerPanel.showAppActions` ends in
`popup.showAsDropDown(anchor, 0, 0)`. That pins the popup to the *row's* left
edge and drops it below the row, so a long-press on an app near the bottom of
the drawer opens a menu that is partly or wholly off-screen, and a long-press
anywhere along a row opens the menu on the far left of it.

**Design.** A new `ui/AnchoredPopup.java` owns placement for every long-press
popup in the app.

```
show(View anchor, float touchScreenX, float touchScreenY, View content, int widthPx)
```

It measures `content` at `widthPx`, resolves the anchor's position with
`getLocationOnScreen`, and places the window with
`showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)` — not `showAsDropDown`,
which cannot express "at this point".

Placement rules, in order:

1. Start at the touch point.
2. If `y + height` would cross the display bottom less the navigation inset,
   flip: place the popup so its *bottom* sits at the touch point.
3. If `x + width` would cross the display right edge less its inset, shift
   left so the right edge is inset-aligned.
4. Clamp `x` and `y` to at least the top/left insets, so a popup taller than
   the space either way still starts on-screen.

**Getting the touch point.** `AdapterView.OnItemLongClickListener` does not
carry coordinates. `DrawerPanel` installs a pass-through
`listView.setOnTouchListener` that stores `getRawX()`/`getRawY()` on every
`ACTION_DOWN` and returns `false`, leaving the ListView's own handling
untouched. `DockView` does the same per slot.

**Call sites.** `DrawerPanel.showAppActions` and `DockView`'s long-press both
route through the helper. Both keep their current content and actions; only
placement changes.

---

## 2. Haptics

**Design.** Two new units.

`core/HapticCurve.java` — pure, unit-tested. Maps drag progress to vibration
amplitude:

- `amplitude(float progress)` → a squared ramp from a floor amplitude to full
  as the panel approaches its snap threshold, so the drag feels like it gains
  weight as it commits.
- The result is quantized to **8 buckets**. This is the reason the curve is
  its own unit: a 60 fps drag must not issue 60 `vibrate()` calls a second.
  Bucketing means the vibrator is re-commanded only when the bucket changes —
  at most 8 times across a full-width drag.

`util/Haptics.java` — the Android side. Wraps `Vibrator`
(`VibratorManager.getDefaultVibrator()` on API 31+, `getSystemService` below),
and checks the master preference on **every** entry point, so a single `false`
silences the whole app.

| Method | API 29+ | API 26–28 |
|---|---|---|
| `click()` | `EFFECT_CLICK` | 12 ms one-shot, amplitude 60 |
| `longPress()` | `EFFECT_HEAVY_CLICK` | 24 ms one-shot, amplitude 140 |

Drag feedback is a repeating waveform:

- `dragStart()` — begins a repeating `createWaveform(new long[]{0, 40}, new
  int[]{0, amp}, /* repeat */ 0)` at the floor amplitude.
- `dragProgress(float p)` — recomputes the bucket; restarts the waveform at
  the new amplitude only if the bucket changed.
- `dragEnd()` — `cancel()`.

Devices reporting `hasAmplitudeControl() == false` fall back to a plain on/off
repeating pattern at a fixed duty cycle, so the drag still buzzes; it just
does not swell.

**Wiring.** The gesture hooks already exist in `LauncherRoot`:

| Hook | Call |
|---|---|
| `seize()`, first seize of a gesture | `dragStart()` |
| `drag()`, per frame | `dragProgress(1 - reveal(translation, extent))` |
| `onTouchEvent` `ACTION_UP` / `ACTION_CANCEL` | `dragEnd()` |

`dragEnd()` must also run when a gesture is abandoned without an UP —
`onDetachedFromWindow` and `onPause` both call it, so a vibration can never
outlive the drag that started it.

`click()` goes on every interactive listener: dock slots, drawer rows, tab
chips, quick-action rows, settings chips/toggles/dock rows/permission rows,
bottom-sheet rows, search-overlay rows, the coffee button, the three clock tap
regions, limit-slider detent crossings, and alpha-scrubber letter changes.
`longPress()` goes on the two long-press surfaces from §1 and the category-tab
long-press.

**Permission.** `<uses-permission android:name="android.permission.VIBRATE"/>`
— normal protection, granted at install, no runtime prompt.

**Setting.** A new `FEEDBACK` section in `SettingsPanel` holding one
`PixelToggle`. Pref key `haptics`, **default on**. `Prefs` gains `K_HAPTIC`
and a `haptics()` getter alongside the existing ones.

---

## 3. App usage accuracy

**Defect.** `UsageRepository.scan` has three independent overcounting bugs.

1. `openedAt` is a `Map<String, Long>`, so several packages can be
   simultaneously "open". Android's own model has exactly one foreground
   activity; every extra open entry is time counted twice.
2. Any span without a matching `MOVE_TO_BACKGROUND` is closed at `end` — that
   is, at *now*. An app foregrounded before the screen went off, which never
   emitted a pause, is credited with every minute since. **This is the
   reported symptom: a background app counted as used.**
3. The headline number is not app time at all. `dayTotal` returns the device's
   screen-on span minus the launcher's own foreground time, so time staring at
   a lit screen with no app focused counts as app usage.

**Design.** The state machine moves out of the `app` module into
`core/ForegroundSpans.java`, as a pure function from a list of
`(packageName, eventType, timestampMillis)` tuples to two interval lists. It
is testable without a device, which the current loop is not — the `app` module
has no test source set at all.

Transitions:

| Event | Effect |
|---|---|
| `ACTIVITY_RESUMED` | close the focused span (whatever package) at `ts`; open `pkg` at `ts` |
| `ACTIVITY_PAUSED`, `ACTIVITY_STOPPED` | close the focused span **only if** it is `pkg` |
| `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`, `DEVICE_SHUTDOWN` | close the focused span at `ts` |
| `SCREEN_INTERACTIVE` | open a screen-awake window |
| `KEYGUARD_HIDDEN` | open a screen-awake window if none is open |

At the end of the scan a still-open span is closed at `end` **only if the
screen is currently interactive**. Otherwise it is discarded. That single rule
is what fixes bug 2.

`ACTIVITY_STOPPED` is API 29+; on 26–28 the machine simply never sees it and
relies on `ACTIVITY_PAUSED` plus the screen-off close, which is a truthful
degradation rather than a wrong number.

`UsageMath` gains two pure, tested helpers:

- `merge(List<Interval>)` — coalesces overlapping or touching spans per
  package, so no arrangement of events can double-count a minute.
- `intersect(List<Interval> spans, List<Interval> windows)` — clips app spans
  to the screen-awake windows.

`dayTotal` becomes: merge, intersect with screen-awake, drop the launcher's
own package, sum. `resolveTotal` and its screen-on branch are **deleted**,
along with `UsageMathTest`'s coverage of it. `mostUsedToday` runs on the same
merged, clipped list, so the per-app rows and the headline finally agree.

`pickupsToday` currently runs a second, independent `queryEvents` pass over
the same window. It folds into the single scan, counting `KEYGUARD_HIDDEN`
events as it goes.

**Tests** (`core`, no device): an app left running in the background across a
screen-off; an app foregrounded and never paused, with the screen currently
off; two `ACTIVITY_RESUMED` events with no pause between them; overlapping
spans for one package; a span straddling midnight; a day with no screen events
at all.

---

## 4. Weather — precise fix, and a weather tap that works

**Manifest.**

- Add `ACCESS_FINE_LOCATION` beside the existing `ACCESS_COARSE_LOCATION`.
- Add a `<package android:name="…"/>` entry to `<queries>` for each of the
  nine packages in `ClockWidget.WEATHER_PACKAGES`. This alone is what makes
  the existing tap-to-open-weather work on API 30+.

**`LocationSource`.**

- `hasPermission()` returns true when *either* location permission is granted.
- New `requestFresh(Consumer<double[]> callback)`: `getCurrentLocation()` on
  API 30+, `requestSingleUpdate()` on 26–29, preferring the GPS/fused provider
  when `ACCESS_FINE_LOCATION` is held and the network provider otherwise.
  Bounded by an **8 second** timeout after which the callback fires with the
  last-known fix, or `null`. It never registers for continuous updates —
  the existing "a launcher has no business following you around" constraint
  stands.
- `lastKnown()` is unchanged and remains the fallback.

**`WeatherRepository.refresh`** becomes two-stage: ask for a fresh fix →
on timeout or failure fall back to `lastKnown()` → then to the remembered fix
in prefs → and only then give up. The existing 30-minute freshness window and
10-minute floor are untouched, so this costs at most one location fix every
ten minutes, and only when a fetch was already going to happen.

**`OpenMeteoWeather.coord`** goes from 3 decimals to 4 (~11 m). The
`Locale.US` formatting stays; a comma-decimal locale would still 400.

**`ClockWidget`.** The weather region gains a long-press listener that always
forces a refresh (`setOnWeatherLongPress`). The tap keeps its current
behaviour — open a weather app, falling back to a forced refresh when there
genuinely is not one — which now actually resolves.

**`HomeActivity`** requests both location permissions in one prompt.
`SetupScreen` and `SettingsPanel` copy is updated to say the fix is precise.

---

## 5. Pixel-art icons

**Design.** `USE_POSTERIZED_ICONS` and the either/or wiring are deleted.
`GeneratedTileIcons` is renamed to `icons/PixelArtIcons.java` and implements a
three-stage fallback chain, evaluated per app:

1. **Hand-drawn mark.** `PixelGlyphs.forPackage(packageName)` — unchanged,
   16×16, palette-role coloured. Big-name apps keep their crafted marks.
2. **Converted real icon.** Load the activity icon (falling back to the
   application icon). Render at **24×24**. On API 26+, an
   `AdaptiveIconDrawable` is first cropped to its centre safe zone (72/108 of
   the bounds) so the conversion sees the logo rather than a full-bleed
   background plate. Map every non-transparent pixel to the nearest colour in
   `palette.ramp()` via the existing `Quantize.nearestIndex` with its Bayer
   bias — the same quantization the wallpaper tint uses, so icons and
   wallpaper share one colour language. Upscale to the requested size with
   `createScaledBitmap(..., false)`; nearest-neighbour is what keeps it pixel
   art rather than a blurry small icon.
3. **Letter tile.** Only when the app genuinely has no icon.

**"Genuinely has no icon"** is a real test, not an exception check, because
the platform hands back a generic placeholder rather than throwing.
A new `core/IconCoverage.java` provides `isBlank(int[] pixels)` — true when
more than 97 % of the pixels are fully transparent. Stage 3 is entered when
the drawable is null, *or* resolves to the platform's default
`sym_def_app_icon`, *or* `isBlank` reports the 24×24 render as empty.

**Cost.** One 24×24 conversion per app per palette, cached in the existing
4 MB `IconCache` keyed by component + palette + size. `InstrumentedIconSource`
stays as a debug-only measuring wrapper.

**Out of scope.** Drawing *additional* hand-drawn `PixelGlyphs` marks — the
first half of `issues.md` item 5 — is deliberately not part of this change.
The chain above means every app without a mark now gets a converted real icon
instead of a letter, which is the outcome that item was reaching for.

---

## 6. Versioning and signing

**Two defects, both of which must be fixed for updates to install.**

**`versionCode`.** Frozen at 1, so every build looks like the same version to
the package manager. In `app/build.gradle`:

```groovy
versionName "1.0.0"
versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInteger() + 1000
```

The `+ 1000` offset guarantees the first V7 build exceeds the installed
`versionCode 1` by a wide margin regardless of the run number, and CI's
monotonically increasing run number does the rest. A local build gets 1000,
which is fine — local builds are not what gets updated over.

**Signing.** CI runs `assembleDebug` on a fresh `ubuntu-latest` runner, which
generates a new `~/.gradle/debug.keystore` on every run. Android refuses to
install an update whose signing certificate does not match the installed
app's, which produces "App not installed" no matter what `versionCode` says.

Fix: generate `app/debug.keystore` once with the standard
`androiddebugkey` / `android` credentials, **commit it**, and point an
explicit `signingConfigs.debug` at it, applied to both build types. A debug
keystore carries no security value — it is a well-known key pair by design —
so committing it is the ordinary practice for a personally sideloaded app.
`.gitignore` is checked so the file is not excluded.

> **One-time manual step for the owner:** the currently installed launcher was
> signed with a different, now-lost key. It must be **uninstalled once** before
> the first V7 build will install. Every build after that updates cleanly.

`BUILD.md` gains a short section recording both facts, so the next person to
touch the workflow does not undo them.

---

## Files

**New (6)**

| File | Module |
|---|---|
| `ui/AnchoredPopup.java` | app |
| `util/Haptics.java` | app |
| `core/HapticCurve.java` | core |
| `core/ForegroundSpans.java` | core |
| `core/IconCoverage.java` | core |
| `app/debug.keystore` | — |

**Renamed (1)** — `icons/GeneratedTileIcons.java` → `icons/PixelArtIcons.java`
(absorbing `PosterizedIcons`, which is deleted).

**Modified (21)** — `AndroidManifest.xml`, `app/build.gradle`,
`HomeActivity.java`, `LauncherRoot.java`, `DrawerPanel.java`, `DockView.java`,
`SettingsPanel.java`, `BottomSheet.java`, `SearchOverlay.java`,
`ClockWidget.java`, `AlphaScrubber.java`, `LimitSlider.java`,
`CoffeeButton.java`, `Prefs.java`, `LocationSource.java`,
`WeatherRepository.java`, `OpenMeteoWeather.java`, `UsageRepository.java`,
`UsageMath.java`, `SetupScreen.java`, `BUILD.md`.

**Deleted** — `UsageMath.resolveTotal` and its tests;
`icons/PosterizedIcons.java`; `HomeActivity.USE_POSTERIZED_ICONS`.

## Testing

New `core` unit tests for `ForegroundSpans` (the six cases in §3),
`UsageMath.merge` / `intersect`, `HapticCurve` (bucketing, monotonicity,
clamping), and `IconCoverage.isBlank`. `UsageMathTest` is updated for the
deleted `resolveTotal`.

Everything else is device behaviour and is verified on a local emulator built
from the toolchain installed for this work: popup placement against the bottom
and right edges, haptic feel on tap and through a full-width drag, the icon
chain across apps with a glyph / with a real icon / with none, a fresh
location fix, and a weather tap that opens a weather app.
