"""Separate diagnostic executable entrypoint; normal receiver imports stay off."""
import os
from pathlib import Path
import runpy
import sys


def main():
    os.environ["SC_RECEIVER_TIMING"] = "1"
    if "SC_RECEIVER_TIMING_DIR" not in os.environ:
        base = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
        os.environ["SC_RECEIVER_TIMING_DIR"] = str(base / "timing-logs")
    # Import only after enabling this separate process's diagnostics. There is no
    # persistent setting change, and no receiver is imported by static checks.
    from receiver_timing import recorder
    recorder.start()
    try:
        runpy.run_module("simple_controller_receiver", run_name="__main__")
    finally:
        recorder.close()


if __name__ == "__main__":
    main()
