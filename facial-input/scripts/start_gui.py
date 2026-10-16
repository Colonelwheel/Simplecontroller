"""Shortcut entry point, with source imports and safe Windows spawn handling."""
from pathlib import Path
import multiprocessing
import sys

if not getattr(sys, "frozen", False):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

if __name__ == "__main__":
    multiprocessing.freeze_support()
    if sys.argv[1:2] == ["--check-package"]:
        from facial_input.package_check import main as check_package
        raise SystemExit(check_package(sys.argv[2:]))
    from facial_input.__main__ import main
    raise SystemExit(main())
