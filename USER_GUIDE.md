# SimpleController User Guide

SimpleController turns an Android phone or tablet into a customizable controller for a Windows PC. The phone sends commands over your local network to the Windows receiver, which produces keyboard, mouse, or virtual Xbox-controller input. Buttons, sticks, aiming surfaces, and floating controls can be arranged around your comfortable reach and operated with one finger.

## Install and connect

### What you need

- An Android device running Android 7.0 or newer.
- A Windows PC; Windows 10 or 11 is recommended.
- A shared local network, or a USB connection with Android USB tethering.
- The [Android APK](SimpleController.apk), [Windows receiver](SimpleControllerReceiver.exe), and [ViGEmBus driver installer](ViGEmBus_1.22.0_x64_x86_arm64.exe).

Install **ViGEmBus once on the PC before starting the receiver**. Run its installer and restart Windows if requested. The receiver creates virtual Xbox controllers at startup, even if your first test uses keyboard input. The receiver EXE does **not** install this driver for you. You do not need to reinstall the driver for each app update.

Open the APK on Android and allow installation from that browser or file manager when Android asks. Start the receiver on Windows and leave it running. The packaged EXE does not require a separate Python installation. Run only one SimpleController receiver at a time: different receiver versions use the same port and can create additional virtual controllers.

Allow the receiver through Windows Firewall on your trusted **Private** network. Keep the firewall enabled and do not forward the controller port through your router. Guest networks often prevent the phone from reaching the PC.

### Wi-Fi or a shared home network

1. Start the receiver and read its console's **IP Address** and **Port**.
2. Connect the phone and PC to the same local network. The PC can use Ethernet while the phone uses Wi-Fi.
3. On the phone, tap **Connect**. Enter the displayed PC address and port **42734**.
4. Leave **Use ConsoleBridge (CBv0)** unchecked for the Windows receiver. Choose **Player 1** for your first test, then connect.
5. Test a simple Button such as `X360A`, or a keyboard Button such as `W` in Notepad.

Enter `42734` explicitly if an older saved setting or the initial port field shows `9001`. The Windows receiver uses **UDP 42734**. Wi-Fi setup uses the address you enter; tapping Connect does not perform general Wi-Fi receiver discovery.

The **Connected** label means the app started its UDP connection; it does not prove that a game received input. Confirm receiver activity and test a simple command before making a complicated layout.

### USB tethering

Connect the phone to the PC with USB and start the receiver. On the phone, choose **Connect → USB Tether Mode**, enable USB tethering in Android's settings, then return to SimpleController. Allow about 12 seconds for receiver discovery. This discovery path finds the tether-network address without replacing your saved Wi-Fi address.

USB tethering creates a network connection; USB debugging is not required. If Windows classifies that connection as Public, allow this receiver and port specifically rather than opening broad Public-network access.

## Create and save your controller

The app calls a complete saved controller a **profile** or **layout**. It can contain several pages and independent Portrait and Landscape arrangements.

1. Tap **Edit**; it changes to **Done**.
2. To start empty, choose **Load → New Layout**. To adapt an existing profile, load it first.
3. Tap **+** and choose a control. A new control opens its properties automatically.
4. Give it a recognizable label, set its payload, and adjust its size and behavior.
5. Drag controls to move them. Long-press an existing control in Edit mode to reopen its properties.
6. Tap **Save** and choose a name, then **Done** to play.

Use large targets and put frequently used actions near your easiest resting position. Build and test one control at a time. Keep a reachable Button with payload `RELEASE_ALL` in every playing layout.

**Load** switches saved profiles. Long-press a saved profile for Rename, Duplicate, Export, or Delete. Save under a new name when you want to preserve your previous setup. Although the app normally saves the working profile when it pauses, explicitly Save after meaningful edits.

### Pages and orientation

In Edit, tap **Editing page: Base ▾** to duplicate the current page, add a blank page, import a saved profile's page, rename a page, or choose Home. Each page has its own complete controls. Duplicated pages are independent copies.

An ordinary Button's **Local page action** can Go to a page, Toggle a page, Return to the previous page, or Go Home. Choose the destination in the editor. Page actions are local navigation, and cannot combine with Hold Toggle, Turbo, Auto-Tap, Button Aim, or directional Button behavior. A page change cleans up active output; arrange navigation so every playing page has an easy return route. Reopening a profile starts on Home.

Use **Edit in Landscape / Edit in Portrait** to edit without physically rotating the phone. Each page keeps separate positions and sizes for the two orientations. **Play lock: Portrait / Landscape** chooses the orientation applied when you tap Done. Orientation changes release active output. The first switch of an older profile creates a fitted arrangement for its missing orientation.

### Export, backup, and import

To export one saved profile, choose **Edit → Load**, long-press its name, then **Export to file**. Choose a location outside the app and wait for **Export verified**.

For a complete backup, choose **Edit → Load → Export all profiles** and wait for **All-profile export verified**. This includes saved controller profiles and pages, TouchAim calibration/manual profiles, Button Aim and stick profiles, boost profiles, and full floating-ring presets. Network, player, transport, and theme preferences are separate and are not included.

Use **Load → Import profile file** to select a `.simplecontroller-profile.json` or `.simplecontroller-profiles-backup.json` file. A single-profile import offers a unique name and **Import and load**. A full backup offers **Import all**, adds independent copies with unique names, and keeps the currently loaded profile in place. Older single-page layout JSON can also be imported.

Keep a verified external backup before changing installations. If Android reports a signature mismatch, preserve the existing app and its profiles while resolving it; uninstalling removes private app data.

## Payload syntax

A payload says what a Button should do. Use the spellings below; uppercase special commands avoid case-handling differences. Ordinary Buttons handle their matching release when you lift. You usually do not need to type `_HOLD`, `_RELEASE`, or a separate release command.

| Action | Button payload |
| --- | --- |
| Keyboard key | `W`, `SPACE`, `ENTER`, `SHIFT`, `CTRL`, `F1` |
| Xbox face button | `X360A`, `X360B`, `X360X`, `X360Y` |
| Shoulder button | `X360LB`, `X360RB` |
| Back / Start | `X360BACK`, `X360START` |
| Stick click | `X360LS`, `X360RS` |
| D-pad | `X360UP`, `X360DOWN`, `X360LEFT`, `X360RIGHT` |
| Analog trigger | `LT:1.0`, `RT:0.5` |
| Left / right mouse button | `MOUSE_LEFT_DOWN`, `MOUSE_RIGHT_DOWN` |
| Fixed stick direction | `LS:R50`, `LS:UR100`, `RS:L75` |
| Stop active input | `RELEASE_ALL` |
| Toggle Touchpad scrolling | `SCROLL_MODE_TOGGLE` |
| Toggle / enable / disable Camera Follow | `CAMERA_FOLLOW`, `CAMERA_FOLLOW:1`, `CAMERA_FOLLOW:0` |

Trigger values run from `0.0` to `1.0`: zero releases, `0.5` is half pull, and `1.0` is full pull. Use `LT:` and `RT:`, without an `X360` prefix.

Fixed-direction Button macros use `LS:<direction><percent>` or `RS:<direction><percent>`. Directions are `R`, `L`, `U`, `D`, `UR`, `UL`, `BR`, and `BL`; `BR` and `BL` mean down-right and down-left. The optional percentage defaults to 100. These are different from an ordinary Stick control's payload selector: use `STICK` for its left stick or `STICK_R` for its right stick.

Separate combined actions with commas:

```text
SHIFT,W
X360RB,X360A
LS:R50,X360RB,X360A
LT:1.0,RT:1.0
```

Combinations are not guaranteed timed scripts. Do not use `WAIT:` or `DELAY:` commands. Although the receiver recognizes `WAIT_150`, Android normally sends split commands separately, so a Button payload does not guarantee an ordered press-wait-press sequence. Do not put mouse DOWN and UP together on a normal hold Button. Keep Release All, Camera Follow, and Scroll Mode toggles on dedicated Buttons.

## Holding, repeating, and sliding

**Hold Toggle** keeps one Button's action held after lift. Enable it in the Button's properties and set its hold time. Hold until the threshold and vibration, then lift; tap again to release. A short touch ending before the threshold does nothing. The default threshold is 500 ms; adjust it to your comfortable timing.

**Turbo** repeats compatible ordinary Buttons while your finger remains down. Its interval is in milliseconds. **Toggle Auto-Tap** is a per-Button alternative: tap once to start repeating and tap again to stop. It cannot combine with Hold Toggle, Button Aim, or directional Button inputs. Avoid stacking repeat methods on one control.

For a timed trigger pulse, `RT:1.0P0.3` requests a full right-trigger press for 0.3 seconds. On an ordinary nonlatched Button it is a finite pulse; lifting can release it earlier. With Hold Toggle, the duration becomes the whole repeating cycle, divided between pressed and released. Keep Pulse separate from Turbo and Auto-Tap.

**Swipe** lets a continuous touch transfer between compatible controls. Start on a control and slide across its neighbors. Button Aim and directional Buttons keep their own captured gestures. Disable Swipe when a stick gesture needs to travel beyond its visible boundary.

## Movement and aiming controls

### Sticks and Stick+

An ordinary **Stick** produces analog movement. **Auto-center** returns it to neutral on lift; disabling Auto-center retains the last position. Add a **Re-center Button** when using retention. Re-centering stick movement does not replace Release All for latched buttons or triggers.

**Response Curve Stick** shapes the axes; **Radial Response Curve Stick** shapes distance from center while preserving direction. Choose the response that suits your travel and game settings.

**Directional/WASD mode** assigns direction commands such as `W`, `A`, `S`, and `D`. **Stick+** retains analog movement and adds actions at configured direction/distance thresholds. For example, use `X360LS` for sprint at Regular Boost and `X360A` for jump at Super Boost. Thresholds and commands are yours to choose.

Extended Boost settings can replace shared thresholds with per-direction values or add an Outer stage. Distances above `1.00` require travel outside the stick; leave enough reachable screen space. Save reusable stick profiles to copy commands/thresholds, with an explicit choice whether to switch the target's mode.

### Touchpad and Button Aim

A **TouchPad** moves the mouse relatively. Its click options include holding left click while touching, a toggle lock, and a double-tap click-lock gesture. Use one click behavior at a time. A dedicated `SCROLL_MODE_TOGGLE` Button switches Touchpads between pointer movement and vertical scrolling.

On a normal Button, enable **Aim while pressed** to aim or steer while handling its payload. Choose Mouse, Game Mouse, right stick, left stick, or D-pad output. Immediate timing activates the payload on touch; Send on release uses the completed gesture, with optional delay and boundary cancellation/re-arming. The gesture can continue beyond the Button's edge.

Tune sensitivity, deadzone, displacement, inversion, and stick centering as appropriate. Base and One-Shot Alternate have separate aiming/action settings. Start with Base before adding Alternate behavior. Saved Button Aim profiles offer aiming-feel-only or complete application; these choices intentionally copy different settings.

### Touch Aim and Camera Follow

**Touch Aim** combines movement with actions selected by finger-contact geometry. It offers contact-score thresholds and a calibrated two-state Aim/Shoot workflow. Contact Size, Major, and Minor are not dependable force measurements: phones and finger angles differ. Use Touch Sensor Test and calibration to see whether the stages are distinguishable for you. Keep Release All available while tuning this experimental input.

**Camera Follow** generates horizontal right-stick assistance from eligible left-stick movement through the Windows receiver. A dedicated Camera Follow Button can set strength. Movement controls can opt out with **Exclude from Camera Follow**; manual right-stick input takes priority. Optional mouse-engagement suppression is available in Floating / Camera Follow options. Game settings still affect the resulting camera motion.

## Floating rings, Buttons, and groups

Floating controls let a saved target appear under an intentional touch in an activation zone, reducing travel to a fixed control. They require the matching Windows receiver's scoped-output support; ConsoleBridge and older receivers do not provide this feature.

In Edit, choose **+ → Floating ring → New**. Configure its activation rectangle, starting role, enabled layers, movement, aiming knobs, firing, and Boost stages. **+ → Manage floating controls** lists zones by name, including zones hidden beneath other controls. Drag an outline to move it, or open its settings for position/size sliders and exact fields. Changing the activation rectangle does not resize the ring or group members.

New rings have movement and aiming enabled, boosts disabled, and Fire mode None. Up to three aiming knobs can have independent ADS/fire bindings and None, Hold, Tap, Release-shot, or Repeat behavior. Boost 1/2 add direction-dependent actions; return-fire and fine sensitivity are optional. Movement stays movement during its captured gesture; lift and intentionally touch an aim knob to begin aiming. Direct Boost 2 initially starts only its own stage. Enable **Activate Boost 1 when returning from direct Boost 2** if an inward return should also activate Boost 1.

In Play, touch unused space inside the invisible activation zone. Existing visible controls and app UI take priority. If connection preparation is still running, keep that finger down: the app waits up to 1.5 seconds and, when ready, activates at its latest position. Lifting first cancels the waiting contact. A failed or timed-out connection can require another touch.

The revealed instance remains after lift. Use its **Finish** target, or hold **Edit** in Play to choose it from the Finish list. Finish ends that instance's owned actions and timers and hides it without deleting its saved configuration. Other instances can remain active. Release All is the broader cleanup action.

To convert existing Buttons, choose **+ → Floating Button / group from selection**, draw a rectangle, and lift. Adjust the preview checkboxes and configure an initial Button or Reveal only. Android Back cancels selection even before drawing. Conversion offers Save a copy to preserve the previous saved profile. Ungroup restores original member positions while retaining their latest behavior settings.

To reuse a complete ring, open its zone settings and **Save full ring / activation zone preset**. In another profile choose **+ → Floating ring → Saved Presets → Add a new ring to this profile**. Review and Save the zone, then save the controller profile. Full presets include both saved orientation rectangles and ring configuration; grouped Buttons are excluded. Boost-only profiles are narrower and preserve the target's movement and aiming feel.

## Optional facial input

[Download FacialInput.exe](https://github.com/Colonelwheel/Simplecontroller/releases/download/facial-input-windows-2026-10-16/FacialInput.exe) for Windows 10/11 x64 to add expression buttons and optional face movement through the same receiver. The standalone app includes Python 3.12, MediaPipe, OpenCV, the Qt GUI, and its verified Face Landmarker model. After download, launch is offline: no separate Python installation, pip, model download, or administrator access is required. Install ViGEmBus separately and keep one matching SimpleController receiver running; the facial app creates no additional virtual controller.

1. Open `FacialInput.exe`; it starts **paused**.
2. Choose **Standalone webcam**, select your camera, then **Apply camera / retry**. A compatible local shared-camera publisher is an optional alternative. Select the face region with two separate corner taps if needed.
3. Use **Calibrate** to collect neutral samples and comfortable expressions. Review thresholds, then use **Test (no output)** to check deliberate actions and ordinary speech/blinking without sending game input.
4. Choose a binding, Tap or Hold, and Player. Select **Start** when ready; fresh tracking, receiver readiness, and neutral checks still apply. **Pause** stops facial output.
5. Use **Save Settings** to keep your changes. Existing settings and saved profiles stay under `%LOCALAPPDATA%\FacialGamingInput`; updating the EXE does not require deleting them.

Processing stays local; camera images are not recorded or uploaded. Closing the window quits; **Minimize to tray** leaves it running. Optional movement, wink and boost settings need deliberate setup. Phone and face inputs can overlap, so another held source may mask a facial tap's release edge. Tune and test a simple binding first.

The [facial-input guide](facial-input/README.md) covers source installation, developer packaging, synthetic package checks, and troubleshooting. Package checks do not establish webcam recognition, comfort, or game compatibility.

## Troubleshooting

- **Nothing reaches the PC:** check that exactly one receiver is running, the displayed IP is correct, the phone uses UDP `42734`, and Firewall permits this receiver. Confirm the devices share a reachable network rather than an isolated guest network.
- **Connected, but no game input:** test `W` in Notepad or `X360A` in a controller tester. Focus the game, check its input mode, and try starting it after the receiver. Some games restrict simulated input; successful desktop input does not establish game compatibility.
- **Receiver exits or no Xbox controller appears:** check the ViGEmBus installation and restart if its installer requests it. Starting the EXE alone does not install the driver.
- **Held or repeating input remains:** use Release All. Check Hold Toggle, Auto-Tap, retained stick output, and overlapping repeat settings before changing the layout.
- **Floating control does not appear:** leave ConsoleBridge unchecked, use the matching receiver, enable its zone/layers, and touch free zone space. Check that the ring fits on screen and its saved starting role is enabled.
- **Aim drifts or feels too fast:** reduce sensitivity, adjust deadzone/displacement, and check Auto-center or retained movement. Tune one setting at a time in the intended application.
- **Installation/update problem:** preserve the old app and export verified profiles before any uninstall. Keep your previous APK and backup while resolving signing or compatibility issues.

Receiver logs are under `%LOCALAPPDATA%\SimpleControllerReceiver\touchpad_logs`. When reporting an issue, include app/receiver versions, connection type, a minimal payload example, and what happened. Separate controller-tester results from game behavior.

Public Android and receiver source is under [source/android](source/android/) and [source/receiver](source/receiver/). The [VoiceAttack guide](voiceattack/USER_GUIDE.md) covers optional voice commands through the receiver. Compilation and automated checks do not establish touch comfort, camera behavior, or compatibility with every game.
