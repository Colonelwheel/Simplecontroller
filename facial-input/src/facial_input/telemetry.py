"""Latest-only scalar telemetry for an independent local resource collector."""
from __future__ import annotations

import json
import ctypes
import getpass
import hashlib
import math
import os
import struct
import time
from multiprocessing.shared_memory import SharedMemory

SIZE = 8192


class ScalarTelemetry:
    def __init__(self):
        self.memory = None
        self.last = 0.0
        self.sequence = 0
        if os.name == "nt":
            try:
                self.memory = SharedMemory(name=f"FGI.Scalars.{os.getpid()}", create=True, size=SIZE)
            except (OSError, FileExistsError):
                pass

    def publish(self, snapshot):
        now = time.monotonic()
        if self.memory is None or now - self.last < 1:
            return
        self.last = now
        scalar = {"pid": os.getpid(), "published_at": now}
        for name in ("fps", "result_age_ms", "inference_ms"):
            value = snapshot.get(name)
            scalar[name] = value if type(value) in (int, float) and math.isfinite(value) else None
        for name in ("mode", "camera_status", "worker_status"):
            scalar[name] = str(snapshot.get(name, ""))[:160]
        # Do not export frames, face features, scores, shortcuts or typed text.
        payload = json.dumps(scalar, allow_nan=False).encode("utf-8")
        self.sequence += 2
        ctypes.c_ulonglong.from_buffer(self.memory.buf, 0).value = self.sequence - 1
        struct.pack_into("<I", self.memory.buf, 8, len(payload))
        self.memory.buf[16:16 + len(payload)] = payload
        ctypes.c_ulonglong.from_buffer(self.memory.buf, 0).value = self.sequence

    def close(self):
        if self.memory is not None:
            self.memory.close()
            self.memory = None


def read_scalars(pid):
    try:
        memory = SharedMemory(name=f"FGI.Scalars.{int(pid)}", create=False)
    except (OSError, ValueError):
        return None
    try:
        for _ in range(3):
            sequence, length = struct.unpack_from("<QI", memory.buf, 0)
            if not sequence or sequence % 2 or not 0 < length <= SIZE - 16:
                continue
            payload = bytes(memory.buf[16:16 + length])
            if struct.unpack_from("<Q", memory.buf, 0)[0] != sequence:
                continue
            value = json.loads(payload)
            if value.get("pid") != int(pid) or not 0 <= time.monotonic() - value.get("published_at", 0) <= 3:
                return None
            return value
    except (ValueError, TypeError, KeyError, struct.error):
        return None
    finally:
        memory.close()
    return None


def read_monitor_export():
    """Read only the existing export header; never access frame slots."""
    name = "tsm_face_v1_" + hashlib.sha256(getpass.getuser().casefold().encode()).hexdigest()[:16]
    try:
        memory = SharedMemory(name=name, create=False)
    except OSError:
        return None
    header = struct.Struct("<Q8sII16sQQQdIII")
    try:
        for _ in range(3):
            data = bytes(memory.buf[:header.size])
            fields = header.unpack(data)
            if fields[0] % 2 or bytes(memory.buf[:header.size]) != data:
                continue
            if fields[1] != b"TSMFACE1" or fields[2] != 1 or fields[3] != 1:
                return None
            captured = fields[8]
            age = time.monotonic() - captured
            if not math.isfinite(age) or age < 0:
                return None
            return {"sequence": fields[5], "generation": [fields[4].hex(), fields[6], fields[7]], "frame_age_ms": age * 1000}
    except (ValueError, struct.error):
        return None
    finally:
        memory.close()
    return None
