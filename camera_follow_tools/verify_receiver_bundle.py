"""Inspect the receiver bundle without executing native inputs or receiver code."""
import hashlib
import json
import marshal
from pathlib import Path
import sys
import types

from PyInstaller.archive.readers import CArchiveReader

PRESERVED_COMPANIONS = {
    'face_input_server.py': 'E79EAD00839599072A84A1F4DB2C53008CCBFD47373DF1E46500351EF7F8FB01',
    'voice_input_server.py': '1E70358ECE24BA10F376BA2697B15747395AF520A76C1C6CDDF691B8E8F64E48',
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
    entry = marshal.loads(archive.extract('simple_controller_receiver'))
    pyz_name = next(name for name, record in archive.toc.items() if record[-1] == 'z')
    pyz = archive.open_embedded_archive(pyz_name)
    modules = {'simple_controller_receiver': entry}
    for name in ('face_input_server', 'voice_input_server', 'relative_mouse', 'camera_follow'):
        assert name in pyz.toc, name
        modules[name] = pyz.extract(name)
    for name, bundled in modules.items():
        source = delivery / 'source' / (name + '.py')
        expected = [compile(source.read_bytes(), str(source), 'exec', optimize=level)
                    for level in (0, 1, 2)]
        assert any(semantic(bundled) == semantic(code) for code in expected), name
    entry_symbols = symbols(entry)
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
             'verified': ['all five bundled modules match build source semantically',
                          'preserved retained-nose facial, voice, and Game Mouse companions',
                          'Camera Follow exclusion and live strength protocol present',
                          'Game Mouse routing and ordering barrier present',
                          'x64 and x86 ViGEm DLL bytes match'],
             'receiver_executed': False, 'live_game_validation': 'pending'}
    with (delivery / 'bundle-verification.json').open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2)
    print(json.dumps(value, indent=2))


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
