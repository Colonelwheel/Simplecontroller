# ConsoleBridge / Pico Release All TODO

Status: deferred. The production Pico firmware has not been identified or authorized for modification or flashing. The Android app clears its own state in ConsoleBridge mode, but it must report that Pico-side release is unconfirmed until this work is completed and tested on the intended hardware.

## Protocol

- [ ] Identify the production UF2 and matching source before editing anything.
- [ ] Add an explicit, idempotent CBv0 `RELEASE_ALL` frame type. It must be a set/reset command, never a toggle.
- [ ] Carry a sender session/epoch and sequence in the command, or define an equivalent release barrier using the existing CBv0 sequence field.
- [ ] After accepting Release All, reject any older queued or reordered input frame from that sender.
- [ ] Return an acknowledgement containing the accepted session/sequence so Android can distinguish confirmation from UDP send success.
- [ ] Make duplicate commands and repeated acknowledgements harmless.

## Firmware state reset

- [ ] Clear the entire keyboard HID report, including modifiers and every held key.
- [ ] Release left, right, and middle mouse buttons.
- [ ] Discard pending mouse movement and scroll deltas; reset any smoothing/accumulator state.
- [ ] Release every gamepad button and neutralize the D-pad/hat.
- [ ] Set both triggers to zero.
- [ ] Center both analog sticks.
- [ ] Cancel macros, `WAIT`/`DELAY` continuations, pulse/turbo tasks, rate-limit queues, and any resend loop.
- [ ] Clear connection-owned hold/latch bookkeeping so reconnect cannot restore stale output.
- [ ] Send the neutral keyboard, mouse, and gamepad HID reports immediately.

## Android integration after firmware support exists

- [ ] Encode the new CBv0 frame in `CbProtocol.kt`.
- [ ] Send bounded retries using the same release identity.
- [ ] Wait for the exact acknowledgement and update the current unconfirmed UI message.
- [ ] Keep Android local cancellation first so timers and resenders stop even when the Pico is unreachable.

## Verification

- [ ] Add parser/state tests for every output category and duplicate Release All frames.
- [ ] Test a delayed macro and an older reordered packet arriving after the release barrier.
- [ ] Test release during Hold, Turbo/pulse, stick streaming, TouchAim, and mouse click lock.
- [ ] Test Wi-Fi loss, reconnect, firmware restart, and repeated emergency taps.
- [ ] Test every enabled USB HID profile on the actual intended Pico and console/PC target.
- [ ] Keep Pico support labeled unverified until those hardware tests pass.

## Button Aim Surface follow-up (deferred)

- [ ] Identify the intended production Pico firmware, then verify Button Aim Surface payloads,
  sustained Mouse/LS/RS traffic, ordered stick updates, rate limits, terminal centering, configured
  release delay cancellation, and `RELEASE_ALL` behavior on the real hardware. No Pico firmware,
  UF2, protocol, or flashing change is authorized as part of the Android Button Aim implementation.
