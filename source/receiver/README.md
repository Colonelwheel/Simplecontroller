# Windows receiver source

This directory is the canonical receiver source and packaging location. Dev's executable is [Simple Controller Receiver.exe](<../../Simple Controller Receiver.exe>), with hang diagnostics enabled. Main retains its existing receiver without these diagnostic additions. Editing Python does not rebuild or replace an EXE.

`simple_controller_receiver.py` contains the receiver logic. `receiver_diagnostics.py` is the packaged entrypoint: it enables the adjacent `receiver_timing.py` recorder before starting the receiver. Keep all eight modules together, including Camera Follow, phone-output, relative-mouse, facial-input and voice-input. `SimpleControllerReceiver.spec` packages them and both included `ViGEmClient` DLLs. `requirements.txt` records the versions used to package the supplied Windows receiver.

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

The November 23, 2026 dev update ports the existing desktop diagnostic implementation, rebuilds the renamed receiver and verifies all eight packaged modules plus both DLLs without launching it. Its 294 mocked receiver tests passed; real input/game behavior remains a separate check. See [branch workflow](../../BRANCHES.md), [the setup guide](../../README.md), [facial input](../../facial-input/README.md), and [VoiceAttack](../../voiceattack/USER_GUIDE.md).

## Retained development helpers

Dev also retains the [Camera Follow packaging helper](tools/camera_follow/build_receiver.ps1), [Game Mouse packaging helper](tools/game_mouse/build_receiver.ps1), and [voice integration helper](../../voiceattack/build_receiver.ps1). They resolve all eight canonical modules and bundled DLLs from repository-relative paths, accept a selected Python executable, and require a fresh delivery directory. They package the diagnostic entrypoint while retaining their historical output filenames; the specification above is the canonical public receiver recipe. The [diagnostic bundle inspector](tools/diagnostics/verify_receiver_bundle.py) verifies a delivery's `build-report.json`, eight packaged modules and DLLs without executing the receiver, creating a new `bundle-verification.json` report.
