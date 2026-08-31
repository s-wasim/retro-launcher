# Panel Ghost Fix, Reveal Fade, and Launcher-Role Controls

2026-08-31. Architectural addendum to `2026-08-28-retro-launcher-design.md`.
Scope: fix the slow-close ghost-panel artifact, make panel reveal fade with
drag progress, add a lock-the-device long-press, add default-launcher
prompts, and let a swipe from the status-bar inset expand the notification
shade. A "recents" panel was explored and explicitly dropped from this round
— see the brainstorming transcript for the discarded design.

## 1. Ghost panel on slow close

**Symptom** (user-confirmed): closing Settings, the app drawer, or Screen
Time slowly shows a second, dimmed copy of that panel's own content — rows,
text, buttons — sitting behind the real, moving panel, as if it were drawn
twice.

**Root cause.** `SkyView` (`app/.../sky/SkyView.java`) extends `SurfaceView`.
A `SurfaceView`'s pixels are composited by SurfaceFlinger as an independent
hole-punch layer, outside the RenderThread pipeline that draws and animates
every other view, including the sliding panels. The two compositors run on
separate schedules. Under a slow drag — many frames, a long visible window —
they can fall out of lockstep, and the hole-punch layer briefly shows a
stale prior frame (which still has the panel's last opaque, fully-open
content baked into what was behind the hole) bleeding through at the edge of
the live, correctly-composited panel above it. This explains every part of
the symptom: it only shows up when slow (more frames for the desync to
become visible), it looks like "the same content, faded" (a stale hole-punch
frame under a fresh RenderThread frame), and it only ever affects the three
panels that slide over the sky (Settings, Drawer, ScreenTime) — Home itself
never needs to "reveal" anything.

**Fix.** Convert `SkyView` from `SurfaceView`/`SurfaceHolder.Callback` to
`TextureView`/`TextureView.SurfaceTextureListener`. A `TextureView` is
composited as a normal GPU texture inside the same RenderThread pipeline as
every other view, so it cannot fall out of lockstep with a panel's
translation — there is only one compositor. The render thread, buffer,
upscale, and `Canvas`-based drawing (`lockCanvas()` /
`unlockCanvasAndPost()`) stay exactly as they are; only the base class and
callback interface change. `TextureView.lockCanvas()`/`unlockCanvasAndPost()`
are documented safe to call off the UI thread, matching the existing
dedicated `SkyRenderThread`. The buffer is tiny (108px wide, upscaled), so
the extra copy a `TextureView` requires versus a `SurfaceView` is
negligible.

## 2. Panels fade in/out with reveal progress

Today `LauncherRoot.drag()` and `.slide()` only ever write translation; a
panel is either fully opaque (visible) or fully invisible, snapping between
the two. The ask: the panel's own transparency should track how far it has
been pulled into view, continuously, at any drag speed — including
arbitrarily slow.

Every panel's rest offsets are symmetric: 0 (fully open) and ±extent (fully
closed), where extent is the view's width (Settings, Drawer) or height
(Time). That means one formula works for all three panels and both
directions:

```
alpha(translation, extent) = clamp(1 - |translation| / extent, 0, 1)
```

Applied in `drag()` right after each `setTranslationX/Y` call, and in
`slide()` as an `.alpha(target)` alongside the existing
`.translationX/Y()` on the `ViewPropertyAnimator` (so a released fling or a
tap on CLOSE fades too, not just a manual partial drag — the same visual
language throughout). `applyRest()`'s `rest()` helper also sets alpha
(1 for shown, 0 for hidden) so a panel snapped instantly by a layout pass —
not just an animated one — starts from a clean state next time it opens.

This is also a defense-in-depth measure against fix #1: even if a one-frame
compositor desync ever recurs, it now gets alpha-blended into a fade rather
than popping as a hard-edged ghost.

## 3. Lock the device on long-press (Home)

`LauncherRoot` already runs a `GestureDetector` for double-tap-to-search,
fed every touch event that reaches the root's own `onTouchEvent` (i.e.
every tap on Home's empty background — clock and dock already opt out via
`setNoSwipe`). Add `onLongPress` to the same listener, gated the same way
double-tap is (`view == VIEW_HOME`), exposed as
`setLongPressListener(Runnable)`.

Locking a screen from an app requires `DevicePolicyManager`, which requires
Device Admin activation — a one-time system dialog, not an in-app one. New
`LockAdminReceiver extends DeviceAdminReceiver` (`app/.../admin/`) with an
empty `res/xml/device_admin.xml` policy set (no policies requested beyond
the ability to lock) and a manifest `<receiver>` guarded by
`BIND_DEVICE_ADMIN`. `HomeActivity`'s long-press handler: if
`dpm.isAdminActive(admin)`, call `dpm.lockNow()` directly; otherwise launch
`ACTION_ADD_DEVICE_ADMIN` with an explanation string via
`startActivityForResult`, and re-check status in `onResume()` (already
called on return from any settings-adjacent activity, same as the existing
permission-status refresh).

`SettingsPanel` gets a new always-visible row in the PERMISSIONS section,
`DEVICE LOCK — ON / ENABLE`, next to Location and Usage Access, reusing the
existing `permissionRow` visual pattern (generalized to accept custom
granted/fix labels instead of the hardcoded "GRANTED"/"FIX" so it reads
naturally for a capability rather than a permission).

## 4. Default-launcher prompts

**Detection:** `RoleManager.isRoleHeld(RoleManager.ROLE_HOME)` on API 29+;
on 26–28, compare `PackageManager`'s resolved default `CATEGORY_HOME`
activity's package against this app's.

**Request:** `RoleManager.createRequestRoleIntent(ROLE_HOME)` on 29+,
`Settings.ACTION_HOME_SETTINGS` below that.

**Home screen:** a small, dismiss-by-being-satisfied banner on `HomePanel`
(top-center, matching the veil/rounded/monospace-caps chrome language of
`ClockWidget`/`DockView`), visible only while not the default launcher,
`setNoSwipe` like the other floating widgets, tapping it fires the request
intent.

**Settings:** an always-visible status row in the PERMISSIONS section
(same generalized `permissionRow`), `DEFAULT LAUNCHER — DEFAULT / SET`,
shown regardless of state like Location and Usage Access — the home banner
is the one that disappears once satisfied, matching the sparse-home-screen
principle; the settings row stays as a durable status readout like its
neighbors.

Both read from the same `refreshPermissionStatus()` cycle already wired to
`onResume()`, so returning from the system role picker updates both
surfaces without new lifecycle plumbing.

## 5. Swipe down from the status-bar inset expands the shade

A plain swipe-down on Home is already inert today (`LauncherRoot.moves()`
returns `false` for a downward drag while `view == VIEW_HOME`) — screen
time only opens on swipe *up*. This adds one narrow exception: if the
gesture's `downY` falls inside the top system-bar inset (new
`Insets.systemTop(View)`, mirroring the existing
`Insets.gestureLeftRight()`), and the locked axis resolves to vertical-down
while still on Home, fire a callback instead of dragging anything (nothing
was draggable there anyway) and mark the gesture not-ours.

The callback (`HomeActivity`) expands the shade via the long-standing
reflection technique other launchers use: `getSystemService("statusbar")`
cast through `android.app.StatusBarManager#expandNotificationsPanel()`,
gated behind the manifest's `EXPAND_STATUS_BAR` permission (normal
protection level, no runtime prompt), wrapped in a blanket
`catch (Throwable)` so an OS version that blocks the call in the future
makes the swipe a silent no-op rather than a crash. Undocumented and not
guaranteed by Google — accepted as such per discussion.

## Explicitly out of scope this round

- **Recents panel** (grid of recently-used apps): dropped after
  brainstorming; revisit separately if wanted.
- **Hardware Recents button/gesture interception:** researched and
  confirmed infeasible for a non-privileged third-party launcher — Android
  always routes it to SystemUI's own Overview regardless of default-launcher
  status, with no public API to intercept or redirect it. Not attempted.

## Testing

Everything in this round is Android-API glue (SurfaceView/TextureView,
DevicePolicyManager, RoleManager, WindowInsets, reflection) with no new
pure-logic worth extracting into `core` — nothing here is unit-testable the
way `UsageMath` or `WeatherParser` are. Verification is: existing `core`
tests stay green (no `core` changes in this round), a full read-through of
each diff, and a CI build (there is no local Android SDK in this
environment, so the `app` module cannot be compiled locally — matches prior
sessions' verification path).
