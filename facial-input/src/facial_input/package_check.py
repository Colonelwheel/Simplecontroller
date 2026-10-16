"""Explicit package diagnostics using synthetic pixels and isolated Qt only."""
from __future__ import annotations

import argparse
import hashlib
import json
import multiprocessing
import os
from pathlib import Path
import queue

MODEL_SHA256 = '64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff'


def _native_probe(model_path, results):
    try:
        import cv2
        import mediapipe as mp
        import numpy as np
        image = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.zeros((64, 64, 3), dtype=np.uint8))
        options = mp.tasks.vision.FaceLandmarkerOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=model_path,
                                             delegate=mp.tasks.BaseOptions.Delegate.CPU),
            running_mode=mp.tasks.vision.RunningMode.IMAGE,
            output_face_blendshapes=True,
        )
        with mp.tasks.vision.FaceLandmarker.create_from_options(options) as landmarker:
            result = landmarker.detect(image)
        results.put({'ok': True, 'mediapipe': mp.__version__, 'opencv': cv2.__version__,
                     'numpy': np.__version__, 'synthetic_faces': len(result.face_landmarks)})
    except BaseException as exc:
        results.put({'ok': False, 'error': f'{type(exc).__name__}: {exc}'})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', required=True, type=Path, help='New JSON report file; never overwritten')
    args = parser.parse_args(argv)
    if args.report.exists():
        parser.error('Report already exists; preserved. Choose a new report filename.')
    from .packaging_runtime import default_model_path
    model = default_model_path()
    report = {'ok': False, 'scope': 'synthetic package check; no camera, receiver or personal settings'}
    try:
        digest = hashlib.sha256(model.read_bytes()).hexdigest()
        report['model_sha256'] = digest
        if digest != MODEL_SHA256:
            raise ValueError('Bundled model SHA-256 mismatch')
        # Verify the actual frozen child executable and native ML libraries together.
        context = multiprocessing.get_context('spawn')
        results = context.Queue(maxsize=1)
        child = context.Process(target=_native_probe, args=(str(model), results))
        child.start()
        try:
            native = results.get(timeout=60)
        except queue.Empty:
            raise RuntimeError('Native model probe did not complete within 60 seconds')
        finally:
            child.join(timeout=5)
            if child.is_alive():
                child.terminate()
                child.join(timeout=5)
            results.close()
        report['native'] = native
        if not native.get('ok'):
            raise RuntimeError(native.get('error', 'Native probe failed'))
        os.environ['QT_QPA_PLATFORM'] = 'offscreen'
        from PySide6.QtWidgets import QApplication, QPushButton
        app = QApplication.instance() or QApplication(['Facial Input package check'])
        widget = QPushButton('Package check')
        widget.show()
        app.processEvents()
        widget.close()
        report['qt_platform'] = app.platformName()
        report['ok'] = True
    except Exception as exc:
        report['error'] = f'{type(exc).__name__}: {exc}'
    with args.report.open('x', encoding='utf-8') as output:
        json.dump(report, output, indent=2)
    return 0 if report['ok'] else 1
