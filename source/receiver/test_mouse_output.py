"""Real Windows pipe/helper tests. Every backend is fake; no native input runs."""
import ast
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import patch

from mouse_output import MouseOutput, worker
from multiprocessing.connection import PipeConnection


class FakeBackend:
    def __init__(self, log, delayed_op="none", delay="0"):
        self.log = Path(log)
        self.delayed_op = delayed_op
        self.delay = delay
        self.delayed = False

    def record(self, op, args):
        with self.log.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps([op, args]) + "\n")

    def call(self, op, *args):
        self.record("start:" + op, args)
        if op == self.delayed_op and not self.delayed:
            self.delayed = True
            if self.delay == "forever":
                threading.Event().wait()
            elif self.delay == "fail":
                raise OSError("deliberate fake native failure")
            else:
                time.sleep(float(self.delay))
        self.record(op, args)

    def mouseDown(self, button):
        self.call("mouseDown", button)

    def mouseUp(self, button):
        self.call("mouseUp", button)

    def moveRel(self, x, y):
        self.call("moveRel", x, y)

    def game_move(self, x, y):
        self.call("game_move", x, y)

    def scroll(self, amount):
        self.call("scroll", amount)


def wait_for(predicate, seconds=5):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.002)
    raise AssertionError("offline fake-helper condition did not finish")


class HelperFixture(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="sc-fake-mouse-")
        self.addCleanup(self.directory.cleanup)
        self.helpers = []

    def events(self, index=0):
        log = Path(self.directory.name) / f"helper-{index}.jsonl"
        if not log.exists():
            return []
        return [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines()]

    def helper(self, delayed_op="none", delay="0"):
        index = len(self.helpers)
        log = Path(self.directory.name) / f"helper-{index}.jsonl"
        command = [sys.executable, "-B", str(Path(__file__).resolve()),
                   "--fake-backend", str(log), delayed_op, str(delay)]
        output = MouseOutput(command=command)
        self.helpers.append(output)

        self.addCleanup(self.cleanup_helper, output)
        wait_for(lambda: not output.pending)
        self.assertIsNone(output.error)
        return output

    def cleanup_helper(self, output):
        output.close()
        output._thread.join(2)
        process = output.process
        if process is not None:
            # Only this test's fake child may be terminated, never a receiver.
            if process.poll() is None:
                process.terminate()
            process.wait(3)
        output._thread.join(1)

    def bind_fake_receiver_ports(self):
        """Use ephemeral substitutes if the user's actual ports are occupied."""
        sockets = []
        for kind, requested in ((socket.SOCK_DGRAM, 42734),
                                (socket.SOCK_STREAM, 42735),
                                (socket.SOCK_STREAM, 42736)):
            item = socket.socket(socket.AF_INET, kind)
            item.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            try:
                item.bind(("127.0.0.1", requested))
            except OSError:
                item.bind(("127.0.0.1", 0))
            if kind == socket.SOCK_STREAM:
                item.listen()
            sockets.append(item)
            self.addCleanup(item.close)
        return sockets

class MouseHelperTests(HelperFixture):
    def test_measured_late_mouse_up_recovers_and_keeps_helper_alive(self):
        for delay in (0.370, 1.500):
            with self.subTest(delay=delay):
                output = self.helper("mouseUp", delay)
                self.assertTrue(output.mouseDown("left"))
                started = time.monotonic()
                self.assertFalse(output.mouseUp("left"))
                self.assertLess(time.monotonic() - started, 0.200)
                self.assertIsNone(output.process.poll())
                self.assertTrue(output.pending)
                wait_for(lambda: not output.pending)
                self.assertTrue(output.moveRel(7, -3))
                self.assertTrue(output.mouseDown("right"))
                self.assertTrue(output.mouseUp("right"))

    def test_late_down_preserves_continuous_hold(self):
        output = self.helper("mouseDown", 0.370)
        self.assertFalse(output.mouseDown("left"))
        wait_for(lambda: not output.pending)
        self.assertIn(["mouseDown", ["left"]], self.events())
        self.assertNotIn(["mouseUp", ["left"]], self.events())
        self.assertTrue(output.moveRel(2, 3))
        self.assertTrue(output.mouseUp("left"))
        self.assertEqual(1, self.events().count(["mouseUp", ["left"]]))

    def test_late_down_released_if_its_owner_ends_during_stall(self):
        output = self.helper("mouseDown", 0.370)
        self.assertFalse(output.mouseDown("left"))
        started = time.monotonic()
        self.assertFalse(output.mouseUp("left"))
        self.assertLess(time.monotonic() - started, 0.025)
        wait_for(lambda: not output.pending)
        completed = [op for op, _ in self.events() if not op.startswith("start:")]
        self.assertEqual(["mouseDown", "mouseUp"], completed)

    def test_repress_during_stall_cannot_resurrect_the_previous_hold(self):
        output = self.helper("mouseDown", 0.370)
        output.mouseDown("left")
        output.mouseUp("left")
        output.mouseDown("left")  # This newer press is dropped.
        wait_for(lambda: not output.pending)
        self.assertFalse(output.mouseDown("left"))  # No hidden state resync.
        self.assertEqual(1, self.events().count(["mouseDown", ["left"]]))
        self.assertEqual(1, self.events().count(["mouseUp", ["left"]]))
        output.mouseUp("left")  # A fresh release permits a fresh press.
        self.assertTrue(output.mouseDown("left"))

    def test_moves_buttons_and_scroll_do_not_queue_or_wait_while_busy(self):
        output = self.helper("moveRel", 1.500)
        self.assertFalse(output.moveRel(1, 2))
        started = time.monotonic()
        for _ in range(1000):
            self.assertFalse(output.moveRel(9, 9))
            self.assertFalse(output.game_move(8, 8))
            self.assertFalse(output.scroll(120))
            self.assertFalse(output.mouseDown("right"))
        for button in ("left", "right", "middle"):
            self.assertFalse(output.mouseUp(button))
        self.assertLess(time.monotonic() - started, 0.100)
        wait_for(lambda: not output.pending)
        self.assertEqual(1, sum(op == "moveRel" for op, _ in self.events()))
        self.assertFalse(any(op in ("mouseDown", "game_move", "scroll")
                             for op, _ in self.events()))
        self.assertTrue(output.moveRel(3, 4))

    def test_release_during_startup_drains_before_mouse_becomes_ready(self):
        # Hold process creation in the one I/O thread, without adding a helper lane.
        entered, proceed = threading.Event(), threading.Event()
        spawn = MouseOutput._spawn
        def delayed_spawn(output):
            entered.set()
            self.assertTrue(proceed.wait(2))
            return spawn(output)
        command = [sys.executable, "-B", str(Path(__file__).resolve()),
                   "--fake-backend", str(Path(self.directory.name) / "helper-0.jsonl"), "none", "0"]
        with patch.object(MouseOutput, "_spawn", delayed_spawn):
            output = MouseOutput(command=command)
            self.helpers.append(output)
            self.addCleanup(self.cleanup_helper, output)
            self.assertTrue(entered.wait(1))
            self.assertFalse(output.mouseUp("left"))
            proceed.set()
            wait_for(lambda: not output.pending)
        output.close()
        output._thread.join(2)
        output.process.wait(2)
        self.assertEqual(1, self.events().count(["mouseUp", ["left"]]))

    def test_release_all_steps_return_immediately_after_first_timeout(self):
        output = self.helper("mouseUp", 1.500)
        for button in ("left", "right", "middle"):
            output.mouseDown(button)
        output.mouseUp("left")
        started = time.monotonic()
        output.mouseUp("right")
        output.mouseUp("middle")
        self.assertFalse(output.retry_releases())
        self.assertLess(time.monotonic() - started, 0.025)
        wait_for(lambda: not output.pending)
        for button in ("left", "right", "middle"):
            self.assertEqual(1, self.events().count(["mouseUp", [button]]))

    def test_pending_and_recovery_logging_is_bounded_during_packet_flood(self):
        with self.assertLogs(level="INFO") as messages:
            output = self.helper("moveRel", 0.370)
            output.moveRel(1, 1)
            for _ in range(1000):
                output.moveRel(2, 2)
            wait_for(lambda: not output.pending)
        pending = [line for line in messages.output if "Mouse call pending" in line]
        recovered = [line for line in messages.output if "Late mouse reply received" in line]
        self.assertEqual(1, len(pending))
        self.assertEqual(1, len(recovered))

    def test_failed_release_is_retained_until_explicit_retry(self):
        output = self.helper("mouseUp", "fail")
        output.mouseDown("left")
        self.assertFalse(output.mouseUp("left"))
        self.assertTrue(output.pending)
        self.assertFalse(output.moveRel(1, 1))
        self.assertTrue(output.retry_releases())
        self.assertTrue(output.moveRel(2, 2))
        self.assertEqual(2, self.events().count(["start:mouseUp", ["left"]]))

    def test_close_releases_ledger_before_helper_exits(self):
        output = self.helper()
        for button in ("left", "right", "middle"):
            output.mouseDown(button)
        output.close()
        output._thread.join(2)
        output.process.wait(2)
        self.assertEqual(0, output.process.returncode)
        for button in ("left", "right", "middle"):
            self.assertEqual(1, self.events().count(["mouseUp", [button]]))

    def test_close_during_late_down_eventually_releases_it(self):
        output = self.helper("mouseDown", 0.370)
        output.mouseDown("left")
        started = time.monotonic()
        output.close()
        self.assertLess(time.monotonic() - started, 0.025)
        output._thread.join(2)
        output.process.wait(2)
        self.assertEqual(1, self.events().count(["mouseUp", ["left"]]))

    def test_idle_helper_crash_releases_hold_then_requires_a_fresh_press(self):
        output = self.helper()
        self.assertTrue(output.mouseDown("left"))
        previous = output.process
        previous.terminate()  # Kill only this fake backend, with its ledger held.
        previous.wait(2)
        wait_for(lambda: output.process is not previous and not output.pending)
        self.assertEqual(1, self.events().count(["mouseUp", ["left"]]))
        self.assertFalse(output.mouseDown("left"))  # Old Hold cannot replay.
        self.assertTrue(output.mouseUp("left"))
        self.assertTrue(output.mouseDown("left"))
        self.assertEqual(2, self.events().count(["mouseDown", ["left"]]))

    def test_crash_during_down_releases_the_uncertain_button(self):
        output = self.helper("mouseDown", 1.500)
        output.mouseDown("left")
        previous = output.process
        previous.terminate()
        previous.wait(2)
        wait_for(lambda: output.process is not previous and not output.pending)
        self.assertEqual(0, self.events().count(["mouseDown", ["left"]]))
        self.assertEqual(1, self.events().count(["mouseUp", ["left"]]))

    def test_pipe_failure_never_respawns_past_a_live_helper(self):
        output = self.helper(delay="break-pipe-live")
        previous = output.process
        output.moveRel(1, 1)
        wait_for(lambda: output.error is not None)
        time.sleep(1.300)
        self.assertIs(previous, output.process)
        self.assertIsNone(previous.poll())
        self.assertEqual([], list(output._respawns))

    def test_respawn_limit_stops_until_release_all_requests_a_rate_limited_retry(self):
        output = self.helper()
        output.mouseDown("left")
        healthy_command = output._command.copy()
        output._command = [*healthy_command[:-1], "exit-ready"]
        previous = output.process
        offset = [0.0]
        real_clock = time.monotonic
        clock = types.SimpleNamespace(monotonic=lambda: real_clock() + offset[0])
        with patch("mouse_output.time", clock), self.assertLogs(level="ERROR") as messages:
            previous.terminate()
            previous.wait(2)
            wait_for(lambda: output._paused, seconds=8)
            wait_for(lambda: any("recovery paused" in line for line in messages.output))
            attempts = list(output._respawns)
            self.assertEqual(3, len(attempts))
            self.assertGreaterEqual(attempts[1] - attempts[0], 1.0)
            self.assertGreaterEqual(attempts[2] - attempts[1], 1.0)
            last = output.process
            output._command = healthy_command
            # A manual retry cannot bypass the rolling minute limit.
            self.assertFalse(output.retry_releases())
            time.sleep(0.300)
            self.assertIs(last, output.process)
            offset[0] = 61.0
            with output._condition:
                output._condition.notify()
            wait_for(lambda: output.process is not last and not output.pending)
        self.assertEqual(1, self.events().count(["mouseUp", ["left"]]))
        self.assertTrue(output.moveRel(5, 6))

    def test_ready_warning_is_advisory_and_does_not_kill_the_helper(self):
        spawn = MouseOutput._spawn
        class PipeWithEarlyWarning:
            def __init__(self, pipe):
                self.pipe = pipe
            def poll(self, timeout):
                self_timeout = 0.020 if timeout == 10.0 else timeout
                return self.pipe.poll(self_timeout)
            def __getattr__(self, name):
                return getattr(self.pipe, name)
        with patch.object(MouseOutput, "_spawn", lambda output: PipeWithEarlyWarning(spawn(output))), \
                self.assertLogs(level="WARNING") as messages:
            output = self.helper(delay="late-ready")
        self.assertTrue(any("not ready after 10 seconds" in line for line in messages.output))
        self.assertIsNone(output.process.poll())
        self.assertFalse(output.pending)
        self.assertEqual([], self.events())

    def test_unverified_worker_identity_never_allows_a_replacement(self):
        output = self.helper()
        previous = output.process
        output._worker_matches = False  # A different packaged worker PID was reported.
        previous.terminate()
        previous.wait(2)
        wait_for(lambda: output.error is not None)
        self.assertFalse(output.retry_releases())
        time.sleep(1.300)
        self.assertIs(previous, output.process)
        self.assertEqual([], list(output._respawns))

    def test_eof_releases_ledger_even_without_close_operation(self):
        output = self.helper()
        # Exercise the real child directly to close the only protocol endpoint.
        output.close()
        output._thread.join(2)
        output.process.wait(2)
        pipe = output._spawn()
        self.assertEqual((True, output.process.pid), pipe.recv())
        pipe.send(("mouseDown", ("middle",)))
        self.assertEqual((True, None), pipe.recv())
        pipe.close()
        output.process.wait(2)
        self.assertEqual(0, output.process.returncode)
        self.assertEqual(1, self.events().count(["mouseUp", ["middle"]]))

    def test_stuck_helper_cannot_retain_receiver_ports_after_restart(self):
        ports = self.bind_fake_receiver_ports()
        # Make the parent's sockets explicitly inheritable: handle_list must exclude them.
        for item in ports:
            item.set_inheritable(True)
        addresses = [(item.type, item.getsockname()) for item in ports]
        output = self.helper("mouseUp", "forever")
        output.mouseDown("left")
        output.mouseUp("left")
        output.close()
        self.assertIsNone(output.process.poll())
        for item in ports:
            item.close()
        replacements = []
        try:
            for kind, address in addresses:
                item = socket.socket(socket.AF_INET, kind)
                replacements.append(item)
                item.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                item.bind(address)
                if kind == socket.SOCK_STREAM:
                    item.listen()
            self.assertEqual(3, len(replacements))
        finally:
            for item in replacements:
                item.close()


class ReceiverIntegrationTests(HelperFixture):
    """Import only through the existing fake keyboard/mouse/gamepad harness."""
    def setUp(self):
        super().setUp()
        from test_receiver_release_all import receiver, fake_keyboard
        self.receiver = receiver
        self.keyboard = fake_keyboard
        receiver.face_input_state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), "mouse candidate fake setup")
        receiver.release_barriers.clear()
        self.output_patch = None

    def use_helper(self, delayed_op="none", delay="0"):
        output = self.helper(delayed_op, delay)
        output_patch = patch.object(self.receiver, "mouse_output", output)
        output_patch.start()
        self.addCleanup(output_patch.stop)
        game_patch = patch.object(self.receiver, "game_mouse_output", output.game_move)
        game_patch.start()
        self.addCleanup(game_patch.stop)
        return output

    def command(self, command):
        return self.receiver.process_command(command, ("127.0.0.1", 31999))

    def test_merged_face_owner_keeps_late_phone_down_held(self):
        output = self.use_helper("mouseDown", 0.370)
        self.command("MOUSE_LEFT_DOWN")
        self.receiver.desktop_input.face_set("player1", "MOUSE_LEFT", True)
        self.command("MOUSE_LEFT_UP")
        wait_for(lambda: not output.pending)
        self.assertNotIn(["mouseUp", ["left"]], self.events())
        self.receiver.desktop_input.face_set("player1", "MOUSE_LEFT", False)
        self.assertEqual(1, self.events().count(["mouseUp", ["left"]]))

    def test_forever_mouse_stall_does_not_gate_keyboard_xbox_or_phone_activation(self):
        output = self.use_helper("mouseUp", "forever")
        self.command("MOUSE_LEFT_DOWN")
        self.command("MOUSE_LEFT_UP")
        started = time.monotonic()
        for key in ("CTRL", "ALT", "TAB"):
            self.command("KEY_DOWN:" + key)
        for key in ("CTRL", "ALT", "TAB"):
            self.command("KEY_UP:" + key)
        self.assertFalse(self.keyboard.down)
        self.command("PHONE_CAPS_V1:candidate:nonce")
        reply = self.command("PHONE_ACTIVATE_V1:candidate:0")
        self.assertIsNotNone(reply)
        self.assertIn("ACK", reply)
        self.command("X360A_HOLD")
        self.assertIn("A", self.receiver.gamepads["player1"].raw.buttons)
        from phone_output_protocol import state_key
        from urllib.parse import quote_plus
        key = quote_plus(state_key("X360B_HOLD", "player1"))
        self.command(f"PHONE_STATE_V1:candidate:1:{key}:X360B_HOLD")
        self.assertIn("B", self.receiver.gamepads["player1"].raw.buttons)
        self.assertLess(time.monotonic() - started, 0.150)
        self.assertTrue(output.pending)
        self.assertFalse(any("key" in op.lower() for op, _ in self.events()))

    def test_slow_370ms_keyboard_release_stays_outside_helper(self):
        output = self.use_helper()
        self.command("KEY_DOWN:CTRL")
        release = self.keyboard.release
        def delayed_release(key):
            time.sleep(0.370)
            release(key)
        with patch.object(self.keyboard, "release", side_effect=delayed_release):
            self.command("KEY_UP:CTRL")
        self.assertFalse(self.keyboard.down)
        self.assertFalse(output.pending)
        self.assertTrue(output.moveRel(1, 2))
        self.assertFalse(any("key" in op.lower() for op, _ in self.events()))

    def test_release_all_retry_preserves_newer_xbox_and_mouse_holds(self):
        output = self.use_helper("mouseUp", 0.370)
        self.command("MOUSE_LEFT_DOWN")
        self.assertIsNone(self.command("RELEASE_ALL:candidate-release:20"))
        started = time.monotonic()
        self.command("X360A_HOLD")
        self.assertIsNone(self.command("RELEASE_ALL:candidate-release:20"))
        self.assertLess(time.monotonic() - started, 0.025)
        wait_for(lambda: not output.pending)
        self.command("MOUSE_RIGHT_DOWN")
        self.assertEqual("RELEASE_ALL_ACK:candidate-release:20",
                         self.command("RELEASE_ALL:candidate-release:20"))
        self.assertIn("A", self.receiver.gamepads["player1"].raw.buttons)
        self.assertIn("right", output._wanted)
        self.assertEqual(1, self.events().count(["mouseUp", ["right"]]))

    def test_failed_release_all_retry_retries_only_pending_mouse(self):
        output = self.use_helper("mouseUp", "fail")
        self.command("MOUSE_LEFT_DOWN")
        # The later cleanup calls may already retry the failed left UP.
        first = self.command("RELEASE_ALL:candidate-error:30")
        self.command("X360A_HOLD")
        self.assertEqual("RELEASE_ALL_ACK:candidate-error:30",
                         self.command("RELEASE_ALL:candidate-error:30"))
        self.assertIn("A", self.receiver.gamepads["player1"].raw.buttons)
        self.assertFalse(output.pending)

    def test_release_all_requests_recovery_after_automatic_respawns_pause(self):
        output = self.use_helper()
        self.command("MOUSE_LEFT_DOWN")
        previous = output.process
        # Exhaust the real limiter's history without waiting a minute in a test.
        with output._condition:
            output._respawns.extend([time.monotonic()] * 3)
        previous.terminate()
        previous.wait(2)
        wait_for(lambda: output._paused)
        self.assertIsNone(self.command("RELEASE_ALL:manual-recovery:10"))
        self.command("X360A_HOLD")
        with output._condition:
            output._respawns.clear()  # Rate window has elapsed.
            output._condition.notify()
        wait_for(lambda: output.process is not previous and not output.pending)
        self.assertEqual("RELEASE_ALL_ACK:manual-recovery:10",
                         self.command("RELEASE_ALL:manual-recovery:10"))
        self.assertIn("A", self.receiver.gamepads["player1"].raw.buttons)

    def test_cleanup_log_reports_pending_mouse_instead_of_success(self):
        output = self.use_helper("mouseUp", 0.370)
        self.command("MOUSE_LEFT_DOWN")
        with self.assertLogs(self.receiver.logger, level="INFO") as messages:
            self.command("RELEASE_ALL:pending-log:10")
        self.assertTrue(any("mouse cleanup pending" in line for line in messages.output))
        self.assertFalse(any("Released all outputs" in line for line in messages.output))
        wait_for(lambda: not output.pending)

    def test_receiver_routes_all_mouse_operations_through_helper(self):
        output = self.use_helper()
        for command in ("DELTA:1,-1", "GAME_DELTA:2,-2", "SCROLL:1",
                        "MOUSE_MIDDLE_DOWN", "MOUSE_MIDDLE_UP"):
            self.command(command)
        operations = [op for op, _ in self.events() if not op.startswith("start:")]
        self.assertEqual(["moveRel", "game_move", "scroll", "mouseDown", "mouseUp"], operations)

    def test_worker_dispatch_precedes_diagnostics_and_receiver_import(self):
        import receiver_diagnostics
        with patch("mouse_output.worker") as fake_worker, \
                patch.object(sys, "argv", ["receiver", "--mouse-output-worker", "123"]), \
                patch.object(receiver_diagnostics.runpy, "run_module") as run:
            receiver_diagnostics.main()
        fake_worker.assert_called_once_with(123)
        run.assert_not_called()
        source = Path(__file__).with_name("mouse_output.py").read_text(encoding="utf-8")
        imports = {node.names[0].name for node in ast.walk(ast.parse(source))
                   if isinstance(node, ast.Import)}
        self.assertTrue(imports.isdisjoint({"keyboard", "vgamepad", "simple_controller_receiver"}))


def probe_worker(executable, report_path):
    """Explicit frozen smoke: only READY and CLOSE; never send an output operation."""
    state = types.SimpleNamespace(_command=[str(Path(executable).resolve())], process=None)
    started = time.monotonic()
    pipe = MouseOutput._spawn(state)
    try:
        if not pipe.poll(10.0):
            raise AssertionError("No frozen worker ready reply within smoke-check budget")
        ready = pipe.recv()
        ready_seconds = time.monotonic() - started
        assert ready[0] is True and type(ready[1]) is int, ready
        pipe.send(("close", ()))
    finally:
        pipe.close()
    code = state.process.wait(10)
    assert code == 0, code
    result = {"ready": ready, "ready_seconds": ready_seconds, "launcher_pid": state.process.pid,
              "exit_code": code, "operations_sent": ["close"], "receiver_started": False}
    with Path(report_path).open("x", encoding="utf-8") as stream:
        json.dump(result, stream, indent=2)
    print(json.dumps(result))


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--probe-worker":
        probe_worker(sys.argv[2], sys.argv[3])
    elif "--mouse-output-worker" in sys.argv:
        marker = sys.argv.index("--fake-backend")
        backend = FakeBackend(*sys.argv[marker + 1:marker + 4])
        handle = int(sys.argv[sys.argv.index("--mouse-output-worker") + 1])
        if backend.delay in ("exit-ready", "break-pipe-live"):
            pipe = PipeConnection(handle)
            pipe.send((True, os.getpid()))
            pipe.close()
            if backend.delay == "break-pipe-live":
                threading.Event().wait()
            raise SystemExit(23)
        if backend.delay == "late-ready":
            time.sleep(0.150)
        worker(handle, backend)
    else:
        unittest.main()
