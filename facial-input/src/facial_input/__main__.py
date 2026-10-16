"""Desktop entry point. Downloads and camera capture are never implicit."""
from __future__ import annotations

import argparse
from dataclasses import replace
from pathlib import Path
import sys


def main(argv=None):
    parser = argparse.ArgumentParser(description="Local facial controller input using shared frames or a standalone webcam")
    parser.add_argument("--model", type=Path, help="Local MediaPipe Face Landmarker .task model")
    parser.add_argument("--feed-name", help="Explicit local shared-memory camera feed name")
    parser.add_argument("--settings", type=Path, help="Append-only settings file")
    parser.add_argument("--camera-source", choices=("shared", "webcam"), help="Explicit camera source (otherwise saved preference)")
    parser.add_argument("--camera-index", type=int, help="Standalone webcam number, 0 through 20")
    parser.add_argument("--camera-resolution", choices=("640x480", "1280x720", "1920x1080"),
                        help="Standalone capture detail; inference still uses a bounded face crop")
    parser.add_argument("--demo", action="store_true", help="Synthetic UI demo: no camera, controller, or inference")
    parser.add_argument("--start-minimized", action="store_true")
    args = parser.parse_args(argv)
    from PySide6.QtWidgets import QApplication, QMessageBox
    from .settings import default_settings_path, load_settings
    from .gui import MainWindow
    app = QApplication(sys.argv[:1])
    app.setApplicationName("Facial Input")
    app.setOrganizationName("Tyler Accessibility")
    settings_path = args.settings or default_settings_path()
    try:
        settings = load_settings(settings_path)
        if args.camera_source is not None:
            settings = replace(settings, camera_source=args.camera_source)
        if args.camera_index is not None:
            settings = replace(settings, camera_index=args.camera_index)
        if args.camera_resolution is not None:
            settings = replace(settings, camera_resolution=args.camera_resolution)
        settings.validate()
        model_path = args.model or (Path(settings.model_path) if settings.model_path else None)
        bundled_model = Path(__file__).resolve().parents[2] / "models" / "face_landmarker.task"
        if model_path is None and bundled_model.is_file():
            model_path = bundled_model
        window = MainWindow(settings, model_path=model_path, feed_name=args.feed_name,
                            settings_path=settings_path, demo=args.demo)
    except (OSError, ValueError) as exc:
        QMessageBox.critical(None, "Facial Input could not start", str(exc))
        return 2
    app.aboutToQuit.connect(window.shutdown)
    window.show()
    if args.start_minimized:
        window.minimize_to_tray()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
