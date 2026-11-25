# Windows receiver source

**Rollback status:** the [root receiver EXE](<../../Simple Controller Receiver.exe>) has been restored to the previous eight-module build from `1483b33`. This directory retains the withdrawn nine-module isolation source from `4f6aa9c` for investigation. The withdrawn build could strand held modifiers because a desktop timeout rejected later key-up requests. Do not rebuild/deploy it as a corrected receiver. [Matching restored source](https://github.com/Colonelwheel/Simplecontroller/tree/1483b33cfec8c3808303fa3332d14e8365c6ab98/source/receiver) and the current checksum are identified in the [root guide](../../README.md).

The following implementation and packaging notes describe the retained isolation candidate, not the restored EXE. Main retains its existing receiver without these diagnostic additions. Editing Python does not rebuild or replace an EXE.

`simple_controller_receiver.py` contains the receiver logic. `receiver_diagnostics.py` is the packaged entrypoint: it enables the adjacent `receiver_timing.py` recorder before starting the receiver. Keep all nine modules together, including `desktop_output.py`, Camera Follow, phone-output, relative-mouse, facial-input and voice-input. `SimpleControllerReceiver.spec` packages them and both included `ViGEmClient` DLLs. `requirements.txt` records the versions used to package the supplied Windows receiver.

## Desktop output isolation

Mouse and keyboard native calls run in a separate helper process. The receiver retains ownership, timers, packet ordering, Camera Follow and its two virtual controllers. Startup warms the helper before opening the input endpoints. Healthy calls preserve the existing keyboard, tagged facial-key, PyAutoGUI and Game Mouse backends; input is not buffered for later replay.

A desktop request has a 100 ms deadline covering contention and communication as well as native execution. A timeout or helper/native failure disables further desktop output for that receiver session, so a stuck Windows mouse call cannot indefinitely hold the receiver's shared output lock. Controller processing continues after the bounded failure. The receiver does not automatically restart the helper or replay old clicks. Invalid input values are rejected separately.

Cleanup continues through independent controller and scheduling work when desktop output fails. Unconfirmed desktop cleanup must not receive a successful cleanup acknowledgement, including duplicate requests. A failed native call may already have affected Windows or may finish late; a timeout cannot cancel input already executing. Restart the receiver after Windows input recovers. This contains a failure; it does not repair an underlying Windows, hook or driver fault.

The helper owns no virtual controllers or receiver ports. Its internal startup mode is handled before receiver initialization. It may appear as an additional receiver-named process in a packaged build. If Windows cannot terminate a helper stuck in native code, it can remain present and can still lock the shared EXE; the main receiver's output/network lifetime is separate.

## Run from source

Use a suitable Windows Python environment with the listed dependencies. Install ViGEmBus separately before running the receiver. From the repository root, the entrypoint is:

```powershell
Set-Location source/receiver
python -B receiver_diagnostics.py
```

Running this command creates the receiver's virtual controllers and listens for input. Close an older receiver before intentionally starting another; do not launch one automatically as a documentation or build check. Ordinary logs remain under `%LOCALAPPDATA%\SimpleControllerReceiver\touchpad_logs`. Diagnostic files go into `hang-diagnostics` beside the EXE (beside this entrypoint for source runs), unless `SC_RECEIVER_TIMING_DIR` selects another directory. See [diagnostic capture and retention](RECEIVER_TIMING_DIAGNOSTICS.md). Directly running `simple_controller_receiver.py` leaves diagnostics off unless `SC_RECEIVER_TIMING=1` is set.

## Package a separate executable

Use a prepared Windows Python environment containing the requirements, including PyInstaller. Keep dependency installation in a separate environment rather than altering an existing working runtime. The specification resolves source and DLL paths relative to this directory.

From `source/receiver`, choose a fresh build directory and direct every packaging output there:

```powershell
$receiverBuildRoot = Join-Path $env:TEMP ("simplecontroller-receiver-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $receiverBuildRoot -ErrorAction Stop | Out-Null
$env:PYINSTALLER_CONFIG_DIR = Join-Path $receiverBuildRoot "config"
python -B -m PyInstaller --distpath (Join-Path $receiverBuildRoot "dist") --workpath (Join-Path $receiverBuildRoot "work") SimpleControllerReceiver.spec
```

The new executable is under that directory's `dist/Simple Controller Receiver.exe`. The command does not copy it over the root public executable or change desktop shortcuts. Preserve the existing executable until replacement is explicitly approved and the new artifact has been verified. Do not use old cleanup/rebuild batch files that overwrite existing outputs.

Before publishing a rebuilt receiver, check the selected source and companions, packaged files and DLLs, and relevant synthetic tests. An executable package check does not establish actual controller, camera, speech-recognition, or game behavior. Do not start native input or camera sessions without authorization.

The November 23, 2026 dev update ported the existing desktop diagnostic implementation and passed 294 mocked tests. The November 24 isolation update passed 324 mocked tests, static verification of all nine packaged modules and both DLLs, and the frozen no-input startup probe. No receiver/native input session or game was started during these checks. See [branch workflow](../../BRANCHES.md), [the setup guide](../../README.md), [facial input](../../facial-input/README.md), and [VoiceAttack](../../voiceattack/USER_GUIDE.md).

## Retained development helpers

Dev also retains the [Camera Follow packaging helper](tools/camera_follow/build_receiver.ps1), [Game Mouse packaging helper](tools/game_mouse/build_receiver.ps1), and [voice integration helper](../../voiceattack/build_receiver.ps1). They resolve all nine canonical modules and bundled DLLs from repository-relative paths, accept a selected Python executable, and require a fresh delivery directory. They package the diagnostic entrypoint while retaining their historical output filenames; the specification above is the canonical public receiver recipe. The [diagnostic bundle inspector](tools/diagnostics/verify_receiver_bundle.py) verifies a delivery's `build-report.json`, nine packaged modules and DLLs without executing the receiver, creating a new `bundle-verification.json` report.
