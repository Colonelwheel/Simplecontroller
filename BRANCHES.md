# Branches, downloads, and compatibility

## Source branches

| Branch | Purpose | Preserved source/artifact basis |
| --- | --- | --- |
| [`main`](https://github.com/Colonelwheel/Simplecontroller/tree/main) | Default public release | Macro Mode/U and format-17 compatibility promoted from dev `632447c` onto the previous public-release basis `69eee7d` |
| [`dev`](https://github.com/Colonelwheel/Simplecontroller/tree/dev) | Development work and additional verification/packaging tools | Android source at `632447c` supplied the Macro Mode/U promotion; dev now adds selected USB/Wi-Fi reconnection, a rebuilt APK, and extra receiver/voice tools |

`main` is the default public branch. The historical `master` snapshot is preserved locally. Both current branches use the same source layout. Dev's November 24 Android update additionally remembers USB versus manual connection mode, restores USB discovery on reopen, and rejects canceled/stale discovery results. Main retains its previous Android implementation and APK. Controller/profile schemas and the manual Wi-Fi address/ConsoleBridge preference are unchanged. Dev now supplies `Simple Controller Receiver.exe` with hang diagnostics; main keeps `SimpleControllerReceiver.exe` without those additions:

```text
README.md, USER_GUIDE.md, BRANCHES.md
SimpleController.apk, Simple Controller Receiver.exe (dev receiver)
source/android/       Android Studio and Gradle project
source/receiver/      Windows receiver source and packaging
facial-input/         Separate optional facial app
voiceattack/          Optional voice helper and guide
```

The initial main/dev setup and November 22 dev documentation refresh preserved this branch's existing APK/EXE contents. On November 23, dev ports the desktop hang-diagnostic receiver, rebuilds and renames its download to `Simple Controller Receiver.exe`, and updates its packaging helpers. Main's receiver is unchanged. The six production modules preserve the existing control logic with added diagnostic instrumentation; dev also supplies `receiver_timing.py`, `receiver_diagnostics.py` and their tests. The 294 mocked receiver tests and static bundle verification are separate from real input/game testing. The later main promotion builds its own public Android APK from the promoted source; it does not rebuild or replace the November 17 dev APK. Each branch's root README identifies its supplied artifact hashes and verification. Main release `06b1e64` rebuilt and verified its APK byte-for-byte identical to the then-current November 17 dev download; dev's later USB reconnect APK now differs. This does not establish a new device/game test.

## Profile compatibility

Both current main and dev Android source accept controller-profile formats through **17**. Writers require 17 for stored U or `macroModeEnabled=true`, including inactive settings. Older supported profiles remain readable, and profiles without these features retain their earlier conditional versions.

An older format-16 APK from before the main promotion cannot import profiles or backups requiring format 17. U can also require newer reusable schemas: manual TouchAim and calibration 5, Button Aim 9, Directional/Stick+ 6, floating boost 7, and complete ring presets 8. Unsupported data must be rejected before decoding rather than silently dropping settings. Single-profile transfer remains version 1; conditional backup envelopes remain 1–3. Keep verified external backups and the previous compatible APK before moving between app versions. Do not uninstall or clear private app data to resolve a signing issue.

See [the Android profile-transfer contract](source/android/PROFILE_TRANSFER_FORMAT.md) for conditional nested formats and reusable-profile schemas. The branch reorganization does not change serialized data, application IDs, or signing configuration.

## Source branch versus Android build variant

`main` and `dev` select source history. `sideloadDebug` and `playRelease` select Android package/build settings available from either branch:

| Build variant | Application ID | Target API | Purpose |
| --- | --- | --- | --- |
| `sideloadDebug` | `com.example.simplecontroller` | 34 | Existing sideloaded app identity and public APK |
| `playRelease` | `io.github.colonelwheel.simplecontroller` | 36 | Separate Google Play App Bundle identity |

Building or testing Play Release from dev uses dev source. It does not copy code into main, commit or push a branch, upload to Google Play, or install anything. The two application IDs have separate Android private storage; user-selected verified profile exports are the transfer boundary.

## Development and promotion

1. Confirm the intended local branch with `git branch --show-current` and inspect existing changes.
2. Make development changes on dev in the shared directory structure. Keep local SDK paths, caches, keys, backups, and private instructions untracked.
3. Run the checks appropriate to the approved change, preserving existing data and using isolated outputs where practical. Device installs and live input checks need their own authorization.
4. Promote selected changes to main deliberately through normal Git commits/integration. Keep unrelated experiments out of the public release.
5. When an artifact changes, build from the selected source, verify the artifact/source correspondence, and update the intended APK/EXE and its recorded checksum together. Editing source alone does not update an existing binary.
6. Update branch-specific download links, guides, compatibility notes, and verification evidence, then commit and push the intended branch. Report the branch, commit, source project, and artifact paths.

## Downloads and retained history

The root README links to downloads from the branch it describes. GitHub **Code → Download ZIP** provides the committed snapshot of the selected branch, without local untracked build data or Git's working-copy metadata. It is a source/file snapshot, not a complete backup of Git history or local files.

The public repository now publishes only `main` and `dev`. On November 22, 2026, the eight older remote branches, including `master`, `codex/play-store-release`, and `codex/floating-controls`, were retired after their exact tips were preserved in local archive branches and verified complete-history backups. Existing local folders and branch references remain intact; their concrete PC locations belong in the private local workspace map. Commit history reachable through main/dev and the preserved public tags remains available on GitHub; work unique to retired branches is retained in the local archives and backups. URLs containing retired branch names no longer resolve. Current README download links use main/dev, and fixed release-asset links remain unchanged.

Keep the historical recovery tag `stable-2026-09-10` pointing to its original target, and preserve the history containing recovery commit `c6b60428296e1bc2a93fe3859bb962104f769bc9`. Also retain `safe-before-facial-movement-20261010` and the `facial-input-windows-2026-10-16` release tag. Current FacialInput download links intentionally reference that existing versioned release.

Do not force-push, recreate history, remove historical references, or retire old folders/branches as part of routine release promotion. Any later cleanup requires an explicit preservation plan and authorization for the affected data and links.

## Current source layout and historical material

Dev retains the current Android module, receiver companions/tests, and reusable helpers in the common source layout. See [ARCHITECTURE.md](ARCHITECTURE.md) for current entrypoints. The long historical architecture dump, old readme transcript, private restore notes, credential-bearing legacy firmware sketches, old binary collections, and generated build/IDE data are not copied into these new public working tips. They remain in their original local folders and preserved history; this setup does not erase or rewrite those records.

Detailed Android feature documents remain under `source/android` so their module-relative `app/src/...` references keep their meaning. Dated verification, legacy delivery filenames, and old desktop shortcuts are historical evidence rather than current deployment instructions. Local workspace/device maps and backups remain private.
