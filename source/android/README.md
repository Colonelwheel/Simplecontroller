# Android development source

This is the Android source for **`dev`**, based on floating-controls snapshot `1ea6ffa` in the common `source/android` layout. It includes Macro Mode and per-command `(U...)` timed release from `9bb9187`, plus the later standalone diagnostic removal. TouchAim and its calibration workflow remain available. The November 24, 2026 update adds selected USB/Wi-Fi reconnection and generation-guarded USB discovery. Connection preferences remain separate from controller profiles; profile formats and saved manual endpoint/ConsoleBridge settings are unchanged.

Open this `source/android` directory in Android Studio. [`main`](https://github.com/Colonelwheel/Simplecontroller/tree/main) uses the same layout for the public release. Both current branches support controller-profile formats through 17 for Macro Mode/U. Main promotes Macro Mode/U and its regression tests from dev `632447c`; dev's newer USB reconnect source/APK now differs. See [branch and profile compatibility](../../BRANCHES.md) and [the portable source map](../../ARCHITECTURE.md).

Use JDK 21 and Android SDK 36. Configure your own local SDK path, then run from this directory:

```powershell
.\gradlew.bat testSideloadDebugUnitTest testPlayReleaseUnitTest
.\gradlew.bat assembleSideloadDebug bundlePlayRelease
.\gradlew.bat lintSideloadDebug lintPlayRelease
```

The supplied root [SimpleController.apk](../../SimpleController.apk) is rebuilt from this branch for the November 24, 2026 USB reconnect update. With Auto Reconnect enabled, it restores the last selected connection method, discovers active USB on an upgrade with no saved mode, and keeps explicit manual Wi-Fi selection available without automatic fallback. Historical verification for the November 17 delivery passed 678 JVM tests per Android variant, APK/AAB packaging, and lint with zero errors. Those historical checks were not rerun for the main/dev organization and do not establish physical touch feel or game compatibility. The initial main/dev setup did not build/install an app; verification for the later USB reconnect update is recorded in the root README.

The APK uses the Sideload Debug identity `com.example.simplecontroller`, targeting API 34. The separate Play Release package is `io.github.colonelwheel.simplecontroller`, targeting API 36; Play signing/upload remains separate. A developer build uses its own signing setup. Source branch names and build variants are independent.

Device and emulator operations follow the [root project boundary](../../AGENTS.md#build-and-verification-boundaries). Keep local SDK paths, caches, signing keys, private instructions, and device backups out of version control.

References: [modifiers and stage tuning](PAYLOAD_MODIFIERS_AND_STAGE_TUNING.md), [Macro Mode/U porting contract](MACRO_MODE_AND_PAYLOAD_PORTING.md), [profile compatibility](PROFILE_TRANSFER_FORMAT.md), [Android distributions](PLAY_RELEASE.md), and [detailed control guide](SIMPLECONTROLLER_USER_GUIDE.md). Module-relative paths in these documents start at `source/android`. Dated delivery notes retain their original historical meanings.
