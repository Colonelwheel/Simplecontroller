# Known-good restore points before TouchAim Game Mouse

Tyler explicitly confirmed the current GitHub APK and EXE/receiver versions as
known-good restore points on October 11, 2026, before this feature was changed.

## Verified GitHub commits

- Simplecontroller repository: `https://github.com/Colonelwheel/Simplecontroller`,
  branch `codex/play-store-release`, commit
  `ea152049812d9f3c6473ca5057877ee99496868c`
  (`Retain nose movement through tracking loss until paused`). The current Android
  source at this point is unchanged since
  `54c65ca0b3c4851841e935d9f0c4f5867316454b`
  (`Allow duplicate and empty directional button payloads`).
- Facial-Gaming-Input repository:
  `https://github.com/Colonelwheel/Facial-Gaming-Input`, branch `main`, commit
  `06df36643f97350a41b19c4929e7d20825a7b314`
  (`Retain nose movement through tracking loss until paused`).

Both remote branch tips were read directly with `git ls-remote` and matched these
commits before implementation. The shared receiver entrypoint and face, voice,
and Camera Follow companions in the Play checkout root and `Edit exe Folder`
were byte-identical to the facial integration and newest retained-nose delivery.

## Known-good receiver artifact

The current Test build desktop receiver shortcut targets:

`C:\Users\Tyler\OneDrive\Documents\ChatGPT\Face Detection for Gaming Inputs\deliveries\receiver-retained-nose-20261010\dist\Simple Controller Receiver (Facial Movement and Boosts).exe`

SHA256: `FAA5612FC43B813DBF2D3AB6236D20DCAA31F694F1DE1487523B8C2F25780F11`.

This is the newest facial receiver, including indefinite nose tracking-loss
retention, facial movement/wink/boosts, source ownership, VoiceAttack, and Camera
Follow. It is the baseline for the additive Game Mouse receiver, not an older
facial-only or Release All executable. The existing artifact and shortcuts remain.

## Installed APK and private data snapshot

Phone: Samsung Galaxy S22 `R5CT70EJ58T`; package `com.example.simplecontroller`.
Installed baseline APK SHA256:
`539A56868B28CF3CFE4E416A3FA768CAA54A6783A94D9546372B64539268707D`.

An additional local-only read-only snapshot was created at
`C:\Codex Projects\New codex\.codex-game-mouse-20261011\pre-update`.
It contains `installed-baseline.apk`, `app-data.tar`, and
`preservation-manifest.json` with hashes for 112 existing files under `files`,
`shared_prefs`, `databases`, and `no_backup`. These private files are not uploaded.

## Recovery boundary

Inspect these commits in a separate checkout or new directory. Preserve all later
work and obtain explicit authorization before replacing source, installed apps,
saved profiles/preferences, or shortcuts. Do not reset/clean the existing checkout,
uninstall SimpleController, clear app data, or run connected Gradle tests. A prior
APK would require a signing-compatible in-place update with preservation checks.
Do not start multiple receivers at once. No automatic rollback is authorized.
