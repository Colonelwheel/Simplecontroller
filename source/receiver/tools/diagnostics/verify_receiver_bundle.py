"""Static archive verification; never import or run the receiver entrypoint."""
import hashlib
import json
import marshal
from pathlib import Path
import sys

from PyInstaller.archive.readers import CArchiveReader


def verify(delivery):
    report = json.loads((delivery / "build-report.json").read_text(encoding="utf-8-sig"))
    executable = Path(report["executable"]["path"])
    assert hashlib.sha256(executable.read_bytes()).hexdigest().upper() == report["executable"]["sha256"]
    archive = CArchiveReader(str(executable))
    pyz_name = next(name for name, record in archive.toc.items() if record[-1] == "z")
    pyz = archive.open_embedded_archive(pyz_name)
    verified = []
    for item in report["inputs"]:
        path = Path(item["path"])
        assert hashlib.sha256(path.read_bytes()).hexdigest().upper() == item["sha256"], str(path)
        if path.suffix == ".py":
            name = path.stem
            code = marshal.loads(archive.extract(name)) if name == "receiver_diagnostics" else pyz.extract(name)
            expected = compile(path.read_text(encoding="utf-8-sig"), code.co_filename, "exec", optimize=0)
            assert code == expected, "Bundled code differs from reviewed source: " + name
            verified.append(name)
        elif path.suffix.lower() == ".dll":
            architecture = path.parent.name
            bundled_name = next(name for name in archive.toc if name.endswith("ViGEmClient.dll") and architecture in name)
            assert hashlib.sha256(archive.extract(bundled_name)).hexdigest().upper() == item["sha256"]
            verified.append(architecture + "/ViGEmClient.dll")
    result = {"executable_sha256": report["executable"]["sha256"], "verified": verified,
              "receiver_executed": False, "hardware_game_validation": "pending"}
    with (delivery / "bundle-verification.json").open("x", encoding="utf-8") as stream:
        json.dump(result, stream, indent=2)
    print(json.dumps(result))


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
