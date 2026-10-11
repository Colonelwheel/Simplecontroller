# Touch Aim (Game Mouse)

In Edit mode, tap **+**, then **Touch Aim (Game Mouse)** beside the existing
**Touch Aim** entry. Both controls use the same aiming surface, calibration,
manual modes, Aim/Shoot payloads, sensitivities, and release handling.

Only the new control's Mouse movement requests the matching Windows receiver's
experimental relative mouse-event backend through `GAME_DELTA:dx,dy`.
The existing Touch Aim and TouchPad continue sending `DELTA:dx,dy`.
Right-stick and Left-stick outputs keep their existing paths in both versions.
Changing the new control's display name does not change its mouse backend.

The control stores `touchAimGameMouse: true`; older controls default to false.
Saving, reopening, duplicating pages, or exporting/importing a complete controller
profile preserves this choice. Applying either a manual TouchAim profile or a
calibration (including Apply all saved settings) preserves the target control's
mouse version. This lets existing calibration settings work on either version
without silently changing its backend.

Profiles containing the new control use nested controller format 6. Legacy-only
profiles continue writing format 5. Transfer and all-profile backup envelopes stay
at version 1. Older apps reject a nested format-6 profile before reading controls,
including when it is inside an all-profile backup, rather than dropping the new
setting. This app accepts the historical formats as well as format 6.

With ConsoleBridge enabled, both variants retain the existing CBv0 mouse-delta
frame and Pico HID path. No new command is sent to Pico, and no firmware change
is required. Pico/console behavior still requires validation on the intended
hardware.

Windows relative SendInput is software-injected input; its compatibility with
Borderlands 3 or other games is unverified until an actual game test. It does not
install or reproduce Unified Remote's virtual HID driver. Windows pointer settings
and game input handling may change its feel. Use the matching new receiver for
Game Mouse; an older receiver cannot process `GAME_DELTA` movement, although its
normal payload commands may still work.

## October 11 delivery and verification

The matching APK was installed on the Galaxy S22 using a signing-compatible
`adb install -r` update. Its installed SHA256 matched the built artifact. All 113
files in the latest saved app state were byte-identical immediately afterward,
including the newly saved BL3 TouchAim layout. No uninstall, data clearing,
connected Gradle test, or automatic app launch occurred.

The new desktop shortcut is **Simplecontroller Receiver - TouchAim Game Mouse**.
It targets the separate EXE under `deliveries/touchaim-game-mouse-20261011`.
Close the old receiver yourself before launching it; run one receiver only.
Then open SimpleController, enter Edit mode, and choose **+ → Touch Aim (Game
Mouse)**. Existing TouchAim controls stay on their original backend. Existing
manual/calibration settings can be applied to the new control.

The receiver was based on the newest retained-nose facial build. Face, voice, and
Camera Follow companions remain byte-identical to that baseline, and packaged
code/DLLs were inspected without launching the EXE. Previous EXEs and shortcuts
remain available. Future facial receiver builds must include the new
`relative_mouse.py` and current Play entrypoint to retain this Game Mouse feature;
the older facial integration snapshots remain historical restore sources.

Validation: 179 JVM tests passed in each Android variant, Android lint passed
with zero errors (174 warnings), and 215 mocked receiver tests passed. Examples
include legacy `DELTA` preservation, new relative flags and 32/64-bit INPUT sizes,
manual/calibration backend retention, conditional profile versions, malformed
movement rejection, stale movement after Release All, and retained nose movement
coexisting with phone and voice ownership. Independent source and packaged-bytecode
review found no actionable issue. These are synthetic checks plus installation
and artifact verification; live phone UI, Borderlands 3, elevation/pointer feel,
and Pico hardware checks remain separate.

See `TOUCHAIM_GAME_MOUSE_RESTORE_POINTS.md` for Tyler's confirmed known-good
pre-change GitHub commits and artifacts, and the delivery's JSON manifests for
new APK/EXE hashes and shortcut target. Local snapshots contain private saved data
and are not included in the GitHub delivery.
