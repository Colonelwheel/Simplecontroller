# Branches, downloads, and compatibility

## Source branches

| Branch | Purpose | Preserved source/artifact basis |
| --- | --- | --- |
| [`main`](https://github.com/Colonelwheel/Simplecontroller/tree/main) | Public release | Public-release snapshot `69eee7d`; Android work based on `d6e20f6` with the standalone diagnostic removed |
| [`dev`](https://github.com/Colonelwheel/Simplecontroller/tree/dev) | Development features awaiting release promotion | Floating-controls snapshot `1ea6ffa`, including Macro Mode and per-command `(U...)` timing |

Both branches use the same layout:

```text
README.md, USER_GUIDE.md, BRANCHES.md
SimpleController.apk, SimpleControllerReceiver.exe
source/android/       Android Studio and Gradle project
source/receiver/      Windows receiver source and packaging
facial-input/         Separate optional facial app
voiceattack/          Optional voice helper and guide
```

The main/dev setup preserves existing packaged APK/EXE contents. New source organization and documentation do not mean those binaries were rebuilt, installed, or tested in a game. Root README checksums identify the supplied files. Historic build/test reports describe their dated deliveries.

## Profile compatibility

The current main Android source accepts controller-profile formats through **16**. Dev supports **17** for stored Macro Mode or `(U...)` timed releases, including inactive settings. Older supported profiles remain readable by dev.

A profile or all-profile backup containing unsupported format-17 settings cannot be imported into this main release. It must be rejected rather than silently dropping those settings. Keep verified external backups before moving between app versions, and retain the previous compatible APK. Do not uninstall or clear private app data to switch branches or resolve a signing issue.

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

Legacy branches remain available for historical references and existing links, including `codex/play-store-release` and `codex/floating-controls`. They are preserved snapshots, not aliases that automatically follow main or dev. Existing local folders and branch references are retained; their concrete PC locations belong in the private local workspace map.

Keep the historical recovery tag `stable-2026-09-10` pointing to its original target, and preserve the history containing recovery commit `c6b60428296e1bc2a93fe3859bb962104f769bc9`. Also retain `safe-before-facial-movement-20261010` and the `facial-input-windows-2026-10-16` release tag. Current FacialInput download links intentionally reference that existing versioned release.

Do not force-push, recreate history, remove historical references, or retire old folders/branches as part of routine release promotion. Any later cleanup requires an explicit preservation plan and authorization for the affected data and links.
