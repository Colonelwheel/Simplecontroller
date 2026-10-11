# Camera Follow participation and live strength

## User controls

Movement controls offer **Exclude from Camera Follow**. When checked, their
left-stick movement still moves the character but does not generate Camera
Follow assistance. Camera Follow stays enabled for participating controls.
This setting does not alter manual right-stick priority or turn mouse movement
into Camera Follow input. Retained movement keeps its source's participation
setting until that movement is replaced or centered.

For an ordinary button whose payload is `CAMERA_FOLLOW`, `CAMERA_FOLLOW:1`, or
`CAMERA_FOLLOW:0`, its options offer **Camera Follow strength (%)**. The payload
keeps its existing toggle/on/off meaning. Save the field to apply the strength
live; pressing the button also applies its saved value. The receiver uses one
effective strength per player. Saving unrelated controls does not change it.

- **100%** preserves the original response: gain 0.55 and maximum horizontal
  right-stick output 0.45.
- **125%** uses gain 0.6875 and maximum output 0.5625.
- **75%** uses gain 0.4125 and maximum output 0.3375.
- **0%** gives no generated assistance while leaving the toggle state and
  manual right-stick input intact.

The supported range is 0 through 200 percent. The field scales response and its
output ceiling together. Existing activation delay, ramp durations, movement
deadzone, manual override timeout, and manual/macro right-stick priority remain
unchanged. Game settings still influence the actual camera turning speed.

## Persistence and compatibility

Existing controls default to participation and 100% strength. Full controller
save/reopen, transfer, and backup preserve the per-control values. A profile
containing an exclusion or nondefault strength requires controller format 8;
older apps reject that version rather than silently removing those settings.
Profiles using only previous features retain their previous format version.
The transfer and backup envelopes remain version 1.

Exclusion is a property of the target control's Camera Follow participation.
Applying reusable Button Aim, directional, manual TouchAim, or calibration
tuning keeps the target control's exclusion setting, as those reusable profiles
do not capture Camera Follow participation. Their existing schemas remain valid.

Camera Follow runs in the Windows receiver. ConsoleBridge/Pico keeps its existing
binary stick output and does not apply these receiver-only controls.

## Receiver protocol

Eligible movement retains the existing ordered stick commands. Excluded phone
left-stick movement uses `STICK_L_NO_FOLLOW:session:sequence:x,y`; excluded
left-stick macro movement uses `STICK_MACRO_L_NO_FOLLOW:session:sequence:x,y`.
These commands share the physical left-stick freshness and Release All barriers
with ordinary left-stick packets. A stale packet cannot replace either the
movement vector or its Camera Follow participation.

Live strength uses `CAMERA_FOLLOW_STRENGTH:session:sequence:percent` with a separate
strength freshness gate and the existing output safety barrier. The receiver
rejects malformed, nonfinite, out-of-range, duplicate, and stale strength updates.
Facial left-stick movement retains its previous Camera Follow behavior when it
is the active source; an excluded phone stick does not suppress that fallback.

## Protected starting point

The pre-change source restore point is commit
`10512b755df37613d6d5957da07d7a8280122ea3` on `codex/play-store-release`.
The existing Button Aim Game Mouse APK and TouchAim Game Mouse receiver delivery
remain available. The receiver base includes the newest confirmed retained-nose
facial input integration (`06df36643f97350a41b19c4929e7d20825a7b314` in the facial
workspace), Game Mouse, and unchanged facial/voice companions.

The prior APK SHA-256 is
`0539FBFAB7841DC75C65C4685D75A0AA5F86FAD149B36C4D5692EA9C10CFE7B3`.
The prior receiver EXE SHA-256 is
`23D8121A1D2044DA470A8861FCF7C2C124F43FEE23F12CCF8CFE8DE2D3E1449C`.
New build output, phone snapshots, and deliveries use separate directories.
Existing APKs, EXEs, desktop shortcuts, and app data are preserved.

## Verified delivery

New artifacts are in `deliveries/camera-follow-controls-20261011`:

- APK: `Simplecontroller (Camera Follow Controls).apk`, SHA-256
  `7456CF1F0D0AFB49E2DCF478904F70B4AA4636C0B0B00D7050F795BA2F43DDDE`.
- EXE: `Simple Controller Receiver (Camera Follow Controls and Game Mouse).exe`,
  SHA-256 `3242F841DFA9490312BFC5D02C2CCF626FAAD59DA980DA47B381066AFE6CDE17`.
- Desktop shortcut: **Simplecontroller Receiver - Camera Follow Controls**.

Both Android JVM suites passed **208 tests each**. Cases include participation
metadata on retained movement and takeover, unchanged right-stick routing,
ordered commands, profile format selection, full export/import and backup, and
preserving target participation when applying reusable profiles. Sideload Debug
APK assembly passed; lint reported **0 errors and 176 warnings**.

The receiver passed **236 synthetic tests**, including 21 new cases for excluded
movement preserving physical LS while immediately removing generated assistance,
face/phone winning-source behavior, stale packets and Release All races,
Player 1/2 isolation, finite strength boundaries, live scaling and reductions,
and manual/macro right-stick priority. At 125%, full horizontal LS produces
0.5625 generated RS; 0.4 horizontal LS produces 0.275 after normal activation
and ramping. These checks use mocked native outputs and local test sockets.

Static bundle verification confirmed all five bundled modules match their build
sources semantically, both ViGEm DLLs match, and the retained-nose facial, voice,
and Game Mouse companions remain unchanged. Root/Edit receiver copies match.
The packaged EXE was not launched, and no production camera or native game
inputs were activated during verification.

Candidate and installed baseline signing certificates matched. The authorized
`adb install -r` succeeded; the installed APK matched the delivered APK, and all
**114 saved app files** remained byte-for-byte identical to the latest stable
pre-install snapshot. No uninstall, data clearing, connected tests, or automatic
app launch was performed. Phone snapshots remain outside the repository.

For a real game check, close the old receiver and start the new desktop shortcut.
Verify an excluded movement stick still moves the character without assistance,
a participating stick still assists, and saving strength 125% changes assistance.
These phone/game behaviors remain unverified by the synthetic/build checks.
