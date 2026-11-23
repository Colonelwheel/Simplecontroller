# Project instructions

## Repository layout

- `main` is the public release; `dev` contains newer development work. Read [BRANCHES.md](BRANCHES.md) before release promotion or changing source layout.
- The Android Studio/Gradle project is `source/android`, with Kotlin and resources under its `app` module.
- The Windows receiver entrypoint, companions, requirements, and PyInstaller specification are in `source/receiver`.
- `facial-input` is the separate optional facial app; `voiceattack` contains the optional voice helper and guide.
- Root APK/EXE files are published artifacts. Source changes do not update a packaged binary automatically.

## Before making changes

- Before planning or implementing changes, consult [ARCHITECTURE.md](ARCHITECTURE.md): read its Start here routing table, then the relevant subsystem, ownership/cleanup rules and test pointers. Verify the current branch and actual source; the map describes main and can become stale. Update affected entries in the same work when approved implementation or layout changes alter them. This guide does not grant source/device permissions.
- Read the relevant project-authored Markdown, inspect the actual branch/worktree and local changes, and preserve working behavior. Prefer targeted changes.
- Ask before source changes unless the concrete scope is already authorized. Ask for the commit message for important source, firmware, build, or binary changes; use a clear message for documentation-only work.
- Treat tracked, untracked, ignored, generated, backup, profile, and installed-app data as important. Obtain explicit permission before overwriting, moving, or deleting existing data outside the authorized scope.
- Keep existing historical branches and worktrees unless their retirement is specifically authorized. Never reset, clean, force-push, or rewrite their history as routine housekeeping.
- Preserve the recovery commit `c6b60428296e1bc2a93fe3859bb962104f769bc9` and the `stable-2026-09-10` tag and its original target. Preserve other existing recovery/release tags.
- Do not publish local handoffs, private settings, signing keys, credentials, device backups, or personal assistant instructions. Shared instructions should use portable repository-relative paths.

## Build and verification boundaries

- Run Android commands from `source/android`. See its README for the supported `sideloadDebug` and `playRelease` tasks and signing boundary.
- Use isolated build outputs where practical. An APK build is not authorization to install it; a receiver build is not authorization to replace or launch a running receiver.
- Do not run `installSideloadDebug`, any Gradle install task, or any connected-device test task without explicit approval covering the device and its data effects. Do not uninstall or clear app data as a troubleshooting shortcut.
- Do not launch receiver/camera/native-input sessions, change shortcuts, install drivers, or flash firmware without authorization for those effects.
- Run checks proportionate to the change. Preserve app IDs, signing behavior, profile formats, output ownership, Hold, and release/cancellation behavior unless an approved change explicitly requires otherwise.
- Clearly distinguish historical verification, source/byte checks, builds, tests, installation checks, and actual device/game behavior. Do not repeat historical test counts as newly run results.

## Branch workflow

- Keep `main` and `dev` layouts aligned. Promote selected development changes deliberately and update the corresponding guides, profile compatibility notes, artifact provenance, and hashes.
- Commit only intended files. Inspect staged contents before committing; do not broadly stage existing IDE files, caches, or local outputs.
- Use the intended repository and named branch when pushing. A build variant named Play Release is not a GitHub release branch.
