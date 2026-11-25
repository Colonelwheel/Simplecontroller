"""Inspect the new receiver archive without importing or executing receiver code."""
import hashlib
import json
import marshal
from pathlib import Path
import sys
import types

from PyInstaller.archive.readers import CArchiveReader


# Facial/voice baselines include the approved hang-diagnostic instrumentation.
BASELINE_COMPANIONS = {
    'face_input_server.py': '3FE8864EDD5C14F5C3FC9BF43F0C3194093207150B8142E7178DE12E3D44C039',
    'voice_input_server.py': '3C6928B57D23050B69806555EEA74F8A9D94A12CED8B006D39A4E6CCB22746D9',
    'camera_follow.py': '88E472DA732D8E5FC5649FB5ECB262C86CCEF950621B451D93DAD64C9E059207',
}


def semantic(value):
    """Compare executable code while allowing build paths and line tables to differ."""
    if isinstance(value, types.CodeType):
        return (value.co_code, semantic(value.co_consts), value.co_names,
                value.co_varnames, value.co_freevars, value.co_cellvars,
                value.co_argcount, value.co_posonlyargcount, value.co_kwonlyargcount,
                value.co_flags, value.co_exceptiontable)
    if isinstance(value, tuple):
        return tuple(semantic(item) for item in value)
    if isinstance(value, frozenset):
        return frozenset(semantic(item) for item in value)
    return value


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
    wrapper = marshal.loads(archive.extract('receiver_diagnostics'))
    pyz_name = next(name for name, record in archive.toc.items() if record[-1] == 'z')
    pyz = archive.open_embedded_archive(pyz_name)
    modules = {'receiver_diagnostics': wrapper}
    for name in ('face_input_server', 'voice_input_server', 'camera_follow', 'relative_mouse', 'desktop_output',
                 'phone_output_protocol', 'simple_controller_receiver', 'receiver_timing'):
        assert name in pyz.toc, name
        modules[name] = pyz.extract(name)
    for name, bundled in modules.items():
        source = delivery / 'source' / (name + '.py')
        expected = [compile(source.read_bytes(), str(source), 'exec', optimize=level)
                    for level in (0, 1, 2)]
        assert any(semantic(bundled) == semantic(code) for code in expected), name
    source_names = [Path(record['path']).stem for record in report['inputs']
                    if Path(record['path']).suffix == '.py']
    assert set(source_names) == set(modules), 'Incomplete receiver source manifest'
    assert len(source_names) == len(modules), 'Duplicate receiver source manifest entries'
    dll_architectures = [Path(record['path']).parent.name for record in report['inputs']
                         if Path(record['path']).suffix.lower() == '.dll']
    assert sorted(dll_architectures) == ['x64', 'x86'], 'Incomplete or duplicate ViGEm DLL manifest'
    assert {'SC_RECEIVER_TIMING', 'SC_RECEIVER_TIMING_DIR', 'hang-diagnostics',
            'run_module', 'simple_controller_receiver'} <= symbols(wrapper)
    assert {'--desktop-output-worker', 'worker_main'} <= symbols(wrapper)
    assert {'DesktopOutputBridge', 'DesktopOutputError', 'NativeDesktopBackend',
            'worker_main'} <= symbols(modules['desktop_output'])
    assert {'TimingRecorder', 'observe_lock', '_watchdog_main', '_writer_main',
            '_rotate'} <= symbols(modules['receiver_timing'])
    assert {'GAME_DELTA:', 'handle_game_mouse_delta', 'send_relative_mouse',
            'handle_touchpad', 'release_state_lock', 'send_tagged_key',
            'effective_left_changed', 'output_packet_is_current'} <= symbols(modules['simple_controller_receiver'])
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
        'verified': ['all nine bundled modules match build source semantically',
                     'diagnostic startup wrapper and bounded timing recorder present',
                     'isolated desktop-output helper and early worker dispatch present',
                     'source and DLL input hashes', 'approved instrumented facial/voice and preserved follow companions',
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
