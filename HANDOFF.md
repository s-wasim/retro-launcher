# Constraints and rationale: Minimal Android Launcher

**Goal:** a native Android launcher with the smallest practical APK, built
entirely on free GitHub Actions minutes. No local Android toolchain required.

This began as the brief the launcher was built from. The scaffolding half of
it — the tree to create, the files to write, the order of work — described
work that is long finished and was removed in 2.1.1. What is left is what is
still binding: the amendment ledger below, the constraints in §1, and the
design-handoff rules in §2, all of which the source comments cite by section.

---

## 0. Amendments — 2026-08-28

This document was written before the design existed. Once the "Retro Launcher"
prototype arrived it turned out to specify four screens, real usage data and
live weather, which several constraints below forbid. The following changes
were **approved by the project owner** on 2026-08-28. Where this section and
the rest of the document disagree, this section wins.

| # | Original rule | Amended to | Reason |
|---|---|---|---|
| 1 | §1 "No `<uses-permission>` entries at all" | Exactly **three** permitted: `PACKAGE_USAGE_STATS`, `INTERNET`, `ACCESS_COARSE_LOCATION` | Screen time needs real usage data; the weather line needs real weather. Android exposes neither without these. No `POST_NOTIFICATIONS` — the over-limit nag is in-launcher. |
| 2 | §1 "Size budget: release APK under 80 KB" | **Not a priority.** Report actual size every build and flag what drives it, but do not cut features to hit a number. | Four screens, a per-pixel renderer and ten palettes will exceed 80 KB. The owner would rather have the design than the number. |
| 3 | §4 "Keep it to two files. No abstraction layers, no interfaces, no DI." | Superseded by the module layout in the spec (~25 focused classes across `ui/ sky/ theme/ icons/ data/ util/`). | Written for a one-screen `ListView`. Unworkable at four screens. Still no DI, still no frameworks. |
| 4 | §1 "Zero third-party dependencies — the `dependencies {}` block should be empty" | `testImplementation 'junit:junit:4.13.2'` permitted. **The shipped APK still has zero dependencies.** | `testImplementation` is compile-time-only for local JVM tests and never enters the APK. Without it nothing in the project is unit-testable, and roughly 40% of this codebase is pure logic — sky interpolation, palette thresholds, the date tokeniser, weather parsing, usage aggregation — where a silent off-by-one shows up as a wrong colour at 04:36 and nowhere else. |
| 5 | §3 "`git init` only — do not add a remote, do not push" | Applied to scaffolding, now complete. A remote is required for CI. | The owner wires it up when ready. |
| 6 | §8 open questions | All resolved — see `design/DESIGN_NOTES.md` §9. | Answered by the owner during design. |
| 7 | Row 1 above: "Exactly three permitted" | **Four**: adds `EXPAND_STATUS_BAR` (normal protection, no runtime prompt). Still four after the 2026-08-31 corrections. | Status-bar swipe (`design/DESIGN_NOTES.md` §9 delta 21), requested 2026-08-31. Two entries that look like additions are not: `BIND_DEVICE_ADMIN` (delta 19) and `BIND_ACCESSIBILITY_SERVICE` (delta 21's fallback) are `android:permission` attributes **protecting** our own `LockAdminReceiver` and `ShadeService` so only the system can bind them. An app never declares either as a `<uses-permission>` for itself. |
| 8 | Row 7's spirit: a small, non-invasive permission set | An **opt-in `AccessibilityService`** (`ShadeService`) now exists. Off by default; the user turns it on from Settings → PERMISSIONS. | Accessibility is the heaviest thing this app asks for and deserves flagging. It is the only route left: `StatusBarManager#expandNotificationsPanel()` is a denylisted non-SDK interface and throws at `targetSdk 34`, so swipe-down-for-notifications does not work on a current device without it. The service is declared as narrowly as the framework allows — no `canRetrieveWindowContent`, no gesture dispatch — and its `onAccessibilityEvent` is empty: it exists solely to be a bound instance that `performGlobalAction` can be called on. Everything else still works with it off. **It carries a second action as of the same day** (delta 25): `GLOBAL_ACTION_LOCK_SCREEN`, which is the only way to lock the screen without costing the user their fingerprint — `DevicePolicyManager#lockNow()` raises the strong-auth-required flag and Android then refuses every biometric until a PIN is entered. The device admin is kept as the fallback for API 26–27 and for users who leave the service off. |

| 9 | Row 7 above, and row 8's device-admin fallback | **V9 §10 removes two.** `SYSTEM_ALERT_WINDOW` is dropped from the manifest, and the device admin — `LockAdminReceiver`, its `BIND_DEVICE_ADMIN` receiver block, `res/xml/device_admin.xml` and `LockRoute.ADMIN` — is deleted entirely. The runtime set is now `INTERNET`, `ACCESS_COARSE_LOCATION` + `ACCESS_FINE_LOCATION`, `PACKAGE_USAGE_STATS`, plus the normal-protection `EXPAND_STATUS_BAR`, `VIBRATE` and `REQUEST_DELETE_PACKAGES`. | `SYSTEM_ALERT_WINDOW` was declared for an overlay lock route Shizuku superseded and that was never built — there is no `TYPE_APPLICATION_OVERLAY` anywhere in the source — so it bought a "display over other apps" prompt for nothing. The admin route locked, but through `lockNow()`, which raises the strong-auth flag: Android then demanded the PIN instead of the fingerprint on the next unlock, which is why row 8 already called it a fallback and why `LockRoute` never counted it as `settled()`. Lock survives on Shizuku → Accessibility, neither of which costs the fingerprint. |
| 10 | §1's spirit again, on the other side | **V9 §11 adds no permission but reaches further**: `LauncherApps#getActivityList` per user profile replaces `queryIntentActivities`, so OEM clones in a secondary profile appear in the drawer. | Available since API 21 against `minSdk 26`, and it needs no `<uses-permission>` — in particular not `QUERY_ALL_PACKAGES`, which is not declared and must not be. This is why it does not undo row 9. |

| 11 | Row 4 above: "**The shipped APK still has zero dependencies**" | **No longer true as of V8.** The APK ships `dev.rikka.shizuku:api` + `:provider` and, transitively required by them, `androidx.annotation`. `android.useAndroidX` is therefore `true`, not `false`. | Shizuku is the only lock route that costs the user neither their fingerprint nor an accessibility grant. The dependency is contained to `ShizukuLock.java` and `ShizukuLockService.java`; nothing else in the app may import either. `androidx.annotation` is annotations only — it contributes no runtime code to the APK. Everything else in §1 is unchanged. |

Unchanged and still binding: **Java only, no Kotlin. No Compose, no Material.
`ListView`, not `RecyclerView`. `minSdk 26`, `targetSdk 36`. R8 full mode +
`shrinkResources`. No image assets. No font files. No WebView.**

---

## 1. Non-negotiable constraints

These exist to keep the APK in the 20–80 KB range. Do not relax any of them without asking.

> ⚠️ **Rows 1, 2, 4, 6 and 7 of this table have been amended — see §0.**
> Permissions, the size budget and the empty `dependencies {}` block changed
> on 2026-08-28 (rows 1, 2 and 7 of §0); `targetSdk` moved to 36 in V6 and the
> AndroidX and third-party rules changed in V8 (row 11 of §0).

| Rule | Reason |
|---|---|
| **Java only, no Kotlin** | Kotlin bundles ~1.5 MB of stdlib |
| **No AndroidX, no Compose, no Material** — *amended, §0 row 11: `androidx.annotation` only, for Shizuku* | Each adds megabytes; the framework equivalents are enough |
| **`ListView`, not `RecyclerView`** | RecyclerView is AndroidX |
| **`minSdk 26`, `targetSdk 36`** | Lets us use framework APIs without support shims. `targetSdk` tracks what Play requires. |
| **R8 full mode + `shrinkResources true`** on release | Strips unused code and resources |
| **No image assets** except the single adaptive launcher icon | Bitmaps dominate size in small apps |
| **Zero third-party dependencies** in `app/build.gradle` — *amended, §0 rows 4 and 11* | Every dependency has to earn the size it costs |

**Size budget:** debug APK under 150 KB, release APK under 80 KB. Report the actual size in the build log. If a change pushes past budget, stop and flag it rather than proceeding.

---

## 2. The design handoff — read this carefully

The design arrives as a **Claude Design web prototype**. It will live at `design/prototype/`.

**That prototype is a visual reference ONLY.**

Do:
- Open it, read the HTML/CSS, and extract the *design tokens*: colors (hex), background, text sizes in sp, row heights, padding/margins in dp, font weight, alignment, spacing rhythm.
- Record everything you extract in `design/DESIGN_NOTES.md` as a token table, so the mapping from prototype to native is auditable.
- Reimplement the design using **Android XML layouts and framework views only**.

Do **not**:
- Copy the HTML, CSS, or JS into the project.
- Use a `WebView`, or any hybrid/Cordova/Capacitor approach. A WebView launcher would be slow, heavy, and defeats the entire purpose.
- Add a font file to match the prototype's typeface unless explicitly approved — a TTF is typically 100–400 KB and would blow the entire budget on its own. Map to the closest system font (`sans-serif`, `sans-serif-light`, `sans-serif-medium`, `monospace`) and note the substitution in `DESIGN_NOTES.md`.

If the prototype uses an effect that has no cheap native equivalent (blur, complex gradients, custom shadows), note it in `DESIGN_NOTES.md`, implement the nearest cheap approximation, and flag it in your summary rather than pulling in a library.
