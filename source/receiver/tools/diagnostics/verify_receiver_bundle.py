"""Static archive verification; never import or run the receiver entrypoint."""
import hashlib
import json
import marshal
from pathlib import Path
import sys
import types

from PyInstaller.archive.readers import CArchiveReader


REQUIRED_MODULES = {
    "receiver_diagnostics", "receiver_timing", "desktop_output",
    "simple_controller_receiver", "phone_output_protocol", "relative_mouse",
    "face_input_server", "voice_input_server", "camera_follow",
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
    report = json.loads((delivery / "build-report.json").read_text(encoding="utf-8-sig"))
    executable = Path(report["executable"]["path"])
    assert hashlib.sha256(executable.read_bytes()).hexdigest().upper() == report["executable"]["sha256"]
    archive = CArchiveReader(str(executable))
    pyz_name = next(name for name, record in archive.toc.items() if record[-1] == "z")
    pyz = archive.open_embedded_archive(pyz_name)
    source_modules = [Path(item["path"]).stem for item in report["inputs"]
                      if Path(item["path"]).suffix == ".py"]
    assert set(source_modules) == REQUIRED_MODULES, "Incomplete receiver source manifest"
    assert len(source_modules) == len(REQUIRED_MODULES), "Duplicate receiver source manifest entries"
    dll_architectures = [Path(item["path"]).parent.name for item in report["inputs"]
                         if Path(item["path"]).suffix.lower() == ".dll"]
    assert sorted(dll_architectures) == ["x64", "x86"], "Incomplete or duplicate ViGEm DLL manifest"
    verified = []
    modules = {}
    for item in report["inputs"]:
        path = Path(item["path"])
        assert hashlib.sha256(path.read_bytes()).hexdigest().upper() == item["sha256"], str(path)
        if path.suffix == ".py":
            name = path.stem
            code = marshal.loads(archive.extract(name)) if name == "receiver_diagnostics" else pyz.extract(name)
            expected = compile(path.read_text(encoding="utf-8-sig"), code.co_filename, "exec", optimize=0)
            assert code == expected, "Bundled code differs from reviewed source: " + name
            modules[name] = code
            verified.append(name)
        elif path.suffix.lower() == ".dll":
            architecture = path.parent.name
            bundled_name = next(name for name in archive.toc if name.endswith("ViGEmClient.dll") and architecture in name)
            assert hashlib.sha256(archive.extract(bundled_name)).hexdigest().upper() == item["sha256"]
            verified.append(architecture + "/ViGEmClient.dll")
    assert {"DesktopOutputBridge", "DesktopOutputError", "NativeDesktopBackend",
            "worker_main"} <= symbols(modules["desktop_output"])
    assert {"--desktop-output-worker", "worker_main"} <= symbols(modules["receiver_diagnostics"])
    result = {"executable_sha256": report["executable"]["sha256"], "verified": verified,
              "receiver_executed": False, "hardware_game_validation": "pending"}
    with (delivery / "bundle-verification.json").open("x", encoding="utf-8") as stream:
        json.dump(result, stream, indent=2)
    print(json.dumps(result))


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
