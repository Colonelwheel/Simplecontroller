"""Inspect the new receiver archive without importing or executing receiver code."""
import hashlib
import json
import marshal
from pathlib import Path
import sys
import types

from PyInstaller.archive.readers import CArchiveReader


BASELINE_COMPANIONS = {
    'face_input_server.py': 'E79EAD00839599072A84A1F4DB2C53008CCBFD47373DF1E46500351EF7F8FB01',
    'voice_input_server.py': '1E70358ECE24BA10F376BA2697B15747395AF520A76C1C6CDDF691B8E8F64E48',
    'camera_follow.py': 'E6D19BAEA6CD56A2B9BAB9404772B01B3FCC1B5446F54270E3ACFB58762BEB28',
}


def symbols(code):
    result = set(code.co_names) | {code.co_name}
    pending = list(code.co_consts)
    while pending:
        item = pending.pop()
        if isinstance(item, types.CodeType):
            result.update(symbols(item))
        elif isinstance(item, str):
            result.add(item)
        elif isinstance(item, (tuple, frozenset)):
            pending.extend(item)
    return result


def verify(delivery):
    report = json.loads((delivery / 'build-report.json').read_text(encoding='utf-8-sig'))
    executable = Path(report['executable']['path'])
    expected_hash = report['executable']['sha256']
    assert hashlib.sha256(executable.read_bytes()).hexdigest().upper() == expected_hash
    for record in report['inputs']:
        path = Path(record['path'])
        actual = hashlib.sha256(path.read_bytes()).hexdigest().upper()
        assert actual == record['sha256'], str(path)
        if path.name in BASELINE_COMPANIONS:
            assert actual == BASELINE_COMPANIONS[path.name], f'Baseline companion changed: {path}'

    archive = CArchiveReader(str(executable))
    entry = marshal.loads(archive.extract('simple_controller_receiver'))
    assert {'GAME_DELTA:', 'handle_game_mouse_delta', 'send_relative_mouse',
            'handle_touchpad', 'release_state_lock', 'send_tagged_key',
            'effective_left_changed', 'output_packet_is_current'} <= symbols(entry)
    pyz_name = next(name for name, record in archive.toc.items() if record[-1] == 'z')
    pyz = archive.open_embedded_archive(pyz_name)
    assert {'face_input_server', 'voice_input_server', 'camera_follow', 'relative_mouse'} <= set(pyz.toc)
    face_symbols = symbols(pyz.extract('face_input_server'))
    assert {'retained_nose_movement', 'retain_on_tracking_loss', '_handle_v3',
            'movement_input', 'stick_blocked', '_source_notice', 'captured_at'} <= face_symbols
    mouse_symbols = symbols(pyz.extract('relative_mouse'))
    assert {'send_relative_mouse', 'win32_input_types', 'InputUnion', 'KeyboardInput',
            'HardwareInput', 'MOUSEEVENTF_MOVE', 'SendInput'} <= mouse_symbols
    for architecture in ('x64', 'x86'):
        bundled = next(name for name in archive.toc if 'ViGEmClient' in name
                       and architecture in name and name.endswith('.dll'))
        record = next(item for item in report['inputs'] if architecture in item['path']
                      and item['path'].endswith('.dll'))
        assert hashlib.sha256(archive.extract(bundled)).hexdigest().upper() == record['sha256']

    value = {
        'executable_sha256': expected_hash,
        'verified': ['source and DLL input hashes', 'unchanged newest facial/voice/follow companions',
                     'GAME_DELTA routing and release barrier', 'relative SendInput module and complete INPUT union',
                     'retained nose movement capability', 'v3 movement and phone source priority',
                     'x64/x86 ViGEm DLL bytes'],
        'executed': False, 'live_game_validation': 'pending',
    }
    with (delivery / 'bundle-verification.json').open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2)
    print(json.dumps(value, indent=2))


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
