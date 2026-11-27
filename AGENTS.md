# Project instructions

## Repository layout

- `main` is the public release; `dev` contains newer development work. Read [BRANCHES.md](BRANCHES.md) before release promotion or changing source layout.
- The Android Studio/Gradle project is `source/android`, with Kotlin and resources under its `app` module.
- The Windows receiver entrypoint, companions, requirements, and PyInstaller specification are in `source/receiver`.
- `facial-input` is the separate optional facial app; `voiceattack` contains the optional voice helper and guide.
- Root APK/EXE files are published artifacts. Source changes do not update a packaged binary automatically.

## Before making changes

- Before planning or implementing changes, use [ARCHITECTURE.md](ARCHITECTURE.md) as a lookup, not a read-through: read its Start here routing table, then only the sections it names for this task (find them by heading), plus their linked contracts and test pointers. Verify the current branch and actual source; this map describes main and can become stale. Update affected entries in the same work when approved implementation or layout changes alter them. This guide does not grant source/device permissions.
- When a change affects features, controls, settings, setup, downloads or saved-data compatibility, update the matching section of [USER_GUIDE.md](USER_GUIDE.md) and/or [README.md](README.md) in the same change. Find the section by heading and leave unrelated parts alone.
- Read the relevant project-authored Markdown, inspect the actual branch/worktree and local changes, and preserve working behavior. Prefer targeted changes.
- Protect signing keys, saved profiles, installed-app data, backups, and untracked or ignored files you didn't create. Don't delete existing folders or clear app data as cleanup.
- Keep existing branches, worktrees and history unless their retirement is specifically authorized. Never reset, clean, force-push, or rewrite their history as routine housekeeping.
- Preserve the recovery commit `c6b60428296e1bc2a93fe3859bb962104f769bc9` and the `stable-2026-09-10` tag and its original target. Preserve other existing recovery/release tags.
- Do not publish local handoffs, private settings, signing keys, credentials, device backups, or personal assistant instructions. Shared instructions should use portable repository-relative paths.

## Build and verification boundaries

- Run Android commands from `source/android`. See its README for the supported `sideloadDebug` and `playRelease` tasks and signing boundary.
- Building an APK doesn't authorize installing it, and building the receiver doesn't authorize replacing or launching a running receiver.
- Don't install to, uninstall from, clear data on, or run connected tests on a physical phone unless its owner asks; use an emulator for connected tests. Before any Gradle install or connected task, set `ANDROID_SERIAL` to the one online emulator serial and stop if it is unset, a list, or not `emulator-NNNN`, so a phone that's also plugged in can't be targeted. PowerShell guard: `if ([string]::IsNullOrWhiteSpace($env:ANDROID_SERIAL) -or $env:ANDROID_SERIAL -notmatch '^emulator-\d+$') { throw 'One emulator serial is required' }`. A `--serial` option must equal `ANDROID_SERIAL`.
- Do not launch receiver/camera/native-input sessions, change shortcuts, install drivers, or flash firmware without authorization for those effects.
- Run checks proportionate to the change. Preserve app IDs, signing behavior, profile formats, output ownership, Hold, and release/cancellation behavior unless an approved change explicitly requires otherwise.
- Clearly distinguish historical verification, source/byte checks, builds, tests, installation checks, and actual device/game behavior. Do not repeat historical test counts as newly run results.

## Branch workflow

- Keep `main` and `dev` layouts aligned. Promote selected development changes deliberately and update the corresponding guides, profile compatibility notes, artifact provenance, and hashes.
- Commit only intended files. Inspect staged contents before committing; do not broadly stage existing IDE files, caches, or local outputs.
- Use the intended repository and named branch when pushing. A build variant named Play Release is not a GitHub release branch.
