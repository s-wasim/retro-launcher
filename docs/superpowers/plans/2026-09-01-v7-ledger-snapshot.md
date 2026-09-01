# SDD ledger — plan: docs/superpowers/plans/2026-09-01-v7-launcher-fixes.md

Spec: docs/superpowers/specs/2026-09-01-v7-launcher-fixes-design.md (read)
Branch: V7 (isolated from main; merge-base 02e53bb). No worktree — the plan
and spec are committed on V7 and the user directed the work here.
Toolchain verified before start: `:core:test assembleDebug` BUILD SUCCESSFUL.

## Pre-flight scan

### Shared-file / shared-interface pairs

| Tasks | Shared surface | Producer → consumer | Finding |
|---|---|---|---|
| 2 → 3 | `AnchoredPopup` | T2 produces `window`/`showAt`/`trackTouchPoint`; T3 consumes all three | clean — signatures identical in both task texts |
| 4 → 5 | `HapticCurve` | T4 produces `BUCKETS`/`bucket`/`amplitudeForBucket`; T5 consumes | clean |
| 5 → 6 | `Haptics` | T5 produces ctor `(Context, boolean)` + `click`/`longPress`; T6 consumes via `setHaptics` | clean |
| 7 → 9 | `ForegroundSpans` | T7 produces `Event`/`Result{apps,awake,pickups}`/`scan`; T9 consumes | clean — field names match |
| 8 → 9 | `UsageMath` | T8 produces `merge`/`intersect`, deletes `resolveTotal`; T9 consumes both and drops the last `resolveTotal` caller | **CONFLICT** — T8 Step 5's grep expectation contradicts itself (see R1) |
| 2, 3, 6 | `DrawerPanel.java` | T2 adds touch tracking; T3 reads `SIZE_TAB_*`; T6 adds tick/thud | **STALE** — T3 hedges on a visibility change that is already done (R4) |
| 3, 6 | `DockView.java` | T3 adds `actionRow`+`showSlotActions`; T6 ticks inside `actionRow` | clean — T6 explicitly defers to T3's `actionRow` |
| 5, 6, 10 | `SettingsPanel.java` | T5 adds FEEDBACK section; T6 ticks rows; T10 edits copy | clean — disjoint regions |
| 6, 10 | `ClockWidget.java` | T6 adds `tick()` only, no `thud()`; T10 adds `thud()` + long-press | clean — T10 runs after T6 and adds it explicitly. **Reviewers of T6 must not flag the absent `thud()`.** |
| 5, 10 | `AndroidManifest.xml` | T5 adds VIBRATE; T10 adds FINE_LOCATION + `<package>` queries | clean — disjoint elements |
| 3, 5, 6, 10, 11 | `HomeActivity.java` | five tasks touch it | clean — each edits a distinct method; no two rewrite the same block |
| 11 | `IconCoverage` → `PixelArtIcons` | produced and consumed inside T11 | clean |

### Per-task internal consistency

| Task | Own text agrees with itself? |
|---|---|
| 1 | yes — keystore generated before `signingConfigs` references it; verification greps match what was written |
| 2 | yes — 7 test cases match the 4 placement rules; `PopupPlacement` signature identical in test and impl |
| 3 | **no** — see R4 |
| 4 | yes — 9 test cases match the constants and the squared ramp |
| 5 | yes — but see R3: Step 5's view names were unverified prose |
| 6 | **no** — see R3: named `home.coffee`, `limitSlider`, `setScrubberHaptics`; none of those exist |
| 7 | yes — 16 test cases cover all 8 event constants and both end-of-scan branches |
| 8 | **no** — see R1 |
| 9 | **no** — see R2: Step 2's grep is too noisy to have a checkable expectation |
| 10 | yes — the 9 `<package>` names match `ClockWidget.WEATHER_PACKAGES` exactly (verified by grep) |
| 11 | yes — 7 test cases; `AppEntry.firstLetter()` and `Quantize.nearestIndex` confirmed to exist |
| 12 | **no** — see R5: Step 5 pushed to a branch that publishes a public release |

## Pre-flight rulings

Ruling R1: Task 8 Step 5's "Expected: no output" contradicted its own
parenthetical. Corrected to "exactly one hit — UsageRepository.dayTotal",
with any hit inside core/ failing the task. — Why: the step as written could
not be passed or failed. — Cost if wrong: none; it is a verification
expectation, not behaviour.

Ruling R2: Task 9 Step 2's grep (`SCREEN\b|\.screen\b|Scan(`) matches
`ScreenTimePanel`, `screenTime` and more, so "no hits" was unachievable.
Replaced with two precise greps. — Why: an unpassable check gets skipped, and
a skipped check is worse than none. — Cost if wrong: none; the real safety
net is the compile in Step 3.

Ruling R3: Task 6 Step 5 named `home.coffee`, `screenTime.limitSlider`,
`drawer.setScrubberHaptics` — none exist. Verified against source:
`CoffeeButton` and `LimitSlider` are private children of `ScreenTimePanel`
(fields `coffee`, `slider`); `AlphaScrubber` is `DrawerPanel.scrubber`.
Rewrote Step 5 so the two parents forward via their own `setHaptics`, added
`ScreenTimePanel` to the task's file list, and replaced the "if the class
tracks a lastReported" guesswork with the actual code: `LimitSlider.setValue`
already gates on `changed`; `AlphaScrubber.fireLetterAt` does not gate at all
and needs a `lastLetter` field. — Why: the plan told the implementer to guess
at names I could verify in one grep. — Cost if wrong: an implementer wires a
haptic to the wrong view; caught at compile time.

Ruling R4: Task 3 Step 3 hedged "if SIZE_TAB_* are not public, make them
public". They are already `public static final` (DrawerPanel.java:52).
Replaced the hedge with the fact. — Why: a conditional instruction invites an
unnecessary edit to a shared file. — Cost if wrong: none.

Ruling R5: Task 12 Step 5 ran `git push origin V7`, which triggers the
workflow and publishes a public GitHub release with an APK. Removed from the
plan; Task 12 now stops at a green local build and reports. — Why: publishing
a release is an outward-facing side effect the user has not authorised for
this session. Their CI-trigger choice authorised the workflow config, not a
push. — Cost if wrong: the user runs one command themselves.

Ruling R6 (recorded, no change): Task 6 says `ClockWidget` gets `tick()` and
no `thud()`; Task 10 Step 6 adds `thud()` to it. Not a conflict — T10 runs
after T6. Recorded so a T6 reviewer does not flag the absent helper as an
omission.

## Progress

Task 1: dispatched (t1-impl, sonnet) — BASE 80a829d — briefs for tasks 1-12 pre-generated
Ruling R7: A stale background job from the pre-plan session finished mid-Task-1
  and created duplicate SDK dirs (platform-tools-2, platforms/android-36-2,
  build-tools/36.0.0-2). Removed the three duplicates; verified platform-tools/adb,
  platforms/android-36/android.jar and build-tools/36.0.0 intact, local.properties
  unchanged, gradle@8 = 8.14.5 (matches the CI pin exactly). Also patched the plan's
  `build-tools/*/apksigner` glob to the explicit `build-tools/36.0.0/` path — the SDK
  legitimately holds both 35.0.0 and 36.0.0, so the glob expanded to two paths and
  would have failed Task 1 Step 6 and Task 12 Step 3. Messaged t1-impl mid-flight.
  — Why: unreferenced 171MB of duplicates, and a verification command that cannot run.
  — Cost if wrong: none; the removed dirs were byte-identical re-downloads.
Task 1: implementer DONE (de5fb11) — :core:test + assembleDebug green, versionCode=1000,
  versionName=1.0.0, cert CN=Android Debug. Commit scoped correctly (4 files, no
  local.properties). Review package review-80a829d..de5fb11.diff; t1-review dispatched.
  Note: implementer verified against build-tools/36.0.0-2, which I deleted as a duplicate
  (R7) — reviewer instructed to re-verify independently against 36.0.0.
Task 1: complete (commits 80a829d..de5fb11, review clean — spec PASS, quality Approved,
  reviewer independently re-verified apksigner/aapt2/keytool; keystore SHA-256 84:19:79:…
  matches the APK signer digest 841979667c83…)

Ruling R8: Task 3 attached AnchoredPopup.trackTouchPoint to the DockView itself.
  A ViewGroup's OnTouchListener only fires for events that reach the group, and each
  dock slot has its own OnClickListener that consumes ACTION_DOWN — so the point would
  have stayed {-1,-1} forever and every dock popup would silently fall back to the
  anchor corner, reproducing the exact bug the task fixes. Patched Task 3 to attach the
  tracker per slot inside buildSlot and pass the array into showSlotActions.
  Verified this does NOT affect Task 2: DrawerPanel's rows have no own click listeners
  (row taps go through setOnItemClickListener, which AbsListView dispatches), and
  LauncherRoot.setNoSwipe/setVerticalScroller use view TAGS, not touch listeners, so
  nothing is clobbered on either surface.
  — Why: a silent no-op that passes compile and review-by-reading.
  — Cost if wrong: dock popups anchor at the slot corner instead of the finger.
Task 2: dispatched (t2-impl, sonnet) — BASE 33e7382
Pre-check for Task 5 (done early, no change needed): all six seize() call sites are
  inside drag() (LauncherRoot.java:351,352,360,365,372,377) and nowhere else, so hooking
  dragStart() into seize() fires only during a real drag — no spurious buzz from settle
  animations or goTo(). The VIEW_HOME horizontal branch calls seize() twice in one frame;
  the dragBuzzing guard collapses that to one dragStart(). Design confirmed sound.

Ruling R9: t2-impl reported changing a TEST EXPECTATION to match the implementation —
  normally the wrong move, so I verified the arithmetic independently rather than
  accepting it. The implementer is right and my plan was wrong: for
  place(900,400, w=410,h=300, screen 1080x2400, insets l40/t60/r40/b130), rule 3 gives
  x = (1080-40) - 410 = 630. My brief asserted 600 — a plain arithmetic slip on my part.
  The algorithm is untouched and still matches the spec's rule ("shift left so the right
  edge is inset-aligned"); the other 6 cases and the second assertion in the same test
  are unaffected. Accepted, and the implementer added the derivation as a comment.
  — Cost if wrong: none; verified by hand and by python.
Ruling R10: t2-impl also fixed `View.WINDOW_SERVICE` -> `Context.WINDOW_SERVICE` in my
  AnchoredPopup code block. Confirmed: View has no WINDOW_SERVICE constant; the brief's
  block would not have compiled. Accepted. It used the fully-qualified name inline rather
  than adding an import — cosmetic, left to the reviewer to raise if it cares.
Verified independently: PopupPlacement.java has zero imports (Android-free, as required).
Pre-check (API surface, done early against platforms/android-36/android.jar via javap):
  every API the later tasks call exists in SDK 36 and compiles as written —
   Task 5:  VibrationEffect.createPredefined / createOneShot(long,int) /
            createWaveform(long[],int[],int) AND createWaveform(long[],int); EFFECT_CLICK;
            EFFECT_HEAVY_CLICK; Vibrator.hasAmplitudeControl/hasVibrator/cancel. All present.
   Task 10: LocationManager.getCurrentLocation(String,CancellationSignal,Executor,
            Consumer<Location>) — the exact 4-arg overload the plan uses — and
            requestSingleUpdate(String,LocationListener,Looper), still present though
            deprecated (build does not use -Werror; a deprecation note already appears in
            the current green build). LocationListener's onStatusChanged/onProviderEnabled/
            onProviderDisabled are `default` methods in 36, so the plan's @Override stubs
            are legal but optional.
   Task 11: PackageManager.getDefaultActivityIcon() is public API; AdaptiveIconDrawable
            present. Both fine.
  No plan changes needed — recorded so no implementer burns a 6-minute build discovering it.
Task 2: complete (commits 33e7382..4262124, review clean — spec PASS, quality Approved,
  7/7 PopupPlacementTest, reviewer confirmed the pre-R displayBounds fallback against
  HomeActivity's actual setDecorFitsSystemWindows(false) edge-to-edge setup)

Ruling R11: Task 3 said to update "both setOnSlotActionListener blocks (lines 148 and
  169)". Wrong — there is exactly ONE DockView.SlotActionListener (line ~169). The block
  at line ~147 has an identical two-line body but implements
  SettingsPanel.DockActionListener, a DIFFERENT interface not changing in this task.
  Adding onRemove/onAppInfo to it fails to compile ("does not override a method from a
  supertype"). Patched Task 3 to name the single correct site and to warn explicitly
  about its look-alike. Also verified and recorded the import situation: Intent (12),
  Settings (22), ArrayList (59), List (61) are already imported; android.net.Uri is not,
  so the plan's fully-qualified Uri call stands.
  — Why: two anonymous classes with byte-identical bodies, one line apart in the file.
  — Cost if wrong: hard compile failure, so it would have been caught — but only after a
    wasted dispatch and build cycle.

Ruling R12: Enumerated all 31 click/long-click/toggle listener sites across the 10 UI
  files and replaced Task 6's prose ("every setOnClickListener on a palette card, chip,
  dock row or permission row") with an exact file+line+quoted-listener table. Doing so
  exposed a DOUBLE-TICK bug: LimitSlider's minus/plus buttons (lines 80-81) call
  setValue(), which is exactly where Step 4 puts the detent tick — so ticking the buttons
  too would fire the vibrator twice per press. Task 6 now explicitly excludes them and
  says why. Also ruled that close buttons and scrim-dismiss taps DO tick: spec §2's
  governing clause is "every interactive listener" and its list is illustrative, and
  partial haptics feel broken in a way uniform silence does not.
  — Why: a 31-site mechanical task described in prose is where an implementer silently
    misses five sites and no reviewer can tell.
  — Cost if wrong: a double buzz on one control, or a few silent taps.
Task 3: implementer DONE (e3dad7c). Independently confirmed the 7s build was real, not a
  no-op: APK timestamp fresh, and the COMPILED DockView$SlotActionListener carries all
  four methods (onReplace/onRemove/onAppInfo/onAdd). Review package
  review-8d19d87..e3dad7c.diff; t3-review dispatched.

Pre-check (Task 7 event constants, verified against the real
  android.app.usage.UsageEvents$Event in platforms/android-36/android.jar via
  `javap -constants`): all eight values I hardcoded into core/ForegroundSpans are correct —
    ACTIVITY_RESUMED=1 (== MOVE_TO_FOREGROUND=1)
    ACTIVITY_PAUSED=2  (== MOVE_TO_BACKGROUND=2)
    SCREEN_INTERACTIVE=15   SCREEN_NON_INTERACTIVE=16
    KEYGUARD_SHOWN=17       KEYGUARD_HIDDEN=18
    ACTIVITY_STOPPED=23     DEVICE_SHUTDOWN=26
  This mattered more than the other API checks: ForegroundSpans is Android-free by design,
  so its constants are copies. A wrong copy would produce a silently wrong screen-time
  number that NO unit test could catch, because the tests use the same constants. Verified
  against the platform rather than trusted. No change needed.
Task 3: complete (commits 8d19d87..e3dad7c, review clean — spec PASS, quality Approved;
  reviewer confirmed R8 landed (trackTouchPoint on `col` at DockView.java:131, no field on
  DockView) and that the SettingsPanel.DockActionListener look-alike is byte-for-byte
  untouched (R11). Index-based removeDockSlot adjudicated correct: slot indices come from
  iteration order over the same List that setEntries() rebuilds from.)
Task 4: dispatched (t4-impl, haiku — pure transcription + TDD, 2 files) — BASE e3dad7c

Ruling R13: Task 5 named the new settings section builder `buildFeedbackSection` and told
  the implementer to "find whichever method rebuilds every section on a palette change".
  SettingsPanel has a strict convention I should have matched: every section has a
  `rebuildXSection()` (rebuildPaletteSection 234, rebuildClockSection 328,
  rebuildTempSection 477, rebuildDockSection 507, rebuildPermissionsSection 589), all
  dispatched from rebuildAll() at line 224, which setPalette() calls. Renamed to
  `rebuildFeedbackSection` and replaced the "find whichever method" hand-wave with the
  literal rebuildAll() body to write, with FEEDBACK inserted between temp and dock to
  match its layout position.
  — Why: a vague instruction plus an off-convention name is how a section silently fails
    to re-colour on a palette change — visible only when the user switches palettes.
  — Cost if wrong: the FEEDBACK toggle keeps stale colours until the panel is rebuilt.
Task 4: implementer DONE (9d5ed0b) — 9/9 tests, no imports in HapticCurve, TDD order
  confirmed from the report (saw "cannot find symbol: class HapticCurve" before writing
  the impl). Review package review-f45fca6..9d5ed0b.diff; t4-review dispatched (told to
  judge the arithmetic independently, since tests+impl came from the same brief).

Ruling R14: Task 10 Step 8 told the implementer to grep for coarse/approximate/rough and
  "edit each hit". I ran it. Exactly ONE user-facing string claims a coarse fix
  (SetupScreen:63) — every other hit is an unrelated comment about touch handling or clock
  packages, and blindly editing them would corrupt working code. strings.xml has no
  location copy at all; this app inlines every user-facing string. Replaced the step with
  three exact edits (SetupScreen:63 copy, SetupScreen:17 javadoc, SettingsPanel:630
  caption) and an explicit leave-alone list for the three row labels/state strings, whose
  lengths are load-bearing for layout.
  — Why: "edit each hit" over a grep with mostly false positives invites damage to
    unrelated code, and the reviewer would have no way to tell intent from accident.
  — Cost if wrong: a stale or over-long caption; cosmetic.
Task 4: complete (commits f45fca6..9d5ed0b, review clean — spec PASS, quality Approved.
  Reviewer verified the arithmetic independently rather than trusting green tests:
  bucket(1.0f)->7 not 8; clamp01 tests isNaN BEFORE any comparison (avoids the
  NaN-comparisons-are-false trap); amplitudeForBucket(0)==40 and (7)==255 exactly, t=7/7f
  being exact in float so no rounding drift. Full 20-class core suite green.)
Task 5: dispatched (t5-impl, sonnet — 6 files, integration) — BASE 6e24fcc
Task 5: implementer DONE (1d6a2ba, 6 files, +240). My own structural checks before review
  all pass: VIBRATE at manifest:59; endDragBuzz reachable from all three abandonment paths
  (LauncherRoot:279 ACTION_UP/CANCEL, :456 onDetachedFromWindow, HomeActivity:666 onPause);
  exactly one onPause in HomeActivity (no duplicate declared); rebuildFeedbackSection at
  :519 registered in rebuildAll at :236 (R13 honoured); APK rebuilt 68s before checking.
Task 5: minor (deferred): brief's Step 6 spelled the listener as fully-qualified
  java.util.function.Consumer<Boolean> though SettingsPanel already imports Consumer and
  uses the bare form in toggleRow. My brief's inconsistency, not the implementer's — it
  correctly followed the text and REPORTED the discrepancy instead of silently deviating,
  which is the behaviour I want. Cosmetic; carried to the final whole-branch review to
  triage rather than spending a fix round on two words.

Ruling R15: The R12 haptic call-site table was captured at 4262124, but Task 3 (+69 lines
  in DockView) and Task 5 (+24 in SettingsPanel) shifted it. Re-verified all 31 sites at
  1d6a2ba and corrected 12 stale line numbers: DockView 122->128, 144->153, and the Task 3
  actionRow now has a concrete line (206) rather than a "(Task 3)" placeholder;
  SettingsPanel 155->158, 319->328, 391->400, 430->439, 460->469, 579->603, 698->722,
  750->774, 771->795. DrawerPanel/BottomSheet/SearchOverlay/ClockWidget/LimitSlider/
  CoffeeButton/ScreenTimePanel were unaffected. Also added a standing instruction that the
  QUOTED LISTENER TEXT is authoritative over the line number, so future drift degrades to
  a search rather than a wrong edit.
  — Why: a line-number table in a plan goes stale the moment an earlier task edits the
    same file; handing an implementer stale numbers for a 31-site mechanical task is how
    the wrong lines get edited.
  — Cost if wrong: an implementer edits an unrelated listener; caught at review.
Task 5: complete (commits 6e24fcc..1d6a2ba, review clean — spec PASS, quality Approved,
  no Task 6 scope creep. Reviewer traced all four drag branches (HOME-h, SETTINGS, DRAWER,
  HOME-v/TIME) and confirmed progress runs monotonically 0->1 in each; confirmed
  dragStart's applyDragBucket(0) leaves dragBucket==0 so an immediate dragProgress(0f)
  does NOT re-command; confirmed the no-amplitude path uses createWaveform(timings,
  repeat=0) which genuinely loops rather than firing once.)

Ruling R16: Task 6's AlphaScrubber step said "Reset lastLetter = 0; on ACTION_DOWN in
  onTouchEvent". Not directly possible — ACTION_DOWN and ACTION_MOVE share a single
  fallthrough case (AlphaScrubber.java:81-83), so there is no ACTION_DOWN branch to add a
  line to. Replaced the instruction with the full rewritten onTouchEvent that splits the
  case. Without this the implementer either invents its own split (unreviewable variance)
  or drops the reset, which would break re-pressing the letter a drag ended on.
  — Cost if wrong: scrubber ignores a tap on the last-used letter.
Task 6: dispatched (t6-impl, sonnet — 31 sites across 10 files) — BASE d2fda44

Pre-check (Task 9 blast radius, done early): Task 9 rewrites UsageRepository wholesale on
  the claim its public surface is unchanged. Verified every external reference:
    HomeActivity:625-628 -> todayMillis, last7DaysMillis, pickupsToday, mostUsedToday
    ScreenTimePanel:61,266,353,369,376 -> UsageRepository.AppUsage (.pkg/.millis only)
  Nothing outside the class touches the private Scan type, the SCREEN pseudo-package, or
  dayTotal. So the rewrite is safe provided it keeps those four signatures and AppUsage's
  two public fields. Also noted: mostUsedToday returns out.subList(0,limit), a VIEW backed
  by the ArrayList, and ScreenTimePanel stores it as a field — pre-existing behaviour that
  the plan's rewrite preserves unchanged; flagging so no reviewer mistakes it for new.
Task 6: implementer reported DONE (f76efe0) "31/31 sites" — but my own verification found
  Step 3 NOT DONE. thud() helpers are declared in 5 files yet called ZERO times anywhere.
  All three long-press surfaces are silent:
    DrawerPanel:139 listView.setOnItemLongClickListener  — no thud()
    DrawerPanel:259 chip.setOnLongClickListener (category tab) — no thud()
    DockView:142    col.setOnLongClickListener — no thud()
  The implementer counted only Step 2's 31-row tick table and treated that as the whole
  task. Spec verdict would be ❌. Entering fix loop round 1 (resume original implementer).
  Secondary: thud() is declared but unused in BottomSheet:34, SettingsPanel:48,
  SearchOverlay:50 — dead code my own Step 1 wording invited ("add only tick() there, so no
  dead method is introduced" named only 4 files to skip, implying thud() in the other 6,
  but only 3 sites actually long-press).
Task 6: fix round 1/5 (2 addressed, 0 open — Step 3 thud() at the 3 long-press surfaces;
  dead thud() declarations removed from BottomSheet/SettingsPanel/SearchOverlay;
  commits f76efe0..6edab79). Verified myself: exactly 3 thud() call sites
  (DrawerPanel:140, DrawerPanel:260, DockView:143), exactly 2 declarations
  (DrawerPanel:56, DockView:41), 31 tick() calls intact.
  Task 6 has not yet had a task review — I found the Step 3 gap structurally before
  dispatching one — so the review now covers the FULL task range d2fda44..6edab79 rather
  than being a scoped re-review of the fix.
Task 6: accepted deviation: SettingsPanel.toggleRow's lambda param renamed checked ->
  isChecked. My brief's lambda shadowed toggleRow's own `checked` parameter and would not
  compile. Implementer's fix is correct and minimal.

Ruling R17: Task 8 told the implementer to "add import static org.junit.Assert.assertTrue
  if not already imported". UsageMathTest already has the WILDCARD
  `import static org.junit.Assert.*;` (line 4), so that would add a redundant import the
  reviewer would then flag. My added test code was also fully-qualified
  (java.util.Arrays.asList, java.util.List<...>) against imports the file already has
  (Arrays, List, Calendar, TimeZone) — noise inconsistent with the surrounding style.
  De-qualified all of it and specified the ONE genuinely missing import
  (java.util.ArrayList). Separately verified the delete range: the three resolveTotal
  tests are exactly lines 152-165, and 166 is the class's closing brace — so the plan's
  "delete 152-165" is correct as written, now with an explicit warning not to eat line 166.
  — Cost if wrong: a redundant import or a deleted closing brace; both caught at compile.
Task 6: complete (commits d2fda44..6edab79, 1 fix round, review clean — spec PASS, quality
  Approved. Reviewer walked all 31 table rows individually and named each line; confirmed
  no setOnLongClickListener lost its return during lambda conversion; confirmed the
  AlphaScrubber lastLetter gate is a fix not a regression (setSelection was previously
  re-fired on every pixel within one letter's band).)
Task 7: NOT DISPATCHED. Brief was generated (task-7-brief.md) but no implementer was
  sent — user halted implementation here. BASE for Task 7 when resumed: 73e25f4.

=== CHECKPOINT: user halted after Task 6. Tasks 7-12 remain. ===
