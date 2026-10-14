# Floating phone output protocol

`PHONE_OUTPUT_V1` is an additive Windows receiver capability. Floating controls
require the matching Python receiver source. Editing source does not update an
already packaged EXE. This implementation does not deploy or start a receiver,
modify a shortcut, change Pico firmware, or prove game/hardware behavior.

## Capability and quiescent adoption

Android sends `PHONE_CAPS_V1:process:nonce`. The response is
`PHONE_CAPS_V1_ACK:process:nonce:PHONE_OUTPUT_V1`. A capability acknowledgement
alone does not enable scoped output.

ControlView reports legacy gestures, holds, retained output, pending work and
repeating senders through `UdpClient.setLegacyOutputBusy`. When all those owners
are idle, Android sends `PHONE_ACTIVATE_V1:process:sequence`. New initial gestures
are consumed during `activationPending`; no synthetic DOWN is sent afterward.
The user can touch again after acknowledgement. Existing Release All remains
accessible. If acknowledgement fails, floating activation stays unavailable.

The receiver applies the activation identity once, establishes a process ordering
barrier, and neutralizes phone-owned legacy output at that quiescent boundary.
This preserves facial and voice ownership, the other player's pad, Camera Follow
preferences, and saved data. Desktop phone mouse output remains the existing
global stream; keyboard output retains the Player 1 rule. Pre-barrier ordered
legacy holds, coordinates and mouse deltas cannot arrive afterward and revive
output. A duplicate activation ACK does not neutralize newer input again.

Android runtime claims are cleared on receiver/player/transport invalidation. ConsoleBridge
does not negotiate this capability; its existing CBv0 frames and fallback remain
unchanged. Pico-side scoped release and acknowledgements remain unsupported.

## Frames and ownership

Fields marked `encoded` use UTF-8 URL form encoding. New frames are limited to
1000 bytes, within the existing 1024-byte receive limit.

| Frame | Fields after its name | Meaning |
| --- | --- | --- |
| `PHONE_STATE_V1` | `process:sequence:encoded-key:encoded-command` | Authoritative aggregate phone state for one logical output. |
| `PHONE_EVENT_V1` | `process:sequence:encoded-scope:generation:event-sequence:encoded-command` | Relative movement or a scoped legacy action. |
| `PHONE_END_V1` | `process:sequence:encoded-scope:generation` | End this scope generation and reject its future old events/continuations. |

Canonical keys include `player1|XBOX|A`, `player2|TRIGGER|RT`,
`GLOBAL|MOUSE|RIGHT`, `GLOBAL|KEY|LCTRL`, and separate
`player1|MANUAL|R` / `player1|MACRO|R` stick channels. Known key aliases are
normalized to the receiver's physical key names. Other key expressions retain
their own spelling; different compound keyboard expressions must not be assumed
to share an individual-key lease.

The phone coordinator keeps per-control/role contributions. Boolean outputs
combine; trigger contributions use the maximum. Existing Button Aim still
selects its own Base/Alternate winner before contributing. Scoped UP/zero removes
that contribution and emits the remaining aggregate. Explicit authored force
commands use a separate legacy-force operation. Generated replay releases never
become broad force commands.

Active/exclusive finite transactions cannot be taken over. Retained replacement
revokes the old contribution and invokes its cancellation/indicator callback.
Finite/repeating output reservations survive OFF gaps and end only when the
transaction ends. Compound Hold payloads can share ADS while reserving only their
configured fire keys. Finite multi-output pulses reserve all participating keys.
Conflicting ring pulse configurations are rejected rather than stealing holds.

For negotiated LS state, a nonneutral owned macro takes priority; ending it restores
the currently owned manual vector and its Camera Follow participation. Ending
manual movement preserves an active macro. RS retains its existing macro/manual
priority. Legacy stick commands keep their existing parsing and behavior.

The receiver applies aggregate Xbox state without the legacy re-press edge.
Intentional ordinary replay remains a separate event; existing directional replay
keeps its 35 ms phone release/repress timing when no other legitimate owner masks
the release. Receiver face/voice buttons remain combined with phone ownership,
and trigger values remain merged by maximum. Full pulse release edges cannot
occur while another source holds the output.

## Freshness, Finish and acknowledgements

State revisions are monotonic per process/logical output. Scope event sequences
and ended-generation tombstones are independent of other controls. Validation,
freshness, native mutation, activation and cleanup share the receiver RLock.
Legacy timed sequences retain their scoped guard while waiting outside that lock.
Known reversible outputs started inside a scoped raw sequence are also released
by END while preserving aggregate and other-source holds.

Android captures player, destination, socket, transport, epoch and scope/output
guards before queuing. The UdpClient queue and NetworkClient fallback queue both
recheck those guards at send time. A closed parent cannot create an active child.

Finish records every logical key touched by its scope and children, including
claims already removed and reserved-only outputs. It sends an acknowledged final
aggregate for those keys plus END identities. Consequently, an earlier lost OFF
cannot be mistaken for confirmed cleanup merely because END was acknowledged.
Duplicate Finish calls share the pending/cached result; older completion callbacks
cannot overwrite a newer cleanup result.

The base acknowledgement is `PHONE_ACK_V1:process:sequence`. State/replay feedback
can append `:MASKED:encoded-key` or `:CLEAR:encoded-key`, based on actual facial or
voice ownership. NetworkClient matches the base ACK and exposes ordered feedback
through `UdpClient.outputConflicts`. It does not infer ADS animation or weapon
restrictions from these values.

Each identity uses five attempts, 60 ms apart, followed by a 300 ms ACK timeout.
Retries are idempotent and bounded. Confirmed cleanup requires every final state
and END acknowledgement. Missing acknowledgements leave cleanup unconfirmed;
all terminal packets being lost cannot be made safe by sender cancellation alone.
An END barrier also cannot undo a relative delta or irreversible action already
emitted. Global Release All retains its separate all-source semantics.

## Verification boundaries

The new coordinator JVM tests cover ownership, retained replacement, finite OFF
gap locks, capability/adoption ordering, ended roots, conflict feedback and
terminal cleanup acknowledgement. Fake-native receiver tests cover stale legacy
traffic after adoption, scoped END, shared phone/face/voice state, Camera Follow
and manual/macro routing. These checks do not validate physical phone feel,
network/game timing, a packaged receiver EXE, or Pico/console behavior.
