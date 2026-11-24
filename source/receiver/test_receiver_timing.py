"""Isolated timing tests: never import the receiver or native input libraries."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

from receiver_timing import CATEGORIES, TimingRecorder


class BlockingStream:
    def __init__(self, block_on_call=1):
        self.entered = threading.Event()
        self.release = threading.Event()
        self.data = bytearray()
        self.closed = False
        self.calls = 0
        self.block_on_call = block_on_call

    def write(self, value):
        self.calls += 1
        if self.calls == self.block_on_call:
            self.entered.set()
            self.release.wait(3)
        self.data.extend(value)
        return len(value)

    def close(self):
        self.closed = True


class TimingTests(unittest.TestCase):
    def setUp(self):
        # Preserve uniquely created test artifacts; never clean existing data.
        root = os.environ.get("SC_RECEIVER_TIMING_TEST_ROOT", tempfile.gettempdir())
        self.directory = Path(tempfile.mkdtemp(prefix="receiver-timing-test-", dir=root))
        self.recorders = []

    def tearDown(self):
        for recorder in self.recorders:
            recorder.close()
            if recorder._thread is not None:
                recorder._thread.join(3)

    def make(self, **kwargs):
        recorder = TimingRecorder(enabled=True, interval_seconds=kwargs.pop("interval_seconds", 10), **kwargs)
        self.recorders.append(recorder)
        return recorder

    def finish(self, recorder):
        recorder.close()
        recorder._thread.join(3)
        self.assertFalse(recorder._thread.is_alive())
        return [json.loads(line) for path in recorder.paths
                for line in path.read_text(encoding="utf-8").splitlines()]

    def test_disabled_start_record_and_span_create_nothing(self):
        recorder = TimingRecorder()
        self.assertIsNone(recorder.start(self.directory / "should-not-exist"))
        recorder.record("command", 10)
        with recorder.span("command"):
            pass
        self.assertIsNone(recorder._thread)
        self.assertFalse((self.directory / "should-not-exist").exists())

    def test_disabled_import_creates_no_files_or_thread(self):
        env = os.environ.copy()
        env.pop("SC_RECEIVER_TIMING", None)
        env["SC_RECEIVER_TIMING_DIR"] = str(self.directory / "absent")
        result = subprocess.run(
            [sys.executable, "-B", "-c", "import receiver_timing,threading; assert not receiver_timing.recorder.enabled; assert receiver_timing.recorder._thread is None"],
            cwd=Path(__file__).parent, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.directory / "absent").exists())

    def test_summary_totals_slow_events_and_clock_calibration(self):
        recorder = self.make()
        self.assertIsNotNone(recorder.start(self.directory))
        recorder.record("command", 1_000_000)
        recorder.record("command", 30_000_000)
        records = self.finish(recorder)
        self.assertEqual(records[0]["event"], "metadata")
        self.assertGreater(records[0]["utc_ns"], 0)
        self.assertGreater(records[0]["perf_ns"], 0)
        summary = next(r for r in records if r["event"] == "summary")
        self.assertEqual(summary["categories"]["command"],
                         {"count": 2, "total_ns": 31_000_000, "max_ns": 30_000_000})
        self.assertEqual(len(summary["slow_events"]), 1)
        self.assertEqual(summary["slow_events"][0]["kind"], "command")

    def test_callers_do_not_wait_for_statistics_lock(self):
        recorder = self.make()
        recorder.start(self.directory)
        recorder._lock.acquire()
        try:
            done = threading.Event()
            thread = threading.Thread(target=lambda: (recorder.record("command", 30_000_000), done.set()))
            thread.start()
            self.assertTrue(done.wait(0.25), "record() waited for the statistics lock")
            thread.join()
            self.assertEqual(recorder._dropped_lock, 1)
        finally:
            recorder._lock.release()

    def test_slow_file_writes_do_not_block_input_or_close(self):
        # Block the summary write after its snapshot, rather than metadata I/O.
        stream = BlockingStream(block_on_call=2)
        recorder = TimingRecorder(enabled=True, interval_seconds=0.02)
        self.recorders.append(recorder)
        with mock.patch.object(recorder, "_open_stream", return_value=stream):
            recorder.start(self.directory)
        recorder.record("command", 1)
        try:
            self.assertTrue(stream.entered.wait(1))
            done = threading.Event()
            thread = threading.Thread(target=lambda: (recorder.record("command", 4), done.set()))
            thread.start()
            self.assertTrue(done.wait(0.25), "record() waited for file I/O")
            thread.join()
            before = time.perf_counter()
            recorder.close()
            self.assertLess(time.perf_counter() - before, 0.75)
        finally:
            stream.release.set()

    def test_slow_buffer_is_bounded_and_reports_eviction(self):
        recorder = self.make(max_slow_events=3)
        recorder.start(self.directory)
        for i in range(20):
            recorder.record("lock_wait", 20_000_000 + i)
        self.assertEqual(len(recorder._slow), 3)
        records = self.finish(recorder)
        summary = next(r for r in records if r["event"] == "summary")
        self.assertEqual(summary["dropped_slow"], 17)
        self.assertEqual(summary["categories"]["lock_wait"]["count"], 20)
        self.assertEqual(len(summary["slow_events"]), 3)

    def test_category_allowlist_and_duration_validation_never_log_inputs(self):
        recorder = self.make()
        recorder.start(self.directory)
        secret = "player1:KEY_DOWN:private-typed-content:10.0.0.1"
        recorder.record(secret, 30_000_000)
        recorder.record("command", secret)
        recorder.record("command", -1)
        recorder.record("command", 2**100)
        recorder.record("command", True)
        records = self.finish(recorder)
        self.assertNotIn(secret, recorder.path.read_text(encoding="utf-8"))
        summary = next(r for r in records if r["event"] == "summary")
        self.assertEqual(set(summary["categories"]), CATEGORIES)
        self.assertEqual(summary["invalid_categories"], 1)
        self.assertEqual(summary["invalid_durations"], 4)

    def test_span_and_measure_preserve_result_and_original_exception(self):
        recorder = self.make()
        recorder.start(self.directory)
        error = ValueError("must survive")
        with self.assertRaises(ValueError) as caught:
            with recorder.span("command"):
                raise error
        self.assertIs(caught.exception, error)

        @recorder.measure("desktop_output")
        def output(value):
            """Original documentation."""
            if value:
                raise error
            return 123

        self.assertEqual(output(False), 123)
        self.assertEqual(output.__name__, "output")
        self.assertEqual(output.__doc__, "Original documentation.")
        with self.assertRaises(ValueError) as caught:
            output(True)
        self.assertIs(caught.exception, error)
        records = self.finish(recorder)
        self.assertNotIn("must survive", recorder.path.read_text(encoding="utf-8"))
        summary = next(r for r in records if r["event"] == "summary")
        self.assertEqual(summary["categories"]["command"]["count"], 1)
        self.assertEqual(summary["categories"]["desktop_output"]["count"], 2)

    def test_diagnostic_record_failure_does_not_replace_application_exception(self):
        recorder = self.make()
        recorder.start(self.directory)
        original = RuntimeError("application")
        with mock.patch.object(recorder, "record", side_effect=OSError("diagnostic")):
            with self.assertRaises(RuntimeError) as caught:
                with recorder.span("command"):
                    raise original
        self.assertIs(caught.exception, original)

    def test_file_collision_does_not_overwrite_existing_file(self):
        path = self.directory / "already-exists.jsonl"
        path.write_bytes(b"keep this data")
        recorder = self.make()
        with mock.patch.object(recorder, "_new_path", return_value=path):
            self.assertIsNone(recorder.start(self.directory))
        self.assertEqual(path.read_bytes(), b"keep this data")
        self.assertFalse(recorder.enabled)

    def test_start_file_failure_is_safe_and_directory_env_is_supported(self):
        recorder = self.make()
        target = self.directory / "chosen"
        with mock.patch.dict(os.environ, {"SC_RECEIVER_TIMING_DIR": str(target)}):
            with mock.patch.object(recorder, "_open_stream", side_effect=OSError("unavailable")):
                self.assertIsNone(recorder.start())
        self.assertFalse(recorder.enabled)
        self.assertIsNone(recorder._thread)
        self.assertTrue(target.is_dir())

    def test_empty_directory_env_uses_appdata_and_start_is_idempotent(self):
        recorder = self.make()
        with mock.patch.dict(os.environ, {"SC_RECEIVER_TIMING_DIR": "", "LOCALAPPDATA": str(self.directory)}):
            path = recorder.start()
            self.assertEqual(path.parent, self.directory / "SimpleControllerReceiver" / "timing_logs")
            self.assertEqual(recorder.start(self.directory / "ignored"), path)
        self.assertFalse((self.directory / "ignored").exists())

    def test_disabled_measure_passes_original_arguments_and_result(self):
        recorder = TimingRecorder()
        marker = object()

        @recorder.measure("command")
        def function(value, *, extra):
            self.assertIs(value, marker)
            self.assertEqual(extra, "unchanged")
            return marker

        self.assertIs(function(marker, extra="unchanged"), marker)
        self.assertIsNone(recorder._thread)

    def test_write_failure_disables_without_raising_to_callers(self):
        stream = mock.Mock()
        stream.write.side_effect = OSError("disk unavailable")
        recorder = self.make()
        with mock.patch.object(recorder, "_open_stream", return_value=stream):
            recorder.start(self.directory)
        recorder._thread.join(1)
        self.assertFalse(recorder.enabled)
        recorder.record("command", 1)
        self.assertEqual(recorder._reason, "write_error")
        stream.close.assert_called_once()

    def test_impossibly_small_budget_fails_closed_without_exceeding_limit(self):
        recorder = self.make(max_bytes=800)
        path = recorder.start(self.directory)
        recorder.record("command", 30_000_000)
        self.finish(recorder)
        self.assertLessEqual(path.stat().st_size, 800)
        self.assertFalse(recorder.enabled)
        self.assertEqual(recorder._reason, "record_too_large")
        recorder.record("command", 1)

    def wait_until(self, predicate, message="Condition was not reached"):
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.005)
        self.fail(message)

    def test_never_returned_operation_emits_bounded_private_frame_metadata(self):
        recorder = self.make(interval_seconds=0.01, watchdog_interval_seconds=0.005,
                             stall_threshold_seconds=0.02)
        recorder.start(self.directory)
        entered, release = threading.Event(), threading.Event()

        def fake_output():
            private_input = "PRIVATE_INPUT_MUST_NOT_BE_LOGGED"
            with recorder.span("native_keyboard_output"):
                entered.set()
                release.wait(2)
            return private_input

        thread = threading.Thread(target=fake_output)
        thread.start()
        try:
            self.assertTrue(entered.wait(1))
            self.wait_until(lambda: b'"event":"long_operation"' in recorder.path.read_bytes())
            self.assertTrue(thread.is_alive(), "operation returned before it was captured")
            records = [json.loads(line) for line in recorder.path.read_text().splitlines()]
            event = next(r for r in records if r["event"] == "long_operation")
            self.assertEqual(event["pid"], os.getpid())
            operation = event["operations"][0]
            self.assertEqual(operation["kind"], "native_keyboard_output")
            self.assertEqual(operation["native_thread_id"], thread.native_id)
            self.assertGreater(operation["elapsed_ns"], 0)
            self.assertNotIn("PRIVATE_INPUT_MUST_NOT_BE_LOGGED", recorder.path.read_text())
            for entry in event["threads"]:
                self.assertLessEqual(len(entry["frames"]), 16)
                for frame in entry["frames"]:
                    self.assertEqual(set(frame), {"file", "function", "line"})
                    self.assertNotIn("\\", frame["file"])
                    self.assertNotIn("/", frame["file"])
        finally:
            release.set()
            thread.join(2)

    def test_completed_operation_cannot_stay_active_when_diagnostic_locks_contend(self):
        recorder = self.make()
        recorder.start(self.directory)
        span = recorder.span("command")
        span.__enter__()
        recorder._operations_lock.acquire()
        recorder._lock.acquire()
        done = threading.Event()
        thread = threading.Thread(target=lambda: (span.__exit__(None, None, None), done.set()))
        try:
            thread.start()
            self.assertTrue(done.wait(0.25))
            self.assertTrue(span.operation.done)
        finally:
            recorder._lock.release()
            recorder._operations_lock.release()
            thread.join(1)
        self.assertEqual(recorder._active_snapshot(), [])

    def test_observed_rlock_preserves_nested_ownership_and_original_exception(self):
        recorder = self.make()
        recorder.start(self.directory)
        lock = threading.RLock()
        error = RuntimeError("original")
        with self.assertRaises(RuntimeError) as caught:
            with recorder.observe_lock(lock):
                with recorder.observe_lock(lock):
                    self.assertTrue(lock._is_owned())
                    raise error
        self.assertIs(caught.exception, error)
        self.assertFalse(lock._is_owned())
        self.assertEqual(recorder._active_snapshot(), [])
        with mock.patch.object(recorder, "span", side_effect=OSError("observer")):
            with recorder.observe_lock(lock):
                self.assertTrue(lock._is_owned())
        self.assertFalse(lock._is_owned())
        self.assertIs(TimingRecorder().observe_lock(lock), lock)

    def test_waiting_on_output_lock_is_observed_before_acquisition(self):
        recorder = self.make(watchdog_interval_seconds=0.005, stall_threshold_seconds=0.02)
        recorder.start(self.directory)
        lock, finished = threading.Lock(), threading.Event()
        lock.acquire()

        def work():
            with recorder.observe_lock(lock):
                finished.set()

        thread = threading.Thread(target=work)
        thread.start()
        try:
            self.wait_until(lambda: any(o["kind"] == "lock_wait" for o in recorder._active_snapshot()))
            self.wait_until(lambda: bool(recorder._pending))
            self.assertFalse(finished.is_set())
        finally:
            lock.release()
            thread.join(2)
        self.assertTrue(finished.is_set())

    def test_idle_receive_and_authored_wait_do_not_raise_long_operation_alert(self):
        recorder = self.make(watchdog_interval_seconds=0.005, stall_threshold_seconds=0.01)
        recorder.start(self.directory)
        with recorder.span("udp_receive_wait"):
            time.sleep(0.05)
            self.assertTrue(recorder._active_snapshot()[0]["expected_wait"])
            self.assertFalse(recorder._pending)
        with recorder.span("command"):
            with recorder.span("legacy_wait"):
                time.sleep(0.05)
                self.assertFalse(recorder._pending)

    def test_watchdog_continues_with_blocked_writer_and_bounded_queue(self):
        stream = BlockingStream()
        recorder = self.make(watchdog_interval_seconds=0.005, stall_threshold_seconds=0.01,
                             repeat_stall_seconds=0.01, max_pending_snapshots=2)
        with mock.patch.object(recorder, "_open_stream", return_value=stream):
            recorder.start(self.directory)
        try:
            self.assertTrue(stream.entered.wait(1))
            with recorder.span("native_gamepad_update"):
                self.wait_until(lambda: recorder._dropped_snapshots > 0)
                self.assertEqual(len(recorder._pending), 2)
                self.assertTrue(recorder._watchdog_thread.is_alive())
                before = time.monotonic()
                recorder.close()
                self.assertLess(time.monotonic() - before, 0.5)
        finally:
            stream.release.set()

    def test_watchdog_recovers_after_transient_frame_capture_failure(self):
        recorder = self.make(watchdog_interval_seconds=0.005, stall_threshold_seconds=0.01)
        real_frames = recorder._frames
        calls = []

        def flaky_frames(operations):
            calls.append(1)
            if len(calls) == 1:
                raise RuntimeError("private failure text must not be recorded")
            return real_frames(operations)

        with mock.patch.object(recorder, "_frames", side_effect=flaky_frames):
            recorder.start(self.directory)
            with recorder.span("native_mouse_output"):
                self.wait_until(lambda: bool(recorder._pending))
                self.assertEqual(recorder._dropped_snapshots, 1)
                self.assertTrue(recorder._watchdog_thread.is_alive())
                self.assertGreaterEqual(len(calls), 2)
        records = self.finish(recorder)
        self.assertTrue(any(r["event"] == "long_operation" for r in records))
        self.assertNotIn("private failure text", recorder.path.read_text())

    def test_active_registry_has_a_hard_bound(self):
        recorder = self.make(max_active_operations=3)
        recorder.start(self.directory)
        spans = [recorder.span("command") for _ in range(20)]
        for span in spans:
            span.__enter__()
        self.assertEqual(len(recorder._active_snapshot()), 3)
        self.assertEqual(recorder._dropped_active, 17)
        for span in spans:
            span.__exit__(None, None, None)
        self.assertEqual(recorder._active_snapshot(), [])

    def test_rollover_keeps_recording_and_never_touches_prior_files(self):
        prior = self.directory / "receiver-timing-prior-session.jsonl"
        prior.write_bytes(b"preserve previous evidence")
        recorder = self.make(max_bytes=32768)
        # Exercise the writer's rotation deterministically, without a live input process.
        recorder._base_path = recorder._new_path(self.directory)
        recorder._open_segment(recorder._base_path)
        self.assertTrue(recorder._write(recorder._metadata()))
        try:
            for number in range(40):
                self.assertTrue(recorder._write({"event": "test", "number": number, "padding": "x" * 3000}))
            self.assertTrue(recorder.enabled)
            self.assertGreater(recorder._segment_number, 4)
            self.assertEqual(len(recorder.paths), 4)
            self.assertLessEqual(sum(p.stat().st_size for p in recorder.paths), 32768)
            self.assertEqual(prior.read_bytes(), b"preserve previous evidence")
            self.assertIn('"number":39', recorder.path.read_text())
            self.assertTrue(all(json.loads(p.read_text().splitlines()[0])["event"] == "metadata"
                                for p in recorder.paths))
        finally:
            recorder._stream.close()

    def test_rotation_preserves_externally_modified_session_segment(self):
        recorder = self.make(max_bytes=16384, retain_segments=2)
        recorder._base_path = recorder._new_path(self.directory)
        recorder._open_segment(recorder._base_path)
        recorder._write(recorder._metadata())
        first = recorder.path
        recorder._rotate()
        with first.open("ab") as stream:
            stream.write(b"external addition")
        expected = first.read_bytes()
        try:
            with self.assertRaises(OSError):
                recorder._rotate()
            self.assertEqual(first.read_bytes(), expected)
        finally:
            recorder._stream.close()

    def test_idle_period_has_only_compact_heartbeat_until_close(self):
        recorder = self.make(interval_seconds=0.005, idle_heartbeat_seconds=0.025)
        recorder.start(self.directory)
        with recorder.span("udp_receive_wait"):
            self.wait_until(lambda: b'"event":"heartbeat"' in recorder.path.read_bytes())
            records = [json.loads(line) for line in recorder.path.read_text().splitlines()]
            self.assertFalse(any(r["event"] == "summary" for r in records))
            self.assertLess(recorder.path.stat().st_size, 5000)

    def test_periodic_writer_flushes_without_close(self):
        recorder = TimingRecorder(enabled=True, interval_seconds=0.02)
        self.recorders.append(recorder)
        recorder.start(self.directory)
        recorder.record("udp_process", 3)
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline:
            if b'"event":"summary"' in recorder.path.read_bytes():
                break
            time.sleep(0.01)
        else:
            self.fail("No periodic summary was written")
        records = self.finish(recorder)
        self.assertGreaterEqual(sum(r["categories"]["udp_process"]["count"]
                                    for r in records if r["event"] == "summary"), 1)


if __name__ == "__main__":
    unittest.main()
