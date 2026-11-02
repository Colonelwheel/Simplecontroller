"""Opt-in, bounded timing diagnostics. Never record commands or input values."""
from datetime import datetime, timezone
from functools import wraps
import json
import os
from pathlib import Path
import threading
import time
import uuid


CATEGORIES = frozenset((
    "udp_receive_wait", "udp_dispatch", "udp_process", "udp_reply", "command",
    "phone_protocol", "lock_wait", "lock_hold", "native_gamepad_update",
    "desktop_output", "game_mouse_output", "legacy_mouse_output", "scroll_output",
))


class _NullSpan:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


_NULL_SPAN = _NullSpan()


class _Span:
    def __init__(self, recorder, kind):
        self.recorder, self.kind, self.started = recorder, kind, None

    def __enter__(self):
        try:
            self.started = time.perf_counter_ns()
        except Exception:
            pass
        return self

    def __exit__(self, *exc):
        try:
            if self.started is not None:
                self.recorder.record(self.kind, time.perf_counter_ns() - self.started)
        except Exception:
            pass
        return False


class TimingRecorder:
    """One recording session. Input callers never wait for the writer's lock.

    Dropped counters are best effort under concurrent callers. The writer copies
    the fixed-size statistics and bounded event list under its lock, then performs
    serialization and all log writes after releasing it. Startup alone creates
    the directory and exclusively opens a new file; disabled startup does neither.
    """

    def __init__(self, enabled=False, interval_seconds=1.0,
                 max_bytes=64 * 1024 * 1024, max_slow_events=256,
                 slow_threshold_ns=20_000_000):
        self.enabled = bool(enabled)
        self.path = None
        self._interval = max(0.001, float(interval_seconds))
        self._max_bytes = max(0, int(max_bytes))
        self._max_slow = max(0, int(max_slow_events))
        self._slow_threshold = max(0, int(slow_threshold_ns))
        self._lock = threading.Lock()
        self._startup_lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = None
        self._stream = None
        self._active = False
        self._started = False
        self._written = 0
        self._reason = "closed"
        self._stats = self._empty_stats()
        self._slow = []
        self._dropped_lock = self._dropped_slow = 0
        self._invalid_categories = self._invalid_durations = 0
        self._period_start = None

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
                path = self._new_path(directory)
                self._stream = self._open_stream(path)
                self.path = path
                self._period_start = time.perf_counter_ns()
                self._active = True
                self._thread = threading.Thread(
                    target=self._writer_main, name="Receiver timing writer", daemon=True)
                self._thread.start()
                return path
        except Exception:
            self.enabled = self._active = False
            self._reason = "start_error"
            if self._stream is not None:
                try:
                    self._stream.close()
                except Exception:
                    pass
            return None

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

    def close(self):
        self._active = False
        self._stop.set()
        thread = self._thread
        if thread is not None and thread is not threading.current_thread():
            try:
                thread.join(timeout=0.25)
            except Exception:
                pass

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
            "dropped_lock": self._dropped_lock, "dropped_slow": self._dropped_slow,
            "invalid_categories": self._invalid_categories,
            "invalid_durations": self._invalid_durations,
        }

    def _write(self, record):
        payload = (json.dumps(record, ensure_ascii=True, separators=(",", ":")) + "\n").encode("utf-8")
        if self._written + len(payload) > self._max_bytes:
            self._reason = "size_limit"
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

    def _writer_main(self):
        try:
            metadata = {
                "event": "metadata", "format": 1, "utc_ns": time.time_ns(),
                "perf_ns": time.perf_counter_ns(), "perf_units": "nanoseconds",
                "interval_seconds": self._interval, "max_bytes": self._max_bytes,
                "slow_threshold_ns": self._slow_threshold,
                "max_slow_events": self._max_slow,
                "categories": sorted(CATEGORIES),
            }
            if not self._write(metadata):
                return
            while not self._stop.wait(self._interval):
                if not self.enabled or not self._write(self._snapshot()):
                    return
            if not self._write(self._snapshot()):
                return
            self._write({"event": "stopped", "utc_ns": time.time_ns(),
                         "perf_ns": time.perf_counter_ns(), "reason": self._reason})
        except Exception:
            self._reason = "write_error"
        finally:
            self.enabled = self._active = False
            try:
                self._stream.close()
            except Exception:
                pass


recorder = TimingRecorder(
    enabled=os.environ.get("SC_RECEIVER_TIMING", "").strip().lower() in ("1", "true", "yes", "on"))
