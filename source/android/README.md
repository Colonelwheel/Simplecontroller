# Android source

This is the Android source for the **`main` public release**. Macro Mode, per-command `(U...)` timed release and compatibility checks are promoted from dev commit `632447ce0606a21dd63886564fdcecf68de1fd8b` onto the earlier public-release basis `69eee7d`. The Android implementation and regression tests match that dev snapshot: 77 production Kotlin files and 65 Kotlin JVM test files. TouchAim and its calibration workflow remain available; the standalone Touch Sensor Test activity and extra launcher remain removed. Local SDK paths, build caches, private keys and generated outputs are excluded.

Open this `source/android` directory in Android Studio. The [`dev` branch](https://github.com/Colonelwheel/Simplecontroller/tree/dev) uses the same layout for ongoing work and additional receiver/voice development tools. Both branches now support controller-profile formats through 17, with conditional nested reusable-library versions. See [branch and profile compatibility](../../BRANCHES.md) and [the implementation map](../../ARCHITECTURE.md).

Use JDK 21 and Android SDK 36. Configure your own SDK path if needed, then run from this directory:

```powershell
.\gradlew.bat testSideloadDebugUnitTest testPlayReleaseUnitTest
.\gradlew.bat assembleSideloadDebug bundlePlayRelease
.\gradlew.bat lintSideloadDebug lintPlayRelease
```

Fresh verification results and the public APK's checksum/provenance are recorded in [the root README](../../README.md#5-downloads-and-source). Test-file counts describe the source inventory, not executed test cases. The initial main/dev organization did not rebuild or install artifacts; this later promotion is a separate off-device Android build and verification task. No phone installation or live game test is part of this promotion.

The public [SimpleController.apk](../../SimpleController.apk) uses the Sideload Debug identity `com.example.simplecontroller`, targeting API 34. The separate Play Release package is `io.github.colonelwheel.simplecontroller`, targeting API 36; Play signing/upload remains separate. A developer build uses that developer's own signing setup; compare signing certificates before proposing an in-place update.

`sideloadDebug` and `playRelease` are build variants available from either source branch. Building a variant does not publish to GitHub or move changes between `main` and `dev`. Builds and tests do not authorize device installation. Do not run Gradle install or connected-device tasks without explicit approval for that device and its data effects.

See [Macro Mode/U timing and porting](MACRO_MODE_AND_PAYLOAD_PORTING.md), [payload modifiers and stage tuning](PAYLOAD_MODIFIERS_AND_STAGE_TUNING.md), [saved-profile compatibility](PROFILE_TRANSFER_FORMAT.md), and [distribution variants](PLAY_RELEASE.md). Macro Mode/U executes on Android using existing receiver commands. The existing public receiver already supports floating controls and is unchanged by this Android update.
