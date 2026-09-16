# SimpleController App Architecture Notes

This note explains how the SimpleController project is currently organized, how input moves through the app, and what each meaningful file does.

## 2026-09-15 App-Controlled Portrait/Landscape Layouts

`MainActivity` explicitly requests Portrait or Landscape and never depends on Android auto-rotate.
Two large controls are present only in Edit Mode: one switches the editor orientation and one toggles
the persisted Play Mode lock. Entering Play Mode and app startup apply that saved lock. Both controls
use `GONE` in Play Mode, so they are neither visible nor tappable while gaming. The sideload-only
`TouchDiagnosticActivity` remains independently locked to Portrait.

The main activity declares Android 16's temporary restricted-resizability compatibility property so
API-36 large-screen devices continue honoring its explicit orientation request. Most phones are
already exempt from Android 16's `sw600dp` override. OEM policy can still override requested
orientation, and Android 17 removes this large-screen opt-out, so those device classes remain a
device-test risk rather than an automated guarantee.

Controller-profile format 3 keeps one canonical `Control` list for behavior and separate Portrait
and Landscape geometry overlays on every `ControllerPage`. Each overlay is keyed by stable control
ID and records its source canvas dimensions. Keeping Hold, Turbo, TouchAim, Button Aim, page action,
and other behavior in one canonical control prevents the two orientations from drifting. Format 2
profiles and historical arrays migrate their exact existing geometry to Portrait only. On first use
of missing geometry, pure fitting code proportionally places and uniformly scales each control, then
clamps its complete rectangle within the destination canvas. Later synchronization updates only the
active orientation.

Before an explicit editor orientation change, the activity snapshots the current page, runs the
central output-release path, and cancels TouchAim calibration. `onConfigurationChanged` waits for
the new canvas measurement before rendering the same edited page with its target geometry. Page
duplicate/import remaps the same regenerated control IDs through both overlays. Transfer version 1
and all-profile backup version 1 remain unchanged; both carry nested format-3 profiles, while their
readers continue accepting format 2 and the golden debug export.

Primary files:

- `app/src/main/java/com/example/simplecontroller/model/OrientationLayout.kt`
- `app/src/main/java/com/example/simplecontroller/model/OrientationPreferenceStore.kt`
- `app/src/main/java/com/example/simplecontroller/model/ControllerProfile.kt`
- `app/src/main/java/com/example/simplecontroller/io/LayoutStorage.kt`
- `app/src/main/java/com/example/simplecontroller/MainActivity.kt`

## 2026-09-15 Separate Play Store Release

The Android module has two deliberately isolated distribution variants that share the same source
and profile-transfer contract:

- `sideloadDebug` keeps application ID `com.example.simplecontroller` and target API 34 so future
  development APKs continue updating the existing debug installation.
- `playRelease` uses the permanent application ID `io.github.colonelwheel.simplecontroller` and
  target API 36 for Google Play. It installs beside the debug app rather than overwriting it.

Only those two combinations are enabled. The Touch Sensor Test activity and its second launcher
icon are declared only in the `sideload` source set, so they remain available for development but
are excluded from the Play manifest. Module-wide compile SDK is 36, and Android Gradle Plugin
8.10.1 is used with the existing Gradle 8.11.1 wrapper because that plugin supports API 36.

Use `assembleSideloadDebug` for the existing feature APK and `bundlePlayRelease` for the Play AAB.
The Play bundle remains unsigned until a private upload key is deliberately configured. Never
commit a keystore, signing password, or local signing-properties file.

## 2026-09-14 Portable Profile Import/Export

External transfer supports a versioned `simplecontroller-profile` JSON envelope containing one
complete `ControllerProfile` and a separate `simplecontroller-profiles-backup` envelope containing
all controller and reusable setting profiles. Because the file lives in a user-selected Storage
Access Framework location,
it can be selected again after the debug APK is uninstalled and a separately signed release APK is
installed. The format is independent of application ID, signing certificate, and target SDK.

`LayoutManager` exposes Import and **Export all profiles** in the Load dialog, plus single-profile
Export in each saved profile's long-press management menu. `MainActivity` owns the Activity Result
launchers. Before opening the create-file
picker, the exact profile snapshot is staged in an app-private `AtomicFile`, allowing Android to
recreate the Activity without replacing the intended export. The selected document is written,
closed, reopened, decoded, and compared with that snapshot before `Export verified` is shown.

Import performs provider I/O and decoding off the UI thread, limits files to 25 MB, requires valid
UTF-8/JSON, and validates version/structure before any internal write. A unique editable name is
prefilled; existing profiles are never overwritten automatically. The imported profile is saved
atomically and re-read before the existing safe profile-install path releases outputs and loads its
Home page. Whole-profile import preserves page/control IDs and cross-page targets exactly.

The all-profile bundle includes every controller profile/page and the TouchAim calibration/manual,
Button Aim, and Directional/Stick+ reusable profile stores. Restore adds unique copies, remaps
included TouchAim calibration references, verifies the new internal files, and keeps the currently
loaded profile unchanged. Network/player/transport/theme and other non-profile preferences are not
part of the backup.

`PROFILE_TRANSFER_FORMAT.md` is the release compatibility contract. In particular, the future API
36 release must accept transfer version 1, all-profile backup version 1, nested profile format 2,
bare version-2 profile objects, and historical top-level Control arrays. The committed golden debug
export must remain readable.

Primary files:

- `PROFILE_TRANSFER_FORMAT.md`
- `app/src/main/java/com/example/simplecontroller/io/LayoutStorage.kt`
- `app/src/main/java/com/example/simplecontroller/io/ProfileBackup.kt`
- `app/src/main/java/com/example/simplecontroller/io/LayoutManager.kt`
- `app/src/main/java/com/example/simplecontroller/MainActivity.kt`
- `app/src/test/resources/golden/debug-profile-transfer-v1.json`

## 2026-09-14 Controller Pages

Saved controller profiles now use a versioned object containing an ordered list of complete
`ControllerPage` snapshots and one stable Home page ID. Each page has its own stable UUID, name,
and full `Control` list. The runtime active/previous page is intentionally not serialized: opening
or loading a profile starts on Home. Older top-level JSON control arrays still load and are wrapped
in memory as one deterministic-ID page named `Base`; the structured multipage format is written by
the normal save/autosave path.

The existing mutable `controls` list remains the rendered working copy. Before page selection,
save, or runtime navigation, MainActivity deep-copies it back into only the displayed page.
Selecting another page refills the working list from another detached copy. Duplicate and import
regenerate page/control IDs and copy every serialized setting, so pages never share mutable control
state or remain linked to another saved profile.

Buttons store typed Android-local `PageAction` and `pageTargetId` fields separately from their
ordinary payload. Go To and Toggle resolve the current page name through the stable target ID;
Return and Home need no target. ControlView consumes these actions once on `ACTION_DOWN`, ahead of
Button Aim, Auto-Tap, Hold, Turbo, or ordinary payload handling. Receiver transports also reject
raw `PAGE_*` strings as defense in depth.

A successful Play Mode change resolves its destination first, then runs `ReleaseAllCoordinator`
to invalidate stale sends and cancel latches, sticks, Turbo, Auto-Tap, delayed actions, Button Aim,
TouchAim, pointer ownership, and one-shot state. Only after cleanup does it silently detach the old
views and render the destination page. The UDP connection, player selection, and transport settings
stay unchanged. Explicit Release All clears every output but deliberately keeps the current page.
Edit Mode, profile loading, disconnect, and app pause reset the runtime session to Home and clear
its single previous-page value.

Primary files:

- `app/src/main/java/com/example/simplecontroller/model/ControllerProfile.kt`
- `app/src/main/java/com/example/simplecontroller/model/Control.kt`
- `app/src/main/java/com/example/simplecontroller/io/LayoutStorage.kt`
- `app/src/main/java/com/example/simplecontroller/io/LayoutManager.kt`
- `app/src/main/java/com/example/simplecontroller/MainActivity.kt`
- `app/src/main/java/com/example/simplecontroller/ui/ControlView.kt`
- `app/src/main/java/com/example/simplecontroller/ui/PropertySheetBuilder.kt`

## 2026-09-13 TouchAim two-state calibration wizard

TouchAim retains its original manual `AIM_ONLY/LOW/MEDIUM/HIGH` behavior as the serialized default.
Explicit opt-in manual and calibrated two-state modes add only `AIM/SHOOT`; neither is simulated
with duplicate legacy thresholds. Manual two-state averages the enabled raw contact sensors, while
calibrated mode applies saved per-sensor normalization. Their ON/OFF values are stored separately to
prevent raw and normalized score scales from colliding. Both support an optional Aim payload, a configurable Shoot payload,
Aim-payload carryover, three Shoot activation behaviors, independent ON/OFF thresholds, smoothing,
confirmation timing, and a separate Shoot aim sensitivity for finer control after SHOOT is confirmed.

`TouchAimCalibrationWizard` adds an output-suppressed full-canvas overlay while leaving an outlined
capture target exactly over the current TouchAim rectangle. It records two separate passes for each
intended state with a user-controlled countdown/duration and a mid-pass moving cue. Calibration and
validation never enter `TouchAimHandler`, `AimOutputSession`, Swipe, or a payload executor. The
central Release All path runs before the overlay is installed and after it is removed.
The wizard verifies actual pointer travel after the movement cue, rejects interrupted passes, and
requires an intentional Aim-to-Shoot-to-Aim cycle before live validation can be completed.

`TouchAimCalibrationAnalyzer` is pure Kotlin. It evaluates all non-empty subsets of Size,
TouchMajor, and TouchMinor after robust per-sensor normalization. Candidate thresholds are trained
and validated against separate repeated passes. The scoring weights accidental Shoot activation
conservatively and prefers a single sensor unless a normalized combination materially improves the
held-out result. Held-out evaluation simulates the actual smoothing, hysteresis, confirmation timers,
and stationary-contact deadlines. An unreliable candidate with a meaningful sensor direction can
be applied only through an explicit warning after live validation and remains editable; a result
with no viable direction exposes no applicable threshold.

`TwoStateTouchAimStateMachine` is also pure Kotlin. A confirmed upward transition either holds
Shoot, presses once on entry, or arms a press for a confirmed return to Aim. A complete finger lift
is always a hard reset, not a return transition. All terminal cleanup clears the arm without firing.
`TouchAimHandler` uses the existing `ButtonAimPayloadExecutor` lease ownership for both two-state
modes and retains its previous parser/transition path for manual three-stage controls.
Two-state payloads are restricted to lease-backed reversible outputs; raw edge actions, toggles, and
Release All are rejected before a calibrated gesture can activate.

Reusable profiles are versioned, named, UUID-addressed app-private JSON files. Applying a profile
copies a frozen effective snapshot into `Control`, so later profile rename/delete does not silently
alter the layout. Profiles include device/display/control geometry, selected sensor scales/weights,
thresholds, timing, behavior/payload settings, reliability metrics, validation summary, and compact
per-pass distributions plus bounded synchronized samples for later re-evaluation.

Primary files:

- `app/src/main/java/com/example/simplecontroller/model/TouchAimCalibration.kt`
- `app/src/main/java/com/example/simplecontroller/io/TouchAimCalibrationStore.kt`
- `app/src/main/java/com/example/simplecontroller/ui/TouchAimCalibrationAnalyzer.kt`
- `app/src/main/java/com/example/simplecontroller/ui/TwoStateTouchAimStateMachine.kt`
- `app/src/main/java/com/example/simplecontroller/ui/TouchAimCalibrationWizard.kt`
- `app/src/main/java/com/example/simplecontroller/ui/TouchAimHandler.kt`

Manual TouchAim profiles and Button Aim profiles are stored separately from wizard
calibrations as versioned, UUID-addressed, per-profile AtomicFile JSON. Manual profiles copy a
mode-matched manual snapshot without geometry or calibrated detector data. Button Aim profiles save
both Base/Alternate aiming feel and the associated payload/action settings. Loading offers an
aiming-only path that preserves the target button's actions and a complete path that replaces them.
Both paths preserve identity, geometry, and the button's separate swipe setting.

Stick directional profiles use the same per-profile store for the shared Directional/WASD and
Stick+ command matrix. Each snapshot stores 12 directional/boost commands, Regular and Super Boost
thresholds, and the source mode. Commands-only application preserves the target mode; apply-and-
switch selects the saved WASD or Stick+ mode. Both paths preserve geometry, STICK_L/STICK_R payload,
sensitivity, auto-center, analog threshold settings, and normal versus Response Curve stick type.

Additional files:

- `app/src/main/java/com/example/simplecontroller/model/TouchAimManualProfile.kt`
- `app/src/main/java/com/example/simplecontroller/model/ButtonAimProfile.kt`
- `app/src/main/java/com/example/simplecontroller/model/StickDirectionalProfile.kt`
- `app/src/main/java/com/example/simplecontroller/model/ButtonAimPayloadPolicy.kt`
- `app/src/main/java/com/example/simplecontroller/io/ControlSettingsProfileStores.kt`

## 2026-09-12 Ordinary Button Toggle Auto-Tap

Ordinary Button controls have an optional per-control `Toggle auto-tap` mode. The first tap starts
an immediate finite press/release loop; the next tap stops it. `Auto-tap interval` is measured from
the start of one press to the start of the next, defaults to 100 ms, and is clamped to a minimum of
16 ms. Each press lasts at most 50 ms and always ends before the next press begins.

Auto-tap is separate from global Turbo and takes priority over global Hold/Turbo for that control.
The property sheet keeps Auto-tap mutually exclusive with per-button Hold Toggle and Button Aim
Surface. The running state uses a distinct green fill. `RELEASE_ALL`, app pause, edit mode, profile
or property changes, control removal, connection loss, and deliberate disconnect all cancel its
timers and release its current finite press. Canceled callbacks use a generation guard and cannot
restart the loop later.

The loop uses the existing owned-payload executor so Xbox buttons, keyboard keys, mouse buttons,
triggers, stick-direction macros, and multi-command payloads receive explicit matching releases.
State-toggle payloads (`RELEASE_ALL`, Camera Follow, and Scroll Mode Toggle) are deliberately not
eligible for Auto-tap. No Windows receiver or Pico firmware change is required by the Android
implementation; Pico/ConsoleBridge behavior remains deferred for hardware verification.

Primary files:

- `app/src/main/java/com/example/simplecontroller/model/Control.kt`
- `app/src/main/java/com/example/simplecontroller/ui/ButtonAutoTapController.kt`
- `app/src/main/java/com/example/simplecontroller/ui/ControlView.kt`
- `app/src/main/java/com/example/simplecontroller/ui/PropertySheetBuilder.kt`
- `app/src/test/java/com/example/simplecontroller/ui/ButtonAutoTapControllerTest.kt`

## 2026-09-08 Camera Follow, TouchAim, Packet Ordering, and Packaging Handoff

This is the newest operational handoff and supersedes older port/build guidance below where the two conflict. The Camera Follow feature was tested end-to-end by the user and reported to work well.

### Current connection details

- The current `simple_controller_receiver.py` listens on UDP port `42734` and prints the PC's likely LAN IP and port at startup.
- The Android source still has a legacy default/saved port of `9001`. In the app's connection settings, use the IP and port printed by the running receiver; for this receiver that is currently `42734`.
- `Use ConsoleBridge (CBv0)` must be unchecked when using the Python/standalone Windows receiver.
- ConsoleBridge remains a separate path on UDP port `9010`; its binary behavior was intentionally preserved.
- Phone and PC must be on the same local network. Allow the receiver through Windows Firewall on private networks if prompted.

### TouchAim output modes

TouchAim now has three selectable base-aim outputs:

- `Mouse`
- `Right stick`
- `Left stick`

The new Left stick option deliberately uses the same conventions and send path as the project's normal left stick. It is not an approximation invented separately for TouchAim:

- Stick origin is per control. The backward-compatible default converts control center-to-edge into
  normalized `-1.0` through `1.0` X/Y values. The optional initial-touch origin sends neutral on
  contact, then uses radial displacement and deadzone from that first coordinate.
- The control's sensitivity and invert-Y settings are applied.
- Left and Right stick TouchAim call the shared `UdpClient.sendStickPosition()` path.
- Stick output is resent every 16 ms while active and explicitly centered when the touch ends.
- Mouse TouchAim remains relative movement through `sendTouchpadDelta()` with its existing smoothing/scaling and an 8 ms send interval.
- TouchAim has an opt-in `Response-curve sensitivity (stick output only)` checkbox. It defaults off, affects only Left/Right stick output, and is ignored by Mouse output.
- TouchAim manual profiles and full calibration profiles preserve the origin, displacement, and
  deadzone. Detection-only calibration application preserves the target control's current aiming
  settings.

Relevant files:

- `app/src/main/java/com/example/simplecontroller/model/Control.kt`
- `app/src/main/java/com/example/simplecontroller/ui/TouchAimHandler.kt`
- `app/src/main/java/com/example/simplecontroller/ui/PropertySheetBuilder.kt`
- `app/src/main/java/com/example/simplecontroller/ui/ControlView.kt`

### Response Curve Stick

`Response Curve Stick` is a separate Add-menu control type. Existing controls remain `STICK` and keep the original linear sensitivity behavior; saved layouts are not migrated automatically. The new serialized type is `CURVED_STICK`, uses the same `STICK` payload by default, and reuses normal stick drawing, packet ordering, auto-center, recenter, swipe, Directional mode, and Stick+ infrastructure.

For one axis, the opt-in curve is:

```text
output = sign(input) * abs(input)^(1 / sensitivity)
```

Behavior:

- `1.0` is linear.
- `0.5` gives a square curve for finer center control: 50% travel produces 25% output.
- `2.0` gives a square-root curve for stronger center response: 50% travel produces about 70.7% output.
- Any positive sensitivity still reaches 100% output at full physical tilt.
- `0.0` intentionally disables output.
- Inputs and outputs are clamped to the normalized `-1.0` through `1.0` range.

Boost and super-boost thresholds on Response Curve Stick use physical stick travel, not curved analog output. This keeps changing curve sensitivity from silently moving the physical activation points. When Directional mode internally emits an analog coordinate, it sends the curved coordinate while command thresholds continue to use the separate physical vector. Existing Stick behavior is unchanged.

TouchAim uses the same curve implementation when its new checkbox is enabled and its output is Left or Right stick. TouchAim contact-score thresholds are independent and unchanged. A curved Left-stick output naturally feeds the receiver's existing Camera Follow input, while a curved Right-stick output remains real/manual RS input and therefore retains priority over Camera Follow.

Primary files:

- `app/src/main/java/com/example/simplecontroller/ui/StickResponseCurve.kt`
- `app/src/test/java/com/example/simplecontroller/ui/StickResponseCurveTest.kt`
- `app/src/main/java/com/example/simplecontroller/model/Control.kt`
- `app/src/main/java/com/example/simplecontroller/ui/ControlView.kt`
- `app/src/main/java/com/example/simplecontroller/ui/TouchAimHandler.kt`
- `app/src/main/java/com/example/simplecontroller/ui/DirectionalStickHandler.kt`
- `app/src/main/java/com/example/simplecontroller/ui/PropertySheetBuilder.kt`

Local JVM tests cover linear `1.0`, gentle `0.5`, responsive `2.0`, full-tilt preservation in both directions, and zero sensitivity. The project was also given its missing standard JUnit 4 test dependency in `app/build.gradle.kts`; this fixed both the existing template test and the new curve tests.

### Analog-stick packet ordering

Packet ordering was restored for regular stick coordinates and is shared by TouchAim Left/Right stick modes.

Android creates one process-unique stick session ID and a monotonically increasing sequence number. Legacy text packets use:

```text
STICK_L:<session_id>:<sequence>:<x>,<y>
STICK_R:<session_id>:<sequence>:<x>,<y>
STICK_MACRO_L:<session_id>:<sequence>:<x>,<y>
STICK_MACRO_R:<session_id>:<sequence>:<x>,<y>
```

The receiver keeps the newest packet per `(player, physical stick)`. Within the same Android session it discards duplicate or older sequence numbers, preventing a delayed UDP coordinate from overwriting a newer one. Manual and macro sources share the sender sequence and receiver freshness gate for their physical stick.

This covers:

- normal Left stick controls
- normal Right stick controls
- TouchAim Left stick
- TouchAim Right stick
- LS/RS directional macro coordinates on the Python receiver path

Mouse TouchAim was intentionally left as relative `DELTA:x,y` packets. Applying the stick-coordinate stale-packet rule to relative mouse deltas would discard movement distance; mouse deltas are not absolute coordinates that can simply be replaced by the newest value. ConsoleBridge continues using its existing CBv0 framing/sequence behavior rather than the Python receiver's text metadata.

Primary ordering implementation:

- Android: `UdpClient.sendOrderedStickPosition()`
- Receiver: the sequenced-stick branch in `process_command()`

### Camera Follow architecture

Camera Follow is implemented on the PC receiver, where the final virtual Xbox 360 controller state is generated. Android only owns the user-facing toggle state and sends an explicit state command:

```text
CAMERA_FOLLOW:1
CAMERA_FOLLOW:0
```

Explicit state makes duplicate UDP commands safe: receiving `CAMERA_FOLLOW:1` twice still leaves the feature enabled rather than toggling it back off. The Android state is resent after `UdpClient` reconnects/initializes.

The `CAMERA_FOLLOW` payload is a convenient one-press toggle. It is guarded so Hold and Turbo callbacks cannot repeatedly toggle it while a finger remains down. The app shows a short `Camera Follow ON/OFF` toast. The receiver logs only actual state changes (`Camera Follow enabled` or `Camera Follow disabled`), not every frame.

Receiver responsibilities are split as follows:

- `camera_follow.py`: pure, lock-protected state machine and normalized-value calculations.
- `simple_controller_receiver.py`: UDP parsing, real/macro stick updates, worker timing, virtual `vgamepad` output, logging, disconnect/input-loss cleanup, and settings.
- `test_camera_follow.py`: focused isolated tests that do not require a virtual controller.

Final Right-stick priority is:

1. Explicit RS macro/button input
2. Real manual Right-stick input
3. Camera Follow generated horizontal input
4. Neutral Right stick

Camera Follow reads the already-normalized Left and Right stick states. After sustained Left-stick movement it generates proportional horizontal-only Right-stick output. It never generates vertical camera movement. It ramps in/out, clamps its maximum, yields immediately to manual Right-stick input, waits after manual release, and never overwrites an active explicit RS macro.

Disabling the feature immediately clears generated output. Generated/manual/macro state is also cleared on explicit `DISCONNECT`, inactive-connection cleanup, input loss, and receiver shutdown so the virtual Right stick cannot remain stuck.

The regular receiver stick conversion remains unchanged: values are normalized, the existing near-center `0.05` deadzone is applied once, and Y is inverted only when written to `vgamepad`.

### Camera Follow settings

The effective settings are together near the top of `simple_controller_receiver.py` in `CAMERA_FOLLOW_SETTINGS`:

```python
CAMERA_FOLLOW_SETTINGS = CameraFollowSettings(
    enabled_by_default=False,
    movement_deadzone=0.20,
    manual_rs_deadzone=0.15,
    activation_delay_ms=200,
    manual_override_timeout_ms=750,
    strength=0.55,
    maximum_output=0.45,
    ramp_in_ms=180,
    ramp_out_ms=120,
    invert_direction=False,
    allow_sideways_steering=True,
)
CAMERA_FOLLOW_UPDATE_INTERVAL_SECONDS = 0.01
CAMERA_FOLLOW_INPUT_LOSS_TIMEOUT_SECONDS = 5.0
```

Important: change the effective values in `simple_controller_receiver.py`, not only the dataclass defaults in `camera_follow.py`, because the receiver explicitly supplies every value above.

Adjustment guide:

- Increase `strength` for more camera response from the same horizontal Left-stick input.
- `maximum_output=0.45` caps generated movement at 45% of full Right-stick range.
- Lower `ramp_in_ms` for faster engagement; raise it for a gentler entry.
- Lower `ramp_out_ms` for a faster return to center.
- `activation_delay_ms` controls how long movement must continue before assistance begins.
- `manual_override_timeout_ms` controls how long assistance stays suppressed after manual RS movement ends.
- `manual_rs_deadzone` determines when real RS motion takes priority.
- Set `invert_direction=True` only for a game whose camera convention is reversed.
- With `allow_sideways_steering=False`, purely sideways LS motion is not enough to activate assistance.

Normalized stick values use `-1.0` through `1.0`.

### Creating the Android Camera Follow button

1. Add or edit a normal Button control.
2. Set its payload to `CAMERA_FOLLOW`.
3. If the name is blank, saving that payload fills the visible name as `Camera Follow`.
4. Each distinct press alternates between enabled and disabled and sends one explicit state command.

The payload editor's autocomplete currently includes Camera Follow and the normal X360 commands:

```text
X360A X360B X360X X360Y
X360LB X360RB X360START X360BACK
X360UP X360DOWN X360LEFT X360RIGHT
X360LS X360RS
CAMERA_FOLLOW
```

`_HOLD` and `_RELEASE` variants remain fully supported by the app and receiver but were intentionally removed from the autocomplete list to reduce clutter.

### Regression-sensitive behavior preserved

The Camera Follow work did not change the existing Xbox button auto-release timers or unrelated hold/release timing. Existing behavior retained includes:

- normal button presses and explicit releases
- Hold/latch conversion to `_HOLD` and cleanup to `_RELEASE`
- Turbo behavior
- trigger reset behavior
- keyboard key-down/key-up synchronization
- LS/RS direction commands and recentering
- mouse button cleanup
- ConsoleBridge encoding and port selection
- Left-stick virtual-controller output

Explicit directional stick buttons use `STICK_MACRO_L/R` on the Python text path so the receiver can give them the required macro priority. On ConsoleBridge, they deliberately retain the prior ordinary binary LS/RS behavior because Camera Follow arbitration exists only in the Python receiver.

### Verification completed on 2026-09-08

- `python3 -m unittest -q test_camera_follow.py`: 12 tests passed.
- `python3 -m py_compile camera_follow.py simple_controller_receiver.py`: passed.
- Android `assembleDebug`: successful after redirecting around a Windows/Android Studio lock in `app/build`.
- User performed an end-to-end Camera Follow test and reported that it worked well.
- Standalone EXE startup was captured and verified through `UDP server started on 0.0.0.0:42734`.
- The fixed EXE was smoke-tested, and only the temporary test process was terminated afterward.

The focused Camera Follow tests cover disabled behavior, centered LS, activation delay, proportional left/right assistance, ramp-out, immediate manual override, override timeout, macro priority, disabling while active, input loss, duplicate explicit-state commands, and isolation from legacy command handling.

## USB Tether Mode (added 2026-09-08)

USB tethering is an alternate transport for the same UDP controller packets. It does not change stick conversion, packet ordering, Camera Follow, TouchAim, Hold, RELEASE, Turbo, macros, rate limiting, or virtual-controller output.

### User workflow

1. Connect the Android phone to the PC with a USB cable.
2. Start `SimpleControllerReceiver-USB-Tether.exe` on the PC.
3. In the Android app, tap **Connect**, then **USB Tether Mode**.
4. Android opens its tether/network settings. Enable **USB tethering**.
5. Return to SimpleController. The app searches for the receiver for up to 12 seconds and connects to the discovered USB address automatically.

Android does not allow an ordinary app to silently enable USB tethering, so the app can open the correct settings page but the user must operate the system toggle. On manufacturer builds without the direct tether-settings activity, it falls back to the broader wireless/network settings page.

The receiver must be running before returning to SimpleController. If discovery fails, verify that USB tethering is on and allow the receiver through Windows Firewall for private networks/UDP.

### Saved manual connection is preserved

USB discovery is runtime-only. It does **not** overwrite these saved connection-dialog values:

- server IP address
- server port
- player selection
- Auto Reconnect setting
- ConsoleBridge (CBv0) preference

Direct USB mode temporarily sends legacy controller UDP directly to the Python receiver and therefore temporarily disables CBv0 in memory only. The saved CBv0 choice is restored when normal saved settings are loaded or the user connects through the normal dialog. After turning tethering off, open **Connect** again (or restart the app) and the prior manual IP and port will still be present.

### Implementation details

- `dialog_connection_settings.xml` adds the **USB Tether Mode** button.
- `MainActivity.kt` opens tether settings, detects the return to the app, suppresses a racing manual auto-connect, and applies the discovered endpoint only in memory.
- `NetworkClient.kt` broadcasts `SIMPLE_CONTROLLER_DISCOVER` on USB-like Android interfaces and common tether subnets. It checks the saved port plus ports `42734` and `9001`.
- `simple_controller_receiver.py` replies with `SIMPLE_CONTROLLER_HERE:<receiver port>` before creating any controller-session record.

The discovery command is explicit and stateless. Duplicate probes do not press controls, alter Camera Follow, or create active controller sessions.

### USB build verification and artifacts

- `python3 -m py_compile simple_controller_receiver.py camera_follow.py`: passed.
- `python3 -m unittest -q test_camera_follow.py`: 12 tests passed.
- Android `testDebugUnitTest`: passed.
- Android `assembleDebug`: passed.
- PyInstaller standalone build: passed with `--collect-binaries vgamepad`.
- The packaged EXE archive contains both x64 and x86 `ViGEmClient.dll` files.

Desktop artifacts:

```text
C:\Users\Tyler\OneDrive\Desktop\Simplecontroller-USB-Tether-debug.apk
SHA-256: 0d8fb6bb2cdbca088577746d1f4f7689737ddc8ec5f5bdc2c12f11c42bb6a03c

C:\Users\Tyler\OneDrive\Desktop\SimpleControllerReceiver-USB-Tether.exe
SHA-256: c9bd97d7ff5b16538117cf9db9b4ea01a36d3efd1dc3442ca768f98d93c8e8b6
```

### Dedicated receiver editing folder

For convenient Camera Follow tuning, a separate build kit is stored at:

```text
C:\Users\Tyler\OneDrive\Desktop\Edit exe Folder
```

It contains `simple_controller_receiver.py`, `camera_follow.py`, the focused test file, the current standalone EXE, and local x64/x86 copies of `ViGEmClient.dll`. `Rebuild SimpleController Receiver.bat` runs the tests, builds into local `build`/`dist` subfolders, explicitly bundles the local DLL copies, and places the finished EXE at the top of the editing folder.

Run it from Command Prompt with:

```bat
cd /d "C:\Users\Tyler\OneDrive\Desktop\Edit exe Folder"
"Rebuild SimpleController Receiver.bat"
```

This is intentionally a separate editable copy. Changes made there do not update the source under `New codex\Simplecontroller`. Python, PyInstaller, and the imported Python packages still need to be installed; copying the DLLs removes only the DLL-source dependency on AppData.

### Windows receiver: source and standalone use

Run from source in Command Prompt so errors remain visible:

```bat
cd /d "C:\Users\Tyler\OneDrive\Desktop\New codex\Simplecontroller"
py simple_controller_receiver.py
```

The standalone receiver includes Python and `camera_follow.py`, so Python is not required to run the finished EXE. The ViGEmBus driver is still required for virtual Xbox controllers. The packaged client DLL does not replace the Windows ViGEmBus driver.

Receiver logs are written under:

```text
%LOCALAPPDATA%\SimpleControllerReceiver\touchpad_logs
```

If a standalone EXE seems to close immediately, launch it from Command Prompt to preserve the traceback. The first packaged build failed because PyInstaller did not automatically include:

```text
vgamepad\win\vigem\client\x64\ViGEmClient.dll
```

The corrected build must use `--collect-binaries vgamepad`.

### Rebuilding the standalone receiver

Close the running receiver before replacing its EXE. After changing Camera Follow settings or logic:

```bat
cd /d "C:\Users\Tyler\OneDrive\Desktop\New codex\Simplecontroller"

py -m unittest -q test_camera_follow.py

py -m PyInstaller --noconfirm --clean --onefile --console --name SimpleControllerReceiver --collect-binaries vgamepad simple_controller_receiver.py

copy /Y "dist\SimpleControllerReceiver.exe" "C:\Users\Tyler\OneDrive\Desktop\SimpleControllerReceiver-Camera-Follow.exe"
```

PyInstaller automatically bundles the imported local `camera_follow.py`; it does not need a second command-line argument. Use `py -m PyInstaller` rather than relying on `pyinstaller.exe` being on `PATH`.

The current delivered files from this session are:

```text
C:\Users\Tyler\OneDrive\Desktop\Simplecontroller-Camera-Follow-debug.apk
C:\Users\Tyler\OneDrive\Desktop\SimpleControllerReceiver-Camera-Follow.exe
```

Hashes at the end of the session (these naturally change after any rebuild):

```text
APK SHA-256: bfa2cad60ce51e738dbea8fc698d6613511405eebc81afbd4e6114cbe9ba0c20
EXE SHA-256: e3f8627d43b8cef7ee0fe26c09a405890404621d78d1b62f368e674cccb6f0fd
```

### Main files changed for this work

- `camera_follow.py`
- `test_camera_follow.py`
- `simple_controller_receiver.py`
- `app/src/main/java/com/example/simplecontroller/UdpClient.kt`
- `app/src/main/java/com/example/simplecontroller/NetworkClient.kt`
- `app/src/main/java/com/example/simplecontroller/MainActivity.kt`
- `app/src/main/java/com/example/simplecontroller/model/Control.kt`
- `app/src/main/java/com/example/simplecontroller/ui/TouchAimHandler.kt`
- `app/src/main/java/com/example/simplecontroller/ui/ControlView.kt`
- `app/src/main/java/com/example/simplecontroller/ui/ControlViewHelper.kt`
- `app/src/main/java/com/example/simplecontroller/ui/PropertySheetBuilder.kt`
- `app/src/main/java/com/example/simplecontroller/ui/StickResponseCurve.kt`
- `app/src/test/java/com/example/simplecontroller/ui/StickResponseCurveTest.kt`
- `app/build.gradle.kts`

## 2026-08-23 Network Troubleshooting Handoff (Historical)

This section records an older troubleshooting state. Its `9001` receiver-port advice does not describe the current 2026-09-08 `simple_controller_receiver.py`, which listens on `42734`.

Current symptom:

- Android can connect/register with `simple_controller_receiver.py` on both regular Wi-Fi and Android USB tethering.
- Receiver logs examples:
  - `Client ('192.168.0.222', 34486) connected as player1`
  - `Client ('10.68.25.19', 38282) registered as player1`
- After connect/register, the receiver still does not log keyboard, button, stick, or touchpad packets.

Changes made in this debugging pass:

- Restored the Python receiver/app default receiver port back to `9001`.
- Kept Android fallback probing for the temporary broken port `42734` in `NetworkClient`, but the normal receiver port is `9001`.
- Updated `simple_controller_receiver.py` to listen on `PORT = 9001`.
- Updated docs that still told the user to manually enter `42734`.
- `NetworkClient.kt` now does direct connect attempts plus broadcast discovery for Wi-Fi and common Android USB tethering routes.
- `MainActivity.kt` no longer initializes `UdpClient` from the saved/manual host before `NetworkClient` finishes resolving the real receiver endpoint.
- `UdpClient.kt` now invalidates stale async initialization, clears `serverAddress` on close, and reinitializes if the socket is closed.
- Latest experiment: when `Use ConsoleBridge (CBv0)` is unchecked, `UdpClient` routes legacy keyboard/button/stick/touchpad sends through `NetworkClient.send()` instead of its own separate UDP socket. This keeps the Python receiver path on the same socket that already proves it can connect/register.

Files that were changed and should be kept in sync with Android Studio:

- `app/src/main/java/com/example/simplecontroller/NetworkClient.kt`
- `app/src/main/java/com/example/simplecontroller/MainActivity.kt`
- `app/src/main/java/com/example/simplecontroller/UdpClient.kt`
- PC side only: `simple_controller_receiver.py`

Important mode note:

- For the Python receiver, `Use ConsoleBridge (CBv0)` should be unchecked.
- CBv0 mode is for ConsoleBridge/Pico and sends supported binary frames to UDP port `9010`, not to the Python receiver text protocol.

Next debugging steps:

1. Verify Android Studio has the latest `NetworkClient.kt`, `MainActivity.kt`, and `UdpClient.kt`, then reinstall the APK.
2. In Android logcat, filter these tags while pressing a simple keyboard button such as `W`:
   - `ControlViewHelper`
   - `NetworkClient`
   - `UdpClient`
3. Expected app-side logs:
   - `ControlViewHelper`: `firePayload()` and `sendCommand() called with: 'W'`
   - `NetworkClient`: `Sending: KEY_DOWN:W` or `Sending: W`
4. If `ControlViewHelper` does not log, the button UI/payload path is not firing. Inspect `ControlView` touch handling, edit mode state, payload value, and whether the current layout actually contains a payload.
5. If `ControlViewHelper` logs but `NetworkClient` does not log `Sending:`, inspect `UdpClient` and release/hold paths.
6. If `NetworkClient` logs `Sending:` but the Python receiver still logs nothing, add raw packet logging in `udp_server()` immediately after `decoded_data = data.decode('utf-8').strip()`:

   ```python
   logger.info(f"RAW UDP from {addr}: {decoded_data!r}")
   ```

   Then rerun the receiver and press one button.
7. Interpret raw receiver logs:
   - Only `CONNECT`, `REGISTER`, and `PING`: Android control send path is not executing or is not using the connected endpoint.
   - Raw control packets present but no action: bug is inside `process_command()` parsing/routing.
   - No raw packets after register: inspect Android logcat send path and the active `NetworkClient` socket/endpoint.

Do not spend more time changing ports until raw packet/logcat evidence shows a port problem. Current evidence says the receiver is reachable on `9001`; the remaining issue is after connection/register in the control-send path.

## High-Level Purpose

SimpleController is an Android touch-controller app for controlling a Windows PC or a ConsoleBridge/Pico receiver over the local network.

The normal flow is:

1. The Android app displays a custom control layout.
2. The user touches an on-screen button, stick, touchpad, or recenter control.
3. The Android app converts that touch into a text command or CBv0 binary frame.
4. The command goes over UDP to either:
   - `simple_controller_receiver.py` on port `9001`, or
   - the ConsoleBridge/Pico firmware on port `9010` when CBv0 mode is enabled.
5. The receiver turns the command into keyboard, mouse, or gamepad output.

The Android app is the control surface. It does not directly inject PC input by itself.

## Main Runtime Concepts

Controls are represented by the serializable `Control` model. Each `Control` has:

- an `id`
- a `ControlType`
- position and size: `x`, `y`, `w`, `h`
- a `payload`
- editable behavior settings such as sensitivity, auto-center, hold toggle, touchpad click lock, directional mode, and Stick+ mode

The active controls list lives in `MainActivity`. `LayoutManager` creates, saves, loads, and renders controls. Each control on screen is a `ControlView`.

## Control Types

`BUTTON` controls fire payload commands. A payload can contain multiple commands separated by commas or spaces.

Examples:

```text
X360A
X360RB, X360A
LS:R100, X360RB, X360A
MOUSE_LEFT_DOWN
LT:1.0
RT:1.0P0.3
```

`STICK` controls send analog stick coordinates. A stick payload normally names the side, such as `STICK_L`, `STICK_R`, `LS`, or `RS`.

`TOUCHPAD` controls send relative mouse movement deltas and can also perform mouse click/drag/lock behavior.

`RECENTER` controls stop active stick sending and force sticks back to center.

## Button Payload Behavior

Button payloads are split on commas and spaces. Each command is classified by `ControlViewHelper`.

Important command families:

- `X360...`: Xbox-style gamepad buttons.
- `X360..._HOLD`: held gamepad button, no server auto-release.
- `X360..._RELEASE`: explicit gamepad button release.
- `LT:<value>` / `RT:<value>`: analog trigger values from `0.0` to `1.0`.
- `LT:1.0P0.3` / `RT:1.0P0.3`: pulse trigger syntax.
- `KEY_DOWN:<key>` / `KEY_UP:<key>`: reliable keyboard hold/release protocol.
- bare keyboard commands such as `W`, `SPACE`, or `SHIFT`.
- `MOUSE_LEFT_DOWN`, `MOUSE_LEFT_UP`, `MOUSE_RIGHT_DOWN`, etc.
- `SCROLL_MODE_TOGGLE`: local app toggle that switches touchpad movement into scroll.
- `LS:<direction><percent>` / `RS:<direction><percent>`: button-assigned stick direction.

The new stick direction button syntax supports:

```text
LS:R50
LS:RIGHT50
LS:L75
LS:U25
LS:D100
LS:UR100
LS:UL100
LS:BL100
LS:BR100
RS:R50
```

The number is optional and defaults to `100`. It works for every supported direction. On button release or unlatch, the helper sends `0,0` for that stick so it returns to center.

## Hold, Latch, and Release Behavior

Hold behavior depends on both press and release handling.

On press:

- normal buttons fire immediately unless the per-button hold toggle waits for a long press.
- global hold can latch non-toggle buttons immediately.
- Xbox commands are converted to `_HOLD` while the finger is physically down so the receiver does not auto-release them.
- turbo mode starts a repeat loop that repeatedly fires the payload.

On release or unlatch:

- `releaseLatched()` walks through the same payload.
- Xbox commands become `_RELEASE`.
- triggers are reset to `LT:0.0` or `RT:0.0`.
- mouse down commands are matched with mouse up commands.
- stick direction button commands are centered with `UdpClient.sendStickPosition(..., 0f, 0f)`.
- keyboard commands are sent as key up.

Because release cleanup is centralized, new held command types should usually be added to both the press path and `releaseLatched()`.

## Network Modes

The app has two overlapping network helpers.

`NetworkClient` owns connection state:

- connection status flow
- player role
- server host/port
- auto reconnect
- heartbeat
- player-prefixed command sending

`UdpClient` owns lower-latency packet sends:

- generic commands
- key down/up plus key sync
- touchpad deltas
- scroll packets
- stick position packets
- CBv0 binary encoding when enabled

When CBv0 is off, messages are plain text to the active Python receiver port, normally `9001`, and are usually prefixed as `player1:` or `player2:`.

When CBv0 is on, `UdpClient` tries to encode supported commands with `CbProtocol` and sends those binary frames to port `9010`. Unsupported CBv0 commands fall back to legacy text.

## Android App Files

### `app/src/main/java/com/example/simplecontroller/MainActivity.kt`

The real launcher activity. It owns the main app lifecycle and top-level UI.

Key responsibilities:

- calls `ThemeManager.init()` before UI setup
- loads `R.layout.activity_main`
- reads and writes the current layout name through `SharedPreferences`
- loads saved controls with `loadControls()`
- creates the shared mutable `controls` list
- creates `UIComponentBuilder` and `LayoutManager`
- seeds a default layout if no saved layout exists
- spawns all `ControlView` instances
- observes `NetworkClient.connectionStatus` and `NetworkClient.lastErrorMessage`
- handles the connection settings dialog
- stores server IP, port, auto reconnect, player role, and CBv0 preference
- initializes `NetworkClient`; `NetworkClient` resolves the receiver endpoint and then updates `UdpClient`
- adds the edit button, connect button, global switches, save/load buttons, add button, and turbo speed editor
- handles swipe mode by overriding `dispatchTouchEvent`
- creates built-in Xbox-style layouts
- persists layouts on pause
- disconnects on stop unless auto reconnect is enabled
- keeps split-screen/window-inset behavior from automatically resizing existing controls

The built-in layout helpers are large but mostly repetitive. They clear the current layout, create controls with preset positions/payloads, save them under names such as `xbox_standard`, `player1_xbox`, and `player2_xbox`, then show a toast.

### `app/src/main/java/com/example/simplecontroller/RefactoredMainActivity.kt`

An older or alternate refactored activity. The manifest points to `MainActivity`, not this class.

It demonstrates the extracted helper architecture but does not include the newer connection dialog, player role UI, CBv0 option, built-in Xbox layouts, turbo speed editor, or split-screen handling from `MainActivity`.

Treat this as reference/legacy code unless the manifest is intentionally switched.

### `app/src/main/java/com/example/simplecontroller/CbProtocol.kt`

Builds ConsoleBridge CBv0 binary packets.

It can encode:

- keyboard events
- mouse movement deltas
- mouse button events
- gamepad button events
- gamepad stick positions
- gamepad trigger values

Packet structure:

```text
0xCB, version, sequence, type, payload, crc8
```

The parser side accepts legacy-style command strings such as `DELTA:x,y`, `LS:x,y`, `RS:x,y`, `LT:v`, `RT:v`, `KEY_DOWN:W`, and `X360A_HOLD`, then converts them to typed binary payloads.

### `app/src/main/java/com/example/simplecontroller/NetworkClient.kt`

Package: `com.example.simplecontroller.net`.

This file physically lives one directory above a normal `net` package folder. Kotlin can still compile it, but Android Studio indexing can complain because the source path does not match the package.

Responsibilities:

- tracks `ConnectionStatus`: disconnected, connecting, connected, error
- tracks `PlayerRole`: player 1 or player 2
- stores current host, port, and auto reconnect flag
- opens a UDP socket to the configured receiver
- verifies the receiver with a `CONNECT:<player>` response instead of assuming UDP send success
- broadcasts `SIMPLE_CONTROLLER_DISCOVER` when the saved host does not answer
- can find `simple_controller_receiver.py` on Wi-Fi or the common Android USB tether subnet without changing the saved IP field
- sends `CONNECT:<player>` and `REGISTER:<player>`
- listens for server responses such as `PONG`
- sends periodic `PING` heartbeat packets
- treats missed heartbeat replies as a dropped connection so auto reconnect can discover a new route
- prefixes outgoing non-registration messages with `player1:` or `player2:`
- exposes state through Kotlin `StateFlow`
- schedules reconnect attempts when configured

Despite the name, this is also UDP-based in the current code.

### `app/src/main/java/com/example/simplecontroller/UdpClient.kt`

Package: `com.example.simplecontroller.net`.

Like `NetworkClient.kt`, this file is physically under `com/example/simplecontroller/` even though its package is `.net`.

Responsibilities:

- manages a separate low-latency UDP socket
- supports normal legacy text sends to the receiver port discovered by `NetworkClient`
- supports CBv0 sends to port `9010` when enabled
- falls back to `NetworkClient.send()` if the UDP socket is not initialized or send fails
- sends touchpad deltas as `DELTA:x,y`
- sends scroll commands as `SCROLL:x`
- sends stick positions through `sendStickPosition()`
- normalizes stick names such as `LS`, `RS`, `LEFT`, `RIGHT`, `STICK_L`, and `STICK_R`
- tracks held keyboard keys and periodically sends `KEY_SYNC:<key>` while keys remain pressed
- clears key state and socket state on close

This is the preferred path for time-sensitive control output.

### `app/src/main/java/com/example/simplecontroller/model/Control.kt`

Defines the app's persisted control model.

Contains:

- `ControlType`: `BUTTON`, `STICK`, `TOUCHPAD`, `RECENTER`
- `Control`: serializable data class used for layout JSON
- `ControlType.defaultPayload()`: helper for default command strings

Because `Control` is serialized, adding/removing fields affects layout compatibility. `LayoutStorage` uses `ignoreUnknownKeys` and `encodeDefaults`, which helps old/new layout files survive model changes.

### `app/src/main/java/com/example/simplecontroller/io/LayoutStorage.kt`

Low-level JSON persistence helpers.

Responsibilities:

- creates filenames in app private storage as `layout_<name>.json`
- lists saved layouts
- loads controls with kotlinx serialization
- saves controls with pretty printed JSON
- ignores unknown keys when loading older or newer layout files
- writes default-valued fields so saved layouts are complete

### `app/src/main/java/com/example/simplecontroller/io/LayoutManager.kt`

Higher-level layout manager used by `MainActivity`.

Responsibilities:

- creates `ControlView` instances from `Control` models
- spawns all controls onto the canvas
- creates new controls in the center of the canvas
- shows save and load dialogs
- creates a new blank layout
- renames, duplicates, and deletes saved layout files
- supplies the app's default layout
- duplicates controls from existing models
- notifies `MainActivity` through `LayoutCallback` when layouts are saved/loaded or control views need clearing

### `app/src/main/java/com/example/simplecontroller/ui/ControlView.kt`

The on-screen view for one control.

Responsibilities:

- draws buttons, sticks, touchpads, and recenter controls
- holds touch state such as pressed/latch state, touchpad movement history, and click-lock state
- handles haptic feedback
- registers/unregisters itself with `SwipeManager`
- supports edit-mode dragging
- opens properties on long press in edit mode
- routes play-mode touches by control type
- delegates button payload behavior to `ControlViewHelper`
- delegates directional stick behavior to `DirectionalStickHandler`
- delegates non-auto-centered stick streaming to `ContinuousSender`
- implements touchpad mouse movement, scroll mode, one-finger drag, click lock, and double-tap click-lock
- computes normalized stick coordinates from touch position
- sends analog stick coordinates through `UdpClient.sendStickPosition()`
- sends final center packets when auto-center is enabled

For sticks, the app coordinate convention is:

- right: positive X
- left: negative X
- down: positive Y
- up: negative Y

The Python receiver inverts Y before sending to the virtual gamepad.

### `app/src/main/java/com/example/simplecontroller/ui/ControlViewHelper.kt`

Helper for labels, button payload firing, release cleanup, turbo repeat, global settings, and swipe coordination.

Responsibilities:

- draws/updates each control label
- opens the property sheet
- duplicates or deletes a control through `MainActivity`
- splits button payloads into commands
- appends `_HOLD` to Xbox commands when appropriate
- sends `_RELEASE` commands on release/unlatch
- resets analog triggers on release
- releases mouse-down payloads on lift
- releases keyboard commands on lift
- handles `SCROLL_MODE_TOGGLE`
- handles trigger pulse syntax like `RT:1.0P0.3`
- handles turbo repeat with `GlobalSettings.turboSpeed`
- parses `LS:`/`RS:` stick-direction button syntax and centers the stick on release

Also contains:

- `GlobalSettings`: shared flags for edit mode, snap, hold, scroll mode, turbo, turbo speed, and swipe mode
- `SwipeManager`: central registry for `ControlView` instances and a wrapper around `SwipeHandler`

### `app/src/main/java/com/example/simplecontroller/ui/DirectionalStickHandler.kt`

Handles stick modes that produce directional commands.

Responsibilities:

- converts analog stick position into up/down/left/right command payloads
- supports regular boost and super boost thresholds
- supports Stick+ mode, where analog stick output and directional commands are layered together
- sends trigger-like commands with intensity when they match supported trigger patterns
- can keep directional commands repeating after release when auto-center is off
- stops all directional state and sends a final stick center for actual stick controls

### `app/src/main/java/com/example/simplecontroller/ui/ContinuousSender.kt`

Keeps analog stick values streaming after the finger lifts when auto-center is off.

Responsibilities:

- stores the last stick/touchpad position
- applies a square response curve for finer center control
- throttles immediate position sends
- starts a repeating sender loop
- if auto-center is on, decays/glides toward center and then sends `0,0`
- if auto-center is off, keeps sending the last value without forcing center
- stops the sender and only forces center when the model itself is set to auto-center

### `app/src/main/java/com/example/simplecontroller/ui/SwipeHandler.kt`

Implements swipe-across-controls mode.

Responsibilities:

- tracks the active finger gesture
- finds which registered `ControlView` is under the finger
- forwards synthetic/down/move/up events to controls using local coordinates
- keeps move events going to the current control while the finger remains over it
- switches controls only after leaving the current one and entering another
- uses a short cooldown to avoid repeated enter/exit thrashing
- sends clamped `ACTION_UP` events when leaving a control so sticks capture a final vector cleanly

Known future fix:

The global Swipe switch can become impossible to turn off because swipe mode may consume `ACTION_UP` even when the gesture did not actually activate a `ControlView`. Do not fix this by requiring `ACTION_DOWN` to start on a control, because that would break the useful behavior where a finger can start in empty space and then swipe into a button.

The safer fix is to add a separate gesture-ownership flag, for example:

```kotlin
private var swipeOwnsGesture = false
```

Intended behavior:

- On `ACTION_DOWN`, reset `swipeOwnsGesture = false`.
- If the down event starts on a `ControlView`, forward the down event, set `swipeOwnsGesture = true`, and return `true`.
- If the down event starts in empty space or on normal app UI, keep enough state to allow later entry into a control if desired, but return `false`.
- On `ACTION_MOVE`, if the finger enters a `ControlView`, forward the control activation, set `swipeOwnsGesture = true`, and return `true`.
- On `ACTION_UP` / `ACTION_CANCEL`, only consume the event when `swipeOwnsGesture == true`.
- If `swipeOwnsGesture == false`, return `false` so normal Android UI widgets such as the Swipe switch receive their release/click event.
- Always reset `swipeOwnsGesture`, `activeTouch`, and `lastTouchedView` at the end of the gesture.

This preserves both intended cases:

- swiping from one control to another still works
- starting in empty space and entering a control can still work
- tapping app-level UI such as Swipe/Edit/Connect/Save/Load is not hijacked by swipe mode

### `app/src/main/java/com/example/simplecontroller/ui/PropertySheetBuilder.kt`

Builds and saves the per-control properties dialog.

Responsibilities:

- creates a scrollable dark-themed dialog
- edits common fields: label, width, height, payload
- edits button fields: hold toggle, hold duration, swipe activation
- edits stick/touchpad fields: sensitivity and auto-center
- edits touchpad fields: hold-left-while-touch, toggle click-lock, double-tap click-lock
- keeps touchpad click modes mutually exclusive
- edits stick fields: directional mode, Stick+ mode, directional commands, boost thresholds, super boost thresholds, command payloads, and reusable directional profiles
- duplicates or deletes controls from inside the dialog
- saves values back to the `Control` model and refreshes the rendered `ControlView`

### `app/src/main/java/com/example/simplecontroller/ui/ThemeManager.kt`

Centralizes theme selection and color lookup.

Responsibilities:

- remembers dark-mode preference in `theme_preferences`
- applies day/night mode through `AppCompatDelegate`
- exposes resource IDs for control colors
- provides helper methods for themed colors/text colors

The app currently defaults to dark mode.

### `app/src/main/java/com/example/simplecontroller/ui/UIComponentBuilder.kt`

Programmatic UI factory for `MainActivity`.

Responsibilities:

- creates corner buttons
- creates global switches
- creates floating action buttons
- adds views to the main canvas with `FrameLayout.LayoutParams`
- stacks switches vertically
- shows/hides groups of views
- creates the turbo-speed edit field plus apply button

## Android Resource Files

### `app/src/main/AndroidManifest.xml`

Defines app permissions and the launcher activity.

Important details:

- requests `INTERNET` for UDP/network sockets
- requests `VIBRATE` for haptic feedback
- launches `.MainActivity`
- marks the activity exported because it has a launcher intent filter
- declares resize/multi-window support
- handles configuration changes manually for orientation, screen size/layout, and keyboard hidden

### `app/src/main/res/layout/activity_main.xml`

The main app layout.

It is a full-screen `FrameLayout` with id `canvas`. The app adds almost everything else programmatically. It also includes a top-center `TextView` with id `connectionStatus`.

### `app/src/main/res/layout/dialog_connection_settings.xml`

The connection settings dialog layout.

Includes:

- server host input
- server port input
- player 1/player 2 radio buttons
- auto-reconnect checkbox

`MainActivity` dynamically adds the CBv0 checkbox to this dialog at runtime.

### `app/src/main/res/menu/main_menu.xml`

Defines one overflow menu item: `Toggle Dark Mode`.

### `app/src/main/res/values/colors.xml`

Defines app colors for dark background, text, buttons, sticks, touchpads, recenter controls, player indicators, and connection states.

### `app/src/main/res/values/themes.xml`

Defines `Theme.SimpleController`, based on `Theme.Material3.DayNight.NoActionBar`.

### `app/src/main/res/values/strings.xml`

Defines the app name string: `Simplecontroller`.

### `app/src/main/res/xml/backup_rules.xml`

Android backup include/exclude placeholder file. Currently no custom app data rules are active.

### `app/src/main/res/xml/data_extraction_rules.xml`

Android 12+ data extraction placeholder file. Currently no custom cloud/device-transfer data rules are active.

### `app/src/main/res/drawable/ic_launcher_background.xml`

Launcher icon background vector resource.

### `app/src/main/res/drawable/ic_launcher_foreground.xml`

Launcher icon foreground vector resource.

### `app/src/main/res/mipmap-*`

Launcher icon bitmap/XML resources for different densities and adaptive icon support.

## Build and Gradle Files

### `settings.gradle.kts`

Names the root project `Simplecontroller`, configures plugin/dependency repositories, and includes the `:app` module.

### `build.gradle.kts`

Top-level Gradle plugin declarations. Android application, Kotlin Android, and Kotlin Compose plugins are available through the version catalog, but not directly applied at the root.

### `app/build.gradle.kts`

Android app module configuration.

Important details:

- Android application plugin
- Kotlin Android plugin
- Kotlin serialization plugin version `2.0.21`
- namespace: `com.example.simplecontroller`
- application IDs: `com.example.simplecontroller` for `sideloadDebug` and
  `io.github.colonelwheel.simplecontroller` for `playRelease`
- compile SDK 36, min SDK 24; target SDK 34 for sideload and 36 for Play
- Java/Kotlin target 1.8
- release minification disabled
- no custom ABI/density splits, so the sideload build produces one universal APK
- dependencies include kotlinx serialization JSON, coroutines Android, AndroidX core/appcompat, and Material

### `gradle/libs.versions.toml`

Version catalog for Gradle plugins and libraries. Some Compose entries exist, but the current app UI is classic Android views, not Compose.

### `gradle.properties`

Project-wide Gradle settings:

- JVM heap/file encoding
- AndroidX enabled
- official Kotlin code style
- non-transitive R classes enabled

### `gradlew` and `gradlew.bat`

Gradle wrapper launchers for Unix-like shells and Windows.

### `gradle/wrapper/gradle-wrapper.jar`

Gradle wrapper binary.

### `gradle/wrapper/gradle-wrapper.properties`

Gradle wrapper distribution configuration.

### `app/proguard-rules.pro`

Placeholder ProGuard/R8 rules file. Release minification is currently disabled, so this is not doing much unless release settings change later.

### `local.properties`

Local Android SDK path file. This should stay local and not be committed.

## Tests

### `app/src/test/java/com/example/simplecontroller/ExampleUnitTest.kt`

Template JVM unit test that checks `2 + 2 == 4`. It does not currently test app behavior.

### `app/src/androidTest/java/com/example/simplecontroller/ExampleInstrumentedTest.kt`

Template Android instrumentation test that checks the app package name. It does not currently test UI/control behavior.

## Windows Receiver Files

### `simple_controller_receiver.py`

Main Windows receiver script.

Responsibilities:

- listens on UDP `0.0.0.0:42734`
- responds to `SIMPLE_CONTROLLER_DISCOVER` so the Android app can auto-detect it on Wi-Fi or USB tethering
- writes logs under `%LOCALAPPDATA%/SimpleControllerReceiver/touchpad_logs/`
- creates two virtual Xbox 360 gamepads with `vgamepad`
- tracks button, keyboard, mouse, and connection state per player
- routes `player1:` and `player2:` prefixed packets
- handles `PING`, `CONNECT:<player>`, and `REGISTER:<player>`
- handles touchpad/mouse deltas through `pyautogui.moveRel`
- handles scroll through `pyautogui.scroll`
- handles mouse buttons
- handles analog sticks and triggers through `vgamepad`
- handles Xbox button press/hold/release mappings
- auto-releases non-hold Xbox button presses after a short timer
- processes comma-separated command sequences on a background thread
- cleans inactive connections and key states on a timer

Dependencies include `keyboard`, `mouse`, `vgamepad`, and `pyautogui`. Xbox controller output requires ViGEm on Windows.

### `controller_server_*.log`

Runtime logs from previous receiver runs. These are evidence/debug artifacts, not source code.

### `touchpad_logs/controller_server_*.log`

Runtime logs written by the Python receiver to `touchpad_logs/`.

### `touchpad_logs/touchpad_stable_*.log`

Touchpad-related runtime/debug log artifact.

## ConsoleBridge / Arduino Firmware Files

The `.ino` files are firmware variants for a Wi-Fi RP2040/Pico-style receiver using TinyUSB HID.

Common behavior:

- connect to configured Wi-Fi station mode, or fall back to setup AP mode
- listen for UDP commands on `9010`
- optionally expose a small HTTP page on port `80` for reboot/BOOTSEL
- decode CBv0 packets
- decode some legacy ASCII command tokens
- send USB HID keyboard, mouse, and/or gamepad reports
- blink the onboard LED for status/activity

Important security note: these files contain hard-coded Wi-Fi credentials. Do not share them publicly without removing or replacing those values.

## Planned: Android/On-Device Camera Follow for Pico/Console Use

Status: **planning only; not implemented yet**. No Android logic or Pico firmware was changed for this idea during the 2026-09-08 session.

### Why this version is needed

The current Camera Follow state machine runs in the Python PC receiver. That works for the virtual Xbox controller on Windows, but it is unavailable when SimpleController sends to a Pico W for console use. The planned solution is to optionally run the same state machine in the Android app and transmit its result as ordinary right-stick coordinates. If the selected Pico firmware already accepts the app's normal ordered RS coordinate packets, the Pico does not need to understand or calculate Camera Follow itself.

### Preserve two execution modes

Do not replace the proven Python implementation. Add an explicit Camera Follow execution/transport choice:

- **PC Receiver**: keep today's Python Camera Follow behavior and send the existing `CAMERA_FOLLOW:1` / `CAMERA_FOLLOW:0` commands.
- **On Device / Pico**: run Camera Follow in Kotlin and send synthesized ordinary RS coordinate packets.

Only one implementation may be active at a time. Android must not generate RS assistance while also asking the Python receiver to generate it. Camera Follow remains disabled by default.

### Intended Android architecture

Port `camera_follow.py` to a small, pure Kotlin state-machine class with equivalent settings and deterministic time-based update behavior. Keep it independently unit-testable.

Feed the engine the app's **already processed, normalized outgoing state**, not raw touch coordinates:

- latest LS X/Y after the selected normal or Response Curve sensitivity behavior
- latest real/manual RS X/Y
- whether an explicit RS macro is active
- whether manual camera input is coming from TouchAim
- connection/input-valid state

All existing LS-producing paths must feed the same state, including normal Stick, Response Curve Stick, and TouchAim Left Stick. All manual camera sources must suppress assistance appropriately, including a normal RS and TouchAim Right Stick. TouchAim Mouse should also be treated as manual camera activity when relevant so automatic RS output does not fight it.

Generated output must use the existing `UdpClient` stick-coordinate sender and its packet session/sequence ordering. Do not create a second direct socket, bypass ordering metadata, or introduce an unrestricted per-frame sender. Reuse the existing rate/cadence conventions so the feature does not flood the Pico or compete with TouchAim.

Likely Android touchpoints to inspect before editing:

- `camera_follow.py`: exact reference behavior to translate
- `UdpClient.kt`: ordered stick packet emission and centralized outgoing-state/arbitration boundary
- `DirectionalStickHandler.kt`: normal and Response Curve LS/RS values
- `TouchAimHandler.kt` and `SwipeHandler.kt`: TouchAim Left/Right/Mouse sources and cleanup
- `ControlView.kt` / `ControlViewHelper.kt`: explicit stick macro press/release lifecycle
- existing settings/property code: persist the selected Camera Follow execution mode without creating an unrelated UI system
- a new focused Kotlin engine and corresponding JVM unit tests

Keep the refactor narrow. If the current handlers cannot distinguish manual RS from synthesized or macro RS cleanly, add only a small shared stick-source/arbitration state near `UdpClient`; do not rewrite working control subsystems.

### Required output priority and behavior

Use the same priority as the PC implementation:

1. Explicit RS macro/button command
2. Real/manual RS input, including applicable TouchAim camera input
3. Android-generated Camera Follow horizontal input
4. Neutral RS

The Kotlin implementation should retain the existing Camera Follow semantics and defaults unless deliberately changed later:

- movement deadzone and activation delay before assistance
- proportional horizontal output based primarily on LS X
- no generated vertical movement
- optional direction inversion
- ramp-in and ramp-out
- maximum-output clamp
- immediate yield to manual camera input
- manual override timeout before smooth resumption
- optional sideways steering behavior
- immediate generated-output cleanup on disable, disconnect, input loss, layout/control destruction, or app shutdown

Generated Camera Follow must modify only the horizontal component it owns. It must not erase or alter a higher-priority source's RS Y value, affect LS output, auto-release held buttons, duplicate presses, break Turbo/RELEASE, interfere with boost thresholds, bypass packet ordering, or conflict with any TouchAim mode.

### Pico/UF2 uncertainty and safety stop

The correct production UF2 is currently unknown, and physical Pico testing will not be available for a while. Do **not** guess which UF2 is correct, flash a board, or modify a random `.ino` variant based only on its filename.

Known candidate artifacts currently include:

- `receiver.uf2`
- `touchpad_logs/receiver W dpad.uf2`
- several `Rate limit...ino`, Pokken, DS3, and pointer firmware variants

At the next session, first inventory the candidate UF2/INO files, record hashes and timestamps, and compare their supported packet formats. Where possible, inspect UF2 strings/metadata and match them to source without writing to hardware. Confirm whether the intended firmware accepts the same ordered LS/RS coordinate packets and whether it expects legacy text on its controller port or ConsoleBridge CBv0 on port `9010`.

If the correct firmware already consumes the ordinary RS coordinates emitted by `UdpClient`, no Camera Follow computation should be added to the Pico. If its protocol lacks the required coordinate form or ordering metadata, document that exact gap before proposing the smallest firmware change.

### Planned verification sequence

Work in this order when the feature is resumed:

1. Identify the likely production UF2/source and its actual input protocol without flashing anything.
2. Confirm the Android packet path used by that firmware for LS, RS, macros, and TouchAim.
3. Translate the Python state machine into pure Kotlin with fake-clock/unit-test support.
4. Add explicit **PC Receiver** versus **On Device / Pico** execution selection and prevent double assistance.
5. Integrate through the existing ordered stick sender and source-priority rules.
6. Run Kotlin tests for disabled, deadzone, activation delay, proportional steering, ramp-out, manual override, override timeout, macro priority, disable cleanup, disconnect/input loss, and duplicate explicit state.
7. Build the APK and perform a packet-level test using a local capture/debug harness even if the Pico is unavailable.
8. Mark console/Pico behavior as unverified until the correct board and UF2 can be physically tested. Later test left/right direction, manual takeover, macros, TouchAim modes, packet ordering, and disconnect cleanup on the actual console path.

This plan lets the Android work be developed and unit-tested while preserving the working PC receiver. Final Pico/console sign-off must wait for hardware access and positive identification of the proper UF2.

### `POINTER WORKS (4).ino`

ConsoleBridge firmware variant with keyboard/mouse plus gamepad support enabled by default (`GAMEPAD_ONLY` defaults to `0`).

It defines HID descriptors, keyboard/mouse report sending, generic gamepad report sending, legacy ASCII parsing, CBv0 parsing, UDP receive loop, and HTTP reboot helpers.

### `Rate limit.ino`

Firmware variant focused on rate-limited sending. It has similar Wi-Fi, HID, legacy token, CBv0, HTTP, setup, and loop structure. `GAMEPAD_ONLY` is enabled in this variant.

### `Rate limit Switch controllerPossible pokken idless.ino`

Firmware variant for Switch/HORI Pokken-style experimentation. It enables gamepad-only behavior and contains Switch/Pokken-related profile flags.

### `Rate limit Switch controller Possibly ds3.ino`

Firmware variant for DS3/Switch controller profile experimentation. It includes flags for DualShock 3 emulation and HORI Pokken mode.

### `possible pokken.ino`

Another Switch/HORI Pokken-style firmware experiment. It is similar to the other Pokken-named variant.

### `receiver.uf2`

Compiled UF2 firmware image for an RP2040-family board.

### `touchpad_logs/receiver W dpad.uf2`

Another compiled UF2 firmware image stored under the logs/artifacts folder.

## Packaged Binary/Archive Files

### `SimpleController App.apk`

Built Android APK artifact.

### `SimpleControllerSetup.exe`

Windows setup executable artifact.

### `ViGEmBus_1.22.0_x64_x86_arm64.exe`

ViGEmBus installer used for virtual Xbox controller support on Windows.

### `Simplecontroller.zip`

Project/release archive artifact.

### `app/src/main/java/com/example/simplecontroller.zip`

Zip archive stored inside the Java source tree. This is not source code and should usually not live inside `app/src/main/java`.

## Existing Documentation Files

### `README.txt`

User/setup guide. It explains what SimpleController does, required hardware/software, Android installation, Windows receiver setup, ViGEm, local-network behavior, profile creation, usage, and troubleshooting.

### `AGENTS.md`

Developer/contributor guidelines. It documents the expected project structure, build/test commands, Kotlin style, test guidance, PR guidance, ConsoleBridge integration notes, and operational notes.

### `IMPLEMENTATION_PLAN.md`

Refactoring migration plan from an older monolithic architecture toward extracted helper classes.

### `REFACTORING.md`

Explains the refactoring goals and extracted responsibilities: `SwipeHandler`, `DirectionalStickHandler`, `ContinuousSender`, `PropertySheetBuilder`, `ControlViewHelper`, `GlobalSettings`, `UIComponentBuilder`, and `LayoutManager`.

## IDE and Generated Files

### `.gitignore`

Ignores Gradle/IDE/build/local files such as `.gradle`, `local.properties`, `.idea/workspace.xml`, `/build`, and native build outputs.

### `.idea/*`

Android Studio project metadata. Some files are useful locally, but workspace/cache files are usually machine-specific.

### `.gradle/*`

Gradle cache/build metadata. Generated locally.

### `.kotlin/errors/*`

Kotlin/IDE error report logs. Generated locally.

## Current Caveats Worth Remembering

At the time this note was written:

- The working tree had existing uncommitted changes outside this documentation note, including `ControlViewHelper.kt` and `ContinuousSender.kt`.
- `NetworkClient.kt` and `UdpClient.kt` declare package `com.example.simplecontroller.net` but live directly under `com/example/simplecontroller/`. Kotlin can compile this, but Android Studio may show confusing unresolved references or package/indexing warnings.
- `RefactoredMainActivity.kt` is not the launcher activity and appears older than `MainActivity.kt`.
- Camera Follow and stick-response behavior have focused automated tests; USB discovery was verified by syntax, Android compilation/unit tests, and receiver packaging but still requires an end-to-end phone/PC tether test on the actual devices.
- The `.ino` firmware files include hard-coded Wi-Fi credentials.
- Build verification from this shell requires Java/JDK configuration. Without `JAVA_HOME` or `java` on `PATH`, Gradle compile commands cannot run here.
