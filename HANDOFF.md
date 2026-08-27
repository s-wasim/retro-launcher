# Handoff: Minimal Android Launcher

**Audience:** Claude Code
**Goal:** Build a native Android launcher with the smallest practical APK, built entirely on free GitHub Actions minutes. No local Android toolchain required.

---

## 1. Non-negotiable constraints

These exist to keep the APK in the 20–80 KB range. Do not relax any of them without asking.

| Rule | Reason |
|---|---|
| **Java only, no Kotlin** | Kotlin bundles ~1.5 MB of stdlib |
| **No AndroidX, no Compose, no Material** | Each adds megabytes; the framework equivalents are enough |
| **`ListView`, not `RecyclerView`** | RecyclerView is AndroidX |
| **`minSdk 26`, `targetSdk 34`** | Lets us use framework APIs without support shims |
| **R8 full mode + `shrinkResources true`** on release | Strips unused code and resources |
| **No image assets** except the single adaptive launcher icon | Bitmaps dominate size in small apps |
| **Zero third-party dependencies** in `app/build.gradle` | The `dependencies {}` block should be empty |

**Size budget:** debug APK under 150 KB, release APK under 80 KB. Report the actual size in the build log. If a change pushes past budget, stop and flag it rather than proceeding.

---

## 2. The design handoff — read this carefully

The design arrives as a **web prototype produced by Claude Design from a Figma file**. It will live at `design/prototype/`.

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

---

## 3. Local folder structure

Create exactly this. `git init` only — **do not add a remote, do not push, do not create a repo.** The user will wire up the remote themselves later.

```
minimal-launcher/
├── .gitignore
├── README.md
├── HANDOFF.md                      ← this file
├── settings.gradle
├── build.gradle
├── gradle.properties
│
├── .github/
│   └── workflows/
│       └── build.yml
│
├── design/
│   ├── DESIGN_NOTES.md             ← you write this; token extraction table
│   └── prototype/                  ← Claude Design output drops here; READ ONLY
│
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/
        └── main/
            ├── AndroidManifest.xml
            ├── java/
            │   └── com/minimal/launcher/
            │       ├── HomeActivity.java
            │       └── AppEntry.java
            └── res/
                ├── layout/
                │   ├── activity_home.xml
                │   └── row_app.xml
                ├── values/
                │   ├── colors.xml
                │   ├── strings.xml
                │   └── styles.xml
                └── xml/
                    └── (only if needed)
```

### Note on the Gradle wrapper

There is deliberately **no `gradlew` / `gradle-wrapper.jar`** in this tree. The wrapper JAR is a binary that can't be authored as text, and committing one you didn't generate is a supply-chain smell.

Instead, CI installs Gradle directly via `gradle/actions/setup-gradle`. If you happen to have Gradle available locally, you may run `gradle wrapper --gradle-version 8.7` and commit the result — but the workflow below must keep working either way.

---

## 4. File contents

### `.gitignore`
```
.gradle/
build/
local.properties
*.apk
*.iml
.idea/
.DS_Store
```

### `settings.gradle`
```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MinimalLauncher"
include ':app'
```

### `build.gradle` (root)
```groovy
plugins {
    id 'com.android.application' version '8.5.2' apply false
}
```

### `gradle.properties`
```
org.gradle.jvmargs=-Xmx2048m
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=false
android.enableR8.fullMode=true
```

`android.useAndroidX=false` is load-bearing — it makes the build fail loudly if anything drags in an AndroidX dependency.

### `app/build.gradle`
```groovy
plugins { id 'com.android.application' }

android {
    namespace 'com.minimal.launcher'
    compileSdk 34

    defaultConfig {
        applicationId "com.minimal.launcher"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "0.1"
        vectorDrawables.useSupportLibrary false
    }

    buildTypes {
        debug {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'),
                          'proguard-rules.pro'
        }
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'),
                          'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    // Keep the APK to one language and one density
    androidResources {
        localeFilters += ['en']
    }

    dependencies { }   // stays empty — see constraints
}
```

Shrinking is enabled on `debug` too, deliberately: the debug APK is what gets installed on the phone, so it needs to be the small one. Debug builds are auto-signed with the local debug keystore, which is fine for personal sideloading.

### `AndroidManifest.xml`

Must include:
- `<queries>` with an intent for `ACTION_MAIN` + `CATEGORY_LAUNCHER` — **required on API 30+**, without it `queryIntentActivities` returns an empty list and the launcher appears broken with no error.
- The home intent filter: `ACTION_MAIN` + `CATEGORY_HOME` + `CATEGORY_DEFAULT` + `CATEGORY_LAUNCHER`.
- `android:launchMode="singleTask"`, `android:stateNotNeeded="true"`, `android:excludeFromRecents="true"` on the activity.
- No `<uses-permission>` entries at all.

### `HomeActivity.java`

Behavior:
1. `getPackageManager().queryIntentActivities()` for MAIN/LAUNCHER.
2. Map to `AppEntry` records (label + package + activity name), sort by label case-insensitively.
3. Bind to a `ListView` via a `BaseAdapter` with a proper view holder.
4. On item click, launch via a component-name intent with `FLAG_ACTIVITY_NEW_TASK`.
5. On long-press, open the system app-info screen (`ACTION_APPLICATION_DETAILS_SETTINGS`).
6. Override `onBackPressed()` to do nothing — back must not exit the home screen.
7. Refresh the list in `onResume()` so installs and uninstalls show up.

Keep it to two files. No abstraction layers, no interfaces, no DI.

---

## 5. GitHub Actions workflow

`.github/workflows/build.yml`:

```yaml
name: Build APK

on:
  push:
    branches: [main]
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
          gradle-version: '8.7'

      - name: Build
        run: gradle assembleDebug --no-daemon

      - name: Report APK size
        run: ls -lh app/build/outputs/apk/debug/*.apk

      - name: Publish release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release create "build-${{ github.run_number }}" \
            app/build/outputs/apk/debug/app-debug.apk \
            --title "Build ${{ github.run_number }}" \
            --notes "Automated build from ${{ github.sha }}"
```

Why these choices:
- **Release asset, not artifact.** Artifacts download as a ZIP, which Android can't install directly. A release asset is a tappable `.apk` URL on the phone.
- **`gh` CLI instead of a third-party release action.** It's preinstalled on the runner; one less dependency to trust or pin.
- **`permissions: contents: write`** is required or `gh release create` fails with a 403.
- **`concurrency` + push-to-main only** keeps minute usage down.

**Free-tier note for the user:** if the repo is public, Actions minutes are unlimited. If private, the free plan gives 2,000 minutes/month and `ubuntu-latest` bills at 1×. These builds run roughly 2–3 minutes, so even a private repo allows hundreds of builds per month.

---

## 6. Order of work

1. Scaffold the full tree with placeholder styling, plus a `README.md` covering local structure, the CI flow, and phone-side install steps.
2. `git init`, commit. **Stop there — no remote.**
3. Wait for `design/prototype/` to be populated.
4. Extract tokens into `design/DESIGN_NOTES.md`.
5. Apply tokens to `colors.xml`, `styles.xml`, and the two layouts.
6. Commit again as a separate "apply design" commit so the design pass is reviewable in isolation.

Do not attempt to compile locally. If Gradle or the Android SDK is absent, that is expected — CI is the build environment. Verify what you can statically: XML well-formedness, Java syntax, manifest completeness.

---

## 7. Definition of done

- [ ] Tree matches section 3 exactly
- [ ] `dependencies {}` in `app/build.gradle` is empty
- [ ] `<queries>` block present in the manifest
- [ ] Back button does not exit the launcher
- [ ] List refreshes on resume
- [ ] No WebView anywhere in the project
- [ ] No font files in `res/`
- [ ] `design/DESIGN_NOTES.md` maps every prototype token to its native equivalent, with substitutions flagged
- [ ] Local git repo initialized, committed, **no remote configured**
- [ ] `README.md` explains how to add a remote and get the first build

---

## 8. Open questions to raise, not guess

If any of these are unspecified when you reach them, ask rather than deciding:
- Does the design show a search field, or a plain scrolling list?
- Are there pinned favorites on the home screen, separate from the full app list?
- Are app icons shown, or is it text-only? (Text-only is smaller and faster; icons are loaded from the system, so they cost no APK size but do cost memory and scroll performance.)
- Is there a wallpaper, or a solid background color?