# Button Aim Game Mouse

Edit an ordinary **Button**, enable **Aim while pressed** under **Button Aim
Surface**, and select **Game Mouse** in **Aim output**. The same option is
available separately in **Alternate aim output** for One-Shot Alternate. Base
and Alternate can select different outputs and sensitivities.

Game Mouse reuses the relative mouse-event backend already delivered for **Touch
Aim (Game Mouse)**. Use the **Simplecontroller Receiver - TouchAim Game Mouse**
desktop shortcut. The current receiver already accepts these commands; this
addition requires only an APK update and no new receiver EXE.

Both Mouse choices expose the existing **Linear Relative** and **Smoothed /
Nonlinear** profiles, sensitivity, and Y inversion. Game Mouse changes only the
movement backend: the shared aim session sends `GAME_DELTA:dx,dy` to the PC.
Original Mouse continues sending `DELTA:dx,dy`. Payloads, Immediate/Send on release
timing, Hold/latch, directional additions, cancellation and cleanup continue
through their existing paths. Game Mouse does not use TouchAim contact-size
thresholds or Aim/Shoot states.

Existing buttons and older saved Button Aim profiles retain their current
outputs. Save/reopen, complete controller export/import, reusable **Button Aim
profiles**, **Apply aiming feel only**, and **Apply complete profile** preserve
the Base and Alternate mouse selections independently. Applying aiming feel
changes the output choice while retaining the target button's actions.

## Format compatibility

- Controller profiles containing either Button Aim Game Mouse selection write
  nested format **7**, including an Alternate selection stored while Alternate
  is disabled. Older apps reject format 7 before decoding its new enum.
- Profiles containing only Touch Aim (Game Mouse) keep format **6**. Profiles
  without either new mouse selection keep format **5**.
- Reusable Button Aim profiles with either Game Mouse selection use schema **4**.
  Newly captured profiles with original outputs still use schema **3**;
  historical schemas 1 and 2 remain readable and keep their version when copied
  into backups. A Game Mouse value labeled with an older schema is rejected.
- Single-profile transfer and all-profile backup envelopes remain version **1**.
  Backup validation checks nested controller and reusable profile versions.

With ConsoleBridge enabled, Game Mouse uses the existing CBv0 `DELTA` binary
frame and Pico HID mouse path. No Pico firmware or protocol change is needed.
Pico/console behavior still requires verification on the intended hardware.

Tyler confirmed the prior Touch Aim Game Mouse delivery works in Borderlands 3.
This separate Button Aim integration still requires its own real phone/game
check after the APK update. The existing working receiver and previous APK
delivery remain restore points.

## Verified delivery: October 11, 2026

- APK: `deliveries/button-aim-game-mouse-20261011/Simplecontroller (Button Aim Game Mouse).apk`.
- APK SHA-256: `0539FBFAB7841DC75C65C4685D75A0AA5F86FAD149B36C4D5692EA9C10CFE7B3`.
- Both Sideload Debug and Play Release JVM suites passed **190 tests each**,
  including all 25 independent Base/Alternate output combinations, legacy
  defaults, profile round trips, preserving actions when applying aiming feel,
  and PC Game Mouse versus ConsoleBridge command routing.
- Sideload Debug APK assembly passed. Lint reported **0 errors, 174 warnings**.
- Candidate and installed baseline signing certificates matched. The authorized
  `adb install -r` completed successfully; the installed APK matched the delivered
  APK, and all **113 saved app files remained byte-for-byte identical** to the
  latest pre-install snapshot. No uninstall, data clearing, connected tests, or
  automatic app launch was performed.
- The existing TouchAim Game Mouse receiver EXE and desktop shortcut are reused.
  Its SHA-256 remains `23D8121A1D2044DA470A8861FCF7C2C124F43FEE23F12CCF8CFE8DE2D3E1449C`.

These are build, JVM, and installation checks. The new Button Aim option still
needs a real game check; no new camera, native input, or receiver session was
started during verification. TouchAim Aim/Shoot sensitivities and contact-size
behavior were not changed.
