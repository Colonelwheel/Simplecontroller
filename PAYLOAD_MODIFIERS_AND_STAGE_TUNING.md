# Payload modifiers and TouchAim stage tuning

Modifiers belong to individual action commands, after the existing command text:

```text
LT:1.0(I)(HRD200),RT:1.0(SOR)
```

- `(I)` activates the command immediately when its source activates.
- `(SOR)` arms a command until the source's normal release, then emits one finite
  press. The default press is 90 milliseconds.
- `(HRD200)` keeps an already held output active for 200 milliseconds after its
  release. The number is whole milliseconds, from 0 to 600000.
- `(R100)` repeatedly presses and releases that command while its action source
  stays held. The number is the interval between press starts, in milliseconds.
- `(D100)` delays that command's activation by 100 milliseconds. The number is
  whole milliseconds, from 0 to 600000. Each command's delay runs independently.
- `(U100)` releases that command's owned press 100 milliseconds after activation.
  Whole durations from 1 to 600000 are accepted. This is separate from D.

An ordinary Button's **Macro Mode** checkbox lets one tap complete all timed
commands after finger-up. Commands without a duration use a finite 90 ms press.
It runs once, ignores global Hold/Turbo, and is separate from Hold Toggle,
Auto-Tap, Button Aim and directional button inputs. R is excluded from this
finite mode. Extra taps during a run are ignored. Release All and hard cleanup
cancel the run. See [Macro Mode and payload porting](MACRO_MODE_AND_PAYLOAD_PORTING.md)
for exact U interactions, compatibility, code entry points and copyable examples.

Commands without modifiers retain their existing behavior. An explicit `(I)` or
`(SOR)` overrides Button Aim's whole-payload timing for that command; undecorated
commands in the same payload inherit the existing timing. Modifier names are
case-insensitive. Conflicting, duplicate, malformed, or unsupported modifiers
are rejected before output starts. Timed modifiers require releasable outputs;
Release All and one-way actions cannot be delayed this way.

For the example above, touching holds LT, lifting activates RT for 90 ms, and LT
releases 200 ms after lift. The timers are independent.

## Release and Hold

On an ordinary Button, finger release starts HRD. With Hold Toggle, lifting while
latched does not start HRD; unlatching starts it. Existing Hold qualification,
Swipe, Turbo and Auto-Tap behavior remains owned by the original control.

Global Turbo excludes commands decorated with HRD: those activate once and stay
held until release/unlatch starts their delay. Turbo repeats immediate commands
without HRD, while SOR stays armed for one release-time pulse. For example,
`LT:1.0(HRD200),RT:1.0(I),RT:1.0(SOR)` holds LT steadily, repeats immediate RT,
then sends one RT pulse on release while LT remains held another 200 ms.
When Turbo and SOR use the same output, Turbo ends first and the SOR pulse starts
after a 35 ms release gap. Other legitimate owners still retain their normal
shared-output ownership; the modifier cannot force their held input to release.

On TouchAim, HRD starts when a held stage stops being eligible or when the finger
lifts. Existing cumulative Hold behavior remains in force: with lower-hold
carryover enabled, a held Medium payload stays held through High and back to
Medium, without starting its timer. Returning below Medium starts its HRD timer;
lifting while that timer is running leaves the original deadline unchanged.
For example, returning to Low with `(HRD2000)` and lifting 500 ms later leaves
1500 ms of the hold remaining. Re-entering the eligible Hold stage during a live
HRD tail resumes that hold without a release gap or another activation delay.
After the tail has expired, a later activation starts normally, including D.
Normal two-state Aim/Shoot threshold exits use the same HRD release rule.

TouchAim `(SOR)` can fire on a normal stage exit or finger lift, once per stage
activation. The existing two-state **Press when returning to Aim** choice keeps
its original threshold-return meaning; unmodified actions are not changed into
finger-lift shots.

Cancellation, pointer loss, Release All, app pause, edit mode, profile/page change,
disconnect and scoped Finish are hard cleanup. They bypass HRD and cancel armed
SOR actions. A stale callback cannot release a newer activation. A legitimate
second owner can keep a shared output held after the first owner's delay ends.

## Activation delay and pulse syntax

### Per-command activation delay

```text
LT:1.0(D100)(HRD200),RT:1.0
```

RT activates immediately. LT activates 100 ms after the action source starts,
provided that source is still active. Once LT is pressed, leaving its eligible
TouchAim stage or lifting starts its 200 ms HRD tail. A lower Hold carried into
a higher stage keeps its original activation deadline and its held output.

Delays run in parallel. `LT:1.0(D100),RT:1.0(D100)` activates both at 100 ms;
`LT:1.0,RT:1.0(D100)` activates LT immediately and RT at 100 ms. Command order
does not create a sequence of waits. Modifier order does not change their meaning.

Leaving an immediate command's source before D expires cancels its pending press.
HRD cannot turn a canceled pending press into a new hold. Re-entry after that
cancellation starts a fresh delay. Hard cleanup cancels pending delays and live
outputs immediately, with stale callbacks unable to activate or release a newer
source. Other legitimate owners of the same output remain independent.

D combines with `(I)`, `(SOR)`, `(R...)`, HRD and existing trigger P syntax.
With R, D delays the first press, then the command follows its own R cadence.
With SOR, D delays the finite release shot after normal source release. The P
press duration begins when the delayed output actually activates. Global Turbo
waits for the command's initial D once, then keeps its normal repeat behavior;
HRD and explicit R commands retain their existing Turbo exclusions. Existing
finite Tap/Auto-Tap/ring action windows still bound their activations.

### Per-command repeat

```text
LT:1.0(I)(HRD200),RT:1.0(I)(R100)
```

This holds LT steadily while RT repeats. RT starts pressed immediately, releases
after 50 ms, and starts its next press at 100 ms, then 200 ms, and so on. Lifting
stops RT and leaves LT held for its configured extra 200 ms. With Hold Toggle,
the action stays active until unlatching. No global Turbo is required.

R controls each command's cadence independently. Global Turbo does not restart
it or shorten its presses; unrelated commands keep their existing behavior.
The default press duration is half the R interval, capped at 50 ms. Whole repeat
intervals from 2 to 600000 ms are accepted. Actual device/network timing remains
best effort, particularly at very short intervals.

An existing trigger P suffix can explicitly choose each press's duration:
`RT:1.0P0.03(R100)` uses 30 ms pressed and 70 ms released. The authored press must
be shorter than the repeat interval so a release interval remains.

Use a Hold mapping for continuous repetition on TouchAim, stick directions,
boosts and ring actions. TouchAim lower-stage Hold carryover keeps the same R
timer through higher stages and back. Leaving the eligible stage or direction
stops repetition. Existing finite Tap/Auto-Tap/native ring Repeat windows still
bound their individual activations; R does not convert those modes into latches.

R and HRD can be combined. On release, repetition stops immediately; HRD can
extend the final press only if it is currently ON. An OFF-phase release does
not reactivate an output. A normal TouchAim stage exit also starts this tail;
hard cleanup always stops both the timer and output immediately.

R repeats while held, so `(R100)(SOR)` on the same command is rejected. Separate
commands can repeat RT and arm an RT release shot independently. When those
commands use the same output, the release shot follows a 35 ms release gap.
If Button Aim's overall timing is Send on release, add `(I)` to the R command
to explicitly select held repetition. Repeating commands are limited to the
same reversible gamepad, trigger, keyboard, mouse-button and stick-macro outputs
as the other timing modifiers. Other legitimate held owners can mask a pulse;
R never forces their output to release.

### One finite trigger pulse

`P` remains part of the trigger command, before the modifiers:

```text
RT:1.0P0.3(SOR)
```

This emits one 300 ms trigger pulse on release. `P0.3` is the duration of one
pulse, not a repetition count or a total repeated-fire window. `(P0.3)` is not a
supported modifier. Existing latched/repeating pulse behavior remains separate.

## Sensitivity and confirmation

The main sensitivity slider is replaced by numeric text entry, accepting values
such as `0.001`, `0.137`, and `1.000` within the existing 0–5 range.

In Manual three-stage TouchAim, Aim-only, Low, Medium and High each have:

- **Stage aim sensitivity (optional):** an independent value on the same scale.
  Blank inherits the main sensitivity, including future edits to that value.
- **Enter Stage Confirmation (ms, optional):** the time the detected destination
  stage must remain steady before its payload transition and sensitivity apply.
  Blank or 0 preserves immediate entry; whole values up to 600000 are accepted.

While confirmation is pending, the confirmed stage stays active. A changed
candidate restarts confirmation; returning to the confirmed stage cancels it.
Stationary contact can finish its confirmation timer without a new MOVE event.
Sensitivity changes preserve the gesture's origin and movement session.

Confirmation belongs to the destination stage. Existing cumulative lower HOLDs
apply with the confirmed destination. When confirmation is enabled, skipped
intermediate PRESS stages do not fire. With every confirmation set to zero,
legacy crossed-stage PRESS behavior is preserved.

Manual and calibrated two-state modes retain their existing Aim/Shoot
sensitivities and entry/return confirmation settings. The new four-stage fields
are shown only for Manual three-stage mode.

## Saved data and transport

Old layouts default to inherited sensitivities and zero new confirmations.
Full layouts, duplicate controls/pages, portable transfers, backups and reusable
manual profiles preserve the new settings. Profiles with new tuning or modifier
syntax require controller format 14, format 15 when R is stored, or format 16
when D is stored, or format 17 when U or Macro Mode is stored. Reusable libraries use conditional schema
versions so older apps reject unsupported features before silently dropping them.

Modifiers execute in Android. Receivers receive the existing plain commands;
no new Pico firmware or receiver command syntax is required. Source/build tests
do not establish physical phone feel, network timing, or game acceptance.

## October 25 verification and delivery

Before the final Turbo exclusion refinement, both Android variants passed 533 JVM
tests each, with no failures, errors or skips. After that final change, 76 focused
modifier, executor, TouchAim-stage, ring and scoped-ownership tests passed. Both
the Sideload Debug APK and Play Release AAB built successfully. Lint reported zero
errors in both variants (208 and 206 warnings respectively).

The signing-compatible `adb install -r` update succeeded on the Galaxy S22.
Two fresh pre-install snapshots matched, and all 127 app files remained
byte-identical after installation. The installed APK matched the delivered APK:
`32A3E7E04F644706B0D36846FF24CADE3A204364DF8BDBCD1A97CFB7E29F494C`.
The previous installed APK and read-only native app snapshots remain in the
separate local build records. No uninstall, clear-data command, connected Gradle
test, backup restore, automatic app launch or live game-input test was performed.

## October 30 repeat modifier verification and delivery

Both Android variants passed all 581 JVM tests with no failures, errors or skips.
Coverage includes independent repeat intervals, ON/OFF release, HRD, global Turbo,
same-output SOR, shared owners, canceled callbacks, directional reentry, TouchAim
Hold carryover and PRESS entry rules, ring ownership and profile compatibility.
The Sideload Debug APK and Play Release AAB built successfully. Lint reported zero
errors in both variants, with the existing 208 and 206 warnings respectively.

The signing-compatible `adb install -r` update succeeded on the Galaxy S22.
Two fresh pre-install snapshots matched, and all 127 app-data files remained
byte-identical after installation. The installed APK matched the tested APK:
`2C145B1964D991E5A597526A04C439C4C80F583077A9487797D4E309225F15B2`.
The working October 25 APK, native app snapshots and new delivery artifacts are
preserved in separate local build records. No uninstall, clear-data command,
connected Gradle test, backup restore, automatic app launch or live game-input
test was performed.

## November 2 Delay and TouchAim HRD verification and delivery

Both Android variants passed all 626 JVM tests with no failures, errors or skips.
The added checks cover parallel command delays, early release, pulse/repeat/Turbo
timing, HRD threshold exit and reentry, stale callbacks, shared owners, ring
reservations, directional sources and conditional profile/library versions.
The Sideload Debug APK and Play Release AAB built successfully. Lint reported
zero errors, with the existing 208 Sideload and 206 Play warnings.

The signing-compatible `adb install -r` update succeeded on the Galaxy S22.
Two fresh pre-install snapshots matched, and all 127 app-data files remained
byte-identical after installation. The installed APK matched the tested APK:
`9C378F407503FBA6D161B5C4384BA15D6DA4A8518A72BA5FB90B5CE264E28B3B`.
The previous working APK, native app snapshots and new delivery artifacts remain
in separate local build records. No uninstall, clear-data command, connected
Gradle test, backup restore, automatic app launch or live game-input test was
performed. Receiver command syntax and receiver executables are unchanged.

## November 6 Macro Mode and U verification and delivery

Both Android variants passed all 678 JVM tests with zero failures, errors or
skips. APK and AAB packaging passed. Lint reported zero errors (209 Sideload
warnings and 207 Play warnings, including one new translation warning for the
Macro Mode help text). The added tests cover finite U deadlines, macro tap
completion, Ctrl+Alt+Tab, simultaneous buttons, D origins, HRD reentry, SOR,
R/Turbo interactions, shared ownership, cleanup and profile compatibility.

The signing-compatible `adb install -r` succeeded. Both fresh pre-install
snapshots matched, and all 127 saved app-data files remained byte-identical
after installation. Installed APK SHA-256:
`478178571B1606AEC51A61900C127EFA2C5C7370832E0B48E32E01B7DDF20203`.
The previous APK and native app snapshots remain in separate local records.
No uninstall, data clearing, restore, connected tests, automatic app launch or
live game-input test was performed. Receiver files remain unchanged.
