"""Shortcut entry point, with source imports and safe Windows spawn handling."""
from pathlib import Path
import multiprocessing
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

if __name__ == "__main__":
    multiprocessing.freeze_support()
    from facial_input.__main__ import main
    raise SystemExit(main())
