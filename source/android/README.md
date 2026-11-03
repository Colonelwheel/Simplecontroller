# Android source

This source snapshot matches verified development commit `d6e20f6` after line-ending normalization. It includes the complete Android module, Gradle wrapper/build files, JVM tests and golden fixtures. Local SDK paths, build caches, private keys and generated outputs are excluded.

Use JDK 21 and Android SDK 36. Configure your own SDK path if needed, then run from this directory:

```powershell
.\gradlew.bat testSideloadDebugUnitTest testPlayReleaseUnitTest
.\gradlew.bat assembleSideloadDebug bundlePlayRelease
.\gradlew.bat lintSideloadDebug lintPlayRelease
```

Both variants passed 626 JVM tests with zero failures/errors/skips. APK/AAB assembly passed and lint reported zero errors (208 Sideload and 206 Play warnings). These checks do not establish physical touch feel or game compatibility.

The public `SimpleController.apk` is the signing-compatible Sideload Debug package `com.example.simplecontroller`, targeting API 34. The separate Play Release package is `io.github.colonelwheel.simplecontroller`, targeting API 36; Play signing/upload remains separate. A developer build uses that developer's own signing setup.

See [payload modifiers and stage tuning](PAYLOAD_MODIFIERS_AND_STAGE_TUNING.md), [saved-profile compatibility](PROFILE_TRANSFER_FORMAT.md), and [distribution variants](PLAY_RELEASE.md). The existing public receiver already supports floating controls and is unchanged by this Android update.
