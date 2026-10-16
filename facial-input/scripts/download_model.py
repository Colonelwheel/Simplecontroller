"""Explicit one-time official model download; never overwrite an existing file."""
from pathlib import Path
import argparse
import hashlib
import urllib.request
import zipfile
import io

URL = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=Path(__file__).resolve().parents[1] / "models" / "face_landmarker.task")
    args = parser.parse_args()
    if args.destination.exists():
        parser.exit(1, "Destination already exists; preserved. Choose a new path or use the existing model.\n")
    with urllib.request.urlopen(URL, timeout=30) as response:
        content = response.read(20 * 1024 * 1024 + 1)
    if len(content) > 20 * 1024 * 1024 or not zipfile.is_zipfile(io.BytesIO(content)):
        parser.exit(1, "Unexpected model response; nothing saved.\n")
    args.destination.parent.mkdir(parents=True, exist_ok=True)
    with args.destination.open("xb") as destination:
        destination.write(content)
    print(f"Saved local model: {args.destination}")
    print(f"SHA256: {hashlib.sha256(content).hexdigest()}")

if __name__ == "__main__":
    main()
