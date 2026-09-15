# SimpleController Portable Profile Transfer Contract

This file is a compatibility contract for moving controller profiles between APK installations.
It is especially important for the transition from a sideloaded/debug APK to a separately signed
release APK targeting Android API 36.

## Mandatory release compatibility

The API 36 release must keep an importer that accepts all of the following:

- transfer files with `fileType: "simplecontroller-profile"` and `transferVersion: 1`;
- all-profile backups with `fileType: "simplecontroller-profiles-backup"` and `backupVersion: 1`;
- the nested `ControllerProfile` format version `2`;
- bare format-version-2 multipage `ControllerProfile` JSON objects created before the wrapper;
- historical top-level JSON arrays of `Control` objects, migrated to one `Base` page.

The committed single-profile fixture
`app/src/test/resources/golden/debug-profile-transfer-v1.json` represents a debug-build export
that future release decoders must continue to accept. Keep it in release compatibility tests.

Do not reuse or reinterpret existing serialized field or enum meanings. Before making a change
that cannot decode the existing shape without loss, increment the appropriate format version and
add an explicit migration. A newer unsupported version must be rejected before typed
deserialization so unknown future controls or enums cannot be silently dropped.

## Transfer boundary

The migration boundary is the user-owned file selected through Android's Storage Access Framework.
It does not depend on the old app's private storage, application ID, signing certificate, target
SDK, or a persisted URI permission. After installing another APK, the user selects the file again.

Use `ACTION_CREATE_DOCUMENT` for export and `ACTION_OPEN_DOCUMENT` for import. The export path must:

1. snapshot and stage the complete profile atomically before opening the picker;
2. write and close the selected document;
3. reopen, decode, and compare it with the staged profile;
4. report success only as `Export verified` after that comparison passes.

The import path must bound the file size, require valid UTF-8/JSON, validate versions and profile
structure, and verify every atomic internal save. Import must never overwrite another saved profile
automatically. A single-profile import is loaded only after verification. An all-profile restore
keeps the currently loaded profile unchanged.

## Included data

The single-profile file contains one complete controller profile:

- ordered pages;
- Home page ID;
- stable page IDs and control IDs;
- every serialized `Control` setting and applied settings snapshot;
- local page actions and cross-page target IDs.

Whole-profile import preserves those IDs exactly. It must not use the single-page import path that
regenerates IDs, because doing so would break cross-page navigation.

The separate all-profile backup contains:

- every saved controller profile and all of its pages;
- saved TouchAim calibration profiles, including their intrinsic device/control metadata;
- saved manual TouchAim profiles;
- saved Button Aim profiles;
- saved Directional/WASD and Stick+ profiles.

Restore adds profiles without replacing existing ones. Controller-profile name collisions receive
an `Imported` suffix. Reusable profiles receive new unique IDs and names when needed, and imported
controller controls that reference an included TouchAim calibration are remapped to its imported
ID and name.

Both formats deliberately exclude network addresses, ports, player selection, reconnect and
transport settings, theme, the current-profile preference, and other app/device preferences.

## Current constants and implementation

- Transfer version: `1`
- All-profile backup version: `1`
- Nested controller-profile format: `2`
- Single-profile suffix: `.simplecontroller-profile.json`
- All-profile suffix: `.simplecontroller-profiles-backup.json`
- Maximum file size: 25 MB
- Codec and validation: `app/src/main/java/com/example/simplecontroller/io/LayoutStorage.kt`
- All-profile bundle and restore: `app/src/main/java/com/example/simplecontroller/io/ProfileBackup.kt`
- Picker integration: `app/src/main/java/com/example/simplecontroller/MainActivity.kt`
- Load/manage UI: `app/src/main/java/com/example/simplecontroller/io/LayoutManager.kt`

The `sideloadDebug` build retains target API 34 and application ID
`com.example.simplecontroller`. The separate `playRelease` build targets API 36 and uses
`io.github.colonelwheel.simplecontroller`. Both builds must retain this contract and golden fixture.
