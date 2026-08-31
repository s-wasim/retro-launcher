# Design notes — "Retro Launcher" prototype

Source: `design/prototype/Retro Launcher standalone.html` — a Claude Design
self-extracting bundle (React + a small custom template runtime, gzip+base64
inline; not literal HTML/CSS/JS you can read in an editor).

**How it was extracted** (repeatable):

1. The file carries four `<script type="__bundler/*">` blocks: `manifest`,
   `ext_resources`, `page_order`, `template`.
2. `manifest` is JSON mapping UUID → `{mime, compressed, data}`. Three
   `text/javascript` entries are gzip+base64; `gunzip` them.
3. The largest (131 KB decompressed) is the dc-runtime. The 69 KB one holds
   the app: a `Component extends DCLogic` class (867 lines) plus its `x-dc`
   markup template (389 lines), recoverable from the `template` block.

This is a **visual + behavioral reference only**, per `HANDOFF.md` §2. Nothing
is copied as HTML/CSS/JS. No `WebView`. No font files. This document is the
audit trail from prototype token to native XML/Java equivalent.

> **Revision note.** An earlier draft of this file was written from a partial
> read and got three things wrong: it omitted the navigation model entirely,
> described the home screen as if it carried the app list, and claimed the
> animated wallpaper had "no cheap native equivalent." All three are corrected
> below. Colors, sky keyframes, palette thresholds and type scale from that
> draft were re-verified against the source and were accurate.

---

## 1. Screen model and navigation

**This is the structural fact everything else hangs off.** The prototype is
not one screen. It is four panels living simultaneously inside one container,
positioned offscreen and moved by dragging.

```
                    ┌───────────────┐
                    │  SCREEN TIME  │
                    └───────┬───────┘
                            │  ↑ swipe up
          ┌──────────┐   ┌──┴───┐   ┌──────────────┐
          │ SETTINGS ├───┤ HOME ├───┤  APP DRAWER  │
          └──────────┘   └──────┘   └──────────────┘
            swipe →                    ← swipe
```

| Panel | Resting position | Enter from home | Threshold |
|---|---|---|---|
| Home | `0,0` | — | — |
| Settings | `left:-100%` | swipe **right** | `dx > 0.22 × W` |
| App drawer | `left:100%` | swipe **left** | `dx < -0.22 × W` |
| Screen time | `top:100%` | swipe **up** | `dy < -0.16 × H` |

Behavioral detail from `onDown`/`onMove`/`onUp`:

- **Axis lock.** No axis is chosen until the finger moves 12px; whichever of
  `|dx|`/`|dy|` is larger wins and is then locked for the gesture.
- **1:1 tracking.** While dragging, the panel's transform follows the finger
  exactly, clamped to `[0, W]` / `[-W, 0]` / `[-H, 0]`.
- **Settle.** On release, `transition: transform 260ms cubic-bezier(.2,.7,.2,1)`.
  During a drag the transition is `none`, so tracking never lags.
- **Opt-out regions.** Any element under `[data-noswipe]` — every scrolling
  list, the tab strip, the A–Z scrubber, the limit slider, the sheet —
  swallows the gesture so panel navigation can't fight inner scrolling.
  **Native takes this per axis** — see §9 delta 14.
- **Reverse swipes** return to home; each panel also has an explicit
  `CLOSE`/`HOME` button in its header.

**The home screen has no app list.** It is wallpaper + clock/weather widget +
dock, nothing else. The scaffolded `HomeActivity.java` — a full-screen
`ListView` of apps — corresponds to the **drawer**, not the home screen.

**Native equivalent:** one `Activity` hosting a custom `ViewGroup` that lays
out four child panels at those offsets and translates them on touch. Not
separate activities — activity transitions cannot track a finger, and the
wallpaper renderer must not restart on navigation.

---

## 2. Everything is driven by time of day

Two independent systems key off the current hour. Neither is a manual theme
toggle by default.

### 2a. Auto palette (`pal: "auto"`, the shipped default)

From `autoPal()` — hour only, no weather input:

| Local hour | Palette | Label shown in Settings |
|---|---|---|
| 00:00–04:36 | `c64` (C64 Blue) | NIGHT |
| 04:36–07:36 | `amber` (CRT Amber) | SUNRISE |
| 07:36–11:00 | `gb` (Game Boy) | MORNING |
| 11:00–16:00 | `mono` (Mono Grey) | MIDDAY |
| 16:00–18:36 | `amber` (CRT Amber) | GOLDEN HOUR |
| 18:36–20:24 | `plasma` (Plasma Red) | DUSK |
| 20:24–24:00 | `c64` (C64 Blue) | NIGHT |

Source thresholds are decimal hours: `4.6, 7.6, 11, 16, 18.6, 20.4`.

**Native equivalent:** compute from `Calendar` on load and on a minute tick.
No library.

### 2b. The pixel sky wallpaper

A small canvas, nearest-neighbour upscaled to fill the screen — deliberately
chunky. **Redrawn every animation frame.**

- **Buffer size:** `W = 108` fixed. `H = clamp(round(108 × viewH / viewW), 96, 320)`.
  On a 9:19.5 phone that's ≈234. (The `<canvas>` element's `height="192"`
  attribute is a placeholder immediately overwritten by `sizeCanvas()`.)
- **Base gradient:** `mix(top, bot, pow(ty, 0.85))` where `ty = y/(H-1)` —
  *not* linear. Endpoints interpolate through 14 hourly keyframes:

| Hour | Top | Bottom | | Hour | Top | Bottom |
|---|---|---|---|---|---|---|
| 00:00 | `#0a0e26` | `#161c40` | | 15:30 | `#4086e0` | `#b2cef0` |
| 03:30 | `#0b0f2a` | `#22204c` | | 17:12 | `#4e84ce` | `#f8c498` |
| 05:00 | `#14183e` | `#523868` | | 18:24 | `#3e54a4` | `#ff8e58` |
| 06:12 | `#2e3e80` | `#d67668` | | 19:18 | `#282c6e` | `#ce5460` |
| 07:00 | `#3e68b6` | `#ffa670` | | 20:24 | `#161b46` | `#603468` |
| 08:30 | `#4488d8` | `#b0d6f6` | | 22:00 | `#0c102c` | `#24224e` |
| 12:00 | `#3682e4` | `#9cd0f7` | | 24:00 | `#0a0e26` | `#161c40` |

Both endpoints are scaled by `dark = 1 − 0.42 × storm` before use.

**Derived scalars** (`w` = weather 0–1; `smooth` is smoothstep):

```
sunAlt   = sin((hour − 6)/12 × π)        day      = clamp(sunAlt×3 + 0.35)
night    = 1 − day                        twilight = smooth(0.45, 0.02, |sunAlt|)
storm    = smooth(0.55, 1.00, w)          cover    = smooth(0.10, 0.66, w)
precip   = smooth(0.62, 0.98, w)          haze     = smooth(0.06,0.24,w)
                                                   × (1 − smooth(0.30,0.50,w))
```

**Body positions:**

```
thSun = (hour−6)/12 × π          thMoon = thSun + π       travel = 0.3125 × H
SUN_X  = 72 − cos(thSun)  × 60   sunY  = 0.667×H + (1 − sin(thSun))  × travel
MOON_X = 36 − cos(thMoon) × 60   moonY = 0.333×H − (1 − sin(thMoon)) × travel
```

**Layers, in draw order:**

| Layer | Detail |
|---|---|
| Gradient + glows | Sun glow r=78, `pow(1−d/78, 2.2)` toward `[255,150,70]`. Moon glow r=46, `pow(1−d/46, 2.4)` toward `[140,165,220]`. Haze lifts toward grey by height. |
| Quantize | Bayer 4×4 ordered dither, then snap to 15-level steps: `round((v + d)/15) × 15`, `d = (bayer/16 − 0.5) × 16`. **This is what produces the retro banding** — not an afterthought. |
| Stars | 130, night only, twinkle `0.55 + 0.45·sin(T×1.7 + φ)`, killed by cloud cover. 10% are 2px. |
| Moon | r=12 disc, real phase terminator `cos(2πq)·√(1−ny²)`, 8 craters with lit rims, dithered edge. |
| Sun | r=13 disc, 3-tone dithered, 8 rays whose length pulses `sin(T×1.6)`. Colors lerp horizon-red → noon-white by altitude. |
| Clouds | 14 procedural clusters (4–6 circular puffs each, seeded LCG). `round(cover × 14)` shown. Drift speed `0.35 + 2.4w`. 3-tone shading by vertical position within the cluster. |
| Lightning | When `w > 0.90`: random jagged bolt, 7 frames, plus a full-screen white flash decaying `×0.72` per frame. |
| Rain / snow | 260 particles, `round(260 × precip)` active. Rain draws 2–4px slanted streaks; snow drifts sinusoidally. |
| Palette tint | *Optional, default off.* Posterize the whole frame to the palette's 6 colors sorted by luminance, with Bayer dithering. |

**Native equivalent — this ports directly, contrary to the earlier draft.**
The buffer is ~25K pixels. A Java loop writing into an `int[]` → `Bitmap`,
blitted to a `SurfaceView` with `FILTER_BITMAP = false`, is ~760K px/s at
30fps — comfortable, and costs zero APK size. Use a dedicated render thread so
per-pixel work never touches the UI thread, and pause it when the launcher
isn't visible.

---

## 3. Color palettes (5 palettes × light/dark)

Six roles: `bg` (screen), `tile` (icon body), `p` (primary/accent),
`a` (secondary), `s` (shadow), `h` (highlight), plus `ink` (text).

| Palette | Theme | bg | tile | p | a | s | h | ink |
|---|---|---|---|---|---|---|---|---|
| Game Boy | light | `#dcebb4` | `#33552a` | `#a4c93c` | `#eaf8a8` | `#1b3311` | `#f6ffdc` | `#1b3311` |
| Game Boy | dark | `#0b1508` | `#1d3315` | `#8bac0f` | `#cfe89a` | `#0f2408` | `#eeffc4` | `#a4c93c` |
| CRT Amber | light | `#f2e5c8` | `#3b2612` | `#e79a20` | `#ffd873` | `#7d3f0c` | `#fff3d2` | `#3b2612` |
| CRT Amber | dark | `#140c05` | `#2b1a0a` | `#ffb020` | `#ffd873` | `#6d3407` | `#fff0c8` | `#e79a20` |
| C64 Blue | light | `#dadef8` | `#3a2f8f` | `#7c70da` | `#b9c8ff` | `#221a5e` | `#f2f4ff` | `#221a5e` |
| C64 Blue | dark | `#0b0820` | `#221a5e` | `#7c70da` | `#b9c8ff` | `#140f3a` | `#eef1ff` | `#9a90ff` |
| Mono Grey | light | `#e4e4e6` | `#3a3c40` | `#8e9196` | `#c6c9ce` | `#22242a` | `#fbfbfd` | `#22242a` |
| Mono Grey | dark | `#0c0d0f` | `#24262b` | `#9aa0a8` | `#c6c9ce` | `#15171b` | `#f2f4f8` | `#c6c9ce` |
| Plasma Red | light | `#f6dcd6` | `#4a1418` | `#e2464a` | `#ff9a86` | `#7d1418` | `#ffe8de` | `#4a1418` |
| Plasma Red | dark | `#140507` | `#33090d` | `#ff4a4a` | `#ff9a86` | `#6d0d10` | `#ffdcd2` | `#ff9a86` |

Derived: **`veil` = `bg` + alpha** — `D9` in dark, `E0` in light. Used as the
background of the clock widget, dock, and A–Z scrubber.

**Defaults:** palette `auto`, theme `dark`, tint `off`.

**Native equivalent:** a `Palette` class holding the 10 sets as `int[]`, plus a
`Tint.apply(View)` walker that repaints a view tree. Static XML styles won't
work — the palette changes with the hour.

---

## 4. Typography

| Prototype token | Value | Native equivalent |
|---|---|---|
| Display font | `'Press Start 2P'` (Google Font, 5 `.woff2` subsets, ~57 KB) | `Typeface.MONOSPACE` bold + `letterSpacing`. **Substitution flagged per HANDOFF §2** — a pixel font would cost more than the whole APK. |
| Body font | `ui-monospace, 'SF Mono', Menlo, Consolas, monospace` | `monospace` |
| Clock digits | `9.4cqw`, line-height 1 | ~34–38sp |
| Clock AM/PM | `3.6cqw`, tracking `.08em`, opacity `.8` | ~13sp, `letterSpacing 0.08` |
| Date line | `3.4cqw`, uppercase, tracking `.16em`, opacity `.82` | ~13sp |
| Weather line | `3.4cqw`, uppercase, tracking `.14em` | ~13sp |
| Panel header | `4.4cqw`, tracking `.06em` | ~16sp, monospace bold |
| Screen-time total | `8cqw` | ~30sp |
| Body / list labels | `2.7–3.2cqw` | ~10–12sp |
| Micro labels | `2.2–2.5cqw` | ~8–9sp — **below Android's 12sp comfort floor; legibility risk flagged** |

**Amendment, 2026-08-28 (issue #6):** the prototype's blinking colon and
seconds display are permanently removed from the native app. The colon
between hours and minutes is always solid (no `blink` keyframe, no toggle),
and seconds are never rendered. This is a fixed design decision, not a user
preference — do not reintroduce a `blink` or `seconds` setting. `ClockWidget`
enforces this directly; `Prefs` no longer exposes `blink()`/`seconds()`.

---

## 5. Layout & spacing

The prototype sizes nearly everything in `cqw` — percent of the phone frame's
own width, via CSS `container-type: size`. **Spacing scales with screen width.**

| Token | cqw | ≈dp @380dp |
|---|---|---|
| Panel edge padding | `4.5cqw` | ~17dp |
| Panel header padding | `5cqw 4.5cqw 3cqw` | ~19/17/11dp |
| Widget padding | `3.2cqw 3.6cqw` | ~12×14dp |
| Widget / dock border | `0.7cqw` | ~2.7dp |
| Widget offset (top/right) | `4cqw` | ~15dp |
| Dock offset (left/bottom) | `4cqw` | ~15dp |
| Dock slot | `13cqw` square | ~49dp |
| Dock gap | `2.6cqw` | ~10dp |
| Drawer row icon | `9cqw` | ~34dp |
| Drawer row padding | `1.8cqw 4.5cqw` | ~7/17dp |
| A–Z scrubber width | `7cqw` | ~27dp |
| Settings row border | `0.6cqw` | ~2.3dp |
| Settings section gap | `6cqw` | ~23dp |
| Sheet top border | `0.8cqw` | ~3dp |
| Weekly bar chart height | `26cqw` | ~99dp |

**Native equivalent:** because `cqw` is literally "percent of screen width,"
resolve it at runtime — `Metrics.cqw(4f)` against `displayMetrics.widthPixels`
— rather than freezing fixed dp. Four lines of Java, and the proportions
survive a 320dp phone and a foldable alike.

**Not applicable:** the prototype's own device-frame chrome (`30px` bezel
radius, `22px` screen radius, `#15171c` body). That simulates a phone. The
real launcher *is* the phone.

---

## 6. Icon system

The prototype does **not** use installed apps' real icons. Each of ~52
hardcoded demo apps has a hand-authored **12×12 pixel glyph** as an array of
12 strings, characters `. p a s h` selecting palette roles.

Rendering (`compose()` / `runs()`):

1. Build a **16×16** grid. Fill a rounded-square silhouette from `TILE_SPAN`
   (per-row `[startX, endX]`, insetting 3,2,1,0… at the corners) with `tile`.
2. Composite the 12×12 glyph at offset `(+2, +2)`, mapping `p a s h` to the
   palette roles.
3. Run-length encode each row into horizontal spans, emitted as `<rect>`s of
   height 1. Cached per `(app, palette)`.

**This does not generalize to arbitrary installed apps** — which is what a real
launcher enumerates via `PackageManager.queryIntentActivities()`. See §9 for
the decision.

---

## 7. Screen inventories

### 7a. Home

Wallpaper canvas (full bleed) + two floating blocks:

- **Clock/weather widget** — top-right, `veil` background, `0.7cqw` primary
  border, `backdrop-filter: blur(2px)`. Three stacked lines: `H : MM` (no
  seconds, solid colon — see amendment above) with optional AM/PM; the
  formatted date; a weather row (a `1.6cqw`
  accent square, the temperature, then the condition at `.6` opacity).
  **No click handlers at all in the prototype** — it is a pure read-out.
- **Dock** — bottom-left, same veil/border treatment. Up to **5** slots, each
  a `13cqw` glyph over a `2.5cqw` uppercase caption. Long-press a slot →
  replace sheet. If fewer than 5, a trailing dashed `+` slot → add sheet.
  **Tapping a dock app does nothing in the prototype** (`onClick: () => {}`).

### 7b. App drawer

Header `APPS` + `HOME` button. Then:

- **Tab strip** — horizontally scrolling; `ALL` plus user categories
  (ships `SOCIAL WORK MEDIA UTILITY`). Non-`ALL` tabs carry an `×` to delete.
  A trailing dashed `+` opens an inline uppercase text field + `ADD`.
- **List** — alphabetical. `A`/`B`/`C` section headers, each a letter followed
  by a `0.5cqw` rule. Rows: `9cqw` glyph, label at `3.2cqw`, and the app's
  categories joined by `·` at `2.3cqw`/`.45` opacity, or `UNSORTED`.
  Long-press a row → category sheet.
- **A–Z scrubber** — fixed `7cqw` right rail, veil background, primary left
  border. All 26 letters; present ones at `.95` opacity, absent at `.25`.
  Press and drag to jump the list to that letter's header.

### 7c. Settings

Header `SETTINGS` + `CLOSE`. Four sections, `6cqw` apart:

| Section | Contents |
|---|---|
| **PALETTE** | 2-column grid of 6 cards: `AUTO / TIME` (with a live note like `MORNING · GAME BOY`) then the 5 fixed palettes. Each card shows its name and 6 color chips. Selected card gets a `p + 2E` wash. Below: `LIGHT`/`DARK` segmented pair, then a `TINT WALLPAPER TO PALETTE` row with a pixel toggle switch. |
| **CLOCK & DATE** | One chip toggle: `12-HOUR`/`24-HOUR` (no `SECONDS` or `BLINK COLON` toggles — removed permanently, see amendment §4). Then 6 date-format rows (`DD MMM YYYY`, `MMM DD`, `DDD DD/MM`, `YYYY-MM-DD`, `DOY / WK`, `CUSTOM`), each showing its token string and a live preview. Choosing `CUSTOM` reveals a dashed builder: a `CLEAR` button, a preview strip, and 15 tappable token chips (`DD D MMM MMMM MM YYYY YY DDD DDDD DOY WK / - SPACE ,`) that append to the format string. |
| **TEMPERATURE** | `CELSIUS °C` / `FAHRENHEIT °F` pair, plus the caption `READ FROM WALLPAPER STATE — <condition> · <LIVE CLOCK\|MANUAL SKY>`. |
| **DOCK — BOTTOM LEFT** | One row per dock slot (glyph, label, `REPLACE`), plus an `EMPTY SLOT` / `ADD` row when under 5. Caption: `LONG-PRESS A DOCK SLOT ON THE HOME SCREEN TO REPLACE IT. MAX 5.` |

### 7d. Screen time

Header `SCREEN TIME` + `CLOSE`. **All data is hardcoded in the prototype.**

- `TODAY` total at `8cqw` (from `USE` + 96 minutes), and `PICKUPS` (literal 87).
  Native puts the `BUY ME A COFFEE` button on the total's row, right-aligned
  (§9 delta 15). The total is the **device's own screen-on time minus the
  launcher's**, not a sum of app timings (§9 delta 16).
- **Daily limit card** — `DAILY LIMIT — 4H 00M` with a state label reading
  either `<n>M LEFT` or `LIMIT EXCEEDED` in `h`. A usage bar (`a` when over,
  `p` otherwise). Below, a `−15` button, a drag track, and `+15`. Range
  **30–600 minutes, snapped to 15**. Over-limit paints the card's background `s`.
- **LAST 7 DAYS** — 7 bars, `26cqw` tall, each labelled with hours and a
  weekday; bars over the limit use `a` instead of `p`.
- **MOST USED** — 6 rows: glyph, name, a proportional bar, minutes. Native
  never lists the launcher itself. The rows need not add up to `TODAY`: they
  are app timings, the total is the device's (§9 delta 16).

**Nothing happens at the limit** — the label turns and that's all.

### 7e. Bottom sheet

`rgba(0,0,0,.55)` scrim; panel `max-height: 82%`, `0.8cqw` top border, header
with title + `DONE`. Two modes:

- **Dock** (`REPLACE DOCK SLOT n` / `ADD TO DOCK`) — every app as a row with
  an `IN DOCK` marker; a `REMOVE FROM DOCK` row on top when replacing.
  Selection dedupes and caps at 5.
- **Categories** (`CATEGORIES — <APP>`) — one checkbox row per category with
  an `IN` marker, plus a `NEW CATEGORY` field + `ADD` that creates the
  category and assigns it in one step.

### 7f. First-run hint

Full-screen `rgba(4,5,8,.82)` overlay: `SWIPE TO MOVE`, then
`→ SETTINGS`, `← APP DRAWER`, `↑ SCREEN TIME`, then `TAP ANYWHERE TO BEGIN`.
Dismissed on tap and persisted. While visible, swipe handling is suppressed.

---

## 8. Persistence

`localStorage['retro-launcher-v1']`, written on every settings change. Exactly
12 keys — `seconds` and `blink` were removed permanently (issue #6, see
amendment §4). Note that `view`, `tab`, `sheet` and `now` are deliberately
*not* persisted, so the launcher always reopens on home:

```
pal  theme  tint  hour12  fmtIdx  custom
unit  dock   cats  memberships     limit  hint
```

**Native equivalent:** `SharedPreferences`, same key set, same
write-on-change discipline.

---

## 9. Prototype → native deltas

Decisions taken with the project owner. Each departs from the prototype
deliberately; none is a silent omission.

| # | Area | Prototype | Native | Why |
|---|---|---|---|---|
| 1 | **Weather** | Synthetic. `tempC = 17 + 9·sunAlt − 5·cover − 4·precip`; conditions from a debug slider via `weatherName()`'s 10 bands. No API anywhere. | Real data from Open-Meteo (free, no key) using `ACCESS_COARSE_LOCATION`. Good for 30 min, never fetched within 10 min of the last attempt, last good reading persisted. Feeds the same `w` scalar, so clouds and rain track reality. **With no reading** the widget shows `--°` but the sky runs on `SyntheticWeather.drift` — a deterministic per-day character with a gentle hourly swing (amended 2026-08-28, Tier 5). | Fake temperature on a real phone is worse than none — hence `--°`. But a flat `w = 0` fallback leaves anyone who declines location with a permanently cloudless wallpaper, which is a worse lie about the *sky* than a drifting stand-in. The readout is a measurement; the wallpaper is scenery. |
| 2 | **Icons** | 52 hand-authored glyphs. | Two implementations behind one `IconSource` interface: generated palette tiles, and system icons posterized through the same Bayer + 6-color ramp the wallpaper's tint mode uses. **Decision deferred to a measured gate in Tier 2.** | The glyph table can't cover arbitrary installed apps. Which replacement feels right depends on real-device performance. |
| 3 | **Categories** | Hand-assigned per demo app. | Auto-filled from `ApplicationInfo.category` (API 26): `SOCIAL→SOCIAL`; `GAME/AUDIO/VIDEO/IMAGE→MEDIA`; `NEWS/PRODUCTIVITY→WORK`; `MAPS/UNDEFINED→UTILITY`. User-editable after. | Android already tags apps. Free, no maintenance list. Games land in MEDIA since the prototype ships no GAMES tab; tabs are user-addable. |
| 4 | **Long-press on an app** | Opens the category sheet. | Opens system **App Info**. Category editing moves to a long-press on the **tab strip**. | Android convention. Both remain reachable. |
| 5 | **Search** | None. | **Double-tap anywhere on the home wallpaper** opens a search overlay. No visible affordance, so the first-run hint carries the only mention of it. Results sit under two headings — `APPS` first, then a single `WEB` row firing `ACTION_WEB_SEARCH`. Ranking is in `core/AppSearch`: exact ▸ label prefix ▸ word prefix ▸ run mid-word, ties to the shorter label. | Requested; keeps the wallpaper unobstructed. Two headings because the app you meant should always be the top item and the escape hatch always in the same place. |
| 6 | **Dock defaults** | `phone, messages, camera`. | Same three, resolved at first run via the default dialer, SMS and camera intents. | Real apps, same intent. |
| 7 | **Dock tap** | Does nothing. | Launches the app. | Obviously. |
| 8 | **Widget taps** | None. | Three independent regions, each a *chain* rather than one intent (amended 2026-08-28, Tier 5): time → `ACTION_SHOW_ALARMS`, then a launch intent for a known clock package; date → `CATEGORY_APP_CALENDAR`, then `content://com.android.calendar/time`, then a known calendar package; weather → a known weather package, then a forced weather refresh. Every step logs under the `LaunchChain` tag. | Owner requirement. The chains exist because a single intent silently did nothing on devices whose clock app never declared `ACTION_SHOW_ALARMS` — a swallowed `ActivityNotFoundException` is indistinguishable from a dead tap. |
| 9 | **Screen time** | Hardcoded. | Real `UsageStatsManager` data, requiring `PACKAGE_USAGE_STATS`. | The screen is pointless with fake numbers. |
| 10 | **Over-limit** | Label changes only. | Persistent marker in the home widget, plus the wallpaper desaturating toward the palette's grey in proportion to the overage. | Requested: a nag with no notification permission. |
| 11 | **Theme** | `LIGHT`/`DARK`, default dark, ignores the OS. | Adds `AUTO` following the system dark-mode setting, and defaults to it. | Mirrors the palette's existing AUTO. |
| 12 | **Navigation** | Swipe from anywhere. | Swipes starting inside the system gesture inset are ignored, so Back keeps working. | Horizontal edge swipes belong to Android. |
| 13 | **Bezel chrome** | 30px/22px rounded device frame. | Dropped. Portrait-locked, edge-to-edge, wallpaper behind the system bars, UI inset. | The launcher *is* the screen. |
| 14 | **Opt-out regions** | `[data-noswipe]` swallows a gesture whole, on both axes (§1). | Ownership is **per axis** (amended 2026-08-29). A vertical list claims vertical drags and lets sideways ones through; a horizontal strip claims the opposite; a widget that drags on its own — the slider, the scrubber, the dock, a panel header — still claims both. A vertical scroller also *releases* the vertical axis at the end of its travel, so a downward drag with nothing left to scroll pulls the screen time panel shut. A drag that can't move the current view is never claimed at all. | The reverse swipe has to work from anywhere on the panel, and a panel is mostly list. With whole-subtree opt-out the closing swipe worked only on the few strips of background either side of the content, which reads as the gesture being broken. Per-axis costs nothing: a list has no use for a sideways drag. |
| 15 | **Donations** | None. | A bordered `BUY ME A COFFEE` button with a pixel mug beside the screen time total, drawn in palette roles like any icon. **Inert until a URL is set** in `CoffeeButton.DONATION_URL`; the tap fires `ACTION_VIEW` once one is. | Owner requirement. The screen time panel is where someone is already thinking about what the launcher is worth to them. |
| 16 | **What counts as screen time** | Hardcoded, so the question never came up. | The launcher is **never** counted — not in the `TODAY` total, not in the week chart, not in `MOST USED`. The total prefers the **device's own screen-on record** (`SCREEN_INTERACTIVE` → `SCREEN_NON_INTERACTIVE` events, API 28+) **minus** the launcher's foreground time; the per-app sum is only computed for a day the device reported no screen events at all — below API 28, or a day missing from the event window. Both paths go through `UsageMath.resolveTotal`, which floors at zero. Added 2026-08-29. | Time spent looking at the home screen is not time spent on an app, and on a launcher this is a large number: it was inflating the user's own screen time with the act of checking it. Preferring the device's reading also makes the total match what the phone's own digital wellbeing screen says, rather than a reconstruction that double-counts overlapping foreground spans. The subtraction, rather than a per-app exclusion, is what keeps that agreement: the device number is opaque and includes the launcher. |
| 17 | **Panel compositing** | N/A — a DOM prototype has no surface-compositing layer to desync. | `SkyView` is a `TextureView`, not a `SurfaceView`. Added 2026-08-31. | A `SurfaceView`'s pixels are composited by SurfaceFlinger as an independent hole-punch layer, outside the RenderThread pipeline that animates the panels above it, so the two can fall a frame out of lockstep. **This was diagnosed as the cause of the "ghost panel" and was not** — the artifact survived the change, and delta 23 has the real cause. Kept regardless: one compositor for the whole window is the right shape here, and it removes a genuine class of tearing. See `docs/superpowers/specs/2026-08-31-panel-fixes-and-launcher-controls-design.md` §1. |
| 18 | **Panel reveal** | Snap between fully shown and fully hidden. | Alpha now tracks drag/settle progress continuously (`LauncherRoot.reveal()`): `1 − \|translation\| / extent`, applied in both `drag()` and `slide()`. Added 2026-08-31. | Requested: the reveal should read as continuous at any drag speed, not binary. Also hardens delta 17's fix — even a future one-frame compositor hiccup would blend into a fade rather than pop as a hard-edged ghost. |
| 19 | **Lock the device** | None. | Long-press on Home calls `DevicePolicyManager#lockNow()`, gated behind a one-time Device Admin activation dialog (`LockAdminReceiver`). `device_admin.xml` declares **`force-lock`, and only that**. Status in Settings → PERMISSIONS reads `hasGrantedPolicy`, not merely `isAdminActive`, and the call itself is wrapped so a refusal re-opens the activation dialog instead of dying. Added 2026-08-31, corrected same day. **Superseded the same day by delta 25** — the admin is now the fallback, not the route. | Requested. The correction is the whole feature: `lockNow()` throws `SecurityException` unless the admin declared `USES_POLICY_FORCE_LOCK`, so the first cut — which shipped an empty `<uses-policies/>` on the theory that "every active admin can already lock" — crashed the launcher every single time the gesture fired. Anyone who activated that admin still has it active after the update, which is why "active" alone is no longer trusted as the status. |
| 20 | **Default launcher** | None. | A dismiss-when-satisfied banner on Home plus a durable status row in Settings → PERMISSIONS. Both open **`Settings.ACTION_HOME_SETTINGS`**, falling back to `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` then `ACTION_SETTINGS`. `RoleManager.ROLE_HOME` is still used to *read* the status on API 29+. The row stays tappable once satisfied, unlike the permission rows. Added 2026-08-31, corrected same day. | Requested. The correction: `RoleManager.createRequestRoleIntent(ROLE_HOME)` looks like the modern path but ROLE_HOME is marked non-requestable in the platform's role definitions, so the system's request-role activity finishes immediately without drawing anything — the button appeared dead. A third-party launcher has to send the user to the settings screen on every API level; there is no in-app grant flow for this role. |
| 21 | **Notification shade** | None. | **Any** downward swipe on Home expands the shade. Two routes in order: reflection on `StatusBarManager#expandNotificationsPanel()` (`EXPAND_STATUS_BAR`, normal protection), then `ShadeService` — an opt-in `AccessibilityService` that calls `performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)` and reads nothing. Enabled from a Settings → PERMISSIONS row; the swipe is a silent no-op while it is off. Added 2026-08-31, corrected same day. | Requested. Two corrections. The trigger was a swipe *starting inside the top system-bar inset*, a ~24dp band the system's own shade gesture already competes for, so the handler rarely saw the gesture; a swipe down anywhere on Home is otherwise inert, so claiming all of it costs nothing. And `expandNotificationsPanel` is a denylisted non-SDK interface — at targetSdk 34 the reflective lookup throws outright on a current AOSP build, and the catch-all swallowed it, so the feature was silent on exactly the devices that need it. The accessibility route is the only one that actually works there, and it is opt-in because it is a heavy permission to ask for. |
| 22 | **Alphabet scrubber length** | Full-height rail, edge to edge (§7b). | Rail measures to 86% of its row's height and centers vertically (`AlphaScrubber.LENGTH_FRACTION`) rather than stretching to both edges. Added 2026-08-31. | On a circular display the rail's endpoints (A, Z) sat in the curved, clipped corners. Shrinking and centering leaves clearance on any display shape, round included. |
| 23 | **Who may write a panel's transform** | N/A — the prototype had one writer. | `applyRest` yields to the **finger** as well as to a running animator: `LauncherRoot.held[]` is set by `seize()` and cleared in `goTo()`, and `applyRest` skips any slot that is animating *or* held. Added 2026-08-31. | This, not delta 17, was the "ghost panel". `applyRest` runs from `onLayout`, which fires on any descendant's `requestLayout`, and it guarded only against animators. A drag has no animator, so every layout pass mid-drag stamped the *rest* transform over the finger's and the next MOVE put it back — the panel alternating between two positions and two alphas frame to frame, which reads as a second, fainter copy behind the real one. A drag guarantees such a pass, too: `seize()` makes the incoming panel VISIBLE, `setVisibility` calls `requestLayout`, and `rest()` set it straight back to INVISIBLE, the two ping-ponging for the whole gesture. It showed only on the way out because closing has a rest state of "fully open, opaque, on top" — every stamped frame is a full-screen flash — while opening rests at "off-screen, alpha 0", where the identical glitch is invisible. That asymmetry is what finally identified it. |
| 24 | **Recents panel** | None. | **Cut, not deferred by choice.** A third-party launcher cannot read or control the system recents: `getRecentTasks()` has returned only the caller's own tasks since API 21, and no API dismisses another app's task. 2026-08-31. | Requested as a 4×4 horizontally-paged grid with per-app dismiss and clear-all. The grid is buildable over `UsageStatsManager`, but its crosses would hide rows from a list of our own invention while the real recents sat untouched behind them — an imitation of a system surface that cannot do what it appears to do. Owner chose to cut it rather than ship that. Same reason there is nothing to map for a hardware recents key: `KEYCODE_APP_SWITCH` is consumed by the system and never dispatched to an app, so that button already opens the real recents and no launcher can intercept it. |
| 25 | **How the lock is performed** | N/A. | `AccessibilityService#performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` via `ShadeService`, with `DevicePolicyManager#lockNow()` kept only as the fallback for API 26–27 and for anyone who has not switched the service on. `LockRoute` (core, tested) picks between them and names the three states the Settings row shows: `ON`, `PIN ONLY`, `ENABLE`. Added 2026-08-31. | Reported: locking through the launcher made the phone demand the PIN and refuse the fingerprint. That is not a bug in our call, it is what a device-admin lock *means* — `lockNow()` raises the strong-auth-required flag on the user, and Android rejects every biometric until a PIN, pattern or password has been entered. No flag or policy relaxes it, so the fix had to be a different API, not a different argument. The global action is the same lock the power button performs and leaves biometrics alone. The admin route stays reachable because it is the only one below API 28, but it is drawn as `PIN ONLY` in the attention colour and stays tappable rather than reading as done — otherwise everyone already on it is stuck there without knowing why. `ShadeService` keeps its narrow name on purpose: the enabled-services setting stores the flattened component name, so renaming the class would silently switch the service off for every user who had already enabled it. |
| 26 | **The drawer's removal row** | One row, `UNINSTALL / DISABLE` (§7b). | Up to two rows, chosen per app from its `ApplicationInfo` flags by `AppActionPolicy` (core, tested): `UNINSTALL` for a user-installed app, `UNINSTALL UPDATES` + `DISABLE` for an updated system app, `DISABLE` alone for a preinstalled one, neither for this launcher. `LAUNCH` and `MORE DETAILS` are unchanged and offered for every app. `android.permission.REQUEST_DELETE_PACKAGES` is declared. Added 2026-08-31. | Reported: the row did **absolutely nothing** — no dialog, no error — while `LAUNCH` and `MORE DETAILS` both worked. The cause was the missing permission, and the reason it presented as silence rather than as a failure is the interesting part: `startActivity(ACTION_DELETE)` *succeeds*. The system's `PackageInstallerActivity` resolves and starts, then checks the calling package for `REQUEST_DELETE_PACKAGES`, does not find it, and `finish()`es before drawing. Nothing throws, so the `catch (ActivityNotFoundException ignored) {}` never ran and there was nothing to see in a logcat either. A fallback chain alone would not have helped — `Launch.first()` advances only when a candidate *throws*. Splitting the row followed from the same audit: a launcher cannot disable an app at all (`setApplicationEnabledSetting` is device-owner-or-self), so half of what that one label promised was never deliverable, and `DISABLE` now deep-links to App Info where the platform's own button is. Every failed chain ends in a themed on-screen notice rather than a silent return. |
| 27 | **Accessibility vs. banking apps** | N/A. | **No change — accepted, not fixed.** `ShadeService` stays opt-in and off by default. Recorded 2026-08-31. | Reported: enabling this launcher's accessibility service makes a banking app refuse to start, warning that only system apps may use accessibility, and the owner asked whether a Play Store review would clear it. It would not. The check is client-side RASP hardening inside the banking app: at startup it enumerates enabled services via `AccessibilityManager` and refuses to proceed if any belongs to a non-system package. It never consults Play, and an approved accessibility declaration governs only whether Google will *distribute* the app — it has no bearing on what a third-party app observes on the device. So the trade-off is fixed and belongs to the user, not to this codebase: fingerprint-preserving lock (delta 25) and the shade swipe (delta 21) on one side, banking apps that run with the service enabled on the other, chosen by toggling the service in Android Settings. Both features degrade to working fallbacks while it is off. Not re-litigable without dropping the service entirely. |
| 28 | **Text fields** | Not in the prototype — a DOM `input` inherits the page's type and colour for free. | `ui/PixelField`, an `EditText` with the drawer's bordered `GradientDrawable` in place of the Material underline, monospace bold at `Metrics` scale, a block caret and square selection handles in `palette.p` (API 29+), and the hint and highlight at reduced ink alpha. The category field also takes an all-caps `InputFilter` over `core/CapsText`; the search field does not. The system keyboard stays. Added 2026-08-31. | Reported: adding an application group used the native input, and it showed — accent underline, thin accent caret, teardrop handles, system text colours, all of it fixed while the palette moves with the hour. A drawn pixel keypad was weighed and cut: no IME switching, no autocorrect, no other languages, no clipboard, for a field touched a handful of times. Two details are load-bearing rather than cosmetic. The filter raises the text *as typed* — `setAllCaps` is display-only, so a lower-case name would still reach `Prefs` and then fail to match the upper-case chip it had become — and `CapsText` returns null when nothing would change, because an `InputFilter` that returns a replacement discards the spans on it, one of which is the IME's composing span; replacing on every keystroke restarts the word the keyboard is assembling. `Locale.ROOT`, too: under a Turkish locale `"mini".toUpperCase()` is `"MİNİ"`, and that category would not match the same word typed anywhere else. |
| 29 | **Target platform** | N/A. | `compileSdk`/`targetSdk` 36 (Android 16, Play's floor from August 2026) on AGP 8.13.2 + Gradle 8.14.5; `minSdk` unchanged at 26. Edge-to-edge is asked for with `Window#setDecorFitsSystemWindows(false)` on API 30+, the `SYSTEM_UI_FLAG_*` path kept below it. Back runs through an `OnBackInvokedCallback` at `PRIORITY_DEFAULT` on API 33+, with `onBackPressed()` kept as the pre-33 path and `enableOnBackInvokedCallback="true"` declared. The package receiver registers `RECEIVER_NOT_EXPORTED` on API 33+. `resConfigs` migrated to `androidResources.localeFilters`. Added 2026-08-31. | Requested as a whole-application audit. Two of the nine findings would have broken the launcher outright at targetSdk 36. Predictive back is on by default there and the platform stops calling `onBackPressed()`, so that override — the only thing stopping Back from closing the home screen — would have gone quietly dead. And `setSystemUiVisibility` has been a no-op since 35, where edge-to-edge is compulsory; the app was already laying out fullscreen by intent, so the six `onApplyWindowInsets` overrides are what keep content clear of the status bar and the gesture pill, and they are unchanged. Three findings needed nothing: there is no native code anywhere (no NDK, no `jniLibs`, no `.so` in the APK — verified by inspecting it), so 16 KB page alignment is already satisfied; the `StatusBarManager` reflection is already wrapped with `ShadeService` as the documented fallback (delta 21); `minSdk` needs no change. Two decisions went against the audit's first reading, both recorded in place: the deprecated `statusBarColor`/`navigationBarColor`/`windowDrawsSystemBarBackgrounds` stay in the theme, because they are inert from 35 but load-bearing on API 26–34 and removing them would put an opaque status bar over the wallpaper on every older device; and `configChanges` is *not* extended to the size-affecting changes, because `Metrics` is captured once in `onCreate` and every padding and text size in the app is a fixed pixel derived from it, so a resize must rebuild the view tree — letting the activity recreate is the cheapest correct way to do that, and recreation is already safe by construction (`stateNotNeeded`, no saved state read, always reopens on home). `screenOrientation="portrait"` is left declared: API 36 ignores it at 600dp and up, which is the platform's call to make, and it is still the right request on a phone. |

---

## 10. Effects with no exact native equivalent

| Prototype effect | Native approximation |
|---|---|
| `backdrop-filter: blur(2px)` on the widget, dock and scrubber | `RenderEffect` is API 31+ and we target `minSdk 26`. Use the flat `veil` fill (`bg` + `D9`/`E0`) with the blur dropped — the alpha is already defined and carries most of the effect. |
| CSS `container-type: size` + `cqw` units | `Metrics.cqw()` resolved from `displayMetrics.widthPixels` at runtime (§5). |
| `text-transform: uppercase` | `android:textAllCaps="true"` |
| `letter-spacing: .16em` | `android:letterSpacing="0.16"` — Android takes em directly. |
| CSS keyframe `blink` on the colon | **Dropped, not ported.** The native colon is solid — see the 2026-08-28 amendment in §4. |
| `Press Start 2P` webfont | `monospace` bold (§4). |

---

## 11. Definition of done

On top of `HANDOFF.md` §7:

- [ ] Four panels navigable by swipe with axis lock, 12px slop, 22%/16%
      thresholds, 1:1 tracking and a 260ms settle
- [ ] Scrolling regions opt out of panel swiping (`data-noswipe` equivalent)
- [ ] Swipes starting in the system gesture inset are ignored; Back still works
- [ ] Auto-palette hour table (§2a) matches exactly
- [ ] Sky renderer ports all layers of §2b including Bayer quantization —
      the banding is the aesthetic, not an artifact
- [ ] Render thread pauses when the launcher is not visible
- [ ] `veil` used in place of every `backdrop-filter`
- [ ] Spacing resolved through `Metrics.cqw()`, not hardcoded dp
- [ ] No font file in `res/`; `monospace` throughout
- [ ] Icon strategy decided at the Tier 2 gate on measured evidence, both
      implementations behind `IconSource`
- [x] Widget's three tap regions wired independently (§9 delta 8) — each a
      fallback chain, logged under `LaunchChain`
- [ ] All 14 persistence keys in `SharedPreferences`; launcher always reopens
      on home — plus 6 Tier 5 keys for the cached reading and last fix
- [ ] Every §9 delta is either implemented or explicitly reported as cut
- [x] Panel slides own their translation exclusively: no layout pass writes
      under a running animator, only moving panels animate, and no hardware
      layer is taken for a translation-only slide

**Still unverified on a device** (Tier 5 close-out): panel slide smoothness,
the clock/calendar/weather tap chains against a real OEM clock app, and a live
Open-Meteo fetch with location granted.
