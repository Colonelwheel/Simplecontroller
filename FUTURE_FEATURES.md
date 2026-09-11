# SimpleController Future Feature To-Do List

This is a parking lot for potential future work, not an implementation order or authorization to change working behavior. Before implementing an item, inspect the current source, preserve existing Hold/latch/Swipe behavior, agree on exact semantics, and add focused regression tests.

## Priority candidates

### TouchAim calibration wizard

- [ ] Replace threshold guesswork with a guided calibration flow.
- [ ] Collect repeated Light, Medium, and Firm samples while both stationary and moving.
- [ ] Allow a longer settling period and multiple cycles per level.
- [ ] Calculate useful statistics such as median, spread, and P10/P50/P90 values.
- [ ] Evaluate Size, TouchMajor, TouchMinor, and combined contact-score candidates.
- [ ] Report overlap and estimated misclassification instead of claiming unreliable levels are distinct.
- [ ] Recommend smoothing, ON thresholds, OFF thresholds/hysteresis, and confirmation timing.
- [ ] Offer a live validation pass before saving the calibration.
- [ ] If three levels are unreliable, recommend a safer two-level or one-secondary-action setup.
- [ ] Store calibration per device and optionally per layout/control.
- [ ] Preserve manual tuning after calibration.

Likely touchpoints: `TouchDiagnosticActivity.kt`, `TouchAimHandler.kt`, `Control.kt`, `PropertySheetBuilder.kt`, and focused JVM/instrumentation tests.

### One-tap Release Everything safety action

- [x] Add an explicit idempotent `RELEASE_ALL` command.
- [x] Clear held/latched Xbox buttons, triggers, keyboard keys, mouse buttons, stick coordinates/macros, Turbo/pulse loops, TouchAim stage holds, Camera Follow output, and any future action-layer state that can hold an output.
- [x] Clear the Android-side state as well as the receiver-side state.
- [x] Make repeated or delayed duplicate commands harmless.
- [x] Provide `RELEASE_ALL` as a normal assignable Button payload. No always-visible built-in button is shown.
- [x] Add tests for activation during each held-output type, disconnection, and packet duplication.

Likely touchpoints: `UdpClient.kt`, `ControlView.kt`, `ControlViewHelper.kt`, `TouchAimHandler.kt`, `simple_controller_receiver.py`, and receiver tests.

### Sticky action layers

- [ ] Allow a one-finger tap to activate/deactivate a named alternate mapping layer without holding a modifier.
- [ ] Let a layer override selected controls while allowing unchanged base controls to remain available.
- [ ] Support explicit Set/Enable/Disable commands so duplicate UDP packets cannot invert state accidentally.
- [ ] Display the active layer clearly and use distinct haptic feedback for entry/exit.
- [ ] Provide optional one-action layers that return to the base layer after the next selection.
- [ ] Define deterministic stacking/priority or initially permit only one active layer to avoid conflicts.
- [ ] Release or reconcile outputs safely when switching layers.

### One-finger radial menu

- [ ] Add a radial control that opens from one touch, selects by sliding into a large wedge, and activates on release.
- [ ] Provide a large center cancel/dead zone so returning to the middle and releasing performs no action.
- [ ] Support four to eight configurable wedges, labels/icons, payloads, and optional nested menus.
- [ ] Allow adjustable activation radius and wedge boundaries.
- [ ] Keep ownership of the finger until release and do not require a second touch.
- [ ] Ensure cancel, app pause, and `ACTION_CANCEL` never send a selection.

### Automatic layout snapshots and Undo

- [ ] Save a lightweight snapshot before moving, resizing, duplicating, deleting, or changing a control.
- [ ] Add one-tap Undo/Redo in edit mode.
- [ ] Keep a small bounded history so storage cannot grow indefinitely.
- [ ] Add named restore points for known-good game layouts.
- [ ] Make snapshot format versioned and tolerant of newly added control properties.

### Automatic per-game profile switching

- [ ] Let the Windows receiver map foreground executables to SimpleController layouts.
- [ ] Send an explicit suggested/selected profile to Android without changing controller outputs.
- [ ] Offer Off, Ask, and Automatic modes.
- [ ] Announce profile changes visibly and haptically so they never happen silently.
- [ ] Fall back safely when an executable is unknown or the requested layout is missing.
- [ ] Avoid sending foreground-window titles or other unnecessary private data.

### On-device Camera Follow for ConsoleBridge/Pico

- [ ] Continue the plan in `APP_ARCHITECTURE_NOTES.md` for a pure Kotlin Camera Follow state machine.
- [ ] Preserve the proven Python receiver implementation.
- [ ] Add an explicit PC Receiver versus On Device/Pico execution choice; never run both simultaneously.
- [ ] Reuse ordered stick packets and the established macro/manual/generated priority.
- [ ] Identify the production Pico firmware and its real protocol before changing or flashing anything.
- [ ] Keep console behavior marked unverified until it is tested on the actual hardware.

## Button Aim Surface

### Goal and examples

Add an optional mode to an ordinary button so one continuous finger contact can both operate that button's payload and control Mouse, Right Stick, or Left Stick by moving across the screen.

Two target workflows:

1. Immediate payload: touch an `RT` button to hold RT and enter the game's targeting/teleport mode, aim while keeping the same finger down, then release to release RT and commit the teleport.
2. Delayed payload: latch LT to remain in ADS, touch an `RT` button and aim without sending RT yet, then release the finger when the shot should occur. Optionally make the released payload a persistent latched hold until explicitly unlatched.

### Proposed properties

- [ ] `Aim while pressed` checkbox on Button controls.
- [ ] Keep the ordinary Button `Payload` field fully configurable. LT and RT are examples only; allow any payload already supported by a normal button, including other gamepad inputs, keyboard keys, mouse buttons, macros, and multi-command payloads.
- [ ] `Aim output` choice: Mouse, Right Stick, or Left Stick.
- [ ] `Send payload on release` checkbox.
- [ ] Aim sensitivity.
- [ ] Invert Y.
- [ ] `Aim movement profile` choice appropriate to the selected output: Linear or Response Curve for Left/Right Stick; Linear Relative or the current Smoothed/Nonlinear scaling for Mouse.
- [ ] Stick displacement/deadzone settings.
- [ ] Clear visual and haptic indication for immediate, armed, and latched states.

Payload and aim output must remain independent. For example, a mouse-button payload may aim with Right Stick, and a gamepad-button payload may aim with Mouse. Reuse the existing `TouchAimOutput` choices and shared aim math where practical, but do not make the new button depend on TouchAim contact-level thresholds.

### Immediate-payload behavior

- [ ] On `ACTION_DOWN`, perform the button payload using its existing normal behavior.
- [ ] While the same finger remains down, movement controls the selected aim output.
- [ ] On `ACTION_UP`, stop/center the aim output and release the payload unless it is latched.
- [ ] If Hold Toggle becomes active, keep the payload held after finger release but always stop/center the aim output on release.
- [ ] A later tap on an already latched button should retain the existing immediate-unlatch behavior.

### Delayed-payload behavior

- [ ] On `ACTION_DOWN`, arm the button without sending its payload.
- [ ] Allow Mouse/Right Stick/Left Stick aiming immediately while armed.
- [ ] On intentional `ACTION_UP`, stop/center aim and then send the payload with the agreed release ordering.
- [ ] With Hold Toggle off, send a normal finite press on release.
- [ ] With Hold Toggle on, support a release-time latched hold that persists until the button is explicitly unlatched.
- [ ] Never fire the delayed payload on `ACTION_CANCEL`, app pause, edit-mode entry, control deletion, connection loss, or invalid pointer ownership.

### Gesture ownership and Swipe conflict

- [ ] The initiating button must retain exclusive ownership of its pointer until lift/cancel, even when the finger moves outside the button bounds.
- [ ] While Button Aim Surface owns the pointer, global Swipe must not transfer the gesture to another control.
- [ ] Other controls must not fire merely because the aiming finger crosses them.
- [ ] A Button Aim Surface that begins through Swipe requires a separate design decision; the safe initial implementation should require `ACTION_DOWN` on the button itself.
- [ ] Continue receiving movement outside the original view bounds through the parent-level dispatcher or equivalent pointer-capture routing.

### Aim coordinate recommendation

- [ ] For Mouse, use relative deltas from the previous sample. Let the user choose raw Linear Relative movement or the proven TouchAim/touchpad Smoothed/Nonlinear scaling; keep cadence and rate limiting safe in both modes.
- [x] For Left/Right Stick, keep displacement from the initial finger-down point as the safe default, with an optional Touch Aim-style setting that maps the touched position within the button directly to the stick. Both modes support Linear or Response Curve mapping.
- [ ] Clamp stick output, resend it at the established cadence, use ordered stick packets, and explicitly center on every terminal path.
- [ ] Decide whether return-to-origin centers the stick and whether a configurable floating-origin/recenter behavior is desirable.

### Output ordering and arbitration

- [ ] Define release-time ordering deliberately. A safe starting rule is: emit the delayed payload first, then center the aim output immediately afterward, so the action observes the final aimed state.
- [ ] Route stick output through the existing ordered `UdpClient.sendStickPosition()` path.
- [ ] Treat Button Aim Surface Right Stick as real/manual RS input so it overrides Camera Follow.
- [ ] Respect explicit RS macro priority and avoid two controls fighting over the same stick.
- [ ] Define behavior if another real stick/TouchAim surface is already active; a safe initial rule is first active pointer owns the stick until release.
- [ ] Keep ConsoleBridge encoding and rate limits unchanged unless its verified protocol requires a narrowly scoped addition.

### Hold/latch compatibility requirements

- [ ] Preserve current ordinary button behavior when `Aim while pressed` is off.
- [ ] Preserve current Hold Toggle timing, unlatch behavior, Turbo/pulse behavior, and explicit release handling unless a new setting explicitly selects different semantics.
- [ ] Do not infer held state only from payload spelling; use explicit per-gesture state.
- [ ] Ensure quick release, long release, already-latched touch, Turbo, global Hold, and multiple-command payloads each have specified and tested behavior.
- [ ] Do not let an aim update retrigger the payload or Hold timer.

### Suggested implementation shape

- [ ] Extract reusable aim tracking/output code rather than duplicating `TouchAimHandler` wholesale.
- [ ] Add serialized Button Aim properties to `Control.kt` with disabled defaults so old layouts behave identically.
- [ ] Add the controls to `PropertySheetBuilder.kt` only for Button controls.
- [ ] Keep a small explicit gesture state machine, for example Idle -> Armed/Aiming -> ImmediateHeld or DelayedArmed -> Released/Latched.
- [ ] Add focused unit tests for state transitions and instrumentation tests for pointer movement outside the button.

### Decisions to confirm before implementation

- [x] Button Aim Surface payloads are user-configurable rather than hard-coded to LT/RT. Base and one-shot alternate payloads are configured independently and may use any normal Button payload, including mouse buttons.
- [x] Aim movement behavior is selectable. Stick output offers Linear or Response Curve; Mouse output offers Linear Relative or the existing Smoothed/Nonlinear scaling.
- [x] With `Send payload on release` and Hold Toggle both enabled, a quick release sends a normal press while a long release creates a latch.
- [x] Stick aiming defaults to the initial touch point as neutral, with an optional per-phase checkbox to use the touched position as the immediate stick position.
- [x] The button retains finger ownership outside its bounds and temporarily suppresses global Swipe.
- [x] On intentional release, send the delayed payload first and then center the aim output immediately afterward.
- [x] Delayed-payload Button Aim Surfaces initially disable Turbo. Immediate-payload aiming may still support Turbo.

## One-Shot Alternate Button Phase

### Goal and example

Allow a button, especially a Button Aim Surface, to become a configured alternate button for exactly one subsequent gesture and then automatically return to its base function.

Example flow:

1. The surface starts as LT plus aim.
2. The first completed LT gesture arms an alternate RT plus aim phase. LT may have released normally or may remain latched, according to the base button's settings.
3. The next touch on the same physical surface behaves as RT plus aim, so the finger can reacquire/continue tracking and fire without traveling to another button.
4. Normally completing and releasing the temporary RT gesture releases RT, releases any still-latched LT base payload, and returns the surface to an unlatched LT function.
5. If Android unexpectedly cancels the temporary RT gesture, the surface remains armed as RT rather than unexpectedly returning to LT. A deliberate adjustable hold-and-release reset returns it to unlatched LT.

This should be a separate optional state layered onto Button Aim Surface rather than a mutation of `model.payload`. Keep the persisted base and alternate configurations stable and track only the active phase at runtime.

### Proposed properties

- [ ] `Use one-shot alternate` checkbox.
- [ ] `Alternate payload` field.
- [ ] `Alternate send timing`: Immediate or On release.
- [ ] `Alternate reset hold duration`, defaulting to 2,000 ms.
- [ ] Optional alternate name, color, and haptic pattern so the armed state is unmistakable.
- [ ] Initially reuse the base Button Aim Surface output, sensitivity, inversion, response curve, and displacement settings.
- [ ] Consider independent alternate aim settings only if a real use case requires them; avoid duplicating every property initially.
- [ ] Keep the alternate action momentary by default. Consider an explicit separate `Alternate can latch` option only after the base behavior is proven.

### Runtime state model

- [ ] Use explicit states such as `BASE`, `ALTERNATE_ARMED`, and `ALTERNATE_ACTIVE`; do not rewrite the serialized base payload.
- [ ] A successful intentional base activation transitions `BASE -> ALTERNATE_ARMED`.
- [ ] Touch-down on an armed surface transitions `ALTERNATE_ARMED -> ALTERNATE_ACTIVE` and uses the alternate payload/timing.
- [ ] Intentional normal alternate release cleans up alternate outputs, releases any base payload that remained latched for the alternate gesture, and transitions `ALTERNATE_ACTIVE -> BASE` with Base unlatched.
- [ ] Unexpected alternate `ACTION_CANCEL` cleans up active alternate output but transitions back to `ALTERNATE_ARMED`, allowing an RT retry instead of silently changing the surface to LT.
- [ ] While left in the canceled/armed alternate state, holding the surface for at least the configured reset duration arms the reset, but does not perform it until release. The RT gesture remains valid throughout; releasing completes RT normally and then returns the surface to unlatched Base.
- [ ] Aim output is available during both base and alternate gestures and always centers/stops at each gesture's terminal event.
- [ ] A base action should arm the alternate only if the base action actually activated. A canceled gesture, failed connection, or Hold Toggle quick release that intentionally does nothing must not arm it.

### Latched-base behavior

- [ ] If the base payload (for example LT) remains latched when the alternate phase is armed, keep that base payload held throughout the temporary alternate gesture.
- [ ] The alternate touch must bypass the ordinary `already latched -> unlatch immediately` branch; otherwise touching RT would incorrectly release LT.
- [ ] Track base-held and alternate-held commands separately so cleaning up RT cannot release the latched LT.
- [ ] After a normally completed alternate releases, automatically release the latched LT base payload and return to unlatched Base. Do not require another LT touch solely to exit ADS.
- [ ] If base and alternate payloads address the same logical output, define ownership before implementation so one phase cannot release an output still owned by the other.

### Delayed alternate behavior

- [ ] With alternate timing set to On release, aim during the alternate gesture without sending its payload.
- [ ] On intentional lift, send the alternate payload first and center/stop aim immediately afterward, following the Button Aim Surface release-order decision.
- [ ] A reset-duration alternate gesture remains a valid RT gesture. Immediate RT stays held until release; On-release RT fires normally on release. After RT completes, release any latched base payload and reset the surface to unlatched Base.
- [ ] Never fire a delayed alternate payload on `ACTION_CANCEL`, app pause, edit-mode entry, control deletion, connection loss, invalid pointer ownership, or an unsuccessful base activation.

### Visual, haptic, and reset behavior

- [ ] Visibly change the label/color while the alternate is armed and active; hidden phase changes are unsafe and confusing.
- [ ] Use distinct optional haptic feedback when the alternate becomes armed, activates, and returns to Base.
- [ ] Reset the temporary phase to Base on layout load/change, edit-mode entry, control deletion, app shutdown, explicit disconnect, and `RELEASE_ALL`.
- [ ] On alternate `ACTION_CANCEL`, center aim, release any alternate-held outputs, and remain visibly `ALTERNATE_ARMED` as RT. Preserve an intentionally latched base payload for retry unless a global safety cleanup such as disconnect, app shutdown, or `RELEASE_ALL` requires releasing it.
- [ ] While recovering from cancellation, use a distinct reset-armed haptic when the configured hold duration is reached; return to unlatched Base only when that long hold is released.
- [ ] A global safety cleanup always releases both base and alternate outputs and returns to unlatched Base, even if the ordinary cancellation-retry behavior would remain armed.
- [ ] Do not persist the temporary armed phase across app restarts.

### Conflicts and safeguards

- [ ] Reuse Button Aim Surface's exclusive pointer ownership and Swipe suppression.
- [ ] Ensure entering the alternate phase does not restart the base Hold timer or resend the base payload.
- [ ] Ensure returning to Base does not automatically activate or release the base payload.
- [ ] Reconcile multiple-command payloads by logical output key, not only raw string equality.
- [ ] Respect manual-stick, macro, TouchAim, and Camera Follow arbitration during both phases.
- [ ] Decide whether global Hold and Turbo are supported in the alternate phase. Safe initial behavior: no global Hold or Turbo for an On-release alternate.
- [ ] Add focused transition tests for unlatched base, latched base, immediate alternate, delayed alternate, early/canceled base gestures, alternate cancel, duplicate packets, connection loss, and payload-key collisions.

### Decisions to confirm before implementation

- [x] When LT remains latched, it stays held during the temporary RT gesture. Normal RT completion then automatically unlatches LT and returns the surface to unlatched Base.
- [x] The alternate has its own Immediate/On-release choice.
- [x] The initial version keeps the alternate momentary rather than giving it an independent Hold Toggle.
- [x] Unexpected alternate cancellation leaves the surface armed as RT for retry instead of returning to LT.
- [x] Resetting a canceled alternate requires holding the surface for at least 2 seconds by default, then releasing; the duration is adjustable.
- [x] Reaching the alternate reset duration only arms the reset; it does not switch back to LT until release.
- [x] The long reset gesture does not suppress RT. Immediate RT remains active during the hold, while delayed RT fires normally on release; RT completes before the surface returns to unlatched LT.

## General implementation safeguards

- [ ] Implement one feature at a time in a focused branch/commit.
- [ ] Add safe serialized defaults so existing layouts load with unchanged behavior.
- [ ] Test Hold, latch/unlatch, Turbo, Swipe, multiple-command payloads, explicit releases, packet ordering, disconnect cleanup, and Player 1/2 after any input-path change.
- [ ] Keep the current Python Camera Follow behavior and ConsoleBridge binary behavior intact unless the selected feature explicitly requires a verified change.
- [ ] Build and test Android plus the Windows receiver before packaging.
