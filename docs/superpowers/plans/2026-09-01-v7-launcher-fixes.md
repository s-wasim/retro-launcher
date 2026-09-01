# V7 Launcher Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix six independent launcher defects — long-press popup placement, missing haptics, overcounted app usage, imprecise/unreachable weather, letter-tile icons, and un-installable updates.

**Architecture:** Every decision that can be expressed as arithmetic moves into the `core` Java library, which has a JUnit source set and no Android dependency, and is driven test-first. The `app` module keeps only the thin Android adapter around each: `PopupPlacement` → `AnchoredPopup`, `HapticCurve` → `Haptics`, `ForegroundSpans`/`UsageMath` → `UsageRepository`, `IconCoverage` → `PixelArtIcons`. Nothing in this plan shares state with anything else in it, so tasks can be reviewed and reverted individually.

**Tech Stack:** Java 17, Android SDK 36 (minSdk 26), AGP 8.13.2, Gradle 8.14.5, JUnit 4.13.2. No third-party runtime dependencies — the app ships `core` and nothing else.

**Spec:** `docs/superpowers/specs/2026-09-01-v7-launcher-fixes-design.md`

## Global Constraints

- `minSdk 26`, `targetSdk 36`, `compileSdk 36`. Every API above 26 must be guarded by a `Build.VERSION.SDK_INT` check with a working fallback. Never raise `minSdk`.
- Java 17 source and target in both modules.
- No new dependencies in `app/build.gradle`. `core` may only have `testImplementation 'junit:junit:4.13.2'`.
- Unit tests live in `core/src/test/java/com/retro/launcher/core/` only. The `app` module has no test source set and this plan does not add one.
- All user-facing copy is ALL CAPS monospace, matching the surrounding UI.
- Build commands, from the repo root:
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test --no-daemon`
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle assembleDebug --no-daemon`
  - `local.properties` must contain `sdk.dir=/Users/saadwaseem/Library/Android/sdk` (gitignored; create it if absent).
- Branch is `V7`. Commit after every task.

## Deviations from the spec, already decided

1. **§1 dock popup.** The spec says `DockView`'s long-press "routes through the helper". It does not currently open a popup at all — it calls `slotActionListener.onReplace(index)`, which opens a full-screen `BottomSheet`. Decision: give dock slots a real anchored popup (REPLACE / REMOVE / APP INFO), Task 3.
2. **New pure unit `core/PopupPlacement.java`** — not in the spec's file list. The spec's four placement rules are arithmetic; putting them in `core` makes §1 testable without a device, matching how every other section of this plan is structured.
3. **CI trigger.** `.github/workflows/build.yml` builds only on `main`. `V7` is added to the push trigger (Task 1) so branch pushes produce an installable APK.

---

### Task 1: Versioning, signing, and CI

Until this lands, nothing else can be installed on the owner's phone, so it goes first. No unit tests — the deliverable is a build that produces a signed, monotonically-versioned APK.

**Files:**
- Create: `app/debug.keystore` (binary, committed)
- Modify: `app/build.gradle`
- Modify: `.github/workflows/build.yml`
- Modify: `BUILD.md`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing other tasks reference. The final verification task (Task 12) reuses the build commands.

- [ ] **Step 1: Confirm `.gitignore` does not exclude the keystore**

Run: `grep -nE 'keystore|\*\.jks|app/' .gitignore`
Expected: no line matching `keystore` or `*.jks`. The current file lists only `.gradle/`, `build/`, `local.properties`, `*.apk`, `*.iml`, `.idea/`, `.DS_Store` — none of which exclude `app/debug.keystore`. If a match appears, stop and report it rather than editing `.gitignore`.

- [ ] **Step 2: Generate the committed debug keystore**

The credentials below are the platform's well-known debug values. They are not a secret — every Android SDK on earth generates the same pair — and committing them is what makes consecutive CI builds share a signature.

```bash
/opt/homebrew/opt/openjdk@17/bin/keytool -genkeypair \
  -keystore app/debug.keystore \
  -storepass android -keypass android \
  -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Android Debug,O=Android,C=US"
```

- [ ] **Step 3: Verify the keystore is readable and has the expected alias**

Run: `/opt/homebrew/opt/openjdk@17/bin/keytool -list -keystore app/debug.keystore -storepass android`
Expected: one entry, `androiddebugkey`, of type `PrivateKeyEntry`.

- [ ] **Step 4: Point the build at it, and fix `versionCode`**

In `app/build.gradle`, replace the two `defaultConfig` version lines:

```groovy
        versionCode 1
        versionName "0.1"
```

with:

```groovy
        // Frozen at 1 through V6, which is half of why "App not installed"
        // appeared on every update: the package manager saw every build as
        // the same version. CI's run number is monotonic; the +1000 offset
        // puts even the first V7 build far above the installed 1 regardless
        // of what the run number happens to be. A local build gets exactly
        // 1000, which is fine — local builds are not what gets updated over.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInteger() + 1000
        versionName "1.0.0"
```

Then add a `signingConfigs` block immediately before `buildTypes` in the same `android { }` block:

```groovy
    // The other half of "App not installed". CI runs on a fresh
    // ubuntu-latest runner, which generates a brand-new ~/.gradle/debug.keystore
    // on every run, so consecutive builds were signed by different keys and
    // Android refuses an update whose certificate does not match. This
    // keystore is committed so every build — CI or local — shares one
    // signature. A debug keystore is a well-known key pair by design and
    // carries no security value; see BUILD.md.
    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
    }
```

and give both build types that config, so the two existing blocks become:

```groovy
    buildTypes {
        debug {
            signingConfig signingConfigs.debug
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'),
                          'proguard-rules.pro'
        }
        release {
            signingConfig signingConfigs.debug
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'),
                          'proguard-rules.pro'
        }
    }
```

- [ ] **Step 5: Ensure `local.properties` exists, then build**

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle assembleDebug --no-daemon
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Verify the APK carries the committed signature and the new version**

```bash
$HOME/Library/Android/sdk/build-tools/*/apksigner verify --print-certs \
  app/build/outputs/apk/debug/app-debug.apk | head -5
$HOME/Library/Android/sdk/build-tools/*/aapt2 dump badging \
  app/build/outputs/apk/debug/app-debug.apk | head -1
```
Expected: the certificate line reads `CN=Android Debug, O=Android, C=US`, and the badging line reads `versionCode='1000' versionName='1.0.0'`.

- [ ] **Step 7: Add `V7` to the CI push trigger**

In `.github/workflows/build.yml`, change:

```yaml
  push:
    branches: [main]
```

to:

```yaml
  push:
    branches: [main, V7]
```

- [ ] **Step 8: Record both facts in `BUILD.md`**

Append this section to `BUILD.md`:

```markdown
## Versioning and signing

Two things have to stay true or sideloaded updates stop installing, and the
failure looks identical in both cases: "App not installed", with no further
detail.

**`versionCode` is computed, not literal.** `app/build.gradle` derives it from
`GITHUB_RUN_NUMBER + 1000`. CI's run number only ever increases, so every
published build outranks the last. Do not replace this with a literal — a
frozen `versionCode` means the package manager sees every build as the same
version and refuses the install.

**`app/debug.keystore` is committed on purpose.** Android refuses an update
whose signing certificate does not match the installed app's. Without a
committed keystore, each `ubuntu-latest` runner generates its own
`~/.gradle/debug.keystore`, so no two CI builds share a signature. The file
holds the platform's well-known debug credentials — alias `androiddebugkey`,
store and key password `android` — which is a published key pair by design and
carries no security value. Both `debug` and `release` build types point at it.

**One-time step after upgrading from a pre-V7 build:** the previously installed
launcher was signed with a runner-generated key that no longer exists.
Uninstall it once before installing the first V7 APK. Every build after that
updates cleanly.
```

- [ ] **Step 9: Commit**

```bash
git add app/debug.keystore app/build.gradle .github/workflows/build.yml BUILD.md
git commit -m "build: version from CI run number and sign with a committed debug keystore"
```

---

### Task 2: Popup placement arithmetic and the drawer's long-press box

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/PopupPlacement.java`
- Create: `core/src/test/java/com/retro/launcher/core/PopupPlacementTest.java`
- Create: `app/src/main/java/com/retro/launcher/ui/AnchoredPopup.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/DrawerPanel.java` (the `listView` setup around line 110, and `showAppActions` at line 346)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `PopupPlacement.place(float touchX, float touchY, int width, int height, int screenWidth, int screenHeight, int insetLeft, int insetTop, int insetRight, int insetBottom)` → `int[]{x, y}`
  - `AnchoredPopup.showAt(PopupWindow popup, View anchor, View content, int widthPx, float touchScreenX, float touchScreenY)` → `void`
  - `AnchoredPopup.trackTouchPoint(View view)` → `float[]` — a 2-element array the caller keeps, holding the last `ACTION_DOWN` raw x/y. Used by Task 3.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/com/retro/launcher/core/PopupPlacementTest.java`:

```java
package com.retro.launcher.core;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class PopupPlacementTest {

    // A 1080x2400 portrait screen with a 60px status inset and a 130px
    // gesture-navigation inset, which is what an API 36 phone reports.
    private static int[] place(float x, float y, int w, int h) {
        return PopupPlacement.place(x, y, w, h, 1080, 2400, 0, 60, 0, 130);
    }

    @Test public void opensAtTheTouchPointWhenThereIsRoom() {
        assertArrayEquals(new int[]{300, 400}, place(300, 400, 410, 300));
    }

    @Test public void flipsAboveTheTouchPointWhenItWouldCrossTheBottomInset() {
        // 2100 + 300 = 2400, past the 2270 usable bottom, so the popup's
        // bottom edge sits on the touch point instead.
        assertArrayEquals(new int[]{300, 1800}, place(300, 2100, 410, 300));
    }

    @Test public void shiftsLeftWhenItWouldCrossTheRightInset() {
        // 900 + 410 = 1310, past 1080; the right edge lands on 1080.
        assertArrayEquals(new int[]{670, 400}, place(900, 400, 410, 300));
    }

    @Test public void clampsToTheTopInsetWhenFlippingWouldGoOffTheTop() {
        // Flipping 2200-height content above y=300 gives -1900; clamp to 60.
        assertArrayEquals(new int[]{300, 60}, place(300, 300, 410, 2200));
    }

    @Test public void clampsToTheLeftInsetWhenTheContentIsWiderThanTheScreen() {
        assertArrayEquals(new int[]{0, 400}, place(50, 400, 1200, 300));
    }

    @Test public void honoursNonZeroLeftAndRightInsets() {
        // A 40px left inset and 40px right inset — a landscape cutout.
        assertArrayEquals(new int[]{600, 400},
                PopupPlacement.place(900, 400, 410, 300, 1080, 2400, 40, 60, 40, 130));
        assertArrayEquals(new int[]{40, 400},
                PopupPlacement.place(10, 400, 410, 300, 1080, 2400, 40, 60, 40, 130));
    }

    @Test public void treatsAZeroSizedScreenAsTheInsetOrigin() {
        assertArrayEquals(new int[]{0, 0},
                PopupPlacement.place(0, 0, 410, 300, 0, 0, 0, 0, 0, 0));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test --tests '*PopupPlacementTest' --no-daemon`
Expected: compilation failure — `cannot find symbol: class PopupPlacement`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/java/com/retro/launcher/core/PopupPlacement.java`:

```java
package com.retro.launcher.core;

/**
 * Where a popup goes when the user long-presses at a point.
 *
 * Android's {@code showAsDropDown(anchor, 0, 0)} can only say "below this
 * view, at its left edge", which is why the drawer's quick-action box opened
 * on the far left of whichever row was pressed and ran off the bottom of the
 * screen for rows near it. Expressing "at this point, and on-screen" needs
 * {@code showAtLocation} and an explicit coordinate, and that coordinate is
 * pure arithmetic — so it lives here, testable, rather than in the view.
 */
public final class PopupPlacement {

    private PopupPlacement() {}

    /**
     * The top-left corner to show a {@code width x height} popup at, for a
     * long-press at {@code (touchX, touchY)} in screen coordinates.
     *
     * <p>Four rules, in order:
     * <ol>
     *   <li>Start at the touch point.</li>
     *   <li>If the popup would cross the bottom inset, flip it so its
     *       <em>bottom</em> edge sits on the touch point.</li>
     *   <li>If it would cross the right inset, shift left until its right
     *       edge is inset-aligned.</li>
     *   <li>Clamp to the top and left insets, so content taller or wider
     *       than the space still starts on-screen rather than above it.</li>
     * </ol>
     *
     * @return {@code {x, y}} in screen coordinates
     */
    public static int[] place(float touchX, float touchY,
                              int width, int height,
                              int screenWidth, int screenHeight,
                              int insetLeft, int insetTop,
                              int insetRight, int insetBottom) {
        float x = touchX;
        float y = touchY;

        if (y + height > screenHeight - insetBottom) y = touchY - height;
        if (x + width > screenWidth - insetRight) x = screenWidth - insetRight - width;

        if (x < insetLeft) x = insetLeft;
        if (y < insetTop) y = insetTop;

        return new int[]{Math.round(x), Math.round(y)};
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test --tests '*PopupPlacementTest' --no-daemon`
Expected: `BUILD SUCCESSFUL`, 7 tests passing.

- [ ] **Step 5: Write the Android side**

Create `app/src/main/java/com/retro/launcher/ui/AnchoredPopup.java`:

```java
package com.retro.launcher.ui;

import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.PopupWindow;

import com.retro.launcher.core.PopupPlacement;

/**
 * Places every long-press popup in the app at the point that was actually
 * pressed, kept on-screen. See {@link PopupPlacement} for the rules; this
 * class only supplies the measurements they need — the content's measured
 * height, the display size, and the system-bar insets.
 */
public final class AnchoredPopup {

    private AnchoredPopup() {}

    /**
     * Attaches a pass-through touch listener that records where each gesture
     * started, and hands back the array it writes into. The listener always
     * returns {@code false}, so the view's own click, long-click and
     * scrolling behaviour is untouched — this only watches.
     *
     * <p>Needed because {@code OnItemLongClickListener} and
     * {@code OnLongClickListener} carry no coordinates: by the time they fire
     * the {@code MotionEvent} is gone.
     *
     * @return a 2-element {@code {rawX, rawY}} array, updated on every
     *         {@code ACTION_DOWN}, initialised to {@code {-1, -1}}
     */
    public static float[] trackTouchPoint(View view) {
        final float[] point = {-1f, -1f};
        view.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                point[0] = e.getRawX();
                point[1] = e.getRawY();
            }
            return false;
        });
        return point;
    }

    /**
     * Shows {@code popup} with its top-left corner at the placement
     * {@link PopupPlacement} computes for this touch point.
     *
     * <p>A touch point of {@code (-1, -1)} — no {@code ACTION_DOWN} was seen,
     * which happens for a keyboard- or accessibility-driven long press —
     * falls back to the anchor's own top-left corner, which is the old
     * behaviour and still on-screen.
     */
    public static void showAt(PopupWindow popup, View anchor, View content,
                              int widthPx, float touchScreenX, float touchScreenY) {
        content.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = content.getMeasuredHeight();

        float x = touchScreenX, y = touchScreenY;
        if (x < 0f || y < 0f) {
            int[] loc = new int[2];
            anchor.getLocationOnScreen(loc);
            x = loc[0];
            y = loc[1];
        }

        Rect screen = displayBounds(anchor);
        int[] insets = systemInsets(anchor);

        int[] at = PopupPlacement.place(x, y, widthPx, height,
                screen.width(), screen.height(),
                insets[0], insets[1], insets[2], insets[3]);

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0], at[1]);
    }

    private static Rect displayBounds(View view) {
        WindowManager wm = (WindowManager) view.getContext().getSystemService(View.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            return new Rect(wm.getCurrentWindowMetrics().getBounds());
        }
        // 26–29: the root view fills the display, since HomeActivity is
        // edge-to-edge and fullscreen.
        View root = view.getRootView();
        return new Rect(0, 0, root.getWidth(), root.getHeight());
    }

    /** {@code {left, top, right, bottom}} of the system bars, in pixels. */
    private static int[] systemInsets(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets wi = view.getRootWindowInsets();
            if (wi != null) {
                android.graphics.Insets i =
                        wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                return new int[]{i.left, i.top, i.right, i.bottom};
            }
        } else {
            WindowInsets wi = view.getRootWindowInsets();
            if (wi != null) {
                return new int[]{wi.getSystemWindowInsetLeft(), wi.getSystemWindowInsetTop(),
                        wi.getSystemWindowInsetRight(), wi.getSystemWindowInsetBottom()};
            }
        }
        return new int[]{0, 0, 0, 0};
    }

    /** Convenience for callers that build their own content view. */
    public static PopupWindow window(View content, int widthPx, float elevationPx) {
        PopupWindow popup = new PopupWindow(content, widthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(elevationPx);
        return popup;
    }
}
```

- [ ] **Step 6: Route the drawer through it**

In `DrawerPanel`, add a field beside the other view fields (near the `listView` declaration):

```java
    /** Where the last gesture over the list started, in screen coordinates.
     *  {@code OnItemLongClickListener} carries no coordinates of its own. */
    private float[] listTouchPoint = {-1f, -1f};
```

Immediately after `LauncherRoot.setVerticalScroller(listView);` (line 113), add:

```java
        listTouchPoint = AnchoredPopup.trackTouchPoint(listView);
```

Change the long-click listener at line 119 to pass the row through unchanged — it already does; no edit needed there.

Then replace the body of `showAppActions` (line 346) so the popup goes through the helper. The two edits are: build the popup with `AnchoredPopup.window`, and swap the final line.

Replace:

```java
        android.widget.PopupWindow popup = new android.widget.PopupWindow(box,
                Math.round(metrics.cqw(38f)), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(Math.round(metrics.cqw(1f)));
```

with:

```java
        int popupWidth = Math.round(metrics.cqw(38f));
        android.widget.PopupWindow popup =
                AnchoredPopup.window(box, popupWidth, metrics.cqw(1f));
```

and replace:

```java
        popup.showAsDropDown(anchor, 0, 0);
```

with:

```java
        // Not showAsDropDown: it can only say "below this view, at its left
        // edge", which put the box on the far left of the row and off the
        // bottom of the screen for rows near it.
        AnchoredPopup.showAt(popup, anchor, box, popupWidth,
                listTouchPoint[0], listTouchPoint[1]);
```

- [ ] **Step 7: Build**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/PopupPlacement.java \
        core/src/test/java/com/retro/launcher/core/PopupPlacementTest.java \
        app/src/main/java/com/retro/launcher/ui/AnchoredPopup.java \
        app/src/main/java/com/retro/launcher/ui/DrawerPanel.java
git commit -m "fix: open the drawer's long-press box at the touch point, on-screen"
```

---

### Task 3: Dock slot long-press popup

Replaces the dock's full-screen `BottomSheet` on long-press with the same anchored quick-action box the drawer uses. Tapping the trailing `+` slot still opens the sheet — that one is a picker, not an action menu.

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/ui/DockView.java`
- Modify: `app/src/main/java/com/retro/launcher/HomeActivity.java` (the two `setOnSlotActionListener` blocks at lines 148 and 169)

**Interfaces:**
- Consumes: `AnchoredPopup.window`, `AnchoredPopup.showAt`, `AnchoredPopup.trackTouchPoint` from Task 2.
- Produces: `DockView.SlotActionListener` gains `void onRemove(int slotIndex)` and `void onAppInfo(String component)` alongside the existing `onReplace(int)` and `onAdd()`.

- [ ] **Step 1: Widen the listener and add the popup**

In `DockView`, replace the `SlotActionListener` interface:

```java
    /** Long-pressing a filled slot requests its replacement; tapping the
     *  trailing dashed slot requests an addition. Both hand off to whoever
     *  owns the dock-picker {@code BottomSheet} — see HomeActivity. */
    public interface SlotActionListener {
        void onReplace(int slotIndex);
        void onAdd();
    }
```

with:

```java
    /**
     * Long-pressing a filled slot opens a quick-action box over the dock;
     * its rows call back here. Tapping the trailing dashed slot requests an
     * addition. {@code onReplace} and {@code onAdd} hand off to whoever owns
     * the dock-picker {@code BottomSheet} — see HomeActivity — while
     * {@code onRemove} and {@code onAppInfo} act directly.
     */
    public interface SlotActionListener {
        void onReplace(int slotIndex);
        void onRemove(int slotIndex);
        void onAppInfo(String component);
        void onAdd();
    }
```

Add these imports to `DockView`:

```java
import android.widget.PopupWindow;
```

Add a field beside `slotActionListener`:

```java
    /** Where the last gesture over this dock started, in screen coordinates.
     *  {@code OnLongClickListener} carries no coordinates of its own. */
    private final float[] touchPoint;
```

and initialise it at the end of the constructor, after `LauncherRoot.setNoSwipe(this);`:

```java
        touchPoint = AnchoredPopup.trackTouchPoint(this);
```

Note: `setNoSwipe` and `trackTouchPoint` both matter here and do not conflict — `trackTouchPoint`'s listener returns `false`, so it observes only.

- [ ] **Step 2: Swap the slot's long-press for the popup**

In `buildSlot`, replace:

```java
        col.setOnLongClickListener(v -> {
            if (slotActionListener != null) slotActionListener.onReplace(index);
            return true;
        });
```

with:

```java
        col.setOnLongClickListener(v -> {
            showSlotActions(v, component, index);
            return true;
        });
```

- [ ] **Step 3: Add the popup builder**

Add these two methods to `DockView`, after `buildAddSlot()`:

```java
    /**
     * The dock's quick-action box: the three things worth doing to a pinned
     * slot. Deliberately the same visual grammar and the same placement path
     * as the drawer's box (DrawerPanel.showAppActions) — a long press should
     * mean one thing across the launcher.
     */
    private void showSlotActions(View anchor, String component, int index) {
        if (palette == null) return;

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setStroke(Math.round(Math.max(1, metrics.cqw(0.8f))), palette.p);
        boxBg.setColor(palette.bg);
        box.setBackground(boxBg);

        int padH = Math.round(metrics.cqw(4.5f));
        int padV = Math.round(metrics.cqw(2f));
        int popupWidth = Math.round(metrics.cqw(38f));

        PopupWindow popup = AnchoredPopup.window(box, popupWidth, metrics.cqw(1f));

        box.addView(actionRow("REPLACE", padH, padV, () -> {
            popup.dismiss();
            if (slotActionListener != null) slotActionListener.onReplace(index);
        }));
        box.addView(actionRow("REMOVE", padH, padV, () -> {
            popup.dismiss();
            if (slotActionListener != null) slotActionListener.onRemove(index);
        }));
        box.addView(actionRow("MORE DETAILS", padH, padV, () -> {
            popup.dismiss();
            if (slotActionListener != null) slotActionListener.onAppInfo(component);
        }));

        AnchoredPopup.showAt(popup, anchor, box, popupWidth, touchPoint[0], touchPoint[1]);
    }

    private View actionRow(String text, int padH, int padV, Runnable onClick) {
        TextView row = new TextView(getContext());
        row.setText(text);
        row.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.setAllCaps(true);
        row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_TAB_CQW, DrawerPanel.SIZE_TAB_MIN));
        row.setPadding(padH, padV, padH, padV);
        row.setTextColor(palette.ink);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }
```

If `DrawerPanel.SIZE_TAB_CQW` / `SIZE_TAB_MIN` are not `public`, make them `public static final` in `DrawerPanel` — `SIZE_ROW_CQW` and `SIZE_ROW_MIN` are already reached from `SettingsPanel` the same way, so this follows the established pattern.

- [ ] **Step 4: Implement the two new callbacks in `HomeActivity`**

Both `setOnSlotActionListener` blocks (lines 148 and 169) currently read:

```java
            @Override public void onReplace(int slotIndex) { openDockSheet(slotIndex); }
            @Override public void onAdd() { openDockSheet(-1); }
```

Replace both with:

```java
            @Override public void onReplace(int slotIndex) { openDockSheet(slotIndex); }
            @Override public void onRemove(int slotIndex) { removeDockSlot(slotIndex); }
            @Override public void onAppInfo(String component) { openAppInfo(component); }
            @Override public void onAdd() { openDockSheet(-1); }
```

Then add these two methods next to `openDockSheet`:

```java
    /** Drop a pinned slot without going through the picker sheet — the sheet
     *  is for choosing an app, and removal is not a choice of app. */
    private void removeDockSlot(int slotIndex) {
        List<String> next = new ArrayList<>(home.dock.entries());
        if (slotIndex < 0 || slotIndex >= next.size()) return;
        next.remove(slotIndex);
        prefs.setDock(next);
        home.dock.setEntries(next);
        settings.setDockEntries(next);
    }

    /** The system's App Info page for a dock component. Same destination as
     *  the drawer's MORE DETAILS row, reached without a drawer row to hang
     *  an {@link AppEntry} off. */
    private void openAppInfo(String component) {
        int slash = component.indexOf('/');
        String pkg = slash >= 0 ? component.substring(0, slash) : component;
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", pkg, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException ignored) {
            // A device with no Settings app to show. Nothing useful to do.
        }
    }
```

Add `import android.content.Intent;` to `HomeActivity` if it is not already there.

- [ ] **Step 5: Build**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/retro/launcher/ui/DockView.java \
        app/src/main/java/com/retro/launcher/ui/DrawerPanel.java \
        app/src/main/java/com/retro/launcher/HomeActivity.java
git commit -m "feat: anchored REPLACE/REMOVE/MORE DETAILS box on dock long-press"
```

---

### Task 4: Haptic curve

Pure arithmetic, test-first. The bucketing is the whole point of the unit: a 60 fps drag must not issue 60 `vibrate()` calls a second.

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/HapticCurve.java`
- Create: `core/src/test/java/com/retro/launcher/core/HapticCurveTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `HapticCurve.BUCKETS` = `8` (int)
  - `HapticCurve.FLOOR_AMPLITUDE` = `40` (int)
  - `HapticCurve.MAX_AMPLITUDE` = `255` (int)
  - `HapticCurve.bucket(float progress)` → `int` in `[0, 7]`
  - `HapticCurve.amplitude(float progress)` → `int` in `[40, 255]`
  - `HapticCurve.amplitudeForBucket(int bucket)` → `int` in `[40, 255]`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/com/retro/launcher/core/HapticCurveTest.java`:

```java
package com.retro.launcher.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HapticCurveTest {

    @Test public void thereAreExactlyEightBuckets() {
        assertEquals(8, HapticCurve.BUCKETS);
    }

    @Test public void bucketsSpanZeroToSeven() {
        assertEquals(0, HapticCurve.bucket(0f));
        assertEquals(0, HapticCurve.bucket(0.124f));
        assertEquals(1, HapticCurve.bucket(0.125f));
        assertEquals(7, HapticCurve.bucket(0.9f));
        assertEquals(7, HapticCurve.bucket(1f));
    }

    @Test public void progressOutsideZeroToOneClampsRatherThanEscapingTheRange() {
        assertEquals(0, HapticCurve.bucket(-5f));
        assertEquals(7, HapticCurve.bucket(5f));
        assertEquals(0, HapticCurve.bucket(Float.NaN));
    }

    @Test public void amplitudeStartsAtTheFloorAndEndsAtFull() {
        assertEquals(HapticCurve.FLOOR_AMPLITUDE, HapticCurve.amplitude(0f));
        assertEquals(HapticCurve.MAX_AMPLITUDE, HapticCurve.amplitude(1f));
    }

    @Test public void amplitudeNeverDecreasesAsProgressRises() {
        int previous = Integer.MIN_VALUE;
        for (int i = 0; i <= 1000; i++) {
            int a = HapticCurve.amplitude(i / 1000f);
            assertTrue("amplitude fell at progress " + (i / 1000f), a >= previous);
            previous = a;
        }
    }

    @Test public void amplitudeStaysWithinTheVibratorsLegalRange() {
        for (int i = 0; i <= 1000; i++) {
            int a = HapticCurve.amplitude(i / 1000f);
            assertTrue(a >= 1 && a <= 255);
        }
    }

    @Test public void aFullDragCommandsTheVibratorAtMostEightTimes() {
        // The reason bucketing exists. Walk a 60fps, 2-second drag and count
        // how many frames would actually change the bucket.
        int changes = 0;
        int last = -1;
        for (int frame = 0; frame <= 120; frame++) {
            int b = HapticCurve.bucket(frame / 120f);
            if (b != last) { changes++; last = b; }
        }
        assertEquals(8, changes);
    }

    @Test public void theRampIsSquaredNotLinear() {
        // Halfway through, a squared ramp is well below the linear midpoint.
        int mid = HapticCurve.amplitude(0.5f);
        int linearMid = (HapticCurve.FLOOR_AMPLITUDE + HapticCurve.MAX_AMPLITUDE) / 2;
        assertTrue("expected " + mid + " < " + linearMid, mid < linearMid);
    }

    @Test public void bucketIndexesOutsideTheRangeClamp() {
        assertEquals(HapticCurve.FLOOR_AMPLITUDE, HapticCurve.amplitudeForBucket(-3));
        assertEquals(HapticCurve.MAX_AMPLITUDE, HapticCurve.amplitudeForBucket(99));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test --tests '*HapticCurveTest' --no-daemon`
Expected: compilation failure — `cannot find symbol: class HapticCurve`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/java/com/retro/launcher/core/HapticCurve.java`:

```java
package com.retro.launcher.core;

/**
 * How hard the vibrator runs as a panel is dragged.
 *
 * <p>Two jobs. The first is feel: a squared ramp from a floor amplitude to
 * full, so a drag gains weight as it approaches its snap threshold rather
 * than buzzing at one flat level the whole way.
 *
 * <p>The second is the reason this is its own unit. A drag emits a frame
 * every 16ms, and re-commanding the vibrator sixty times a second is both
 * wasteful and audibly wrong — each new waveform restarts the motor. So the
 * amplitude is quantized into {@link #BUCKETS} steps and the caller only
 * re-commands when the bucket changes: at most eight times across a full
 * drag, however fast or slow the finger moves.
 */
public final class HapticCurve {

    private HapticCurve() {}

    /** Eight steps is enough for the swell to read as continuous and few
     *  enough that the motor is never restarted mid-buzz. */
    public static final int BUCKETS = 8;

    /** Perceptible on every device tested, and quiet enough that a drag the
     *  user abandons at 5% does not feel like a mistake. */
    public static final int FLOOR_AMPLITUDE = 40;

    /** {@code VibrationEffect}'s maximum. */
    public static final int MAX_AMPLITUDE = 255;

    /**
     * Which of the {@link #BUCKETS} steps {@code progress} falls in.
     * {@code progress} outside {@code [0, 1]} clamps; NaN reads as 0.
     */
    public static int bucket(float progress) {
        float p = clamp01(progress);
        int b = (int) (p * BUCKETS);
        return b >= BUCKETS ? BUCKETS - 1 : b;
    }

    /** The quantized amplitude for this drag progress. */
    public static int amplitude(float progress) {
        return amplitudeForBucket(bucket(progress));
    }

    /**
     * The amplitude for a bucket index, on a squared ramp so the last
     * quarter of the drag carries most of the swell. Bucket 0 is exactly
     * {@link #FLOOR_AMPLITUDE}; bucket {@code BUCKETS - 1} is exactly
     * {@link #MAX_AMPLITUDE}. Out-of-range indexes clamp.
     */
    public static int amplitudeForBucket(int bucket) {
        int b = bucket < 0 ? 0 : (bucket > BUCKETS - 1 ? BUCKETS - 1 : bucket);
        float t = b / (float) (BUCKETS - 1);
        return Math.round(FLOOR_AMPLITUDE + (MAX_AMPLITUDE - FLOOR_AMPLITUDE) * t * t);
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test --tests '*HapticCurveTest' --no-daemon`
Expected: `BUILD SUCCESSFUL`, 9 tests passing.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/HapticCurve.java \
        core/src/test/java/com/retro/launcher/core/HapticCurveTest.java
git commit -m "feat(core): squared, 8-bucket haptic amplitude curve"
```

---

### Task 5: Haptics engine, preference, and the drag

**Files:**
- Create: `app/src/main/java/com/retro/launcher/util/Haptics.java`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/retro/launcher/data/Prefs.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/SettingsPanel.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/LauncherRoot.java`
- Modify: `app/src/main/java/com/retro/launcher/HomeActivity.java`

**Interfaces:**
- Consumes: `HapticCurve.BUCKETS`, `HapticCurve.amplitudeForBucket`, `HapticCurve.bucket` from Task 4.
- Produces:
  - `Haptics(Context context, boolean enabled)` constructor
  - `Haptics.setEnabled(boolean enabled)` → `void`
  - `Haptics.click()`, `Haptics.longPress()`, `Haptics.dragStart()`, `Haptics.dragEnd()` → `void`
  - `Haptics.dragProgress(float progress)` → `void`
  - `Prefs.K_HAPTIC` = `"haptics"` (String), `Prefs.haptics()` → `boolean`, default `true`
  - `LauncherRoot.setHaptics(Haptics haptics)` → `void`
  - `SettingsPanel.setOnHapticsChanged(java.util.function.Consumer<Boolean> listener)` → `void`

- [ ] **Step 1: Declare the permission**

In `app/src/main/AndroidManifest.xml`, immediately after the `EXPAND_STATUS_BAR` block, add:

```xml
    <!-- Drag and tap feedback. Normal protection: granted at install, no
         runtime prompt. The FEEDBACK toggle in Settings is the only gate,
         and Haptics checks it on every entry point. -->
    <uses-permission android:name="android.permission.VIBRATE" />
```

- [ ] **Step 2: Add the preference**

In `Prefs`, add the key beside `K_HINT`:

```java
    public static final String K_HAPTIC  = "haptics";
```

and the getter beside `hintShown()`:

```java
    /** Default on: a launcher that never buzzes reads as broken, and the
     *  toggle is one tap away in Settings for anyone who disagrees. */
    public boolean haptics()   { return sp.getBoolean(K_HAPTIC, true); }
```

- [ ] **Step 3: Write the engine**

Create `app/src/main/java/com/retro/launcher/util/Haptics.java`:

```java
package com.retro.launcher.util;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.retro.launcher.core.HapticCurve;

/**
 * Every vibration the launcher makes.
 *
 * <p>The master preference is checked on <em>every</em> entry point rather
 * than at construction, so a single {@code setEnabled(false)} silences the
 * whole app the instant the toggle moves, including a drag already in flight.
 *
 * <p>Taps are one-shots. A drag is a repeating waveform whose amplitude is
 * re-commanded only when {@link HapticCurve}'s bucket changes — see that
 * class for why that matters at 60fps.
 *
 * <p>Every failure is silence. A device with no vibrator, a vibrator the
 * system has muted, an amplitude the motor cannot express: none of them are
 * worth an exception on a launcher's touch path.
 */
public final class Haptics {

    /** One pulse of the repeating drag waveform. Short enough that the swell
     *  tracks the finger, long enough that the motor actually spins up. */
    private static final long DRAG_PULSE_MS = 40L;

    private final Vibrator vibrator;
    private boolean enabled;

    /** -1 while no drag is running. */
    private int dragBucket = -1;

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

    /** Begin the repeating drag buzz at the floor amplitude. */
    public void dragStart() {
        if (unavailable()) return;
        dragBucket = -1;
        applyDragBucket(0);
    }

    /**
     * Re-command the motor only if the bucket moved. Safe and cheap to call
     * from every frame of a drag — that is what it is for.
     *
     * @param progress 0 at rest, 1 at the snap threshold
     */
    public void dragProgress(float progress) {
        if (unavailable()) return;
        int bucket = HapticCurve.bucket(progress);
        if (bucket == dragBucket) return;
        applyDragBucket(bucket);
    }

    /** Stop the drag buzz. Idempotent, and safe when no drag is running. */
    public void dragEnd() {
        dragBucket = -1;
        if (vibrator == null) return;
        try {
            vibrator.cancel();
        } catch (RuntimeException ignored) {
        }
    }

    private void applyDragBucket(int bucket) {
        dragBucket = bucket;
        int amplitude = HapticCurve.amplitudeForBucket(bucket);
        try {
            VibrationEffect effect;
            if (hasAmplitudeControl()) {
                // A pulse then a gap, repeating from index 0, so the buzz
                // holds for as long as the finger is down.
                effect = VibrationEffect.createWaveform(
                        new long[]{0L, DRAG_PULSE_MS},
                        new int[]{0, amplitude},
                        /* repeat from */ 0);
            } else {
                // No amplitude control: a fixed duty cycle instead, so the
                // drag still buzzes — it just does not swell.
                effect = VibrationEffect.createWaveform(
                        new long[]{0L, DRAG_PULSE_MS, DRAG_PULSE_MS},
                        /* repeat from */ 0);
            }
            vibrator.cancel();
            vibrator.vibrate(effect);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean hasAmplitudeControl() {
        try {
            return vibrator.hasAmplitudeControl();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Wire the drag in `LauncherRoot`**

Add the import and a field:

```java
import com.retro.launcher.util.Haptics;
```

```java
    /** Null until HomeActivity supplies one; every call site null-checks. */
    private Haptics haptics;

    /** True between the first seize of a gesture and its end, so dragStart
     *  runs once per gesture rather than once per frame. */
    private boolean dragBuzzing;

    public void setHaptics(Haptics haptics) { this.haptics = haptics; }
```

In `seize(int slot, View panel)`, add at the top of the method body:

```java
        if (!dragBuzzing) {
            dragBuzzing = true;
            if (haptics != null) haptics.dragStart();
        }
```

In `drag(float dx, float dy, int w, int h)`, add at the very end of the method, after the axis branches:

```java
        // Progress towards the snap threshold: 1 - reveal is 0 at rest and 1
        // when the panel is fully pulled in, which is the direction the swell
        // should follow.
        if (haptics != null) {
            float progress;
            if (axis == AXIS_H) {
                progress = view == VIEW_HOME
                        ? Math.min(1f, Math.abs(dx) / Math.max(1f, w))
                        : 1f - reveal(view == VIEW_SETTINGS
                                ? clamp(dx, -w, 0) : clamp(dx, 0, w), w);
            } else {
                progress = view == VIEW_HOME
                        ? Math.min(1f, Math.abs(dy) / Math.max(1f, h))
                        : 1f - reveal(clamp(dy, 0, h), h);
            }
            haptics.dragProgress(progress);
        }
```

In `onTouchEvent`, in the `ACTION_UP` / `ACTION_CANCEL` case, add `endDragBuzz();` as the first statement of the case body.

Add the helper and the two lifecycle hooks:

```java
    /** A vibration must never outlive the drag that started it. Called from
     *  the gesture's end, from detach, and from HomeActivity.onPause — a
     *  gesture can be abandoned without ever producing an ACTION_UP. */
    public void endDragBuzz() {
        dragBuzzing = false;
        if (haptics != null) haptics.dragEnd();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        endDragBuzz();
    }
```

- [ ] **Step 5: Construct it and stop it on pause, in `HomeActivity`**

Add the import:

```java
import com.retro.launcher.util.Haptics;
```

Add a field beside the other collaborators:

```java
    private Haptics haptics;
```

In `onCreate`, immediately after `prefs = new Prefs(this);`:

```java
        haptics = new Haptics(this, prefs.haptics());
```

After `root = new LauncherRoot(this);`:

```java
        root.setHaptics(haptics);
```

In `onPause` (add the override if `HomeActivity` has none), before `super.onPause()` returns:

```java
    @Override protected void onPause() {
        super.onPause();
        // A gesture interrupted by an app launch or the screen going off
        // produces no ACTION_UP; without this the buzz would outlive it.
        root.endDragBuzz();
    }
```

If `onPause` already exists, add the `root.endDragBuzz();` line to it rather than declaring a second one.

- [ ] **Step 6: Add the FEEDBACK section to `SettingsPanel`**

Add a field beside the other section fields:

```java
    private final LinearLayout feedbackSection;
```

and a listener field beside the other listeners:

```java
    private java.util.function.Consumer<Boolean> onHapticsChanged;

    public void setOnHapticsChanged(java.util.function.Consumer<Boolean> listener) {
        this.onHapticsChanged = listener;
        buildFeedbackSection();
    }
```

In the constructor, add the section between `tempSection` and `dockSection`:

```java
        content.addView(feedbackSection = section());
```

so the block reads:

```java
        content.addView(paletteSection = section());
        content.addView(clockSection = section());
        content.addView(tempSection = section());
        content.addView(feedbackSection = section());
        content.addView(dockSection = section());
        content.addView(permSection = section());
```

Add the builder, modelled on the existing section builders — locate the method that builds `tempSection` (it starts `tempSection.addView(sectionHeader("TEMPERATURE"));` around line 481) and add this one beside it. `toggleRow(...)` is the existing private helper at line ~755; call it with the same argument order the other callers use:

```java
    /** One toggle, and deliberately its own section rather than a row under
     *  another: haptics are the only thing in the launcher that the user
     *  feels rather than sees. */
    private void buildFeedbackSection() {
        feedbackSection.removeAllViews();
        if (palette == null) return;
        feedbackSection.addView(sectionHeader("FEEDBACK"));
        feedbackSection.addView(toggleRow("HAPTIC FEEDBACK", prefs.haptics(), checked -> {
            prefs.putBool(Prefs.K_HAPTIC, checked);
            if (onHapticsChanged != null) onHapticsChanged.accept(checked);
        }));
    }
```

Find whichever method rebuilds every section on a palette change (the one that calls `buildTempSection`-equivalents) and add `buildFeedbackSection();` to it, so the toggle re-colours with the rest.

- [ ] **Step 7: Wire the toggle to the engine in `HomeActivity`**

Beside the other `settings.setOn...` calls in `onCreate`:

```java
        settings.setOnHapticsChanged(enabled -> haptics.setEnabled(enabled));
```

- [ ] **Step 8: Build**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/main/java/com/retro/launcher/util/Haptics.java \
        app/src/main/java/com/retro/launcher/data/Prefs.java \
        app/src/main/java/com/retro/launcher/ui/SettingsPanel.java \
        app/src/main/java/com/retro/launcher/ui/LauncherRoot.java \
        app/src/main/java/com/retro/launcher/HomeActivity.java
git commit -m "feat: haptic drag feedback, with a FEEDBACK toggle in settings"
```

---

### Task 6: Tap and long-press haptics across every listener

Mechanical but broad. The engine already exists; this task only adds calls.

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/ui/DockView.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/DrawerPanel.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/SettingsPanel.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/BottomSheet.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/SearchOverlay.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/ClockWidget.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/AlphaScrubber.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/LimitSlider.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/CoffeeButton.java`
- Modify: `app/src/main/java/com/retro/launcher/HomeActivity.java`

**Interfaces:**
- Consumes: `Haptics.click()`, `Haptics.longPress()` from Task 5.
- Produces: a `public void setHaptics(Haptics haptics)` setter on each of the nine views above, storing into a nullable `private Haptics haptics;` field.

- [ ] **Step 1: Add the setter to each of the nine views**

Add to each of `DockView`, `DrawerPanel`, `SettingsPanel`, `BottomSheet`, `SearchOverlay`, `ClockWidget`, `AlphaScrubber`, `LimitSlider`, `CoffeeButton`:

```java
import com.retro.launcher.util.Haptics;
```

```java
    /** Null until HomeActivity supplies one. Every call site null-checks
     *  rather than requiring construction order to guarantee it. */
    private Haptics haptics;

    public void setHaptics(Haptics haptics) { this.haptics = haptics; }
```

Then add a private convenience to each, so the call sites stay one line:

```java
    private void tick() { if (haptics != null) haptics.click(); }
    private void thud() { if (haptics != null) haptics.longPress(); }
```

For `ClockWidget`, `AlphaScrubber`, `LimitSlider` and `CoffeeButton`, `thud()` is unused — add only `tick()` there, so no dead method is introduced.

- [ ] **Step 2: Add `tick()` to every tap listener**

Add `tick();` as the first statement of the lambda body at each of these:

- `DockView`: `col.setOnClickListener(v -> launch(component));` → `col.setOnClickListener(v -> { tick(); launch(component); });`
- `DockView`: the `plus.setOnClickListener` in `buildAddSlot`
- `DockView`: each of the three `actionRow(...)` runnables added in Task 3 — instead, add `tick();` inside `actionRow`'s own `row.setOnClickListener(v -> { tick(); onClick.run(); });`
- `DrawerPanel`: `listView.setOnItemClickListener(...)` — add `tick();` before the `instanceof` check
- `DrawerPanel`: `homeButton.setOnClickListener`, `close.setOnClickListener` (delete-category), `chip.setOnClickListener` (tab chip), `plus.setOnClickListener` (new category), and `actionRow`'s `row.setOnClickListener`
- `SettingsPanel`: every `setOnClickListener` on a palette card, chip, dock row or permission row, and the `toggleRow` helper's `PixelToggle` — for the toggle, add `tick();` as the first statement of the `onChange` consumer passed in, i.e. inside `toggleRow` wrap the caller's consumer: `toggle.setOnCheckedChangeListener(checked -> { tick(); onChange.accept(checked); });`
- `BottomSheet`: the row `setOnClickListener` inside `addRow`
- `SearchOverlay`: the result-row `setOnClickListener`, and the web-search row
- `ClockWidget`: all three of `timeView`, `dateView`, `weatherView` `setOnClickListener`s — add `tick();` before the existing `tap(...)` call
- `CoffeeButton`: its `setOnClickListener`

- [ ] **Step 3: Add `thud()` to the long-press surfaces**

- `DrawerPanel`: `listView.setOnItemLongClickListener` — `thud();` before `showAppActions(...)`
- `DrawerPanel`: `chip.setOnLongClickListener` (category tab) — `thud();` before `openMembershipSheet(name)`
- `DockView`: `col.setOnLongClickListener` — `thud();` before `showSlotActions(...)`

- [ ] **Step 4: Add `tick()` on detent and letter crossings**

- `LimitSlider`: find where the slider snaps to a new detent (the point where the reported minute value changes during a drag) and call `tick();` there — once per crossing, not once per frame. If the class tracks a `lastReported` or equivalent, gate on it changing; if not, add `private int lastDetent = Integer.MIN_VALUE;` and gate on that.
- `AlphaScrubber`: in the drag handler, where the letter under the finger changes. The class already computes a letter and calls `onLetterListener`; gate on the letter differing from the previously reported one and call `tick();` there. If it currently fires the listener on every move regardless, add `private char lastLetter = 0;` and gate both the listener and the tick on the change.

- [ ] **Step 5: Hand the engine to every view in `HomeActivity`**

In `onCreate`, after each view is constructed (and after `haptics` is constructed in Task 5), add:

```java
        home.dock.setHaptics(haptics);
        home.clock.setHaptics(haptics);
        home.coffee.setHaptics(haptics);
        drawer.setHaptics(haptics);
        drawer.setScrubberHaptics(haptics);
        settings.setHaptics(haptics);
        sheet.setHaptics(haptics);
        search.setHaptics(haptics);
        screenTime.setLimitSliderHaptics(haptics);
```

`AlphaScrubber` and `LimitSlider` are private children of `DrawerPanel` and `ScreenTimePanel` respectively, so they are reached through their parents. Add to `DrawerPanel`:

```java
    public void setScrubberHaptics(Haptics haptics) { scrubber.setHaptics(haptics); }
```

and to `ScreenTimePanel` (naming the slider field as it is actually declared there):

```java
    public void setLimitSliderHaptics(Haptics haptics) { limitSlider.setHaptics(haptics); }
```

Adjust the field and variable names in the block above to match what `HomeActivity` and `HomePanel` actually call these views — `home.coffee`, `search` and `screenTime` are the expected names; if a name differs, use the real one rather than adding an alias.

- [ ] **Step 6: Build**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/retro/launcher/ui app/src/main/java/com/retro/launcher/HomeActivity.java
git commit -m "feat: tap and long-press haptics on every interactive surface"
```

---

### Task 7: Foreground span state machine

The heart of the usage fix, and pure. This replaces the `Map<String, Long> openedAt` loop in `UsageRepository.scan`, which lets several packages be "open" at once and closes every unmatched span at *now*.

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/ForegroundSpans.java`
- Create: `core/src/test/java/com/retro/launcher/core/ForegroundSpansTest.java`

**Interfaces:**
- Consumes: `UsageMath.Interval` (existing: `new Interval(String pkg, long startMillis, long endMillis)`, public final fields `pkg`, `startMillis`, `endMillis`).
- Produces:
  - `ForegroundSpans.Event` — `public Event(String pkg, int type, long ts)`, public final fields `pkg`, `type`, `ts`
  - `ForegroundSpans.Result` — public final fields `List<UsageMath.Interval> apps`, `List<UsageMath.Interval> awake`, `int pickups`
  - `ForegroundSpans.scan(List<Event> events, long windowStart, long windowEnd)` → `Result`
  - Event-type constants: `ACTIVITY_RESUMED` (1), `ACTIVITY_PAUSED` (2), `SCREEN_INTERACTIVE` (15), `SCREEN_NON_INTERACTIVE` (16), `KEYGUARD_SHOWN` (17), `KEYGUARD_HIDDEN` (18), `ACTIVITY_STOPPED` (23), `DEVICE_SHUTDOWN` (26)

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/com/retro/launcher/core/ForegroundSpansTest.java`:

```java
package com.retro.launcher.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ForegroundSpansTest {

    private static final long MIN = 60_000L;

    private static ForegroundSpans.Event e(String pkg, int type, long ts) {
        return new ForegroundSpans.Event(pkg, type, ts);
    }

    private static long totalFor(List<UsageMath.Interval> spans, String pkg) {
        long sum = 0;
        for (UsageMath.Interval iv : spans) {
            if (pkg.equals(iv.pkg)) sum += iv.endMillis - iv.startMillis;
        }
        return sum;
    }

    @Test public void aPausedAppIsCreditedOnlyWithItsForegroundTime() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 10 * MIN),
                e("a", ForegroundSpans.ACTIVITY_PAUSED, 25 * MIN)
        ), 0, 100 * MIN);
        assertEquals(15 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void anAppLeftRunningAcrossAScreenOffStopsAtTheScreenOff() {
        // The reported symptom: a background app credited with every minute
        // since the screen went dark.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 10 * MIN),
                e("", ForegroundSpans.SCREEN_NON_INTERACTIVE, 20 * MIN)
        ), 0, 600 * MIN);
        assertEquals(10 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void anAppNeverPausedWithTheScreenNowOffIsDiscardedEntirely() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("", ForegroundSpans.SCREEN_NON_INTERACTIVE, 5 * MIN),
                e("", ForegroundSpans.SCREEN_INTERACTIVE, 10 * MIN),
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 12 * MIN),
                e("", ForegroundSpans.SCREEN_NON_INTERACTIVE, 15 * MIN)
        ), 0, 600 * MIN);
        assertEquals(3 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void anAppStillForegroundWithTheScreenOnRunsToTheWindowEnd() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("", ForegroundSpans.SCREEN_INTERACTIVE, 5 * MIN),
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 10 * MIN)
        ), 0, 30 * MIN);
        assertEquals(20 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void twoResumesWithNoPauseBetweenThemDoNotDoubleCount() {
        // Only one activity is ever foreground; resuming b closes a.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 0),
                e("b", ForegroundSpans.ACTIVITY_RESUMED, 10 * MIN),
                e("b", ForegroundSpans.ACTIVITY_PAUSED, 30 * MIN)
        ), 0, 100 * MIN);
        assertEquals(10 * MIN, totalFor(r.apps, "a"));
        assertEquals(20 * MIN, totalFor(r.apps, "b"));
        assertEquals(30 * MIN, totalFor(r.apps, "a") + totalFor(r.apps, "b"));
    }

    @Test public void aPauseForAnAppThatIsNotFocusedIsIgnored() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 0),
                e("b", ForegroundSpans.ACTIVITY_PAUSED, 5 * MIN),
                e("a", ForegroundSpans.ACTIVITY_PAUSED, 10 * MIN)
        ), 0, 100 * MIN);
        assertEquals(10 * MIN, totalFor(r.apps, "a"));
        assertEquals(0L, totalFor(r.apps, "b"));
    }

    @Test public void activityStoppedClosesTheFocusedSpanToo() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 0),
                e("a", ForegroundSpans.ACTIVITY_STOPPED, 8 * MIN)
        ), 0, 100 * MIN);
        assertEquals(8 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void keyguardShownClosesTheFocusedSpan() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 0),
                e("", ForegroundSpans.KEYGUARD_SHOWN, 7 * MIN)
        ), 0, 100 * MIN);
        assertEquals(7 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void deviceShutdownClosesTheFocusedSpan() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 0),
                e("", ForegroundSpans.DEVICE_SHUTDOWN, 4 * MIN)
        ), 0, 100 * MIN);
        assertEquals(4 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void aDayWithNoScreenEventsTreatsTheWholeWindowAsAwake() {
        // Below API 28 the platform emits no screen events at all. Clipping
        // to an empty awake list would zero the day; assuming the window is
        // awake is the truthful degradation.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 10 * MIN),
                e("a", ForegroundSpans.ACTIVITY_PAUSED, 40 * MIN)
        ), 0, 100 * MIN);
        assertEquals(30 * MIN, totalFor(r.apps, "a"));
        assertEquals(1, r.awake.size());
        assertEquals(0L, r.awake.get(0).startMillis);
        assertEquals(100 * MIN, r.awake.get(0).endMillis);
    }

    @Test public void awakeWindowsTrackTheScreen() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("", ForegroundSpans.SCREEN_NON_INTERACTIVE, 10 * MIN),
                e("", ForegroundSpans.SCREEN_INTERACTIVE, 30 * MIN),
                e("", ForegroundSpans.SCREEN_NON_INTERACTIVE, 50 * MIN)
        ), 0, 100 * MIN);
        assertEquals(2, r.awake.size());
        assertEquals(0L, r.awake.get(0).startMillis);
        assertEquals(10 * MIN, r.awake.get(0).endMillis);
        assertEquals(30 * MIN, r.awake.get(1).startMillis);
        assertEquals(50 * MIN, r.awake.get(1).endMillis);
    }

    @Test public void pickupsCountKeyguardDismissals() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("", ForegroundSpans.KEYGUARD_HIDDEN, 1 * MIN),
                e("", ForegroundSpans.KEYGUARD_SHOWN, 2 * MIN),
                e("", ForegroundSpans.KEYGUARD_HIDDEN, 3 * MIN),
                e("", ForegroundSpans.KEYGUARD_HIDDEN, 4 * MIN)
        ), 0, 100 * MIN);
        assertEquals(3, r.pickups);
    }

    @Test public void aSpanStraddlingMidnightIsOneUnsplitInterval() {
        // Splitting is UsageMath.dailyTotals' job, not this machine's.
        long midnight = 1_700_000_000_000L;
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, midnight - 10 * MIN),
                e("a", ForegroundSpans.ACTIVITY_PAUSED, midnight + 10 * MIN)
        ), midnight - 60 * MIN, midnight + 60 * MIN);
        assertEquals(1, r.apps.size());
        assertEquals(midnight - 10 * MIN, r.apps.get(0).startMillis);
        assertEquals(midnight + 10 * MIN, r.apps.get(0).endMillis);
    }

    @Test public void zeroLengthSpansAreDropped() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", ForegroundSpans.ACTIVITY_RESUMED, 5 * MIN),
                e("a", ForegroundSpans.ACTIVITY_PAUSED, 5 * MIN)
        ), 0, 100 * MIN);
        assertTrue(r.apps.isEmpty());
    }

    @Test public void anEmptyEventStreamProducesNothingButTheAwakeWindow() {
        ForegroundSpans.Result r = ForegroundSpans.scan(
                new ArrayList<>(), 0, 100 * MIN);
        assertTrue(r.apps.isEmpty());
        assertEquals(0, r.pickups);
        assertEquals(1, r.awake.size());
    }

    @Test public void anEmptyWindowProducesNothingAtAll() {
        ForegroundSpans.Result r = ForegroundSpans.scan(
                new ArrayList<>(), 100 * MIN, 100 * MIN);
        assertTrue(r.apps.isEmpty());
        assertTrue(r.awake.isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test --tests '*ForegroundSpansTest' --no-daemon`
Expected: compilation failure — `cannot find symbol: class ForegroundSpans`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/java/com/retro/launcher/core/ForegroundSpans.java`:

```java
package com.retro.launcher.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The one-foreground-app state machine, as a pure function.
 *
 * <p>Android's model has exactly one foreground activity at a time. The
 * previous implementation kept a {@code Map<String, Long>} of open packages,
 * so several could be "open" at once and every extra entry counted the same
 * minute twice. Worse, any span without a matching pause was closed at
 * <em>now</em>, which is why an app foregrounded before the screen went off
 * was credited with every minute since — the reported symptom.
 *
 * <p>One rule fixes that: a span still open when the scan ends is closed at
 * the window end <strong>only if the screen is currently interactive</strong>.
 * Otherwise it is discarded, because the device stopped telling us anything
 * and inventing time is worse than losing it.
 *
 * <p>The event-type values are {@code UsageEvents.Event}'s own public
 * constants, repeated here so this class stays free of any Android import
 * and therefore unit-testable. {@code ACTIVITY_STOPPED} is API 29+; below
 * that the machine simply never sees one and relies on
 * {@code ACTIVITY_PAUSED} plus the screen-off close.
 */
public final class ForegroundSpans {

    private ForegroundSpans() {}

    public static final int ACTIVITY_RESUMED = 1;        // == MOVE_TO_FOREGROUND
    public static final int ACTIVITY_PAUSED = 2;         // == MOVE_TO_BACKGROUND
    public static final int SCREEN_INTERACTIVE = 15;
    public static final int SCREEN_NON_INTERACTIVE = 16;
    public static final int KEYGUARD_SHOWN = 17;
    public static final int KEYGUARD_HIDDEN = 18;
    public static final int ACTIVITY_STOPPED = 23;       // API 29+
    public static final int DEVICE_SHUTDOWN = 26;

    /** One row of the platform's event stream, with nothing else attached. */
    public static final class Event {
        public final String pkg;
        public final int type;
        public final long ts;
        public Event(String pkg, int type, long ts) {
            this.pkg = pkg;
            this.type = type;
            this.ts = ts;
        }
    }

    public static final class Result {
        /** Foreground spans, in the order they closed. Never overlapping. */
        public final List<UsageMath.Interval> apps;
        /** Spans during which the display was interactive. */
        public final List<UsageMath.Interval> awake;
        /** Keyguard dismissals in the window. */
        public final int pickups;
        Result(List<UsageMath.Interval> apps, List<UsageMath.Interval> awake, int pickups) {
            this.apps = apps;
            this.awake = awake;
            this.pickups = pickups;
        }
    }

    /** The pseudo-package awake windows ride under. Not a legal package name,
     *  so it can never collide with a real one. */
    public static final String AWAKE = "!awake";

    /**
     * Replays {@code events} — which must be in timestamp order, as
     * {@code queryEvents} returns them — into foreground spans, awake
     * windows and a pickup count.
     *
     * <p>The window is assumed awake at {@code windowStart} until an event
     * says otherwise. That covers both "the screen was already lit when the
     * window opened" and "this device emits no screen events at all"
     * (below API 28), where clipping to an empty awake list would wrongly
     * zero the whole day.
     */
    public static Result scan(List<Event> events, long windowStart, long windowEnd) {
        List<UsageMath.Interval> apps = new ArrayList<>();
        List<UsageMath.Interval> awake = new ArrayList<>();
        int pickups = 0;

        if (windowEnd <= windowStart) return new Result(apps, awake, 0);

        String focused = null;
        long focusedSince = 0L;
        long awakeSince = windowStart;

        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            long ts = clamp(e.ts, windowStart, windowEnd);
            switch (e.type) {
                case ACTIVITY_RESUMED:
                    if (focused != null) addSpan(apps, focused, focusedSince, ts);
                    focused = e.pkg;
                    focusedSince = ts;
                    break;

                case ACTIVITY_PAUSED:
                case ACTIVITY_STOPPED:
                    // Only the focused activity's own pause ends the span. A
                    // pause from anything else is a background transition we
                    // were never counting.
                    if (focused != null && focused.equals(e.pkg)) {
                        addSpan(apps, focused, focusedSince, ts);
                        focused = null;
                    }
                    break;

                case SCREEN_NON_INTERACTIVE:
                case KEYGUARD_SHOWN:
                case DEVICE_SHUTDOWN:
                    if (focused != null) {
                        addSpan(apps, focused, focusedSince, ts);
                        focused = null;
                    }
                    if (awakeSince >= 0) {
                        addSpan(awake, AWAKE, awakeSince, ts);
                        awakeSince = -1L;
                    }
                    break;

                case KEYGUARD_HIDDEN:
                    pickups++;
                    if (awakeSince < 0) awakeSince = ts;
                    break;

                case SCREEN_INTERACTIVE:
                    if (awakeSince < 0) awakeSince = ts;
                    break;

                default:
                    break;
            }
        }

        if (awakeSince >= 0) {
            // The screen is still on, so a span with no pause really is still
            // in the foreground and runs to the window's end.
            addSpan(awake, AWAKE, awakeSince, windowEnd);
            if (focused != null) addSpan(apps, focused, focusedSince, windowEnd);
        }
        // Otherwise the still-open span is dropped on the floor, on purpose.

        return new Result(apps, awake, pickups);
    }

    private static void addSpan(List<UsageMath.Interval> out, String pkg, long from, long to) {
        if (to > from) out.add(new UsageMath.Interval(pkg, from, to));
    }

    private static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test --tests '*ForegroundSpansTest' --no-daemon`
Expected: `BUILD SUCCESSFUL`, 16 tests passing.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/ForegroundSpans.java \
        core/src/test/java/com/retro/launcher/core/ForegroundSpansTest.java
git commit -m "feat(core): one-foreground-app span machine, discarding unclosed spans when the screen is off"
```

---

### Task 8: `UsageMath.merge` / `intersect`, and deleting `resolveTotal`

**Files:**
- Modify: `core/src/main/java/com/retro/launcher/core/UsageMath.java`
- Modify: `core/src/test/java/com/retro/launcher/core/UsageMathTest.java` (delete lines 152–165, the three `resolveTotal` tests; add the new ones)

**Interfaces:**
- Consumes: `UsageMath.Interval` (existing).
- Produces:
  - `UsageMath.merge(List<Interval> intervals)` → `List<Interval>` — per-package, overlapping or touching spans coalesced, sorted by start
  - `UsageMath.intersect(List<Interval> spans, List<Interval> windows)` → `List<Interval>` — each span clipped to the union of `windows`
- Removes: `UsageMath.resolveTotal(long, long, long)`.

- [ ] **Step 1: Write the failing tests**

In `core/src/test/java/com/retro/launcher/core/UsageMathTest.java`, delete the three `resolveTotal` tests (lines 152–165) and append these:

```java
    private static UsageMath.Interval iv(String pkg, long from, long to) {
        return new UsageMath.Interval(pkg, from, to);
    }

    private static long sum(java.util.List<UsageMath.Interval> ivs) {
        long total = 0;
        for (UsageMath.Interval i : ivs) total += i.endMillis - i.startMillis;
        return total;
    }

    @Test public void mergeCoalescesOverlappingSpansForOnePackage() {
        java.util.List<UsageMath.Interval> merged = UsageMath.merge(java.util.Arrays.asList(
                iv("a", 0, 100), iv("a", 50, 200)));
        assertEquals(1, merged.size());
        assertEquals(0L, merged.get(0).startMillis);
        assertEquals(200L, merged.get(0).endMillis);
    }

    @Test public void mergeCoalescesTouchingSpans() {
        java.util.List<UsageMath.Interval> merged = UsageMath.merge(java.util.Arrays.asList(
                iv("a", 0, 100), iv("a", 100, 200)));
        assertEquals(1, merged.size());
        assertEquals(200L, merged.get(0).endMillis);
    }

    @Test public void mergeKeepsSeparatePackagesSeparate() {
        java.util.List<UsageMath.Interval> merged = UsageMath.merge(java.util.Arrays.asList(
                iv("a", 0, 100), iv("b", 50, 200)));
        assertEquals(2, merged.size());
        assertEquals(250L, sum(merged));
    }

    @Test public void mergeKeepsAGapAsTwoSpans() {
        java.util.List<UsageMath.Interval> merged = UsageMath.merge(java.util.Arrays.asList(
                iv("a", 0, 100), iv("a", 150, 200)));
        assertEquals(2, merged.size());
        assertEquals(150L, sum(merged));
    }

    @Test public void mergeSwallowsAFullyContainedSpan() {
        java.util.List<UsageMath.Interval> merged = UsageMath.merge(java.util.Arrays.asList(
                iv("a", 0, 500), iv("a", 100, 200)));
        assertEquals(1, merged.size());
        assertEquals(500L, sum(merged));
    }

    @Test public void mergeHandlesUnsortedInput() {
        java.util.List<UsageMath.Interval> merged = UsageMath.merge(java.util.Arrays.asList(
                iv("a", 150, 200), iv("a", 0, 100), iv("a", 90, 160)));
        assertEquals(1, merged.size());
        assertEquals(200L, sum(merged));
    }

    @Test public void mergeOfNothingIsNothing() {
        assertTrue(UsageMath.merge(new java.util.ArrayList<>()).isEmpty());
    }

    @Test public void intersectClipsSpansToTheWindows() {
        java.util.List<UsageMath.Interval> clipped = UsageMath.intersect(
                java.util.Arrays.asList(iv("a", 0, 1000)),
                java.util.Arrays.asList(iv("!awake", 200, 400), iv("!awake", 600, 700)));
        assertEquals(2, clipped.size());
        assertEquals(300L, sum(clipped));
        assertEquals("a", clipped.get(0).pkg);
    }

    @Test public void intersectDropsSpansEntirelyOutsideEveryWindow() {
        java.util.List<UsageMath.Interval> clipped = UsageMath.intersect(
                java.util.Arrays.asList(iv("a", 0, 100)),
                java.util.Arrays.asList(iv("!awake", 500, 900)));
        assertTrue(clipped.isEmpty());
    }

    @Test public void intersectWithNoWindowsKeepsNothing() {
        java.util.List<UsageMath.Interval> clipped = UsageMath.intersect(
                java.util.Arrays.asList(iv("a", 0, 100)),
                new java.util.ArrayList<>());
        assertTrue(clipped.isEmpty());
    }

    @Test public void intersectPreservesAFullyContainedSpan() {
        java.util.List<UsageMath.Interval> clipped = UsageMath.intersect(
                java.util.Arrays.asList(iv("a", 200, 300)),
                java.util.Arrays.asList(iv("!awake", 0, 1000)));
        assertEquals(1, clipped.size());
        assertEquals(100L, sum(clipped));
    }
```

Add `import static org.junit.Assert.assertTrue;` to the test file if it is not already imported.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test --tests '*UsageMathTest' --no-daemon`
Expected: compilation failure — `cannot find symbol: method merge`.

- [ ] **Step 3: Implement, and delete `resolveTotal`**

In `UsageMath`, delete the whole `resolveTotal` method and its javadoc (lines 105–117), then add:

```java
    /**
     * Coalesces overlapping or touching spans, per package, so no arrangement
     * of events can make one minute count twice. Sorted by start within each
     * package; package order across the result is not meaningful.
     */
    public static List<Interval> merge(List<Interval> intervals) {
        Map<String, List<Interval>> byPkg = new LinkedHashMap<>();
        for (Interval iv : intervals) {
            if (iv.endMillis <= iv.startMillis) continue;
            byPkg.computeIfAbsent(iv.pkg, k -> new ArrayList<>()).add(iv);
        }

        List<Interval> out = new ArrayList<>();
        for (Map.Entry<String, List<Interval>> entry : byPkg.entrySet()) {
            List<Interval> spans = entry.getValue();
            spans.sort((a, b) -> Long.compare(a.startMillis, b.startMillis));
            long start = spans.get(0).startMillis;
            long end = spans.get(0).endMillis;
            for (int i = 1; i < spans.size(); i++) {
                Interval iv = spans.get(i);
                if (iv.startMillis <= end) {
                    // Overlapping or exactly touching: extend rather than add.
                    if (iv.endMillis > end) end = iv.endMillis;
                } else {
                    out.add(new Interval(entry.getKey(), start, end));
                    start = iv.startMillis;
                    end = iv.endMillis;
                }
            }
            out.add(new Interval(entry.getKey(), start, end));
        }
        return out;
    }

    /**
     * Clips every span to {@code windows}, keeping the span's package. Time
     * a device spent with the screen off is not screen time, however
     * confidently the event stream implies an app was foreground through it.
     *
     * <p>A span overlapping several windows produces several intervals. A
     * span overlapping none disappears — including when {@code windows} is
     * empty, which callers must therefore avoid passing when the real
     * meaning is "screen state unknown"; {@link ForegroundSpans} guarantees a
     * non-empty window list whenever the device reported no screen events.
     */
    public static List<Interval> intersect(List<Interval> spans, List<Interval> windows) {
        List<Interval> out = new ArrayList<>();
        for (Interval span : spans) {
            for (Interval window : windows) {
                long from = Math.max(span.startMillis, window.startMillis);
                long to = Math.min(span.endMillis, window.endMillis);
                if (to > from) out.add(new Interval(span.pkg, from, to));
            }
        }
        return out;
    }
```

Add `import java.util.LinkedHashMap;` to `UsageMath`'s imports.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test --no-daemon`
Expected: `BUILD SUCCESSFUL`, the whole `core` suite green, with no reference to `resolveTotal` remaining.

- [ ] **Step 5: Confirm the deletion is complete**

Run: `grep -rn "resolveTotal" core app`
Expected: no output. (`UsageRepository.dayTotal` still calls it at this point in the plan and will not compile — that is Task 9's job, and `:core:test` does not build the `app` module. If `grep` finds only the `UsageRepository` call site, that is expected here.)

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/retro/launcher/core/UsageMath.java \
        core/src/test/java/com/retro/launcher/core/UsageMathTest.java
git commit -m "feat(core): merge and intersect for usage spans; drop resolveTotal"
```

---

### Task 9: Rebuild `UsageRepository` on the new machine

**Files:**
- Modify: `app/src/main/java/com/retro/launcher/data/UsageRepository.java` (whole-file rewrite of `scan`, `dayTotal`, `mostUsedToday`, `pickupsToday`)

**Interfaces:**
- Consumes: `ForegroundSpans.Event`, `ForegroundSpans.Result`, `ForegroundSpans.scan` (Task 7); `UsageMath.merge`, `UsageMath.intersect` (Task 8).
- Produces: `UsageRepository`'s public surface is unchanged — `todayMillis(long)`, `last7DaysMillis(long)`, `mostUsedToday(long, int)`, `pickupsToday(long)`, and the `AppUsage` class. No caller needs editing.

- [ ] **Step 1: Replace the file**

Overwrite `app/src/main/java/com/retro/launcher/data/UsageRepository.java` with:

```java
package com.retro.launcher.data;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import com.retro.launcher.core.ForegroundSpans;
import com.retro.launcher.core.UsageMath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Wraps {@link UsageStatsManager} and turns its event stream into the
 * {@link UsageMath.Interval} lists that class does its calendar-day
 * arithmetic on. Requires {@code PACKAGE_USAGE_STATS} — every method degrades
 * to empty/zero rather than throwing when access isn't granted.
 *
 * <p>All the judgement lives in {@link ForegroundSpans}, which is a pure
 * function and unit-tested; this class only reads events and hands them over.
 * That split is the point: the previous in-line state machine could not be
 * tested without a device, and it was wrong in three ways at once —
 * several packages "open" at the same time, unclosed spans credited with
 * every minute up to now, and a headline number that was screen-on time
 * rather than app time.
 *
 * <p>Today's total is now, simply, the app spans: merged so no minute counts
 * twice, clipped to the spans in which the display was actually interactive,
 * and with the launcher's own foreground time removed — time on the home
 * screen is not time on an app. The per-app rows come off the same list, so
 * the headline and the rows finally agree.
 */
public final class UsageRepository {

    public static final class AppUsage {
        public final String pkg;
        public final long millis;
        public AppUsage(String pkg, long millis) {
            this.pkg = pkg;
            this.millis = millis;
        }
    }

    private final UsageStatsManager usm;
    private final String selfPkg;
    private final TimeZone tz = TimeZone.getDefault();

    public UsageRepository(Context context) {
        this.usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.selfPkg = context.getPackageName();
    }

    /** One pass over the platform's stream, replayed through the pure machine. */
    private ForegroundSpans.Result scan(long start, long end) {
        List<ForegroundSpans.Event> raw = new ArrayList<>();
        if (usm != null && end > start) {
            UsageEvents events = usm.queryEvents(start, end);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                raw.add(new ForegroundSpans.Event(
                        e.getPackageName(), e.getEventType(), e.getTimeStamp()));
            }
        }
        return ForegroundSpans.scan(raw, start, end);
    }

    /**
     * App spans, deduplicated, clipped to the screen-awake windows, with the
     * launcher removed. Everything else in this class is a projection of it.
     */
    private List<UsageMath.Interval> countable(ForegroundSpans.Result r) {
        List<UsageMath.Interval> merged = UsageMath.merge(r.apps);
        List<UsageMath.Interval> awake = UsageMath.merge(r.awake);
        return UsageMath.excluding(UsageMath.intersect(merged, awake), selfPkg);
    }

    public long todayMillis(long nowMillis) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        return UsageMath.totalForDay(countable(scan(dayStart, nowMillis)), dayStart, tz);
    }

    /** Millis used on each of the last 7 calendar days, oldest to today. */
    public long[] last7DaysMillis(long nowMillis) {
        long[] dayStarts = UsageMath.last7DayStarts(nowMillis, tz);
        List<UsageMath.Interval> week = countable(scan(dayStarts[0], nowMillis));
        long[] out = new long[7];
        for (int i = 0; i < 7; i++) out[i] = UsageMath.totalForDay(week, dayStarts[i], tz);
        return out;
    }

    /** Per-app totals for today, descending, capped at {@code limit} rows.
     *  The launcher is not one of the apps. */
    public List<AppUsage> mostUsedToday(long nowMillis, int limit) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        Map<String, Long> totals = new LinkedHashMap<>();
        for (UsageMath.Interval iv : countable(scan(dayStart, nowMillis))) {
            totals.merge(iv.pkg, iv.endMillis - iv.startMillis, Long::sum);
        }
        List<AppUsage> out = new ArrayList<>(totals.size());
        for (Map.Entry<String, Long> e : totals.entrySet()) out.add(new AppUsage(e.getKey(), e.getValue()));
        out.sort((a, b) -> Long.compare(b.millis, a.millis));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /**
     * Keyguard dismissals today. Counted by the same scan that produces the
     * spans, rather than the second independent {@code queryEvents} pass this
     * used to make. Below API 28 the platform emits no keyguard events, so
     * this is 0 — a truthful degradation, not a crash.
     */
    public int pickupsToday(long nowMillis) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        return scan(dayStart, nowMillis).pickups;
    }
}
```

- [ ] **Step 2: Confirm nothing else referenced the deleted internals**

Run: `grep -rn "resolveTotal\|SCREEN\b\|\.screen\b\|Scan(" app/src/main/java/com/retro/launcher/`
Expected: no hits in `UsageRepository` or its callers. `UsageRepository`'s public methods are unchanged, so `HomeActivity`, `ScreenTimePanel` and `WeekChart` need no edits.

- [ ] **Step 3: Build and run the full test suite**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test assembleDebug --no-daemon
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/retro/launcher/data/UsageRepository.java
git commit -m "fix: count only real foreground time, clipped to screen-awake windows"
```

---

### Task 10: Precise location, and a weather tap that resolves

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/retro/launcher/data/LocationSource.java`
- Modify: `app/src/main/java/com/retro/launcher/data/WeatherRepository.java`
- Modify: `app/src/main/java/com/retro/launcher/data/OpenMeteoWeather.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/ClockWidget.java`
- Modify: `app/src/main/java/com/retro/launcher/HomeActivity.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/SetupScreen.java`
- Modify: `app/src/main/java/com/retro/launcher/ui/SettingsPanel.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `LocationSource.hasPermission()` → `boolean` — true when *either* location permission is granted (signature unchanged, semantics widened)
  - `LocationSource.hasFinePermission()` → `boolean`
  - `LocationSource.requestFresh(java.util.function.Consumer<double[]> callback)` → `void` — calls back on the main thread with `{lat, lon}` or `null`, within 8 seconds
  - `ClockWidget.setOnWeatherLongPress(Runnable r)` → `void`

- [ ] **Step 1: Manifest — fine location and the weather packages**

Beside the existing `ACCESS_COARSE_LOCATION` line, add:

```xml
    <!-- The weather fetch already sends exact coordinates; with only COARSE
         the platform hands us a fix rounded to roughly a city block, and the
         app never asks a provider for one at all. FINE plus
         LocationSource.requestFresh is what makes the reading local. Both
         are declared because the user may grant only approximate location,
         and that must still work. -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

Inside the existing `<queries>` element, after the last `<intent>` block, add:

```xml
        <!-- ClockWidget.WEATHER_PACKAGES. On API 30+ an undeclared package is
             invisible, so getLaunchIntentForPackage returned null for every
             one of these and tapping the weather line could never open a
             weather app — the tap was not broken, the visibility was. -->
        <package android:name="com.google.android.apps.weather" />
        <package android:name="com.sec.android.daemonapp" />
        <package android:name="com.samsung.android.weather" />
        <package android:name="com.miui.weather2" />
        <package android:name="com.huawei.android.totemweather" />
        <package android:name="com.coloros.weather2" />
        <package android:name="com.oneplus.weather" />
        <package android:name="com.weather.Weather" />
        <package android:name="com.accuweather.android" />
```

- [ ] **Step 2: Verify the two lists match**

Run: `grep -o 'com\.[a-zA-Z.]*' app/src/main/java/com/retro/launcher/ui/ClockWidget.java | grep -iE 'weather|daemonapp' | sort -u`
Compare against the `<package>` names just added. Expected: the same nine strings, exactly.

- [ ] **Step 3: Add the fresh-fix path to `LocationSource`**

Replace the whole of `LocationSource.java` with:

```java
package com.retro.launcher.data;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The fix the weather fetch needs, and nothing more.
 *
 * <p>This never registers for continuous location updates. A launcher has no
 * business following you around. What it will do — once, when a fetch was
 * already going to happen anyway — is ask a provider for a single current
 * fix, because the alternative was reusing whatever fix some other app last
 * happened to cause, which on a phone that has not opened Maps in a week is
 * a different city.
 *
 * <p>Every failure — permission not granted, location off, no provider has
 * ever had a fix, the request timed out — is the same null.
 */
public final class LocationSource {

    /** Long enough for a warm GPS or a network fix, short enough that the
     *  weather line is not blank while the user looks at it. */
    private static final long TIMEOUT_MS = 8_000L;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    public LocationSource(Context context) {
        this.ctx = context.getApplicationContext();
    }

    /** True when either location permission is held. The user may grant only
     *  approximate location, and approximate weather is still weather. */
    public boolean hasPermission() {
        return granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                || granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    public boolean hasFinePermission() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean granted(String permission) {
        return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** @return {latitude, longitude} of the freshest known fix, or null. */
    public double[] lastKnown() {
        if (!hasPermission()) return null;

        LocationManager lm = manager();
        if (lm == null) return null;

        try {
            Location best = null;
            List<String> providers = lm.getProviders(true);
            for (int i = 0; i < providers.size(); i++) {
                Location l = lm.getLastKnownLocation(providers.get(i));
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            return best == null ? null : coords(best);
        } catch (SecurityException | IllegalArgumentException e) {
            // Revoked between the check and the read, or a provider the device
            // reported and then refused. Either way: no fix.
            return null;
        }
    }

    /**
     * Asks a provider for one current fix, calling back on the main thread
     * with {@code {latitude, longitude}} — or with {@link #lastKnown()}, or
     * null, if nothing arrives within {@value #TIMEOUT_MS}ms.
     *
     * <p>The callback runs exactly once. Nothing stays registered afterwards.
     */
    public void requestFresh(Consumer<double[]> callback) {
        if (callback == null) return;
        if (!hasPermission()) { callback.accept(null); return; }

        LocationManager lm = manager();
        String provider = bestProvider(lm);
        if (lm == null || provider == null) { callback.accept(lastKnown()); return; }

        AtomicBoolean done = new AtomicBoolean(false);
        Runnable giveUp = () -> {
            if (done.compareAndSet(false, true)) callback.accept(lastKnown());
        };
        main.postDelayed(giveUp, TIMEOUT_MS);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, new CancellationSignal(), ctx.getMainExecutor(),
                        location -> {
                            if (!done.compareAndSet(false, true)) return;
                            main.removeCallbacks(giveUp);
                            callback.accept(location == null ? lastKnown() : coords(location));
                        });
            } else {
                // 26–29. requestSingleUpdate is deprecated on R+ but is the
                // only single-shot API below it, and it unregisters itself.
                lm.requestSingleUpdate(provider, new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        if (!done.compareAndSet(false, true)) return;
                        main.removeCallbacks(giveUp);
                        callback.accept(coords(location));
                    }
                    @Override public void onStatusChanged(String p, int s, android.os.Bundle x) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                }, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException | RuntimeException e) {
            main.removeCallbacks(giveUp);
            if (done.compareAndSet(false, true)) callback.accept(lastKnown());
        }
    }

    /**
     * GPS when we hold FINE and the device has it, network otherwise. Not
     * {@code getBestProvider} with a Criteria: that can pick PASSIVE, which
     * never produces a fix on its own and would burn the whole timeout.
     */
    private String bestProvider(LocationManager lm) {
        if (lm == null) return null;
        try {
            if (hasFinePermission() && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        return null;
    }

    private LocationManager manager() {
        return (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    private static double[] coords(Location l) {
        return new double[]{l.getLatitude(), l.getLongitude()};
    }
}
```

- [ ] **Step 4: Make `WeatherRepository.refresh` two-stage**

Replace the body of `refresh` with:

```java
    public void refresh(boolean force, Runnable onUpdated) {
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < FLOOR_MS) return;
        if (!force && reading != null && now - readingAt < FRESH_MS) return;

        if (!inFlight.compareAndSet(false, true)) return;
        lastAttemptAt = now;

        // Stage one: ask a provider for a current fix. The attempt window is
        // already burned above, so this costs at most one location request
        // every ten minutes, and only when a fetch was going to happen anyway.
        location.requestFresh(fresh -> {
            double[] fix = fresh;
            if (fix == null) fix = location.lastKnown();
            if (fix != null) rememberFix(fix);
            else fix = lastRememberedFix();

            if (fix == null) {          // never had a fix; nothing to ask about
                inFlight.set(false);
                return;
            }

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
        });
    }
```

Note that `inFlight` is now claimed *before* the fix request, so a second `refresh` during the 8-second location wait is correctly rejected. Update the class javadoc's refresh-policy paragraph to add one sentence:

```
 * A fetch now begins by asking a provider for one current fix, falling back
 * to the last known fix and then to the remembered one; the 10-minute floor
 * bounds how often that can happen.
```

- [ ] **Step 5: Four decimals in `OpenMeteoWeather`**

Replace `coord`:

```java
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
```

- [ ] **Step 6: Long-press the weather line to force a refresh**

In `ClockWidget`, add the field beside `onNoWeatherApp`:

```java
    private Runnable onWeatherLongPress;
```

the setter beside `setOnNoWeatherApp`:

```java
    /** Long-press always forces a fresh reading, whether or not a weather app
     *  is installed — the tap is for opening one, and there was no gesture
     *  that simply meant "go and look again". */
    public void setOnWeatherLongPress(Runnable r) { this.onWeatherLongPress = r; }
```

and the listener beside the three `setOnClickListener` calls:

```java
        weatherView.setOnLongClickListener(v -> {
            thud();
            if (onWeatherLongPress != null) onWeatherLongPress.run();
            return true;
        });
```

`thud()` requires the `longPress()` helper; add it to `ClockWidget` alongside the `tick()` added in Task 6:

```java
    private void thud() { if (haptics != null) haptics.longPress(); }
```

- [ ] **Step 7: Request both permissions, and wire the long-press, in `HomeActivity`**

Replace `requestLocation()`:

```java
    /** Both in one prompt. The system shows a single dialog with a
     *  precise/approximate choice; granting only approximate still works,
     *  which is why LocationSource.hasPermission accepts either. */
    private void requestLocation() {
        requestPermissions(new String[]{
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }
```

Replace `hasLocationPermission()`:

```java
    private boolean hasLocationPermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }
```

Beside the existing `home.clock.setOnWeatherTap(...)` wiring in `onCreate`, add:

```java
        home.clock.setOnWeatherLongPress(() -> weatherRepository.refresh(true, this::refreshTime));
```

- [ ] **Step 8: Update the copy**

In `SetupScreen` and `SettingsPanel`, find every string describing the location permission — search for the word `COARSE`, `APPROXIMATE`, or `ROUGH` — and change it to say the fix is precise. For example a row reading `"LOCATION (APPROXIMATE)"` becomes `"LOCATION (PRECISE)"`, and a caption reading `"COARSE LOCATION FOR WEATHER"` becomes `"PRECISE LOCATION FOR LOCAL WEATHER"`. Keep the ALL CAPS monospace style and the existing line lengths.

Run first: `grep -rniE "coarse|approximate|rough" app/src/main/java/com/retro/launcher/ui/SetupScreen.java app/src/main/java/com/retro/launcher/ui/SettingsPanel.java app/src/main/res/values/strings.xml`
and edit each hit.

- [ ] **Step 9: Build**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/retro/launcher/data \
        app/src/main/java/com/retro/launcher/ui app/src/main/java/com/retro/launcher/HomeActivity.java \
        app/src/main/res
git commit -m "fix: request a precise fresh fix for weather, and declare the weather packages"
```

---

### Task 11: Pixel-art icon chain

**Files:**
- Create: `core/src/main/java/com/retro/launcher/core/IconCoverage.java`
- Create: `core/src/test/java/com/retro/launcher/core/IconCoverageTest.java`
- Create: `app/src/main/java/com/retro/launcher/icons/PixelArtIcons.java`
- Delete: `app/src/main/java/com/retro/launcher/icons/GeneratedTileIcons.java`
- Delete: `app/src/main/java/com/retro/launcher/icons/PosterizedIcons.java`
- Modify: `app/src/main/java/com/retro/launcher/icons/IconSource.java` (javadoc only)
- Modify: `app/src/main/java/com/retro/launcher/HomeActivity.java` (delete `USE_POSTERIZED_ICONS`, lines 67 and 124–127)

**Interfaces:**
- Consumes: `Quantize.nearestIndex(int argb, int[] ramp, int x, int y)` and `Palette.ramp()` (both existing); `PixelGlyphs.forPackage(String)` and `PixelGlyphs.runs(String)` (existing); `PixelTile.SIZE`, `PixelTile.runs()` (existing).
- Produces:
  - `IconCoverage.BLANK_THRESHOLD` = `0.97f` (float)
  - `IconCoverage.isBlank(int[] pixels)` → `boolean`
  - `PixelArtIcons(PackageManager pm, IconCache cache)` implementing `IconSource`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/com/retro/launcher/core/IconCoverageTest.java`:

```java
package com.retro.launcher.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class IconCoverageTest {

    private static final int OPAQUE = 0xFF336699;
    private static final int CLEAR = 0x00000000;

    private static int[] withOpaque(int total, int opaqueCount) {
        int[] pixels = new int[total];
        Arrays.fill(pixels, CLEAR);
        for (int i = 0; i < opaqueCount; i++) pixels[i] = OPAQUE;
        return pixels;
    }

    @Test public void anEntirelyTransparentRenderIsBlank() {
        assertTrue(IconCoverage.isBlank(withOpaque(576, 0)));
    }

    @Test public void aFullyOpaqueRenderIsNotBlank() {
        assertTrue(!IconCoverage.isBlank(withOpaque(576, 576)));
    }

    @Test public void aRenderJustOverTheThresholdIsBlank() {
        // 24x24 = 576. 97% transparent is 558.72, so 559 clear pixels — that
        // is 17 opaque — still reads as blank.
        assertTrue(IconCoverage.isBlank(withOpaque(576, 17)));
    }

    @Test public void aRenderJustUnderTheThresholdIsNotBlank() {
        assertFalse(IconCoverage.isBlank(withOpaque(576, 18)));
    }

    @Test public void aSingleFaintPixelIsStillBlank() {
        assertTrue(IconCoverage.isBlank(withOpaque(576, 1)));
    }

    @Test public void nullAndEmptyAreBlank() {
        assertTrue(IconCoverage.isBlank(null));
        assertTrue(IconCoverage.isBlank(new int[0]));
    }

    @Test public void nearlyTransparentPixelsCountAsTransparent() {
        int[] pixels = new int[576];
        Arrays.fill(pixels, 0x01000000);  // alpha 1 of 255
        assertTrue(IconCoverage.isBlank(pixels));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test --tests '*IconCoverageTest' --no-daemon`
Expected: compilation failure — `cannot find symbol: class IconCoverage`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/java/com/retro/launcher/core/IconCoverage.java`:

```java
package com.retro.launcher.core;

/**
 * Whether a rendered icon has anything in it.
 *
 * <p>Needed because "this app has no icon" is not an exception on Android —
 * {@code getApplicationIcon} hands back a generic placeholder rather than
 * throwing, so the only honest test is to render the thing and look. An app
 * whose icon converts to almost nothing is better served by a letter tile
 * than by a nearly-empty square.
 */
public final class IconCoverage {

    private IconCoverage() {}

    /** Above this fraction of fully transparent pixels, there is no icon
     *  worth showing. Set where a small mark — a thin glyph on a clear
     *  background — still counts as real content. */
    public static final float BLANK_THRESHOLD = 0.97f;

    /** An alpha at or below this is transparent for our purposes; anti-aliased
     *  edges leave a fringe of near-zero alpha that is not content. */
    private static final int ALPHA_FLOOR = 8;

    /**
     * True when more than {@link #BLANK_THRESHOLD} of {@code pixels} are
     * transparent. A null or empty array is blank.
     *
     * @param pixels ARGB_8888 pixels, in any layout — only the alpha channel
     *               and the count matter
     */
    public static boolean isBlank(int[] pixels) {
        if (pixels == null || pixels.length == 0) return true;
        int clear = 0;
        for (int argb : pixels) {
            if (((argb >>> 24) & 0xFF) <= ALPHA_FLOOR) clear++;
        }
        return clear > pixels.length * BLANK_THRESHOLD;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test --tests '*IconCoverageTest' --no-daemon`
Expected: `BUILD SUCCESSFUL`, 7 tests passing.

- [ ] **Step 5: Write `PixelArtIcons`**

Create `app/src/main/java/com/retro/launcher/icons/PixelArtIcons.java`:

```java
package com.retro.launcher.icons;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import com.retro.launcher.core.IconCoverage;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.PixelGlyphs;
import com.retro.launcher.core.PixelTile;
import com.retro.launcher.core.Quantize;
import com.retro.launcher.data.AppEntry;

/**
 * Every app icon in the launcher, as pixel art, in three stages evaluated per
 * app:
 *
 * <ol>
 *   <li><b>Hand-drawn mark.</b> {@link PixelGlyphs} — 16x16, palette-role
 *       coloured. Big-name apps keep their crafted marks.</li>
 *   <li><b>Converted real icon.</b> The app's own icon rendered at 24x24 and
 *       quantized through the palette's ramp with the shared Bayer bias, so
 *       icons and wallpaper speak one colour language. Upscaled
 *       nearest-neighbour, which is what keeps it pixel art rather than a
 *       blurry small icon.</li>
 *   <li><b>Letter tile.</b> Only when the app genuinely has no icon.</li>
 * </ol>
 *
 * <p>This replaces the {@code GeneratedTileIcons} / {@code PosterizedIcons}
 * either-or, which was wired behind a debug flag and made the two mutually
 * exclusive — so a hand-drawn mark and a converted real icon could never
 * appear in the same drawer. They are stages of one chain, not rivals.
 */
public final class PixelArtIcons implements IconSource {

    private static final String SOURCE = "pixart";

    /** The conversion resolution. Coarse enough to read as pixel art at any
     *  size, fine enough that a logo survives it — 16 lost too much. */
    private static final int SRC = 24;

    /** An adaptive icon's centre safe zone, per the platform's own spec: the
     *  logo occupies 72 of the 108 units, the rest is a background plate that
     *  would otherwise fill the whole converted square with one flat colour. */
    private static final float SAFE_ZONE = 72f / 108f;

    private final PackageManager pm;
    private final IconCache cache;

    public PixelArtIcons(PackageManager pm, IconCache cache) {
        this.pm = pm;
        this.cache = cache;
    }

    @Override public Bitmap iconFor(AppEntry app, Palette palette, int sizePx) {
        String key = IconCache.key(app.component(), palette.id, palette.dark, SOURCE, sizePx);
        Bitmap cached = cache.get(key);
        if (cached != null) return cached;

        Bitmap bmp = render(app, palette, sizePx);
        cache.put(key, bmp);
        return bmp;
    }

    private Bitmap render(AppEntry app, Palette palette, int sizePx) {
        // Stage 1.
        String mark = PixelGlyphs.forPackage(app.packageName);
        if (mark != null) return drawMark(mark, palette, sizePx);

        // Stage 2.
        Bitmap converted = convertRealIcon(app, palette, sizePx);
        if (converted != null) return converted;

        // Stage 3.
        return drawLetterTile(app.firstLetter(), palette, sizePx);
    }

    // ---- stage 1: hand-drawn marks ---------------------------------------

    /** Marks are pixel art: no antialiasing, and every rect snapped to the
     *  16x16 grid, or the edges turn to mush at small icon sizes. */
    private static Bitmap drawMark(String mark, Palette palette, int sizePx) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float scale = sizePx / (float) PixelTile.SIZE;
        Paint paint = new Paint();
        paint.setAntiAlias(false);
        for (int[] run : PixelGlyphs.runs(mark)) {
            int row = run[0], start = run[1], end = run[2];
            paint.setColor(colorFor((char) run[3], palette));
            canvas.drawRect(Math.round(start * scale), Math.round(row * scale),
                    Math.round((end + 1) * scale), Math.round((row + 1) * scale), paint);
        }
        return bmp;
    }

    private static int colorFor(char role, Palette palette) {
        switch (role) {
            case PixelGlyphs.ROLE_PRIMARY:   return palette.p;
            case PixelGlyphs.ROLE_ACCENT:    return palette.a;
            case PixelGlyphs.ROLE_SHADE:     return palette.s;
            case PixelGlyphs.ROLE_HIGHLIGHT: return palette.h;
            case PixelGlyphs.ROLE_TILE:
            default:                         return palette.tile;
        }
    }

    // ---- stage 2: the real icon, converted --------------------------------

    /** @return the converted icon, or null when the app has no real icon —
     *          which is a rendered test, not an exception check, because the
     *          platform hands back a placeholder rather than throwing. */
    private Bitmap convertRealIcon(AppEntry app, Palette palette, int sizePx) {
        Drawable icon = loadIcon(app);
        if (icon == null || isPlatformDefault(icon)) return null;

        Bitmap small = Bitmap.createBitmap(SRC, SRC, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        drawCropped(icon, canvas);

        int[] pixels = new int[SRC * SRC];
        small.getPixels(pixels, 0, SRC, 0, 0, SRC, SRC);
        if (IconCoverage.isBlank(pixels)) return null;

        int[] ramp = palette.ramp();
        for (int y = 0; y < SRC; y++) {
            for (int x = 0; x < SRC; x++) {
                int i = y * SRC + x;
                int argb = pixels[i];
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) continue;
                int idx = Quantize.nearestIndex(argb, ramp, x, y);
                pixels[i] = (alpha << 24) | (ramp[idx] & 0x00FFFFFF);
            }
        }
        small.setPixels(pixels, 0, SRC, 0, 0, SRC, SRC);

        // false: nearest-neighbour. Filtering here would turn pixel art back
        // into a blurry small icon, which is the whole thing we are avoiding.
        return Bitmap.createScaledBitmap(small, sizePx, sizePx, false);
    }

    /**
     * Draws {@code icon} into the canvas, cropping an adaptive icon to its
     * centre safe zone first. Without the crop the conversion sees mostly the
     * full-bleed background plate and every adaptive icon quantizes to the
     * same flat square.
     */
    private static void drawCropped(Drawable icon, Canvas canvas) {
        boolean adaptive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && icon instanceof AdaptiveIconDrawable;
        if (!adaptive) {
            icon.setBounds(0, 0, SRC, SRC);
            icon.draw(canvas);
            return;
        }
        // Draw at the inflated size the crop implies, offset so the safe zone
        // lands on the canvas.
        int inflated = Math.round(SRC / SAFE_ZONE);
        int offset = (inflated - SRC) / 2;
        icon.setBounds(new Rect(-offset, -offset, inflated - offset, inflated - offset));
        icon.draw(canvas);
    }

    private Drawable loadIcon(AppEntry app) {
        try {
            return pm.getActivityIcon(new ComponentName(app.packageName, app.activityName));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            try {
                return pm.getApplicationIcon(app.packageName);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                return null;
            }
        }
    }

    /** The framework's generic placeholder, which is what an app with no
     *  icon of its own resolves to. */
    private boolean isPlatformDefault(Drawable icon) {
        try {
            Drawable fallback = pm.getDefaultActivityIcon();
            return fallback != null
                    && fallback.getConstantState() != null
                    && fallback.getConstantState().equals(icon.getConstantState());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    // ---- stage 3: the letter tile -----------------------------------------

    private static Bitmap drawLetterTile(char letter, Palette palette, int sizePx) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float scale = sizePx / (float) PixelTile.SIZE;

        Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tilePaint.setColor(palette.tile);
        for (int[] run : PixelTile.runs()) {
            int row = run[0], start = run[1], end = run[2];
            canvas.drawRect(start * scale, row * scale, (end + 1) * scale, (row + 1) * scale, tilePaint);
        }

        Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        letterPaint.setColor(palette.p);
        letterPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        letterPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setTextSize(sizePx * 0.5f);
        float baselineOffset = (letterPaint.descent() + letterPaint.ascent()) / 2f;
        canvas.drawText(String.valueOf(letter), sizePx / 2f, sizePx / 2f - baselineOffset, letterPaint);
        return bmp;
    }

    @Override public void onPaletteChanged() { cache.evictAll(); }
}
```

- [ ] **Step 6: Delete the two old sources and rewire `HomeActivity`**

```bash
git rm app/src/main/java/com/retro/launcher/icons/GeneratedTileIcons.java \
       app/src/main/java/com/retro/launcher/icons/PosterizedIcons.java
```

In `HomeActivity`, delete line 67:

```java
    private static final boolean USE_POSTERIZED_ICONS = false;
```

(and the comment block above it, if it only explains that flag), and replace lines 124–127:

```java
        IconSource rawIcons = USE_POSTERIZED_ICONS
                ? new PosterizedIcons(getPackageManager(), iconCache)
                : new GeneratedTileIcons(iconCache);
        IconSource icons = new InstrumentedIconSource(rawIcons, USE_POSTERIZED_ICONS ? "posterized" : "generated");
```

with:

```java
        IconSource icons = new InstrumentedIconSource(
                new PixelArtIcons(getPackageManager(), iconCache), "pixart");
```

and fix the imports: delete `import com.retro.launcher.icons.GeneratedTileIcons;` and `import com.retro.launcher.icons.PosterizedIcons;`, add `import com.retro.launcher.icons.PixelArtIcons;`.

- [ ] **Step 7: Update the `IconSource` javadoc**

The current javadoc describes a debug toggle between two implementations that no longer exist. Replace it with:

```java
/**
 * How an app becomes a bitmap. One implementation —
 * {@link PixelArtIcons} — and one debug-only measuring wrapper,
 * {@link InstrumentedIconSource}. The seam stays because the drawer, the dock
 * and the search overlay all draw through it, and swapping the implementation
 * for a measurement or an experiment should not touch any of them.
 */
```

- [ ] **Step 8: Confirm nothing still references the deleted classes**

Run: `grep -rn "GeneratedTileIcons\|PosterizedIcons\|USE_POSTERIZED_ICONS" app core`
Expected: no output.

- [ ] **Step 9: Build and test**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle :core:test assembleDebug --no-daemon
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
git add -A app/src/main/java/com/retro/launcher/icons \
           core/src/main/java/com/retro/launcher/core/IconCoverage.java \
           core/src/test/java/com/retro/launcher/core/IconCoverageTest.java \
           app/src/main/java/com/retro/launcher/HomeActivity.java
git commit -m "feat: three-stage pixel-art icon chain replacing the posterized/generated toggle"
```

---

### Task 12: Full verification

**Files:** none modified. The deliverable is evidence.

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Clean full build and the whole test suite**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle clean :core:test assembleDebug --no-daemon
```
Expected: `BUILD SUCCESSFUL`. Record the test count.

- [ ] **Step 2: Read the test report and confirm the new suites ran**

Run: `grep -l "" core/build/reports/tests/test/classes/*.html | xargs -n1 basename`
Expected: the list includes `com.retro.launcher.core.PopupPlacementTest.html`, `HapticCurveTest.html`, `ForegroundSpansTest.html`, `IconCoverageTest.html`, and `UsageMathTest.html`.

- [ ] **Step 3: Confirm the APK's version and signature**

```bash
$HOME/Library/Android/sdk/build-tools/*/aapt2 dump badging \
  app/build/outputs/apk/debug/app-debug.apk | grep -E "^package|uses-permission" | head -20
```
Expected: `versionCode='1000' versionName='1.0.0'`, and the permission list includes `android.permission.VIBRATE` and `android.permission.ACCESS_FINE_LOCATION`.

- [ ] **Step 4: Confirm the spec's deletions actually happened**

```bash
grep -rn "resolveTotal\|GeneratedTileIcons\|PosterizedIcons\|USE_POSTERIZED_ICONS\|showAsDropDown" app core
```
Expected: no output.

- [ ] **Step 5: Push and confirm CI is green**

```bash
git push origin V7
gh run watch
```
Expected: the workflow runs on the `V7` push, tests pass, and a `build-<n>` release is published with an APK whose `versionCode` is `n + 1000`.

---

## Self-review

**Spec coverage.**

| Spec section | Task |
|---|---|
| §1 long-press popup positioning | 2 (drawer), 3 (dock) |
| §2 haptics — `HapticCurve`, `Haptics`, wiring, permission, setting | 4, 5, 6 |
| §3 usage accuracy — `ForegroundSpans`, `merge`/`intersect`, `dayTotal`, `pickupsToday`, deleted `resolveTotal` | 7, 8, 9 |
| §4 weather — manifest, `LocationSource`, `WeatherRepository`, `OpenMeteoWeather`, `ClockWidget`, `HomeActivity`, copy | 10 |
| §5 pixel-art icons — chain, `IconCoverage`, deletions | 11 |
| §6 versioning and signing, `BUILD.md` | 1 |
| §Testing | 2, 4, 7, 8, 11 (unit); 12 (build + CI) |

Two spec details are deliberately altered, both recorded under "Deviations" above and both agreed before this plan was written: the dock gets a real popup rather than only a repositioned one, and `PopupPlacement` is added to `core` so §1's rules are testable. One spec detail is implemented differently from its letter: `GeneratedTileIcons` is *deleted and replaced* by `PixelArtIcons` rather than renamed, because the new class shares only two of its five methods and `git` records the rename either way.

**Type consistency.** `Haptics` is constructed as `new Haptics(Context, boolean)` in Task 5 and consumed via `setHaptics(Haptics)` in Task 6. `ForegroundSpans.Result` fields `apps` / `awake` / `pickups` are named identically in Tasks 7 and 9. `UsageMath.merge` / `intersect` signatures match between Tasks 8 and 9. `AnchoredPopup.window` / `showAt` / `trackTouchPoint` signatures match between Tasks 2 and 3. `IconCoverage.isBlank(int[])` matches between Tasks 11's test and its use in `PixelArtIcons`.
