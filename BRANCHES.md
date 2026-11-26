# Branches, downloads, and compatibility

## Source branches

| Branch | Purpose | Preserved source/artifact basis |
| --- | --- | --- |
| [`main`](https://github.com/Colonelwheel/Simplecontroller/tree/main) | Default public release | Macro Mode/U and format-17 compatibility promoted from dev `632447c` onto the previous public-release basis `69eee7d` |
| [`dev`](https://github.com/Colonelwheel/Simplecontroller/tree/dev) | Development work and additional verification/packaging tools | Android implementation and regression tests at `632447c` are the source of this main promotion; dev retains its additional receiver/voice tools and historical feature notes |

`main` is the default public branch. The historical `master` snapshot is preserved locally. Both current branches use the same layout:

```text
README.md, USER_GUIDE.md, BRANCHES.md
SimpleController.apk, SimpleControllerReceiver.exe
source/android/       Android Studio and Gradle project
source/receiver/      Windows receiver source and packaging
facial-input/         Separate optional facial app
voiceattack/          Optional voice helper and guide
```

The initial main/dev setup preserved existing APK/EXE contents. This later Macro Mode/U promotion updates main's Android source, regression tests and public APK; the receiver, driver and facial downloads stay at their existing versions. The root README records the supplied artifact checksums and this release's verification. A source change alone does not rebuild a binary, install an app or establish game behavior.

## Profile compatibility

Both current main and dev Android source accept controller-profile formats through **17**. Writers require 17 when Macro Mode is stored as enabled or any action attempts `(U...)`, including inactive settings. Profiles without those features retain their earlier conditional versions; older supported profiles remain readable.

A format-16 APK from before this promotion cannot import profiles or all-profile backups containing format-17 settings. U can also require newer nested reusable schemas: manual TouchAim and calibration 5, Button Aim 9, Directional/Stick+ 6, floating boost 7, and full-ring presets 8. Unsupported versions must be rejected before decoding rather than silently dropping settings. This promotion adds no transfer/backup-envelope version: single-profile transfer stays 1 and the conditional backup envelope remains 1–3.

Keep verified external backups before moving between app versions, and retain the previous compatible APK. Do not uninstall or clear private app data to switch branches or resolve a signing issue.

See [the Android profile-transfer contract](source/android/PROFILE_TRANSFER_FORMAT.md) for conditional nested formats and reusable-profile schemas. Macro Mode defaults off in older layouts. Application IDs and signing configuration are unchanged by this promotion; an independently built APK can still use a different developer's signing key.

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
3. Run the checks appropriate to the approved change, preserving existing data and using the project's established reusable build-output folder. Device installs and live input checks need their own authorization.
4. Promote selected changes to main deliberately through normal Git commits/integration. Keep unrelated experiments out of the public release.
5. When an artifact changes, build from the selected source, verify the artifact/source correspondence, and update the intended APK/EXE and its recorded checksum together. Editing source alone does not update an existing binary.
6. Update branch-specific download links, guides, compatibility notes, and verification evidence, then commit and push the intended branch. Report the branch, commit, source project, and artifact paths.

## Downloads and retained history

The root README links to downloads from the branch it describes. GitHub **Code → Download ZIP** provides the committed snapshot of the selected branch, without local untracked build data or Git's working-copy metadata. It is a source/file snapshot, not a complete backup of Git history or local files.

The public repository now publishes only `main` and `dev`. On November 22, 2026, the eight older remote branches, including `master`, `codex/play-store-release`, and `codex/floating-controls`, were retired after their exact tips were preserved in local archive branches and verified complete-history backups. Existing local folders and branch references remain intact; their concrete PC locations belong in the private local workspace map. Commit history reachable through main/dev and the preserved public tags remains available on GitHub; work unique to retired branches is retained in the local archives and backups. URLs containing retired branch names no longer resolve. Current README download links use main/dev, and fixed release-asset links remain unchanged.

Keep the historical recovery tag `stable-2026-09-10` pointing to its original target, and preserve the history containing recovery commit `c6b60428296e1bc2a93fe3859bb962104f769bc9`. Also retain `safe-before-facial-movement-20261010` and the `facial-input-windows-2026-10-16` release tag. Current FacialInput download links intentionally reference that existing versioned release.

Do not force-push, recreate history, remove historical references, or retire old folders/branches as part of routine release promotion. Any later cleanup requires an explicit preservation plan and authorization for the affected data and links.
