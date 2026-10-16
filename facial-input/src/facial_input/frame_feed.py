"""Version 1 local latest-frame transport. No network, files, or frame queue.

Identical copy ships in the facial app. The owner writes two bounded slots and
publishes with an aligned revision seqlock; readers attach read-only and retry
at most three times. The supported runtime is Windows x64, whose aligned 64-bit
stores are atomic and whose store ordering publishes payload before revision.
Clients never register a multiprocessing resource tracker or unlink the owner.
"""
from __future__ import annotations

import ctypes
from dataclasses import dataclass
import getpass
import hashlib
import math
import mmap
from multiprocessing import shared_memory
import os
import platform
import re
import struct
import time
import uuid

import numpy as np

MAX_WIDTH, MAX_HEIGHT = 640, 480
SLOT_BYTES = MAX_WIDTH * MAX_HEIGHT * 3
HEADER_BYTES = 128
SIZE = HEADER_BYTES + 2 * SLOT_BYTES
MAGIC = b"TSMFACE1"
VERSION = 1
# revision is naturally aligned at offset 0; all metadata sits behind it.
HEADER = struct.Struct("<Q8sII16sQQQdIII")
STATE_PAUSED, STATE_READY = 0, 1


def default_feed_name() -> str:
    identity = getpass.getuser().casefold().encode("utf-8")
    return "tsm_face_v1_" + hashlib.sha256(identity).hexdigest()[:16]


def _name(name: str | None) -> str:
    value = default_feed_name() if name is None else name
    if not isinstance(value, str) or re.fullmatch(r"[A-Za-z0-9_-]{1,80}", value) is None:
        raise ValueError("shared feed name must contain 1-80 letters, digits, _ or -")
    return value


@dataclass(frozen=True)
class FramePacket:
    image: np.ndarray
    captured_at: float
    sequence: int
    camera_generation: int
    scene_generation: int
    session_id: str


class SharedFramePublisher:
    """Single-writer, single-owner bounded allocation; refuses an existing name."""

    def __init__(self, name: str | None = None):
        self.name = _name(name)
        if os.name == "nt" and (ctypes.sizeof(ctypes.c_void_p) != 8
                                 or platform.machine().casefold() not in {"amd64", "x86_64"}):
            raise OSError("face feed requires Windows x64")
        self._shared = shared_memory.SharedMemory(name=self.name, create=True, size=SIZE)
        self._session = uuid.uuid4().bytes
        self._revision = 0
        self._slot = 0
        self._closed = False
        self._atomic_revision = ctypes.c_longlong.from_buffer(self._shared.buf, 0)
        self.invalidate()

    def _store_revision(self, value: int):
        # This aligned scalar assignment is one 64-bit native store on x64.
        # Python evaluates payload writes before this call, and x64 TSO retains
        # their publication order. No OS global flushing or consumer lock.
        self._atomic_revision.value = value

    def _write(self, image, captured_at, sequence, camera_generation, scene_generation):
        if self._closed:
            raise RuntimeError("publisher closed")
        self._revision += 2
        self._store_revision(self._revision - 1)
        self._slot ^= 1
        width = height = length = 0
        state = STATE_PAUSED
        if image is not None:
            height, width = image.shape[:2]
            length = image.nbytes
            offset = HEADER_BYTES + self._slot * SLOT_BYTES
            self._shared.buf[offset:offset + length] = image.tobytes()
            state = STATE_READY
        metadata = HEADER.pack(self._revision - 1, MAGIC, VERSION, state,
                               self._session, sequence, camera_generation,
                               scene_generation, captured_at, width, height, self._slot)
        self._shared.buf[8:HEADER.size] = metadata[8:]
        self._store_revision(self._revision)

    def publish(self, image: np.ndarray, captured_at: float, sequence: int,
                camera_generation: int, scene_generation: int):
        if (not isinstance(image, np.ndarray) or image.dtype != np.uint8
                or image.ndim != 3 or image.shape[2] != 3
                or not 0 < image.shape[1] <= MAX_WIDTH
                or not 0 < image.shape[0] <= MAX_HEIGHT):
            raise ValueError("feed image must be uint8 BGR within 640x480")
        if not math.isfinite(captured_at) or captured_at < 0:
            raise ValueError("capture timestamp must be finite and nonnegative")
        if any(isinstance(v, bool) or not isinstance(v, int) or not 0 <= v < 2**64
               for v in (sequence, camera_generation, scene_generation)):
            raise ValueError("sequence/generations must be uint64")
        self._write(np.ascontiguousarray(image), captured_at, sequence,
                    camera_generation, scene_generation)

    def invalidate(self):
        if not self._closed:
            self._write(None, 0.0, 0, 0, 0)

    def close(self):
        if self._closed:
            return
        self.invalidate()
        self._closed = True
        del self._atomic_revision
        self._shared.close()
        self._shared.unlink()  # only this allocation's owner performs cleanup


class _ReadOnlyMapping:
    def __init__(self, name):
        self._address = None
        self._handle = None
        self._mapping = None
        if os.name == "nt":
            self._kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            self._kernel.OpenFileMappingW.argtypes = (ctypes.c_ulong, ctypes.c_int, ctypes.c_wchar_p)
            self._kernel.OpenFileMappingW.restype = ctypes.c_void_p
            self._kernel.MapViewOfFile.argtypes = (ctypes.c_void_p, ctypes.c_ulong,
                                                  ctypes.c_ulong, ctypes.c_ulong, ctypes.c_size_t)
            self._kernel.MapViewOfFile.restype = ctypes.c_void_p
            self._kernel.UnmapViewOfFile.argtypes = (ctypes.c_void_p,)
            self._kernel.CloseHandle.argtypes = (ctypes.c_void_p,)
            self._handle = self._kernel.OpenFileMappingW(4, False, name)  # FILE_MAP_READ
            if not self._handle:
                raise FileNotFoundError("feed unavailable")
            self._address = self._kernel.MapViewOfFile(self._handle, 4, 0, 0, SIZE)
            if not self._address:
                self._kernel.CloseHandle(self._handle)
                self._handle = None
                raise OSError("feed mapping failed")
        else:
            # Read-only test support on Linux, without resource_tracker ownership.
            fd = os.open("/dev/shm/" + name, os.O_RDONLY)
            try:
                self._mapping = mmap.mmap(fd, SIZE, access=mmap.ACCESS_READ)
            finally:
                os.close(fd)

    def read(self, offset, size):
        if self._address is not None:
            return ctypes.string_at(self._address + offset, size)
        return self._mapping[offset:offset + size]

    def close(self):
        if self._address is not None:
            self._kernel.UnmapViewOfFile(self._address)
            self._address = None
        if self._handle is not None:
            self._kernel.CloseHandle(self._handle)
            self._handle = None
        if self._mapping is not None:
            self._mapping.close()
            self._mapping = None


class SharedFrameReader:
    """Read-only client; returns a detached BGR copy or None, never waits."""

    def __init__(self, name: str | None = None, *, stale_after: float = 0.250,
                 clock=time.monotonic):
        self.name = _name(name)
        if not math.isfinite(stale_after) or stale_after <= 0:
            raise ValueError("stale_after must be positive")
        self.stale_after = stale_after
        self._clock = clock
        self._mapping = None
        self.status = "missing"

    def _detach(self):
        if self._mapping is not None:
            self._mapping.close()
            self._mapping = None

    def close(self):
        self._detach()
        self.status = "missing"

    def read_latest(self) -> FramePacket | None:
        if self._mapping is None:
            try:
                self._mapping = _ReadOnlyMapping(self.name)
            except FileNotFoundError:
                self.status = "missing"
                return None
            except (OSError, ValueError):
                self.status = "fault"
                return None
        for _ in range(3):
            revision = struct.unpack("<Q", self._mapping.read(0, 8))[0]
            if revision & 1:
                continue
            fields = HEADER.unpack(self._mapping.read(0, HEADER.size))
            rev, magic, version, state, session, seq, camera_gen, scene_gen, captured, width, height, slot = fields
            if rev != revision or rev & 1:
                continue
            if magic != MAGIC or version != VERSION:
                self.status = "fault"
                self._detach()
                return None
            if state == STATE_PAUSED:
                # Producer may be shutting down; don't hold its name alive.
                self.status = "paused"
                self._detach()
                return None
            if (state != STATE_READY or slot not in (0, 1)
                    or not 0 < width <= MAX_WIDTH or not 0 < height <= MAX_HEIGHT
                    or not math.isfinite(captured)):
                self.status = "fault"
                self._detach()
                return None
            age = self._clock() - captured
            if age < 0 or age > self.stale_after:
                self.status = "stale"
                self._detach()
                return None
            raw = self._mapping.read(HEADER_BYTES + slot * SLOT_BYTES, width * height * 3)
            # Verify header and pixel copy belong to the same finished publication.
            if self._mapping.read(0, HEADER.size) != HEADER.pack(*fields):
                continue
            self.status = "ready"
            image = np.frombuffer(raw, dtype=np.uint8).reshape(height, width, 3).copy()
            return FramePacket(image, captured, seq, camera_gen, scene_gen, session.hex())
        self.status = "busy"
        return None
