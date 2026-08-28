# How the APK gets built

This project never compiles locally — there is no Android SDK or Gradle
wrapper on this machine, and none is needed. CI is the only build
environment. Here's the path from "design applied" to "APK on your phone."

## 1. Trigger

Once the design pass is committed (tokens extracted into
`design/DESIGN_NOTES.md`, then applied to `colors.xml`, `styles.xml`, and
the two layouts), pushing that commit to `main` fires
`.github/workflows/build.yml` automatically. It can also be run manually
with `gh workflow run build.yml`.

## 2. What CI does

1. Checks out the repo on a fresh `ubuntu-latest` runner.
2. Installs JDK 17 (Temurin) and Gradle 8.7 — no wrapper needed, since the
   workflow installs Gradle directly via `gradle/actions/setup-gradle`.
3. Runs `gradle assembleDebug --no-daemon`.
   - `minifyEnabled true` + `shrinkResources true` + R8 full mode strip
     unused code/resources on this build type (debug is intentionally
     shrunk too, since it's the one that gets sideloaded).
   - `dependencies {}` in `app/build.gradle` is empty and
     `android.useAndroidX=false` is set, so the build fails loudly if
     anything accidentally pulls in AndroidX/Kotlin stdlib/Compose.
4. Prints the resulting APK's size with `ls -lh`, so every run's log states
   the number against budget (debug < 150 KB, release < 80 KB).
5. Publishes `app-debug.apk` as a **GitHub release asset** (via `gh
   release create`, tagged `build-<run-number>`) — not a workflow artifact,
   because artifacts download as a ZIP and Android can't install that
   directly; a release asset is a tappable `.apk` URL.

## 3. Getting the APK onto a phone

```bash
gh release download build-<run-number> --pattern '*.apk'
```

Transfer it to the phone (`adb install app-debug.apk`, or any file-transfer
method) and install it — "install unknown apps" needs to be allowed for
whatever app opens it, since this doesn't go through the Play Store. Then
set it as the default home app: **Settings → Apps → Default apps → Home
app**.

## 4. If a build fails or blows the size budget

The workflow doesn't enforce the size budget automatically — check the
"Report APK size" step's log against the numbers in `HANDOFF.md` §1
yourself. If a change pushed it over budget, that's a signal to revert or
rework the change rather than raise the budget.
