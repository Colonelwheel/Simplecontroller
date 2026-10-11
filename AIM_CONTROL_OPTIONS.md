# Aim cancellation and control option editing

## Optional Send on release boundary behavior

Under a Button's **Button Aim Surface / Aim while pressed**, two new options
default to off:

- **Cancel Send on release if finger leaves button**: leaving the button cancels
  the pending release payload for that gesture, including leaving and returning
  within a coalesced touch event. Lifting outside also cancels it.
- **Re-arm when finger returns inside**: available when cancellation is checked.
  Returning inside makes the pending release eligible again. Leaving again
  cancels it again. Without this option, returning stays canceled until a new
  gesture starts.

Both options apply to Base and One-Shot Alternate when their payload timing is
**Send on release**. Immediate payloads retain their existing behavior. Aiming
continues while the finger moves outside, and lifting uses ordinary aim
centering/retention. A canceled release does not schedule its payload or delay.
Existing Base latches are preserved during alternate cancellation. Hold/reset
qualification stops on exit and starts a fresh interval on an opted-in return.

## Editing improvements

Enabling **Stick+** or **Directional mode** now shows its existing command and
threshold controls immediately. The same checkbox listeners update visibility
and enforce mutual exclusion, so the editor does not need a Save/reopen cycle.
Changing modes does not rebuild the fields or erase unsaved values.

TouchAim now gives persistent labels and per-field help for:

| Field | Default | Meaning |
| --- | --- | --- |
| Contact-score smoothing | 0.350 | Filters the sensor score used to decide Aim/Shoot. Lower values smooth more and respond slower; this is separate from mouse movement smoothing. |
| Enter Shoot confirmation | 90 ms | The score must remain at or above Shoot ON for this long before entering Shoot. |
| Return to Aim confirmation | 120 ms | The score must remain at or below Shoot OFF for this long before returning to Aim. |
| Shoot aim sensitivity | Existing saved value | Replaces normal Aim sensitivity while Shoot is confirmed, using the same scale. |

Confirmation settings keep the existing 32–2000 ms editor range; defaults and
the sensor/movement algorithms are unchanged.

**Clear all boost commands** in Boost thresholds clears the four Regular,
four Super, and four Outer boost command boxes, including hidden Outer fields.
It leaves the ordinary Up/Down/Left/Right commands and every threshold distance
unchanged. It edits only the open dialog: **Save** keeps the cleared commands;
canceling the dialog preserves the previously saved control.

## Profile compatibility

Full controller save/export/backup preserves both new options. A Button with
either option stored as true requires controller format **9**, including an
inactive re-arm selection. Other controls retain their earlier conditional
formats 5, 6, 7, or 8. Transfer and backup envelopes remain version 1.

Reusable Button Aim profiles store these options as action settings and require
schema **5** only when either is true. Game Mouse without these options stays
schema 4, other newly captured profiles stay schema 3, and historical schemas
1 and 2 remain readable. **Apply aiming feel only** preserves the target's
boundary options; **Apply complete profile** copies the saved values.

## Protected baseline

Pre-change commit: `0b4fecb6b6e51aba1c525385af22436eebc5cd58`.
Pre-change APK SHA-256:
`7456CF1F0D0AFB49E2DCF478904F70B4AA4636C0B0B00D7050F795BA2F43DDDE`.
The existing Camera Follow Controls receiver and desktop shortcut are reused;
receiver sources, EXEs, facial input, and Game Mouse are unchanged. New Android
builds and phone preservation snapshots use separate directories. Existing
artifacts and saved app data remain recovery points.

## Verified delivery

APK: `deliveries/aim-control-options-20261011/Simplecontroller (Aim Control Options).apk`.
SHA-256: `8C96E747B8D1D4E6BAA7EA59D14A978EBA279FD28A5E288337A657E23D469D04`.

Both Sideload Debug and Play Release JVM suites passed **230 tests each**.
The 22 new tests cover the pure boundary policy (opt-out, Immediate input,
inside/outside edges, historical exit/return order, permanent cancellation,
optional re-arming, leaving again, reset, and nonfinite coordinates) and profile
round trips, legacy defaults, conditional versions, backup compatibility, and
aim-only versus complete application. Handler exit/return timer and cleanup
paths were reviewed in source; JVM policy checks do not execute Android touch
events or the actual Handler callbacks.

Sideload Debug APK assembly passed. Lint reported **0 errors and 184 warnings**.
The form changes were reviewed for immediate visibility, preserving unsaved
fields, targeting only the 12 boost command tags, and labels matching the actual
confirmation limits. No connected-device tests, phone UI automation, or real
game input checks were run; these remain manual validation.

Candidate and installed baseline signing certificates matched. The authorized
`adb install -r` succeeded. The installed APK matched the delivered APK, and all
**116 saved app files** remained byte-for-byte identical to the latest stable
pre-install snapshot. No uninstall, data clearing, or automatic app launch was
performed. Phone snapshots remain outside the repository.

The existing **Simplecontroller Receiver - Camera Follow Controls** shortcut and
receiver are reused. Its EXE SHA-256 remains
`3242F841DFA9490312BFC5D02C2CCF626FAAD59DA980DA47B381066AFE6CDE17`.
