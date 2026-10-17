# SimpleController Portable Profile Transfer Contract

This file is a compatibility contract for moving controller profiles between APK installations.
It is especially important for the transition from a sideloaded/debug APK to a separately signed
release APK targeting Android API 36.

## Mandatory release compatibility

The API 36 release must keep an importer that accepts all of the following:

- transfer files with `fileType: "simplecontroller-profile"` and `transferVersion: 1`;
- all-profile backups with `fileType: "simplecontroller-profiles-backup"` and `backupVersion: 1`;
- nested `ControllerProfile` format version `10` for floating zones/groups/rings and stored scoped/suppression options, including disabled settings;
- nested format `11` when a floating zone stores closer Aim/Movement boost spacing, swipe-resistant Finish or Boost-entry fire timing;
- nested format `12` when a ring stores role boost banks/custom geometry, even disabled, or immediate direct-Boost aiming;
- nested format `13` when a Boost 2 stores the direct-touch delay bypass, including disabled settings;
- nested `ControllerProfile` format version `9` for optional Button Aim boundary cancellation/re-arming;
- nested `ControllerProfile` format version `8` for Camera Follow exclusion or nondefault strength;
- nested `ControllerProfile` format version `7` for Button Aim Game Mouse;
- nested `ControllerProfile` format version `6` for Touch Aim (Game Mouse);
- nested `ControllerProfile` format version `5` for profiles without Game Mouse;
- the older nested `ControllerProfile` format version `4`;
- the older nested `ControllerProfile` format version `3`;
- the older nested `ControllerProfile` format version `2`;
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
- independent Portrait and Landscape position/size geometry for every page, when created.

Format 2 profiles and historical control arrays contain one geometry set. They migrate as Portrait
without changing their controls or settings. Landscape remains absent until the first Landscape
switch, when the app creates a proportional fitted copy and clamps it within the new canvas.

Whole-profile import preserves those IDs exactly. It must not use the single-page import path that
regenerates IDs, because doing so would break cross-page navigation.

The separate all-profile backup contains:

- every saved controller profile and all of its pages;
- saved TouchAim calibration profiles, including their intrinsic device/control metadata;
- saved manual TouchAim profiles;
- saved Button Aim profiles;
- saved Directional/WASD and Stick+ profiles;
- named floating boost profiles and optional ring aiming/action snapshots;
- complete floating-ring zone presets, including both saved activation rectangles and all ring settings.

Restore adds profiles without replacing existing ones. Controller-profile name collisions receive
an `Imported` suffix. Reusable profiles receive new unique IDs and names when needed, and imported
controller controls that reference an included TouchAim calibration are remapped to its imported
ID and name.

Both formats deliberately exclude network addresses, ports, player selection, reconnect and
transport settings, theme, the current-profile preference, and other app/device preferences.

## Current constants and implementation

- Transfer version: `1`
- Maximum backup version: `3`; writers use `3` when the full-ring preset library is present, `2` for boost profiles without full-ring presets, otherwise `1`. Historical backups 1 and 2 remain readable. Nonempty boost libraries require at least version 2; nonempty full-ring preset libraries require version 3. Future envelope and nested preset versions are rejected before decoding.
- Maximum nested controller-profile format: `13` (formats `2` through `12` remain importable)
- Writers use `13` if a root or stored role-bank Boost 2 has `skipDelayOnDirectTouch: true`, even when disabled; otherwise `12` if a ring stores nonnull `boostBanks` (including disabled actions/geometry) or `directBoostStartsAim: true`; otherwise `11` if any floating zone stores `finishIgnoresSwipes`, `compactAimBoosts`, `adjacentMovementBoosts`, or an aim knob's `returnFire.startOnBoostEntry` as true, even inside disabled configuration; otherwise `10` when floating/scoped configuration is stored, even disabled; otherwise
  `9` when a Button stores either boundary cancellation/re-arming option as true;
  otherwise `8` when any control excludes its movement from Camera Follow or stores strength
  other than 100%; otherwise `7` when a Button has Game Mouse selected for Base or Alternate;
  otherwise `6` when a TouchAim control has `touchAimGameMouse: true`, otherwise `5`.
  Older apps reject nested format 6 before deserialization, including inside all-profile backups.
  Manual and calibration profile application preserve the target control's mouse backend.
- Reusable Button Aim profiles with either boundary option enabled use schema `5`;
  Game Mouse without those options uses schema `4`; newly captured profiles
  with existing outputs remain schema `3`, and historical schemas `1` and `2` remain readable.
  Both aiming-only and complete application copy the saved Base/Alternate output selections.
  Boundary options are action settings: aiming-only application preserves the target's values,
  while complete application copies them. Defaults for both options are false.
- Camera Follow participation belongs to the target control. Applying reusable Button Aim,
  Directional/Stick+, manual TouchAim, or calibration profiles leaves its exclusion unchanged.
  Complete controller profiles preserve both `excludeFromCameraFollow` and
  `cameraFollowStrengthPercent`, including inactive selections. Current live strength is an
  app preference, excluded from portable transfers like other runtime/network preferences.
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

## Floating configuration and library contract

Floating geometry and references are detached saved configuration. Runtime placement, visibility, pointer/timer/armed state, ownership and suppression are excluded. Copies remap related IDs and both orientation/restoration maps; whole-profile transfer preserves isolated IDs.

Named boost profiles use schema 3 when their Boost 2 stores the direct-touch delay bypass, schema 2 when their optional ring aiming/actions snapshot stores Boost-entry fire timing, and schema 1 otherwise. Complete ring presets use schema 4 for the direct-touch delay bypass, schema 3 for stored role banks/custom geometry or immediate direct aiming, schema 2 for the earlier closer-spacing/Finish/fire-timing options, and schema 1 otherwise. Older schemas remain readable; under-versioned new options and unsupported nested schemas are rejected before typed decoding or internal writes. Boost-only application explicitly targets one bank, preserving its enable choices and custom geometry, the other bank, target layers, Finish policy, movement and normal/fine/firing tuning. A full-ring preset preserves both banks, including inactive settings. Explicit ring aiming/actions application includes fire timing and validates knob-slot mapping atomically. Transfer/backup envelopes are unchanged. All-profile staging, verification, additive restore and rollback include the libraries.
