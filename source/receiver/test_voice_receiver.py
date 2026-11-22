"""Voice/phone/face coexistence against the actual receiver with native mocks."""
import threading
import unittest
import uuid
from unittest.mock import patch

# This existing harness mocks native modules BEFORE importing the receiver.
from test_receiver_release_all import receiver, fake_keyboard, fake_pyautogui


class VoiceReceiverTests(unittest.TestCase):
    address = ("127.0.0.1", 31010)

    def setUp(self):
        receiver.face_input_state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), "mock isolation")
        receiver.active_connections.clear()
        receiver.release_barriers.clear()
        receiver.latest_stick_packets.clear()
        self.state = receiver.voice_input_state
        self.original_clock = self.state.clock
        self.now = 10.0
        self.state.clock = lambda: self.now
        self.session = str(uuid.uuid4())
        self.seq = 0
        self.pin = self.state.ready()
        fake_keyboard.down.clear()
        fake_pyautogui.down.clear()

    def tearDown(self):
        receiver.face_input_state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), "mock cleanup")
        self.state.clock = self.original_clock

    def voice(self, op, player="player1", **fields):
        self.seq += 1
        return self.state.handle({
            "v": 1, "receiver": self.pin["receiver"], "epoch": self.pin["epoch"],
            "session": self.session, "seq": self.seq, "player": player,
            "op": op, **fields,
        })

    def phone(self, payload, player="player1"):
        return receiver.process_command(player + ":" + payload, self.address)

    def arm_face(self, player="player1"):
        state = receiver.face_input_state
        epoch = state.handle({"v": 1, "op": "hello", "session": str(uuid.uuid4()),
                              "player": player})["epoch"]
        state.handle({"v": 1, "op": "arm", "epoch": epoch, "seq": 1})
        return state, epoch

    def test_three_button_owners_release_in_order(self):
        self.phone("X360A_HOLD")
        receiver.gamepads["player1"].face_set("A", True)
        self.assertEqual("ack", self.voice("hold", button="A")["op"])
        self.voice("release", button="A")
        self.assertIn("A", receiver.gamepads["player1"].buttons)
        self.phone("X360A_RELEASE")
        self.assertIn("A", receiver.gamepads["player1"].buttons)
        receiver.gamepads["player1"].face_set("A", False)
        self.assertNotIn("A", receiver.gamepads["player1"].buttons)

    def test_voice_tap_expiry_preserves_phone_and_face(self):
        self.phone("X360B_HOLD")
        receiver.gamepads["player1"].face_set("B", True)
        self.voice("tap", button="B", duration_ms=100)
        self.now += 0.101
        self.state.tick()
        self.assertFalse(receiver.gamepads["player1"].voice_buttons)
        self.assertIn("B", receiver.gamepads["player1"].buttons)

    def test_old_phone_timer_cannot_release_voice_hold(self):
        class Timer:
            timers = []

            def __init__(self, duration, callback):
                self.callback = callback
                self.timers.append(self)

            def start(self):
                pass

            def cancel(self):
                pass

        with patch.object(receiver.threading, "Timer", Timer):
            self.phone("X360A")
            self.voice("hold", button="A")
            Timer.timers[-1].callback()
            self.assertIn("A", receiver.gamepads["player1"].buttons)
            self.voice("release", button="A")
            self.assertNotIn("A", receiver.gamepads["player1"].buttons)

    def test_voice_finite_then_persistent_has_no_stale_release(self):
        self.voice("hold", button="RB", duration_ms=300)
        self.voice("hold", button="RB")
        self.now += 1
        self.state.tick()
        self.assertIn("RB", receiver.gamepads["player1"].buttons)

    def test_trigger_max_restores_latest_remaining_value(self):
        self.phone("RT:0.4")
        self.voice("trigger", button="RT", value=0.8)
        pad = receiver.gamepads["player1"]
        self.assertEqual(0.8, pad.right_trigger)
        pad.face_set("RT", True)
        self.assertEqual(1.0, pad.right_trigger)
        self.phone("RT:0.6")
        pad.face_set("RT", False)
        self.assertEqual(0.8, pad.right_trigger)
        self.voice("release", button="RT")
        self.assertEqual(0.6, pad.right_trigger)

    def test_finite_trigger_overlay_restores_persistent_voice_and_phone(self):
        self.phone("RT:0.3")
        self.voice("trigger", button="RT", value=0.4)
        self.voice("trigger", button="RT", value=1.0, duration_ms=200)
        pad = receiver.gamepads["player1"]
        self.assertEqual(1.0, pad.right_trigger)
        pad.face_set("RT", True)
        self.now += 0.201
        self.state.tick()
        self.assertEqual(1.0, pad.right_trigger)
        pad.face_set("RT", False)
        self.assertEqual(0.4, pad.right_trigger)
        self.voice("release", button="RT")
        self.assertEqual(0.3, pad.right_trigger)

    def test_voice_clear_preserves_phone_face_sticks_and_other_features(self):
        self.phone("X360A_HOLD")
        self.phone("LS:0.3,0.4")
        self.phone("KEY_DOWN:W")
        self.phone("MOUSE_LEFT_DOWN")
        receiver.gamepads["player1"].face_set("B", True)
        self.voice("hold", button="RB")
        self.voice("hold", player="player2", button="Y", duration_ms=500)
        before_stick = receiver.gamepads["player1"].left_stick
        self.assertNotEqual((0.0, 0.0), before_stick)
        self.assertEqual("ack", self.voice("clear")["op"])
        self.assertEqual({"A", "B"}, receiver.gamepads["player1"].buttons)
        self.assertEqual(set(), receiver.gamepads["player2"].buttons)
        self.assertEqual(before_stick, receiver.gamepads["player1"].left_stick)
        self.assertIn("w", fake_keyboard.down)
        self.assertIn("left", fake_pyautogui.down)
        self.now += 1
        self.state.tick()
        self.assertEqual({"A", "B"}, receiver.gamepads["player1"].buttons)
        self.assertEqual("error", self.voice("hold", button="X")["op"])

    def test_voice_global_release_clears_every_output_and_old_macro(self):
        face, epoch = self.arm_face()
        face.handle({"v": 1, "op": "hold", "epoch": epoch, "seq": 2,
                     "button": "B"})
        self.phone("X360A_HOLD")
        self.phone("KEY_DOWN:W")
        self.phone("MOUSE_LEFT_DOWN")
        self.phone("LT:0.5")
        self.voice("hold", player="player2", button="Y", duration_ms=500)
        self.voice("trigger", button="RT", value=0.9, duration_ms=300)
        result = self.voice("release_all")
        self.assertEqual("ack", result["op"])
        for pad in receiver.gamepads.values():
            self.assertEqual(set(), pad.buttons)
            self.assertEqual(0.0, pad.left_trigger)
            self.assertEqual(0.0, pad.right_trigger)
            self.assertEqual((0.0, 0.0), pad.left_stick)
            self.assertEqual((0.0, 0.0), pad.right_stick)
        self.assertFalse(fake_keyboard.down)
        self.assertFalse(fake_pyautogui.down)
        self.assertFalse(face.armed)
        self.assertEqual("reset", face.handle({"v": 1, "op": "hold", "epoch": epoch,
                                              "seq": 3, "button": "B"})["op"])
        self.assertEqual("error", self.voice("hold", button="A")["op"])
        self.now += 1
        self.state.tick()
        self.assertEqual(set(), receiver.gamepads["player2"].buttons)
        # Approved semantics: a new phone stream can reassert after a voice reset.
        self.phone("X360A_HOLD")
        self.assertIn("A", receiver.gamepads["player1"].buttons)
        # A genuinely new voice command uses the fresh handshake epoch.
        self.pin = self.state.ready()
        self.assertEqual("ack", self.voice("hold", button="B")["op"])

    def test_phone_global_release_invalidates_voice_timing_and_chain(self):
        self.voice("tap", button="A", duration_ms=250)
        self.phone("RELEASE_ALL:test-phone:10")
        self.now += 1
        self.state.tick()
        self.assertEqual(set(), receiver.gamepads["player1"].buttons)
        self.assertEqual("error", self.voice("hold", button="B")["op"])

    def test_voice_never_changes_phone_connection_or_camera_follow_timestamps(self):
        before = dict(receiver.camera_follow_last_input_at)
        lost = dict(receiver.camera_follow_input_lost)
        self.voice("hold", button="A")
        self.voice("trigger", button="LT", value=0.7)
        self.assertEqual({}, receiver.active_connections)
        self.assertEqual(before, receiver.camera_follow_last_input_at)
        self.assertEqual(lost, receiver.camera_follow_input_lost)

    def test_voice_cleanup_failure_does_not_interrupt_existing_global_release(self):
        class Timer:
            timers = []

            def __init__(self, duration, callback):
                self.callback = callback
                self.cancelled = False
                self.timers.append(self)

            def start(self):
                pass

            def cancel(self):
                self.cancelled = True

        self.phone("KEY_DOWN:W")
        self.phone("MOUSE_LEFT_DOWN")
        self.voice("hold", button="B", duration_ms=500)
        self.voice("hold", player="player2", button="Y")
        generation = receiver.release_generations["player1"]
        with patch.object(receiver.threading, "Timer", Timer):
            self.phone("X360A")
            with patch.object(receiver.gamepads["player1"], "voice_clear",
                              side_effect=RuntimeError("synthetic native voice failure")):
                self.assertEqual("ack", self.voice("release_all")["op"])
        self.assertTrue(Timer.timers[-1].cancelled)
        self.assertGreater(receiver.release_generations["player1"], generation)
        self.assertFalse(receiver.pending_button_release_timers["player1"])
        self.assertFalse(fake_keyboard.down)
        self.assertFalse(fake_pyautogui.down)
        for pad in receiver.gamepads.values():
            self.assertFalse(pad.buttons)
        self.assertEqual("error", self.voice("hold", button="A")["op"])
        self.now += 1
        self.state.tick()
        self.assertFalse(receiver.gamepads["player1"].buttons)

    def test_player_one_release_does_not_release_player_two(self):
        self.voice("hold", button="A")
        self.voice("hold", player="player2", button="A")
        self.voice("release", button="A")
        self.assertNotIn("A", receiver.gamepads["player1"].buttons)
        self.assertIn("A", receiver.gamepads["player2"].buttons)

    def test_phone_press_edges_preserved_when_face_voice_idle(self):
        self.phone("X360A_HOLD")
        raw = receiver.gamepads["player1"].raw
        with patch.object(raw, "release_button", wraps=raw.release_button) as release:
            self.phone("X360A_HOLD")
            release.assert_called_once_with(button="A")

    def test_phone_duplicate_press_does_not_interrupt_voice_owner(self):
        self.voice("hold", button="A")
        raw = receiver.gamepads["player1"].raw
        with patch.object(raw, "release_button", wraps=raw.release_button) as release:
            self.phone("X360A_HOLD")
            self.phone("X360A_HOLD")
            release.assert_not_called()

    def test_voice_reports_preserve_camera_follow_right_stick(self):
        pad = receiver.gamepads["player1"]
        pad.right_joystick_float(0.35, -0.2)
        self.voice("hold", button="A")
        self.voice("release", button="A")
        self.assertEqual((0.35, -0.2), pad.right_stick)


if __name__ == "__main__":
    unittest.main()
