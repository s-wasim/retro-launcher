# V9 — Sky Rework, Weather Accuracy, Drawer & Permission Fixes

**Branch:** `V9`, cut from `origin/main` at `2765494` (V8 merged via PR #7).
**Version:** `versionName` → `2.0.0`. `versionCode` continues to derive from
`GITHUB_RUN_NUMBER + 1000`; it is not touched.

**Shipping order.** Part 1 (§1–§7) is built, committed and pushed on its own so
CI produces a testable APK **before** Part 2 (§8) begins. Part 2 lands on the
same branch afterwards.

---

## Part 1 — The sky

### §1 One clock drives colour and position

The defect behind "the light doesn't change when the sun reaches the bottom" is
a split source of truth. `SkyView` warps real time through `SolarClock` and
hands the warped hour to `SkyKeyframes` (colour) and `SkyRenderer.sunAlt`
(brightness) — but the body's *position* comes from
`SkyRenderer.sunAngle(hour)`, which hardcodes `0 at 06:00, π at 18:00`. Colour
follows the real sunset; position does not. They disagree by however far the
real sunset sits from 18:00.

The fix is not to tune the offset. It is to anchor the parabola to the same two
constants the keyframe table is already warped onto, so "sun at the bottom" and
"sky turns orange" become the *same event* rather than two events that need to
be kept in agreement:

```java
// core/BodyPath.java — pure, no Android type, fully unit-testable
static final float DAY_SPAN = SolarClock.SUNSET_ANCHOR - SolarClock.SUNRISE_ANCHOR;

/** 0 at the sunrise anchor, 1 at the sunset anchor; <0 and >1 during twilight. */
static float sunT(float warpedHour) {
    return (warpedHour - SolarClock.SUNRISE_ANCHOR) / DAY_SPAN;
}
```

`sunAngle` and `moonAngle` are deleted. Nothing else may compute a body
position.

### §2 The sun's parabola

Bottom-left → top-centre → bottom-right. Tangent to the top edge at solar noon,
so the whole disc is visible for the flare to bloom from; tangent to the bottom
edge at the anchors, so the sun sits *on* the horizon exactly when the sky goes
orange.

```java
static float sunX(float t, int w) { return t * w; }

static float sunY(float t, int w, int h, float r) {
    float u = 2f * t - 1f;                    // -1 at sunrise, 0 at noon, +1 at sunset
    return r + (h - 2f * r) * u * u;
}
```

| t | position | meaning |
|---|---|---|
| `0.0` | `(0, h-r)` | sunrise — tangent to bottom, half off the left edge |
| `0.5` | `(w/2, r)` | solar noon — tangent to top, dead centre |
| `1.0` | `(w, h-r)` | sunset — tangent to bottom, half off the right edge |

Past the anchors `u²` exceeds 1 and the parabola carries the disc below the
buffer on its own. The sun is therefore still partly visible through twilight
and clears the screen only once `sunY - r > h`, which satisfies "only
disappearing from screen once sunset is over" with no extra branch. `renderSun`
keeps its existing `if (sunY >= h + 18) return;` early-out.

### §3 The moon's sag, on its own clock

Moonrise has no relationship to sunrise, so the moon cannot share `sunT`. It
gets `moonT`, mapped over the moonrise → moonset window (§4), and the
asymmetric sag curve: a fall of 50% of screen height into the centre, then a
climb of 20% back up as it exits right.

```java
static float moonX(float t, int w) { return t * w; }

static float moonY(float t, int h) {
    float u = 2f * t - 1f;
    float drop = (t < 0.5f) ? 0.50f : 0.20f;   // fall 50% h, climb back 20% h
    return 0.5f * h - drop * h * u * u;
}
```

| t | y | meaning |
|---|---|---|
| `0.0` | `0` | enters at the top-left |
| `0.5` | `0.5h` | vertex, dead centre |
| `1.0` | `0.3h` | exits the right edge, 20% above the vertex |

The two branches meet at `t = 0.5` with zero slope on both sides, so the join is
smooth despite the differing coefficients. This is the property the unit test
asserts, not the endpoints alone.

**Fade.** The moon persists until the sun starts appearing, wherever it happens
to be on its track:

```java
float moonAlpha = 1f - SkyRenderer.smooth(-0.05f, 0.10f, sunAlt);
```

Plus a short fade-in/out at the `t = 0` and `t = 1` edges so it does not pop.

**Note on the entry point.** The moon appears already at the top edge at
moonrise rather than climbing in from below. This is the described intent and is
deliberate; it is recorded here because it is the one place the sky departs from
physical behaviour.

### §4 Real moonrise, offline

New `core/LunarMath.java`, mirroring `SolarMath`'s existing NOAA-derived style —
pure math, no I/O, no Android type. Low-precision Meeus lunar ecliptic
longitude/latitude → equatorial coordinates → hour angle at the standard
`+0.125°` moonrise altitude → local rise and set hours. Accurate to roughly ten
minutes, which is far finer than a 12px disc on a 108px-wide buffer can express.

```java
public static LunarTimes moonTimes(float lat, float lon, LocalDate date, ZoneId zone);
```

Returns null when the moon neither rises nor sets that day (it happens at any
latitude roughly once a month, since the lunar day is ~24h50m). Callers treat
null as "do not draw the moon", the same contract `SolarClock` already uses for
absent solar times.

`SolarTimes` gains two nullable fields rather than a parallel object, so one
cache entry and one `SkyView` setter carry everything:

```java
public final float moonriseHour;   // NaN when unknown
public final float moonsetHour;    // NaN when unknown
```

`WeatherRepository` caches them beside the existing `K_SOL_*` keys under
`K_SOL_MOONRISE` / `K_SOL_MOONSET`, invalidated by the same
`K_SOL_EPOCH_DAY` check. `WeatherRepository.solarTimes()` computes them from
`LunarMath` alongside the existing `SolarMath.sunTimes` fallback — no new
network call, and Open-Meteo is not asked for lunar data because it does not
serve it.

`moonT` handles the wrap where moonset falls after midnight, the same fold
`SolarClock.warp` already performs for night.

### §5 Lens flare

Drawn only at solar noon and only when clear. Because the sun is at top-centre
at `t = 0.5`, the flare axis is the screen's vertical midline and the ghosts
march straight down it.

```java
float flare = SkyRenderer.smooth(0.06f, 0.0f, Math.abs(sunT - 0.5f))  // noon proximity
            * (1f - conditions.cloudCover)
            * SkyRenderer.clamp01(sunAlt);
```

Rendering obeys the wallpaper's existing pixel-art rules — no alpha ramps, no
smooth falloff:

- 4–5 ghost discs spaced down the midline between the sun and the bottom edge,
  radii stepping down, positions derived from `flare` so they slide as noon
  passes.
- Each ghost is a flat **ring**, one or two pixels thick, written through the
  same `Bayer.M` ordered dither and 15-step `quantize()` every other layer uses.
- A short horizontal streak at the sun itself, drawn on the existing 8-ray
  scaffold in `renderSun`.

Gated hard at `flare < 0.02f` so it costs nothing for the rest of the day.

### §6 Weather becomes four independent channels

**Root cause of the inaccuracy.** One `w` scalar drives clouds *and*
precipitation together, through `cover = smooth(0.10, 0.66, w)` and
`precip = smooth(0.62, 0.98, w)`. Because both read the same input, cloud cover
is a strictly increasing function of rain intensity. Light drizzle therefore
*cannot* be drawn without heavy cloud, and a thunderstorm with no rain cannot be
drawn at all. Retuning the WMO table moves the problem; it cannot remove it.

```java
public final class Weather {
    public final int     tempC;
    public final String  label;
    public final float   w;               // derived — keeps the label and the cache working
    public final float   cloudCover;      // 0-1, its own layer
    public final float   precip;          // 0-1 intensity, its own layer
    public final Precip  type;            // NONE | RAIN | SNOW
    public final boolean thunder;         // gates lightning, independent of precip
    public final int     precipProbability;  // 0-100
}

public enum Precip { NONE, RAIN, SNOW }
```

`w` is retained and derived so `SyntheticWeather.label`, the persisted
`K_WX_W` value, and `ClockWidget`'s read-out keep working unchanged. The
derivation must be explicit about thunder, because `max(cloudCover, precip)`
alone would label a dry thunderstorm — high cloud, zero rain — as `RAIN`:

```java
static float derive(float cloudCover, float precip, boolean thunder) {
    if (thunder) return 1.0f;                    // >= 0.95 -> "THUNDERSTORM"
    if (precip > 0f) return 0.62f + precip * 0.36f;   // the precipitation bands
    return cloudCover * 0.62f;                   // the dry bands, below LIGHT RAIN
}
```

The two dry/wet ranges are kept disjoint at `0.62` so a cloud-only sky can never
cross into a rain label, which is the mislabelling that exists today.

**Request.** `OpenMeteoWeather` moves off the legacy `current_weather=true` onto
the modern parameter, on the same single GET:

```
&current=temperature_2m,weather_code,cloud_cover,precipitation,precipitation_probability
&daily=sunrise,sunset&timezone=auto&forecast_days=2
```

`WeatherParser.OBJECT_KEY` becomes `"current"`. The quoted-key match already
guards against `"current_units"` being mistaken for it, exactly as it does today
for `"current_weather_units"`. The null-on-anything-malformed contract is
unchanged: a missing channel falls back to the value implied by `weather_code`,
so a partial response degrades to today's behaviour rather than to nothing.

**Location.** `ACCESS_FINE_LOCATION` and `LocationSource.requestFresh` are
already in place and already send four-decimal coordinates. The inaccuracy was
the payload, not the fix; no location change is required.

**Layer mapping.** Each layer reads exactly one channel:

| Layer | Driven by | Was |
|---|---|---|
| clouds shown | `round(cloudCover × 14)` | `smooth(0.10,0.66,w)` |
| cloud darkness | `precip` and `thunder` | `smooth(0.55,1.00,w)` |
| rain drops | `round(260 × precip)`, `type == RAIN` | `smooth(0.62,0.98,w)` |
| snow | separate drift particles, `type == SNOW` | not drawn at all |
| lightning | `thunder` **only**, with a strike-rate floor | `w > 0.90` |
| stars | `1 - cloudCover` | `1 - cover`, and `w/0.30` |
| haze | `cloudCover` at low values | `smooth(0.06,0.24,w)` |

Consequences, all of which are the requested behaviour and none of which are
expressible today: light drizzle draws few clouds and slow sparse drops and
scales up continuously as rain intensifies; a thunderstorm with no precipitation
draws a dark sky and bolts and **zero** drops; snow is a real layer rather than
a label on rain.

**Signature.** `render()` would otherwise reach eight parameters, so the
conditions travel as one struct:

```java
public void render(int[] out, SkyConditions c, float seconds);

public final class SkyConditions {   // core, immutable
    public final float hour;         // warped
    public final float cloudCover, precip, moonPhase;
    public final Precip type;
    public final boolean thunder;
    public final int tempC;
}
```

This is the largest single piece of work in V9 and it ripples through
`SyntheticWeather`, `WeatherParser`, `WeatherRepository`, `ClockWidget`,
`SkyView` and their tests.

### §7 Heat shimmer

A post-pass in `SkyRenderer` after every layer and before `applyTint`, so the
distortion lands on the finished image and the tint ramp still posterizes
cleanly. A per-row horizontal shift of the `int[]` — no resampling, no
interpolation, which keeps it inside the pixel-art idiom:

```java
float shimmer = SkyRenderer.smooth(35f, 45f, tempC);          // off below 35°C
float amp = shimmer * 2.5f * (float) Math.pow(y / (float) h, 1.5);  // strongest near the horizon
int dx = Math.round(amp * (float) Math.sin(y * 0.35f + seconds * 2.2f));
```

Gated at `shimmer < 0.01f`, so it is free at ordinary temperatures.

### §7b Manual wallpaper override

A `WALLPAPER` section in `SettingsPanel` (left panel) holding one
`PixelToggle`, **`MANUAL WALLPAPER`**, off by default. When on, a `WALLPAPER`
block appears at the top of `ScreenTimePanel` (the usage panel) with:

| Control | Range |
|---|---|
| CLOUD COVER | 0–100% |
| PRECIPITATION | 0–100% |
| TEMPERATURE | −20 – 50°C |
| TIME OF DAY | 0–24h |
| MOON PHASE | 0–1 |
| THUNDER | toggle |
| SNOW | toggle |

`HomeActivity.refreshTime()` consults the override ahead of the repository, so
the sky follows the sliders live:

```java
Weather w = prefs.manualWallpaper()
        ? prefs.overrideWeather()
        : weatherRepository.current(decimalHour());
```

The widget continues to show `--°` for a synthetic reading; while the override
is on it shows the override's own temperature, since that value is now a
deliberate user choice rather than invented data.

Prefs keys: `K_WX_OVERRIDE`, `K_OV_CLOUD`, `K_OV_PRECIP`, `K_OV_TEMP`,
`K_OV_HOUR`, `K_OV_MOON`, `K_OV_THUNDER`, `K_OV_SNOW`. The setting persists
until switched off — it is a feature, not only a test harness.

**Widget work.** `LimitSlider` is welded to ±15-minute steps over 30–600 and
calls `UsageMath.snapLimit` directly, so it cannot be reused as-is. A generic
`PixelSlider` (min, max, step, label formatter) is extracted from it and
`LimitSlider` is re-expressed on top with no behaviour change to the existing
daily-limit control.

---

## Part 2 — Launcher fixes

### §8 Action keys flip

Long-press searches; double-tap locks.

```java
// HomeActivity ~L184-188 — the two listeners swap bodies
root.setLongPressListener(() -> { search.setPalette(palette); search.open(); });
root.setDoubleTapListener(this::lockDevice);
```

Three strings currently describe the old mapping and all three must change:

- `HintOverlay.java:40` — `"DOUBLE-TAP TO SEARCH", "LONG-PRESS TO LOCK"`
- `SettingsPanel.java:704` — `"LONG-PRESS THE HOME SCREEN TO LOCK, ONCE DEVICE LOCK IS ON."`
- `strings.xml` `shade_service_description` — "…and a long press lock the
  screen…". This one is user-facing in the Android Accessibility settings
  screen, so leaving it stale would misdescribe what the service does.

### §9 App drawer section headers

`AppEntry.firstLetter()` scans *forward* for the first `A-Z`, so `"1Password"`
finds `P` and files under **P** instead of **#**:

```java
public char firstLetter() {
    for (int i = 0; i < label.length(); i++) {          // ← the bug
        char c = Character.toUpperCase(label.charAt(i));
        if (c >= 'A' && c <= 'Z') return c;
    }
    return '#';
}
```

It must inspect character 0 only: a letter yields that letter, anything else
yields `#`.

`AppEntry` lives in the `app` module, which has no unit-test source set — only
`:core` does. The rule therefore moves into `core/DrawerSections.java` as a pure
function and `AppEntry.firstLetter()` delegates to it, matching how every other
piece of testable logic in this project is placed:

```java
// core/DrawerSections.java
public static char sectionFor(String label);   // 'A'-'Z', or '#'
```

`AlphaScrubber.setPresentLetters` gains `#`, which sorts first
naturally — the drawer sorts by lowercased label and digits precede letters in
ASCII, so the `#` group is already at the top of the list and the header lands
in the right place with no ordering change.

### §10 Permission reduction

Two removals, chosen because they are the largest trust signals that can go
without losing the lock gesture.

**`SYSTEM_ALERT_WINDOW`** — declared in the manifest, surfaced as a
`DRAW OVER APPS` row in Settings, and **never used**: there is no
`TYPE_APPLICATION_OVERLAY` anywhere in the source. It was added for an overlay
lock route that Shizuku superseded. Remove the `uses-permission`, the Settings
row and its listener, and `HomeActivity.hasOverlayPermission` /
`openOverlaySettings`.

**The device admin, entirely** — `LockAdminReceiver`, the `BIND_DEVICE_ADMIN`
receiver block, `res/xml/device_admin.xml`, `LockRoute.ADMIN`, the admin branch
in `HomeActivity.lockDevice`, and the `PIN ONLY` status and its Settings
caption. `LockRoute.choose` narrows to `(shizuku, accessibility)` and its test
narrows with it.

Lock survives on Shizuku → Accessibility, both of which keep the fingerprint
working. Admin was already documented as the route that costs the fingerprint
and was never `settled()`, so nothing that was previously a finished state is
lost.

**Retained, with reasons:** `INTERNET` (weather), `ACCESS_FINE_LOCATION` +
`ACCESS_COARSE_LOCATION` (accurate weather per §6), `PACKAGE_USAGE_STATS`
(screen time), `EXPAND_STATUS_BAR` (normal protection, no prompt, in use),
`VIBRATE` (normal protection, in use), `REQUEST_DELETE_PACKAGES` (normal
protection, required for the drawer's uninstall row). The accessibility service
stays because it is a lock route and the shade swipe. `QUERY_ALL_PACKAGES` is
not declared and must not be introduced by §11.

### §11 Cloned apps

`AppRepository.load()` uses `pm.queryIntentActivities`, which only ever returns
activities in the **calling user's profile**. OEM clones (Samsung Dual
Messenger, Xiaomi Dual Apps, OnePlus Parallel Apps) live in a secondary user
profile, so they are invisible to that call — the drawer is not deduplicating
them, it never saw them.

```java
LauncherApps la = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
for (UserHandle user : userManager.getUserProfiles()) {
    for (LauncherActivityInfo info : la.getActivityList(null, user)) { … }
}
```

Available since API 21 against a `minSdk` of 26, and it requires **no new
permission** — which is why it does not undo §10.

**Component keys.** The primary profile keeps the existing `pkg/activity` form
so every stored dock slot and category membership survives the change. Clones
take `pkg/activity@serial`, from
`userManager.getSerialNumberForUser(user)`. `AppEntry` gains the `UserHandle`
so launches can be routed correctly.

**Launching.** `Launch` and `AppActions` route through
`la.startMainActivity(component, user, sourceBounds, opts)`; a plain
`startActivity` cannot cross a profile boundary and would fail silently for
exactly the apps this section adds.

**Marker.** Clone rows render their label with a trailing `★`, tinted to the
palette accent so it reads as a badge rather than part of the name. Applied at
the view layer in `DrawerPanel.appView` and in `SearchOverlay`, never baked into
`AppEntry.label` — the label is what search matches against and what category
membership is keyed on.

**Out of scope.** Container-style cloners (Parallel Space and similar) run their
clones inside their own process rather than a user profile. They are invisible
to `LauncherApps` and to every other launcher on the device, and cannot be
fixed here.

---

## Testing

`:core` stays Android-free and carries the new logic, so all of it is JVM-unit-
testable:

| Suite | Asserts |
|---|---|
| `BodyPathTest` | sun tangent to bottom at both anchors and to top at noon; `sunX` monotonic left→right; moon endpoints `0 → 0.5h → 0.3h`; **zero slope on both branches at the moon's vertex** (the smooth-join property, not just the endpoints); both curves continuous across the anchor boundary |
| `LunarMathTest` | rise/set for known date+latitude pairs within ±15 min; null on a no-rise day; the moonset-after-midnight wrap |
| `WeatherParserTest` | each channel parsed independently; a missing channel falls back to the `weather_code` implication; dry thunderstorm yields `thunder && precip == 0`; snow yields `type == SNOW`; malformed bodies still return null |
| `SkyConditionsTest` | `w` derivation keeps existing labels stable across the WMO table; dry thunderstorm labels `THUNDERSTORM`, not `RAIN`; the dry and wet ranges never overlap |
| `LockRouteTest` | narrowed to two routes; `ADMIN` gone |
| `DrawerSectionsTest` | `"1Password" → '#'`, `"Zoom" → 'Z'`, `"7-Zip" → '#'`, `"…" → '#'`, empty label → `'#'` |

Renderer layer behaviour is verified by rendering into an `int[]` and asserting
pixel counts — the pattern `SkyRendererTest` already uses — for the three cases
that are impossible today: drizzle (low cloud, sparse drops), dry thunderstorm
(bolts, zero drops), and snow.

## Risks

1. **§6 is a breaking change** to `SkyRenderer.render()` and to `Weather`'s
   constructor, rippling through six files and their tests. It is the right fix
   — one scalar can never express a dry thunderstorm — but it is larger than the
   arcs, and it is why Part 1 ships and is tested before Part 2 starts.

2. **Cached readings predate the new channels.** `WeatherRepository.restore()`
   reads `K_WX_W` and would rebuild a `Weather` with no channel data. Restore
   derives the channels from the stored `w` as a one-time migration; the reading
   is replaced within thirty minutes by a real fetch, and stale entries are
   already discarded after twelve hours.

3. **`getSerialNumberForUser` on locked-down OEM builds.** Some ROMs restrict
   profile enumeration. `getUserProfiles()` returning only the primary user must
   degrade to exactly today's behaviour, not to an empty drawer.
