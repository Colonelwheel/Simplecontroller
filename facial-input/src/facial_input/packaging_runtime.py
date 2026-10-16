"""Frozen-app paths and independent collector launch details; no native startup."""
from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import sys
from typing import Sequence


def _source_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


def default_model_path() -> Path:
    """Locate read-only bundled data without depending on the working directory."""
    root = (Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
            if _frozen() else _source_root())
    return root / "models" / "face_landmarker.task"


def resolve_model_path(explicit: Path | None, saved: str | None) -> Path | None:
    """Explicit/saved custom selections retain precedence, including missing files."""
    selected = explicit if explicit is not None else (Path(saved) if saved else None)
    if selected is not None:
        return selected
    bundled = default_model_path()
    return bundled if bundled.is_file() else None


def model_path_for_settings(path: Path | None) -> str | None:
    """Do not persist a frozen bundle's launch-specific extraction directory."""
    if path is None:
        return None
    if _frozen() and Path(path).resolve() == default_model_path().resolve():
        return None
    return str(path)


@dataclass(frozen=True)
class CollectorLaunch:
    command: list[str]
    cwd: Path
    output_root: Path
    environment: dict[str, str] | None


def resource_collector_launch(arguments: Sequence[str], settings_path: Path) -> CollectorLaunch:
    """An explicitly requested collector outlives the GUI and writes new run files."""
    if _frozen():
        output_root = Path(settings_path).absolute().parent / "resource-tests"
        command = [sys.executable, "--collect-resources"]
        cwd = Path(sys.executable).resolve().parent
        # A one-file collector must own its extraction directory after the GUI exits.
        environment = {**os.environ, "PYINSTALLER_RESET_ENVIRONMENT": "1"}
    else:
        cwd = _source_root()
        output_root = cwd / "private" / "resource-tests"
        command = [sys.executable, "-B", str(cwd / "scripts" / "collect_resources.py")]
        environment = None
    command.extend(("--output-root", str(output_root)))
    command.extend(arguments)
    return CollectorLaunch(command, cwd, output_root, environment)


def prepare_resource_collector() -> None:
    """Restore normal Windows DLL lookup only in the independent frozen collector."""
    if not _frozen() or sys.platform != "win32":
        return
    import ctypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    set_directory = kernel.SetDllDirectoryW
    set_directory.argtypes = (ctypes.c_wchar_p,)
    set_directory.restype = ctypes.c_int
    if not set_directory(None):
        raise ctypes.WinError(ctypes.get_last_error())
