"""Owned inference process; camera access is isolated in an optional child."""
from __future__ import annotations

import math
from pathlib import Path
import queue
import time

def put_latest(output, value):
    try:
        output.put_nowait(value)
    except queue.Full:
        try:
            output.get_nowait()
        except queue.Empty:
            pass
        try:
            output.put_nowait(value)
        except queue.Full:
            pass

def scores_from_blendshapes(categories):
    values = {c.category_name: float(c.score) for c in categories}
    required = ("jawOpen", "browInnerUp", "browOuterUpLeft", "browOuterUpRight")
    if not all(k in values and math.isfinite(values[k]) and 0 <= values[k] <= 1 for k in required):
        return {}
    return {"mouth": values["jawOpen"], "brows": max(values["browInnerUp"], (values["browOuterUpLeft"] + values["browOuterUpRight"]) / 2)}

def wink_score_from_blendshapes(categories):
    """Subject-left wink; reject bilateral blink without invalidating old scores."""
    try:
        values = {c.category_name: float(c.score) for c in categories}
        left, right = values.get("eyeBlinkLeft"), values.get("eyeBlinkRight")
        if not all(isinstance(v, (int,float)) and math.isfinite(v) and 0 <= v <= 1 for v in (left,right)):
            return None
        # An ordinary blink is a valid neutral wink result, not tracking loss.
        return left if right <= .30 else 0.
    except (ValueError, TypeError, AttributeError, OverflowError):
        return None

def features_from_landmarks(landmarks, image_shape):
    """Bounded scalar-only features in fixed crop geometry, never full landmarks.

    Both coordinate axes use image-height units, avoiding crop aspect distortion.
    Lip/cheek features subtract local facial references and rotate along the eye
    line; common head translation and in-plane roll therefore cancel.
    """
    try:
        height, width = image_shape[:2]
        if height <= 0 or width <= 0: return {}
        aspect = width/height
        def point(index):
            p = landmarks[index]
            x,y = float(p.x)*aspect, float(p.y)
            if not math.isfinite(x) or not math.isfinite(y) or not -2 <= x <= 4 or not -2 <= y <= 4: raise ValueError("Invalid landmark")
            return (x,y)
        # Face ends near both temples, stable scale; nose group reduces one-point noise.
        left,right = point(33),point(263)
        dx,dy = right[0]-left[0],right[1]-left[1]
        scale = math.hypot(dx,dy)
        if not .0001 <= scale <= 4: return {}
        roll = math.atan2(dy,dx)
        noses = [point(i) for i in (1,4,5)]
        features = {"nose_x":sum(p[0] for p in noses)/3, "nose_y":sum(p[1] for p in noses)/3,
                    "face_scale":scale,"face_roll":roll}
        def relative_x(subject, references):
            p = point(subject)
            refs = [point(i) for i in references]
            rx,ry = sum(r[0] for r in refs)/len(refs),sum(r[1] for r in refs)/len(refs)
            return ((p[0]-rx)*math.cos(roll)+(p[1]-ry)*math.sin(roll))/scale
        for name, compute in (("lip_x",lambda: relative_x(0,(168,6))),
                              ("cheek_x",lambda: (relative_x(50,(127,33))+relative_x(280,(356,263)))/2)):
            try:
                value = compute()
                if math.isfinite(value) and -4 <= value <= 4: features[name] = value
            except (ValueError, TypeError, AttributeError, IndexError, OverflowError): pass
        if "lip_x" in features and "cheek_x" in features:
            features["combined_x"] = (features["lip_x"]+features["cheek_x"])/2
        return features
    except (ValueError, TypeError, AttributeError, IndexError, OverflowError):
        return {}

def inference_worker(output, stop, preview, fps_value, model_path, feed_name,
                     camera_source="shared", camera_index=0, camera_resolution="640x480", camera_roi=None):
    from .camera_source import WebcamSource, below_normal_priority
    below_normal_priority()
    from .frame_feed import SharedFrameReader
    reader = None
    landmarker = None
    try:
        if camera_source not in ("shared", "webcam"):
            raise ValueError("Camera source must be shared or webcam")
        if not model_path or not Path(model_path).is_file():
            put_latest(output, {"kind": "fault", "detail": "Choose the local Face Landmarker model; no downloads occur at launch."})
            stop.wait()
            return
        import numpy as np
        import mediapipe as mp
        options = mp.tasks.vision.FaceLandmarkerOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=str(model_path), delegate=mp.tasks.BaseOptions.Delegate.CPU),
            running_mode=mp.tasks.vision.RunningMode.VIDEO,
            num_faces=1, output_face_blendshapes=True,
            output_facial_transformation_matrixes=False,
            min_face_detection_confidence=.5,
            min_face_presence_confidence=.5,
            min_tracking_confidence=.5)
        landmarker = mp.tasks.vision.FaceLandmarker.create_from_options(options)
        reader = (SharedFrameReader(feed_name) if camera_source == "shared"
                  else WebcamSource(camera_index, fps_value, camera_resolution, camera_roi))
        last_identity = None
        last_generation = None
        last_stamp = -1
        next_frame = 0.0
        last_status = None
        while not stop.is_set():
            now = time.monotonic()
            if now < next_frame:
                stop.wait(min(.020, next_frame - now))
                continue
            packet = reader.read_latest()
            if packet is None:
                if getattr(reader, "failure", None):
                    put_latest(output, {"kind": "fault", "detail": reader.failure,
                                        "capture_info": dict(getattr(reader, "metadata", {}))})
                    break
                if reader.status != last_status:
                    put_latest(output, {"kind": "camera", "detail": reader.status,
                                        "capture_info": dict(getattr(reader, "metadata", {}))})
                    last_status = reader.status
                stop.wait(.030)
                continue
            identity = (packet.session_id, packet.camera_generation, packet.scene_generation, packet.sequence)
            if identity == last_identity:
                stop.wait(.005)
                continue
            last_identity = identity
            if last_generation is not None and identity[:3] != last_generation:
                # Discard native tracking/smoothing from a previous face crop.
                previous, landmarker = landmarker, None
                previous.close()
                landmarker = mp.tasks.vision.FaceLandmarker.create_from_options(options)
                last_stamp = -1
            last_generation = identity[:3]
            last_status = "ready"
            next_frame = now + 1 / max(10, min(30, fps_value.value))
            # No horizontal mirroring: blendshapes use the subject's left/right.
            rgb = np.ascontiguousarray(packet.image[:, :, ::-1])
            timestamp = max(last_stamp + 1, int(packet.captured_at * 1000))
            last_stamp = timestamp
            started = time.perf_counter()
            result = landmarker.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), timestamp)
            scores = scores_from_blendshapes(result.face_blendshapes[0]) if len(result.face_blendshapes) == 1 else {}
            if len(result.face_blendshapes) == 1:
                wink = wink_score_from_blendshapes(result.face_blendshapes[0])
                if wink is not None: scores["wink"] = wink
            features = features_from_landmarks(result.face_landmarks[0], rgb.shape) if len(result.face_landmarks) == 1 else {}
            put_latest(output, {"kind": "result", "scores": scores,
                "features": features,
                "sequence": packet.sequence, "captured_at": packet.captured_at,
                "generation": identity[:3], "inference_ms": (time.perf_counter() - started) * 1000,
                "capture_info": dict(getattr(reader, "metadata", {})),
                "frame": packet.image if preview.is_set() else None})
    except Exception as exc:
        put_latest(output, {"kind": "fault", "detail": f"Facial inference unavailable: {type(exc).__name__}: {exc}"})
    finally:
        try:
            if reader is not None:
                reader.close()
        finally:
            try:
                if landmarker is not None:
                    landmarker.close()
            finally:
                output.cancel_join_thread()

def demo_worker(output, stop, preview, fps_value):
    """Explicit synthetic UI demo, with no camera or receiver access."""
    import numpy as np
    count = 0
    start = time.monotonic()
    while not stop.is_set():
        now = time.monotonic()
        count += 1
        phase = (now - start) % 6
        value = .8 if 2 <= phase < 3 else .08
        frame = np.zeros((240, 320, 3), dtype=np.uint8) if preview.is_set() else None
        put_latest(output, {"kind": "result", "scores": {"mouth": value, "brows": value * .9},
            "sequence": count, "captured_at": now, "generation": ("DEMO", 0, 0), "inference_ms": 0.0, "frame": frame})
        stop.wait(1 / fps_value.value)
    output.cancel_join_thread()
