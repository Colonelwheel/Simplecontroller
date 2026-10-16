# Rebuild the supplied receiver from this directory with PyInstaller.
from pathlib import Path

root = Path(SPECPATH)
a = Analysis(
    [str(root / 'simple_controller_receiver.py')],
    pathex=[str(root)],
    binaries=[
        (str(root / 'ViGEmClient/x64/ViGEmClient.dll'), 'vgamepad/win/vigem/client/x64'),
        (str(root / 'ViGEmClient/x86/ViGEmClient.dll'), 'vgamepad/win/vigem/client/x86'),
    ],
    datas=[],
    hiddenimports=['phone_output_protocol', 'relative_mouse', 'face_input_server', 'voice_input_server', 'camera_follow'],
    hookspath=[],
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='SimpleControllerReceiver',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
)
