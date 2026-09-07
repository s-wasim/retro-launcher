# Retro Launcher

A native Android home-screen launcher: four swipe-navigable panels, an
animated per-pixel sky wallpaper, live weather, and screen-time limits. Java
only, no Kotlin, no Compose, no Material, no `RecyclerView` — the framework
views and one small dependency, and nothing else. `minSdk 26`, `targetSdk 36`.

- [`HANDOFF.md`](HANDOFF.md) — the constraints and the ledger of every
  amendment to them, which the source comments cite by section.
- [`design/DESIGN_NOTES.md`](design/DESIGN_NOTES.md) — the prototype-to-native
  token audit, the sky renderer's layers, and the §9 delta list.
- [`BUILD.md`](BUILD.md) — how the APK is built, signed and published.

## Layout

```
.
├── settings.gradle, build.gradle, gradle.properties   # root Gradle config
├── core/                       # pure Java, no Android types — unit-testable
│   └── src/main/java/com/retro/launcher/core/         # sky, solar/lunar,
│       │                                                palettes, glyphs,
│       │                                                search, usage math
│       └── ../test/                                    # JUnit, runs on a bare JDK
├── app/                        # the Android module
│   ├── debug.keystore, expected-signer.txt             # see BUILD.md
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── aidl/                                       # Shizuku lock service
│       ├── java/com/retro/launcher/                    # ui/ sky/ theme/
│       │                                                 icons/ data/ lock/
│       │                                                 shade/ util/
│       └── res/                                        # one layout, one icon
├── design/                     # design source of record, not build inputs
└── .github/workflows/build.yml # CI build, signer check, release
```

Anything pure enough to test without an emulator lives in `:core`, which is a
plain `java-library`. `:app` is the only module that sees an Android type.

## Building

There is deliberately no Gradle wrapper committed (no `gradlew`, no
`gradle-wrapper.jar`) — CI installs Gradle directly via
`gradle/actions/setup-gradle`, and a binary wrapper JAR you did not watch
someone generate is a supply-chain smell. The versions are pinned in two
places that must move together: `gradle-version` in
`.github/workflows/build.yml` (8.14.5) and the AGP version in the root
`build.gradle` (8.13.2).

No local Android SDK is needed to work on this repo — CI is the reference
build environment. With a local Gradle 8.14.5+ and an Android SDK:

```bash
gradle :core:test        # unit tests, no SDK needed
gradle assembleDebug     # the APK
```

## Installing

Every push builds and publishes a signed debug APK as a GitHub release asset:

```bash
gh release download build-<run-number> --pattern '*.apk'
adb install app-debug.apk
```

Then set it as the home app: **Settings → Apps → Default apps → Home app**.
Read [`BUILD.md`](BUILD.md) before changing anything about signing or
`versionCode` — both have broken sideloaded updates before, and the failure
reads only as "App not installed".
