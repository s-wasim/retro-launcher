# Minimal Launcher

A native Android home-screen launcher built for minimum APK size: Java only,
no AndroidX/Compose/Material, zero third-party dependencies. See
[`HANDOFF.md`](HANDOFF.md) for the full rationale and constraints.

## Local structure

```
.
├── settings.gradle, build.gradle, gradle.properties   # root Gradle config
├── app/                                                # the single module
│   ├── build.gradle                                    # minSdk 26, targetSdk 34, empty deps
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/minimal/launcher/                  # HomeActivity, AppEntry
│       └── res/                                        # layouts, values, adaptive icon
├── design/
│   ├── prototype/                                      # Claude Design output lands here (read-only reference)
│   └── DESIGN_NOTES.md                                 # token extraction table, filled in once the prototype exists
└── .github/workflows/build.yml                         # CI build + release
```

There is deliberately no Gradle wrapper committed (no `gradlew` /
`gradle-wrapper.jar`) — CI installs Gradle directly via
`gradle/actions/setup-gradle`, and a binary wrapper JAR authored by a human
you didn't watch generate it is a supply-chain smell. If you have Gradle
installed locally you can generate one yourself with
`gradle wrapper --gradle-version 8.7` and commit it; the workflow works
either way.

No Android SDK or local Gradle is required to work on this repo — the build
only ever runs in CI.

## CI flow

Every push to `main` (or a manual `workflow_dispatch`) triggers
`.github/workflows/build.yml`, which:

1. Checks out the repo.
2. Installs JDK 17 and Gradle 8.7.
3. Runs `gradle assembleDebug`.
4. Prints the resulting APK size (budget: under 150 KB debug, under 80 KB
   release — see `HANDOFF.md` §1).
5. Publishes the APK as a GitHub **release asset** (not a build artifact —
   artifacts download as a ZIP that Android can't install directly; a
   release asset is a tappable `.apk` URL).

## Getting your first build

This repo currently has **no remote configured** — only `git init` was run
locally, on purpose. To get CI running:

```bash
gh repo create minimal-launcher --source=. --private   # or --public
git push -u origin main
```

(Public repos get unlimited Actions minutes; private repos get 2,000
free minutes/month on the free plan, and each build here takes ~2-3
minutes, so hundreds of private builds/month are free too.)

Then trigger a build either by pushing again, or manually:

```bash
gh workflow run build.yml
```

## Installing the APK on your phone

Once a run finishes, grab the APK from the release it published:

```bash
gh release download build-<run-number> --pattern '*.apk'
```

Transfer the `.apk` to your phone (e.g. `adb install app-debug.apk`, or any
file-transfer method) and install it. You'll need "install unknown apps"
enabled for whichever app you use to open it, since this isn't going
through the Play Store.

To actually use it as your home screen, after installing go to
**Settings → Apps → Default apps → Home app** and pick this launcher.

## Design pass (not done yet)

`design/prototype/` is empty. Once a Claude Design prototype is dropped
there, its tokens (colors, spacing, type sizes, etc.) get extracted into
`design/DESIGN_NOTES.md` and then applied to `res/values/colors.xml`,
`res/values/styles.xml`, and the two layout files, as a separate commit.
See `HANDOFF.md` §2 for exactly what is and isn't allowed in that pass
(no WebView, no copied HTML/CSS, no added font files without explicit
approval).
