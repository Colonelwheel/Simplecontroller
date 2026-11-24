"""Exercise diagnostic instrumentation with fake native outputs in a child process."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class ReceiverTimingIntegrationTests(unittest.TestCase):
    def test_unfinished_release_native_and_lock_wait_are_visible_without_unblocking(self):
        directory = Path(tempfile.mkdtemp(prefix="receiver-hang-integration-",
                                          dir=os.environ.get("SC_RECEIVER_TIMING_TEST_ROOT", tempfile.gettempdir())))
        child = r'''
import json, os, threading, time
from pathlib import Path
from unittest.mock import Mock, patch
from test_receiver_release_all import receiver
from face_input_server import FaceInputServer
from receiver_timing import recorder

recorder._interval = 0.02
recorder._idle_ns = 20_000_000
path = recorder.start(Path(os.environ["SC_RECEIVER_TIMING_DIR"]))
assert path is not None
address = ("127.0.0.1", 31141)
seen = set()
snapshots = []

def unfinished():
    snapshot = recorder._snapshot()
    snapshots.append(snapshot)
    return {item["kind"] for item in snapshot["active_operations"]}

def check_blocked(target, attribute, action, expected, verify=None, forbidden_active=()):
    entered, release = threading.Event(), threading.Event()
    errors = []
    original = getattr(target, attribute)
    def blocked(*args, **kwargs):
        entered.set()
        if not release.wait(5):
            raise AssertionError("Test did not release fake backend")
        return original(*args, **kwargs)
    def run():
        try:
            action()
        except BaseException as error:
            errors.append(error)
    with patch.object(target, attribute, side_effect=blocked):
        worker = threading.Thread(target=run)
        worker.start()
        try:
            assert entered.wait(2), expected
            assert worker.is_alive()
            active = unfinished()
            assert expected <= active, (expected, active)
            assert not set(forbidden_active) & active, (forbidden_active, active)
            seen.update(expected)
            # Also leave the unfinished call visible to the independent writer.
            time.sleep(0.05)
            assert worker.is_alive(), "The fake block ended before observation"
        finally:
            release.set()
            worker.join(2)
        assert not worker.is_alive()
        assert not errors, errors
    if verify is not None:
        verify()

receiver.handle_key_press("F13")
check_blocked(receiver.keyboard, "release",
              lambda: receiver.process_command("KEY_UP:F13", address),
              {"key_release", "native_keyboard_output", "lock_hold"})
assert "f13" not in receiver.keyboard.down
assert receiver.keyboard.released.count("f13") == 1

receiver.handle_mouse_buttons("MOUSE_LEFT_DOWN")
check_blocked(receiver.pyautogui, "mouseUp",
              lambda: receiver.handle_mouse_buttons("MOUSE_LEFT_UP"),
              {"native_mouse_output", "lock_hold"})
assert "left" not in receiver.pyautogui.down
assert receiver.pyautogui.ups.count("left") == 1

pad = receiver.gamepads["player1"]
check_blocked(pad.raw, "update", pad.update,
              {"native_gamepad_update", "lock_hold"})

# This follows the direct KEY_UP lock site, not just the synchronized decorator.
holder_ready, release_holder = threading.Event(), threading.Event()
errors = []
def holder():
    with receiver.release_state_lock:
        holder_ready.set()
        release_holder.wait(5)
def release_key():
    try:
        receiver.handle_key_release("F14")
    except BaseException as error:
        errors.append(error)
holding = threading.Thread(target=holder)
holding.start()
assert holder_ready.wait(2)
waiting = threading.Thread(target=release_key)
waiting.start()
try:
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        active = unfinished()
        if {"key_release", "lock_wait"} <= active:
            break
        time.sleep(0.005)
    else:
        raise AssertionError("Direct key release lock wait was invisible")
    assert waiting.is_alive()
    assert "f14" not in receiver.keyboard.released
    seen.add("lock_wait")
finally:
    release_holder.set()
    waiting.join(2)
    holding.join(2)
assert not waiting.is_alive() and not holding.is_alive()
assert not errors, errors
assert receiver.keyboard.released.count("f14") == 1

# A stuck ordinary log write is distinguishable from a stuck native key release.
check_blocked(receiver.logger, "info", lambda: receiver.handle_key_release("F15"),
              {"key_release", "log_output", "lock_hold"})

# An intentional WAIT must not hide a later blocked synchronous log write.
check_blocked(receiver.logger, "info", lambda: receiver.handle_wait_command("WAIT_1"),
              {"log_output"}, forbidden_active={"legacy_wait"})

# Exercise shutdown with fake objects only. Never create or bind a real socket.
state = Mock()
server = FaceInputServer(state)
server.listener = Mock()
server.stop_event.set()
check_blocked(state, "disconnect", server._run, {"shutdown_face"})
server.listener.close.assert_called_once()

assert unfinished() == set(), "Completed calls left stale active markers"
recorder.close()
recorder._thread.join(3)
assert not recorder._thread.is_alive()
text = "\n".join(p.read_text(encoding="utf-8") for p in path.parent.glob("*.jsonl"))
serialized_snapshots = json.dumps(snapshots)
for forbidden in ("F13", "F14", "F15", "KEY_UP", "127.0.0.1", "MOUSE_LEFT"):
    assert forbidden not in text and forbidden not in serialized_snapshots, forbidden
emitted = [json.loads(line) for line in text.splitlines() if line.strip()]
assert any(any(item["kind"] == "native_keyboard_output"
               for item in record.get("active_operations", []))
           for record in emitted), "Independent writer missed in-progress input"
print(json.dumps({"seen": sorted(seen), "fake_native": True}))
'''
        environment = dict(os.environ)
        environment.update(SC_RECEIVER_TIMING="1", SC_RECEIVER_TIMING_DIR=str(directory / "timings"),
                           TEMP=str(directory), TMP=str(directory), PYTHONDONTWRITEBYTECODE="1")
        result = subprocess.run([sys.executable, "-B", "-c", child], cwd=Path(__file__).resolve().parent,
                                env=environment, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        summary = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertTrue(summary["fake_native"])
        self.assertTrue({"key_release", "native_keyboard_output", "native_mouse_output",
                         "native_gamepad_update", "lock_wait", "log_output", "shutdown_face"}
                        <= set(summary["seen"]))

    def test_enabled_receiver_preserves_ownership_exceptions_and_records_delays(self):
        # Existing harness replaces every native input module before importing
        # the receiver. No real socket, controller, keyboard or mouse is used.
        directory = Path(tempfile.mkdtemp(prefix="receiver-timing-integration-",
                                          dir=os.environ.get("SC_RECEIVER_TIMING_TEST_ROOT", tempfile.gettempdir())))
        child = r'''
import json, os, threading, time
from pathlib import Path
from unittest.mock import Mock, patch
from test_receiver_release_all import receiver
from face_input_server import synchronized
from receiver_timing import recorder

assert recorder.enabled
path = recorder.start(Path(os.environ["SC_RECEIVER_TIMING_DIR"]))
assert path is not None
address = ("127.0.0.1", 31141)
assert "PHONE_OUTPUT_V1" in receiver.process_command("PHONE_CAPS_V1:private_session:nonce", address)
assert "ACK" in receiver.process_command("PHONE_ACTIVATE_V1:private_session:0", address)
pad = receiver.gamepads["player1"]
pad.face_set("RT", True)
receiver.process_command("player1:PHONE_STATE_V1:private_session:1:player1%7CTRIGGER%7CRT:RT%3A0", address)
assert pad.raw.right_trigger == 1.0
pad.face_set("RT", False)
assert pad.raw.right_trigger == 0.0

original_update = pad.raw.update
def delayed_update():
    time.sleep(0.03)
    return original_update()
with patch.object(pad.raw, "update", side_effect=delayed_update):
    receiver.process_command("player1:PHONE_STATE_V1:private_session:2:player1%7CXBOX%7CA:X360A_HOLD", address)
assert "A" in pad.raw.buttons
receiver.process_command("player1:PHONE_STATE_V1:private_session:3:player1%7CXBOX%7CA:X360A_RELEASE", address)
assert "A" not in pad.raw.buttons
with patch.object(pad.raw, "update", side_effect=RuntimeError("fake-native-failure")):
    try:
        pad.update()
    except RuntimeError as error:
        assert str(error) == "fake-native-failure"
    else:
        raise AssertionError("Native exception was swallowed")

lock = threading.RLock()
ready = threading.Event()
release = threading.Event()
def hold_lock():
    with lock:
        ready.set()
        release.wait(2)
holder = threading.Thread(target=hold_lock)
holder.start()
assert ready.wait(2)
timer = threading.Timer(0.05, release.set)
timer.start()
@synchronized(lock)
def callback():
    return "unchanged-result"
assert callback() == "unchanged-result"
holder.join(2)
timer.join(2)
assert not holder.is_alive()
@synchronized(lock)
def raising_callback():
    raise ValueError("original-callback-error")
try:
    raising_callback()
except ValueError as error:
    assert str(error) == "original-callback-error"
else:
    raise AssertionError("Callback exception was swallowed")
assert lock.acquire(blocking=False)
lock.release()

fake_socket = Mock()
fake_socket.recvfrom.side_effect = [(b"PHONE_CAPS_V1:private_session:nonce", address), KeyboardInterrupt()]
with patch.object(receiver.socket, "socket", return_value=fake_socket), patch.object(receiver.threading, "Timer"):
    try:
        receiver.udp_server()
    except KeyboardInterrupt:
        pass
    else:
        raise AssertionError("UDP interruption changed")
fake_socket.sendto.assert_called_once()
fake_socket.close.assert_called_once()
recorder.close()
recorder._thread.join(timeout=3)
assert not recorder._thread.is_alive(), "Diagnostic writer did not finish its test flush"
text = path.read_text(encoding="utf-8")
records = [json.loads(line) for line in text.splitlines()]
summaries = [record for record in records if record["event"] == "summary"]
categories = {}
counts = {}
slow = []
for record in summaries:
    for name, stats in record["categories"].items():
        categories[name] = max(categories.get(name, 0), stats["max_ns"])
        counts[name] = counts.get(name, 0) + stats["count"]
    slow.extend(record["slow_events"])
assert categories["native_gamepad_update"] >= 20_000_000, categories
assert categories["lock_wait"] >= 20_000_000, categories
for name in ("command", "phone_protocol", "udp_process", "udp_receive_wait", "udp_dispatch", "udp_reply"):
    assert counts.get(name, 0) > 0, (name, counts)
assert any(event["kind"] == "native_gamepad_update" for event in slow)
assert "private_session" not in text
assert "X360A" not in text
assert "127.0.0.1" not in text
print(json.dumps({"categories": sorted(categories), "fake_native": True, "preserved_ownership": True}))
'''
        environment = dict(os.environ)
        environment.update(SC_RECEIVER_TIMING="1", SC_RECEIVER_TIMING_DIR=str(directory / "timings"),
                           TEMP=str(directory), TMP=str(directory), PYTHONDONTWRITEBYTECODE="1")
        result = subprocess.run([sys.executable, "-B", "-c", child], cwd=Path(__file__).resolve().parent,
                                env=environment, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        summary = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertTrue(summary["preserved_ownership"])
        self.assertTrue(summary["fake_native"])


if __name__ == "__main__":
    unittest.main()
