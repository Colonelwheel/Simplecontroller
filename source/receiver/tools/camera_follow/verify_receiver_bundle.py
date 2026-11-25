"""Inspect the receiver bundle without executing native inputs or receiver code."""
import hashlib
import json
import marshal
from pathlib import Path
import sys
import types

from PyInstaller.archive.readers import CArchiveReader

# Facial/voice baselines include the approved hang-diagnostic instrumentation.
PRESERVED_COMPANIONS = {
    'face_input_server.py': '3FE8864EDD5C14F5C3FC9BF43F0C3194093207150B8142E7178DE12E3D44C039',
    'voice_input_server.py': '3C6928B57D23050B69806555EEA74F8A9D94A12CED8B006D39A4E6CCB22746D9',
    'relative_mouse.py': 'FB72916E56614351197017F829F10F5A3F619B91C06CC0D806B695CDAC253438',
}


def digest(blob):
    return hashlib.sha256(blob).hexdigest().upper()


def semantic(value):
    """Compare executable code, allowing build paths and debug line tables to differ."""
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
    exe = Path(report['executable']['path'])
    assert digest(exe.read_bytes()) == report['executable']['sha256']
    inputs = report['inputs']
    for record in inputs:
        path = Path(record['path'])
        assert digest(path.read_bytes()) == record['sha256'], str(path)
        if path.name in PRESERVED_COMPANIONS:
            assert record['sha256'] == PRESERVED_COMPANIONS[path.name], str(path)

    archive = CArchiveReader(str(exe))
    wrapper = marshal.loads(archive.extract('receiver_diagnostics'))
    pyz_name = next(name for name, record in archive.toc.items() if record[-1] == 'z')
    pyz = archive.open_embedded_archive(pyz_name)
    modules = {'receiver_diagnostics': wrapper}
    for name in ('face_input_server', 'voice_input_server', 'relative_mouse', 'camera_follow',
                 'phone_output_protocol', 'simple_controller_receiver', 'receiver_timing', 'mouse_output'):
        assert name in pyz.toc, name
        modules[name] = pyz.extract(name)
    for name, bundled in modules.items():
        source = delivery / 'source' / (name + '.py')
        expected = [compile(source.read_bytes(), str(source), 'exec', optimize=level)
                    for level in (0, 1, 2)]
        assert any(semantic(bundled) == semantic(code) for code in expected), name
    assert {'SC_RECEIVER_TIMING', 'SC_RECEIVER_TIMING_DIR', 'hang-diagnostics',
            'run_module', 'simple_controller_receiver'} <= symbols(wrapper)
    assert {'--mouse-output-worker', 'worker'} <= symbols(wrapper)
    assert {'MouseOutput', 'worker'} <= symbols(modules['mouse_output'])
    assert {'TimingRecorder', 'observe_lock', '_watchdog_main', '_writer_main',
            '_rotate'} <= symbols(modules['receiver_timing'])
    entry_symbols = symbols(modules['simple_controller_receiver'])
    assert {'GAME_DELTA:', 'handle_game_mouse_delta', 'send_relative_mouse',
            'effective_left_changed', 'output_packet_is_current'} <= entry_symbols
    assert any('STICK_L_NO_FOLLOW' in value for value in entry_symbols)
    assert any('CAMERA_FOLLOW_STRENGTH:' in value for value in entry_symbols)
    assert {'retained_nose_movement', 'retain_on_tracking_loss', '_handle_v3',
            'movement_input', 'stick_blocked', '_source_notice', 'captured_at'} <= symbols(modules['face_input_server'])
    for architecture in ('x64', 'x86'):
        name = next(name for name in archive.toc if 'ViGEmClient' in name
                    and architecture in name and name.endswith('.dll'))
        source = next(record for record in inputs if architecture in record['path']
                      and record['path'].endswith('.dll'))
        assert digest(archive.extract(name)) == source['sha256']
    value = {'executable_sha256': report['executable']['sha256'],
             'verified': ['all nine bundled modules match build source semantically',
                          'diagnostic startup wrapper and bounded timing recorder present',
                          'approved instrumented facial/voice and preserved Game Mouse companions',
                          'Camera Follow exclusion and live strength protocol present',
                          'Game Mouse routing and ordering barrier present',
                          'x64 and x86 ViGEm DLL bytes match'],
             'receiver_executed': False, 'live_game_validation': 'pending'}
    with (delivery / 'bundle-verification.json').open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2)
    print(json.dumps(value, indent=2))


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
