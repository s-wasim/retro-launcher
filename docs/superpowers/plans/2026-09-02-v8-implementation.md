# V8 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the five V8 fixes — CI signing hygiene, bounded drag haptics, correct moon position, a sun-anchored sky, and a Shizuku-based screen lock — exactly as specified.

**Architecture:** Sixteen tasks in the spec's own dependency order. Tasks 1–2 (CI, haptics) are self-contained. Task 3 (moon angle) touches one renderer line but ripples into its test file's helpers. Tasks 4–9 build the new `SolarClock`/`SolarMath`/`SolarTimes` subsystem in `:core` bottom-up, then wire it through `WeatherParser` → `OpenMeteoWeather` → `WeatherRepository` → `HomeActivity` → `SkyView` → `SkyRenderer`. Tasks 10–13 add `LockRoute`'s third case and the Shizuku glue behind a Settings toggle that defaults off.

**Tech Stack:** Java 17, Android (minSdk 26, compileSdk/targetSdk 36), Gradle 8.14.5 / AGP 8.13.2, JUnit 4 for `:core` (plain-JVM tests, no Robolectric), `dev.rikka.shizuku:api`/`:provider` (new, `app` only).

**Spec:** `docs/superpowers/specs/2026-09-02-v8-design.md` — this plan implements it task-by-task; read both together.

## Global Constraints

- `:core` stays dependency-free: no `android.*` imports, no third-party libraries (test-scoped JUnit only). Every new pure-logic class (`Detents`, `SolarClock`, `SolarMath`, `SolarTimes`) goes in `core/src/main/java/com/retro/launcher/core/`.
- All Android glue (`ShizukuLock`, `OpenMeteoWeather` changes, `WeatherRepository`, UI) goes in `app/src/main/java/com/retro/launcher/...` and is *not* unit-tested — verified on-device/by-build, matching the existing standard for `ShadeService` and `LocationSource`.
- Every public method that already exists and is called from outside its own task's files must keep its exact name unless the task explicitly says to rename it and fix every call site (grep before renaming).
- Java, not Kotlin. Tabs/braces follow the existing style in the file being edited (K&R, 4-space indent, javadoc `/** */` on public members).
- Run `gradle :core:test --no-daemon` after every `:core` change and `gradle assembleDebug --no-daemon` after every task before committing.
- Commit after each task, on branch `V8`. Do not push unless asked.

---

### Task 1: CI builds every branch and fails on a signer regression

**Files:**
- Modify: `.github/workflows/build.yml`
- Create: `app/expected-signer.txt`
- Modify: `BUILD.md`

**Interfaces:**
- Produces: `app/expected-signer.txt` — a repo file holding the one expected SHA-256 signer fingerprint, colon-separated uppercase hex, read by CI and by any human running the same `apksigner` command locally.

- [ ] **Step 1: Widen the CI trigger to every branch**

In `.github/workflows/build.yml`, change:
```yaml
on:
  push:
    branches: [main, V7]
  workflow_dispatch:
```
to:
```yaml
on:
  push:
    branches: ['**']
  workflow_dispatch:
```

- [ ] **Step 2: Record the expected signer fingerprint**

Create `app/expected-signer.txt` with exactly this content (no trailing newline beyond the one line):
```
84:19:79:66:7C:83:5B:0C:44:D8:E6:62:8A:24:76:45:60:86:3F:14:CF:88:9D:43:D7:84:AD:C2:16:B5:13:54
```

- [ ] **Step 3: Add an Android SDK setup step and the signer-verification step to CI**

The GitHub-hosted `ubuntu-latest` image's preinstalled SDK build-tools version can drift; pin it explicitly so `apksigner` is always at a known path. Insert this step in `.github/workflows/build.yml` right after the `setup-gradle` step (before "Unit tests"):

```yaml
      - uses: android-actions/setup-android@v3

      - name: Install build-tools
        run: sdkmanager "build-tools;36.0.0"
```

Then, after the existing "Build" step and before "Report APK size", insert:

```yaml
      - name: Verify signer
        run: |
          APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
          FINGERPRINT=$("$APKSIGNER" verify --print-certs app/build/outputs/apk/debug/app-debug.apk \
            | grep 'Signer #1 certificate SHA-256 digest' | awk '{print $NF}' | tr 'a-f' 'A-F' \
            | sed 's/\(..\)/\1:/g; s/:$//')
          EXPECTED=$(cat app/expected-signer.txt | tr -d '\n')
          echo "Built with signer:    $FINGERPRINT"
          echo "Expected signer:      $EXPECTED"
          if [ "$FINGERPRINT" != "$EXPECTED" ]; then
            echo "::error::Signer fingerprint mismatch — this build would break updates for every installed copy."
            exit 1
          fi
```

The full `build.yml` after this task's edits should read, top to bottom:
```yaml
name: Build APK

on:
  push:
    branches: ['**']
  workflow_dispatch:

concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v4
        with:
          # Pinned with the AGP version in build.gradle: AGP 8.13.2 needs
          # Gradle 8.14 or newer. There is no wrapper in this repo, so the
          # two have to be changed together.
          gradle-version: '8.14.5'

      - uses: android-actions/setup-android@v3

      - name: Install build-tools
        run: sdkmanager "build-tools;36.0.0"

      - name: Unit tests
        run: gradle :core:test --no-daemon

      - name: Build
        run: gradle assembleDebug --no-daemon

      - name: Verify signer
        run: |
          APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
          FINGERPRINT=$("$APKSIGNER" verify --print-certs app/build/outputs/apk/debug/app-debug.apk \
            | grep 'Signer #1 certificate SHA-256 digest' | awk '{print $NF}' | tr 'a-f' 'A-F' \
            | sed 's/\(..\)/\1:/g; s/:$//')
          EXPECTED=$(cat app/expected-signer.txt | tr -d '\n')
          echo "Built with signer:    $FINGERPRINT"
          echo "Expected signer:      $EXPECTED"
          if [ "$FINGERPRINT" != "$EXPECTED" ]; then
            echo "::error::Signer fingerprint mismatch — this build would break updates for every installed copy."
            exit 1
          fi

      - name: Report APK size
        run: ls -lh app/build/outputs/apk/debug/*.apk

      - name: Publish release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release create "build-${{ github.run_number }}" \
            app/build/outputs/apk/debug/app-debug.apk \
            --title "Build ${{ github.run_number }}" \
            --notes "Automated build from ${{ github.sha }}. Signer: $(cat app/expected-signer.txt). versionCode: $((${{ github.run_number }} + 1000))."
```

- [ ] **Step 4: Document the one-time uninstall and the new gate in BUILD.md**

In `BUILD.md`, under the existing `## Versioning and signing` section, after the paragraph ending "Every build after that updates cleanly.", append:

```markdown

**CI verifies the signer on every run.** `app/expected-signer.txt` holds the
one fingerprint every build must carry. The "Verify signer" step in
`.github/workflows/build.yml` runs `apksigner verify --print-certs` on the
freshly built APK and fails the build if the digest does not match — this is
the check whose absence let three months of unsignable releases ship before
V8. If it fails, the keystore or signing config changed; that is a stop,
not a fingerprint to update casually.

**Every push, on every branch, now builds.** The workflow used to trigger
only on `main` and `V7`; a push to any other branch produced no APK and no
signal that CI even ran. It now triggers on `branches: ['**']`.

**Diagnosing a failed update:** compare the release notes' signer line
against the device's installed signer with
`adb shell dumpsys package com.retro.launcher | grep -A2 signatures`.
```

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/build.yml app/expected-signer.txt BUILD.md
git commit -m "$(cat <<'EOF'
ci: build every branch and gate on a signer fingerprint

CI only triggered on main/V7, so pushing V8 built nothing. And nothing
verified the signer, which is exactly how three months of ephemeral-keystore
builds shipped before V7's fix landed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 2: `Detents` — bucketed drag levels with hysteresis

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/Detents.java`
- Create: `core/src/test/java/com/retro/launcher/core/DetentsTest.java`

**Interfaces:**
- Produces: `Detents.level(float progress)` → `int` in `[0, 5]`. Consumed by Task 3's `Haptics.dragProgress`.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/com/retro/launcher/core/DetentsTest.java`:

```java
package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class DetentsTest {

    @Test public void levelIsZeroBelowTheFirstThreshold() {
        assertEquals(0, Detents.level(0f));
        assertEquals(0, Detents.level(0.19f));
    }

    @Test public void levelIsOneAtExactlyTheFirstThreshold() {
        assertEquals(1, Detents.level(0.2f));
    }

    @Test public void levelIsFiveAtOne() {
        assertEquals(5, Detents.level(1f));
    }

    @Test public void progressOutsideZeroToOneClamps() {
        assertEquals(0, Detents.level(-1f));
        assertEquals(5, Detents.level(2f));
    }

    @Test public void nanReadsAsZero() {
        assertEquals(0, Detents.level(Float.NaN));
    }

    @Test public void risingThrough0Point2EntersLevel1() {
        Detents d = new Detents();
        assertEquals(0, d.next(0.19f));
        assertEquals(1, d.next(0.20f));
    }

    @Test public void fallingTo0Point19StaysAtLevel1() {
        Detents d = new Detents();
        d.next(0.20f);
        assertEquals(1, d.next(0.19f));
    }

    @Test public void fallingTo0Point18ReturnsToLevel0() {
        Detents d = new Detents();
        d.next(0.20f);
        d.next(0.19f);
        assertEquals(0, d.next(0.18f));
    }

    @Test public void aMonotonicSweepProducesExactlyFiveLevelChanges() {
        Detents d = new Detents();
        int changes = 0;
        int last = d.next(0f);
        for (int i = 1; i <= 120; i++) {
            int lvl = d.next(i / 120f);
            if (lvl != last) { changes++; last = lvl; }
        }
        assertEquals(5, changes);
    }

    @Test public void aSweepUpThenDownProducesExactlyTenLevelChanges() {
        Detents d = new Detents();
        int changes = 0;
        int last = d.next(0f);
        for (int i = 1; i <= 120; i++) {
            int lvl = d.next(i / 120f);
            if (lvl != last) { changes++; last = lvl; }
        }
        for (int i = 119; i >= 0; i--) {
            int lvl = d.next(i / 120f);
            if (lvl != last) { changes++; last = lvl; }
        }
        assertEquals(10, changes);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests com.retro.launcher.core.DetentsTest --no-daemon`
Expected: FAIL — `Detents` does not exist.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/java/com/retro/launcher/core/Detents.java`:

```java
package com.retro.launcher.core;

/**
 * How many of a panel drag's five haptic thresholds have been crossed.
 *
 * <p>Replaces {@code HapticCurve}. That class drove a continuously repeating
 * vibration waveform for the whole length of a drag, which is what tripped
 * the platform's per-app vibration cutoff on a slow expansion — see the V8
 * design spec item 2. This class only ever says which threshold band the
 * drag is currently in; {@code Haptics} turns each *change* of band into one
 * short, non-repeating pulse.
 *
 * <p>{@link #level(float)} is the stateless function: five thresholds at
 * {@code 0.2, 0.4, 0.6, 0.8, 1.0}, so {@code level} runs {@code 0..5}.
 * {@code progress} outside {@code [0, 1]} clamps; {@code NaN} reads as 0 —
 * the same contract {@code HapticCurve.bucket} had, so callers need no new
 * NaN handling.
 *
 * <p>An instance additionally applies {@link #HYSTERESIS}: a level is only
 * entered once progress passes {@code 0.02} beyond its threshold, and only
 * left once progress falls {@code 0.02} back below it. Without this a finger
 * resting exactly on a boundary would chatter the vibrator on every frame of
 * sensor jitter.
 */
public final class Detents {

    /** Five bands, thresholds at 20% intervals. */
    private static final float[] THRESHOLDS = {0.2f, 0.4f, 0.6f, 0.8f, 1.0f};

    /** How far past a threshold progress must move before the level change
     *  commits, in either direction. */
    public static final float HYSTERESIS = 0.02f;

    private int currentLevel = 0;

    /** Stateless: which band {@code progress} falls in, with no hysteresis. */
    public static int level(float progress) {
        float p = clamp01(progress);
        int level = 0;
        for (float t : THRESHOLDS) {
            if (p >= t) level++;
        }
        return level;
    }

    /**
     * Stateful: the level after this reading, applying hysteresis against
     * whatever level the previous call to {@link #next} settled on. The
     * first call on a fresh instance has no prior level to hold onto, so it
     * behaves exactly like {@link #level}.
     */
    public int next(float progress) {
        float p = clamp01(progress);
        int rising = level(p);
        if (rising > currentLevel) {
            // Moving up: commit as soon as the plain threshold is crossed —
            // hysteresis only guards the downward re-cross.
            currentLevel = rising;
            return currentLevel;
        }
        if (rising < currentLevel) {
            // Moving down: only leave the current level once progress has
            // fallen HYSTERESIS below the threshold that entered it.
            float enteringThreshold = currentLevel == 0 ? 0f : THRESHOLDS[currentLevel - 1];
            if (p <= enteringThreshold - HYSTERESIS) {
                currentLevel = rising;
            }
        }
        return currentLevel;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests com.retro.launcher.core.DetentsTest --no-daemon`
Expected: PASS, 9/9.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/Detents.java \
        core/src/test/java/com/retro/launcher/core/DetentsTest.java
git commit -m "$(cat <<'EOF'
feat: add Detents, a hysteresis-guarded drag-level function

Pure replacement for HapticCurve's bucket math — Task 3 rewires Haptics
onto it, dropping the repeating-waveform drag buzz that overran the
vibrator.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 3: Rewrite `Haptics` onto `Detents`; delete `HapticCurve`

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/util/Haptics.java`
- Delete: `core/src/main/java/com/retro/launcher/core/HapticCurve.java`
- Delete: `core/src/test/java/com/retro/launcher/core/HapticCurveTest.java`

**Interfaces:**
- Consumes: `Detents.next(float progress)` from Task 2.
- Produces: `Haptics.dragStart()`, `Haptics.dragProgress(float)`, `Haptics.dragEnd()` — same names/signatures `LauncherRoot.java:410,436,449` already call; no call-site changes needed.

- [ ] **Step 1: Delete `HapticCurve` and its test**

```bash
git rm core/src/main/java/com/retro/launcher/core/HapticCurve.java
git rm core/src/test/java/com/retro/launcher/core/HapticCurveTest.java
```

- [ ] **Step 2: Confirm the build now fails where `Haptics` used `HapticCurve`**

Run: `gradle assembleDebug --no-daemon`
Expected: FAIL — `Haptics.java` cannot resolve `HapticCurve`.

- [ ] **Step 3: Rewrite `Haptics.java`**

Replace the full contents of `app/src/main/java/com/retro/launcher/util/Haptics.java` with:

```java
package com.retro.launcher.util;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.retro.launcher.core.Detents;

/**
 * Every vibration the launcher makes.
 *
 * <p>The master preference is checked on <em>every</em> entry point rather
 * than at construction, so a single {@code setEnabled(false)} silences the
 * whole app the instant the toggle moves, including a drag already in flight.
 *
 * <p>Taps are one-shots. A drag used to be a repeating waveform re-commanded
 * as its amplitude bucket changed — up to 40ms of continuous motor time per
 * commit, held open for the whole drag. That is what tripped the platform's
 * per-app vibration cutoff on a slow panel expansion (V8 design spec item
 * 2). A drag is now a sequence of short, non-repeating "detent" pulses, one
 * per {@link Detents} threshold crossed, in either direction, capped at
 * {@link #MAX_TICKS_PER_GESTURE} per gesture so an oscillating drag cannot
 * re-drive the motor indefinitely.
 *
 * <p>Every failure is silence. A device with no vibrator, a vibrator the
 * system has muted, an amplitude the motor cannot express: none of them are
 * worth an exception on a launcher's touch path.
 */
public final class Haptics {

    /** A single expansion costs 5 ticks; six deliberate up-down sweeps still
     *  feel right at 10 ticks each. Only pathological jitter reaches this. */
    private static final int MAX_TICKS_PER_GESTURE = 12;

    private final Vibrator vibrator;
    private boolean enabled;

    private final Detents detents = new Detents();
    /** -1 while no drag is running — matches {@code Detents}'s own "nothing
     *  entered yet" state so the first frame of a drag always ticks. */
    private int dragLevel = -1;
    private int ticksThisGesture;

    public Haptics(Context context, boolean enabled) {
        this.enabled = enabled;
        this.vibrator = resolveVibrator(context.getApplicationContext());
    }

    private static Vibrator resolveVibrator(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm == null ? null : vm.getDefaultVibrator();
        }
        return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /** Turning haptics off stops anything already running. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) dragEnd();
    }

    public boolean isEnabled() { return enabled; }

    private boolean unavailable() {
        return !enabled || vibrator == null || !vibrator.hasVibrator();
    }

    /** Every interactive tap in the launcher. */
    public void click() {
        if (unavailable()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(12L, 60));
            }
        } catch (RuntimeException ignored) {
            // A vendor vibrator that refuses the effect. Silence is fine.
        }
    }

    /** The two long-press surfaces and the category-tab long-press. */
    public void longPress() {
        if (unavailable()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(24L, 140));
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Resets detent state for a new drag. Commands nothing. */
    public void dragStart() {
        dragLevel = -1;
        ticksThisGesture = 0;
    }

    /**
     * Re-derives the detent level from {@code progress} and fires one
     * {@link #detentTick()} if it moved — in either direction, so pulling a
     * panel back down ticks as it re-crosses a threshold same as opening it.
     * Safe and cheap to call from every frame of a drag — that is what it is
     * for.
     *
     * @param progress 0 at rest, 1 at the snap threshold
     */
    public void dragProgress(float progress) {
        if (unavailable()) return;
        int level = detents.next(progress);
        if (level == dragLevel) return;
        dragLevel = level;
        if (ticksThisGesture >= MAX_TICKS_PER_GESTURE) return;
        ticksThisGesture++;
        detentTick();
    }

    /** Ends the drag. Idempotent, and safe when no drag is running. Retains
     *  {@code vibrator.cancel()} for safety even though a detent tick is a
     *  one-shot with nothing left running to cancel. */
    public void dragEnd() {
        dragLevel = -1;
        ticksThisGesture = 0;
        if (vibrator == null) return;
        try {
            vibrator.cancel();
        } catch (RuntimeException ignored) {
        }
    }

    /** One short pulse marking a detent crossing. Never a repeating waveform. */
    private void detentTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(10L, 80));
            }
        } catch (RuntimeException ignored) {
        }
    }
}
```

- [ ] **Step 4: Build and run the full test suite**

Run: `gradle :core:test --no-daemon && gradle assembleDebug --no-daemon`
Expected: both PASS. `LauncherRoot.java` needs no changes — it already calls `dragStart()`/`dragProgress(float)`/`dragEnd()` with no other type dependency on the old class.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/retro/launcher/util/Haptics.java \
        core/src/main/java/com/retro/launcher/core/HapticCurve.java \
        core/src/test/java/com/retro/launcher/core/HapticCurveTest.java
git commit -m "$(cat <<'EOF'
fix: replace the repeating drag-haptics waveform with bounded detent ticks

Haptics.applyDragBucket ran a continuously repeating VibrationEffect for
the whole length of a drag, which is what tripped the platform's per-app
vibration cutoff and silenced haptics device-wide. Each of Detents' five
thresholds now fires one non-repeating EFFECT_TICK, capped at 12 ticks per
gesture.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 4: Fix the moon's screen position — `thMoon` follows elongation

**Files:**
- Modify: `core/src/main/java/com/retro/launcher/core/SkyRenderer.java`
- Modify: `core/src/test/java/com/retro/launcher/core/SkyRendererTest.java`

**Interfaces:**
- Produces: package-private static `SkyRenderer.sunAngle(float hour)` and `SkyRenderer.moonAngle(float hour, float phase)`, used by this task's test file only.

**Why the test file changes too:** `SkyRendererTest` hardcodes the *old* moon-position formula (`thMoon = thSun + π`, independent of phase) in its own private `moonX`/`moonY` helpers, used to locate the moon disc for sampling. Under the new formula the disc's position depends on phase, so several existing tests that exercise non-full phases (waxing/waning crescent, the southern-view mirror) are locating the wrong pixels once the fix lands. This step corrects those helpers and the hours they sample at.

- [ ] **Step 1: Extend the test with the new angle relationship**

In `core/src/test/java/com/retro/launcher/core/SkyRendererTest.java`, add these test methods (anywhere among the other `@Test` methods, e.g. directly after `sunAltitudePeaksAtNoonAndBottomsAtMidnight`):

```java
    @Test public void moonAngleMatchesSunAngleAtFullMoon() {
        // Preserves today's one night where the old fixed-opposite formula
        // happened to be correct.
        assertEquals(SkyRenderer.sunAngle(9f) + (float) Math.PI,
                     SkyRenderer.moonAngle(9f, 0.5f), 0.0001f);
    }

    @Test public void moonAngleMatchesSunAngleAtNewMoon() {
        assertEquals(SkyRenderer.sunAngle(9f), SkyRenderer.moonAngle(9f, 0f), 0.0001f);
    }

    @Test public void moonAngleOffsetIsAQuarterTurnAtFirstQuarter() {
        float offset = SkyRenderer.moonAngle(9f, 0.25f) - SkyRenderer.sunAngle(9f);
        assertEquals((float) Math.PI / 2f, offset, 0.0001f);
    }

    @Test public void moonAngleIsMonotonicInPhase() {
        float prev = SkyRenderer.moonAngle(9f, 0f);
        for (int i = 1; i <= 100; i++) {
            float phase = i / 100f;
            float cur = SkyRenderer.moonAngle(9f, phase);
            assertTrue("angle did not advance at phase " + phase, cur > prev);
            prev = cur;
        }
    }
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `gradle :core:test --tests com.retro.launcher.core.SkyRendererTest --no-daemon`
Expected: FAIL — `SkyRenderer.sunAngle`/`moonAngle` do not exist yet.

- [ ] **Step 3: Extract the angle helpers and fix the position formula in `SkyRenderer.java`**

Replace lines 155–157 of `core/src/main/java/com/retro/launcher/core/SkyRenderer.java`:
```java
        // Body positions — DESIGN_NOTES §2b.
        final float thSun  = (hour - 6f) / 12f * (float) Math.PI;
        final float thMoon = thSun + (float) Math.PI;
```
with:
```java
        // Body positions — DESIGN_NOTES §2b.
        final float thSun  = sunAngle(hour);
        final float thMoon = moonAngle(hour, moonPhase);
```

Then add these two static methods near the bottom of the class, right before `public static float sunAlt(float hour)`:

```java
    /** The sun's hour angle: 0 at 06:00, π at 18:00. */
    static float sunAngle(float hour) {
        return (hour - 6f) / 12f * (float) Math.PI;
    }

    /**
     * The moon's hour angle. The moon lags the sun by exactly its
     * elongation: new moon ({@code phase 0}) puts it on the sun; full
     * ({@code phase 0.5}) puts it opposite — the one case the old fixed
     * {@code thSun + π} formula got right, since it assumed every night was
     * a full moon. The quarters sit a quarter turn off, matching them
     * rising/setting roughly six hours from the sun.
     *
     * <p>Ignores lunar declination and the parallactic angle, so this is
     * right to roughly the hour rather than the minute — see MoonPhase's
     * own javadoc for the same limit on the phase itself. Against a 12px
     * disc that is the correct place to stop.
     */
    static float moonAngle(float hour, float phase) {
        return sunAngle(hour) + 2f * (float) Math.PI * phase;
    }
```

- [ ] **Step 4: Run the new tests to verify they pass**

Run: `gradle :core:test --tests com.retro.launcher.core.SkyRendererTest --no-daemon`
Expected: the four new tests PASS. Several existing tests now FAIL (`moonDiscAppearsAtNight`, `waxingCrescentIsLitOnTheRightFromTheNorth`, `waningCrescentIsLitOnTheLeftFromTheNorth`, `theSouthernViewMirrorsTheTerminator`, `starsOnlyAppearAtNight`, `quantizationSnapsToFifteenLevelSteps` if it starts sampling wrong pixels) — expected, fixed in the next step.

- [ ] **Step 5: Fix the test file's own moon-position helpers**

In `core/src/test/java/com/retro/launcher/core/SkyRendererTest.java`, replace lines 101–108 (the `sunX`/`sunY`/`moonX`/`moonY` block, keeping `sunX`/`sunY` as-is since the sun formula did not change):

Find:
```java
    private static float sunX(float hour) {
        float thSun = (float) ((hour - 6) / 12.0 * Math.PI);
        return 72f - (float) Math.cos(thSun) * 60f;
    }
    private static float sunY(float hour, int h) {
        float thSun = (float) ((hour - 6) / 12.0 * Math.PI);
        float travel = 0.3125f * h;
        return 0.667f * h + (1f - (float) Math.sin(thSun)) * travel;
    }
    private static float moonX(float hour) {
        float thMoon = (float) ((hour - 6) / 12.0 * Math.PI) + (float) Math.PI;
        return 36f - (float) Math.cos(thMoon) * 60f;
    }
    private static float moonY(float hour, int h) {
        float thMoon = (float) ((hour - 6) / 12.0 * Math.PI) + (float) Math.PI;
        float travel = 0.3125f * h;
        return 0.333f * h - (1f - (float) Math.sin(thMoon)) * travel;
    }
```

Replace with:
```java
    private static float sunX(float hour) {
        float thSun = (float) ((hour - 6) / 12.0 * Math.PI);
        return 72f - (float) Math.cos(thSun) * 60f;
    }
    private static float sunY(float hour, int h) {
        float thSun = (float) ((hour - 6) / 12.0 * Math.PI);
        float travel = 0.3125f * h;
        return 0.667f * h + (1f - (float) Math.sin(thSun)) * travel;
    }
    /** The hour at which a moon of this phase sits highest on screen —
     *  moonAngle(hour, phase) == π/2 — so a test can pick a phase without
     *  hand-picking an hour where that phase's disc happens to be visible. */
    private static float hourForMoonOverhead(float phase) {
        float hour = 12f - 24f * phase;
        return ((hour % 24f) + 24f) % 24f;
    }
    private static float moonX(float hour, float phase) {
        float thMoon = (float) ((hour - 6) / 12.0 * Math.PI) + 2f * (float) Math.PI * phase;
        return 36f - (float) Math.cos(thMoon) * 60f;
    }
    private static float moonY(float hour, float phase, int h) {
        float thMoon = (float) ((hour - 6) / 12.0 * Math.PI) + 2f * (float) Math.PI * phase;
        float travel = 0.3125f * h;
        return 0.333f * h - (1f - (float) Math.sin(thMoon)) * travel;
    }
```

- [ ] **Step 6: Fix every call site of the changed `moonX`/`moonY` signatures**

Six call sites need a phase argument added. All the tests below use `renderAt(hour, weather)`, whose body renders at a hardcoded `moonPhase` of `0.62f` — pass that same `0.62f` wherever the disc came from a `renderAt(...)` call. Apply these exact edits in `SkyRendererTest.java`:

Line 57 (`quantizationSnapsToFifteenLevelSteps`), change:
```java
        int mx = Math.round(moonX(12f)), my = Math.round(moonY(12f, H));
```
to:
```java
        int mx = Math.round(moonX(12f, 0.62f)), my = Math.round(moonY(12f, 0.62f, H));
```

Line 134 (`moonDiscAppearsAtNight`), change:
```java
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
```
to:
```java
        int cx = Math.round(moonX(0f, 0.62f)), cy = Math.round(moonY(0f, 0.62f, H));
```

Line 147 (`fullMoonIsBrighterThanNewMoon` — this one renders at explicit phases `0.5f`/`0.0f` at hour `0f`, not via `renderAt`; the sample point should be the *full* moon's own position), change:
```java
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
```
to:
```java
        int cx = Math.round(moonX(0f, 0.5f)), cy = Math.round(moonY(0f, 0.5f, H));
```

Lines 152–158 (`moonAt` helper — render at the hour each phase is actually visible, not a fixed midnight), change:
```java
    private int[] moonAt(float phase, boolean southern) {
        SkyRenderer r = new SkyRenderer(W, H);
        r.setSouthernView(southern);
        int[] buf = new int[W * H];
        r.render(buf, 0f, 0f, phase, 0f);
        return buf;
    }
```
to:
```java
    private int[] moonAt(float phase, boolean southern) {
        SkyRenderer r = new SkyRenderer(W, H);
        r.setSouthernView(southern);
        int[] buf = new int[W * H];
        r.render(buf, hourForMoonOverhead(phase), 0f, phase, 0f);
        return buf;
    }
```

Line 162 (`waxingCrescentIsLitOnTheRightFromTheNorth`), change:
```java
        int[] buf = moonAt(0.12f, false);
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
```
to:
```java
        int[] buf = moonAt(0.12f, false);
        float hour = hourForMoonOverhead(0.12f);
        int cx = Math.round(moonX(hour, 0.12f)), cy = Math.round(moonY(hour, 0.12f, H));
```

Line 168 (`waningCrescentIsLitOnTheLeftFromTheNorth`), change:
```java
        int[] buf = moonAt(0.88f, false);
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
```
to:
```java
        int[] buf = moonAt(0.88f, false);
        float hour = hourForMoonOverhead(0.88f);
        int cx = Math.round(moonX(hour, 0.88f)), cy = Math.round(moonY(hour, 0.88f, H));
```

Lines 174–176 (`theSouthernViewMirrorsTheTerminator`), change:
```java
        int[] north = moonAt(0.12f, false);
        int[] south = moonAt(0.12f, true);
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
```
to:
```java
        int[] north = moonAt(0.12f, false);
        int[] south = moonAt(0.12f, true);
        float hour = hourForMoonOverhead(0.12f);
        int cx = Math.round(moonX(hour, 0.12f)), cy = Math.round(moonY(hour, 0.12f, H));
```

Line 183 (`hemisphereDoesNotChangeHowMuchOfAFullMoonIsLit`), change:
```java
        int cx = Math.round(moonX(0f)), cy = Math.round(moonY(0f, H));
```
to:
```java
        int cx = Math.round(moonX(0f, 0.5f)), cy = Math.round(moonY(0f, 0.5f, H));
```

Line 216 (`starsOnlyAppearAtNight`, night half — `renderAt(0f, 0f)` uses phase `0.62f`), change:
```java
        int mxN = Math.round(moonX(0f)), myN = Math.round(moonY(0f, H));
```
to:
```java
        int mxN = Math.round(moonX(0f, 0.62f)), myN = Math.round(moonY(0f, 0.62f, H));
```

Line 227 (`starsOnlyAppearAtNight`, day half — `renderAt(12f, 0f)` uses phase `0.62f`), change:
```java
        int mxD = Math.round(moonX(12f)), myD = Math.round(moonY(12f, H));
```
to:
```java
        int mxD = Math.round(moonX(12f, 0.62f)), myD = Math.round(moonY(12f, 0.62f, H));
```

- [ ] **Step 7: Run the full test file to verify everything passes**

Run: `gradle :core:test --tests com.retro.launcher.core.SkyRendererTest --no-daemon`
Expected: PASS, all tests including the 4 new ones.

- [ ] **Step 8: Run the whole `:core` suite to confirm nothing else broke**

Run: `gradle :core:test --no-daemon`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/SkyRenderer.java \
        core/src/test/java/com/retro/launcher/core/SkyRendererTest.java
git commit -m "$(cat <<'EOF'
fix: draw the moon at its true elongation from the sun, not always opposite

thMoon was pinned to thSun + π, so the moon rose at sunset every night —
true only at full moon. It now lags the sun by exactly moonPhase * 2π,
matching MoonPhase's already-correct phase calculation to the moon's
actual sky position.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 5: `SolarTimes` and `SolarMath` — the offline sunrise/sunset fallback

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/SolarTimes.java`
- Create: `core/src/main/java/com/retro/launcher/core/SolarMath.java`
- Create: `core/src/test/java/com/retro/launcher/core/SolarMathTest.java`

**Interfaces:**
- Produces: `SolarTimes(float sunriseHour, float sunsetHour, float tomorrowSunriseHour, java.time.LocalDate date)` — immutable holder, all hours as decimal local hours in `[0, 24)`. `SolarMath.sunTimes(float latitude, float longitude, java.time.LocalDate date, java.time.ZoneId zone)` → `SolarTimes` or `null` for a degenerate (polar) case. Consumed by Task 8 (`WeatherParser`) and Task 9 (`OpenMeteoWeather`/`WeatherRepository`).

- [ ] **Step 1: Create the `SolarTimes` holder**

Create `core/src/main/java/com/retro/launcher/core/SolarTimes.java`:

```java
package com.retro.launcher.core;

import java.time.LocalDate;

/**
 * One day's sunrise, sunset, and the *next* day's sunrise — the last one is
 * what {@link SolarClock}'s night warp needs to know where "night" ends —
 * each as a decimal local hour in {@code [0, 24)}, plus the date they
 * belong to. Immutable.
 */
public final class SolarTimes {

    public final float sunriseHour;
    public final float sunsetHour;
    public final float tomorrowSunriseHour;
    public final LocalDate date;

    public SolarTimes(float sunriseHour, float sunsetHour, float tomorrowSunriseHour, LocalDate date) {
        this.sunriseHour = sunriseHour;
        this.sunsetHour = sunsetHour;
        this.tomorrowSunriseHour = tomorrowSunriseHour;
        this.date = date;
    }
}
```

- [ ] **Step 2: Write the failing `SolarMath` test**

Create `core/src/test/java/com/retro/launcher/core/SolarMathTest.java`. These three reference triples were independently computed with the widely used `astral` sunrise/sunset library (same NOAA-family sunrise equation this class implements) for the exact date/location/zone below, so a correct implementation should land within the two-minute tolerance used:

```java
package com.retro.launcher.core;

import org.junit.Test;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.junit.Assert.*;

public class SolarMathTest {

    /** Two minutes, in decimal hours — the precision the spec claims. */
    private static final float TOL_HOURS = 2f / 60f;

    @Test public void newYorkSummerSolstice() {
        SolarTimes t = SolarMath.sunTimes(40.7128f, -74.0060f,
                LocalDate.of(2026, 6, 21), ZoneId.of("America/New_York"));
        assertNotNull(t);
        assertEquals(5.4214f, t.sunriseHour, TOL_HOURS);
        assertEquals(20.5081f, t.sunsetHour, TOL_HOURS);
        assertEquals(5.4253f, t.tomorrowSunriseHour, TOL_HOURS);
        assertEquals(LocalDate.of(2026, 6, 21), t.date);
    }

    @Test public void londonNearEquinox() {
        SolarTimes t = SolarMath.sunTimes(51.5074f, -0.1278f,
                LocalDate.of(2026, 3, 20), ZoneId.of("Europe/London"));
        assertNotNull(t);
        assertEquals(6.0608f, t.sunriseHour, TOL_HOURS);
        assertEquals(18.2206f, t.sunsetHour, TOL_HOURS);
        assertEquals(6.0228f, t.tomorrowSunriseHour, TOL_HOURS);
    }

    @Test public void sydneySummerSolstice() {
        SolarTimes t = SolarMath.sunTimes(-33.8688f, 151.2093f,
                LocalDate.of(2026, 12, 21), ZoneId.of("Australia/Sydney"));
        assertNotNull(t);
        assertEquals(5.6814f, t.sunriseHour, TOL_HOURS);
        assertEquals(20.0864f, t.sunsetHour, TOL_HOURS);
        assertEquals(5.6894f, t.tomorrowSunriseHour, TOL_HOURS);
    }

    /** Tromsø, Norway, well inside the Arctic Circle, at the summer
     *  solstice: the sun never sets. No sunrise/sunset exists that day. */
    @Test public void polarDayReturnsNull() {
        SolarTimes t = SolarMath.sunTimes(69.6492f, 18.9553f,
                LocalDate.of(2026, 6, 21), ZoneId.of("Europe/Oslo"));
        assertNull(t);
    }

    @Test public void everyHourIsInRange() {
        SolarTimes t = SolarMath.sunTimes(51.5074f, -0.1278f,
                LocalDate.of(2026, 3, 20), ZoneId.of("Europe/London"));
        assertNotNull(t);
        assertTrue(t.sunriseHour >= 0f && t.sunriseHour < 24f);
        assertTrue(t.sunsetHour >= 0f && t.sunsetHour < 24f);
        assertTrue(t.tomorrowSunriseHour >= 0f && t.tomorrowSunriseHour < 24f);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `gradle :core:test --tests com.retro.launcher.core.SolarMathTest --no-daemon`
Expected: FAIL — `SolarMath` does not exist.

- [ ] **Step 4: Implement `SolarMath`**

Create `core/src/main/java/com/retro/launcher/core/SolarMath.java`:

```java
package com.retro.launcher.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.JulianFields;

/**
 * Sunrise and sunset from latitude, longitude and date, offline — the
 * fallback for when {@code OpenMeteoWeather}'s network fetch does not answer
 * or its {@code daily} block cannot be parsed. Pure math, no I/O, no Android
 * type. Implements the standard NOAA-derived sunrise equation (see
 * Wikipedia, "Sunrise equation"), correct to roughly a minute — far finer
 * than the 14-keyframe sky gradient this feeds can express.
 */
public final class SolarMath {

    private SolarMath() {}

    /**
     * @return today's sunrise, today's sunset and tomorrow's sunrise as
     *         decimal local hours in {@code zone}, or {@code null} if the
     *         sun does not rise or set that day at this latitude (polar day
     *         or polar night) on either day.
     */
    public static SolarTimes sunTimes(float latitude, float longitude, LocalDate date, ZoneId zone) {
        DayResult today = compute(latitude, longitude, date, zone);
        if (today == null) return null;
        DayResult tomorrow = compute(latitude, longitude, date.plusDays(1), zone);
        if (tomorrow == null) return null;
        return new SolarTimes(today.sunrise, today.sunset, tomorrow.sunrise, date);
    }

    private static final class DayResult {
        float sunrise, sunset;
    }

    private static DayResult compute(float latitude, float longitude, LocalDate date, ZoneId zone) {
        long julianDay = date.getLong(JulianFields.JULIAN_DAY);
        double n = julianDay - 2451545.0 + 0.0008;
        double meanSolarNoon = n - longitude / 360.0;
        double solarMeanAnomaly = norm360(357.5291 + 0.98560028 * meanSolarNoon);
        double eqCenter = 1.9148 * sinDeg(solarMeanAnomaly)
                         + 0.0200 * sinDeg(2 * solarMeanAnomaly)
                         + 0.0003 * sinDeg(3 * solarMeanAnomaly);
        double eclipticLongitude = norm360(solarMeanAnomaly + 102.9372 + eqCenter + 180.0);
        double solarTransit = meanSolarNoon
                + 0.0053 * sinDeg(solarMeanAnomaly)
                - 0.0069 * sinDeg(2 * eclipticLongitude);
        double declination = Math.asin(sinDeg(eclipticLongitude) * sinDeg(23.4397));

        double latRad = Math.toRadians(latitude);
        double cosHourAngle = (sinDeg(-0.833) - Math.sin(latRad) * Math.sin(declination))
                             / (Math.cos(latRad) * Math.cos(declination));
        if (Double.isNaN(cosHourAngle) || cosHourAngle < -1.0 || cosHourAngle > 1.0) {
            return null; // polar day (sun never sets) or polar night (never rises)
        }
        double hourAngle = Math.toDegrees(Math.acos(cosHourAngle));

        double sunriseJulian = solarTransit - hourAngle / 360.0;
        double sunsetJulian  = solarTransit + hourAngle / 360.0;

        Float sunriseHour = toLocalHour(sunriseJulian, date, zone);
        Float sunsetHour  = toLocalHour(sunsetJulian, date, zone);
        if (sunriseHour == null || sunsetHour == null) return null;

        DayResult r = new DayResult();
        r.sunrise = sunriseHour;
        r.sunset = sunsetHour;
        return r;
    }

    /**
     * Converts a fractional Julian Day (UT) into a decimal local hour on
     * {@code date} in {@code zone}. {@code julianDayFraction} may fall
     * slightly before or after {@code date}'s own UTC midnight—midnight
     * window; the result is still normalized into {@code [0, 24)}.
     */
    private static Float toLocalHour(double julianDayFraction, LocalDate date, ZoneId zone) {
        double jdMidnightUtc = date.getLong(JulianFields.JULIAN_DAY) - 0.5;
        double utDaysSinceMidnight = julianDayFraction - jdMidnightUtc;
        double utHour = utDaysSinceMidnight * 24.0;

        LocalDateTime approxLocalMidnight = date.atStartOfDay();
        ZoneOffset offset = zone.getRules().getOffset(approxLocalMidnight);
        double localHour = utHour + offset.getTotalSeconds() / 3600.0;

        double h = localHour % 24.0;
        if (h < 0) h += 24.0;
        if (Double.isNaN(h) || Double.isInfinite(h)) return null;
        return (float) h;
    }

    private static double sinDeg(double degrees) {
        return Math.sin(Math.toRadians(degrees));
    }

    private static double norm360(double degrees) {
        double v = degrees % 360.0;
        return v < 0 ? v + 360.0 : v;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `gradle :core:test --tests com.retro.launcher.core.SolarMathTest --no-daemon`
Expected: PASS, 6/6. If a triple is outside tolerance, double check the `ZoneId` string and the sign of `longitude` (west is negative) before touching the algorithm.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/SolarTimes.java \
        core/src/main/java/com/retro/launcher/core/SolarMath.java \
        core/src/test/java/com/retro/launcher/core/SolarMathTest.java
git commit -m "$(cat <<'EOF'
feat: add SolarTimes and SolarMath, the offline sunrise/sunset fallback

Pure NOAA-derived sunrise equation in :core, good to about a minute, for
when the network sunrise/sunset fetch (Task 8/9) is unavailable.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 6: `SolarClock` — the time warp

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/SolarClock.java`
- Create: `core/src/test/java/com/retro/launcher/core/SolarClockTest.java`

**Interfaces:**
- Consumes: nothing (pure floats in, no dependency on `SolarTimes`, so it can be called even when only some of the three numbers are known).
- Produces: `SolarClock.warp(float realHour, float sunriseHour, float sunsetHour, float tomorrowSunriseHour)` → `float` in `[0, 24)`. Consumed by Task 10 (`SkyView`).

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/com/retro/launcher/core/SolarClockTest.java`:

```java
package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SolarClockTest {

    private static final float SUNRISE = 6.2f;
    private static final float SUNSET  = 18.4f;

    @Test public void sunriseMapsToTheDawnAnchor() {
        assertEquals(SolarClock.SUNRISE_ANCHOR,
                SolarClock.warp(6f, 6f, 18f, 6f), 0.001f);
    }

    @Test public void sunsetMapsToTheDuskAnchor() {
        assertEquals(SolarClock.SUNSET_ANCHOR,
                SolarClock.warp(18f, 6f, 18f, 6f), 0.001f);
    }

    @Test public void solarNoonMapsToTheMidpointOfTheAnchors() {
        float midpointReal = (6f + 18f) / 2f;
        float midpointAnchor = (SolarClock.SUNRISE_ANCHOR + SolarClock.SUNSET_ANCHOR) / 2f;
        assertEquals(midpointAnchor, SolarClock.warp(midpointReal, 6f, 18f, 6f), 0.01f);
    }

    @Test public void aSummerDayCompressesNightAndStaysMonotonicAcrossMidnight() {
        // Sunrise 04:00, sunset 22:00, next sunrise 04:00 — an 18h day, 6h night.
        float sunrise = 4f, sunset = 22f, tomorrowSunrise = 4f;
        float prev = SolarClock.warp(sunset, sunrise, sunset, tomorrowSunrise);
        for (float h = sunset + 0.25f; h <= 24f; h += 0.25f) {
            float warped = SolarClock.warp(h % 24f, sunrise, sunset, tomorrowSunrise);
            assertTrue("not monotonic at real hour " + h, warped >= prev);
            prev = warped;
        }
        for (float h = 0f; h < sunrise; h += 0.25f) {
            float warped = SolarClock.warp(h, sunrise, sunset, tomorrowSunrise);
            assertTrue("not monotonic at real hour " + h, warped >= prev);
            prev = warped;
        }
    }

    @Test public void identityWarpWhenSunsetIsNotAfterSunrise() {
        assertEquals(9f, SolarClock.warp(9f, 12f, 8f, 12f), 0.001f);
        assertEquals(9f, SolarClock.warp(9f, 8f, 8f, 12f), 0.001f);
    }

    @Test public void identityWarpForNaN() {
        assertEquals(9f, SolarClock.warp(9f, Float.NaN, 18f, 6f), 0.001f);
        assertEquals(9f, SolarClock.warp(9f, 6f, Float.NaN, 6f), 0.001f);
        assertEquals(9f, SolarClock.warp(9f, 6f, 18f, Float.NaN), 0.001f);
    }

    @Test public void outputIsAlwaysInZeroToTwentyFour() {
        for (float h = 0f; h < 24f; h += 0.5f) {
            float warped = SolarClock.warp(h, 6f, 18f, 6f);
            assertTrue("warp(" + h + ") = " + warped, warped >= 0f && warped < 24f);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests com.retro.launcher.core.SolarClockTest --no-daemon`
Expected: FAIL — `SolarClock` does not exist.

- [ ] **Step 3: Implement `SolarClock`**

Create `core/src/main/java/com/retro/launcher/core/SolarClock.java`:

```java
package com.retro.launcher.core;

/**
 * Maps real local time onto {@link SkyKeyframes}'s own time frame, anchored
 * on the two hours the keyframe table already draws dawn ({@code 6.2}) and
 * dusk ({@code 18.4}) at.
 *
 * <p>Day (real time in {@code [sunrise, sunset]}) maps linearly onto
 * {@code [SUNRISE_ANCHOR, SUNSET_ANCHOR]}, so every intermediate keyframe
 * lands at the same *fraction* of daylight it always occupied — equally
 * spaced across however long the day actually is at this latitude and time
 * of year. Night (real time in {@code [sunset, next sunrise]}) maps onto
 * {@code [SUNSET_ANCHOR, SUNSET_ANCHOR + 11.8]}, taken mod 24, so
 * pre-midnight and post-midnight night are one continuous segment with no
 * discontinuity at 00:00.
 *
 * <p>Degenerate input — a missing sunrise/sunset, a non-finite value, or
 * {@code sunset <= sunrise} (polar day, polar night, no location fix yet) —
 * warps as the identity, so the sky falls back to the fixed 6.2/18.4 table
 * rather than producing something undefined.
 */
public final class SolarClock {

    private SolarClock() {}

    public static final float SUNRISE_ANCHOR = 6.2f;
    public static final float SUNSET_ANCHOR  = 18.4f;

    /** Night spans {@code 24 - (SUNSET_ANCHOR - SUNRISE_ANCHOR)} anchor
     *  hours — matches {@code SkyRenderer.sunAlt}'s piecewise night span. */
    private static final float NIGHT_ANCHOR_SPAN = 24f - (SUNSET_ANCHOR - SUNRISE_ANCHOR);

    public static float warp(float realHour, float sunriseHour, float sunsetHour, float tomorrowSunriseHour) {
        float h = normalize(realHour);
        if (!valid(sunriseHour, sunsetHour, tomorrowSunriseHour)) return h;

        if (h >= sunriseHour && h <= sunsetHour) {
            float t = (h - sunriseHour) / (sunsetHour - sunriseHour);
            return SUNRISE_ANCHOR + t * (SUNSET_ANCHOR - SUNRISE_ANCHOR);
        }

        // Night: fold pre-midnight and post-midnight real time onto one
        // continuous [sunset, tomorrowSunrise + 24) window.
        float hNight = h < sunriseHour ? h + 24f : h;
        float nightEndReal = tomorrowSunriseHour <= sunsetHour
                ? tomorrowSunriseHour + 24f
                : tomorrowSunriseHour;
        float span = nightEndReal - sunsetHour;
        float t = span <= 0f ? 0f : (hNight - sunsetHour) / span;
        float warped = SUNSET_ANCHOR + clamp01(t) * NIGHT_ANCHOR_SPAN;
        return normalize(warped);
    }

    private static boolean valid(float sunrise, float sunset, float tomorrowSunrise) {
        return isFinite(sunrise) && isFinite(sunset) && isFinite(tomorrowSunrise)
                && sunset > sunrise;
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float normalize(float h) {
        if (Float.isNaN(h)) return h;
        float m = h % 24f;
        return m < 0f ? m + 24f : m;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests com.retro.launcher.core.SolarClockTest --no-daemon`
Expected: PASS, 7/7.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/SolarClock.java \
        core/src/test/java/com/retro/launcher/core/SolarClockTest.java
git commit -m "$(cat <<'EOF'
feat: add SolarClock, the real-time-to-sky-gradient-time warp

Maps real sunrise/sunset onto the sky gradient's fixed 6.2/18.4 dawn/dusk
anchors so the gradient stays evenly spaced across an actual day's length
at any latitude, falling back to the identity warp for degenerate input.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 7: `SkyRenderer.sunAlt` becomes piecewise, anchored on the same two hours

**Files:**
- Modify: `core/src/main/java/com/retro/launcher/core/SkyRenderer.java`
- Modify: `core/src/test/java/com/retro/launcher/core/SkyRendererTest.java`

**Interfaces:**
- Consumes: `SolarClock.SUNRISE_ANCHOR`, `SolarClock.SUNSET_ANCHOR` (Task 6) as the two piecewise breakpoints, so the two classes cannot drift apart.

- [ ] **Step 1: Update the existing `sunAlt` test's expectations**

The old formula was `sin((hour-6)/12*π)`, zero at 6.0 and 18.0. The new one is zero at the anchors, 6.2 and 18.4. In `core/src/test/java/com/retro/launcher/core/SkyRendererTest.java`, replace the existing test:
```java
    @Test public void sunAltitudePeaksAtNoonAndBottomsAtMidnight() {
        assertEquals(1f,  SkyRenderer.sunAlt(12f), 0.001f);
        assertEquals(0f,  SkyRenderer.sunAlt(6f),  0.001f);
        assertEquals(-1f, SkyRenderer.sunAlt(0f),  0.001f);
        assertEquals(0f,  SkyRenderer.sunAlt(18f), 0.001f);
    }
```
with:
```java
    @Test public void sunAltitudePeaksAtNoonAndBottomsAtMidnight() {
        assertEquals(1f,  SkyRenderer.sunAlt(12f), 0.001f);
        assertEquals(-1f, SkyRenderer.sunAlt(0f),  0.001f);
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
```

- [ ] **Step 2: Run test to verify the new assertions fail**

Run: `gradle :core:test --tests com.retro.launcher.core.SkyRendererTest --no-daemon`
Expected: FAIL on the new anchor-based assertions (old formula is zero at 6.0/18.0, not 6.2/18.4).

- [ ] **Step 3: Replace `sunAlt` with the piecewise version**

In `core/src/main/java/com/retro/launcher/core/SkyRenderer.java`, replace:
```java
    public static float sunAlt(float hour) {
        return (float) Math.sin((hour - 6f) / 12f * Math.PI);
    }
```
with:
```java
    /** Day span, in hours, between the sky gradient's dawn and dusk
     *  anchors — see {@link SolarClock}. */
    private static final float DAY_SPAN_HOURS = SolarClock.SUNSET_ANCHOR - SolarClock.SUNRISE_ANCHOR;
    private static final float NIGHT_SPAN_HOURS = 24f - DAY_SPAN_HOURS;

    /**
     * Sun altitude proxy: {@code 0} at both {@link SolarClock#SUNRISE_ANCHOR}
     * and {@link SolarClock#SUNSET_ANCHOR}, {@code 1} at solar noon
     * (midway between them), {@code -1} at solar midnight. Piecewise so it
     * stays continuous and consistent with the anchors {@code SolarClock}
     * warps real time onto, rather than the fixed 6/18 the table used to
     * assume.
     */
    public static float sunAlt(float hour) {
        if (hour >= SolarClock.SUNRISE_ANCHOR && hour <= SolarClock.SUNSET_ANCHOR) {
            return (float) Math.sin(Math.PI * (hour - SolarClock.SUNRISE_ANCHOR) / DAY_SPAN_HOURS);
        }
        float h = hour < SolarClock.SUNRISE_ANCHOR ? hour + 24f : hour;
        return -(float) Math.sin(Math.PI * (h - SolarClock.SUNSET_ANCHOR) / NIGHT_SPAN_HOURS);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests com.retro.launcher.core.SkyRendererTest --no-daemon`
Expected: PASS, all tests.

- [ ] **Step 5: Run the whole `:core` suite**

Run: `gradle :core:test --no-daemon`
Expected: PASS. (`renderNeverThrowsAcrossTheWholeDay`, `discsClipAtTheBufferEdgeWithoutCrashing` etc. sweep the full `[0,24]` range and exercise both branches of the new piecewise function — should still pass unchanged since they only assert determinism/no-crash/alpha, not exact `sunAlt` values.)

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/SkyRenderer.java \
        core/src/test/java/com/retro/launcher/core/SkyRendererTest.java
git commit -m "$(cat <<'EOF'
fix: make SkyRenderer.sunAlt piecewise around the sky gradient's own anchors

sunAlt was zero at a fixed 6:00/18:00, inconsistent with the keyframe
table's actual 6.2/18.4 dawn/dusk. It is now zero at exactly those anchors,
so the same real-time-to-gradient-time warp (SolarClock, Task 6) drives
both consistently.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 8: `WeatherParser` parses the `daily` sunrise/sunset block; `WeatherFetch` bundles both results

**Files:**
- Modify: `core/src/main/java/com/retro/launcher/core/WeatherParser.java`
- Modify: `core/src/test/java/com/retro/launcher/core/WeatherParserTest.java`
- Create: `core/src/main/java/com/retro/launcher/core/WeatherFetch.java`

**Interfaces:**
- Produces: `WeatherParser.parseSolarTimes(String json, java.time.LocalDate today)` → `SolarTimes` or `null`. `WeatherFetch(Weather weather, SolarTimes solarTimes)` — `solarTimes` may be `null`. Consumed by Task 9 (`OpenMeteoWeather`, `WeatherSource`).

- [ ] **Step 1: Write the failing tests**

In `core/src/test/java/com/retro/launcher/core/WeatherParserTest.java`, add (anywhere among the other tests):

```java
    // ---- daily sunrise/sunset -------------------------------------------

    private static final String RECORDED_WITH_DAILY =
            "{\"latitude\":52.52,\"longitude\":13.419,\"timezone\":\"Europe/Berlin\","
            + "\"current_weather\":{\"temperature\":13.4,\"weathercode\":3},"
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
                "{\"current_weather\":{\"temperature\":13.4,\"weathercode\":3}}",
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
```

Add the import at the top of the test file (alongside the existing `import org.junit.Test;`):
```java
import java.time.LocalDate;
```
(The fully-qualified `java.time.LocalDate` used inline above works without this import too; either is fine — keep the file consistent with whichever the implementer picks. The inline form is used above so no import edit is strictly required.)

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests com.retro.launcher.core.WeatherParserTest --no-daemon`
Expected: FAIL — `WeatherParser.parseSolarTimes` does not exist.

- [ ] **Step 3: Add `parseSolarTimes` to `WeatherParser`**

In `core/src/main/java/com/retro/launcher/core/WeatherParser.java`, add this import at the top, after `package com.retro.launcher.core;`:
```java
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
```

Add this public method right after the existing `parse(String json)` method (after its closing brace, before `objectFor`):

```java
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

    /**
     * The quoted string elements of the JSON array at {@code key} within one
     * object body, or null if the key is absent or its value is not an
     * array of bare strings.
     */
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
        if (i < body.length() && body.charAt(i) == ']') return out; // empty array

        while (i < body.length()) {
            if (body.charAt(i) != '"') return null;
            int start = ++i;
            while (i < body.length() && body.charAt(i) != '"') i++;
            if (i >= body.length()) return null; // unterminated string
            out.add(body.substring(start, i));
            i = skipSpace(body, i + 1);
            if (i >= body.length()) return null;
            if (body.charAt(i) == ',') { i = skipSpace(body, i + 1); continue; }
            if (body.charAt(i) == ']') return out;
            return null;
        }
        return null; // never closed
    }

    /** Open-Meteo's ISO local datetime, e.g. {@code "2026-08-28T06:12"}, as
     *  a decimal hour — or null if it does not parse. */
    private static Float hourOfDay(String isoLocalDateTime) {
        try {
            LocalDateTime dt = LocalDateTime.parse(isoLocalDateTime);
            return dt.getHour() + dt.getMinute() / 60f;
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }
```

- [ ] **Step 4: Create `WeatherFetch`**

Create `core/src/main/java/com/retro/launcher/core/WeatherFetch.java`:

```java
package com.retro.launcher.core;

/**
 * The two things one Open-Meteo request can yield: the current reading, and
 * — since {@code &daily=sunrise,sunset} rides along on the same GET — the
 * day's solar times. {@code solarTimes} is null whenever the {@code daily}
 * block was absent or unparseable; {@code weather} follows the same
 * null-on-failure contract {@link WeatherParser#parse} always had.
 */
public final class WeatherFetch {

    public final Weather weather;
    public final SolarTimes solarTimes;

    public WeatherFetch(Weather weather, SolarTimes solarTimes) {
        this.weather = weather;
        this.solarTimes = solarTimes;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `gradle :core:test --tests com.retro.launcher.core.WeatherParserTest --no-daemon`
Expected: PASS, all tests including the 4 new ones.

Run: `gradle :core:test --no-daemon`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/WeatherParser.java \
        core/src/main/java/com/retro/launcher/core/WeatherFetch.java \
        core/src/test/java/com/retro/launcher/core/WeatherParserTest.java
git commit -m "$(cat <<'EOF'
feat: parse Open-Meteo's daily sunrise/sunset block into SolarTimes

Adds WeatherParser.parseSolarTimes and a WeatherFetch holder bundling a
Weather reading with its SolarTimes from the same HTTP response, so
OpenMeteoWeather (Task 9) needs only the one GET the spec calls for.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 9: `OpenMeteoWeather` fetches sunrise/sunset too; `WeatherSource`/`WeatherRepository` carry `SolarTimes`

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/data/WeatherSource.java`
- Modify: `app/src/main/java/com/retro/launcher/data/OpenMeteoWeather.java`
- Modify: `app/src/main/java/com/retro/launcher/data/WeatherRepository.java`
- Modify: `app/src/main/java/com/retro/launcher/data/Prefs.java`

Android glue — not unit-tested, verified by build and (where practical) on-device, same standard as the existing `LocationSource`/`WeatherRepository`.

**Interfaces:**
- Consumes: `WeatherFetch`, `SolarTimes`, `WeatherParser.parseSolarTimes`, `SolarMath.sunTimes` (Tasks 5, 8).
- Produces: `WeatherRepository.solarTimes()` → `SolarTimes` or `null`. Consumed by Task 11 (`HomeActivity`).

- [ ] **Step 1: Widen `WeatherSource.fetch` to return a `WeatherFetch`**

Replace the full contents of `app/src/main/java/com/retro/launcher/data/WeatherSource.java`:

```java
package com.retro.launcher.data;

import com.retro.launcher.core.WeatherFetch;

/**
 * Where a real weather reading — and the day's solar times, riding along on
 * the same request — comes from. The seam spec §3.6 reserved at Tier 1 so
 * the network implementation could land without touching a caller.
 */
public interface WeatherSource {

    /**
     * Fetches one reading. Blocking — callers run it off the main thread.
     *
     * @return the reading and solar times, or null for any failure at all:
     *         no network, a non-200 reply, a timeout, a body we cannot
     *         parse. {@code WeatherFetch.solarTimes} may itself be null even
     *         on success — failure there is independently normal. Failure is
     *         never an exception.
     */
    WeatherFetch fetch(double latitude, double longitude);
}
```

- [ ] **Step 2: Update `OpenMeteoWeather`**

Replace the full contents of `app/src/main/java/com/retro/launcher/data/OpenMeteoWeather.java`:

```java
package com.retro.launcher.data;

import android.util.Log;

import com.retro.launcher.core.WeatherFetch;
import com.retro.launcher.core.WeatherParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/**
 * One HTTPS GET to api.open-meteo.com — no key, no account, no SDK. Also
 * requests today's and tomorrow's sunrise/sunset on the same GET, since
 * {@link SolarClock}'s night warp needs tomorrow's sunrise.
 *
 * Deliberately minimal: one attempt, short timeouts, a bounded read, and null
 * for every failure. {@link WeatherRepository} owns the decision of *when* to
 * call this; the only policy here is "give up quickly".
 */
public final class OpenMeteoWeather implements WeatherSource {

    private static final String TAG = "Weather";
    private static final String ENDPOINT = "https://api.open-meteo.com/v1/forecast";

    private static final int TIMEOUT_MS = 5_000;

    /** The real body is a few hundred bytes even with the daily block.
     *  Anything wildly larger is not our JSON, and reading it unbounded
     *  would be a memory hazard on a hostile network. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    @Override public WeatherFetch fetch(double latitude, double longitude) {
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(ENDPOINT
                    + "?latitude=" + coord(latitude)
                    + "&longitude=" + coord(longitude)
                    + "&current_weather=true"
                    + "&daily=sunrise,sunset&timezone=auto&forecast_days=2");

            conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setUseCaches(false);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "open-meteo returned HTTP " + code);
                return null;
            }
            String body = read(conn.getInputStream());
            com.retro.launcher.core.Weather weather = WeatherParser.parse(body);
            if (weather == null) return null;

            // The solar block is a bonus: its absence or malformation is not
            // a fetch failure, only a missing WeatherFetch.solarTimes.
            com.retro.launcher.core.SolarTimes solarTimes =
                    WeatherParser.parseSolarTimes(body, LocalDate.now());
            return new WeatherFetch(weather, solarTimes);

        } catch (Exception e) {
            // IOException, SSL failures, a malformed URL, a SecurityException
            // from a restricted profile — all the same outcome: no update.
            Log.d(TAG, "fetch failed: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Four decimal places — about 11m.
     *
     * Three (~110m) was coarse enough to move a fix to the next
     * neighbourhood, which is visible on a coastline or in a valley. Still far
     * more precision than we send anywhere else, and the API's own resolution
     * is much coarser than either. Locale.US because a device set to a
     * comma-decimal locale would otherwise send "52,52" and get a 400 back.
     */
    private static String coord(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        byte[] chunk = new byte[2048];
        int n;
        while ((n = in.read(chunk)) > 0) {
            out.write(chunk, 0, n);
            if (out.size() > MAX_BODY_BYTES) {
                Log.d(TAG, "response exceeded " + MAX_BODY_BYTES + " bytes; discarding");
                return null;
            }
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
```

- [ ] **Step 3: Add solar-time cache keys to `Prefs`**

In `app/src/main/java/com/retro/launcher/data/Prefs.java`, after the existing `K_WX_LON` line, add:
```java
    // Tier 6. Today's solar times, cached by epoch day so a SolarMath
    // recomputation or a cheap Prefs read both cost nothing on the common
    // "already have today's" path.
    public static final String K_SOL_EPOCH_DAY = "solEpochDay";
    public static final String K_SOL_SUNRISE   = "solSunrise";
    public static final String K_SOL_SUNSET    = "solSunset";
    public static final String K_SOL_TOMORROW  = "solTomorrowSunrise";
```
No new getters needed — the generic `getLong`/`getFloat`/`putLong`/`putFloat` already on `Prefs` (bottom of the class) cover these.

- [ ] **Step 4: Wire solar-time fetch, cache and fallback into `WeatherRepository`**

In `app/src/main/java/com/retro/launcher/data/WeatherRepository.java`, add these imports after the existing ones:
```java
import com.retro.launcher.core.SolarMath;
import com.retro.launcher.core.SolarTimes;
import com.retro.launcher.core.WeatherFetch;

import java.time.LocalDate;
import java.time.ZoneId;
```

Change the field `private Weather reading;` block to also track the fetched solar times, and change every use of `WeatherSource.fetch`'s return type. Replace the `refresh` method body's fetch callback — find:
```java
            final double lat = fix[0], lon = fix[1];
            new Thread(() -> {
                final Weather fetched = source.fetch(lat, lon);
                main.post(() -> {
                    inFlight.set(false);
                    if (fetched == null) return;   // silent; the last good value stands
                    reading = fetched;
                    readingAt = System.currentTimeMillis();
                    persist();
                    if (onUpdated != null) onUpdated.run();
                });
            }, "weather-fetch").start();
```
and replace with:
```java
            final double lat = fix[0], lon = fix[1];
            new Thread(() -> {
                final WeatherFetch fetched = source.fetch(lat, lon);
                main.post(() -> {
                    inFlight.set(false);
                    if (fetched == null || fetched.weather == null) return;   // silent; the last good value stands
                    reading = fetched.weather;
                    readingAt = System.currentTimeMillis();
                    persist();
                    if (fetched.solarTimes != null) persistSolarTimes(fetched.solarTimes);
                    if (onUpdated != null) onUpdated.run();
                });
            }, "weather-fetch").start();
```

Then add these methods right after the existing `fix()` method (before the `// ---- last good value, across restarts` comment):

```java
    /**
     * Today's sunrise, sunset and tomorrow's sunrise. Cache first (today's
     * entry, if present); otherwise a local {@link SolarMath} computation
     * from the current fix, cached for the rest of the day; otherwise null,
     * which is exactly the signal {@link com.retro.launcher.core.SolarClock}
     * treats as "no data — draw the fixed table". Safe to call from the main
     * thread: the cache read is a SharedPreferences read, and SolarMath is
     * pure local arithmetic, not a network call.
     */
    public SolarTimes solarTimes() {
        LocalDate today = LocalDate.now();
        SolarTimes cached = restoreSolarTimes(today);
        if (cached != null) return cached;

        double[] f = fix();
        if (f == null) return null;

        SolarTimes computed = SolarMath.sunTimes((float) f[0], (float) f[1], today, ZoneId.systemDefault());
        if (computed != null) persistSolarTimes(computed);
        return computed;
    }

    private void persistSolarTimes(SolarTimes t) {
        prefs.putLong(Prefs.K_SOL_EPOCH_DAY, t.date.toEpochDay());
        prefs.putFloat(Prefs.K_SOL_SUNRISE, t.sunriseHour);
        prefs.putFloat(Prefs.K_SOL_SUNSET, t.sunsetHour);
        prefs.putFloat(Prefs.K_SOL_TOMORROW, t.tomorrowSunriseHour);
    }

    private SolarTimes restoreSolarTimes(LocalDate today) {
        long storedEpochDay = prefs.getLong(Prefs.K_SOL_EPOCH_DAY, Long.MIN_VALUE);
        if (storedEpochDay != today.toEpochDay()) return null; // missing, or a stale past date
        return new SolarTimes(
                prefs.getFloat(Prefs.K_SOL_SUNRISE, Float.NaN),
                prefs.getFloat(Prefs.K_SOL_SUNSET, Float.NaN),
                prefs.getFloat(Prefs.K_SOL_TOMORROW, Float.NaN),
                today);
    }
```

- [ ] **Step 5: Update the test-seam constructor's Javadoc reference (no signature change)**

No change needed to `WeatherRepository`'s two constructors — `WeatherSource` is still the type of the `source` field, only its `fetch` return type changed, which is already handled by Step 4's edit.

- [ ] **Step 6: Build to confirm everything compiles**

Run: `gradle assembleDebug --no-daemon`
Expected: PASS. (This module has no `app`-side unit tests; the existing `:core` suite is unaffected by this task.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/retro/launcher/data/WeatherSource.java \
        app/src/main/java/com/retro/launcher/data/OpenMeteoWeather.java \
        app/src/main/java/com/retro/launcher/data/WeatherRepository.java \
        app/src/main/java/com/retro/launcher/data/Prefs.java
git commit -m "$(cat <<'EOF'
feat: fetch and cache solar times alongside the weather reading

OpenMeteoWeather now requests &daily=sunrise,sunset&forecast_days=2 on its
existing GET; WeatherRepository.solarTimes() serves the day's cached entry,
falling back to an offline SolarMath computation from the last known fix
when the network has not answered — never a second network round trip.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 10: `SkyView` warps real time through `SolarClock` before rendering

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/sky/SkyView.java`

Android glue — verified by build and on-device.

**Interfaces:**
- Consumes: `SolarClock.warp` (Task 6), `SolarTimes` (Task 5).
- Produces: `SkyView.setLocation(float lat, float lon)` replacing `setLatitude(float)`; `SkyView.setSolarTimes(SolarTimes)`. Consumed by Task 11 (`HomeActivity`).

- [ ] **Step 1: Replace `setLatitude` with `setLocation`, add `setSolarTimes`, and warp `drawFrame`'s hour**

In `app/src/main/java/com/retro/launcher/sky/SkyView.java`, add these imports after the existing ones:
```java
import com.retro.launcher.core.SolarClock;
import com.retro.launcher.core.SolarTimes;
```

Replace the field:
```java
    private volatile float latitude = Float.NaN;   // no fix yet
```
with:
```java
    private volatile float latitude = Float.NaN;   // no fix yet
    private volatile float longitude = Float.NaN;  // no fix yet
    private volatile SolarTimes solarTimes;         // null until known
```

Replace the method:
```java
    /** The latitude of the coarse fix the weather already keeps, which is all
     *  the moon needs: it decides which way up the phase is drawn.
     *  {@code Float.NaN} means "no fix" and reads as the northern view. */
    public void setLatitude(float degrees) { this.latitude = degrees; }
```
with:
```java
    /**
     * The coarse fix the weather already keeps. Latitude decides which way
     * up the moon's phase is drawn ({@code Float.NaN} means "no fix" and
     * reads as the northern view); both feed {@link SolarClock}'s time warp
     * once {@link #setSolarTimes} has a value.
     */
    public void setLocation(float latitudeDegrees, float longitudeDegrees) {
        this.latitude = latitudeDegrees;
        this.longitude = longitudeDegrees;
    }

    /** Today's sunrise/sunset, or null when none is known yet — the sky then
     *  draws against the fixed 6.2/18.4 table instead of a warped one. */
    public void setSolarTimes(SolarTimes times) { this.solarTimes = times; }
```

Replace the body of `drawFrame()`:
```java
        float hour = decimalHour();
        float seconds = (System.nanoTime() - startNanos) / 1_000_000_000f;
        // Seven sines a frame against a 108xN pixel loop — not worth caching,
        // and recomputing means the terminator creeps in real time.
        float moonPhase = MoonPhase.phase(System.currentTimeMillis());
        r.render(buf, hour, weather, moonPhase, seconds);
```
with:
```java
        float realHour = decimalHour();
        SolarTimes times = solarTimes;
        float hour = times == null
                ? realHour
                : SolarClock.warp(realHour, times.sunriseHour, times.sunsetHour, times.tomorrowSunriseHour);
        float seconds = (System.nanoTime() - startNanos) / 1_000_000_000f;
        // Seven sines a frame against a 108xN pixel loop — not worth caching,
        // and recomputing means the terminator creeps in real time.
        float moonPhase = MoonPhase.phase(System.currentTimeMillis());
        r.render(buf, hour, weather, moonPhase, seconds);
```

- [ ] **Step 2: Build to confirm it compiles**

Run: `gradle assembleDebug --no-daemon`
Expected: FAIL until Task 11 updates `HomeActivity`'s call site — that is expected and resolved in the next task. If working through this plan strictly in order, proceed to Task 11 before attempting a standalone build of this task alone.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/retro/launcher/sky/SkyView.java
git commit -m "$(cat <<'EOF'
feat: warp SkyView's real time through SolarClock before rendering

setLatitude becomes setLocation(lat, lon) — the warp needs longitude too —
and drawFrame now passes SolarClock.warp(realHour, ...) to the renderer
instead of the raw wall-clock hour, once solar times are known.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 11: `HomeActivity` pushes location and solar times into `SkyView`

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/HomeActivity.java`

Android glue — verified by build and on-device.

**Interfaces:**
- Consumes: `SkyView.setLocation`, `SkyView.setSolarTimes` (Task 10); `WeatherRepository.solarTimes()` (Task 9).

- [ ] **Step 1: Rewrite `refreshSkyLocation` to push both location and solar times**

In `app/src/main/java/com/retro/launcher/HomeActivity.java`, replace:
```java
    /** The moon's phase is the same everywhere; which way up it looks is not.
     *  Latitude comes from the coarse fix the weather already keeps. */
    private void refreshSkyLocation() {
        double[] fix = weatherRepository.fix();
        sky.setLatitude(fix == null ? Float.NaN : (float) fix[0]);
    }
```
with:
```java
    /** The moon's phase is the same everywhere; which way up it looks, and
     *  what real time maps onto the sky gradient, are not. Both come from
     *  the coarse fix and solar times the weather repository already keeps. */
    private void refreshSkyLocation() {
        double[] fix = weatherRepository.fix();
        sky.setLocation(fix == null ? Float.NaN : (float) fix[0],
                         fix == null ? Float.NaN : (float) fix[1]);
        sky.setSolarTimes(weatherRepository.solarTimes());
    }
```

- [ ] **Step 2: Confirm `refreshSkyLocation` is already called on the right cadence**

Run: `grep -n "refreshSkyLocation" app/src/main/java/com/retro/launcher/HomeActivity.java`

This should already be wired to the same tick/refresh path `refreshTime()` runs on from V7 (a periodic `Handler` tick and `onResume()`). No further change needed — `weatherRepository.solarTimes()` is cheap (SharedPreferences read, or local math on a cache miss) and safe to call on that same cadence. If the grep shows it is *not* called anywhere near `refreshTime()`, add a call to `refreshSkyLocation()` immediately after the existing `refreshTime()` call in whichever method drives the periodic tick.

- [ ] **Step 3: Build**

Run: `gradle assembleDebug --no-daemon`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/retro/launcher/HomeActivity.java
git commit -m "$(cat <<'EOF'
feat: push location and solar times into SkyView

refreshSkyLocation now calls the widened SkyView.setLocation(lat, lon) and
the new setSolarTimes(...), sourced from WeatherRepository.fix()/solarTimes()
— completing the wiring from Tasks 5-10 through to the rendered sky.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 12: `LockRoute` gains the `SHIZUKU` case

**Files:**
- Modify: `core/src/main/java/com/retro/launcher/core/LockRoute.java`
- Modify: `core/src/test/java/com/retro/launcher/core/LockRouteTest.java`

**Interfaces:**
- Produces: `LockRoute.choose(boolean shizuku, boolean accessibility, boolean admin)` — replaces the two-argument overload. Consumed by Task 14 (`HomeActivity`).

- [ ] **Step 1: Rewrite the test for the three-argument `choose`**

Replace the full contents of `core/src/test/java/com/retro/launcher/core/LockRouteTest.java`:

```java
package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class LockRouteTest {

    /** Shizuku avoids both the accessibility service (flagged by some
     *  banking apps) and the device admin (costs the fingerprint), so it
     *  outranks both when available. */
    @Test public void shizukuIsPreferredWhenAllThreeAreAvailable() {
        assertEquals(LockRoute.SHIZUKU, LockRoute.choose(true, true, true));
    }

    @Test public void shizukuIsPreferredOverAccessibilityAlone() {
        assertEquals(LockRoute.SHIZUKU, LockRoute.choose(true, true, false));
    }

    @Test public void shizukuIsPreferredOverAdminAlone() {
        assertEquals(LockRoute.SHIZUKU, LockRoute.choose(true, false, true));
    }

    @Test public void shizukuAloneIsChosen() {
        assertEquals(LockRoute.SHIZUKU, LockRoute.choose(true, false, false));
    }

    /** The bug the two-route version existed to prevent, still true without
     *  Shizuku: the device admin locks through the framework, which forces
     *  the next unlock to PIN and refuses the fingerprint. */
    @Test public void prefersAccessibilityWhenBothRoutesAreAvailableAndShizukuIsNot() {
        assertEquals(LockRoute.ACCESSIBILITY, LockRoute.choose(false, true, true));
    }

    @Test public void fallsBackToTheAdminWhenNeitherShizukuNorAccessibilityIsOn() {
        assertEquals(LockRoute.ADMIN, LockRoute.choose(false, false, true));
    }

    @Test public void usesAccessibilityWhenTheAdminIsNotActive() {
        assertEquals(LockRoute.ACCESSIBILITY, LockRoute.choose(false, true, false));
    }

    @Test public void reportsNoneWhenNoneIsSetUp() {
        assertEquals(LockRoute.NONE, LockRoute.choose(false, false, false));
    }

    @Test public void statusWordsSeparateTheThreeWorkingRoutes() {
        assertEquals("ON",       LockRoute.SHIZUKU.status());
        assertEquals("ON",       LockRoute.ACCESSIBILITY.status());
        assertEquals("PIN ONLY", LockRoute.ADMIN.status());
        assertEquals("ENABLE",   LockRoute.NONE.status());
    }

    /** Shizuku costs nothing extra to unlock again, same as accessibility —
     *  only the admin route is unsettled business. */
    @Test public void shizukuAndAccessibilityAreBothSettled() {
        assertTrue(LockRoute.SHIZUKU.settled());
        assertTrue(LockRoute.ACCESSIBILITY.settled());
        assertFalse(LockRoute.ADMIN.settled());
        assertFalse(LockRoute.NONE.settled());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests com.retro.launcher.core.LockRouteTest --no-daemon`
Expected: FAIL — `LockRoute.SHIZUKU` and the three-argument `choose` do not exist.

- [ ] **Step 3: Rewrite `LockRoute`**

Replace the full contents of `core/src/main/java/com/retro/launcher/core/LockRoute.java`:

```java
package com.retro.launcher.core;

/**
 * Which of the three ways of locking the screen the launcher should use, and
 * what the DEVICE LOCK / SHIZUKU LOCK rows in Settings should say about it.
 *
 * <p>The choice is not cosmetic, which is the whole reason this lives in
 * {@code core} with a test around it. Locking through the device admin —
 * {@code DevicePolicyManager#lockNow()} — makes the framework raise the
 * "strong auth required after DPM lock" flag on the user, and while that flag
 * is set Android refuses every biometric: the next unlock has to be the PIN,
 * pattern or password. There is no flag or policy that relaxes it; it is what
 * a device-admin lock means. The accessibility global action
 * ({@code GLOBAL_ACTION_LOCK_SCREEN}, API 28+) is the same lock the power
 * button performs and leaves biometrics untouched, so the fingerprint still
 * opens the phone — and Shizuku's {@code input keyevent 26} is, literally,
 * the power button, with the same result.
 *
 * <p>Shizuku ranks first when available: it is the only route that needs
 * neither an active accessibility service (flagged by some banking apps as
 * suspicious on a non-system app) nor the device admin's fingerprint cost.
 * Accessibility is the fallback for anyone who has not paired Shizuku (it
 * must be re-paired after every reboot on an unrooted device), and the admin
 * route survives only as the last resort for devices that cannot offer
 * either of the better two, or users who have not switched one on yet.
 */
public enum LockRoute {

    /** A Shizuku (ADB-shell) session running {@code input keyevent 26}.
     *  Locks; fingerprint still unlocks; needs no accessibility service. */
    SHIZUKU,

    /** Accessibility global action. Locks; fingerprint still unlocks. */
    ACCESSIBILITY,

    /** Device admin. Locks; Android then demands the PIN on the next unlock. */
    ADMIN,

    /** None of the three is set up — the gesture has nothing to call. */
    NONE;

    /**
     * @param shizuku       the Shizuku toggle is on and a permitted session
     *                      is currently reachable
     * @param accessibility the service is connected and the platform is new
     *                      enough for {@code GLOBAL_ACTION_LOCK_SCREEN}
     * @param admin         the device admin is active and holds force-lock
     */
    public static LockRoute choose(boolean shizuku, boolean accessibility, boolean admin) {
        if (shizuku) return SHIZUKU;
        if (accessibility) return ACCESSIBILITY;
        if (admin) return ADMIN;
        return NONE;
    }

    /**
     * Whether there is nothing left for the user to do — the state the
     * DEVICE LOCK row draws as done and inert.
     *
     * <p>{@link #ADMIN} deliberately does not count. It locks, so it is
     * tempting to call it finished, but it costs the fingerprint, and drawing
     * it as settled would leave everybody already on it with no way to find
     * out there is a better route.
     */
    public boolean settled() {
        return this == SHIZUKU || this == ACCESSIBILITY;
    }

    /** Status word for the DEVICE LOCK row. */
    public String status() {
        switch (this) {
            case SHIZUKU:
            case ACCESSIBILITY: return "ON";
            case ADMIN:         return "PIN ONLY";
            default:            return "ENABLE";
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests com.retro.launcher.core.LockRouteTest --no-daemon`
Expected: PASS, 9/9.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/LockRoute.java \
        core/src/test/java/com/retro/launcher/core/LockRouteTest.java
git commit -m "$(cat <<'EOF'
feat: add LockRoute.SHIZUKU, ranked above accessibility

Extends choose() to a third boolean. Shizuku is preferred whenever
available: it is the only route needing neither an accessibility service
(flagged by some banking apps) nor the device admin's fingerprint cost.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 13: Add the Shizuku dependency and `ShizukuLock`

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/main/java/com/retro/launcher/lock/ShizukuLock.java`
- Modify: `app/src/main/AndroidManifest.xml`

Android/third-party glue — verified on-device, per the spec's own testing note: "`ShizukuLock` is Android glue over an external service and is verified on-device, not by unit test — the same standard the existing accessibility route is held to."

**Interfaces:**
- Produces: `ShizukuLock.isAvailable(boolean toggleOn)`, `ShizukuLock.hasPermission()`, `ShizukuLock.requestPermission(int requestCode)`, `ShizukuLock.lock()`. Consumed by Task 14.

- [ ] **Step 1: Add the dependency**

In `app/build.gradle`, replace:
```groovy
dependencies {
    implementation project(':core')
}
```
with:
```groovy
dependencies {
    implementation project(':core')

    // The project's first external dependency. Contained entirely to
    // ShizukuLock.java, the one file in the app that imports Shizuku types —
    // see the V8 design spec item 5.
    implementation 'dev.rikka.shizuku:api:13.1.5'
    implementation 'dev.rikka.shizuku:provider:13.1.5'
}
```

- [ ] **Step 2: Register the `ShizukuProvider` in the manifest**

Read `app/src/main/AndroidManifest.xml` first to find the existing `<application>` tag's closing structure and current `authorities`/provider entries (there may be none yet). Inside `<application>...</application>`, add:
```xml
        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:multiprocess="false"
            android:enabled="true"
            android:exported="true"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

- [ ] **Step 3: Write `ShizukuLock`**

Create `app/src/main/java/com/retro/launcher/lock/ShizukuLock.java`:

```java
package com.retro.launcher.lock;

import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

/**
 * The only file in the app that imports a Shizuku type — see the V8 design
 * spec item 5. Every method swallows every {@code Throwable}, not just
 * {@code RuntimeException}: if the AAR is somehow absent at runtime a call
 * into it throws {@code NoClassDefFoundError}, which is not a
 * {@code RuntimeException}, and that has to read as "unavailable" exactly
 * like every other failure here, not crash the launcher.
 */
public final class ShizukuLock {

    private ShizukuLock() {}

    /**
     * @param toggleOn the Settings "SHIZUKU LOCK" preference
     * @return true only when the toggle is on, the Shizuku app's binder is
     *         reachable, and this app currently holds its permission
     */
    public static boolean isAvailable(boolean toggleOn) {
        return toggleOn && hasPermission();
    }

    /** Permission state alone, ignoring the Settings toggle — what the
     *  SHIZUKU LOCK row's status text needs to distinguish "not permitted
     *  yet" from "not enabled at all". */
    public static boolean hasPermission() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether the Shizuku app's binder is reachable at all, regardless of
     *  whether this app has been granted permission yet. */
    public static boolean isServiceRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** For the Settings row's fix action. A no-op if the service is not
     *  running or permission is already granted. */
    public static void requestPermission(int requestCode) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Locks the screen via a shell {@code input keyevent 26} — literally the
     * power button. Returns false on any failure so the caller can fall
     * through to the next {@link com.retro.launcher.core.LockRoute}.
     */
    public static boolean lock() {
        if (!hasPermission()) return false;
        try {
            Process process = Shizuku.newProcess(new String[]{"input", "keyevent", "26"}, null, null);
            return process.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Build**

Run: `gradle assembleDebug --no-daemon`
Expected: PASS. (Compiles against the Shizuku AAR; actual lock behavior needs a paired Shizuku session on-device — nothing in this step exercises it.)

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle app/src/main/AndroidManifest.xml \
        app/src/main/java/com/retro/launcher/lock/ShizukuLock.java
git commit -m "$(cat <<'EOF'
feat: add dev.rikka.shizuku dependency and the ShizukuLock wrapper

The project's first external dependency, contained entirely to this one
file. lock() runs the Shizuku-shell equivalent of the power button; every
method fails to "unavailable" rather than throwing, including for a
missing AAR.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 14: Wire Shizuku into `HomeActivity`'s lock routing

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/data/Prefs.java`
- Modify: `app/src/main/java/com/retro/launcher/HomeActivity.java`

Android glue — verified on-device.

**Interfaces:**
- Consumes: `ShizukuLock.isAvailable/hasPermission/requestPermission/lock` (Task 13), `LockRoute.choose(shizuku, accessibility, admin)` (Task 12), `SettingsPanel.PermissionActionListener` and `setShizukuLockStatus` (Task 15 — this task adds the interface method and its `HomeActivity` implementation; Task 15 adds the row that calls it).

- [ ] **Step 1: Add the Shizuku toggle key to `Prefs`**

In `app/src/main/java/com/retro/launcher/data/Prefs.java`, after `K_HAPTIC`, add:
```java
    public static final String K_SHIZUKU = "shizukuLock";
```
And after the `haptics()` getter, add:
```java
    /** Default off: a second app to install and a pairing that must be
     *  redone after every reboot is a real cost, so the user opts in. */
    public boolean shizukuLockEnabled() { return sp.getBoolean(K_SHIZUKU, false); }
```

- [ ] **Step 2: Update `HomeActivity`'s `lockRoute()` and `lockDevice()`**

In `app/src/main/java/com/retro/launcher/HomeActivity.java`, add this import alongside the existing ones:
```java
import com.retro.launcher.lock.ShizukuLock;
```

Add a request-code constant next to `REQ_LOCATION`:
```java
    private static final int REQ_LOCATION = 1;
    private static final int REQ_SHIZUKU = 2;
```

Replace:
```java
    /** Which of the two lock routes is available right now — see
     *  {@link LockRoute} for why the order matters. */
    private LockRoute lockRoute() {
        return LockRoute.choose(ShadeService.canLockScreen(this), canLockViaAdmin());
    }
```
with:
```java
    /** Which of the three lock routes is available right now — see
     *  {@link LockRoute} for why the order matters. */
    private LockRoute lockRoute() {
        return LockRoute.choose(
                ShizukuLock.isAvailable(prefs.shizukuLockEnabled()),
                ShadeService.canLockScreen(this),
                canLockViaAdmin());
    }
```

Replace the body of `lockDevice()`:
```java
    private void lockDevice() {
        if (ShadeService.lockScreen()) return;
        if (canLockViaAdmin()) {
```
with:
```java
    private void lockDevice() {
        if (ShizukuLock.isAvailable(prefs.shizukuLockEnabled()) && ShizukuLock.lock()) return;
        if (ShadeService.lockScreen()) return;
        if (canLockViaAdmin()) {
```
(leave the rest of the method body — the `try { dpm.lockNow(); ... }` block and `requestLockCapability()` fallback — unchanged).

- [ ] **Step 3: Add the Shizuku enable/request action and wire it into `refreshPermissionStatus`**

Add this method near `requestLockCapability()`:
```java
    /** The SHIZUKU LOCK row's fix action: turn the toggle on (if it was
     *  off) and (re-)request permission — this also covers "paired before,
     *  needs re-pairing after a reboot", which looks identical to Shizuku's
     *  API as "not permitted yet". */
    private void enableShizukuLock() {
        if (!prefs.shizukuLockEnabled()) prefs.putBool(Prefs.K_SHIZUKU, true);
        ShizukuLock.requestPermission(REQ_SHIZUKU);
        refreshPermissionStatus();
    }
```

In `refreshPermissionStatus()`, add a line after the existing `settings.setNotificationShadeStatus(ShadeService.isEnabled(this));`:
```java
        settings.setShizukuLockStatus(prefs.shizukuLockEnabled(), ShizukuLock.hasPermission());
```

- [ ] **Step 4: Implement the new `PermissionActionListener` method**

In `HomeActivity.java` around line 149, find:
```java
        settings.setPermissionActionListener(new SettingsPanel.PermissionActionListener() {
            @Override public void onRequestLocation() { requestLocation(); }
            @Override public void onOpenUsageAccessSettings() { openUsageAccessSettings(); }
            @Override public void onEnableDeviceLock() { requestLockCapability(); }
            @Override public void onSetDefaultLauncher() { requestDefaultLauncher(); }
            @Override public void onEnableNotificationShade() { openAccessibilitySettings(); }
        });
```
and replace it with:
```java
        settings.setPermissionActionListener(new SettingsPanel.PermissionActionListener() {
            @Override public void onRequestLocation() { requestLocation(); }
            @Override public void onOpenUsageAccessSettings() { openUsageAccessSettings(); }
            @Override public void onEnableDeviceLock() { requestLockCapability(); }
            @Override public void onSetDefaultLauncher() { requestDefaultLauncher(); }
            @Override public void onEnableNotificationShade() { openAccessibilitySettings(); }
            @Override public void onEnableShizukuLock() { enableShizukuLock(); }
        });
```
(This compiles only once Task 15 adds `onEnableShizukuLock()` to the `PermissionActionListener` interface — if implementing Task 14 before Task 15, this step will not compile in isolation; that is expected, matching the file-splitting pattern used in Tasks 10-11.)

- [ ] **Step 5: Build**

Run: `gradle assembleDebug --no-daemon`
Expected: FAILs until Task 15 adds the interface method; proceed to Task 15 before verifying this task's build in isolation.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/retro/launcher/data/Prefs.java \
        app/src/main/java/com/retro/launcher/HomeActivity.java
git commit -m "$(cat <<'EOF'
feat: route long-press-home-to-lock through Shizuku first

lockRoute() and lockDevice() both try ShizukuLock before the accessibility
and device-admin fallbacks, gated by a Prefs toggle that defaults off.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 15: The SHIZUKU LOCK row in Settings

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/ui/SettingsPanel.java`

Android UI — verified by build; visually confirm on-device or emulator if available (a Settings panel row, following the exact pattern of the existing DEVICE LOCK/NOTIFICATION SHADE rows).

**Interfaces:**
- Produces: `SettingsPanel.PermissionActionListener.onEnableShizukuLock()`; `SettingsPanel.setShizukuLockStatus(boolean enabled, boolean permitted)`. Completes Task 14's `HomeActivity` wiring.

- [ ] **Step 1: Add the interface method**

In `app/src/main/java/com/retro/launcher/ui/SettingsPanel.java`, replace:
```java
    public interface PermissionActionListener {
        void onRequestLocation();
        void onOpenUsageAccessSettings();
        void onEnableDeviceLock();
        void onSetDefaultLauncher();
        void onEnableNotificationShade();
    }
```
with:
```java
    public interface PermissionActionListener {
        void onRequestLocation();
        void onOpenUsageAccessSettings();
        void onEnableDeviceLock();
        void onSetDefaultLauncher();
        void onEnableNotificationShade();
        void onEnableShizukuLock();
    }
```

- [ ] **Step 2: Add the status fields and setter**

Replace:
```java
    private boolean isDefaultLauncher;
    private boolean shadeServiceEnabled;
```
with:
```java
    private boolean isDefaultLauncher;
    private boolean shadeServiceEnabled;
    private boolean shizukuLockEnabled;
    private boolean shizukuLockPermitted;
```

Add this setter right after the existing `setNotificationShadeStatus`:
```java
    /** V8 design spec item 5: whether the SHIZUKU LOCK toggle is on, and
     *  whether a permitted session is currently reachable — two different
     *  things, since the toggle survives a reboot but the pairing does not. */
    public void setShizukuLockStatus(boolean enabled, boolean permitted) {
        this.shizukuLockEnabled = enabled;
        this.shizukuLockPermitted = permitted;
        rebuildPermissionsSection();
    }
```

- [ ] **Step 3: Add the row**

In `rebuildPermissionsSection()`, right after the `shadeRow` block (`permSection.addView(shadeRow);`) and before the `defaultRow` block, add:
```java
        // Three states again, same shape as DEVICE LOCK: off (never opted
        // in), on-but-not-permitted (opted in, needs (re-)pairing — the
        // normal state after a reboot on an unrooted device), and on.
        boolean shizukuGranted = shizukuLockEnabled && shizukuLockPermitted;
        String shizukuText = !shizukuLockEnabled ? "ENABLE" : "GRANT";
        View shizukuRow = permissionRow("SHIZUKU LOCK", shizukuGranted, "ON", shizukuText,
                () -> { if (permissionListener != null) permissionListener.onEnableShizukuLock(); });
        addTopMargin(shizukuRow, gap);
        permSection.addView(shizukuRow);
```

- [ ] **Step 4: Extend the caption with the Shizuku caveat**

Replace the caption block's text (inside `rebuildPermissionsSection()`):
```java
        caption.setText("WEATHER NEEDS PRECISE LOCATION · SCREEN TIME NEEDS USAGE ACCESS. "
                + "THE LAUNCHER WORKS WITHOUT EITHER.\n\n"
                + "LONG-PRESS THE HOME SCREEN TO LOCK, ONCE DEVICE LOCK IS ON.\n\n"
                + "DEVICE LOCK AND NOTIFICATION SHADE BOTH RUN OFF THE SAME ONE SWITCH: "
                + "RETRO LAUNCHER UNDER ACCESSIBILITY. IT READS NOTHING; IT ONLY LOCKS THE "
                + "SCREEN AND OPENS THE SHADE.\n\n"
                + "PIN ONLY MEANS LOCKING STILL GOES THROUGH ADMIN ACCESS, WHICH MAKES "
                + "ANDROID ASK FOR YOUR PIN INSTEAD OF YOUR FINGERPRINT. TAP IT AND SWITCH "
                + "ON ACCESSIBILITY TO KEEP THE FINGERPRINT.\n\n"
                + "TAP SET ON DEFAULT LAUNCHER TO PICK RETRO LAUNCHER AS YOUR HOME APP.");
```
with:
```java
        caption.setText("WEATHER NEEDS PRECISE LOCATION · SCREEN TIME NEEDS USAGE ACCESS. "
                + "THE LAUNCHER WORKS WITHOUT EITHER.\n\n"
                + "LONG-PRESS THE HOME SCREEN TO LOCK, ONCE DEVICE LOCK IS ON.\n\n"
                + "DEVICE LOCK AND NOTIFICATION SHADE BOTH RUN OFF THE SAME ONE SWITCH: "
                + "RETRO LAUNCHER UNDER ACCESSIBILITY. IT READS NOTHING; IT ONLY LOCKS THE "
                + "SCREEN AND OPENS THE SHADE.\n\n"
                + "PIN ONLY MEANS LOCKING STILL GOES THROUGH ADMIN ACCESS, WHICH MAKES "
                + "ANDROID ASK FOR YOUR PIN INSTEAD OF YOUR FINGERPRINT. TAP IT AND SWITCH "
                + "ON ACCESSIBILITY TO KEEP THE FINGERPRINT.\n\n"
                + "SHIZUKU LOCK IS AN OPTIONAL ALTERNATIVE TO ACCESSIBILITY THAT SOME "
                + "BANKING APPS DO NOT FLAG. IT NEEDS A SEPARATE SHIZUKU APP PAIRED OVER "
                + "WIRELESS DEBUGGING, AND THAT PAIRING MUST BE REDONE AFTER EVERY REBOOT "
                + "UNLESS YOUR DEVICE IS ROOTED. GRANT MEANS THE TOGGLE IS ON BUT THE "
                + "PAIRING HAS LAPSED.\n\n"
                + "TAP SET ON DEFAULT LAUNCHER TO PICK RETRO LAUNCHER AS YOUR HOME APP.");
```

- [ ] **Step 5: Build**

Run: `gradle assembleDebug --no-daemon`
Expected: PASS — this closes the loop opened by Task 14 Step 4.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/retro/launcher/ui/SettingsPanel.java
git commit -m "$(cat <<'EOF'
feat: add the SHIZUKU LOCK row to Settings, off by default

Follows the existing DEVICE LOCK three-state row pattern (ENABLE / GRANT /
ON), with caption copy stating the reboot re-pairing cost plainly rather
than hiding it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01P8Toco1Hp5noBgmsR1qQhJ
EOF
)"
```

---

### Task 16: Full-branch verification

**Files:** none (verification only)

- [ ] **Step 1: Run the complete `:core` suite**

Run: `gradle :core:test --no-daemon`
Expected: PASS — every test across `DetentsTest`, `SkyRendererTest`, `SolarMathTest`, `SolarClockTest`, `WeatherParserTest`, `LockRouteTest`, and every pre-existing `:core` test file untouched by this plan.

- [ ] **Step 2: Full assemble**

Run: `gradle assembleDebug --no-daemon`
Expected: PASS.

- [ ] **Step 3: Confirm `HapticCurve` is fully gone**

Run: `grep -rn "HapticCurve" --include=*.java .`
Expected: no output.

- [ ] **Step 4: Confirm the two-argument `LockRoute.choose` has no remaining callers**

Run: `grep -rn "LockRoute.choose" --include=*.java .`
Expected: exactly one call site, in `HomeActivity.lockRoute()`, with three arguments.

- [ ] **Step 5: Confirm `setLatitude` has no remaining callers**

Run: `grep -rn "\.setLatitude(" --include=*.java .`
Expected: no output (only `SkyView.setLocation` remains).

- [ ] **Step 6: Manual CI check**

Push the `V8` branch (only if the user has asked for a push) and confirm in the Actions run: the workflow triggers on the branch, `Verify signer` passes, and the release notes include the signer/versionCode line from Task 1.

- [ ] **Step 7: Request code review**

Use the `superpowers:requesting-code-review` skill against the full `V8` branch diff before merging, covering all sixteen tasks together — several (haptics, moon angle, the solar subsystem) interact through `SkyRenderer`/`SkyView` in ways worth a whole-branch pass rather than sixteen independent ones.
