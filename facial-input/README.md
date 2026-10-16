# Facial Gaming Input — optional source and setup

Facial expressions and optional face movement send inputs through the existing
[SimpleController receiver](../SimpleControllerReceiver.exe). This folder contains
Python source, not a packaged facial GUI EXE. It creates no additional virtual
controller. See the main [user guide](../USER_GUIDE.md) for the phone app and receiver.

The app starts paused. Calibration and **Test (no output)** send no game inputs.
Camera images are processed locally; the app does not upload or record imagery.

## Windows setup

1. Install **Windows x64 Python 3.12**. The commands below use its `py -3.12`
   launcher. Keep this folder's `src`, `scripts`, and launcher together.
2. The receiver requires the separately installed ViGEmBus driver. Its
   [driver installer](../ViGEmBus_1.22.0_x64_x86_arm64.exe) is included beside the
   receiver. The facial setup commands do not install this driver.
3. Open PowerShell in this `facial-input` folder and run these commands in order.
   Dependencies stay in this folder's `.venv`; do not copy someone else's environment.

   ```powershell
   py -3.12 -m venv .venv
   .\.venv\Scripts\python.exe -m pip install -r requirements-lock.txt
   .\.venv\Scripts\python.exe -B scripts\download_model.py
   ```

   Package installation and the explicit model download require internet access.
   Python is restricted to 3.12 by `pyproject.toml`; the pinned requirements include
   MediaPipe 0.10.21, NumPy 1.26.4, PySide6 6.11.2, and OpenCV 4.11.0.86.
4. The downloader fetches Google's Face Landmarker model into
   `models\face_landmarker.task`. No model is included in this source folder.
   It refuses to overwrite an existing file and prints its SHA-256. Compare that
   result with the expected value below before using it; the script does not
   automatically enforce this expected digest.

   ```text
   64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff
   ```

   You can also check an existing model with:

   ```powershell
   Get-FileHash .\models\face_landmarker.task -Algorithm SHA256
   ```

## Start with a standalone webcam

1. Run one copy of `SimpleControllerReceiver.exe`. Close a previous receiver
   before switching; multiple receivers use the same phone port.
2. Double-click **Start Facial Input.cmd**. Keep output paused while setting up.
3. In **Camera source**, choose **Standalone webcam**, select the camera number,
   then press **Apply camera / retry**. Use a camera available to this app; it
   cannot determine whether another application needs the same device.
4. If needed, choose capture detail and use **Select face region**. Tap one corner
   around your face, then the opposite corner. No dragging or simultaneous input
   is required. Review the optional preview for mouth and eyebrow detail.
5. Calibrate or tune thresholds, then use **Test (no output)** to check deliberate
   expressions and ordinary speech/blinking. Select the desired binding and
   Player, then use **Start** when ready. Fresh tracking, accepted thresholds,
   receiver readiness, and the applicable neutral/centering checks still apply.
6. **Pause** stops live facial output. **Save Settings** explicitly saves your
   choices. Closing the window or choosing tray **Quit** ends the app; **Minimize
   to tray** keeps it running.

Shared-camera mode is optional and needs a compatible local shared-memory camera
publisher. The safety-monitor integration is not bundled here. Standalone webcam
mode does not require that monitor, and the app never starts or stops it for you.

Settings and saved calibration/movement profiles belong to the current Windows
user under `%LOCALAPPDATA%\FacialGamingInput`. No personal settings, calibration,
images, logs, environments, or previous receiver binaries are included here.
The optional resource-test button explicitly creates local scalar diagnostics in
`private\resource-tests`, including process/resource measurements; do not publish
those files as part of the source bundle.

This source is version 0.1.0. Camera recognition, comfort, game behavior, and
performance need checking on the intended setup; copying source is not a live test.
