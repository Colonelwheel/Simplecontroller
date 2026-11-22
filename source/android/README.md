# Android source

This is the Android source for the **`main` public release**, preserved from release commit `69eee7d` and based on verified development commit `d6e20f6` with the standalone Touch Sensor Test activity and launcher removed. TouchAim and its calibration workflow are unchanged. It includes the complete Android module, Gradle wrapper/build files, JVM tests and golden fixtures. Local SDK paths, build caches, private keys and generated outputs are excluded.

Open this `source/android` directory in Android Studio. The [`dev` branch](https://github.com/Colonelwheel/Simplecontroller/tree/dev) uses the same layout and includes newer Macro Mode/U features. Main supports controller-profile formats through 16; dev can write format 17. See [branch and profile compatibility](../../BRANCHES.md).

Use JDK 21 and Android SDK 36. Configure your own SDK path if needed, then run from this directory:

```powershell
.\gradlew.bat testSideloadDebugUnitTest testPlayReleaseUnitTest
.\gradlew.bat assembleSideloadDebug bundlePlayRelease
.\gradlew.bat lintSideloadDebug lintPlayRelease
```

Historical verification for the November 17, 2026 release passed 626 JVM tests per variant with zero failures/errors/skips. APK/AAB assembly passed and lint reported zero errors (211 Sideload and 211 Play warnings). That APK has only the controller launcher; APK/AAB code no longer contains the standalone diagnostic. The main/dev setup did not rebuild or install these artifacts. Historical checks do not establish physical touch feel or game compatibility.

The public `SimpleController.apk` is the signing-compatible Sideload Debug package `com.example.simplecontroller`, targeting API 34. The separate Play Release package is `io.github.colonelwheel.simplecontroller`, targeting API 36; Play signing/upload remains separate. A developer build uses that developer's own signing setup.

`sideloadDebug` and `playRelease` are build variants available from either source branch. Building a variant does not publish to GitHub or move changes between `main` and `dev`. Builds and tests do not authorize device installation. Do not run Gradle install or connected-device tasks without explicit approval for that device and its data effects.

See [payload modifiers and stage tuning](PAYLOAD_MODIFIERS_AND_STAGE_TUNING.md), [saved-profile compatibility](PROFILE_TRANSFER_FORMAT.md), and [distribution variants](PLAY_RELEASE.md). The existing public receiver already supports floating controls and is unchanged by this Android update.
