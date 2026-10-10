# Facial movement, wink and boosts receiver delivery

Approved subject: **Add facial stick movement, wink input and directional boosts**.
Baseline `04204ed`, branch `codex/play-store-release`.
Pushed source restore tag: `safe-before-facial-movement-20261010`.

Four reviewed sources were applied to the root and `Edit exe Folder` only after
original/staged/preserved-companion hashes matched. Root/Edit entrypoints and
face companion copies are byte-identical. Added two fake-native regression files.
VoiceAttack/Camera Follow companions, Android source/builds, older receiver EXEs
and existing runtime files are unchanged. No native inputs/apps/cameras launched.

The additive loopback v3 endpoint supports named facial owners, LS vector,
250 ms original-capture leases, 100 ms Tap IDs, generic keyboard ownership,
tagged facial injection, source-aware re-center watch, phone LS priority and
axis-scoped reset. Original v1/v2 paths remain supported. Face packets do not
maintain phone connectivity, ordering or Camera Follow phone-activity timestamps.
RELEASE_ALL resets all source ownership and epochs before cleanup.

Actual applied checkout: **195 synthetic tests passed in 3.223 seconds**.
Full facial workspace integration: **681 passed in 30.73 seconds**, including
runtime/client/server loopback with fake outputs and offscreen UI. Counts overlap.
Cases include preserving phone W/RT holds, phone LS takeover without face-vector
fallback, independent axis reset, stale lease/heartbeat cleanup, timer/epoch races,
96-target bounds and source snapshots preserving short phone shortcut taps.
These do not prove game/physical-controller/elevation/camera/performance behavior.
An unchanged shared-memory timing test fell short once, then passed targeted and
full reruns without a weakened criterion; the facial validation notes retain it.

New separate artifact in the facial workspace:
`deliveries/receiver-movement-20261010/dist/Simple Controller Receiver (Facial Movement and Boosts).exe`.
SHA256 `04636384815521BD46A6733A5BA490B2396B685FE9146D66438EACEDFF6C8F6D`.
Desktop shortcut: **Simplecontroller Receiver - Facial Movement and Boosts**.
Build input hashes/bundled v3 callbacks/companions/ViGEm DLLs were inspected
without executing the EXE. Run one receiver only; Tyler chooses when to close
the old one and start this shortcut. Existing EXEs/shortcuts remain available.

Full launch/validation/restore/resource-test instructions are in the facial
workspace's `README.md`, `NEXT_CHAT_HANDOFF.md`, `MOVEMENT_VALIDATION.md` and
`SAFE_RESTORE_POINT.md`; protocol and exact application manifest are under
`integrations/receiver`. Preserve all later work before any restoration. No
destructive historical rebuild BAT, Git reset/clean or runtime-data replacement.
