# Bundled components

FacialInput.exe includes CPython 3.12, MediaPipe, OpenCV, NumPy, PySide6/Qt,
and their required runtime libraries. Third-party copyright/license notices
collected from the installed distributions are bundled under `licenses`.
The build also writes those notices beside the EXE for distribution.

The GUI uses the community PySide6/Qt Core, Gui and Widgets libraries. These
libraries remain dynamically linked and retain their original licenses.
The matching application source, packaging recipe and pinned dependencies are
provided in the release branch's `facial-input` directory, allowing a rebuild
with replacement compatible libraries. Qt/PySide source and licensing details:

- https://download.qt.io/official_releases/QtForPython/
- https://code.qt.io/cgit/pyside/pyside-setup.git/
- https://doc.qt.io/qtforpython-6/licenses.html
- https://www.qt.io/licensing/open-source-lgpl-obligations

The bundled, unmodified Google Face Landmarker model is obtained from:

https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task

SHA-256: `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`

Official model description and component model cards:
https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker

No personal settings, calibrations, camera images or resource-test records are
included. The program does not download dependencies or models at startup.
