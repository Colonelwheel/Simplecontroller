"""Exercise diagnostic instrumentation with fake native outputs in a child process."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class ReceiverTimingIntegrationTests(unittest.TestCase):
    def test_enabled_receiver_preserves_ownership_exceptions_and_records_delays(self):
        # Existing harness replaces every native input module before importing
        # the receiver. No real socket, controller, keyboard or mouse is used.
        directory = Path(tempfile.mkdtemp(prefix="receiver-timing-integration-"))
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
