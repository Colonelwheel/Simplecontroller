# SimpleController Android distributions

Both Git branches, `main` and `dev`, provide these Android build variants. `main` is the public
release source and `dev` is the development source; neither branch name selects a build variant.
The Google Play variant has a separate identity from the existing debug installation. See
[branch roles and profile compatibility](../../BRANCHES.md).

## Variant identities

| Variant | Application ID | Target API | Purpose |
|---|---|---:|---|
| `sideloadDebug` | `com.example.simplecontroller` | 34 | Existing development/feature APK |
| `playRelease` | `io.github.colonelwheel.simplecontroller` | 36 | Google Play Android App Bundle |

The Play build can be installed beside the debug build. Profiles move between them through the
versioned export/import contract in `PROFILE_TRANSFER_FORMAT.md`; the two apps do not share private
Android storage.

The main controller activity uses the app's saved Play Mode orientation lock rather than the phone's
rotation sensor. The standalone Touch Sensor Test activity and its extra launcher icon have been
removed from both distributions. TouchAim and its calibration workflow remain available.

## Build commands

Run from `source/android`:

```powershell
.\gradlew.bat assembleSideloadDebug
.\gradlew.bat testSideloadDebugUnitTest
.\gradlew.bat bundlePlayRelease
```

The development APK is produced under `app/build/outputs/apk/sideload/debug/`. The Play bundle is
produced under `app/build/outputs/bundle/playRelease/`.

These commands build local artifacts. They do not publish source to another Git branch, upload
to Google Play, or install on a device. Gradle install and connected-device tasks require explicit
approval for the particular device and affected data.

## Signing boundary

The Play bundle must eventually be signed with a private upload key and enrolled in Play App
Signing. Signing is intentionally not configured in source control yet. Do not commit a keystore,
password, or local signing-properties file.

Before installing the Play edition, export all profiles from the debug app and wait for
`All-profile export verified`. Import the backup through Android's file picker in the Play edition.
