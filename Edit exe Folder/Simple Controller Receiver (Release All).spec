# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['simple_controller_receiver.py'],
    pathex=[],
    binaries=[('ViGEmClient/x64/ViGEmClient.dll', 'vgamepad/win/vigem/client/x64'), ('ViGEmClient/x86/ViGEmClient.dll', 'vgamepad/win/vigem/client/x86')],
    datas=[],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
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
    name='Simple Controller Receiver (Release All)',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
