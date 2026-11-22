"""Synthetic ownership/protocol/socket checks; no native controller or camera."""

import json
import socket
import threading
import time
import unittest
import uuid
from unittest.mock import patch

from face_input_server import FaceInputState, FaceInputServer, MergedGamepad, ProtocolError


class Pad:
    def __init__(self):
        self.buttons = set()
        self.triggers = {"LT": 0.0, "RT": 0.0}
        self.reports = []

    def press_button(self, button):
        self.buttons.add(button)

    def release_button(self, button):
        self.buttons.discard(button)

    def left_trigger_float(self, value_float):
        self.triggers["LT"] = value_float

    def right_trigger_float(self, value_float):
        self.triggers["RT"] = value_float

    def update(self):
        self.reports.append((set(self.buttons), dict(self.triggers)))

    def reset(self):
        self.buttons.clear()
        self.triggers = {"LT": 0.0, "RT": 0.0}


class StateTests(unittest.TestCase):
    def setUp(self):
        self.now = 10.0
        self.lock = threading.RLock()
        self.raw = Pad()
        self.pad = MergedGamepad(self.raw, {b: b for b in ("A", "B", "X", "Y", "LB", "RB", "START", "BACK", "UP", "DOWN", "LEFT", "RIGHT", "LS", "RS")}, self.lock)
        self.other = MergedGamepad(Pad(), self.pad.button_map, self.lock)
        self.state = FaceInputState({"player1": self.pad, "player2": self.other}, self.lock, clock=lambda: self.now)
        self.seq = 0
        self.hello()

    def hello(self, player="player1"):
        result = self.state.handle({"v": 1, "op": "hello", "session": str(uuid.uuid4()), "player": player})
        self.epoch = result["epoch"]
        return result

    def command(self, op, **fields):
        self.seq += 1
        return self.state.handle({"v": 1, "op": op, "epoch": self.epoch, "seq": self.seq, **fields})

    def arm(self):
        self.command("arm")

    def test_connection_starts_unarmed_and_requires_explicit_arm(self):
        self.command("hold", button="A")
        self.assertEqual(set(), self.raw.buttons)
        self.arm()
        self.command("hold", button="A")
        self.assertEqual({"A"}, self.raw.buttons)

    def test_face_release_preserves_phone_and_phone_release_preserves_face(self):
        self.pad.press_button("A")
        self.arm()
        self.command("hold", button="A")
        self.command("release", button="A")
        self.assertEqual({"A"}, self.raw.buttons)
        self.command("hold", button="A")
        self.pad.release_button("A")
        self.assertEqual({"A"}, self.raw.buttons)
        self.command("release", button="A")
        self.assertEqual(set(), self.raw.buttons)

    def test_face_tap_expiry_keeps_phone_hold(self):
        self.pad.press_button("B")
        self.arm()
        self.command("tap", button="B", duration_ms=100)
        self.now += 0.11
        self.state.tick()
        self.assertEqual({"B"}, self.raw.buttons)
        self.assertEqual(set(), self.pad.face_buttons)

    def test_tap_lasts_receiver_timed_100ms(self):
        self.arm()
        self.command("tap", button="A", duration_ms=100)
        self.now += 0.099
        self.state.tick()
        self.assertIn("A", self.raw.buttons)
        self.now += 0.0011
        self.state.tick()
        self.assertNotIn("A", self.raw.buttons)

    def test_hold_replaces_tap_and_old_deadline_cannot_release_it(self):
        self.arm()
        self.command("tap", button="A", duration_ms=100)
        self.now += 0.02
        self.command("hold", button="A")
        self.now += 0.15
        self.state.tick()
        self.assertEqual({"A"}, self.raw.buttons)

    def test_new_binding_clears_previous_face_ownership(self):
        self.arm()
        self.command("hold", button="A")
        self.command("hold", button="B")
        self.assertEqual({"B"}, self.raw.buttons)

    def test_repeated_hold_is_idempotent_without_a_release_edge(self):
        self.arm()
        self.command("hold", button="A")
        self.raw.reports.clear()
        self.command("hold", button="A")
        self.assertTrue(self.raw.reports)
        self.assertTrue(all("A" in buttons for buttons, _ in self.raw.reports))

    def test_reset_serializes_with_inflight_native_face_write(self):
        self.arm()
        entered = threading.Event()
        finish = threading.Event()
        reset_done = threading.Event()
        original = self.raw.press_button

        def blocked(button):
            entered.set()
            self.assertTrue(finish.wait(1))
            original(button)

        def reset():
            self.state.reset(("player1",))
            reset_done.set()

        with patch.object(self.raw, "press_button", blocked):
            writer = threading.Thread(target=lambda: self.command("hold", button="A"))
            writer.start()
            self.assertTrue(entered.wait(1))
            resetter = threading.Thread(target=reset)
            resetter.start()
            self.assertFalse(reset_done.wait(0.02))
            finish.set()
            writer.join(1)
            resetter.join(1)
        self.assertTrue(reset_done.is_set())
        self.assertEqual(set(), self.raw.buttons)

    def test_trigger_max_restores_latest_phone_value(self):
        self.pad.left_trigger_float(0.6)
        self.arm()
        self.command("hold", button="LT")
        self.assertEqual(1.0, self.raw.triggers["LT"])
        self.pad.left_trigger_float(0.3)
        self.assertEqual(1.0, self.raw.triggers["LT"])
        self.command("release", button="LT")
        self.assertEqual(0.3, self.raw.triggers["LT"])
        self.command("hold", button="RT")
        self.pad.right_trigger_float(0.8)
        self.state.disconnect()
        self.assertEqual(0.8, self.raw.triggers["RT"])

    def test_clear_disarms_and_leaves_phone_input(self):
        self.pad.press_button("X")
        self.arm()
        self.command("hold", button="A")
        self.command("clear")
        self.command("hold", button="A")
        self.assertEqual({"X"}, self.raw.buttons)
        self.assertFalse(self.state.armed)

    def test_500ms_timeout_clears_face_only(self):
        self.pad.press_button("Y")
        self.arm()
        self.command("hold", button="A")
        self.now += 0.5
        self.assertTrue(self.state.tick())
        self.assertEqual({"Y"}, self.raw.buttons)
        self.assertIsNone(self.state.session)

    def test_heartbeat_not_actions_refreshes_timeout(self):
        self.arm()
        self.now += 0.4
        self.command("hold", button="A")
        self.now += 0.1
        self.assertTrue(self.state.tick())

    def test_late_command_cannot_resurrect_timed_out_session(self):
        self.arm()
        self.now += 0.501
        with self.assertRaises(ProtocolError):
            self.command("hold", button="A")
        self.assertEqual(set(), self.raw.buttons)

    def test_fresh_heartbeats_extend_live_session(self):
        self.arm()
        self.command("hold", button="A")
        for _ in range(10):
            self.now += 0.1
            self.command("heartbeat")
            self.assertFalse(self.state.tick())
        self.assertEqual({"A"}, self.raw.buttons)

    def test_global_reset_rejects_old_epoch_and_requires_rearm(self):
        self.arm()
        self.command("tap", button="A", duration_ms=100)
        self.state.reset(("player1", "player2"))
        notice = self.state.drain_notifications()[0]
        self.assertEqual("reset", notice["op"])
        self.assertGreater(notice["epoch"], self.epoch)
        stale = self.command("hold", button="B")
        self.assertEqual(notice, stale)
        self.epoch = notice["epoch"]
        self.command("hold", button="B")
        self.assertEqual(set(), self.raw.buttons)
        self.arm()
        self.command("hold", button="B")
        self.now += 0.12
        self.state.tick()
        self.assertEqual({"B"}, self.raw.buttons)

    def test_invalid_messages_do_not_mutate_ownership(self):
        for fields in ({"button": "GUIDE"}, {"button": ["A"]}, {"button": "A", "duration_ms": True}, {"button": "A", "duration_ms": 1000}):
            with self.assertRaises((ProtocolError, TypeError)):
                self.command("tap", **fields)
        self.assertEqual(set(), self.raw.buttons)

    def test_bool_sequence_and_replayed_sequence_rejected(self):
        self.arm()
        with self.assertRaises(ProtocolError):
            self.state.handle({"v": 1, "op": "hold", "button": "A", "epoch": self.epoch, "seq": True})
        with self.assertRaises(ProtocolError):
            self.state.handle({"v": 1, "op": "hold", "button": "A", "epoch": self.epoch, "seq": self.seq})
        self.assertEqual(set(), self.raw.buttons)

    def test_player2_session_does_not_affect_player1(self):
        self.state.disconnect()
        self.hello("player2")
        self.arm()
        self.command("hold", button="RB")
        self.assertEqual(set(), self.raw.buttons)
        self.assertEqual({"RB"}, self.other.raw.buttons)


class SocketTests(unittest.TestCase):
    def setUp(self):
        self.lock = threading.RLock()
        self.raw = Pad()
        self.pad = MergedGamepad(self.raw, {"A": "A", "B": "B"}, self.lock)
        self.state = FaceInputState({"player1": self.pad}, self.lock)
        self.server = FaceInputServer(self.state, port=0)
        self.server.start()
        self.addCleanup(self.server.stop)
        self.client = socket.create_connection(("127.0.0.1", self.server.port), timeout=1)
        self.client.settimeout(1)
        self.send({"v": 1, "op": "hello", "session": str(uuid.uuid4()), "player": "player1"})
        self.epoch = json.loads(self.client.recv(4096))["epoch"]
        self.seq = 0

    def tearDown(self):
        self.client.close()
        self.server.stop()

    def send(self, message):
        self.client.sendall((json.dumps(message) + "\n").encode())

    def command(self, op, **fields):
        self.seq += 1
        self.send({"v": 1, "op": op, "epoch": self.epoch, "seq": self.seq, **fields})

    def until(self, predicate):
        deadline = time.monotonic() + 1
        while not predicate() and time.monotonic() < deadline:
            time.sleep(0.005)
        self.assertTrue(predicate())

    def test_loopback_handshake_hold_reset_and_disconnect(self):
        self.assertEqual("127.0.0.1", self.server.listener.getsockname()[0])
        self.command("arm")
        self.command("hold", button="A")
        self.until(lambda: "A" in self.raw.buttons)
        self.state.reset(("player1",))
        notice = json.loads(self.client.recv(4096))
        self.assertEqual("reset", notice["op"])
        self.assertEqual(set(), self.raw.buttons)
        self.epoch = notice["epoch"]
        self.command("arm")
        self.command("hold", button="B")
        self.until(lambda: "B" in self.raw.buttons)
        self.client.close()
        self.until(lambda: not self.raw.buttons)

    def test_second_session_rejected_without_releasing_first(self):
        self.command("arm")
        self.command("hold", button="A")
        self.until(lambda: "A" in self.raw.buttons)
        with socket.create_connection(("127.0.0.1", self.server.port), timeout=1) as other:
            other.settimeout(1)
            self.assertEqual(b"", other.recv(1))
        self.assertEqual({"A"}, self.raw.buttons)

    def test_malformed_frame_closes_socket_and_clears_face_only(self):
        self.pad.press_button("B")
        self.command("arm")
        self.command("hold", button="A")
        self.until(lambda: "A" in self.raw.buttons)
        self.client.sendall(b'{invalid}\n')
        self.until(lambda: self.state.session is None)
        self.assertEqual({"B"}, self.raw.buttons)

    def test_bounded_unterminated_line_closes_connection(self):
        self.client.sendall(b"x" * 4097)
        self.until(lambda: self.state.session is None)
        self.assertEqual(b"", self.client.recv(1))

    def test_more_than_eight_messages_in_one_chunk_are_drained(self):
        payload = []
        for seq in range(1, 13):
            payload.append(json.dumps({"v": 1, "op": "heartbeat", "epoch": self.epoch, "seq": seq}) + "\n")
        self.client.sendall("".join(payload).encode())
        self.until(lambda: self.state.seq == 12)

    def test_missing_heartbeat_times_out_over_real_transport(self):
        self.command("arm")
        self.command("hold", button="A")
        self.until(lambda: "A" in self.raw.buttons)
        self.until(lambda: self.state.session is None)
        self.assertEqual(set(), self.raw.buttons)
        self.assertEqual(b"", self.client.recv(1))


class ReceiverIntegrationTests(unittest.TestCase):
    def setUp(self):
        # Baseline harness installs native-library mocks BEFORE importing receiver.
        from test_receiver_release_all import receiver
        self.receiver = receiver
        receiver.face_input_state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), "mock test isolation")
        receiver.active_connections.clear()
        self.state = receiver.face_input_state
        self.epoch = self.state.handle({"v": 1, "op": "hello", "session": str(uuid.uuid4()), "player": "player1"})["epoch"]
        self.seq = 0

    def tearDown(self):
        self.state.disconnect()
        self.receiver.release_outputs(tuple(self.receiver.gamepads), "mock test cleanup")

    def command(self, op, **fields):
        self.seq += 1
        return self.state.handle({"v": 1, "op": op, "epoch": self.epoch, "seq": self.seq, **fields})

    def test_face_messages_do_not_update_phone_or_camera_follow_bookkeeping(self):
        before = dict(self.receiver.camera_follow_last_input_at)
        lost = dict(self.receiver.camera_follow_input_lost)
        self.command("arm")
        self.command("hold", button="A")
        self.command("heartbeat")
        self.assertEqual({}, self.receiver.active_connections)
        self.assertEqual(before, self.receiver.camera_follow_last_input_at)
        self.assertEqual(lost, self.receiver.camera_follow_input_lost)

    def test_phone_explicit_release_preserves_face_and_trigger_release_restores_phone(self):
        self.command("arm")
        self.command("hold", button="A")
        self.receiver.handle_button_press("X360A_HOLD")
        self.receiver.handle_button_press("X360A_RELEASE")
        self.assertIn("A", self.receiver.gamepads["player1"].buttons)
        self.command("hold", button="LT")
        self.receiver.handle_trigger_input(0.7)
        self.command("release", button="LT")
        self.assertEqual(0.7, self.receiver.gamepads["player1"].left_trigger)

    def test_release_all_clears_both_sources_and_invalidates_face_continuation(self):
        self.command("arm")
        self.command("hold", button="B")
        self.receiver.handle_button_press("X360A_HOLD")
        self.receiver.release_all_outputs(str(uuid.uuid4()), 10)
        self.assertEqual(set(), self.receiver.gamepads["player1"].buttons)
        self.assertEqual("reset", self.command("hold", button="B")["op"])

    def test_stale_phone_tap_timer_does_not_release_new_phone_hold(self):
        class ManualTimer:
            timers = []

            def __init__(self, duration, callback):
                self.callback = callback
                self.timers.append(self)

            def start(self):
                pass

            def cancel(self):
                pass

        with patch.object(self.receiver.threading, "Timer", ManualTimer):
            self.receiver.handle_button_press("X360A")
            self.receiver.handle_button_press("X360A_HOLD")
            ManualTimer.timers[0].callback()
            self.assertIn("A", self.receiver.gamepads["player1"].buttons)
            self.assertTrue(self.receiver.button_states["player1"]["A"])
            self.receiver.release_outputs(("player1",))
            self.receiver.handle_button_press("X360A_HOLD")
            ManualTimer.timers[0].callback()
            self.assertIn("A", self.receiver.gamepads["player1"].buttons)


if __name__ == "__main__":
    unittest.main()
