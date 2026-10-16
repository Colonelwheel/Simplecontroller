# Facial Gaming Input

[Download FacialInput.exe for Windows 10/11 x64](https://github.com/Colonelwheel/Simplecontroller/releases/download/facial-input-windows-2026-10-16/FacialInput.exe)

Facial expressions and optional comfortable face movement send keyboard, mouse,
and Xbox inputs through the existing [SimpleController receiver](../SimpleControllerReceiver.exe).
The facial app creates no additional virtual controller. It can be used alongside
one-finger phone controls; see the main [setup page](../README.md) and
[controller user guide](../USER_GUIDE.md).

The standalone download includes Python 3.12, MediaPipe 0.10.21, OpenCV 4.11,
the PySide6/Qt GUI, and the verified approximately 3.76 MB Face Landmarker model.
Once downloaded, it launches offline. You do not need to install Python, run pip,
download a model, or run the facial app as administrator. The source installation
and packaging instructions below are for developers or people who prefer Python.

## Install and start

1. Install [ViGEmBus](../ViGEmBus_1.22.0_x64_x86_arm64.exe) once before opening the
   receiver; restart Windows if requested. The receiver creates its Xbox
   controllers at startup. Neither the receiver EXE nor the facial EXE installs
   this driver automatically.
2. Run one copy of [SimpleControllerReceiver.exe](../SimpleControllerReceiver.exe).
   Close an older receiver before switching: multiple receivers use the same
   phone port and may create additional virtual controllers.
3. Download `FacialInput.exe` using the link above and open it. Keep it **paused**
   while setting up. Run one facial-app instance at a time; the receiver accepts
   one facial connection. Existing saved settings are preserved.
4. Choose **Standalone webcam** in **Camera source**, select the camera number,
   then press **Apply camera / retry**. Choose a camera available to this app.
   It cannot determine whether another application needs that device.
5. If needed, adjust capture detail and use **Select face region**: tap one corner
   around your face, then the opposite corner. No dragging, keyboard chord, or
   simultaneous input is required. Use the optional preview to check mouth and
   eyebrow detail at your normal camera position.

A compatible local shared-memory camera publisher is an optional alternative to
standalone capture. In shared mode, the facial app reads the published crop and
does not open a webcam. The safety-monitor integration and its camera publisher
are not bundled with this download. Standalone mode does not require that
monitor; this app never starts or stops it for you. Select the intended camera
mode explicitly, especially when reusing existing saved preferences.

## Calibrate, test, then enable output

1. Press **Calibrate**. Collect neutral samples, then comfortable repeated mouth
   openings and eyebrow raises with their separate buttons. Relax between
   repetitions. **Stop / Review** ends collection; calibration sends no input.
2. Review the proposed thresholds. Use **Test (no output)** to compare deliberate
   expressions with ordinary speech, yawning, blinking, lighting, and posture.
   Choose an expression you can repeat comfortably without accidental activation.
3. Set the binding, Tap or Hold behavior, Player, and preset. **Neutral at or below**
   controls rearming; in Hold mode, **Release below** separately controls release.
   Keep a clear gap between relaxed and deliberate scores.
4. Select **Start** when ready. Reviewed thresholds, fresh tracking, receiver
   readiness, and the applicable neutral/centering checks still apply. **Pause**,
   calibration, and Test stop facial output; changing settings also clears it.
5. Select **Save Settings** explicitly to retain your choices. Closing the window
   or choosing tray **Quit** ends the app. **Minimize to tray** keeps it running.

The app starts paused and the optional preview starts off. Test and calibration
remain useful whenever lighting, posture, camera position, or bindings change.
A successful package check does not establish reliable expression recognition
or comfortable use on a particular webcam.

## Inputs and optional movement

Expression bindings include A/B/X/Y, LB/RB, Start/Back, D-pad directions, LS/RS
clicks, full LT/RT, left click, right click, and Spacebar. Each supports Tap or
Hold. One expression is active by default; the optional checkbox allows mouth
and eyebrow bindings together. Optional face movement, wink and boost features
start disabled and need deliberate configuration. This app does not supply
sequential macros, turbo, or latch.

Phone and facial buttons combine so that one source's release cannot interrupt
another source's hold; trigger output uses the higher value. A held input from
another source can therefore mask the edges of a facial tap. Start with distinct
bindings when checking behavior. Use **Pause** to stop facial output and release its contributions.

The optional Start / Pause shortcut can use a keyboard key or a SimpleController
Xbox button. Add chosen items with **Add selected**; keyboard and Xbox chords
are independent alternatives. Defaults are empty. Configuration uses separate
pointer actions, and a single comfortable binding avoids simultaneous presses.

## Settings and privacy

Settings, saved calibration, and movement profiles belong to the current Windows
user under `%LOCALAPPDATA%\FacialGamingInput`. **Save Settings** creates a new
private settings revision; previous revisions remain intact. Updating the EXE
does not require deleting that folder or recalibrating from scratch.

Images are processed locally. The app does not upload or record camera imagery;
calibration does not save an image/sample stream. Personal settings, calibrations,
images, logs, resource-test records, Python environments, and old receiver
binaries are excluded from this public source package. The optional resource
test explicitly writes local scalar diagnostics; keep those private files out
of anything you share publicly.

## Run from source instead

Use **Windows x64 Python 3.12**, a shared feed or an available webcam, and the
separate receiver/ViGEmBus setup above. Keep this folder's `src`, `scripts`, and
launcher together. Open PowerShell in this `facial-input` folder and run:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-lock.txt
.\.venv\Scripts\python.exe -B scripts\download_model.py
```

Use a new project environment rather than copying someone else's `.venv`.
Installing dependencies and the explicit model download require internet access.
The pinned runtime includes MediaPipe 0.10.21, NumPy 1.26.4, PySide6 6.11.2, and
OpenCV 4.11.0.86. Python is restricted to 3.12 by `pyproject.toml`.

The downloader saves Google's model to `models\face_landmarker.task`, refuses
to overwrite an existing file, and prints its SHA-256. The downloader does not
automatically enforce the expected digest; compare it yourself:

```powershell
Get-FileHash .\models\face_landmarker.task -Algorithm SHA256
```

Expected model SHA-256:

```text
64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff
```

Double-click **Start Facial Input.cmd** after setup. It uses this folder's
virtual environment. Camera selection, calibration, Test, Start, and saved
settings work as described above. The source checkout does not include a model
or a personal settings folder.

## Build the standalone EXE

The matching recipe is [packaging/FacialInput.spec](packaging/FacialInput.spec),
invoked by [scripts/build_windows.py](scripts/build_windows.py). Use Windows x64
Python 3.12 with the runtime environment and verified model prepared above.
[packaging/requirements-build.txt](packaging/requirements-build.txt) pins
PyInstaller 6.22.3 and its build dependencies. They can be installed in a separate,
new tools directory rather than changing the runtime environment:

```powershell
.\.venv\Scripts\python.exe -m pip install --target C:\FacialInputBuildTools -r packaging\requirements-build.txt
.\.venv\Scripts\python.exe -B scripts\build_windows.py --output-root C:\FacialInputBuild --tools-dir C:\FacialInputBuildTools
```

The paths above are examples: choose dedicated, unused generated-output and
tools folders. With the intended Python environment active, the command form is
`python -B scripts/build_windows.py --output-root <dedicatedgeneratedfolder>`;
append `--tools-dir <isolatedtarget>` when using an isolated tools install.
Replace the angle-bracket placeholders with actual paths.

The builder verifies the model hash and creates `dist\FacialInput.exe`, build
intermediates, collected notices, and `build-report.json` beneath the selected
output root. The report includes the EXE's byte size and SHA-256. It refuses to
replace an existing EXE unless you deliberately pass `--replace-build-output`.
Reuse the same dedicated build folder; approve replacing its generated EXE before
using that option. Keep a known-good release separate from generated build output.

An explicit package check exercises the bundled native libraries, model and Qt
with synthetic data:

```powershell
C:\FacialInputBuild\dist\FacialInput.exe --check-package --report C:\FacialInputBuild\package-check.json
```

Choose a new report filename; the checker refuses to overwrite one. It does not
open a webcam, connect to the receiver, or use personal settings. Inspect the
JSON report for `ok: true`; the GUI executable does not provide a normal console
window. This check is separate from testing a real camera, user comfort, or a game.

See [third-party notices](packaging/THIRD_PARTY_NOTICES.md) for bundled runtime,
Qt/PySide6, and model notices. The EXE includes collected component licenses;
the build also leaves those notices beside the distribution for packaging.

The release also provides a downloadable [component-license archive](https://github.com/Colonelwheel/Simplecontroller/releases/download/facial-input-windows-2026-10-16/FacialInput-licenses.zip).

## Troubleshooting

- **No preview or face result:** keep output paused, check the selected camera
  number and source, then **Apply camera / retry**. In shared mode, confirm the
  compatible publisher is actively sharing. In standalone mode, check Windows
  camera privacy permissions and whether another application owns the camera.
- **Start is unavailable or stays neutral:** review calibration thresholds,
  check fresh tracking and receiver readiness, and relax to the required neutral
  position. Do not lower thresholds blindly to bypass those checks.
- **No game input:** verify exactly one matching receiver is running and ViGEmBus
  is installed. Try one simple binding in a controller tester or text editor,
  then check the intended game's focus and input settings.
- **Accidental or missing expression presses:** compare relaxed and deliberate
  scores with **Test (no output)**; review neutral, activation and release
  thresholds separately. Check overlapping phone or facial holds.
- **Settings appear different after an update:** check that you are using the
  same Windows account and the intended camera mode. Preserve the existing
  `%LOCALAPPDATA%\FacialGamingInput` folder while investigating.

This release packages the app for convenient launch. Native package diagnostics
and automated checks do not establish live camera recognition, fatigue, physical
controller recognition, or game compatibility on your setup.
