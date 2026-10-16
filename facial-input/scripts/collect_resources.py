"""Run independently of the GUI and Codex; outputs new scalar-only run files."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from facial_input.resource_metrics import main

if __name__ == "__main__":
    main()
