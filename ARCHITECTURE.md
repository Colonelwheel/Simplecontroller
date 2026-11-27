# Architecture and source map

Implementation map for **main**; see [BRANCHES.md](BRANCHES.md) for branch provenance and compatibility.

## Start here

Read this section, the relevant change route, and that subsystem's entries. There is no need to load this entire document for a small change. Read the linked compatibility contract before changing persisted or transmitted data.

| Planned change or symptom | Read first | Follow through / verify |
| --- | --- | --- |
| Button press, Hold, Turbo, Auto-Tap, alternate | [Control interaction](#control-interaction), `ControlView`, `ControlViewHelper` | `ButtonAutoTapController`, `OneShotAlternateStateMachine`, payload executor; release versus cancellation |
| Macro Mode, payload modifiers, delays, repeats, shared held outputs | [Macro Mode and U](#macro-mode-and-u), [payload execution](#payload-execution-and-ownership) | `ButtonMacroController`, `PayloadModifiers`, `ButtonAimPayloadExecutor`, scope/lease ownership, [timing contract](source/android/PAYLOAD_MODIFIERS_AND_STAGE_TUNING.md) |
| Stick movement, Stick+, directional boosts | [Movement and aiming](#movement-and-aiming) | `DirectionalStickHandler`, `DirectionalCommandStateController`, `ExtendedBoostSettings`; deduplicated shared commands |
| Button Aim or D-pad steering | [Movement and aiming](#movement-and-aiming) | `ButtonAimHandler`, `AimOutputController`, `ButtonAimDpadSession`, boundary policy and alternate settings |
| TouchAim contact thresholds/calibration | [TouchAim](#touchaim) | Models, analyzer/wizard, stage confirmation/payload controller; manual and calibrated modes |
| Floating activation, ring roles/boosts/Finish | [Floating controls](#floating-controls) | Editor/configuration, geometry, state machine, scoped transport; hidden member/orientation restoration |
| Profiles, pages, duplicate/import/export | [Activity and pages](#activity-and-pages), [Persistence](#persistence) | Copy IDs intentionally, validate before decode, atomic save/verified export, [transfer contract](source/android/PROFILE_TRANSFER_FORMAT.md) |
| New saved setting or preset field | [Models](#models), [Persistence](#persistence) | Editor validation, capture/apply/copy, inactive settings, conditional format/schema, backup and tests |
| Connection, USB tether, player, ordering or Release All | [Android transport](#android-transport), [Wire contracts](#wire-contracts) | Both Android clients, receiver router, stale callbacks and exact acknowledgement |
| Camera Follow, suppression, Game Mouse | [Receiver](#windows-receiver) | Android command/configuration plus receiver arbitration/backend; native output requires separate verification |
| Facial expression/movement/camera | [Facial app](#facial-app) | Worker/runtime/client plus receiver `FaceInputState`; camera and output are separate lifecycles |
| VoiceAttack controller actions | [VoiceAttack](#voiceattack) | C# helper and receiver voice state together; macro generation, finite versus persistent ownership |
| Build, application ID, signing or packaged download | [Build and resources](#build-and-resources) | [Distribution settings](source/android/PLAY_RELEASE.md), separate source/artifact provenance; no automatic installation |
| Select relevant checks | [Verification map](#verification-map) | Confirm tests were actually discovered; distinguish source tests from device/game evidence |

### Branch and entrypoint facts

- Both current `main` and `dev` support `(I)`, `(SOR)`, `(HRD...)`, `(R...)`, `(D...)`, **`(U...)`**, and finite **Button Macro Mode**. Stored U or `macroModeEnabled=true` requires controller-profile format **17**, including inactive settings. Older format-16 APKs cannot import that data. Fixed-direction stick payloads such as `LS:R50` remain distinct from Button Macro Mode. See the [Macro Mode/U contract](source/android/MACRO_MODE_AND_PAYLOAD_PORTING.md).
- `source/android/app/src/main/AndroidManifest.xml` launches **`.MainActivity`**. `RefactoredMainActivity.kt` is an older alternative, not a second active launcher. No `RefactoredControlView.kt` exists in this snapshot; historical migration suggestions are not the active runtime wiring.
- Android uses classic Views/AppCompat, with substantial UI constructed in Kotlin. Compose entries in the version catalog do not make this a Compose application.
- The receiver entrypoint is **`source/receiver/simple_controller_receiver.py`**, despite its historical header mentioning `merged_controller.py`. **Do not import it merely to inspect it:** import creates logs and two native virtual gamepads and changes PyAutoGUI settings.
- Root APK/EXE files are existing downloads. Editing source, compiling a variant, or updating this map does not rebuild, install, upload, or replace them. Git branches and Gradle variants are different choices.
- Physical Kotlin paths sometimes differ from package names: `NetworkClient.kt`, `UdpClient.kt`, `PhoneOutputCoordinator.kt`, `ReleaseAllProtocol.kt`, and `UdpHeartbeat.kt` sit at the Kotlin root but declare the `.net` package. `GlobalSettings` and `SwipeManager` are inside `ui/ControlViewHelper.kt`.

## System flow and invariants

```text
Android MainActivity -> ControlView / gesture handlers -> payload or movement output
  -> UdpClient + NetworkClient -> Windows UDP router -> shared output adapters
  -> optional CbProtocol -> external ConsoleBridge gateway/Pico (separate implementation)
Facial GUI -> tracking worker -> runtime -> ReceiverClient -> facial TCP state
VoiceAttack inline C# helper -> voice TCP state
Phone / face / voice ownership -> virtual Xbox pair or shared Windows keyboard/mouse
```

The phone owns layouts, control settings, gesture intent, and Android payload modifiers. The Windows receiver owns native output, its finite timers, cross-source merging and Camera Follow. The standalone facial app owns its camera/calibration/preferences. VoiceAttack owns spoken-command sequencing; its helper uses the receiver's controller ownership.

Preserve these distinctions when changing any subsystem:

1. **Normal end versus hard cancellation.** Normal source release may start HRD, SOR or finite tails. Macro Mode is an explicit exception: normal lift preserves its pending/active finite immediate steps; hard cancellation still ends them. Pointer loss/cancel, edit/profile/page/orientation transitions, Finish and global cleanup must follow their explicit cancellation paths, cancel pending work, and invalidate obsolete callbacks. Do not replace all endings with one generic release.
2. **Owned state versus one-shot events.** Reversible keys/buttons/triggers/sticks have owners. Relative mouse/scroll/tap events need identity and duplicate handling. A state retry must not create a second event.
3. **Several ownership layers.** Payload executor leases prevent one action releasing another's output; `ManualStickArbiter` coordinates manual stick sessions; `PhoneOutputScope` coordinates phone controls/instances; receiver proxies merge phone, face and voice. These layers solve different problems.
4. **Mutable runtime state versus saved configuration.** Pointers, timers, visibility, active ring placement, scopes, active suppression claims and current page history are runtime state. Serialized profiles contain stable settings/IDs, saved geometry and suppression preferences. Keep runtime state out of reusable presets and exports.
5. **Cleanup attempt versus acknowledgement.** Android clears local state before requesting a receiver barrier. Only the matching ACK confirms receiver processing; even that is not evidence of game acceptance. Receiver disconnect/expiry/shutdown is not equivalent to comprehensive `RELEASE_ALL`.
6. **One-finger access and existing behavior.** Preserve reachable safety/navigation controls, Hold qualification, captured gestures outside bounds, independent owners, and existing input modes. Changes to editor visibility or defaults must not silently erase inactive settings.

## Activity and pages

Base Kotlin directory, abbreviated **K** below: [`source/android/app/src/main/java/com/example/simplecontroller/`](source/android/app/src/main/java/com/example/simplecontroller/). File paths in the Android tables are relative to K.

### MainActivity.kt block map

[`MainActivity.kt`](source/android/app/src/main/java/com/example/simplecontroller/MainActivity.kt) is the active composition root, not just a screen. Search these blocks rather than reading all of its built-in template construction for an unrelated change.

| Block / symbols | Responsibility and coupled state |
| --- | --- |
| Fields, `floatingControls`, `floatingEditor`, zone callbacks | Wire floating controllers/editor to the current profile, orientation, live control list, canvas and action targets. `preserveSavedProfileDuringFloatingConversion` blocks accidental autosave over the original. |
| `onCreate`, `setupUI`, `observeConnectionStatus` | Initialize theme/preferences, load complete profile, build canvas/toolbars and observers, then render after orientation/size settle. A read error sets `profileAutosaveBlocked`; a fallback screen must not overwrite the unreadable original on pause. |
| `onStart`, `onResume`, `onPause`, `onStop` | Auto-reconnect/tether return, floating preparation, calibration cancellation, profile synchronization, local/output cleanup and guarded autosave. Pause resets runtime navigation to Home. Stopping closes the network client when auto-reconnect is disabled. |
| `dispatchTouchEvent` | Calibration/transition handling, floating capture and pending connection preparation, scoped-activation barrier, global swipe, then normal Android dispatch. Ordering gives visible controls/app UI priority and prevents switching legacy output mode mid-gesture. |
| `loadNetworkSettings`, `showConnectionSettingsDialog`, `connectViaUsbTether`, `updateConnectionStatusUI` | Saved manual endpoint versus temporary tether discovery, transport/player choice, reconnect and status presentation. UDP “Connected” is set after local setup, not a successful game-input test. |
| `onControllerProfileLoaded`, `onNewControllerProfileRequested`, `installProfile` | Clean current runtime state, install a complete profile, reset navigation, render Home. `LayoutManager.LayoutCallback` provides the UI/storage bridge. |
| `onExternalProfileExportRequested`, `onAllProfilesExportRequested`, `writeAndVerifyProfileExport` | Stage an immutable document before Android's file picker, write/close it, reopen/decode/compare, then report verified success. Work uses lifecycle coroutines and IO dispatch. |
| `readAndPromptForProfileImport`, `promptForProfileImport`, `promptForAllProfilesImport` | Bound/decode external input; additive named saves with verification. Whole backup restore leaves the currently loaded profile in place. |
| `syncDisplayedPageIntoProfile`, `controllerProfileForSave` | Snapshot live controls and current orientation geometry back into the owning page, including floating member/restoration maps. Avoid syncing an unfinished orientation render. |
| `renderPage`, `clearControlViewsSafely`, `resetToHomeAfterCleanup` | Rebuild views for the selected page/orientation; keep hidden floating group members out of ordinary view spawning. Silent detach is allowed after explicit cleanup, not instead of cleanup. |
| `activateControllerPageAction`, `showControllerPageManager`, page creation/duplicate/import/rename/delete helpers | Local page navigation and editing. Whole-profile transfer keeps IDs; page/control copies detach and remap them. Preserve Home, navigation references and independent nested settings. |
| `requestEditModeChange`, `finishLeavingEditMode`, `switchToOrientation`, `completeOrientationRender` | Save current geometry, release/cancel old runtime state, choose editor/Play lock orientation, wait for layout/configuration before rendering. Orientation is not a live sensor-input feature. |
| `activateReleaseAll`, `releaseOutputs` | Clear floating runtime and selection, call `ReleaseAllCoordinator`, optionally report pending/confirmed/unavailable status and haptics. |
| `startTouchAimCalibration`, `cancelTouchAimCalibration` | Locate the active control view; launch wizard only after layout; generation token prevents delayed wizard launch after cancellation. Apply/save only through the completed wizard path. |
| `createXboxControllerLayout`, `createPlayer1XboxLayout`, `createPlayer2XboxLayout`, `finalizeBuiltInPage` | Large built-in layout templates, with separate local `addControl` functions. A default change in one template does not update the other templates or existing user profiles. |
| `onConfigurationChanged`, `setupWindowInsetsHandling`, `refreshLayoutForNewDimensions`, `storeOriginalControlDimensions` | Canvas/insets/multiwindow adaptation and scaling. Keep orientation-specific geometry and first-render guards intact. |

| Other root/UI composition file | Responsibility |
| --- | --- |
| `RefactoredMainActivity.kt` | Legacy alternative using older one-page APIs and smaller UI wiring. Not declared in the manifest. Do not move a production fix here by filename preference. |
| `ui/UIComponentBuilder.kt` | Shared toolbar/switch/button/numeric-input construction and layout styling helpers; used by active MainActivity as well as the older activity. |

## Models

All paths in this table are under **K/model/**. These are persisted data, validation, copying/application policy and pure state models; changing a field often requires more than changing its constructor.

| File | Main blocks / purpose |
| --- | --- |
| `Control.kt` | `Control`, `ControlType` and input enums. Saved geometry/common payload; Hold/Auto-Tap/Macro Mode; page/local floating actions; Base/Alternate Button Aim; click-lock; directional/Stick+ thresholds; TouchAim manual/two-state/calibrated settings. Defaults preserve old layouts. No runtime pointer/timer ownership belongs here. |
| `ControllerProfile.kt` | `ControllerPage`, `ControllerProfile`, `requiredFormatVersion`, `ControllerPageSession`, page-action helpers and `isPageTransportCommand`. Saved pages/Home/geometry/floating zones versus runtime go/toggle/previous/Home history. Conditional maximum format is 17; stored U or Macro selects it before older feature checks. Page commands are Android-local and blocked from receiver transport. |
| `OrientationLayout.kt` | `LayoutOrientation`, `ControlGeometry`, `PageOrientationGeometry`; capture, fitting, per-orientation lookup/update and ID-map helpers. Missing legacy geometry is fitted/clamped without losing control settings. |
| `OrientationPreferenceStore.kt` | `OrientationPreferenceStore` over a small read/write abstraction; persisted Play orientation choice, separate from controller exports. |
| `PayloadModifiers.kt` | `PayloadCommand`, `PayloadCommandTiming`, strict `PayloadModifiers.parse`, repeat press-duration calculation and raw feature detection. Recognizes I/SOR/HRD/R/D/U; nullable `pressDurationMs` and `hasReleaseModifiers` preserve U and detect malformed attempts; rejects conflicts/duplicates/invalid ranges before output. Trigger `P` duration belongs to the command body. |
| `PayloadProfileFeatures.kt` | Enumerate action payloads in controls/rings/boosts, validate modifier syntax, detect/validate per-stage TouchAim tuning. Include stored inactive fields when selecting compatibility versions. |
| `ButtonAimPayloadPolicy.kt` | Validate/classify supported action commands, including reversible outputs and stick macros; shared by editors, executor and scoped transport. Output keys are constructed by coordinator/executor parsing, not this policy. |
| `ButtonAimProfile.kt` | `ButtonAimTuning`, `ButtonAimActionSettings`, `ButtonAimProfile`; capture, conditional schema, validation, `applyAimTuningTo` versus `applyCompleteTo`. Aiming-only application preserves target action settings; complete application copies more. Both enable Button Aim and disable conflicting Macro Mode; Macro is not a reusable Button Aim action setting. |
| `StickDirectionalProfile.kt` | Directional/Stick+ reusable snapshots and conditional schemas; `applyCommandsTo` preserves target mode, `applyAndSwitchModeTo` opts into the saved mode. |
| `ExtendedBoostSettings.kt` | Shared/per-direction extended boost thresholds and directional commands, validation and application helpers. Extra Outer thresholds can exceed the visible stick radius. |
| `TouchAimManualProfile.kt` | Separate manual three-stage/two-state snapshots, validation/schema/capture/apply. Preserve the target's mouse backend and control identity. |
| `TouchAimCalibration.kt` | Sensor transforms, calibration settings/metadata/profile structures, applicability and apply logic. Calibration is device/control-related configuration, not a live sensor stream. |
| `FloatingControls.kt` | Zones/groups, movement/aim/boost/fire/return-fire/fine settings, role boost banks/custom geometry, `FloatingRingConfig`, boost libraries and validation. `seedBoostBanks` adapts legacy shared boost settings; `applyBoostSettingsOnlyTo` deliberately preserves target geometry/enables/other tuning. |
| `FloatingOrientation.kt` | `controlsForFloatingOrientation`: combine ordinary orientation geometry with saved floating member/restoration geometry; do not persist runtime revealed positions. |
| `FloatingRingPreset.kt` | Complete ring-zone preset capture/application, conditional schema and validation; distinct from narrower boost-only presets. |

## Persistence

Paths are under **K/io/**. Follow [PROFILE_TRANSFER_FORMAT.md](source/android/PROFILE_TRANSFER_FORMAT.md) for exact version rules instead of duplicating that contract in new code.

| File | Main blocks / purpose |
| --- | --- |
| `LayoutStorage.kt` | Canonical JSON codec and private `layout_<name>.json` storage. `decodeControllerProfile` migrates historical control arrays/pages; raw preflight precedes typed decode, including attempted U/Macro against format 17; unversioned legacy arrays cannot carry those new features. `validateControllerProfile` handles structural recovery/warnings; `validateControllerProfileForSave` blocks invalid writes. `encodeControllerProfile` chooses the required version; `readControllerProfile` distinguishes NotFound from Error. AtomicFile saves, staged document APIs, size/UTF-8 bounds, safe names, whole-transfer wrappers and detached copy/ID remapping all live here. `loadControls`/`saveControls` are legacy Home-page/one-page compatibility APIs. |
| `LayoutManager.kt` | Save/load/manage dialogs, default controls, view factory/spawn and new/duplicate/remove controls, profile rename/duplicate/export/delete and bulk delete UI. Calls active Activity callbacks for complete multipage profiles; it is not the sole owner of page state. |
| `ControllerProfileBulkDelete.kt` | Pure candidate filtering and selective deletion results, including protection for the currently active profile. UI/confirmation lives in LayoutManager. |
| `ControlSettingsProfileStores.kt` | `AtomicNamedProfileStore`, reusable operation/backup snapshot results and manual TouchAim/Button Aim/directional-stick/floating-boost stores. Canonical UUID filenames, unique names, conditional schema preflight, atomic writes; unreadable-library counts are retained for complete-backup checks. |
| `TouchAimCalibrationStore.kt` | `TouchAimCalibrationCatalog` pure catalog operations and separate validated, atomic calibration storage, rename/duplicate/delete and ID validation. A normal catalog save does not reopen/compare the written file. |
| `FloatingRingPresetStore.kt` | Full-ring encoding/decoding/store, raw nested-option/schema checks before accepting data. Reject invalid application atomically. |
| `PayloadFeatureCompatibility.kt` | Raw recursive JSON detection of modifiers, R, D, U, Macro and stage tuning across saved action field names; `hasReleasePayloadConfiguration`, `hasButtonMacroConfiguration` and `releaseVersion` guard newer settings. `requirePayloadFeatureSchema` prevents an under-versioned feature being silently dropped by `ignoreUnknownKeys`. Add new serialized action fields here as needed. |
| `ProfileBackup.kt` | `ControllerProfilesBackup`, nested version preflight, `createControllerProfilesBackup`, encode/decode/document dispatch, `importControllerProfilesBackup`. Includes current unsaved profile snapshot and all reusable libraries; fails if any required saved data is unreadable. Additive imports receive new names/library IDs; applied calibration references are remapped. Reopen/compare saves; rollback deletes only entries created by that import and reports incomplete rollback. |

Compatibility is layered: single transfer wrapper **1**, conditional all-profile backup envelope **1–3**, nested controller formats through **17**, and independent reusable-library schemas. The newest **stored** feature determines the required version, including disabled alternatives, `(D0)`, attempted U and stored `macroModeEnabled=true`. U conditionally requires manual TouchAim/calibration schema 5, Button Aim 9, Directional/Stick+ 6, floating boost 7, full ring preset 8; older feature-only data keeps its earlier versions. Reject unsupported versions before typed deserialization or writes. Whole-profile import preserves page/control IDs. `detachedControlCopy` changes control ID and self-targeted floating reference but preserves `pageTargetId`; page copying remaps member/geometry IDs and redirects page self-targets only when requested, retaining other page destinations. Network/player/theme/current-profile preferences are intentionally excluded from portable backups.

## Android transport

Paths are relative to **K**. Both `NetworkClient` and `UdpClient` use UDP: comments saying “TCP fallback” in UdpClient describe the fallback client incorrectly.

| File | Main blocks / purpose |
| --- | --- |
| `NetworkClient.kt` | Connection/player settings and StateFlows, IO coroutine socket setup/listener, `CONNECT`/`REGISTER`/heartbeat, bounded reconnect and USB tether discovery. `sendGuarded` captures route/player and checks freshness before queued sends. `requestReleaseAll` and `requestPhoneFrame` retry exact identities and match ACKs; `close` flushes an already prepared barrier before socket closure. It marks CONNECTED after socket setup rather than waiting for a receiver handshake. |
| `UdpClient.kt` | Main low-latency output facade: command/key/stick/mouse/scroll senders, route snapshots, `OutputSafetyEpoch`, key keepalive, Camera Follow preference/strength/manual-RS/mouse suppression owners. Also negotiates scoped phone capability and activation, dispatches coordinator packets, and invalidates capability after route/player/transport changes. `setLegacyOutputBusy` prevents protocol adoption during an existing legacy gesture. `prepareFloatingControls` and preparation tickets support bounded pending contacts without reconnecting stale gestures. |
| `PhoneOutputCoordinator.kt` | `PhoneOutputProtocol` wire builders; `PhoneProtocolActivationGate`, conflict feedback tracker, `PhoneOutputCoordinator` and `PhoneOutputScope`. Per-output claims/reservations, exclusive versus retained replacement, child payload scopes, effective trigger/vector/state selection, duplicate-safe events, finish tombstones and aggregate cleanup ACKs. `beginScope` advances a stable ID's generation. `releasePayload` can preserve reservations; `endPayload` ends its claims; `finish` ends descendants and reports aggregate cleanup confirmation or failure. |
| `ReleaseAllProtocol.kt` | `OutputSafetyEpoch`: unique process session, increasing packet sequence and local generation. Queued sends retain tickets; global release invalidates the generation before sending its ordered barrier. |
| `UdpHeartbeat.kt` | `sendUdpHeartbeat` runs socket IO off the main thread. A failed obsolete socket must not disconnect its replacement. |
| `CbProtocol.kt` | Binary ConsoleBridge frame encoder: magic/version/16-bit sequence/type/payload/CRC8, little-endian numeric fields. Maps supported key/mouse/gamepad/stick/trigger commands; unsupported commands retain existing text fallback. Does not implement external gateway or Pico firmware. |
| `net/CameraFollowCommands.kt` | Ordered stick packet construction, including Camera Follow exclusion; strength command construction, strength normalization and Camera Follow payload recognition. |
| `net/MouseDeltaCommand.kt` | Finite legacy versus Game Mouse delta formatting; preserves the chosen backend. |
| `ui/ReleaseAllCoordinator.kt` | Single Android global-cleanup sequence: invalidate queued output/scopes and key sync first, `SwipeManager.releaseEverythingLocally`, then receiver release barrier. Reports PENDING/CONFIRMED/UNAVAILABLE/CONSOLEBRIDGE_UNAVAILABLE separately. |

The Windows phone endpoint is **UDP 42734**. Some Android fallback/default fields still use **9001**; do not infer the receiver's actual port from those comments/constants. ConsoleBridge binary output targets **9010**. Tether discovery has its own 12-second path and preserves the manual Wi-Fi endpoint. Floating/scoped support requires receiver capability and activation confirmation and is unavailable with CBv0; no Pico-wide Release All acknowledgement is implemented here.

## Control interaction

UI paths below are under **K/ui/**. The active entry is [`ControlView.kt`](source/android/app/src/main/java/com/example/simplecontroller/ui/ControlView.kt).

| File / block | Responsibility and coupled behavior |
| --- | --- |
| `ControlView.kt`: initialization, `outputScopeFor`, `refreshOutputRetention` | Per-control View wires ordinary/Auto-Tap/Aim/directional/TouchAim payload sources and independent movement. A floated control must not fall back to legacy output after its scope ends. Legacy busy state blocks protocol adoption mid-gesture. |
| `onDraw`, `drawTouchAim`, `onTouchEvent`, `editDrag` | Type-specific appearance, edit dragging/properties and direct play dispatch. Rendering must not mutate saved behavior. Direct Aim/directional/TouchAim surfaces retain original pointer events/history. |
| `playTouch` | Precedence: protocol preparation, local floating/page actions, Macro Button (exact Release All bypasses it), directional Button, Aim Button, Release All, Auto-Tap, ordinary Button, Recenter, stick, touchpad, TouchAim. Changing this order can steal an existing feature's gesture. |
| Ordinary Button branches | Per-button Hold Toggle qualification delays first fire; short touches before threshold do nothing in that mode. Latched lift differs from unlatch. Existing Turbo source lifetime is preserved when modifiers are enabled. |
| `handleStickOrPad` and touchpad branches | Analog/curved/directional/Stick+ movement; touchpad relative movement/scroll and separate held/toggle/double-tap click-lock gestures. Touchpad is not interchangeable with AimOutputSession mapping. Recenter drops the owned movement claim rather than acquiring neutral over another owner. |
| `releaseEverythingLocally`, `showProps`, detach hooks | End runtime handlers/timers/claims/suppression/retained output while preserving Control settings. Properties clear old output before editing. Silent detach requires prior cleanup. |
| `ControlViewHelper.kt` | Labels/edit helpers; `firePayload` retains **two ordinary payload paths**: undecorated legacy token/type dispatch versus modifier executor. `sendCommand`, `releaseLatched` own Xbox hold spelling, trigger P behavior and legacy releases. `createButtonAimPayloadExecutor` injects scoped/legacy/local-action adapters; separate `startMacro`, `releaseMacro`, `cancelMacro` methods adapt the finite controller even without suffixes; `hasPendingOutputWork` includes macro work. `cancelPendingActionsForReleaseAll` cancels macro and invalidates delayed callbacks. |
| `GlobalSettings`, `SwipeManager` in `ControlViewHelper.kt` | Runtime edit/snap/Hold/Turbo/Swipe/scroll settings and control/ring registration; global cleanup/recenter and notification on setting changes. TouchAim is registered for cleanup but excluded from swipe hit synthesis. No separate GlobalSettings.kt/SwipeManager.kt exists. |
| `SwipeHandler.kt` | `processTouchEvent`, `forwardEvent`, `cancelActiveTouch`: topmost eligible hits, synthetic local DOWN/MOVE/clamped UP and reentry cooldown. Aim/directional Button and TouchAim preserve direct dispatch instead. |
| `ButtonAutoTapController.kt` | `autoTapUnsupportedPayloadReason`, `toggle`, `stop`: ordinary Button toggle repeat with finite press windows, interval clamp and generation-safe stopping. Reject local navigation/safety/toggle actions; do not silently combine with incompatible modes. |

## Movement and aiming

| File under K/ui | Responsibility / search anchors |
| --- | --- |
| `ContinuousSender.kt` | `setLastPosition`, `startContinuousSending`, `stopContinuousSending`, `stopAndCenter`: ordinary analog resend/retention with captured scope; stale callbacks cannot acquire new output. |
| `DirectionalStickHandler.kt` | `handleDirectionalStick`, `handleStickPlusMode`, `desiredPayloads`, `stopDirectionalCommands`: WASD/Stick+ threshold/direction decisions, leased actions plus owned analog output. Private `startDirectionalSending` remains an uncalled legacy path; active transitions use the reconciler. |
| `DirectionalCommandStateController.kt` | `reconcile`, `clear(natural)`: acquire replacement before releasing departed direction/stage leases so shared keys survive diagonals and boost tier transitions. |
| `StickResponseCurve.kt` | Pure per-axis `apply` and radial `applyRadial` shaping; preserve direction/endpoints where intended. |
| `AimOutputController.kt` | `AimOutputConfig`, `AimOutputSession`, `ManualStickOwnershipRegistry`, `ManualStickArbiter`. Pointer→Mouse/Game Mouse/LS/RS mapping, origin/deadzone/displacement, response curves, retained output/takeover and Camera Follow exclusion. Manual stick ownership is separate from fixed-direction button macros. |
| `ButtonAimHandler.kt` | `startGesture`, Base/Alternate starts/completion helpers, `hardReset`, config extensions and `ButtonAimReleaseBoundaryPolicy`. Payload timing, aiming and phase/latch state are independent. Original pointer capture supports travel outside the button. |
| `ButtonAimDpadSession.kt` | `begin`, `update`, `detachPointerKeepingOutput`, `finish`: original pointer→leased four/eight-way directions/deadzone/origin, separate from companion Button payload. |
| `OneShotAlternateStateMachine.kt` | Pure `reduce`: BASE/ALTERNATE_ARMED/ALTERNATE_RECOVERY and reset arming. Reaching the reset threshold alone does not complete an intentional return to Base. |
| `ButtonDirectionalGestureHandler.kt` | `selectButtonDirection`, `transition`, `releaseAll(natural)`: original-pointer two-stage physical displacement/direction and cumulative payloads. When combined with Button Aim, shares executor but retains separate lease roles. |

Manual registry permits deliberate takeover of RETAINED output but not ACTIVE/DELAY_LOCKED ownership; LS and RS are independent. `AimOutputSession.detachPointerKeepingOutput` locks pending delay; `completeGesture(false)` retains analog output. `suspendAtBoundary`/`resumeFromBoundary` rebase aiming without a jump or new payload; sensitivity changes preserve origin. Resend callbacks remain generation-guarded.

Button Aim release-boundary cancellation applies to **SEND_ON_RELEASE** and consumes historical outside samples; optional return rearm does not imply a new aim session. Immediate Base can still use legacy transport; modifiers/one-shot/directional additions take leased paths. D-pad origin editor labels are **intentionally reversed relative to model enum names** in `saveButtonAimProperties`; inspect the existing mapping before “fixing” it.

## Payload execution and ownership

[`ui/ButtonAimPayloadExecutor.kt`](source/android/app/src/main/java/com/example/simplecontroller/ui/ButtonAimPayloadExecutor.kt) is a reusable engine, despite its Button Aim name. `HandlerButtonAimPayloadScheduler.kt` supplies the Android main-Looper/cancellable-task adapter; tests inject a fake scheduler/transport.

| Executor block | Meaning / invariant |
| --- | --- |
| `parseToken`, `LogicalKey`, policy validation | Classify/validate action payloads before output; preserve unmodified `activate`'s special RELEASE_ALL safety precedence even when another token is invalid. Classify Xbox/key/mouse/trigger-P/stick commands versus explicit force-release/edge actions. Logical ownership keys must agree with scoped transport's physical classification. |
| `activate`, leases, `reconcile` | Independent owners; boolean outputs release after the last owner, trigger/stick arbitration favors Alternate then latest. Ending Alternate restores still-held Base; it must not overwrite unrelated scoped owners. |
| `activateModified` | Per-command D/R/SOR/HRD/U runtimes and finite `completeOnRelease` mode. D deadlines run independently in parallel; R owns cadence; SOR is finite normal-release output; HRD extends only an already active hold. U caps each owned ON duration; Macro finite completion survives natural lift. |
| `releaseNormally`, `transferLiveHoldRelease` | Permit release shots/hold tails. Matching TouchAim reentry resumes a live HRD hold without a gap or another D and transfers its original U deadline; an expired/canceled tail never revives. Canceled pending D cannot become a hold, and R's OFF phase cannot be reactivated by HRD. |
| `releaseTogether` | Remove all supplied leases before reconciliation, preventing a transient restoration of Base between related releases. |
| `releaseOwner`, `releaseAllLeases`, scheduled callbacks | Hard cancellation of pending timers/output, with identity/generation checks against newer activations. Preserve main-thread confinement in production. |
| `replayHeldOutput`, explicit force release | Authored replay/force actions differ from normal ownership removal. Another legitimate owner can mask a pulse's edge; do not force it up merely to manufacture an edge. |

Non-Macro ordinary Buttons without modifiers and unmodified three-stage TouchAim retain separate legacy dispatch/reconciliation paths. A change to this executor alone does not automatically change those paths. Conversely, Macro and stateful stage/ring/directional callers depend on it, so a “Button Aim fix” can affect other controls.

For full timing/default/range semantics use the [modifier contract](source/android/PAYLOAD_MODIFIERS_AND_STAGE_TUNING.md). Essential interactions: global Turbo excludes explicit R and keeps HRD commands steady; source Hold Toggle ends on unlatch; same-output repeat/Turbo must stop before a SOR pulse with the existing release gap; finite Tap/Auto-Tap/ring windows remain finite even with modifiers.

## Macro Mode and U

Finite Button Macro Mode uses [`ui/ButtonMacroController.kt`](source/android/app/src/main/java/com/example/simplecontroller/ui/ButtonMacroController.kt); read the [porting/timing contract](source/android/MACRO_MODE_AND_PAYLOAD_PORTING.md) before changing these paths.

| File / new block | Behavior and invariant |
| --- | --- |
| `ButtonMacroController.press`, `isActive` | Validate nonblank reversible output, reject R/edge/toggle/force-UP/Release All, then activate BASE with default **90 ms** and `completeOnRelease=true`. Extra presses while its lease is active are ignored, not restarted/queued. Authored trigger P is respected; holding the same touch does not repeat a completed run. |
| `release`, `cancel` | Normal source release preserves finite immediate steps and resolves SOR once. Global Swipe exit/handoff synthesizes normal UP while the physical finger may remain down; it is not hard cancellation. Cancel calls `releaseAllLeases` and clears the run; lifecycle/Release All/Finish must not complete pending steps in the background. |
| `ControlView.playTouch`, `macroPointerId` | Macro branch follows local actions and precedes directional/Aim/Auto-Tap/ordinary routing. Track initiating pointer: normal UP/its POINTER_UP releases; missing pointer/CANCEL cancels. Early return ignores global Hold/Turbo. Properties cancel before and after editing; local cleanup avoids legacy release reinterpretation. |
| `ControlViewHelper` macro methods | Lazy controller uses `createButtonAimPayloadExecutor("macro")`, preserving existing scoped output/ownership and legacy adoption-busy checks. |
| `PropertySheetBuilder` Macro checkbox/save | Ordinary Button only; reciprocal exclusion with Hold Toggle, Auto-Tap, Button Aim and directional Button. Local page action disables it. Save can toast an invalid-payload error without clearing the saved flag; runtime validation rejects the activation. Do not assume raw imports resolve every conflict like editor switches. |
| `PayloadModifiers.parse`, `repeatPressDurationMs` | U is whole **1–600000 ms**, once/command, case-insensitive. Reject U with trigger P; R+U requires U<R. `hasReleaseModifiers` detects attempted `(U`, including malformed syntax, for raw version checks. |
| Executor `activate(..., completeOnRelease=false)`, token `completesAfterRelease` | Normal callers retain existing default; finite Macro opts in. Only finite nonrepeat commands preserve pending D/press work after normal release, not unbounded HOLD/R. |
| `scheduleModifiedPressEnd`, `scheduleUpDeadline`, `bindUpDeadline`, `UpDeadline` | U starts when output actually activates after D. Mutable expiry binding transfers the original scheduled deadline to a matching HRD reentry lease; never restart D/U or let old-parent cleanup cancel the new legitimate owner. |
| `scheduleRepeatedTimers`, `repeatImmediatePress` | Outside Macro, U selects each R ON duration. Global Turbo cannot truncate/restart an active U press; later eligible ticks may start another after it ends. Preserve original U during HRD transfer; do not assume repeat cadence is otherwise unchanged by reentry. |
| `finishModifiedPress`, `clearUpDeadline` | Earliest finite/U or HRD expiry retires both callbacks and drops only that child's claim. Hard release cancels deadline bindings as well as scheduled tasks. |
| `finishModifiedIfIdle`, `isLeaseActive` | Prune completed Macro even before finger-up, except while an unsuppressed SOR remains armed; lease activity controls ignored overlapping taps. |

D delays stay independent: Macro `X360A(U100),X360A(D500)(U100),X360B(D800)(U100)` gives A 0–100, A 500–600, B 800–900 ms from initial DOWN even if lifted at 20 ms. `CTRL,ALT,TAB` holds all three together for 90 ms, not a sequential script. SOR's D origin is normal source release, not initial DOWN.

Outside Macro, early normal source exit still cancels pending D and may end an active U early; existing finite PRESS preservation rules remain. While the source stays released, U+HRD ends at the earlier U deadline or source-release+HRD. Eligible HRD reentry cancels that release tail and resumes the hold while preserving its original U cap. Explicit SOR+U uses U duration and HRD cannot extend it; SOR without U retains default/P plus HRD behavior. R is supported with U outside Macro but rejected by the finite Macro controller. U removes only its own claim, so another held owner can mask release/press edges. No receiver/Pico change is needed: Android emits the existing plain commands.

## TouchAim

| File under K/ui | Responsibility / anchors |
| --- | --- |
| `TouchAimHandler.kt` | `startGesture`, `updateContact`, `updateScore`, `transitionTo`, `applyTwoStateActions`, `finishGesture`, `releaseAll`: original-pointer contact/history, manual/calibrated scores, continuous aim, confirmed stages/sensitivity and held/finite output. |
| `TouchAimLegacyLevels.kt` | Active pure `manualTouchAimScore` and `legacyTouchAimLevelForScore`: existing Size/Major/Minor score, threshold/hysteresis behavior. “Legacy” does not mean unused. |
| `TwoStateTouchAimStateMachine.kt` | Pure AIM/SHOOT detector with time-aware smoothing, hysteresis, independent entry/return confirmations/actions. `onContactStarted`, `onScore`, `nextDeadlineMs`, `onTime`, `terminate`; terminal events never fabricate threshold-return shots. |
| `TouchAimStageConfirmation.kt` | Pure destination-stage timing, `onTarget`, `onTime`, `reset`, `enteredTouchAimPressLevels`. Previous confirmed stage stays active while a candidate waits; stationary input can complete via timer. |
| `TouchAimStagePayloadController.kt` | `transition`, `finish`, `touchAimStagePayloadValidationError`: modified three-stage HOLD eligibility/carryover, PRESS and retired HRD resume. One modified stage opts the set into reversible leased stage handling. |
| `TouchAimCalibrationAnalyzer.kt` | Pure `analyze`, `score`, `evaluate`, `tune`, `simulate`: robust sensor normalization/subset choice, thresholds/smoothing/confirmations, whole-pass held-out simulation through the same runtime detector and quality assessment. |
| `TouchAimCalibrationWizard.kt` | Output-suppressed preparation, AIM0/SHOOT0/AIM1/SHOOT1 recordings, review/analyze, live no-output validation, atomic store save and target apply. `startRecording`, `analyzeRecordings`, `startValidation`, `promptApply`, `buildProfile`, `handleCaptureTouch`, `cancel`, `close`. |

Three-stage `transitionTo` keeps the unmodified held-command/crossed-PRESS path unless modifiers opt into the stage controller. With all confirmations zero, crossed intermediate PRESS behavior is preserved; with confirmation, unconfirmed intermediate stages must not fire. Null stage sensitivity inherits the control's common sensitivity without resetting aim origin.

Lower-stage HOLD carryover remains eligible through higher stages and back. HRD begins on loss of eligibility or normal lift, not every stage change; lifting during an existing tail keeps its deadline. Two-state threshold-return fire is different from finger-lift SOR. `finishGesture(false)` allows normal tails; `releaseAll` cancels them, confirmations and aiming. Wizard records original event history, treats interrupted passes as incomplete, requires Aim→Shoot→Aim validation before apply, and invalidates delayed callbacks on close. Removing the standalone sensor-test Activity did not remove this wizard.

## Floating controls

All UI paths are under **K/ui/**; saved configuration is `model/FloatingControls.kt` plus orientation/preset models. Runtime instances are detached from saved placement.

| File | Responsibility / anchors |
| --- | --- |
| `FloatingControlsController.kt` | `bindPage`, `dispatch`, `summon`, `finish`, `clear`, `hiddenMemberIds`: active page's instances, topmost activation/readiness, captured pointer, translated group/ring views, local actions and instance Finish. |
| `PendingFloatingGesture.kt` | Pure `begin`, `update`, `cancel`: bounded wait for scoped readiness, current generation/context, latest position; 1500 ms timeout/16 ms polls. Lift cancels waiting, not a later synthetic press. |
| `FloatingActivationEvents.kt` | `floatingActivationDown`, `resumedFloatingEvent`: preserve contact properties at post-ready activation, filter preactivation history and convert an obsolete terminal to CANCEL rather than a release shot. |
| `FloatingPlacement.kt` | Translation-only fit/anchor/grab offset, bounds/containment and `FinishEntryGuard`. Reject oversized instances rather than shrinking reachable targets. |
| `FloatingRingGeometry.kt` | Pure density-aware roles/knobs/movement/direct boost bands/custom layouts: `roleAt`, `directBand`, `boostDistance`, `boostHysteresis`, `startAnchor`, `unreachableDirections`. |
| `FloatingRingView.kt` | Runtime rendering/status, original MotionEvent/history forwarding: `beginInitial`, `playTouch`, `acceptsDown`; Recenter registration and hard detach cleanup. |
| `FloatingRingStateMachine.kt` | Pure role/pointer/movement/aim/ADS/fire/fine/boost/return-fire/delay/pulse/repeat/retained state. Backend/status interfaces; `down`, `sample`, `up`, `cancel`, `finish`, `hardCleanup`, `evaluateBoosts`, `enterBoost`. |
| `AndroidFloatingRingBackend.kt` | Bridge pure ring to scope, `AimOutputSession`, manual-stick ownership, payload controller, suppression and conflict feedback. `closeScope` reports unconfirmed cleanup as such. |
| `FloatingRingPayloadController.kt` | `acquire`, `reserve`, `release`, `endNormally`, `end`, `close`: reserved slots and modifier executor lifetime. Keep original slot ownership through repeat OFF gaps/tails. |
| `FloatingControlsEditor.kt` | `selectButtons`, `previewMembers`, `edit`, `duplicate`, `ungroup`, `editControlOptions`: conversion, zones/member geometry, banks/knobs/boosts/presets/scoped actions and validation. Full preset versus boost-only application has different boundaries. |
| `FloatingSelectionSession.kt` | Deferred rectangle completion/preview cancellation with current context/generation; Back/pause must veto queued preview even before rectangle completion. |
| `FloatingZoneOutline.kt` | Edit-only one-pointer activation rectangle movement, stale/canceled rollback, tap/hold editor; `cancelEditingGesture`, `applyRectangle`. Does not resize member targets. |
| `FloatingRingEditOverlay.kt` | Decorative editor ring/boost guides at zone center; `updateRectangle`, `onDraw`; no output backend. |
| `FloatingRingEditorPreview.kt` | Read-only scaled bank-specific reachability/direct-band feedback; no runtime session, handler or ownership. |

Movement/aim roles are captured for a gesture; crossing a visual region does not freely change gesture role. Direct Boost 2, return-to-Boost-1, delayed activation, skip-delay-on-direct-touch, cancel-on-exit, latch-on-exit and role-bank selection are independent saved choices. Finish should end **that instance's** claims/timers/suppression and hide it, preserving other instances and its saved configuration. Global Release All is broader. Preserve hit priority, visible-control/UI exclusion, pending-contact cancellation, both orientations, grouped-button restoration, retained takeover rules, fire timing and ring output-conflict validation when adjusting geometry or boosts.

State-machine block anchors: `startAim`/`leaveAim`/`returnToAim` manage independent ADS/fire/fine output; `evaluateBoosts` handles captured bank/custom geometry/hysteresis and cumulative tier transitions; `enterBoost` owns activation delay/generation. `startBoostEntryFire`/`scheduleReturn` derive deadlines from qualifying sample time and allow later history/cancel to invalidate an overdue action. `hold`/`finitePulse`/`repeat` retain separate reservation windows; `stopOutputNormally` permits normal tails while `stopOutput` hard ends them. `up` is a normal end; `cancel` preserves legitimate preexisting retained claims while undoing the current gesture's new/replaced work; `hardCleanup` ends the instance. Final-UP sampling must not invent a new boost activation. `stopFiring`/`releaseAds` consider logical-output overlap, not raw payload spelling; `recenterMovement` changes movement without stealing the winning owner's claim.

## Property editors and styling

| File under K/ui | Responsibility / anchors |
| --- | --- |
| `PropertySheetBuilder.kt` | Programmatic Control editor. `showPropertySheet`, `buildUIComponents` construct shared fields; Button Aim/TouchAim/directional/boost/page-action builders manage per-mode visibility and libraries. `saveProperties` and typed save helpers parse/clamp/assign with feature-specific validation and fallbacks; this is not an atomic whole-form validation transaction. A hidden/inactive setting may still need persistence/compatibility checks. |
| `ControlTuningInputs.kt` | Pure finite 0–5 sensitivity parsing, optional blank→inherit, confirmation blank→0/whole milliseconds ≤600000: `parseSensitivityInput`, `parseOptionalSensitivityInput`, `parseStageConfirmationInput`. |
| `AppUiStyle.kt` | Shared button/surface/ripple/dp styling. |
| `PropertySheetStyle.kt` | Shared recursive editor typography/colors/fields/buttons/dialog styling; `styleContent`, `button`, `dialog`. |
| `ThemeManager.kt` | `init`, `toggleDarkMode`: `theme_preferences` and AppCompat day/night; default dark. Keep theme/device preferences outside profile transfers. |

Adding an editor field usually needs UI construction **and** validation/save, model default, copy/profile capture+apply, conditional schema/raw preflight, backup and tests. Read the entire relevant save branch; constructing a widget alone does not implement a persisted feature.

Property-sheet blocks to pair when editing:

| Construct / save anchors | Scope |
| --- | --- |
| `addButtonAimUI` / `saveButtonAimProperties` | Base/Alternate output, feel, timing/recovery, D-pad and boundary settings. |
| `addButtonDirectionalUI` / `saveButtonDirectionalProperties` | Direction and two physical-distance stages independent of aim sensitivity. |
| `addTouchAimUI` / `saveTouchAimProperties` | Sensor/mode, thresholds/calibration, aiming/actions and per-stage tuning; preserve inactive manual/calibrated state. |
| `addDirectionalCommandsUI`, `addExtendedBoostUI`, `addDirectionalThresholdControls`, `updateDirectionalModeSettings`, `readDirectionalThresholds` | WASD/Stick+ commands, shared/per-direction thresholds and extended boosts. |
| `promptSave*Profile`, `show*ProfileList`, `show*ProfileActions` families | Reusable catalog/capture/application choices; aiming-only versus complete scopes differ. |
| `addSensitivityField`, `addTouchAimStageTuning`, `saveProperties` | Numeric parsing/inheritance plus common dimensions/local actions/conflicting toggles and update callback. Some subdialog buttons save before opening; later Cancel is not rollback of those earlier edits. |

## Wire contracts

| Channel | Sender / consumer | Contract and edit boundary |
| --- | --- | --- |
| Phone discovery, registration, heartbeat | `NetworkClient` / receiver `udp_server`, `process_command` | `SIMPLE_CONTROLLER_DISCOVER` → `SIMPLE_CONTROLLER_HERE:42734`; CONNECT/REGISTER, player prefix and PING/PONG. Separate from output capability negotiation. |
| Legacy ordered phone output | `OutputSafetyEpoch`, UdpClient / receiver router | `OUTPUT:<session>:<seq>:<command>` supports release/adoption barriers; **not** universal latest-packet-wins ordering for every command. Stick variants share per-player/physical-side freshness; strength has its own freshness gate. |
| Scoped phone output | `PhoneOutputProtocol`, coordinator / `phone_output_protocol.py` | Capability uses `PHONE_CAPS_V1` → `PHONE_CAPS_V1_ACK`; activation/state/event/end use `PHONE_ACTIVATE_V1`, `PHONE_STATE_V1`, `PHONE_EVENT_V1`, `PHONE_END_V1` → `PHONE_ACK_V1`. Percent-encoded fields, physical output keys, per-key revisions, scope generations/event identities and end tombstones. State ACK may report MASKED/CLEAR due to another source. |
| Global phone cleanup | Android coordinator/client / receiver release path | `RELEASE_ALL:<session>:<seq>` → exact `RELEASE_ALL_ACK`. Duplicate-safe barrier; stale queued/delayed work must not revive old output. CBv0 lacks the corresponding confirmed implementation. |
| Plain actions | Android handlers/executor / receiver adapters | Xbox/key/mouse/trigger/stick commands; DELTA versus GAME_DELTA, Camera Follow and strength. Android strips/executes payload modifiers; these suffixes are not new native receiver/Pico syntax. |
| Facial input | `receiver_client.py` / `FaceInputServer` | Loopback TCP **42735**, newline JSON; v1 single expression, v2 independent mouth/brows, v3 channels/movement/wink/source watching. Capability, session, epoch, sequence, arm, capture freshness and heartbeat checks. |
| Voice input | `VoiceAttackSender.cs` / `VoiceInputServer` | Loopback TCP **42736**, newline JSON **v1**; ready greeting and one request/ACK per connection. Receiver UUID/epoch plus sender UUID/sequence; receiver owns finite duration. |
| Shared camera feed | `SharedFramePublisher` / `SharedFrameReader` | Local shared memory, `TSMFACE1` v1, 128-byte header and two bounded BGR frame slots with revision seqlock. Publisher owns unlink, reader attaches read-only. This publisher is separate from the phone/receiver protocol. |

Phone UDP binds `0.0.0.0:42734` without authentication: capability/session/sequence checks provide compatibility and freshness, not authorization. Face/voice endpoints are loopback-only. Before adding a command, trace parsing and cleanup at **both ends**, payload validation, logical output classification, ordering/retry behavior and owner release. Never let an unknown control command fall through to an ordinary keyboard action accidentally.

## Windows receiver

Base **R**: [`source/receiver/`](source/receiver/). Read [its README](source/receiver/README.md) before running or packaging. Inspection of the entrypoint must be textual/AST-based or use deliberately installed native mocks; an ordinary import has live effects.

| File / group | Responsibility and anchors |
| --- | --- |
| `simple_controller_receiver.py` | Composition root: native adapters/controllers, UDP router, optional face/voice servers, generation-safe timing, Camera Follow, global cleanup and logging. Block map below. |
| `phone_output_protocol.py` | `state_key`, `inactive_command`, `active_command`, `PhoneEventGuard`, `PhoneOutputProtocolState.process`. Capability/session adoption, per-key desired state plus scoped events, freshness, duplicate ACKs, generation tombstones, validated aggregate application. Triggers use maximum active pressure; other supported states choose the newest eligible active owner. |
| `face_input_server.py` | `MergedDesktopInput`, `MergedGamepad`, `FaceInputState`, `FaceInputServer`. Shared native ownership proxies also serve phone/voice; facial protocol validation, capture leases, tap IDs, source watching, takeover, heartbeat and bounded TCP service. |
| `voice_input_server.py` | `decode_request`, `VoiceInputState`, `VoiceInputServer`. Strict JSON and typed action validation, receiver epoch/session/sequence, finite overlays/persistent holds, duplicate recognition, local request service and voice-only/global cleanup. |
| `camera_follow.py` | Pure `CameraFollowSettings`, `CameraFollowState.step`, `_camera_target`: effective right-stick selection, movement-driven target/ramp, manual override and live strength. Native reporting stays in the composition root. |
| `relative_mouse.py` | `win32_input_types`, `_native_api`, `send_relative_mouse`: complete 32/64-bit Windows INPUT ABI, lazy user32 binding, signed bounds and SendInput failure handling. Relative motion only. |
| `SimpleControllerReceiver.spec` | Portable SPECPATH-based PyInstaller recipe, five companion hidden imports and both ViGEmClient DLLs, console executable. Does not install ViGEmBus. |
| `requirements.txt` | Pinned receiver/packaging dependencies; includes vgamepad, keyboard, mouse, PyAutoGUI and PyInstaller. Keep compatible native packaging as well as Python imports. |
| `ViGEmClient/x64/ViGEmClient.dll`, `ViGEmClient/x86/ViGEmClient.dll` | Bundled native client libraries mapped to vgamepad package paths. Separate from the installed bus driver and root public EXE. |

### Receiver entrypoint block map

| Block / anchors | What it does |
| --- | --- |
| Imports/configuration, `HOST`, `PORT`, `CAMERA_FOLLOW_SETTINGS`, `get_lan_ip_candidates`, `show_connection_info` | Select socket/settings, logs and connection display. Read explicit `CAMERA_FOLLOW_SETTINGS` as well as dataclass defaults: the root overrides those defaults. |
| `release_state_lock`, `write_desktop_input`, `send_tagged_key`, `desktop_input`, `gamepads`, face/voice objects | Construct two merged native pads, desktop writer/provenance tagging, ownership proxies and server state. Import-time effects include AppData log creation and disabling PyAutoGUI's default pause/corner fail-safe. |
| `StabilitySmoother`, `smoother` | Legacy absolute touch-position smoothing. Relative DELTA/GAME_DELTA paths do not pass through it. |
| `output_packet_is_current`, `current_release_generation`, `release_outputs`, `release_all_outputs` | Stale-envelope barrier, comprehensive output cleanup, timer generation invalidation and duplicate-safe release acknowledgement. |
| `handle_game_mouse_delta`, `handle_touchpad`, `handle_mouse_buttons`, `handle_scroll` | Windows mouse adapters. DELTA uses PyAutoGUI movement; GAME_DELTA uses SendInput. Both apply the current 40× gain; Game Mouse validates finite coordinates and truncates to integers. |
| `handle_stick_input`, `handle_trigger_input`, `apply_camera_follow_output`, follow worker | Manual/macro stick routing, trigger adapters, follow calculation/reporting and idle handling. Macro RS, manual RS and follow are separate candidate sources. |
| `neutralize_legacy_phone_outputs`, `phone_output_is_masked`, `apply_phone_aggregate`, `dispatch_phone_event`, `dispatch_scoped_legacy_state` | Negotiated ownership adoption, physical aggregate adapters, masking feedback and scoped legacy compatibility events. Adoption preserves independent face/voice owners. |
| `handle_wait_command`, `handle_key_press`, `handle_key_release`, `handle_button_press`, `process_timed_sequence` | Legacy keyboard/Xbox actions, finite press tokens, comma/WAIT work and guarded timers. Single direct WAIT handling can sleep; legacy sequence threads are a different path. |
| `process_command` | Routing order: negotiated phone protocol, release/ordered envelopes, special controls, mouse/buttons/keys/triggers/sticks and legacy combinations. Inspect exact order before adding syntax. |
| `clean_inactive_connections`, `clean_key_states`, `start_cleanup_scheduler`, `udp_server`, `__main__` | Socket loop and worker lifetime, housekeeping and keyboard reconciliation. Main thread runs UDP; daemon workers handle cleanup/Camera Follow/face/voice. |

### Receiver ownership, concurrency and limitations

- Shared `release_state_lock` is an RLock around pad/owned-state transitions and reports, generations and aggregate application; not every raw legacy mouse/scroll call takes it. Preserve its ordering with Camera Follow's internal state lock. Legacy finite callbacks use generation/token/scope guards; an old timer must not release a newer press.
- `MergedGamepad` combines phone/face/voice digital buttons with OR and triggers with maximum pressure. Nonzero phone left-stick input takes priority over face movement; v3 takeover disarms interrupted facial movement-related channels. A held owner can mask another source's tap edges.
- `MergedDesktopInput` merges approved phone/face key and mouse-button ownership globally. Phone keyboard output is Player 1 only; facial desktop output is global and can be owned by either selected player. The Windows mouse is one shared stream. Legacy compound-key fallbacks and native VoiceAttack keyboard/mouse actions do not all receive these ownership protections.
- Camera Follow worker runs about every 10 ms. Defaults are off; movement/manual-RS deadzones .20/.15, activation 200 ms, manual override 750 ms, gain .55, cap .45, ramp in/out 180/120 ms. Strength starts at 100%, accepts 0–200%, and affects response and cap. Selection considers macro RS, manual RS, manual-override cooldown and generated follow; excluded LS still moves without generating follow.
- Facial service accepts one active client; 500 ms heartbeat failure clears/disarms it. V3 ordinary capture leases last 250 ms and taps 100 ms. Explicit retained nose movement has a narrower lease exception but still ends on heartbeat/session cleanup, clear/pause or phone takeover. Source-watch queues are bounded; overflow must disarm rather than silently lose edges.
- Voice service accepts bounded independent connections (one request each). Tap defaults to 100 ms; finite durations are 1–600000 ms. Omitted Hold/trigger duration intentionally persists after TCP disconnect. Finite overlays must preserve persistent baselines. `clear` ends voice state on both players; `release_all` calls comprehensive receiver cleanup and advances voice epoch.
- Receiver-global release from voice has no phone session/sequence barrier and cannot stop Android's internal Hold/Turbo loops; new phone packets can reassert them. Phone Release All additionally clears those Android loops and sends its barrier.
- **Current cleanup boundary:** `DISCONNECT` and 30-second inactive-connection expiry only remove connection records. Five seconds without phone activity clears follow/manual/macro RS state, not every phone hold. Ordinary main-loop `finally` stops face/voice/follow but does not explicitly call comprehensive phone `release_outputs`. Do not promise that these paths release every keyboard/mouse/button output.
- The 10-second housekeeping scheduler can re-press tracked Player 1 keys that the keyboard library reports released; retained keyboard state is deliberate. Logs persist under `%LOCALAPPDATA%/SimpleControllerReceiver/touchpad_logs` (working-directory fallback without LOCALAPPDATA). Connection/output/Camera Follow state is in memory; receiver profiles/preferences are not stored in a separate JSON library here.

For a new reversible output, change receiver key classification/inactive state, aggregate/native adapters and Android emission together. Exercise overlapping owners, player isolation, duplicate/stale packets, ended scopes, adoption, masking, native failure and Release All. For Camera Follow changes inspect both explicit root configuration and pure state logic; for Game Mouse verify ABI/bounds with fake native APIs before any separately authorized live output.

## Facial app

Separate optional Python/Qt application: [setup and packaging](facial-input/README.md). Base **F**: [`facial-input/src/facial_input/`](facial-input/src/facial_input/). It sends semantic actions to the receiver and creates no virtual gamepad itself.

### Startup and work distribution

`Start Facial Input.cmd` uses the adjacent source `.venv` and launches `-m facial_input`. Frozen startup goes through `scripts/start_gui.py`, Windows `freeze_support`, then `__main__.main` → `gui.MainWindow` → `runtime.AppRuntime`. Explicit `--check-package` and `--collect-resources` dispatch separately.

MainWindow starts paused with preview off, but **paused output does not mean camera/inference/client activity is stopped**. The Qt thread renders snapshots (100 ms refresh) and edits settings; it must not perform capture/inference or blocking process/queue joins. Runtime has receiver IO, supervision/worker-management and queue-draining threads; supervisor ticks about every 15 ms. A spawned inference process owns native ML; webcam mode owns a further capture child. Bounded latest-only queues prevent backlog. Owned Windows process jobs are bound before native startup; shutdown/restart uses bounded child cleanup. `--check-package` is handled by the frozen/start_gui wrapper, not direct `python -m facial_input`; `--collect-resources` is a leading-mode dispatch in `__main__.main`.

### Facial file and block map

| File under F | Responsibility / stable anchors |
| --- | --- |
| `__init__.py` | Package metadata; package-only import opens no device/connection. |
| `__main__.py` | `main`: argument parsing, settings/model/source overrides, Qt/window creation and shutdown; explicit resource-collector dispatch. Invalid saved settings are reported, not overwritten. |
| `gui.py` | Pointer-friendly widget classes and `MainWindow`. `BindingEditor` handles expression settings; `AxisEditor`, `MotionEditor`, `ExpressionMovementEditor`, `BoostEditor`, `AnchorEditor` handle movement/stages. `ChordPicker` builds shortcuts through separate pointer actions. Wheel-resistant controls prevent accidental edits. |
| `runtime.py` | `AppRuntime`: in-memory settings/mode, fresh results, inference lifecycle, movement/output generations and arbitration. Read `start_workers`, `_manage_workers`, `_read_output`, `_observe`, `_tick`, `_tick_extended`, `_bridge_motion`, `set_settings`, `set_mode`, `restart_camera`, `recenter`, `_clear`, `stop`. |
| `worker.py` | `inference_worker`, `demo_worker`, `put_latest`; MediaPipe inference runs in the inference worker, webcam OpenCV capture in its owned capture child. Explicit package probe is separate. `scores_from_blendshapes`, `wink_score_from_blendshapes`, `features_from_landmarks` convert results into bounded scalar features. |
| `camera_source.py` | `_CaptureJob`, `_capture_worker`, `_capture_frames`, `_bounded_frame`, `WebcamSource`. Explicit camera ownership, normalized ROI, bounded frame size, original opening/stall deadlines, owned descendant cleanup. No automatic fallback camera. |
| `frame_feed.py` | `FramePacket`, `SharedFramePublisher`, `_ReadOnlyMapping`, `SharedFrameReader`. TSMFACE1 v1, 128-byte header, two ≤640×480 uint8-BGR slots and revision seqlock. Shared mode requires an external publisher, not bundled here; readers must never unlink it. Coordinate producer/reader ABI changes. |
| `detection.py` | Pure `Sample`, `ExpressionGate`, `Calibration`. Fresh distinct samples, neutral rearm, reliable/responsive confirmation, Tap/Hold and in-memory calibrated thresholds. No camera/files/native output. |
| `movement.py` | Pure configuration/validation (`AxisConfig`, `MovementConfig`, `ExpressionMovementConfig`, `DirectionAnchor`, `BoostStage`) and `MotionProcessor`, `ExpressionMovementProcessor`, `BoostProcessor`. Origins/centering, smoothing, fixed/proportional/step/sweep directions, threshold actions and WASD/stick output. `direction_for_vector` aliases `vector_direction`. |
| `receiver_client.py` | `ReceiverClient`: bounded outgoing queue, loopback socket worker, capabilities/protocol selection, arm/sample/clear/heartbeat scheduling, epoch/sequence and source-watch/takeover/expiry notifications. No native controller creation. |
| `settings.py` | Frozen `ExpressionBinding`, `AppSettings`, validation/default paths, typed load and append-only `save_settings`. Latest settings revision wins; preserve an invalid saved document instead of silently resetting it. |
| `profiles.py` | `CalibrationProfile`, `MovementProfile`, validation/save/load/list/apply. Independent expression/movement snapshots; applying one preserves unrelated bindings/preferences. Session origins are excluded. |
| `shortcuts.py` | `ShortcutKeys` low-level Windows keyboard observation and `ShortcutEdges` edge gates. Only configured keys; ignore facial-tagged injection, preserve modifier sides, require release of a preheld chord, bound event storage. |
| `telemetry.py` | `ScalarTelemetry`, `read_scalars`, `read_monitor_export`: local latest-only scalar status. No frames, feature/score streams or typed text. |
| `resource_metrics.py` | `WindowsSampler`, `PagingCounters`, `create_run`, `collect`, `frame_time_summary`, CLI `main`: separately requested scalar CPU/RAM/IO/fault/cadence collection and optional owned PresentMon. New private run directories/reports; collector lifetime is independent of GUI shutdown. |
| `packaging_runtime.py` | Model/source/frozen resource resolution and `CollectorLaunch`/collector preparation. Preserve explicit missing custom-model selection as an error; do not silently choose another model or persist a temporary frozen extraction path. |
| `package_check.py` | Explicit `main`/`_native_probe`: model digest, synthetic native inference in bounded spawned child, offscreen Qt and new report. No personal settings, webcam or receiver connection. |

Large GUI blocks: `_settings_changed` clears/pauses output and marks unsaved; `_apply_camera` commits pending source/index/ROI edits. `_select_region`, `_region_corner`, `_selection_geometry`, `_cancel_region` implement **two-tap** ROI selection with generation/geometry checks. `_toggle_live`, `_toggle_test`, `pause`, `_open_calibration`, `_begin_stage`, `_finish_calibration` own mode transitions. `_save_profile`/`_load_profile` and movement counterparts are separate from explicit `_save`. `refresh` only renders snapshots/guarded shortcut requests; `_launch_resources` starts the independent collector. `closeEvent` quits; `minimize_to_tray` retains the process; `shutdown` is idempotent.

### Facial invariants and persistence

- Fresh defaults: **shared** camera source (not webcam), camera 0, 640×480/no ROI, 15 FPS; mouth→A Tap, Player 1, reliable preset. Multiple expressions, wink/movement and toggle chords start disabled/empty. Setup instructions explicitly choose webcam when wanted.
- Start eligibility requires reviewed/calibrated enabled input, compatible ready receiver, fresh tracking and applicable movement centers. After gate clear/reset, expression activation separately requires fresh neutral/rearm. Gate defaults are ON .60, OFF/neutral .35; fresh samples ≤250 ms, neutral 100 ms, at least two distinct activation samples, reliable/responsive confirmation 150/50 ms. Repeating one cached frame must not satisfy confirmation.
- Pause/Test/calibration/settings changes clear and suppress action output while processing/control heartbeats may remain active. Scene/camera generations invalidate old inference, session calibration/centers and pause. Client guards must check freshness again when actually sending, not only when queuing.
- **Intentional exception:** accepted nose-only movement may be retained through tracking loss when capability, live mode, generation/epoch and ownership allow. `_bridge_motion` does not resubmit stale samples. Pause/recenter/reset/takeover invalidates it. Do not replace this with a blanket “all tracking loss immediately releases every movement” assumption.
- Extended arbitration prioritizes active mouth route, then brows, then motion. Phone takeover blocks/disarms interrupted facial movement; old off-center/held routes must not silently resume. Origins are session-only; geometry/source/inversion changes can require recentering.
- Boosts use stable action identities, distinct Hold/Tap behavior and Regular/Super/Outer thresholds. A large jump taps only the final applicable stage. Facial diagonal tokens use **DR/DL**, while Android stick macros use **BR/BL**.
- Client protocol 3 is required for movement, wink or phone-Xbox shortcut observation. Check capabilities for desktop/movement/retained nose output; bounded queues, disconnects/epoch changes/rejected replies must invalidate pending actions. Heartbeat is 100 ms only while supervisor pulse is fresh (250 ms); reconnect never replays old actions.
- Settings/profile envelopes are version 1. Private data lives under `%LOCALAPPDATA%/FacialGamingInput`: nominal `settings.json`, append-only `settings-<stamp>-<id>.json`, expression calibration and movement-profile directories. Save is explicit; preserve prior revisions, unrelated expressions, custom models and user preferences.
- Source webcam opens have a 20-second original deadline and 2-second fresh-frame stall deadline. Progress does not extend opening. Shared feed readers are read-only. Neither path should start/stop an unrelated camera publisher or move capture/native inference onto Qt's thread.

### Facial launch, build and support files

Paths in this table are relative to **facial-input/**.

| File | Role |
| --- | --- |
| `Start Facial Input.cmd`, `scripts/start_gui.py` | Source environment launcher versus frozen entrypoint; preserve Windows spawn initialization. Neither launcher installs dependencies. |
| `pyproject.toml` | Setuptools source package, Python 3.12 restriction, runtime dependencies, console entrypoint and original pytest configuration. Configured original test directories are not shipped in this main snapshot. |
| `requirements-lock.txt`, `packaging/requirements-build.txt` | Runtime/development pins versus separate PyInstaller tools. Native ABI/DLL packaging must be checked when dependencies change. |
| `scripts/download_model.py` | Explicit official-model download, bounded response, exclusive creation and printed hash; downloader itself does not enforce expected digest. |
| `scripts/build_windows.py` | Verify expected model digest, isolate work/dist/cache, collect component notices, restrict DLL lookup, record source/artifact hashes. Refuses an existing EXE unless explicit replacement option is given; packaging does not deploy. |
| `packaging/FacialInput.spec` | One-file windowed EXE, model/licenses, MediaPipe and Qt native runtimes, exclusion of conflicting bindings/developer tools. |
| `scripts/collect_resources.py` | Source wrapper for the separately requested resource collector. |
| `packaging/THIRD_PARTY_NOTICES.md` | Component and model provenance/licensing notices; retain with distributions. |
| `.gitignore` | Private settings/calibration/resource runs, environments, model, generated packaging and caches stay local. |

Source model `models/face_landmarker.task` is not committed. Builder/checker enforce expected SHA-256 `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`; frozen package supplies it. `--demo` is an explicit synthetic diagnostic, not unused code. Package-check success proves neither real-face recognition nor camera/game comfort. Resource metrics are diagnostic observations, not causal proof or face-to-game latency measurements.

## VoiceAttack

Main's [`voiceattack/`](voiceattack/) contains **`VoiceAttackSender.cs` and `USER_GUIDE.md` only**. Follow the [guide](voiceattack/USER_GUIDE.md) for passed values and synchronous helper calls; the development branch's mock harness/build helpers are not present here.

| C# block / symbols | Responsibility / invariant |
| --- | --- |
| `VAInline.main`, static `Gate` | Whole-file VoiceAttack inline helper; VoiceAttack supplies `VA`. Serializes calls and reads operation/target/player from `~passedText1..3`, duration from `~passedInteger1`, trigger pressure from `~passedDecimal1`. |
| `ReadText`, `Validate` | Normalize blanks and enforce action-specific fields. `begin`, tap/hold/release/trigger/clear/release_all support digital Xbox/D-pad/stick clicks and LT/RT, not analog stick movement or Android payload syntax. |
| TCP/greeting/request/ACK block, `RequireVersion`, `Text`, `Integer`, `ReadJson` | One loopback connection/call; ready protocol-v1 receiver UUID/epoch, bounded strict UTF-8 JSON and one action request/ACK. `begin` only pins the ready greeting. Timeout is 1500 ms. |
| `SCVoice.session`, `SCVoice.sequence`, `~~SCVoice.receiver`, `~~SCVoice.epoch`, `~~SCVoice.failed` | Sender identity plus command-chain receiver/epoch/failure state. Cleanup ACK must advance epoch; the old chain stays pinned so delayed macro continuations cannot restart after cleanup/restart. Do not refresh generation mid-macro. |
| Exception path | Mark chain failed and log; **no automatic retry** after ambiguous delivery. Recover with separately invoked cleanup/new command, not repeated uncertain taps. |

Receiver owns finite press duration; an ACK does not wait for that duration. VoiceAttack owns pauses/loops/sequences and must call helper synchronously through nested commands. `clear` only clears voice contributions; receiver `release_all` does not stop Android Hold/Turbo or arbitrary native VoiceAttack key/mouse actions. Native VoiceAttack desktop actions bypass receiver ownership entirely.

## Build and resources

Android paths in this table are relative to [`source/android/`](source/android/). [Android README](source/android/README.md) lists supported tasks; [PLAY_RELEASE.md](source/android/PLAY_RELEASE.md) covers distribution/signing boundaries.

| File / group | Responsibility |
| --- | --- |
| `settings.gradle.kts` | Plugin/dependency repositories and single `:app` inclusion. |
| `build.gradle.kts`, `gradle/libs.versions.toml` | Root plugin/version catalog, including AGP/Kotlin. Actual app dependencies also include explicitly pinned versions in its build file; catalog entries alone do not prove use. |
| `app/build.gradle.kts` | Compile SDK 36/min SDK 24, serialization/coroutines/Views dependencies, two enabled variants only: sideloadDebug (`com.example.simplecontroller`, target 34) and playRelease (`io.github.colonelwheel.simplecontroller`, target 36). Build JDK guidance is 21; emitted Java/Kotlin bytecode target is 1.8. Release minification is off, private Play signing is separate. |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` | Checked-in Gradle bootstrap, pinned distribution 8.11.1; use project wrapper. |
| `gradle.properties` | Daemon heap/UTF-8, AndroidX, Kotlin style and nontransitive R configuration. Local SDK/signing paths are not part of this shared file. |
| `app/proguard-rules.pro` | Template project ProGuard file referenced by release configuration; not evidence that minification is enabled. |
| `app/src/main/AndroidManifest.xml` | Internet/vibration permissions, active launcher, config-change/resize/orientation compatibility and `allowBackup`. No standalone sensor-test launcher. |
| `app/src/main/res/layout/activity_main.xml` | Canvas and connection-status scaffold; most toolbar/editor/control UI is Kotlin. |
| `res/layout/dialog_connection_settings.xml`, `res/layout/property_sheet_spinner_item.xml` | Connection dialog resources and property spinner row. Here and in the next rows `res` means `app/src/main/res`. |
| `res/values/strings.xml`, `ui_strings.xml`, `property_sheet_strings.xml` | App/general/editor strings; many dynamic editor labels also remain Kotlin literals. |
| `res/values/colors.xml`, `ui_colors.xml`, `themes.xml`; `res/color/app_button_tint.xml`, `app_control_text.xml`, `app_switch_thumb_tint.xml`, `app_switch_track_tint.xml` | Palettes, Material day/night theme and stateful widget colors. |
| `res/drawable/connection_status_background.xml`, `ic_launcher_background.xml`, `ic_launcher_foreground.xml`; `res/mipmap-*/ic_launcher*` | Status background and adaptive/density launcher branding; bitmap variants are assets, not input logic. |
| `res/menu/main_menu.xml` | Theme menu; active Activity adds floating menu actions in code. |
| `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Template files **not referenced by the current manifest**. Do not assume these commented examples enforce profile backup/privacy policy; verified portable exports are a separate implementation. |

| Root/support item | Role |
| --- | --- |
| `README.md`, `USER_GUIDE.md` | Setup/download/artifact provenance versus concise feature/user guide. Keep behavior examples and downloads consistent with the selected branch. |
| `BRANCHES.md` | Main/dev roles, promotion, profiles and retained history; authoritative workflow companion to this map. |
| `AGENTS.md`, `CLAUDE.md`, `ARCHITECTURE.md` | Shared task instructions, Claude's `@AGENTS.md` import and this implementation map. Keep private paths/settings/personal notes out of public instructions. |
| Root `.gitignore`, `source/.gitignore`, Android `app/.gitignore`, facial `.gitignore` | Scope local IDE/build/dependency/private files; root allows only intended public downloads. Ignoring a tracked file neither untracks it nor erases history. |
| `.gitattributes` | Preserve exact source/binary bytes with `* -text`; Markdown/root config have explicit text rules. Do not normalize historic CRLF source as an incidental documentation/refactor change. |
| `SimpleController.apk`, `SimpleControllerReceiver.exe`, `ViGEmBus_1.22.0_x64_x86_arm64.exe` | Published Android/receiver downloads and separate driver installer. Preserve known-good copies; source changes do not update them. Checksums/provenance belong in README when an approved artifact changes. |

## Verification map

This map is maintained through source inspection and documentation checks. Android build/test evidence for this promotion is recorded in [the root README](README.md#5-downloads-and-source); dated earlier release counts remain historical. For actual source changes choose checks covering the changed behavior, preserve existing data, and record what ran. Start with a relevant deterministic JVM suite; broaden when integration or compatibility is affected.

### Android suites

Base **T**: [`source/android/app/src/test/java/com/example/simplecontroller/`](source/android/app/src/test/java/com/example/simplecontroller/). These entries identify Kotlin JVM test files; they do not report test counts or results. Paths below are relative to T.

| Area | Existing suites |
| --- | --- |
| Payload ownership/transport | `ui/ButtonAimPayloadExecutorTest.kt`, `ui/LegacyScopedPayloadTransportTest.kt` |
| Modifier grammar/timers | `ui/PayloadModifiersTest.kt`, `ui/PayloadRepeatTest.kt`, `ui/PayloadDelayTest.kt`, `ui/RepeatDirectionalPayloadTest.kt` |
| Macro/U/format-17 additions | `ui/ButtonMacroControllerTest.kt`, `ui/PayloadUpTest.kt`, `model/ReleasePayloadProfileCompatibilityTest.kt`; existing Delay compatibility future-version assertions now derive from maxima. |
| Ordinary Auto-Tap | `ui/ButtonAutoTapControllerTest.kt` |
| Alternate phase/boundary | `ui/OneShotAlternateStateMachineTest.kt`, `ui/ButtonAimReleaseBoundaryTest.kt`, `model/ButtonAimBoundaryProfileTest.kt` |
| Stick ownership/reset | `ui/ButtonAimStickOwnershipTest.kt`, `ui/DirectionalStickResetTest.kt` |
| D-pad/directional Button | `ui/ButtonAimDpadSessionTest.kt`, `ui/ButtonDirectionalSelectionTest.kt` |
| Directional/WASD/Stick+ | `ui/DirectionalCommandStateControllerTest.kt`, `model/StickDirectionalProfileTest.kt`, `model/ExtendedBoostSettingsTest.kt` |
| Aim origin/math/backend/Camera Follow | `ui/TouchAimOriginConfigTest.kt`, `ui/StickResponseCurveTest.kt`, `ui/CameraFollowAimConfigTest.kt`, `ui/ButtonAimGameMouseConfigTest.kt`, `model/TouchAimGameMouseTest.kt`, `model/ButtonAimGameMouseTest.kt`, `model/CameraFollowControlProfileTest.kt` |
| TouchAim detection/calibration/tuning | `ui/TouchAimLegacyLevelsTest.kt`, `ui/TouchAimStageConfirmationTest.kt`, `ui/TouchAimStagePayloadControllerTest.kt`, `ui/TwoStateTouchAimStateMachineTest.kt`, `ui/TouchAimCalibrationAnalyzerTest.kt`, `ui/ControlTuningInputsTest.kt`, `model/TouchAimCalibrationProfileTest.kt` |
| Floating placement/readiness/selection | `ui/FloatingPlacementTest.kt`, `ui/FloatingSelectionSessionTest.kt`, `ui/PendingFloatingGestureTest.kt` |
| Ring state/geometry/payloads | `ui/FloatingRingStateMachineTest.kt`, `ui/FloatingRingPayloadControllerTest.kt`, `ui/FloatingRingGeometryTest.kt`, `ui/FloatingBoostBandGeometryTest.kt` |
| Profile/transfer/backup versions | `model/ControllerProfileTest.kt`, `model/ControllerProfileTransferTest.kt`, `model/ProfileBackupFormatTest.kt`, `model/PayloadStageProfileCompatibilityTest.kt`, `model/RepeatPayloadProfileCompatibilityTest.kt`, `model/DelayPayloadProfileCompatibilityTest.kt` |
| Floating persistence/banks/remaps | `model/FloatingControlsPersistenceTest.kt`, `model/FloatingOrientationTest.kt`, `model/FloatingRingPresetTest.kt`, `model/FloatingRingOptionsPersistenceTest.kt`, `model/FloatingRoleBoostPersistenceTest.kt`, `model/FloatingDirectBoostDelayPersistenceTest.kt` |
| Pages/orientation/selective deletion | `model/ControllerPageSessionTest.kt`, `model/OrientationLayoutTest.kt`, `model/OrientationPreferenceStoreTest.kt`, `io/ControllerProfileBulkDeleteTest.kt` |
| Reusable profiles/serialization | `model/ControlSettingsProfilesTest.kt`, `model/ControlOneShotSerializationTest.kt` |
| Wire helpers/scopes/safety | `net/PhoneOutputCoordinatorTest.kt`, `net/UdpHeartbeatTest.kt`, `net/MouseDeltaCommandTest.kt`, `OutputSafetyEpochTest.kt`, `CameraFollowCommandsTest.kt` |
| Template only | `ExampleUnitTest.kt` is not controller regression coverage. |

Golden [`debug-profile-transfer-v1.json`](source/android/app/src/test/resources/golden/debug-profile-transfer-v1.json) must remain readable by both variants. Instrumentation under `app/src/androidTest` consists of template `ExampleInstrumentedTest.kt` and `ui/ButtonDirectionalGestureInstrumentedTest.kt` (MotionEvent-to-handler/recording transport, not actual game output). **Follow the [device/emulator boundary in AGENTS.md](AGENTS.md#build-and-verification-boundaries).** JVM tests do not prove physical touch behavior or Android event integration.

### Receiver and optional-app checks

Main ships **no receiver `test_*.py` files**, VoiceAttack mock harnesses or facial test directories. Facial `pyproject.toml` still names original absent test paths. Do not report zero discovered tests as meaningful regression coverage.

The dev branch separately retains receiver suites for Camera Follow/controls, desktop/dual/movement face input, facial server, movement receiver, Game Mouse, scoped phone protocol/receiver, Release All and voice protocol/receiver. Its `voiceattack/verify_receiver.py --source <directory>` discovers tests in that same directory: pointing it directly at main's receiver would currently run **zero**. Meaningful main verification needs compatible tests and verified main modules together in an isolated fixture, or an explicitly implemented separate test-path mechanism. Receiver tests must supply native fakes before importing the composition root.

Facial explicit package check is a **synthetic native/model/Qt check**, not a webcam/receiver/game test. Source/frozen model resolution, append-only settings, worker lifecycle and no-output modes need their own focused checks when changed. Voice helper verification must cover matching sender/server schemas, receiver restarts, epoch cleanup, duplicate/uncertain delivery and overlapping owners; native VoiceAttack actions are a separate path.

For Android build tasks use [source/android/README.md](source/android/README.md); for isolated receiver packaging use [source/receiver/README.md](source/receiver/README.md); for facial package/model checks use [facial-input/README.md](facial-input/README.md). Build/package success never authorizes replacing a working binary, starting native/camera output, installing on a phone or clearing user data.

## Keeping this map useful

1. During planning, confirm checkout/branch and existing changes, read Start here and the relevant subsystem, then inspect the named symbols/callers and their tests. Use `rg -n` for symbols and `rg --files` for exact paths instead of loading unrelated large files.
2. Before implementing, identify the setting/source owner, natural versus hard ending, stale callback/packet gates, affected saved/transport versions, and other input modes sharing the code. Consult the linked contracts; this map does not replace them.
3. After an approved behavior/layout change, update affected map entries and route/test pointers in the same work. If structure/versions drift, correct this map from source; do not bend implementation to make an old map true.
4. Record the scope and limits of verification, inspect intended staged changes, and publish the selected branch under the repository's instructions. Keep local workspace/private records separate from portable architecture documentation.
