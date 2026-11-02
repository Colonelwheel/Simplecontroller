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
        recorder = TimingRecorder(enabled=True, interval_seconds=10, **kwargs)
        self.recorders.append(recorder)
        return recorder

    def finish(self, recorder):
        recorder.close()
        recorder._thread.join(3)
        self.assertFalse(recorder._thread.is_alive())
        return [json.loads(line) for line in recorder.path.read_text(encoding="utf-8").splitlines()]

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

    def test_size_cap_is_hard_and_disables_recording(self):
        recorder = self.make(max_bytes=800)
        path = recorder.start(self.directory)
        recorder.record("command", 30_000_000)
        self.finish(recorder)
        self.assertLessEqual(path.stat().st_size, 800)
        self.assertFalse(recorder.enabled)
        self.assertEqual(recorder._reason, "size_limit")
        recorder.record("command", 1)

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
