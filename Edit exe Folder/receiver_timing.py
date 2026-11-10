"""Opt-in bounded diagnostics; never record commands, values, locals or source text."""
from collections import deque
from datetime import datetime, timezone
from functools import wraps
import itertools
import json
import ntpath
import os
from pathlib import Path
import stat
import sys
import threading
import time
import uuid

CATEGORIES = frozenset((
    "udp_receive_wait", "udp_dispatch", "udp_process", "udp_reply", "command",
    "phone_protocol", "lock_wait", "lock_hold", "native_gamepad_update",
    "desktop_output", "game_mouse_output", "legacy_mouse_output", "scroll_output",
    "native_keyboard_output", "native_mouse_output", "console_output", "log_output",
    "startup_controllers", "startup_endpoints", "startup_udp", "shutdown_voice",
    "shutdown_face", "shutdown_camera", "shutdown_udp", "shutdown_outputs",
    "output_release", "key_release", "legacy_wait",
))
EXPECTED_WAITS = frozenset(("udp_receive_wait", "legacy_wait"))


class _NullSpan:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


_NULL_SPAN = _NullSpan()


class _Operation:
    __slots__ = ("number", "kind", "started", "thread_id", "native_thread_id", "done")

    def __init__(self, number, kind, started):
        self.number, self.kind, self.started = number, kind, started
        self.thread_id = threading.get_ident()
        self.native_thread_id = threading.get_native_id()
        self.done = False


class _Span:
    def __init__(self, recorder, kind):
        self.recorder, self.kind = recorder, kind
        self.started = self.operation = None

    def __enter__(self):
        try:
            self.started = time.perf_counter_ns()
            self.operation = self.recorder._begin(self.kind, self.started)
        except Exception:
            pass
        return self

    def __exit__(self, *exc):
        # Independent completion flag prevents stale markers when diagnostic locks contend.
        if self.operation is not None:
            self.operation.done = True
        try:
            self.recorder._end(self.operation)
            if self.started is not None:
                self.recorder.record(self.kind, time.perf_counter_ns() - self.started)
        except Exception:
            pass
        return False


class _ObservedLock:
    """Observe the original lock; do not replace identity, ownership or reentrancy."""
    def __init__(self, recorder, lock):
        self.recorder, self.lock, self.hold = recorder, lock, _NULL_SPAN

    def _span(self, kind):
        try:
            return self.recorder.span(kind)
        except Exception:
            return _NULL_SPAN

    def __enter__(self):
        wait = self._span("lock_wait")
        try:
            wait.__enter__()
        except Exception:
            wait = _NULL_SPAN
        try:
            result = self.lock.acquire()
        finally:
            try:
                wait.__exit__(None, None, None)
            except Exception:
                pass
        self.hold = self._span("lock_hold")
        try:
            self.hold.__enter__()
        except Exception:
            self.hold = _NULL_SPAN
        return result

    def __exit__(self, *exc):
        try:
            self.hold.__exit__(*exc)
        except Exception:
            pass
        finally:
            self.lock.release()
        return False


class TimingRecorder:
    """Input never waits for diagnostic I/O/locks. Observer never alters output.

    Rotation touches only exclusively created segments from this session; no
    directory scan or old-session cleanup occurs. The independent watchdog uses
    bounded metadata-only Python frames, not native/kernel stack information.
    """
    def __init__(self, enabled=False, interval_seconds=1.0,
                 max_bytes=64 * 1024 * 1024, max_slow_events=256,
                 slow_threshold_ns=20_000_000, retain_segments=4,
                 stall_threshold_seconds=1.0, watchdog_interval_seconds=0.25,
                 repeat_stall_seconds=10.0, idle_heartbeat_seconds=30.0,
                 max_active_operations=256, max_pending_snapshots=8):
        self.enabled = bool(enabled)
        self.path, self.paths = None, []
        self._interval = max(0.001, float(interval_seconds))
        self._max_bytes = max(0, int(max_bytes))
        self._retain_segments = max(1, min(16, int(retain_segments)))
        self._segment_bytes = self._max_bytes // self._retain_segments
        self._max_slow = max(0, int(max_slow_events))
        self._slow_threshold = max(0, int(slow_threshold_ns))
        self._stall_ns = max(1, int(float(stall_threshold_seconds) * 1e9))
        self._watchdog_interval = max(0.001, float(watchdog_interval_seconds))
        self._repeat_stall_ns = max(self._stall_ns, int(float(repeat_stall_seconds) * 1e9))
        self._idle_ns = max(1, int(float(idle_heartbeat_seconds) * 1e9))
        self._max_active = max(1, min(4096, int(max_active_operations)))
        self._lock, self._operations_lock = threading.Lock(), threading.Lock()
        self._events_lock, self._startup_lock = threading.Lock(), threading.Lock()
        self._stop = threading.Event()
        self._thread = self._watchdog_thread = self._stream = None
        self._active = self._started = False
        self._written = 0
        self._reason = "closed"
        self._stats, self._slow, self._operations = self._empty_stats(), [], {}
        self._operation_numbers = itertools.count(1)
        self._pending = deque(maxlen=max(1, min(64, int(max_pending_snapshots))))
        self._dropped_lock = self._dropped_slow = self._dropped_active = 0
        self._dropped_snapshots = self._invalid_categories = self._invalid_durations = 0
        self._period_start = None
        self._session, self._segment_number = uuid.uuid4().hex, 0
        self._base_path, self._owned_files, self._last_counters = None, {}, None

    @staticmethod
    def _empty_stats():
        return {kind: [0, 0, 0] for kind in CATEGORIES}

    @staticmethod
    def _new_path(directory):
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
        return directory / ("receiver-timing-" + stamp + "-" + uuid.uuid4().hex + ".jsonl")

    @staticmethod
    def _open_stream(path):
        return path.open("xb", buffering=0)

    def start(self, directory=None):
        if not self.enabled:
            return None
        try:
            with self._startup_lock:
                if self._started:
                    return self.path if self._active else None
                self._started = True
                if directory is None:
                    directory = os.environ.get("SC_RECEIVER_TIMING_DIR") or None
                if directory is None:
                    base = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")
                    directory = base / "SimpleControllerReceiver" / "timing_logs"
                directory = Path(directory)
                directory.mkdir(parents=True, exist_ok=True)
                self._base_path = self._new_path(directory)
                self._open_segment(self._base_path)
                self._period_start = time.perf_counter_ns()
                self._active = True
                self._watchdog_thread = threading.Thread(
                    target=self._watchdog_main, name="Receiver diagnostic watchdog", daemon=True)
                self._thread = threading.Thread(
                    target=self._writer_main, name="Receiver timing writer", daemon=True)
                self._watchdog_thread.start()
                self._thread.start()
                return self.path
        except Exception:
            self.enabled = self._active = False
            self._stop.set()
            self._reason = "start_error"
            if self._stream is not None:
                try:
                    self._stream.close()
                except Exception:
                    pass
            return None

    def _begin(self, kind, started):
        if not self.enabled or not self._active:
            return None
        if not self._operations_lock.acquire(False):
            self._dropped_active += 1
            return None
        try:
            if len(self._operations) >= self._max_active:
                self._operations = {k: v for k, v in self._operations.items() if not v.done}
            if len(self._operations) >= self._max_active:
                self._dropped_active += 1
                return None
            operation = _Operation(next(self._operation_numbers), kind, started)
            self._operations[operation.number] = operation
            return operation
        finally:
            self._operations_lock.release()

    def _end(self, operation):
        if operation is None or not self._operations_lock.acquire(False):
            return
        try:
            self._operations.pop(operation.number, None)
        finally:
            self._operations_lock.release()

    def record(self, kind, duration_ns):
        """Best-effort counters only: no waiting, serialization, or file access."""
        if not self.enabled or not self._active:
            return
        acquired = False
        try:
            if type(kind) is not str or kind not in CATEGORIES:
                self._invalid_categories += 1
                return
            if type(duration_ns) is not int or not 0 <= duration_ns <= 2**63 - 1:
                self._invalid_durations += 1
                return
            acquired = self._lock.acquire(False)
            if not acquired:
                self._dropped_lock += 1
                return
            stats = self._stats[kind]
            stats[0] += 1
            stats[1] += duration_ns
            stats[2] = max(stats[2], duration_ns)
            if duration_ns >= self._slow_threshold:
                if self._max_slow:
                    if len(self._slow) >= self._max_slow:
                        del self._slow[0]
                        self._dropped_slow += 1
                    self._slow.append((kind, duration_ns, time.perf_counter_ns()))
                else:
                    self._dropped_slow += 1
        except Exception:
            pass
        finally:
            if acquired:
                self._lock.release()

    def span(self, kind):
        if (not self.enabled or not self._active or
                type(kind) is not str or kind not in CATEGORIES):
            return _NULL_SPAN
        return _Span(self, kind)

    def measure(self, kind):
        def decorate(function):
            @wraps(function)
            def measured(*args, **kwargs):
                if not self.enabled or not self._active:
                    return function(*args, **kwargs)
                with self.span(kind):
                    return function(*args, **kwargs)
            return measured
        return decorate

    def observe_lock(self, lock):
        return _ObservedLock(self, lock) if self.enabled and self._active else lock

    def close(self):
        self._active = False
        self._stop.set()
        deadline = time.monotonic() + 0.25
        for thread in (self._watchdog_thread, self._thread):
            if thread is not None and thread is not threading.current_thread():
                try:
                    thread.join(timeout=max(0, deadline - time.monotonic()))
                except Exception:
                    pass

    def _active_snapshot(self):
        if not self._operations_lock.acquire(False):
            return []
        try:
            self._operations = {k: v for k, v in self._operations.items() if not v.done}
            operations = list(self._operations.values())
        finally:
            self._operations_lock.release()
        now = time.perf_counter_ns()
        return [{"operation": o.number, "kind": o.kind, "started_perf_ns": o.started,
                 "elapsed_ns": max(0, now - o.started), "thread_id": o.thread_id,
                 "native_thread_id": o.native_thread_id, "expected_wait": o.kind in EXPECTED_WAITS}
                for o in operations if not o.done]

    @staticmethod
    def _frames(operations):
        frames, threads, seen = sys._current_frames(), [], set()
        for operation in operations:
            ident = operation["thread_id"]
            if ident in seen or len(threads) >= 16:
                continue
            seen.add(ident)
            frame, stack = frames.get(ident), []
            while frame is not None and len(stack) < 16:
                code = frame.f_code
                stack.append({"file": ntpath.basename(code.co_filename),
                              "function": code.co_name, "line": frame.f_lineno})
                frame = frame.f_back
            threads.append({"thread_id": ident, "native_thread_id": operation["native_thread_id"],
                            "frames": stack})
        return threads

    def _watchdog_main(self):
        last_signature, last_at = None, 0
        while not self._stop.wait(self._watchdog_interval):
            try:
                if not self.enabled:
                    return
                operations = self._active_snapshot()
                intentional = {o["thread_id"] for o in operations if o["kind"] == "legacy_wait"}
                stalled = [o for o in operations if not o["expected_wait"] and
                           o["thread_id"] not in intentional and o["elapsed_ns"] >= self._stall_ns][:32]
                if not stalled:
                    last_signature = None
                    continue
                now = time.perf_counter_ns()
                signature = tuple(o["operation"] for o in stalled)
                if signature == last_signature and now - last_at < self._repeat_stall_ns:
                    continue
                event = {"event": "long_operation", "utc_ns": time.time_ns(), "perf_ns": now,
                         "pid": os.getpid(), "session": self._session, "operations": stalled,
                         "threads": self._frames(stalled)}
                with self._events_lock:
                    if len(self._pending) == self._pending.maxlen:
                        self._dropped_snapshots += 1
                    self._pending.append(event)
                last_signature, last_at = signature, now
            except Exception:
                # A transient frame/snapshot failure must not silently retire the
                # observer. Retry after the bounded poll interval, not in a spin.
                self._dropped_snapshots += 1

    def _snapshot(self):
        ended = time.perf_counter_ns()
        with self._lock:
            stats, self._stats = self._stats, self._empty_stats()
            slow, self._slow = self._slow, []
            started, self._period_start = self._period_start, ended
        return {
            "event": "summary", "utc_ns": time.time_ns(), "perf_ns": ended,
            "period_start_perf_ns": started,
            "categories": {kind: {"count": v[0], "total_ns": v[1], "max_ns": v[2]}
                           for kind, v in stats.items()},
            "slow_events": [{"kind": kind, "duration_ns": duration, "end_perf_ns": stamp}
                            for kind, duration, stamp in slow],
            "active_operations": self._active_snapshot(),
            "dropped_lock": self._dropped_lock, "dropped_slow": self._dropped_slow,
            "dropped_active": self._dropped_active, "dropped_snapshots": self._dropped_snapshots,
            "invalid_categories": self._invalid_categories,
            "invalid_durations": self._invalid_durations,
        }

    def _metadata(self):
        return {"event": "metadata", "format": 2, "utc_ns": time.time_ns(),
                "perf_ns": time.perf_counter_ns(), "perf_units": "nanoseconds",
                "pid": os.getpid(), "session": self._session, "segment": self._segment_number,
                "interval_seconds": self._interval, "max_bytes": self._max_bytes,
                "segment_bytes": self._segment_bytes, "retain_segments": self._retain_segments,
                "slow_threshold_ns": self._slow_threshold, "max_slow_events": self._max_slow,
                "long_operation_threshold_ns": self._stall_ns, "categories": sorted(CATEGORIES)}

    def _open_segment(self, path):
        stream = self._open_stream(path)
        self._stream, self.path = stream, path
        self.paths.append(path)
        self._segment_number += 1
        self._written = 0
        try:
            item = os.fstat(stream.fileno())
            self._owned_files[path] = (item.st_dev, item.st_ino)
        except (AttributeError, OSError, TypeError):
            self._owned_files[path] = None

    def _seal_segment(self):
        self._stream.close()
        identity = self._owned_files.get(self.path)
        if identity is None:
            return
        item = self.path.lstat()
        if (not stat.S_ISREG(item.st_mode) or (item.st_dev, item.st_ino) != identity or
                item.st_size != self._written):
            raise OSError("Session log changed")
        self._owned_files[self.path] = (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns)

    def _rotate(self):
        self._seal_segment()
        if len(self.paths) >= self._retain_segments:
            oldest = self.paths[0]
            identity = self._owned_files.get(oldest)
            item = oldest.lstat()
            if (identity is None or not stat.S_ISREG(item.st_mode) or
                    (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns) != identity):
                raise OSError("Session log changed")
            oldest.unlink()
            self.paths.pop(0)
            self._owned_files.pop(oldest, None)
        path = self._base_path.with_name(self._base_path.stem +
                                        "-part" + str(self._segment_number + 1).zfill(4) + ".jsonl")
        self._open_segment(path)
        return self._write(self._metadata(), allow_rotate=False)

    def _write(self, record, allow_rotate=True):
        payload = (json.dumps(record, ensure_ascii=True, separators=(",", ":")) + "\n").encode("utf-8")
        if len(payload) > self._segment_bytes:
            self._reason = "record_too_large"
            self.enabled = self._active = False
            return False
        if self._written + len(payload) > self._segment_bytes:
            if not allow_rotate or not self._rotate():
                return False
            if self._written + len(payload) > self._segment_bytes:
                self._reason = "record_too_large"
                self.enabled = self._active = False
                return False
        view = memoryview(payload)
        while view:
            count = self._stream.write(view)
            if not count:
                raise OSError("Timing log write failed")
            self._written += count
            view = view[count:]
        return True

    def _write_pending(self):
        with self._events_lock:
            events = list(self._pending)
            self._pending.clear()
        return all(self._write(event) for event in events)

    def _writer_main(self):
        try:
            if not self._write(self._metadata()):
                return
            last_heartbeat = time.perf_counter_ns()
            while not self._stop.wait(self._interval):
                if not self.enabled or not self._write_pending():
                    return
                summary = self._snapshot()
                counters = tuple(summary[k] for k in ("dropped_lock", "dropped_slow", "dropped_active",
                                                     "dropped_snapshots", "invalid_categories", "invalid_durations"))
                activity = any(v["count"] for v in summary["categories"].values())
                if activity or (self._last_counters is not None and counters != self._last_counters):
                    if not self._write(summary):
                        return
                elif summary["perf_ns"] - last_heartbeat >= self._idle_ns:
                    if not self._write({"event": "heartbeat", "utc_ns": summary["utc_ns"],
                                        "perf_ns": summary["perf_ns"], "pid": os.getpid(),
                                        "active_operations": summary["active_operations"]}):
                        return
                    last_heartbeat = summary["perf_ns"]
                self._last_counters = counters
            if not self._write_pending() or not self._write(self._snapshot()):
                return
            self._write({"event": "stopped", "utc_ns": time.time_ns(),
                         "perf_ns": time.perf_counter_ns(), "reason": self._reason})
        except Exception:
            self._reason = "write_error"
        finally:
            self.enabled = self._active = False
            self._stop.set()
            try:
                self._stream.close()
            except Exception:
                pass


recorder = TimingRecorder(
    enabled=os.environ.get("SC_RECEIVER_TIMING", "").strip().lower() in ("1", "true", "yes", "on"))
