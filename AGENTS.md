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
- Protect tracked, untracked, ignored, backup, profile and installed-app data, signing keys and files the user uses. The approved global regenerable-file exception allows in-place overwrite of build outputs, caches, temp files and own-run logs in the established output folder; it does not permit deleting existing folders or overwriting keys, profiles, saves or app data.
- Keep existing historical branches and worktrees unless their retirement is specifically authorized. Never reset, clean, force-push, or rewrite their history as routine housekeeping. Work in the existing main or dev folder; do not create a branch or worktree per task unless Tyler asks.
- Preserve the recovery commit `c6b60428296e1bc2a93fe3859bb962104f769bc9` and the `stable-2026-09-10` tag and its original target. Preserve other existing recovery/release tags.
- Do not publish local handoffs, private settings, signing keys, credentials, device backups, or personal assistant instructions. Shared instructions should use portable repository-relative paths.

## Build and verification boundaries

- Run Android commands from `source/android`. See its README for the supported `sideloadDebug` and `playRelease` tasks and signing boundary.
- Use the project's established reusable build-output folder identified in the local workspace instructions; overwrite its regenerable outputs in place instead of making per-task source/output copies. An APK build is not authorization to install it; a receiver build is not authorization to replace or launch a running receiver.
- Never run Gradle install or connected tasks on the primary phone. Approved connected tests reuse the existing designated emulator. Before every Gradle install or connected task, run adb devices, set ANDROID_SERIAL to the one online emulator serial, and stop if it is unset, a list, or not emulator-NNNN. Use the PowerShell guard: `if ([string]::IsNullOrWhiteSpace($env:ANDROID_SERIAL) -or $env:ANDROID_SERIAL -notmatch '^emulator-\d+$') { throw 'One emulator serial is required' }`. Confirm the selected serial is online and is the designated existing AVD using adb -s <serial> get-state and adb -s <serial> emu avd name. A connected --serial option must equal ANDROID_SERIAL; never use a conflicting override. Do not uninstall or clear protected app data as a troubleshooting shortcut.
- Do not launch receiver/camera/native-input sessions, change shortcuts, install drivers, or flash firmware without authorization for those effects.
- Run checks proportionate to the change. Preserve app IDs, signing behavior, profile formats, output ownership, Hold, and release/cancellation behavior unless an approved change explicitly requires otherwise.
- Clearly distinguish historical verification, source/byte checks, builds, tests, installation checks, and actual device/game behavior. Do not repeat historical test counts as newly run results.

## Branch workflow

- Keep `main` and `dev` layouts aligned. Promote selected development changes deliberately and update the corresponding guides, profile compatibility notes, artifact provenance, and hashes.
- Commit only intended files. Inspect staged contents before committing; do not broadly stage existing IDE files, caches, or local outputs.
- Use the intended repository and named branch when pushing. A build variant named Play Release is not a GitHub release branch.
