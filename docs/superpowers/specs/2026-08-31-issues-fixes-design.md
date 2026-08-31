# Uninstall Fix, Themed Input, and the Android 16 Audit

2026-08-31. Architectural addendum to `2026-08-28-retro-launcher-design.md`
and `2026-08-31-panel-fixes-and-launcher-controls-design.md`.

Scope: the five items in `issues.md`. Item 1 is closed as documentation.
Items 2, 3 and 4 are implemented in this round, in that order. Item 5 is
designed here but **gated** — it is not implemented until the owner
approves it separately, per their instruction in `issues.md`.

Branch: `V6`, cut from `main` at `47b6944` (level with `origin/V5`).

## 0. Phasing

Three phases, one branch, one commit and one CI build each:

| Phase | Content | Rationale |
|-------|---------|-----------|
| 1 | Issue 2 — uninstall/disable | Behaviour fix on the current SDK, verifiable in isolation |
| 2 | Issue 3 — themed input | Same; touches different files again |
| 3 | Issue 4 — API 36 audit | Large blast radius, landed against a known-good build |
| — | Issue 5 — icons | Gated on separate owner approval |

File overlap between phases is near zero. Phase 1 touches `DrawerPanel` and
the manifest's permission block; phase 2 touches `BottomSheet` and
`SearchOverlay`; phase 3 touches the Gradle files, `styles.xml`, the
manifest's activity block, and `HomeActivity`. Landing the SDK bump last
means a regression after it is attributable to it alone.

## 1. Issue 1 — the accessibility flag is accepted, not fixed

**Report.** Enabling this launcher's accessibility service makes the owner's
banking app refuse to run, warning that only system applications may use
accessibility. The owner asked whether a Play Store review would clear it.

**It would not.** The check is client-side RASP hardening inside the banking
app: at startup it enumerates enabled `AccessibilityService`s via
`AccessibilityManager` and refuses to proceed if any of them belongs to a
non-system package. It never consults Play. An approved accessibility
declaration governs whether Google will *distribute* the app; it has no
bearing on what a third-party app observes on the device. There is no change
this launcher can make — short of not shipping an accessibility service —
that alters the outcome.

**Decision (owner, 2026-08-31): keep `ShadeService` exactly as it is.** It is
already opt-in and off by default. It powers two conveniences only — the
status-bar swipe that expands the shade, and the fingerprint-friendly lock
route (`LockRoute.ACCESSIBILITY`, DESIGN_NOTES §9 delta 25). Both degrade to
working fallbacks when it is off: the shade swipe becomes a silent no-op via
the `StatusBarManager` reflection path, and the long-press lock falls back to
the device admin, which forces a PIN rather than accepting a fingerprint.

**Deliverable.** No code. A note in `DESIGN_NOTES.md` recording the finding
above, so this is not re-litigated: the trade-off is *fingerprint unlock and
shade swipe* against *banking apps running with the service enabled*, the
user chooses per-session by toggling the service in Android Settings, and no
Play review changes that.

## 2. Issue 2 — uninstall/disable does nothing

### 2.1 Symptom

Owner-confirmed, precisely bounded:

- Long-pressing an app in the drawer **does** show the quick-action popup.
- `LAUNCH` works.
- `MORE DETAILS` works — the system App Info page opens.
- `UNINSTALL / DISABLE` does **absolutely nothing**. No dialog, no
  confirmation, no error, no visible change. Identical on user-installed and
  preinstalled apps.

### 2.2 Root cause

`DrawerPanel.openUninstall()` fires:

```java
Intent intent = new Intent(Intent.ACTION_DELETE);
intent.setData(Uri.fromParts("package", app.packageName, null));
intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
try { getContext().startActivity(intent); }
catch (ActivityNotFoundException ignored) { }
```

`AndroidManifest.xml` declares four permissions — `INTERNET`,
`ACCESS_COARSE_LOCATION`, `PACKAGE_USAGE_STATS`, `EXPAND_STATUS_BAR`. It does
**not** declare `android.permission.REQUEST_DELETE_PACKAGES`. Since Android
8.0 (API 26 — and `minSdk` here is exactly 26), an app must hold that
permission to drive the package-uninstall flow. It is a `normal`-protection
permission: granted automatically at install, with no runtime prompt, but it
must be declared.

The failure mode this produces matches the symptom exactly, and rules the
other two candidates out:

- `startActivity` **succeeds**. The system's `PackageInstallerActivity` does
  resolve and does start — which is why no `ActivityNotFoundException` is
  thrown and the silent catch never runs.
- That activity then checks the calling package for
  `REQUEST_DELETE_PACKAGES`, does not find it, and `finish()`es before
  drawing anything.
- The user sees the launcher, unchanged. No dialog, no error, nothing.

The two secondary suspects raised during design are **not** the cause here,
though both are still worth closing:

- *Package visibility.* If the `<queries>` block were the problem,
  `startActivity` would throw `ActivityNotFoundException` and the popup's
  other Settings-deep-link action would fail too. `MORE DETAILS` works, so
  visibility filtering is not biting. Hardening it anyway costs three lines
  and removes a real API-30+ hazard.
- *The silent catch.* Not the cause, but it is why the bug was
  undiagnosable from the device. It stays fixed regardless.

**Consequence for the fix:** a fallback chain alone would not have helped.
`Launch.first()` advances only when `startActivity` *throws*, and here it
returns cleanly. The permission is the fix; the chain is insurance against a
different device failing a different way.

### 2.3 Fix

**Manifest.** Add the permission, and close the visibility hazard:

```xml
<uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES" />
```

```xml
<intent>
    <action android:name="android.intent.action.DELETE" />
    <data android:scheme="package" />
</intent>
```

**Honest actions.** `UNINSTALL / DISABLE` is one row promising two things,
only one of which a launcher can do. A launcher cannot disable an app —
`setApplicationEnabledSetting` requires device-owner or the app's own
package. What it can do is send the user to the App Info page, where the
system's own Disable button lives. So the single row becomes up to two,
chosen from the app's `ApplicationInfo` flags:

| App kind | Removal rows offered |
|----------|----------------------|
| User-installed (no `FLAG_SYSTEM`) | `UNINSTALL` |
| Updated system app (`FLAG_UPDATED_SYSTEM_APP`) | `UNINSTALL UPDATES`, `DISABLE` |
| Preinstalled, not updated (`FLAG_SYSTEM`) | `DISABLE` |
| This launcher itself | none |

The table governs the removal rows only. `LAUNCH` and `MORE DETAILS` are
offered for every app including this one, exactly as today.

`DISABLE` opens `ACTION_APPLICATION_DETAILS_SETTINGS` — the same intent
`MORE DETAILS` already uses successfully, so it is known to work on this
device.

**`core/AppActionPolicy`** — a pure class, no Android imports, holding the
table above. Input: the two boolean flags plus a self-package check. Output:
an ordered list of an `Action` enum (`UNINSTALL`, `UNINSTALL_UPDATES`,
`DISABLE`, `APP_INFO`, `LAUNCH`) with its display label. This is where the
decision logic is unit-tested, JVM-only, no emulator — matching how
`LockRoute`, `UsageMath` and `CategoryMap` are already tested in `core`.

**`app/util/AppActions`** — the Android side. Builds the intent for an
`Action` and dispatches it through the existing `Launch.first()` chain
(`ACTION_DELETE` → `ACTION_UNINSTALL_PACKAGE` → App Info), so a device that
fails differently degrades to something visible instead of nothing.

**No silent failures.** The bare `catch (ActivityNotFoundException ignored)`
blocks in `DrawerPanel` go. `Launch.first()` already logs each failed
candidate under `LaunchChain`; when the whole chain fails, the drawer shows a
themed on-screen notice rather than absorbing it.

`DrawerPanel.showAppActions()` builds its rows from `AppActionPolicy` instead
of the current hardcoded three.

### 2.4 Testing

- `core/AppActionPolicyTest` — the flag table above, one case per row, plus
  the self-package exclusion. TDD: tests first, they fail, then the class.
- Manual, on device: a user app uninstalls with a confirmation dialog; a
  preinstalled app offers `DISABLE` and lands on App Info; an updated system
  app offers both.

## 3. Issue 3 — themed input

### 3.1 Symptom

Adding a new application group uses a bare `EditText`
(`BottomSheet.java:89`), which renders with Material's underline, accent
caret, selection handles and system text colours — visibly foreign against a
launcher that is otherwise monospace, all-caps and palette-driven. A second
bare field exists in `SearchOverlay.java:81`.

### 3.2 Decision

Owner chose: **theme the field, keep the system keyboard.** A fully drawn
pixel keypad was considered and rejected for this round — it is a
substantial component with real accessibility cost (no IME switching, no
autocorrect, no language support, no clipboard), for a field the user touches
rarely. Not deferred with a promise; simply out of scope. If it is ever
wanted it gets its own spec.

### 3.3 Design

**`ui/PixelField`**, extending `EditText`, constructed like every other view
here — programmatically, `Metrics`-sized, `Tint`-roled:

- `Typeface.MONOSPACE` bold, sized via `metrics.textPx(...)` like its
  siblings.
- An all-caps `InputFilter` so typed text matches the category chips it
  becomes. Applied to the input, not `setAllCaps` (which is display-only and
  would let lowercase reach `Prefs`).
- `GradientDrawable` background: `palette.bg` fill, 1-unit `palette.p`
  stroke at `metrics.cqw(0.8f)`, matching the drawer's action box and the
  bottom sheet's panel — the launcher's established border idiom.
- Material underline removed by replacing the background outright.
- Blocky caret: `setTextCursorDrawable(...)` (API 29+) with a solid
  `palette.p` block. Below 29, the platform caret is left alone — a thin
  accent line on a three-generation-old device is an acceptable floor, and
  the alternative is drawing a caret by hand in `onDraw` against a moving
  text layout.
- Selection handles and highlight tinted to `palette.p` /
  `palette.p` at reduced alpha via `setHighlightColor` and
  `setTextSelectHandle*` (API 29+).
- `Tint.setRole(this, Tint.ROLE_INK)` for the text and a hint at
  `palette.ink` with reduced alpha, so palette changes propagate through the
  existing `Tint.apply` walk with no extra wiring.

**`ADD` button.** Currently a bare `TextView` beside the field. It gets the
same pixel border treatment so the row reads as one unit, and a minimum
touch target of `metrics.cqw(11f)` — the field is small and the current
target is text-sized.

**Call sites.** `BottomSheet.addField` and `SearchOverlay.field` both become
`PixelField`. `SearchOverlay` keeps its existing `InputMethodManager`
show/hide behaviour and does **not** get the all-caps filter — search should
match what the user typed.

### 3.4 Testing

`PixelField` is a `View` subclass; its logic that can be tested off-device is
the all-caps filter. That goes to `core` as a plain `CharSequence`
transform with unit tests. The rest is visual and verified on device: the
field's border matches the surrounding boxes, the caret is a block, and the
palette follows a theme change.

## 4. Issue 4 — the modern-Android audit

Target chosen by the owner: **API 36 (Android 16)**, which is Play's
requirement from August 2026. Current state: `compileSdk 34`, `targetSdk 34`,
`minSdk 26`, AGP 8.5.2, JDK 17.

`minSdk` stays at 26. Nothing in this round needs a higher floor, and raising
it drops devices for no gain.

### 4.1 Findings and actions

**A. Build tooling.** `compileSdk` and `targetSdk` → 36. AGP 8.5.2 does not
support compiling against 36 and moves to a version that does; the exact
version is pinned during implementation against the current AGP release
notes rather than asserted here, and the Gradle wrapper moves to whatever
that AGP requires. JDK 17 is already sufficient. `versionCode` /
`versionName` are untouched — the CI release workflow numbers builds itself.

**B. Edge-to-edge is mandatory on targetSdk 35+.** `styles.xml` sets
`android:statusBarColor`, `android:navigationBarColor` and
`android:windowDrawsSystemBarBackgrounds`; all three are deprecated and
ignored on 35+. They come out. `HomeActivity.java:279` sets
`SYSTEM_UI_FLAG_LAYOUT_STABLE | LAYOUT_FULLSCREEN | LAYOUT_HIDE_NAVIGATION`
via `setSystemUiVisibility`, deprecated since API 30 and a no-op on 35+; it
is replaced by `getWindow().setDecorFitsSystemWindows(false)` (API 30+),
guarded by `SDK_INT`, with the flag path retained below 30. The project has
no androidx dependency and this does not introduce one.

Because the app was already laying out fullscreen by intent, the *layout*
should be unchanged — but every panel that pads itself from insets must be
re-verified under real edge-to-edge, since the source of those insets
changes. That is six `onApplyWindowInsets` overrides: `HomePanel:118`,
`DrawerPanel:183`, `SettingsPanel:166`, `ScreenTimePanel:236`,
`SearchOverlay:128`, `BottomSheet:117`. Each is checked for content sitting
under the status bar or the gesture pill.

**C. Predictive back.** `HomeActivity.onBackPressed()` (line 604) keeps back
from leaving the home screen. On targetSdk 36 predictive back is on by
default and `onBackPressed()` is no longer called — that method would go dead
and back would start closing the launcher. Fix: declare
`android:enableOnBackInvokedCallback="true"` on `<application>`, register an
`OnBackInvokedCallback` at `PRIORITY_DEFAULT` (API 33+) carrying the current
body, and keep the `onBackPressed()` override as the pre-33 path. The
callback is registered and unregistered with the activity lifecycle.

**D. Orientation is no longer guaranteed.** On API 36, `android:screenOrientation="portrait"` is ignored on displays at or above
600dp — Android 16's adaptive-layout change. The activity declares
`portrait`. The layout is already `Metrics`-driven in `cqw` units, so it may
adapt cleanly; this is verified rather than assumed, on a large-screen
emulator in both orientations, and any panel that breaks is fixed. No
attempt is made to defeat the platform behaviour.

**E. `configChanges` is incomplete.** The activity declares
`uiMode|keyboardHidden|navigation` — not `orientation`, `screenSize`,
`screenLayout`, `density` or `fontScale`. Any of those recreates the
activity. That was survivable while orientation was locked; under D it stops
being. The missing values are added, and the resize path is checked to
confirm `SkyView`'s render thread and the panel state survive it.

**F. Non-SDK reflection.** `HomeActivity.java:539` reflects
`android.app.StatusBarManager#expandNotificationsPanel`. It is a non-SDK
interface and may be blocked outright on newer releases. It is already
wrapped in try/catch with `ShadeService` as the documented fallback, so it
degrades correctly — no change, but the behaviour is confirmed on an API 36
image and recorded in `DESIGN_NOTES.md` rather than left as an assumption.

**G. 16 KB page sizes.** Required for API 36. The project is pure Java with
no NDK, no `jniLibs`, and no dependency beyond `:core`, so there is no
native code to align. Confirmed by inspection and recorded; no action.

**H. `minifyEnabled true` on the debug build type.** Unusual: it runs R8 over
debug builds, slowing iteration and obscuring stack traces — which is part of
why issue 2 was hard to diagnose from the device. Recommended change: debug
`minifyEnabled false` and `shrinkResources false`; release keeps both. This
is flagged to the owner rather than assumed, in case it was deliberate for
APK size, and is the one item in this section that is a recommendation
rather than a requirement.

**I. `resConfigs "en"`.** Deprecated in AGP 8.x in favour of
`androidResources.localeFilters`. Migrated.

### 4.2 Testing

- `./gradlew test` — the existing `core` suite must stay green throughout.
- `./gradlew assembleDebug` on the bumped toolchain.
- The CI `build.yml` workflow, which already runs both.
- On device / emulator, API 36: back does not exit the launcher; no content
  under the status bar or gesture pill in any of the six panels; rotation on
  a large screen does not crash; the shade swipe still works or still
  degrades quietly.

## 5. Issue 5 — pixel-art icons (designed, gated)

**Not implemented without separate owner approval**, per `issues.md`. This
section is the proposal to approve or amend.

### 5.1 Current state

Two `IconSource` implementations exist behind a compile-time A/B toggle
(`HomeActivity.USE_POSTERIZED_ICONS`, currently `false`):

- `GeneratedTileIcons` — draws a hand-authored 12x12 mark from
  `core/PixelGlyphs` when the package has one (~40 exist), otherwise a
  `PixelTile` silhouette with the app's first letter.
- `PosterizedIcons` — loads the real system icon, downsamples to 16x16,
  quantizes each pixel to the palette's six-colour ramp with the shared Bayer
  bias, and scales back up. Written, working, and switched off.

So the request — recognizable pixel marks, with unmatched apps falling back
to a pixelated version of their real icon — is mostly a matter of combining
what is already there.

### 5.2 Design

**`icons/PixelizedIcons`**, one `IconSource` replacing both, resolving in
order:

1. A `PixelGlyphs` mark for the package, if one exists.
2. Otherwise the real icon through the posterize pipeline.
3. Otherwise — icon fails to load — the lettered tile.

The `USE_POSTERIZED_ICONS` toggle and the losing implementation are deleted,
as the original spec's §3.4 always intended ("whichever loses gets deleted,
not left as dead weight"). `InstrumentedIconSource` stays; it is the frame
-time measurement wrapper.

**Three fixes to the posterize step**, so branch 2 looks deliberate rather
than like a blurry screenshot:

- *Alpha threshold.* Pixels are currently kept at their source alpha, which
  leaves a soft translucent fringe around every shape — the one thing that
  most reads as "not pixel art". Alpha is thresholded to fully on or fully
  off.
- *Adaptive-icon safe zone.* `AdaptiveIconDrawable` reserves the outer ~18%
  of its bounds for masking. Drawing it at 16x16 unmasked keeps that dead
  margin, shrinking the actual artwork to roughly 10x10 of usable pixels.
  The foreground layer is drawn at its safe-zone scale so the mark fills the
  tile.
- *Outline.* An optional 1px `palette.p` outline around the opaque region,
  so a pale icon stays legible on a pale palette.

**More marks.** 25–40 new 12x12 entries in `PixelGlyphs`, in the existing
`mark(name, rows...)` format with the existing `p`/`h`/`a`/`s` ramp letters.
Which apps they cover should be driven by the owner's actual installed list
rather than guessed — that list is the first thing requested when this phase
is approved.

### 5.3 Testing

`PixelGlyphs` and `Quantize` are already `core`-tested. New marks extend
`PixelGlyphsTest` (each mark is 12 rows of 12 valid ramp characters, and
every package alias resolves). The alpha threshold and safe-zone scale are
pure functions and go to `core` with tests. The visual result is checked on
device across all palettes.

## 6. What this round does not do

- No pixel keyboard (§3.2).
- No removal or in-app toggling of `ShadeService` (§1).
- No `minSdk` change (§4).
- No recents panel — cut in the previous round and still cut.
- No icon work until §5 is separately approved.
