# Android development source

This is the Android source for **`dev`**, preserving floating-controls snapshot `1ea6ffa` in the common `source/android` layout. It includes Macro Mode and per-command `(U...)` timed release from `9bb9187`, plus the later standalone diagnostic removal. TouchAim and its calibration workflow remain available. Android runtime source, resources, build configuration, and tests retain their original contents; this directory reorganization does not install an app or change its data.

Open this `source/android` directory in Android Studio. [`main`](https://github.com/Colonelwheel/Simplecontroller/tree/main) uses the same layout for the public release. Main supports controller-profile formats through 16; dev supports 17 for Macro Mode/U. See [branch and profile compatibility](../../BRANCHES.md) and [the portable source map](../../ARCHITECTURE.md).

Use JDK 21 and Android SDK 36. Configure your own local SDK path, then run from this directory:

```powershell
.\gradlew.bat testSideloadDebugUnitTest testPlayReleaseUnitTest
.\gradlew.bat assembleSideloadDebug bundlePlayRelease
.\gradlew.bat lintSideloadDebug lintPlayRelease
```

The supplied root [SimpleController.apk](../../SimpleController.apk) is the existing November 17, 2026 feature APK. Historical verification for that delivery passed 678 JVM tests per Android variant, APK/AAB packaging, and lint with zero errors. Those historical checks were not rerun for the main/dev organization and do not establish physical touch feel or game compatibility. No app build or device install was performed by this setup.

The APK uses the Sideload Debug identity `com.example.simplecontroller`, targeting API 34. The separate Play Release package is `io.github.colonelwheel.simplecontroller`, targeting API 36; Play signing/upload remains separate. A developer build uses its own signing setup. Source branch names and build variants are independent.

Do not run Gradle install or connected-device tasks without explicit approval for the device and affected data. Keep local SDK paths, caches, signing keys, private instructions, and device backups out of version control.

References: [modifiers and stage tuning](PAYLOAD_MODIFIERS_AND_STAGE_TUNING.md), [Macro Mode/U porting contract](MACRO_MODE_AND_PAYLOAD_PORTING.md), [profile compatibility](PROFILE_TRANSFER_FORMAT.md), [Android distributions](PLAY_RELEASE.md), and [detailed control guide](SIMPLECONTROLLER_USER_GUIDE.md). Module-relative paths in these documents start at `source/android`. Dated delivery notes retain their original historical meanings.
