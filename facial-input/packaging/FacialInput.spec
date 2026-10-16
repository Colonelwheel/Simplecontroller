# Build with scripts/build_windows.py; runtime data stays outside the source tree.
from pathlib import Path
import os
import importlib.util
from PyInstaller.utils.hooks import collect_data_files, collect_dynamic_libs

project = Path(SPECPATH).parent
model = Path(os.environ.get('FACIAL_MODEL_FILE', project / 'models' / 'face_landmarker.task'))
notices = Path(os.environ['FACIAL_LICENSE_DIRECTORY'])
qt_root = Path(importlib.util.find_spec('PySide6').origin).parent
# Load the same, newer MSVC runtime as Qt before Python's older bundled copy.
# The wheel supplies these redistributable DLLs; no system install is changed.
qt_runtime = [(str(qt_root / name), '.') for name in
              ('VCRUNTIME140.dll', 'VCRUNTIME140_1.dll', 'MSVCP140.dll',
               'MSVCP140_1.dll', 'MSVCP140_2.dll') if (qt_root / name).is_file()]

data = collect_data_files('mediapipe')
data += [(str(model), 'models'), (str(notices), 'licenses')]
data += [(str(project / 'packaging' / 'THIRD_PARTY_NOTICES.md'), '.')]

a = Analysis(
    [str(project / 'scripts' / 'start_gui.py')],
    pathex=[str(project / 'src')],
    binaries=qt_runtime + collect_dynamic_libs('mediapipe'),
    datas=data,
    hiddenimports=['mediapipe.tasks.python.vision.face_landmarker',
                   'mediapipe.python._framework_bindings',
                   'facial_input.package_check'],
    hookspath=[],
    hooksconfig={'matplotlib': {'backends': ['Agg']}},
    runtime_hooks=[],
    excludes=['PyQt5', 'PyQt6', 'PySide2', 'pytest', 'IPython', 'notebook'],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, a.binaries, a.datas, [],
    name='FacialInput', debug=False, bootloader_ignore_signals=False,
    strip=False, upx=False, console=False,
    disable_windowed_traceback=False,
)
