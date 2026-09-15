# SimpleController User Guide

> **Guide version:** September 14, 2026
>
> **Applies to:** the current TouchAim calibration feature build on `codex/receiver-build-folder-backup`
>
> **Platform:** Android 7.0 or newer and Windows 10/11

## What SimpleController is

Because of my disability, SMA, I have not been able to play games with a normal controller for quite a while. I built SimpleController to solve that problem: it turns an Android phone into a completely customizable controller for PC games.

The basic path looks like this:

**Android phone → local Wi-Fi or USB tethering → Windows receiver → keyboard, mouse, or virtual Xbox controller input**

I designed the app around what I can operate with one finger, and I have personally used it for hundreds of hours. You can fill a layout with buttons, sticks, touchpads, and accessibility-focused controls, then decide exactly what each one does and where it sits on the screen.

SimpleController is free and open source. It is still an evolving personal project rather than a polished store product, but it is already genuinely useful. My favorite parts are the features that turn one movement into several context-sensitive actions—especially Stick+, which is what lets me run, jump, and handle many platforming games with one finger.

## Who it may help

SimpleController may be useful if you:

- cannot comfortably use a standard gamepad;
- primarily use one finger or have limited reach, strength, or movement;
- want large controls positioned around your own range of motion;
- need actions to stay held without keeping your finger down;
- want one control to perform several keyboard, mouse, or Xbox actions;
- want to use a phone as an extra controller for a PC game.

Everyone's access needs are different. The app is meant to be rearranged and experimented with until the controls fit the person—not the other way around.

## Important release note before downloading

The older APK and setup program on the repository's default `master` page do **not** contain the complete feature set described in this guide.

For this version, the matching files are:

- Android app: [`Simplecontroller (New feature).apk`](<Simplecontroller (New feature).apk>)
- Windows receiver: [`Simple Controller Receiver (Release All).exe`](<Edit exe Folder/Simple Controller Receiver (Release All).exe>)
- Virtual-controller driver: [`ViGEmBus_1.22.0_x64_x86_arm64.exe`](ViGEmBus_1.22.0_x64_x86_arm64.exe)

Use a matching APK and receiver pair from the exact official release or link I provide. Mixing an older receiver with a newer app can make commands appear broken even when the layout is correct.

The current APK is a development build, and the Windows receiver is not digitally signed, so Android, Windows, or SmartScreen may display an unfamiliar-app warning. Public source code makes the project independently reviewable, but it is not a blanket guarantee of safety. Download it only from the official repository.

> **Protect your profiles:** If Android reports an update or signature problem, do not immediately uninstall the older app. Uninstalling removes the profiles stored inside it. Controller Pages can import another profile already saved inside the app, but external file export/import is not available yet.

## Quick start

1. Install ViGEmBus on the Windows PC. This creates the virtual Xbox controller that games can recognize. Restart Windows if its installer asks you to.
2. Install the matching SimpleController APK on the Android phone or tablet.
3. Run the matching Simple Controller Receiver on the PC. Keep its window open while using the app.
4. The receiver displays its IP address and port. The current receiver uses port **42734**.
5. Put the phone and PC on the same trusted home network. In SimpleController, tap **Connect**, leave **Use ConsoleBridge (CBv0)** unchecked, enter the displayed IP address and `42734`, choose Player 1, and connect.
6. Test one simple button such as `X360A` before building a full layout.

The packaged receiver is standalone, so ordinary users do not need to install Python separately.

The app may display **Connected** as soon as it starts sending UDP packets; that label is not a confirmed handshake. The real test is whether the receiver shows activity and the input works in a game, a controller tester, or Notepad for keyboard keys.

### USB tethering instead of Wi-Fi

USB tethering can give you a direct phone-to-PC network when normal Wi-Fi is inconvenient:

1. Connect the phone to the PC with USB.
2. Start the current Simple Controller Receiver.
3. In the app, tap **Connect**, then **USB Tether Mode**.
4. Enable Android's USB tethering when its settings screen opens.
5. Return to SimpleController and allow up to about 12 seconds for discovery.

USB discovery does not overwrite the Wi-Fi address you saved. This path is implemented and packaged, but it still needs broader real-device testing, so keep Wi-Fi available as a fallback.

### Network safety

The receiver accepts controller commands over the local network without a password. Use it only on a trusted private network or direct USB-tether network.

- Allow the receiver through Windows Firewall for **Private** networks when prompted.
- Do not disable the firewall.
- Do not forward UDP port `42734` through your router.
- If Windows classifies USB tethering as Public, use a narrowly scoped rule for this receiver/port rather than allowing broad Public-network access.

## Creating and editing a layout

A saved **profile** is the complete controller setup. Older versions called these saved profiles
**layouts**, so the Save/Load screens and filenames may still use that word.

1. Tap **Edit** in the upper-right corner. It changes to **Done**.
2. If you want to begin from an empty page, tap **Load**, then **New Layout**.
3. Tap **+** at the bottom of the screen.
4. Choose a control: Button, Stick, Response Curve Stick, TouchPad, Touch Aim, Re-center Button, or a preset layout.
5. A new control opens its properties automatically. To edit an existing control, long-press it while Edit mode is active.
6. Drag a control to move it. Use its width and height settings to resize it.
7. Set its label, payload, colors, and behavior.
8. Tap **Save** to name or update the layout, then **Done** to return to play mode.

Tap **Load** to switch layouts. Long-press a saved layout to rename, duplicate, or delete it. The app normally saves the current layout when it pauses, but pressing **Save** yourself is the safest habit after meaningful changes.

## Controller Pages inside a profile

One saved profile can now contain several complete controller pages, such as **Base**,
**Alternate**, **Driving**, or **Menus**. A page is not a small overlay: it owns a complete list of
Buttons, Sticks, Touchpads, TouchAim surfaces, positions, colors, payloads, and advanced settings.

To create and edit Page 2:

1. Tap **Edit**.
2. Tap **Editing page: Base ▾**.
3. Choose **Duplicate current page** for the safest start, name it, and edit the duplicate.
4. Or choose **Add blank page** or **Import saved profile as page**. The app offers to copy your
   existing page-navigation Buttons at their current positions; this option starts enabled.
5. Long-press controls on Page 2 and edit them normally. Changes affect only Page 2.
6. Use the same page manager to rename a page or set it as Home. Page names are unique regardless
   of capitalization or extra spaces.
7. Tap **Save** to save every page in the profile, then tap **Done**.

Duplicate and import create independent deep copies. Later edits to Base do not change Alternate,
and editing an imported page does not change or depend on its source profile.

### Page-switch Buttons

Edit an ordinary Button and use **Local page action** instead of typing a page command manually:

- **Go to page** switches directly to the selected page. Choosing the active page does nothing.
- **Toggle page** switches to its target; when pressed on that target page, it returns to the page
  most recently left. This lets a copied Toggle Button move from Base to Alternate and back.
- **Return to previous page** returns to the most recently left valid page, or Home if none exists.
- **Go to Home page** switches to the profile's designated Home page.

Target Buttons store the page's stable ID while showing its current name. Renaming a page therefore
does not break its Buttons. If a target was deleted, the Button is visibly marked **Missing page**
and safely does nothing. Deleting a page reports how many controls reference it. You cannot delete
the only page or delete Home until another page becomes Home.

Page actions run only inside Android; they are never sent to the Windows receiver or Pico. They
activate once per intentional press and cannot use Hold Toggle, Turbo, Auto-Tap, Button Aim, or
delayed/on-release behavior.

### Safety and startup behavior

Before a successful Play Mode page change, SimpleController releases held/latched buttons and
triggers, centers sticks, releases keyboard and mouse holds, cancels Turbo/Auto-Tap/delayed work,
and stops Button Aim, TouchAim, Swipe ownership, and one-shot Alternate state. The network
connection, selected player, and transport settings remain active. The new page name appears
briefly after the switch.

Loading or reopening a profile starts on its Home page, normally Base. The temporary active and
previous page are not saved. Release All clears every active output while keeping the current page.
Edit Mode, deliberate disconnect, and leaving the app return the runtime session to Home. Existing
one-page profiles load automatically as a Base page with every existing control and setting
preserved; using the normal save path writes the new multipage format.

The app warns before Play Mode if a non-Home page has no usable Return, Home, or Toggle action.
Edit remains an emergency route, but it is not intended to replace a reachable page-navigation
Button. A possible future `PAGE_ONCE` action may return after one completed action; it is not part
of this version.

## What a payload is

A **payload** is the instruction a control sends. It answers the question, “What should happen when I touch this?”

For example:

- `W` presses the W key.
- `X360A` presses the Xbox A button.
- `MOUSE_RIGHT_DOWN` presses and holds the right mouse button until you lift your finger.
- `RT:0.5` pulls the right trigger halfway.
- `LS:R50` pushes the left stick 50% to the right.

Use uppercase for special commands. It is easier to read and avoids inconsistent case handling.

### Multiple actions on one Button

Separate actions with commas and no spaces:

```text
X360RB,X360A
SHIFT,W
LS:R50,X360RB,X360A
LT:1.0,RT:1.0
```

These are intended as near-simultaneous combinations. They are sent as separate network commands, so do not depend on perfect atomic timing or a guaranteed order.

### Delay commands are experimental

The receiver recognizes the form `WAIT_150`, where the number is milliseconds, but the Android app currently separates and sends each command independently. A payload such as `X360A,WAIT_150,X360B` is therefore not guaranteed to arrive or execute in that order.

For now, treat multi-action payloads as combinations—not timed scripts. Do not use `WAIT:150`, `DELAY:150`, or `DELAY_150`; those spellings are unsupported.

## Ordinary Buttons

Add a **Button**, give it a visible name, and put the action in **Payload**. In the normal mode, the action begins when your finger goes down and releases when your finger lifts.

You normally type `X360A`, `W`, or `MOUSE_LEFT_DOWN`. Do not add internal endings such as `_HOLD` or `_RELEASE`; the app handles those for you.

### Hold Toggle

Hold Toggle lets an action stay active after you remove your finger.

1. Edit a Button and enable **Hold Toggle**.
2. Set the hold time in milliseconds. The default is 500 ms.
3. In play mode, hold the Button until the phone vibrates.
4. Lift your finger. The action remains held.
5. Tap the Button again to release it.

A short tap that ends before the threshold does nothing. A very low threshold such as 50 ms makes latching quick, but it also makes accidental activation easier. Start around 300–500 ms and lower it only if that feels unnecessarily slow.

This per-Button option is the dependable way to create a latch. The separate global **Hold** switch should be treated as experimental until it has had more real-device testing.

## Trigger pressure and Pulse payloads

Analog trigger values run from `0.0` to `1.0`:

```text
LT:0.0   left trigger released
LT:0.5   left trigger halfway
LT:1.0   left trigger fully pressed

RT:0.0   right trigger released
RT:0.5   right trigger halfway
RT:1.0   right trigger fully pressed
```

Use `LT:` and `RT:` exactly. `X360LT:` and `X360RT:` are not valid in the current app-to-receiver path.

### What Pulse does

A Pulse payload presses an analog trigger for a specific amount of time and then releases it automatically:

```text
RT:1.0P0.3
```

That breaks down as:

- `RT` — use the right trigger;
- `1.0` — press it fully;
- `P` — use Pulse behavior;
- `0.3` — pulse time in seconds.

On an ordinary Button without Hold Toggle, `RT:1.0P0.3` presses the right trigger fully for 0.3 seconds, then automatically sends `RT:0.0`.

### Repeating Pulse with Hold Toggle

Pulse becomes a repeating trigger when it is combined with **Hold Toggle**. In this mode, the number after `P` is the entire cycle, divided evenly between pressed and released.

For example, a latched:

```text
RT:1.0P0.6
```

repeats approximately like this:

```text
300 ms pressed → 300 ms released → repeat
```

A smaller number pulses faster; a larger number pulses slower. Tap the latched Button again to stop it. A dedicated `RELEASE_ALL` Button will stop it too.

For the current version, the safest Pulse payloads use full pressure and sit alone on their Button:

```text
RT:1.0P0.3
LT:1.0P0.5
```

Important Pulse limits:

- Do not combine Pulse with global Turbo or Toggle Auto-Tap. Each has its own timer, and the timers can conflict.
- Do not use Pulse inside Touch Aim stage payloads; those stages do not interpret the `P` suffix as Pulse.
- Partial-pressure pulses such as `RT:0.5P0.3` can be cut short when your finger lifts. Use `1.0` for dependable release handling in this build.
- Keep Pulse by itself. Mixing it with another held action can make that other action remain owned longer than intended.
- A delayed Button Aim or Auto-Tap interval can end a long Pulse before the Pulse timer finishes.

## Three different ways to repeat or hold an action

These features sound similar, but they solve different problems.

| Feature | How it starts | How it stops | Best use |
|---|---|---|---|
| **Hold Toggle** | Hold until the threshold and vibration | Tap again | Keep sprint, aim, a direction, or another steady input held |
| **Global Turbo** | Turn Turbo on, then keep a Button physically touched | Lift your finger | Repeat ordinary buttons only while touching them |
| **Toggle Auto-Tap** | Tap a configured Button once | Tap it again | Start a persistent, evenly timed repeater without keeping your finger down |

### Global Turbo

The Turbo value is the interval between repeats, in milliseconds. The current default is 16 ms, and the allowed range is 10–1000 ms. It affects ordinary Buttons globally while Turbo is switched on.

Turbo is best for straightforward Xbox or keyboard actions. Avoid using it with Pulse, analog trigger holds, mouse-down commands, Camera Follow, Scroll Mode, or Release All.

### Toggle Auto-Tap

Toggle Auto-Tap belongs to one Button instead of affecting the whole layout:

1. Edit a Button.
2. Enable **Toggle Auto-Tap**.
3. Choose the interval in milliseconds. The minimum is 16 ms.
4. In play mode, tap once to start and tap again to stop.

Each repeated press lasts for half the interval, up to 50 ms:

- 100 ms interval: about 50 ms down and 50 ms up;
- 40 ms interval: about 20 ms down and 20 ms up;
- 16 ms interval: about 8 ms down and 8 ms up.

Auto-Tap supports Xbox buttons, keyboard keys, mouse buttons, triggers, fixed-direction stick macros, and combinations. It cannot be combined with Hold Toggle or Button Aim. It deliberately refuses `RELEASE_ALL`, `CAMERA_FOLLOW`, and `SCROLL_MODE_TOGGLE`.

Auto-Tap also stops automatically when you use Release All, enter Edit mode, remove or reconfigure the control, switch layouts, disconnect, or leave the app.

## Sticks

This is the part of SimpleController I am proudest of, because its extra modes make many actions possible for me with one finger.

### Basic analog Stick

Add a **Stick**. Leave its payload as `STICK` for the left stick, or use `STICK_R` for the right stick.

- **Auto-center on:** the stick returns to neutral when your finger lifts.
- **Auto-center off:** the stick stays where you left it. In a game, that can let a character keep walking without continuous pressure.

Add a **Re-center Button** when Auto-center is off. Touching it immediately returns non-auto-centering sticks to neutral.

### Directional/WASD mode

Directional mode lets regions of the Stick send commands such as `W`, `A`, `S`, and `D`, which is useful for games without controller support. You can configure the command assigned to each direction and its thresholds.

The current mode may still send analog coordinates alongside the directional commands. Test it in the intended game rather than assuming it is a strictly keyboard-only stick.

### Stick+

Stick+ keeps the normal analog stick movement and adds actions depending on the direction and how far you push. This is what lets me place walking, sprinting, and jumping on one continuous movement.

One practical forward-direction setup is:

- 0% to about 49%: normal analog movement;
- about 50% to 95%: send `X360LS` to sprint;
- above 95%: send `X360A` to jump.

The exact thresholds and payloads are yours to choose. You could use the same idea for crouching, dodging, attacks, modifiers, camera actions, or keyboard commands in different directions.

After first enabling Directional mode or Stick+, you may need to press **OK/Save**, then long-press the Stick again in Edit mode before every nested setting becomes visible.

### Stick directional profiles

Directional/WASD and Stick+ use the same Up, Down, Left, Right, Regular Boost, and Super Boost
command fields. Use **Save changes as new stick profile** to save all 12 commands, both thresholds,
and whether the profile was created in Directional/WASD or Stick+ mode.

When loading a profile, choose **Apply commands only** to copy the commands and thresholds while
keeping the target stick's current mode. Choose **Apply and switch** to copy them and select the
profile's saved WASD or Stick+ mode. Both choices preserve the target stick's position, size, LS/RS
payload, sensitivity, auto-center setting, and normal/Response Curve stick type. Profiles work
between normal Stick and Response Curve Stick controls and can be renamed or deleted.

### Response Curve Stick

A Response Curve Stick changes how quickly analog output grows as your finger moves away from the center:

- around `0.5`: finer control near the center;
- `1.0`: linear response;
- around `2.0`: stronger center response;
- `0`: curve processing disabled.

The full outer travel still reaches 100%. This can make aiming or steering less twitchy without sacrificing full movement.

### Fixed-direction Stick macros on Buttons

A normal Button can also hold a stick in one direction:

```text
LS:R50
LS:UR100
RS:L75
RS:BR100
```

The format is `LS:<direction><percentage>` or `RS:<direction><percentage>`. The percentage is optional and defaults to 100.

Valid directions are `R`, `L`, `U`, `D`, `UR`, `UL`, `BR`, and `BL`. Full words such as `RIGHT` also work for the four straight directions.

The stick centers when the Button is released. Avoid holding two Button macros for the same stick at once, because releasing either Button centers that stick even if the other is still touched.

Do not type raw coordinates such as `STICK_L:0.0,-1.0` into a Button. The comma is treated as a payload separator.

## Touchpad

Add a **TouchPad** for relative mouse movement and choose its sensitivity.

The three click behaviors are:

1. **Hold left while finger is down** — touching the pad holds left click; lifting releases it.
2. **Toggle left click mode** — a tap toggles click-lock on or off. The lock begins after a short delay.
3. **Double-tap click-lock** — a clean single tap behaves like a normal click. To lock, tap and then quickly touch again and hold for about 0.4 seconds. Tap again to unlock.

Only one click behavior should be active at a time.

For a dedicated right-click Button, use:

```text
MOUSE_RIGHT_DOWN
```

The app sends the matching release when your finger lifts. Do not put both `_DOWN` and `_UP` in the same ordinary Button payload.

For scrolling, create a dedicated Button with exactly:

```text
SCROLL_MODE_TOGGLE
```

Tap it once to make all Touchpads scroll vertically. Tap it again to return to pointer movement. Do not combine this toggle with other payloads, Hold, Turbo, or Auto-Tap.

## Button Aim

Button Aim lets the same finger both touch a Button and move outside it to aim or steer.

1. Add or edit a normal Button.
2. Enable **Aim while pressed**.
3. Choose Mouse, right stick, or left stick output.
4. Choose **Immediate** to activate the Button payload as soon as the touch starts, or **Send on release** to decide based on the completed gesture.
5. Adjust sensitivity, dead zone, response curve, and optional send-on-release delay.

Once the gesture begins, you can slide beyond the Button's visible edge without losing it. Button Aim suppresses Swipe behavior so that a moving aim finger does not accidentally activate neighboring controls.

Button Aim is mutually exclusive with Hold Toggle and Toggle Auto-Tap.

### Button Aim profiles

Use **Save changes as new Button Aim profile** to keep a named copy of the complete setup. A profile
stores the Base and Alternate aiming feel plus the Base payload, Hold behavior, payload timing, and
all one-shot Alternate payload/timing settings. It does not store the button's name, size, position,
or swipe setting.

When loading a profile, choose **Apply aiming feel only** to reuse the output, sensitivity,
Linear/Response Curve choice, displacement, deadzone, touch-position origin, inversion, and haptics
without changing the target button's actions. This is useful for giving Fire and ADS islands the
same feel. Choose **Apply complete profile** to restore the saved payloads and action behavior too.
Either choice enables Button Aim and turns off conflicting Auto-Tap. A size warning appears when
the target button is substantially different from the source because touch-position aiming can
then feel different.

## One-Shot Alternate actions

Inside Button Aim, **One-Shot Alternate** gives the same control a Base payload and an Alternate payload.

1. Enable Button Aim and One-Shot Alternate.
2. Enter the Alternate payload.
3. Complete a Base gesture. This arms Alternate mode.
4. Later gestures use the Alternate action.
5. To return to Base, hold the alternate gesture for the configured return time—2 seconds by default—and release.

Despite the current name, short Alternate gestures do not automatically return to Base; they remain in Alternate mode. The long return gesture also performs the Alternate action before the mode resets.

### Touch-position Stick origin

Button Aim's stick mode can interpret the first touch in two ways:

- **Off:** the initial touch becomes neutral, and movement is relative to where your finger started.
- **On:** the center of the Button is neutral, so touching near an edge or corner immediately produces movement in that direction.

Base and Alternate modes can use different origin settings.

### Base unlatch delay

When a Base action is latched and you return from Alternate mode, **Base unlatch delay** keeps the Base action held for a chosen number of extra milliseconds after the Alternate release. Set it to `0` for immediate release.

This is useful when a game needs the Base modifier to overlap the end of the Alternate action instead of disappearing at exactly the same instant.

## Touch Aim

Touch Aim is an experimental one-finger aiming control. It can move the mouse, right stick, or left stick while also changing actions according to the finger-contact shape reported by Android.

1. Add **Touch Aim**.
2. Choose Mouse, RS, or LS movement.
3. Choose the sensor score: contact **Size**, **Major**, or **Minor**.
4. Set Low, Medium, and High thresholds and payloads.
5. Choose **Press** for a brief action or **Hold** to keep the stage active until your contact changes or lifts.
6. Optionally keep lower Hold stages active as the score rises.

One possible idea is normal aim at Low, `LT:1.0` aim-down-sights at Medium, and `RT:1.0` fire at High. That is only an example; finger size readings differ greatly by phone, screen protector, finger angle, and movement.

The Galaxy S22 does not provide useful pressure values for this purpose, so Touch Aim uses touch geometry rather than pressure. Geometry still overlaps between stages and can shift while the finger moves. Treat this as an experimental feature, tune it slowly, and keep a Release All Button available while testing.

### TouchAim stick origin

For Right-stick or Left-stick output, **Use touch position as stick position** selects how a gesture
begins:

- **On (existing behavior):** the center of the TouchAim surface is neutral. Touching away from its
  center can immediately produce stick output.
- **Off:** wherever the finger first lands becomes neutral. Movement is relative to that initial
  touch, like Button Aim with touch-position mode off.

With the setting off, **Full displacement** is the finger travel needed for 100% stick output and
**Deadzone** is the travel ignored around the initial touch. Raise displacement for finer control;
lower it if full-speed turning requires too much travel. Raise deadzone to suppress drift or jitter;
lower it if aiming feels sticky. These controls are hidden for Mouse output because mouse aiming is
already relative. Existing and older layouts default to the original surface-center behavior.

### Calibrated two-state Aim / Shoot mode

Edit a Touch Aim control and tap **Calibrate TouchAim** to calibrate only these two intended states:

1. **AIM:** touch and move the way you naturally aim.
2. **SHOOT:** keep aiming while using your intended shooting finger posture.

The wizard records each state twice. Every recording starts only after you press **Start**, uses an
adjustable preparation countdown (10 seconds by default), and records for 9 seconds by default.
Begin with a steady natural touch. Halfway through, a visual and vibration cue asks you to move
naturally. This moving portion is important because Size, TouchMajor, and TouchMinor can drop while
your finger moves.

Calibration and live validation use the outlined Touch Aim area at its actual screen position. No
gamepad, mouse, or keyboard payload is sent while the wizard is open. After each pass, choose
**Retake**, **Continue**, or **Cancel**. The result evaluates Size, TouchMajor, TouchMinor, and useful
normalized combinations using the repeated passes separately. It reports Good, Borderline, or
Unreliable, estimated false and missed activations, and whether movement reduced reliability.
The moving half must include detected finger travel; an interrupted pass or a pass without enough
actual movement must be retaken. The detector is checked with the same smoothing, hysteresis, and
confirmation timing used during normal TouchAim operation.

If the analyzer finds a consistent sensor direction but still rates the result **Unreliable**, it
shows its experimental best-guess thresholds. You may complete live validation, explicitly apply
that best guess, and then edit every value in Touch Aim properties. This is a starting point, not a
claim that the calibration is reliable. If no meaningful sensor direction exists at all, the wizard
still refuses to invent thresholds; retake it or use Manual two-state.

Two-state settings include:

- Mouse, Right stick, or Left stick aim output;
- an optional Aim-state payload such as `LT:1.0` or `MOUSE_RIGHT_DOWN`;
- a configurable Shoot payload such as `RT:1.0` or `MOUSE_LEFT_DOWN`;
- whether the Aim payload stays held while shooting;
- normal Aim sensitivity and a separate **Shoot aim sensitivity** for finer control after Shoot is
  confirmed;
- surface-center or initial-touch stick origin, with displacement and deadzone for initial-touch;
- separate Shoot-on and Shoot-off thresholds, smoothing, and confirmation times.

The property sheet hides settings that do not belong to the selected mode. Manual three-stage shows
the raw sensor and Low/Medium/High sections; Manual two-state shows the raw sensor and AIM/SHOOT
sections; Calibrated two-state shows the normalized AIM/SHOOT section. All modes retain both stick
aiming styles: leave **Response curve** off for Linear, or turn it on for Response curve.

The gap between Shoot ON and Shoot OFF is **hysteresis**. For example, with ON `1.20` and OFF
`0.90`, Shoot starts above `1.20` but does not return to Aim until the score falls below `0.90`.
This prevents small fluctuations from rapidly firing and releasing. Enter and return confirmation
times are separate: they specify how many milliseconds the score must remain beyond the relevant
threshold before the state changes.

Aim/Shoot state payloads must be fully releasable. Xbox buttons, triggers, mouse buttons, keyboard
keys, and stick-direction macros are supported; one-way actions such as Release All, camera-follow
changes, scroll-mode toggles, and raw edge commands are rejected for these state payloads.

The three Shoot activation choices are:

- **Hold while above threshold:** hold Shoot while the contact remains in Shoot.
- **Press when entering Shoot:** send one finite press after entering Shoot is confirmed.
- **Press when returning to Aim:** entering Shoot only arms the action. Relax the same finger below
  Shoot-off and keep it there for the return confirmation time to send one finite press. Completely
  lifting the finger cancels the armed action and never fires it.

Use **Save changes, then manage calibrations** to save the open property form before applying,
renaming, duplicating, deleting, or re-running named
profiles such as “Bottom of screen thresholds” or “Reclined position.” Profiles record device,
screen, control-position, sensor-normalization, threshold, timing, reliability, and compact repeated
pass information. Applying a profile copies its settings into that one control; renaming or deleting
the saved profile does not silently change the working control.
When loading a profile, choose **Calibration only** to retain the control's current output,
stick origin, sensitivities, payloads, and Shoot behavior, or explicitly choose **Apply all saved
settings** to restore those saved values too.

The original manual Low/Medium/High three-stage mode remains available in the Touch Aim properties.
There is also an explicit **Manual two-state** mode. It averages the checked Size, TouchMajor, and
TouchMinor values (after the Size multiplier), then uses its own editable raw-score ON/OFF
thresholds plus the two-state smoothing, confirmation times, payloads, and separate Shoot
sensitivity. Its thresholds are stored separately from calibrated thresholds because the two score
scales are different. The wizard does not claim
to calibrate three states because it does not record a third intended posture.
On first use in an older layout, Manual two-state starts from that control's existing High threshold
and High-minus-hysteresis rather than imposing unrelated raw-score defaults.

### Manual TouchAim profiles

Manual three-stage and Manual two-state settings can be saved as reusable named profiles. A manual
profile copies the selected manual mode, sensor choices, thresholds, aiming behavior, and that
mode's stage or AIM/SHOOT actions. It does not copy the control's name, size, or position, and it
does not change any reusable calibration saved in the wizard's separate profile list. Applying one
switches only that TouchAim control to the saved manual mode and clears that control's old copied
calibration, preventing a stale detector from being restored accidentally. Profiles can be applied,
renamed, or deleted from the TouchAim property sheet.

### Touch Sensor Test

The APK installs a second launcher icon called **Touch Sensor Test**. It is an optional diagnostic tool, not a second controller app.

It walks one finger through six samples—normal, flattened, and pressed contact while still and moving—then lets you copy the measurements. It sends no controller output. The results can help choose Touch Aim thresholds for a specific phone and finger position.

## Camera Follow

Camera Follow uses left-stick movement to create delayed horizontal right-stick movement, helping the camera follow turns without a second finger.

Create a dedicated Button with:

```text
CAMERA_FOLLOW
```

Tap once to enable it and again to disable it. Manual right-stick input or an `RS:` Button macro takes priority while active.

Camera Follow works only through the Python-based Windows receiver. Leave ConsoleBridge off. Release All clears the current generated right-stick output, but the Camera Follow preference remains enabled and can resume with new left-stick movement.

## Release All safety Button

I strongly recommend putting a clearly visible emergency-release Button on every experimental layout.

1. Add a normal Button.
2. Give it a name such as **RELEASE ALL**.
3. Make its entire payload:

```text
RELEASE_ALL
```

Do not combine it with any other command.

Release All runs immediately when touched, ahead of Hold, Turbo, Button Aim, and other normal behaviors. It cancels app-owned timers, latches, Pulse, Auto-Tap, Button Aim, Touch Aim stages, stick output, keyboard/mouse holds, and trigger values. The Windows receiver then resets both virtual controllers and its shared keyboard/mouse state.

The PC receiver confirms the reset back to the app. ConsoleBridge/Pico does not yet have an atomic receiver-side release, so only the Android-side cleanup is confirmed on that path.

## Snap, Swipe, and the top controls

- **Snap** remains in the top controls for compatibility, but the current Stick release behavior is determined by that Stick's own **Auto-center** setting. Do not rely on the global Snap switch for recentering.
- **Turbo** repeats ordinary Buttons at the configured interval while your finger remains down.
- **Swipe** allows a touch to activate neighboring Buttons as it moves across them. Button Aim suppresses it during an aim gesture.
- **Hold** is a global latch mode, but the current ordinary-Button implementation needs more real-device verification. Prefer each Button's own Hold Toggle.

Swipe and the other global switches are present in the current code, but combinations with advanced controls have not all been tested. Build the first layout without them, then enable one behavior at a time.

## Two-player support

The Windows receiver creates two virtual Xbox controllers.

For two phones, connect both to the same PC address and port. Choose **Player 1** on one phone and **Player 2** on the other in the Connect dialog. Choosing a Player 1 or Player 2 preset layout does not set the network player role.

The code routes Xbox output to separate virtual controllers, but this still needs broader end-to-end testing across phones and local-co-op games. Keyboard and mouse input are shared Windows input, so the meaningful player separation is for Xbox payloads.

## Payload quick reference

### Xbox controller

```text
X360A        X360B        X360X        X360Y
X360LB       X360RB
X360START    X360BACK
X360UP       X360DOWN     X360LEFT     X360RIGHT
X360LS       X360RS
```

`X360LS` and `X360RS` mean clicking the left or right stick. A Guide/Home payload is not currently supported.

### Analog triggers

```text
LT:0.0 through LT:1.0
RT:0.0 through RT:1.0

LT:1.0P0.3
RT:1.0P0.3
```

### Mouse and Touchpad state

```text
MOUSE_LEFT_DOWN
MOUSE_RIGHT_DOWN
MOUSE_MIDDLE_DOWN
SCROLL_MODE_TOGGLE
```

The matching mouse `_UP` commands exist for recovery and specialized features, but an ordinary Button sends them automatically when you lift. Use only the `_DOWN` form in a normal Button.

### Keyboard

Type the ordinary key name directly:

```text
A through Z
0 through 9
SPACE        ENTER        ESC          TAB
BACKSPACE    SHIFT        CTRL         ALT
UP           DOWN         LEFT         RIGHT
HOME         END          PAGEUP       PAGEDOWN
INSERT       DELETE
F1 through F12
```

Do not type `KEY_DOWN:W` or `KEY_UP:W` into an ordinary Button; those are internal/specialized command forms.

### Fixed Stick direction

```text
LS:R50       LS:L50       LS:U100      LS:D100
LS:UR100     LS:UL100     LS:BR100     LS:BL100
RS:R50       RS:L50       RS:U100      RS:D100
RS:UR100     RS:UL100     RS:BR100     RS:BL100
```

### App and receiver controls

```text
CAMERA_FOLLOW
SCROLL_MODE_TOGGLE
RELEASE_ALL
```

Each should be the complete payload of its own dedicated Button.

## Troubleshooting

### Nothing responds

- Confirm that the matching receiver is still running.
- Check that the phone and PC are on the same trusted network.
- Enter the receiver's displayed IP address and port `42734`; the Android app's older default of `9001` is not the current PC-receiver port.
- Make sure **Use ConsoleBridge (CBv0)** is unchecked unless you are actually using Pico hardware.
- Allow the receiver on Private networks in Windows Firewall.
- Test `W` in Notepad or `X360A` in a controller tester before blaming a particular game.
- Remember that the app's Connected label does not prove packets reached the PC.

### Keyboard or mouse works, but the Xbox controller does not

- Install or repair ViGEmBus and restart Windows.
- Reopen the receiver after installing the driver.
- Check whether the game accepts an Xbox/XInput controller.
- If a game sees duplicate input, close other controller-emulation tools temporarily and test again. ViGEm does not normally disable a physical controller, but some games can become confused by multiple devices.

### An input seems stuck

- Tap your `RELEASE_ALL` Button first.
- If necessary, disconnect the app and close the receiver.
- Reopen the receiver, reconnect, and test the suspect control by itself.
- Remove overlapping repeat systems such as Pulse plus Turbo.

### A layout or update disappeared

- Use **Load** and check the saved layout list.
- Avoid uninstalling the app, because Android removes its internal saved layouts.
- Before installing a differently signed APK, duplicate important layouts and record their payloads manually until export/import exists.

### Windows warns about the receiver

The receiver is an unsigned development executable, so SmartScreen may warn that the publisher is unknown. Verify that the file came from the official repository and exact release before choosing whether to run it. The source is available for independent inspection.

## Current limitations and testing status

As of September 14, 2026:

- The current feature build is usable but still a development build rather than a store release.
- Delay macros are not reliable enough for ordered action sequences.
- USB tethering, two-player play, Touch Aim tuning, global Hold, and several ConsoleBridge combinations need more real-device testing.
- ConsoleBridge/Pico does not yet provide atomic receiver-side Release All confirmation.
- Profiles are stored inside the Android app and do not yet have external file export/import.
- Some advanced options may require saving and reopening the control before all nested fields appear.
- The visual design and spacing still have room to improve.

If you find a bug, the most useful report includes:

- phone/tablet model and Android version;
- app and receiver file/version;
- Wi-Fi, USB tether, or ConsoleBridge connection;
- layout/control type and exact payload;
- what you expected to happen;
- what actually happened and whether Release All cleared it.

I am happily accepting feedback, bug reports, visual suggestions, accessibility ideas, and feature requests. This is a project I care deeply about, and practical reports from other people are what will make it better.

## Recent additions: August 29–September 14, 2026

There were no feature commits from August 29 through September 7. The following work began on September 8, was largely committed on September 10, and continued through September 14.

### 1. Touch Sensor Test — developed September 8, committed September 10

A separate diagnostic screen records one-finger contact Size, Major, and Minor measurements while still and moving. Open the **Touch Sensor Test** launcher icon, complete its six prompted touches, then copy the results. It does not send controller input.

### 2. Touch Aim — developed September 8, committed September 10

A one-finger aiming surface can move the mouse or a virtual stick while Low, Medium, and High touch-contact stages activate payloads. Add **Touch Aim**, choose its movement output and sensor score, then configure stage thresholds and Press/Hold actions.

### 3. Touch Aim stick modes and response curve — developed September 8, committed September 10

Touch Aim gained right-stick and left-stick output plus an optional response curve. Choose RS or LS instead of Mouse, then tune the curve for finer center control or stronger response.

### 4. Response Curve Stick — developed September 8, committed September 10

A new Stick type reshapes analog sensitivity while preserving full outer travel. Add **Response Curve Stick**, choose left/right output, and adjust the curve value.

### 5. Ordered analog packets — developed September 8, committed September 10

Stick, Touch Aim, and Stick-macro packets now carry ordering information so older analog coordinates cannot overwrite newer ones after network delay. There is nothing to configure; it works automatically with the matching receiver.

### 6. Camera Follow — developed September 8, committed September 10

The receiver can turn left-stick movement into delayed horizontal camera correction. Add a dedicated `CAMERA_FOLLOW` Button to toggle it. This is for the Python PC receiver, not ConsoleBridge.

### 7. USB Tether Mode — developed September 8, committed September 10

The Connect dialog can open Android tether settings and discover the PC receiver over USB networking. Connect the cable, start the receiver, select **USB Tether Mode**, enable tethering, and return to the app.

### 8. Assignable Release All — September 10

Any normal Button can become an immediate emergency reset. Make `RELEASE_ALL` its entire payload. It bypasses normal Hold/Turbo timing and clears Android plus Python-receiver output state.

### 9. Button Aim — September 10

A Button can remain active while the same finger moves outside it to aim with Mouse, RS, or LS. Enable **Aim while pressed** in Button properties and choose Immediate or Send on release.

### 10. One-Shot Alternate — September 11

Button Aim gained Base and Alternate modes. Complete a Base gesture to arm Alternate; use Alternate gestures until a long alternate hold and release returns the control to Base.

### 11. Touch-position Stick origin — September 11

Button Aim stick output can now map the Button center as neutral, making the first touch position immediately meaningful. Enable the touch-position origin option for Base and/or Alternate.

### 12. Base unlatch delay — September 12

A latched Base payload can remain held briefly after an Alternate return/reset. Set the delay in milliseconds, or use `0` for immediate unlatch.

### 13. Toggle Auto-Tap — September 12

A Button can start a persistent timed repeater with one tap and stop it with the next. Enable **Toggle Auto-Tap**, set the interval, then tap once to start and again to stop.

### 14. Controller Pages — September 14

- One saved profile can contain multiple complete, independently editable pages.
- Page-navigation Buttons support Go To, Toggle, Previous, and Home without sending page commands to a receiver.
- Every successful page change releases active output first, while the connection and player settings stay active.
- Existing one-page profiles migrate in memory as Base and remain readable.

---

Screenshots of an earlier version are available in the [SimpleController image album](https://imgur.com/a/simple-controller-app-MbGxUYt). The current interface contains newer options, so some labels and panels may look different.
