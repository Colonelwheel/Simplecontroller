# Button Macro Mode and timed payload release: porting contract

This describes the Android-local behavior to preserve when importing or porting
these additions. No receiver protocol or EXE change is required. Modifiers are
removed on Android; receivers get the existing ordinary DOWN/HOLD and UP/RELEASE
commands. Existing layouts and controls default to Macro Mode off.

## Syntax and clocks

Payloads contain independent commands separated by commas or whitespace. Suffixes
are case-insensitive, and suffix order does not matter. A comma does not wait for
or release another command. Times are whole milliseconds.

| Suffix | Meaning |
| --- | --- |
| `(I)` | Explicit immediate/source-down timing, overriding inherited Send on release. |
| `(SOR)` | Arm one finite press for normal source release. Default press: 90 ms. |
| `(D100)` | Delay this command's activation 100 ms from its own timing origin. |
| `(U100)` | Release this command's owned press 100 ms after it actually activates. |
| `(HRD200)` | On normal source release, extend a currently held output up to 200 ms. |
| `(R100)` | While the source remains held, start a new press every 100 ms. |

D and HRD accept 0..600000 ms; U accepts 1..600000 ms; R accepts 2..600000 ms.
For immediate commands, D starts with source activation. For SOR, D starts at
normal source release. Different commands' delays run in parallel, including
commands that name the same output. U starts at the actual delayed press, not at
initial touch. Malformed, duplicate or conflicting modifiers reject the whole
payload before any output starts. Timed commands must be reversible outputs:
Xbox buttons, triggers, keyboard keys, mouse buttons or fixed-direction sticks.
Page actions, Release All, toggles and raw force-UP commands are excluded.

## Button Macro Mode

Persist `Control.macroModeEnabled: Boolean = false`. Show a **Macro Mode** checkbox
in ordinary Button settings. On DOWN, start one finite run immediately. Normal
finger-up does not cancel pending D commands or shorten finite immediate presses.
Commands without U use a 90 ms press; an existing trigger P duration is respected.
SOR still waits for the real source finger-up and fires only once.

Additional taps while the run is active are ignored: they neither restart nor
queue another run. After completion, a new tap starts a fresh run. Holding the
finger down does not repeat a completed run. Runs containing armed SOR remain
alive until finger-up. Track the initiating pointer: its POINTER_UP is normal
release, while a missing pointer or Android CANCEL is hard cleanup.

Macro Mode is separate from Hold Toggle, Toggle Auto-Tap, Button Aim and directional
button inputs; selecting one disables the conflicting mode. Macro Mode ignores
global Hold and global Turbo and always runs once. R is rejected in Macro Mode
because it is a while-held repeater without a finite completion time. Applying a
reusable Button Aim profile disables Macro Mode. Macro Mode is not stored as a
reusable Button Aim action setting.

## Examples

Tap a simultaneous shortcut (Macro Mode on):

```text
CTRL,ALT,TAB
```

All three keys go down at source activation and release after 90 ms. To choose
200 ms instead, use `CTRL(U200),ALT(U200),TAB(U200)`.

Tap A for 100 ms, wait 400 ms after its release, tap A again for 100 ms,
wait 200 ms after that release, then tap B for 100 ms (Macro Mode on):

```text
X360A(U100),X360A(D500)(U100),X360B(D800)(U100)
```

| Time from initial touch | Output |
| --- | --- |
| 0..100 ms | A held |
| 100..500 ms | Released; 400 ms gap |
| 500..600 ms | A held |
| 600..800 ms | Released; 200 ms gap |
| 800..900 ms | B held |

Lift after 20 ms and the same full timeline still completes. U does not make D
sequential: `X360A(U100),X360A(D400)(U100),X360B(D200)(U100)` instead presses A at
0 ms, B at 200 ms, and A again at 400 ms.

For a two-button combination at every step (Macro Mode on):

```text
X360A(U100),X360X(U100),X360A(D500)(U100),X360X(D500)(U100),X360B(D800)(U100),X360X(D800)(U100)
```

This holds A+X during 0..100 and 500..600 ms, then B+X during 800..900 ms.

To hold a modifier across several separate presses (Macro Mode on):

```text
CTRL(U900),A(U100),B(D400)(U100),C(D800)(U100)
```

Ctrl stays held through all three 100 ms key presses and releases at 900 ms.

## U outside Macro Mode and modifier interactions

U works anywhere the shared payload executor is used, including normal Buttons,
stick direction/boost commands, Button Aim, TouchAim and floating-ring actions.
It sets one press's maximum duration; it does not create a latch. In normal mode,
releasing/exiting the source early still cancels pending D and normally releases
its active outputs. Existing finite PRESS preservation rules remain applicable.

- U with HRD outside Macro Mode releases at the earlier of the U deadline or
  normal source release plus HRD. A completed U never reactivates for HRD.
  Example: press at 0 with `(U100)(HRD200)`, lift at 20: release at 100, not 220.
- In Macro Mode, finger-up does not shorten finite immediate commands. Their U,
  P or default duration controls completion; HRD does not extend that finite run.
- SOR+U uses U as the release-shot duration after D. HRD cannot extend it beyond U.
  SOR without U retains its existing default/P duration plus HRD behavior.
- Outside Macro Mode, R+U chooses each repeated press's ON duration. U must be
  shorter than R: `RT:1.0(R100)(U20)` means 20 ms ON and 80 ms OFF. D delays only
  the first repeat. Normal source release stops repetition; live HRD is capped
  by the current U deadline. R and SOR cannot decorate the same command.
- Trigger P and U on the same command are rejected: choose one duration syntax.
- Global Turbo does not cut short an active U press. Its next eligible tick can
  start another press after U ends. Existing HRD and R Turbo exclusions remain.
- Shared ownership always wins: one command's U releases only that command's
  claim. Overlapping A commands merge into one held A until the last claim ends.
  Give repeated same-key presses a genuine released gap if separate presses are
  required; U never forcibly releases another control's legitimate hold.

TouchAim's existing cumulative lower-Hold carryover remains unchanged. HRD starts
when a Hold stage becomes ineligible or the finger lifts; lifting during an
existing tail does not restart it. Re-entry can reclaim a still-live identical
HRD token without a new D delay. The U deadline remains a maximum ON duration.

## Cancellation and implementation requirements

Release All, pause, disconnect, Edit, page/profile/orientation changes, removal,
property changes, pointer loss and scoped Finish cancel pending steps, armed SOR
and active output claims immediately. A canceled callback must never press or
release a newer run. Preserve other owners and other floating instances.

Implement separate activation, finite-press, repeat and release timers per token,
with generations and immutable lease identity checked by every callback. Use a
single thread/event loop for state transitions. Do not implement delays by
blocking/sleeping the receiver or Android UI thread. Prune completed finite runs
even while the finger stays down, retaining only still-armed SOR work.

Relevant Kotlin entry points:

- `model/PayloadModifiers.kt`: parse U into nullable `pressDurationMs`;
  `hasReleaseModifiers` also detects malformed U attempts for format gating.
- `ui/ButtonAimPayloadExecutor.kt`: `activate(..., completeOnRelease = true)`
  retains finite commands across normal UP; `isLeaseActive` reports completion.
- `ui/ButtonMacroController.kt`: validate finite single-run payloads, supply the
  default 90 ms press, ignore overlapping taps, and expose normal/hard release.
- `ui/ControlView.kt`, `ControlViewHelper.kt`, `PropertySheetBuilder.kt`: touch
  routing, scoped transport, cleanup and checkbox/mutual exclusion.

## Saved-data compatibility

Full controller profiles require format **17** if any stored action attempts U
or any `macroModeEnabled` is true, including inactive configuration. Preserve the
boolean through saves, duplicates, transfers and backups. D-only stays format16,
R-only15, and older modifiers/stage tuning14. Transfer envelopes remain unchanged.

U requires these reusable schemas only when stored: manual TouchAim **5**,
calibration **5**, Button Aim **9**, Directional/Stick+ **6**, floating boost **7**,
complete ring **8**. Check raw JSON before typed decoding so unsupported features
cannot silently disappear. Historical defaults, versions and fixtures remain
readable. Never overwrite another saved profile on import.

## Verification anchors

Port the behavioral tests rather than only the parser: `PayloadUpTest`,
`ButtonMacroControllerTest` and `ReleasePayloadProfileCompatibilityTest` cover
actual event timelines, tap completion, simultaneous keys, repeat/HRD/SOR,
overlapping owners, invalid input, cancellation, stale callbacks and persistence.
Tests use a deterministic fake scheduler; device/network/game timing is still
best effort. Automated event tests do not establish real game acceptance.

The November 6 delivery passed 678 JVM tests per Android variant, built APK/AAB,
and passed lint with no errors. The in-place ADB install was verified against
the candidate APK, and all 127 saved app files were unchanged. This is build,
event-timeline and install evidence; live keyboard/game behavior remains for
manual testing.
