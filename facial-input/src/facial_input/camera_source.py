"""Explicit standalone camera ownership, isolated from native inference and UI.

Only the capture child opens a webcam. Pixels use the existing fixed two-slot
shared feed; the scalar status queue has one slot. Camera failures never reopen
the device or fall back to another source. Timestamp is read-return time, not a
hardware exposure timestamp; backend buffering age cannot be established here.
"""
from __future__ import annotations

import ctypes
import math
import multiprocessing as mp
import os
import queue
import time
import uuid

from .frame_feed import MAX_HEIGHT, MAX_WIDTH, SharedFramePublisher, SharedFrameReader

OPEN_TIMEOUT = 20.0
STALL_TIMEOUT = 2.0
CAMERA_RESOLUTIONS = {"640x480": (640, 480), "1280x720": (1280, 720),
                      "1920x1080": (1920, 1080)}


def validate_camera_roi(roi):
    """Normalize a proportional x/y/width/height rectangle within the frame."""
    if roi is None:
        return None
    if (not isinstance(roi, (tuple, list)) or len(roi) != 4
            or any(isinstance(v, bool) or not isinstance(v, (int, float))
                   or not math.isfinite(v) for v in roi)):
        raise ValueError("Camera face region must contain four finite normalized numbers")
    x, y, width, height = map(float, roi)
    if (x < 0 or y < 0 or width < .02 or height < .02
            or x + width > 1 + 1e-9 or y + height > 1 + 1e-9):
        raise ValueError("Camera face region must fit inside the full frame")
    return (x, y, min(width, 1 - x), min(height, 1 - y))


def _validate_camera_options(camera_index, camera_resolution, camera_roi):
    if isinstance(camera_index, bool) or not isinstance(camera_index, int) or not 0 <= camera_index <= 20:
        raise ValueError("Camera index must be an integer from 0 to 20")
    if not isinstance(camera_resolution, str) or camera_resolution not in CAMERA_RESOLUTIONS:
        raise ValueError("Camera resolution must be 640x480, 1280x720 or 1920x1080")
    return validate_camera_roi(camera_roi)


def below_normal_priority():
    if os.name == "nt":
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.GetCurrentProcess.restype = ctypes.c_void_p
        kernel.SetPriorityClass.argtypes = (ctypes.c_void_p, ctypes.c_ulong)
        kernel.SetPriorityClass(kernel.GetCurrentProcess(), 0x4000)


class _CaptureJob:
    """Windows kernel closes the camera child when its inference owner exits."""

    def __init__(self, process):
        self._handle = None
        if os.name != "nt":
            return
        from ctypes import wintypes

        class Basic(ctypes.Structure):
            _fields_ = [("process_time", ctypes.c_longlong), ("job_time", ctypes.c_longlong),
                        ("flags", wintypes.DWORD), ("min_ws", ctypes.c_size_t),
                        ("max_ws", ctypes.c_size_t), ("active", wintypes.DWORD),
                        ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD),
                        ("scheduling", wintypes.DWORD)]

        class Counters(ctypes.Structure):
            _fields_ = [(name, ctypes.c_ulonglong) for name in
                        ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]

        class Limits(ctypes.Structure):
            _fields_ = [("basic", Basic), ("io", Counters),
                        ("process_memory", ctypes.c_size_t), ("job_memory", ctypes.c_size_t),
                        ("peak_process", ctypes.c_size_t), ("peak_job", ctypes.c_size_t)]

        kernel = self._kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.CreateJobObjectW.argtypes = (ctypes.c_void_p, wintypes.LPCWSTR)
        kernel.CreateJobObjectW.restype = wintypes.HANDLE
        kernel.SetInformationJobObject.argtypes = (wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD)
        kernel.AssignProcessToJobObject.argtypes = (wintypes.HANDLE, wintypes.HANDLE)
        kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
        self._handle = kernel.CreateJobObjectW(None, None)
        if not self._handle:
            raise ctypes.WinError(ctypes.get_last_error())
        process_handle = None
        try:
            limits = Limits()
            limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            if not kernel.SetInformationJobObject(self._handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
                raise ctypes.WinError(ctypes.get_last_error())
            process_handle = kernel.OpenProcess(0x0101, False, process.pid)  # SET_QUOTA | TERMINATE
            if not process_handle or not kernel.AssignProcessToJobObject(self._handle, process_handle):
                raise ctypes.WinError(ctypes.get_last_error())
        except BaseException:
            self.close()
            raise
        finally:
            if process_handle:
                kernel.CloseHandle(process_handle)

    def close(self):
        if self._handle is not None:
            self._kernel.CloseHandle(self._handle)
            self._handle = None


def _status_priority(item):
    if isinstance(item, dict):
        if item.get("kind") == "progress":
            return 0
        if item.get("kind") == "info":
            return 1
    return 2  # Faults outrank diagnostics; preserve the first failure.


def _put_status(status, item):
    if status is None:
        return
    try:
        status.put_nowait(item)
    except queue.Full:
        # One scalar slot, with bounded replacement. Progress must never hide
        # metadata or faults; replacing progress also preserves the latest phase.
        try:
            old = status.get(timeout=.025)
        except queue.Empty:
            return
        old_priority, new_priority = _status_priority(old), _status_priority(item)
        if new_priority > old_priority or (new_priority == old_priority and new_priority < 2):
            old = item
        try:
            status.put_nowait(old)
        except queue.Full:
            pass


def _notify(status, detail):
    _put_status(status, str(detail)[:500])


def _progress(status, phase):
    _put_status(status, {"kind": "progress", "detail": str(phase)[:100]})


def _inform(status, metadata):
    _put_status(status, {"kind": "info", "detail": metadata})


def _capture_metadata(camera, cv2, requested_resolution):
    def finite_property(prop):
        try:
            value = float(camera.get(prop))
        except Exception:
            return None
        return value if math.isfinite(value) and value > 0 else None
    fps = finite_property(cv2.CAP_PROP_FPS)
    encoded = finite_property(cv2.CAP_PROP_FOURCC)
    fourcc = None
    if encoded is not None and encoded <= 0xffffffff:
        letters = "".join(chr((int(encoded) >> (8 * n)) & 0xff) for n in range(4))
        if all(32 <= ord(letter) < 127 for letter in letters):
            fourcc = letters
    return {"requested_resolution": requested_resolution, "source_fps": fps,
            "source_fourcc": fourcc}


def _bounded_frame(frame, cv2, camera_roi=None):
    import numpy as np
    if (not isinstance(frame, np.ndarray) or frame.dtype != np.uint8
            or frame.ndim != 3 or frame.shape[2] != 3 or min(frame.shape[:2]) < 1):
        raise ValueError("Webcam supplied an invalid BGR frame")
    height, width = frame.shape[:2]
    roi = validate_camera_roi(camera_roi)
    if roi is not None:
        x, y, roi_width, roi_height = roi
        left = min(width - 1, max(0, math.floor(x * width + 1e-9)))
        top = min(height - 1, max(0, math.floor(y * height + 1e-9)))
        right = min(width, max(left + 1, math.ceil((x + roi_width) * width - 1e-9)))
        bottom = min(height, max(top + 1, math.ceil((y + roi_height) * height - 1e-9)))
        frame = frame[top:bottom, left:right]
        height, width = frame.shape[:2]
    scale = min(1.0, MAX_WIDTH / width, MAX_HEIGHT / height)
    if scale < 1:
        frame = cv2.resize(frame, (max(1, int(width * scale)), max(1, int(height * scale))),
                           interpolation=cv2.INTER_AREA)
    return np.ascontiguousarray(frame)


def _capture_frames(camera, publisher, stop, fps_value, cv2, camera_roi=None,
                    metadata=None, status=None):
    camera_roi = validate_camera_roi(camera_roi)
    sequence = 0
    next_publish = 0.0
    reported_dimensions = None
    scene_generation = 1
    _progress(status, "waiting for first camera frame")
    while not stop.is_set():
        ok, frame = camera.read()  # Potentially blocking native call, only in child.
        captured_at = time.monotonic()
        if stop.is_set():
            break
        if not ok or frame is None:
            if sequence == 0:
                # Some drivers return empty reads while warming up. Only the
                # owner's original 20-second deadline permits these retries.
                # Once a valid frame is published, delivery failures are final.
                stop.wait(.030)
                continue
            raise OSError("Webcam stopped delivering frames; select the camera source again to retry")
        # Drain at the driver's delivery pace even if its FPS request is ignored.
        # Sleeping between reads could accumulate old buffered frames. Only
        # published pixel copies and inference are limited to the requested cap.
        if captured_at < next_publish:
            stop.wait(.002)  # Bound pathological immediate-return virtual drivers.
            continue
        source_dimensions = frame.shape[:2] if hasattr(frame, "shape") else None
        frame = _bounded_frame(frame, cv2, camera_roi)
        if source_dimensions != reported_dimensions:
            if reported_dimensions is not None:
                scene_generation += 1
            reported_dimensions = source_dimensions
            _inform(status, {**(metadata or {}), "source_width": source_dimensions[1],
                "source_height": source_dimensions[0], "feed_width": frame.shape[1],
                "feed_height": frame.shape[0], "cropped": camera_roi is not None})
        sequence += 1
        publisher.publish(frame, captured_at, sequence, 1, scene_generation)
        fps = max(10, min(30, fps_value.value))
        next_publish = captured_at + 1 / fps
        stop.wait(.002)


def _capture_worker(feed_name, camera_index, stop, allowed, fps_value, status,
                    camera_resolution="640x480", camera_roi=None):
    publisher = camera = None
    try:
        camera_roi = _validate_camera_options(camera_index, camera_resolution, camera_roi)
        # Parent must establish kill-on-owner-exit protection before camera open.
        if not allowed.wait(3.0) or stop.is_set():
            return
        below_normal_priority()
        _progress(status, "importing OpenCV")
        import cv2
        _progress(status, "configuring OpenCV threads")
        cv2.setNumThreads(1)
        _progress(status, "allocating bounded frame feed")
        publisher = SharedFramePublisher(feed_name)
        _progress(status, "creating camera driver")
        camera = cv2.VideoCapture()
        backend = cv2.CAP_DSHOW if os.name == "nt" else cv2.CAP_ANY
        _progress(status, f"opening webcam {camera_index}")
        if not camera.open(camera_index, backend) or not camera.isOpened():
            raise OSError(f"Cannot open webcam {camera_index}; another app may own it. No other camera was tried")
        width, height = CAMERA_RESOLUTIONS[camera_resolution]
        _progress(status, f"requesting width {width}")
        camera.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        _progress(status, f"requesting height {height}")
        camera.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        fps = max(10, min(30, fps_value.value))
        _progress(status, f"requesting {fps} FPS")
        camera.set(cv2.CAP_PROP_FPS, fps)
        _progress(status, "requesting minimal driver buffering")
        camera.set(cv2.CAP_PROP_BUFFERSIZE, 1)  # Best effort; some backends ignore this.
        _progress(status, "requesting MJPG format")
        camera.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
        _progress(status, "reading negotiated driver settings")
        metadata = _capture_metadata(camera, cv2, camera_resolution)
        _capture_frames(camera, publisher, stop, fps_value, cv2, camera_roi, metadata, status)
    except Exception as exc:
        _notify(status, f"Standalone camera unavailable: {type(exc).__name__}: {exc}")
    finally:
        try:
            if publisher is not None:
                publisher.close()
        finally:
            try:
                if camera is not None:
                    camera.release()  # Can also block; owner enforces bounded shutdown.
            finally:
                status.cancel_join_thread()


class WebcamSource:
    """Latest-frame reader plus bounded lifecycle for one explicitly owned camera."""

    def __init__(self, camera_index, fps_value, camera_resolution="640x480", camera_roi=None,
                 *, context=None, clock=time.monotonic):
        camera_roi = _validate_camera_options(camera_index, camera_resolution, camera_roi)
        self._clock = clock
        self._started = clock()
        self._last_frame_at = None
        self._last_sequence = None
        self._last_startup_phase = "starting capture process"
        self._startup_status_at = self._started
        self.failure = None
        self.metadata = {}
        self.status = "opening standalone webcam (0s): starting capture process"
        self._closed = False
        self._job = None
        context = context or mp.get_context("spawn")
        self._stop = context.Event()
        allowed = context.Event()
        self._status = context.Queue(maxsize=1)
        feed_name = "fgi_webcam_" + uuid.uuid4().hex
        self._reader = SharedFrameReader(feed_name)
        self._process = context.Process(target=_capture_worker,
            args=(feed_name, camera_index, self._stop, allowed, fps_value, self._status,
                  camera_resolution, camera_roi),
            name="facial-webcam-capture", daemon=True)
        try:
            self._process.start()
            self._job = _CaptureJob(self._process)
            allowed.set()
        except BaseException:
            self.close()
            raise

    def read_latest(self):
        if self._closed:
            return None
        try:
            detail = self._status.get_nowait()
        except queue.Empty:
            detail = None
        if isinstance(detail, dict) and detail.get("kind") == "info":
            self.metadata = dict(detail["detail"])
        elif isinstance(detail, dict) and detail.get("kind") == "progress":
            self._last_startup_phase = str(detail["detail"])[:100]
        elif detail:
            self._fail(detail)
            return None
        now = self._clock()
        # A late first frame or repeated progress cannot extend this deadline.
        if self._last_frame_at is None and now - self._started >= OPEN_TIMEOUT:
            self._fail(f"Standalone webcam opening timed out after {OPEN_TIMEOUT:.0f}s; "
                       f"last phase: {self._last_startup_phase}. It may be busy or its driver stalled")
            return None
        packet = self._reader.read_latest()
        if packet is not None and packet.sequence != self._last_sequence:
            self._last_sequence = packet.sequence
            self._last_frame_at = now
        if not self._process.is_alive():
            self._fail("Standalone camera process exited; select the source again to retry")
            return None
        if self._last_frame_at is not None and now - self._last_frame_at > STALL_TIMEOUT:
            self._fail("Standalone webcam stopped delivering fresh frames; no automatic reopen")
            return None
        if self._last_frame_at is not None:
            self.status = self._reader.status
        elif now - self._startup_status_at >= 1.0:
            self._startup_status_at = now
            self.status = (f"opening standalone webcam ({int(now - self._started)}s): "
                           f"{self._last_startup_phase}")
        return packet

    def _fail(self, detail):
        self.failure = self.status = detail
        self.close()

    def close(self):
        if self._closed:
            return
        self._closed = True
        self._stop.set()
        self._reader.close()
        if self._process.pid is not None:
            self._process.join(.250)
            if self._process.is_alive():
                self._process.terminate()
                self._process.join(.250)
            if self._process.is_alive():
                self._process.kill()
                self._process.join(.250)
        if self._job is not None:
            self._job.close()
        if self._process.pid is not None and not self._process.is_alive():
            self._process.close()
        self._status.cancel_join_thread()
        self._status.close()
