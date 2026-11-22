# Windows receiver source

This directory is the canonical receiver source and packaging location in both `main` and `dev`. The public executable is [SimpleControllerReceiver.exe](../../SimpleControllerReceiver.exe); editing Python does not rebuild or replace it.

`simple_controller_receiver.py` is the application entrypoint. Its adjacent Camera Follow, phone-output, relative-mouse, facial-input, and voice-input modules must stay with it. `SimpleControllerReceiver.spec` packages those companions and both included `ViGEmClient` DLLs. `requirements.txt` records the versions used to package the supplied Windows receiver.

## Run from source

Use a suitable Windows Python environment with the listed dependencies. Install ViGEmBus separately before running the receiver. From the repository root, the entrypoint is:

```powershell
Set-Location source/receiver
python -B simple_controller_receiver.py
```

Running this command creates the receiver's virtual controllers and listens for input. Close an older receiver before intentionally starting another; do not launch one automatically as a documentation or build check. Logs are stored under `%LOCALAPPDATA%\SimpleControllerReceiver\touchpad_logs`.

## Package a separate executable

Use a prepared Windows Python environment containing the requirements, including PyInstaller. Keep dependency installation in a separate environment rather than altering an existing working runtime. The specification resolves source and DLL paths relative to this directory.

From `source/receiver`, choose a fresh build directory and direct every packaging output there:

```powershell
$receiverBuildRoot = Join-Path $env:TEMP ("simplecontroller-receiver-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $receiverBuildRoot -ErrorAction Stop | Out-Null
$env:PYINSTALLER_CONFIG_DIR = Join-Path $receiverBuildRoot "config"
python -B -m PyInstaller --distpath (Join-Path $receiverBuildRoot "dist") --workpath (Join-Path $receiverBuildRoot "work") SimpleControllerReceiver.spec
```

The new executable is under that directory's `dist/SimpleControllerReceiver.exe`. The command does not copy it over the root public executable or change desktop shortcuts. Preserve the existing executable until replacement is explicitly approved and the new artifact has been verified. Do not use old cleanup/rebuild batch files that overwrite existing outputs.

Before publishing a rebuilt receiver, check the selected source and companions, packaged files and DLLs, and relevant synthetic tests. An executable package check does not establish actual controller, camera, speech-recognition, or game behavior. Do not start native input or camera sessions without authorization.

The main/dev setup did not rebuild or launch the supplied receiver. See [branch workflow](../../BRANCHES.md), [the main setup guide](../../README.md), [facial input](../../facial-input/README.md), and [VoiceAttack](../../voiceattack/USER_GUIDE.md).
