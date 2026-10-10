"""Synthetic desktop-input ownership and actual receiver paths; native mocks only."""
import threading
import unittest
import uuid
from unittest.mock import patch

from face_input_server import FaceInputState, MergedDesktopInput, MergedGamepad, ProtocolError
from test_face_input_server import Pad


class DesktopStateTests(unittest.TestCase):
    def setUp(self):
        self.now = 10.
        self.lock = threading.RLock()
        self.held = set()
        self.events = []

        def write(target, pressed):
            self.events.append((target, pressed))
            (self.held.add if pressed else self.held.discard)(target)

        self.desktop = MergedDesktopInput(write, self.lock)
        self.pad = MergedGamepad(Pad(), {"A": "A", "B": "B"}, self.lock,
                                 desktop=self.desktop, player="player1")
        self.other = MergedGamepad(Pad(), self.pad.button_map, self.lock,
                                   desktop=self.desktop, player="player2")
        self.state = FaceInputState({"player1": self.pad, "player2": self.other}, self.lock,
                                    clock=lambda: self.now)
        self.epoch = self.state.handle({"v": 2, "op": "hello", "session": str(uuid.uuid4()),
                                        "player": "player1"})["epoch"]
        self.seq = 0

    def command(self, op, binding=None, **fields):
        self.seq += 1
        return self.state.handle({"v": 2, "op": op, "epoch": self.epoch, "seq": self.seq,
            **({"binding": binding} if binding else {}), **fields})

    def arm(self):
        self.command("arm", "mouth")
        self.command("arm", "brows")

    def test_ready_and_reset_advertise_desktop_backend(self):
        self.assertTrue(self.state._notice("ready")["desktop_input"])
        self.state.reset(("player1",))
        self.assertTrue(self.state.drain_notifications()[0]["desktop_input"])

    def test_no_backend_does_not_claim_capability_or_dispatch_desktop_tokens(self):
        state = FaceInputState({"player1": MergedGamepad(Pad(), {"A": "A"}, self.lock)}, self.lock)
        ready = state.handle({"v": 1, "op": "hello", "session": str(uuid.uuid4())})
        self.assertNotIn("desktop_input", ready)
        with self.assertRaises(ProtocolError):
            state.handle({"v": 1, "op": "hold", "epoch": ready["epoch"], "seq": 1,
                          "button": "SPACE"})

    def test_all_three_phone_face_release_orders_preserve_remaining_owner(self):
        self.arm()
        for target in ("MOUSE_LEFT", "MOUSE_RIGHT", "SPACE"):
            with self.subTest(target=target):
                self.desktop.phone_set(target, True)
                self.command("hold", "mouth", button=target)
                self.command("release", "mouth", button=target)
                self.assertIn(target, self.held)
                self.command("hold", "mouth", button=target)
                self.desktop.phone_set(target, False)
                self.assertIn(target, self.held)
                self.command("release", "mouth", button=target)
                self.assertNotIn(target, self.held)

    def test_dual_same_desktop_output_tap_expiry_and_hold_have_no_release_gap(self):
        self.arm()
        for target in ("MOUSE_LEFT", "MOUSE_RIGHT", "SPACE"):
            with self.subTest(target=target):
                self.command("hold", "mouth", button=target)
                self.command("tap", "brows", button=target, duration_ms=100)
                self.events.clear()
                self.now += .101
                self.command("heartbeat")
                self.state.tick()
                self.assertIn(target, self.held)
                self.assertNotIn((target, False), self.events)
                self.command("release", "mouth", button=target)
                self.assertNotIn(target, self.held)

    def test_different_desktop_outputs_and_xbox_rebinding_are_independent(self):
        self.arm()
        self.command("hold", "mouth", button="MOUSE_LEFT")
        self.command("hold", "brows", button="SPACE")
        self.command("hold", "mouth", button="A")
        self.assertEqual({"SPACE"}, self.held)
        self.assertEqual({"A"}, self.pad.raw.buttons)
        self.command("clear", "brows")
        self.assertFalse(self.held)
        self.assertEqual({"A"}, self.pad.raw.buttons)

    def test_tap_receiver_time_is_exactly_100ms(self):
        self.arm()
        self.command("tap", "mouth", button="MOUSE_RIGHT", duration_ms=100)
        self.now += .099
        self.state.tick()
        self.assertIn("MOUSE_RIGHT", self.held)
        self.now += .002
        self.state.tick()
        self.assertFalse(self.held)

    def test_timeout_clears_desktop_face_preserves_phone_and_voice_xbox(self):
        self.desktop.phone_set("SPACE", True)
        self.pad.voice_set("B", True)
        self.arm()
        self.command("hold", "mouth", button="MOUSE_LEFT")
        self.command("hold", "brows", button="SPACE")
        self.now += .5
        self.assertTrue(self.state.tick())
        self.assertEqual({"SPACE"}, self.held)
        self.assertEqual({"B"}, self.pad.raw.buttons)
        self.assertFalse(any(self.desktop.face.values()))

    def test_scoped_and_global_clear_disarm_desktop_bindings(self):
        self.arm()
        self.command("hold", "mouth", button="MOUSE_RIGHT")
        self.command("hold", "brows", button="SPACE")
        self.command("clear", "mouth")
        self.command("hold", "mouth", button="MOUSE_RIGHT")
        self.assertEqual({"SPACE"}, self.held)
        self.command("clear")
        self.command("hold", "brows", button="SPACE")
        self.assertFalse(self.held)

    def test_reset_rejects_stale_desktop_continuations_and_cancels_timers(self):
        self.arm()
        self.command("tap", "mouth", button="SPACE", duration_ms=100)
        self.command("hold", "brows", button="MOUSE_RIGHT")
        self.state.reset(("player1",))
        notice = self.state.drain_notifications()[0]
        self.assertEqual(notice, self.command("hold", "mouth", button="SPACE"))
        self.assertFalse(self.held)
        self.assertTrue(all(not c["armed"] and c["deadline"] is None
                            for c in self.state.channels.values()))

    def test_legacy_v1_can_select_desktop_output_without_dual_binding_field(self):
        self.state.disconnect()
        ready = self.state.handle({"v": 1, "op": "hello", "session": str(uuid.uuid4())})
        self.assertTrue(ready["desktop_input"])
        self.state.handle({"v": 1, "op": "arm", "epoch": ready["epoch"], "seq": 1})
        self.state.handle({"v": 1, "op": "hold", "epoch": ready["epoch"], "seq": 2, "button": "SPACE"})
        self.assertEqual({"SPACE"}, self.held)
        self.state.disconnect()
        self.assertFalse(self.held)

    def test_player2_desktop_is_same_windows_target_without_affecting_player1_pad(self):
        self.pad.voice_set("A", True)
        self.desktop.phone_set("MOUSE_LEFT", True)
        self.state.disconnect()
        self.epoch = self.state.handle({"v": 2, "op": "hello", "session": str(uuid.uuid4()),
                                       "player": "player2"})["epoch"]
        self.arm()
        self.command("hold", "mouth", button="SPACE")
        self.command("hold", "brows", button="MOUSE_LEFT")
        self.assertEqual({"SPACE", "MOUSE_LEFT"}, self.held)
        self.state.disconnect()
        self.assertEqual({"MOUSE_LEFT"}, self.held)
        self.assertEqual({"A"}, self.pad.raw.buttons)

    def test_phone_duplicates_preserve_legacy_events_when_no_face_owner(self):
        for target in ("MOUSE_LEFT", "MOUSE_RIGHT", "SPACE"):
            self.events.clear()
            for pressed in (True, True, False, False):
                self.desktop.phone_set(target, pressed)
            self.assertEqual([(target, p) for p in (True, True, False, False)], self.events)

    def test_bare_phone_tap_does_not_consume_persistent_space_owner(self):
        self.desktop.phone_set("SPACE", True)
        self.arm()
        self.command("hold", "mouth", button="SPACE")
        self.events.clear()
        self.desktop.phone_tap("SPACE")
        self.assertEqual([], self.events)
        self.command("release", "mouth", button="SPACE")
        self.assertEqual({"SPACE"}, self.held)
        self.assertIn("SPACE", self.desktop.phone)
        self.desktop.phone_set("SPACE", False)
        self.assertFalse(self.held)

    def test_bare_phone_tap_without_face_keeps_normal_down_up_and_prior_phone_hold(self):
        self.desktop.phone_set("SPACE", True)
        self.events.clear()
        self.desktop.phone_tap("SPACE")
        self.assertEqual([("SPACE", True), ("SPACE", False)], self.events)
        self.assertIn("SPACE", self.desktop.phone)
        self.desktop.resync_phone("SPACE")
        self.assertEqual({"SPACE"}, self.held)

    def test_face_join_after_legacy_bare_space_tap_restores_known_up_immediately(self):
        self.desktop.phone_set('SPACE', True)
        self.desktop.phone_tap('SPACE')
        self.assertIn('SPACE', self.desktop.phone)
        self.assertNotIn('SPACE', self.held)
        self.assertFalse(self.desktop.emitted['SPACE'])
        self.arm()
        self.command('hold', 'mouth', button='SPACE')
        self.assertIn('SPACE', self.held)
        self.desktop.phone_set('SPACE', False)
        self.assertIn('SPACE', self.held)
        self.command('release', 'mouth', button='SPACE')
        self.assertNotIn('SPACE', self.held)

    def test_native_release_failure_still_invalidates_epochs_and_clears_other_outputs(self):
        self.arm()
        self.command("tap", "mouth", button="MOUSE_LEFT", duration_ms=100)
        self.command("hold", "brows", button="SPACE")
        self.pad.face_set("A", True)
        original = self.desktop.write

        def fail_left_release(target, pressed):
            if target == "MOUSE_LEFT" and not pressed:
                raise OSError("synthetic mouse release failure")
            original(target, pressed)

        with patch.object(self.desktop, "write", fail_left_release):
            with self.assertRaises(OSError):
                self.state.reset(("player1",))
        self.assertEqual({"MOUSE_LEFT"}, self.held)  # Failed native write cannot be promised successful.
        self.assertFalse(any(self.desktop.face.values()))
        self.assertFalse(self.pad.raw.buttons)
        self.assertTrue(all(not c["armed"] and c["deadline"] is None
                            for c in self.state.channels.values()))
        self.assertEqual("reset", self.command("hold", "brows", button="SPACE")["op"])
        self.state.disconnect()
        self.assertIsNone(self.state.session)


class DesktopReceiverTests(unittest.TestCase):
    def setUp(self):
        from test_receiver_release_all import receiver, fake_keyboard, fake_pyautogui
        self.receiver, self.keyboard, self.mouse = receiver, fake_keyboard, fake_pyautogui
        receiver.face_input_state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), "desktop mock isolation")
        receiver.active_connections.clear()
        self.keyboard.down.clear()
        self.mouse.down.clear()
        self.state = receiver.face_input_state
        ready = self.state.handle({"v": 2, "op": "hello", "session": str(uuid.uuid4()), "player": "player1"})
        self.assertTrue(ready["desktop_input"])
        self.epoch, self.seq = ready["epoch"], 0

    def tearDown(self):
        self.state.disconnect()
        self.receiver.release_outputs(tuple(self.receiver.gamepads), "desktop mock cleanup")

    command = DesktopStateTests.command
    arm = DesktopStateTests.arm

    def phone(self, payload, player="player1"):
        return self.receiver.process_command(player + ":" + payload, ("127.0.0.1", 31234))

    def test_left_and_right_phone_commands_cannot_release_face_hold(self):
        self.arm()
        for target, button in (("MOUSE_LEFT", "left"), ("MOUSE_RIGHT", "right")):
            self.command("hold", "mouth", button=target)
            self.phone(target + "_DOWN")
            self.phone(target + "_UP")
            self.assertIn(button, self.mouse.down)
            self.command("release", "mouth", button=target)
            self.assertNotIn(button, self.mouse.down)

    def test_phone_mouse_hold_survives_face_release_and_phone2_keeps_existing_mouse_access(self):
        self.arm()
        self.phone("MOUSE_RIGHT_DOWN", "player2")
        self.command("hold", "mouth", button="MOUSE_RIGHT")
        self.command("release", "mouth", button="MOUSE_RIGHT")
        self.assertIn("right", self.mouse.down)
        self.phone("MOUSE_RIGHT_UP", "player2")
        self.assertNotIn("right", self.mouse.down)

    def test_phone_space_hold_bare_tap_face_release_and_periodic_resync_preserve_phone(self):
        self.arm()
        self.phone("KEY_DOWN:SPACE")
        self.command("hold", "mouth", button="SPACE")
        self.phone("SPACE")
        self.command("release", "mouth", button="SPACE")
        self.assertIn("space", self.keyboard.down)
        self.assertIn("SPACE", self.receiver.desktop_input.phone)
        self.keyboard.down.discard("space")
        self.receiver.desktop_input.phone.discard("SPACE")  # Simulated inconsistent old ownership.
        self.receiver.clean_key_states()
        self.assertIn("space", self.keyboard.down)
        self.assertIn("SPACE", self.receiver.desktop_input.phone)
        self.phone("KEY_UP:SPACE")
        self.assertNotIn("space", self.keyboard.down)

    def test_legacy_bare_space_tap_before_face_hold_reasserts_without_resync_delay(self):
        self.arm()
        self.phone('KEY_DOWN:SPACE')
        self.phone('SPACE')
        self.assertNotIn('space', self.keyboard.down)
        self.command('hold', 'mouth', button='SPACE')
        self.assertIn('space', self.keyboard.down)
        self.phone('KEY_UP:SPACE')
        self.assertIn('space', self.keyboard.down)
        self.command('release', 'mouth', button='SPACE')
        self.assertNotIn('space', self.keyboard.down)

    def test_space_phone_bare_tap_and_up_cannot_cut_short_face_hold(self):
        self.arm()
        self.command("hold", "mouth", button="SPACE")
        self.phone("SPACE")
        self.phone("KEY_UP:SPACE")
        self.assertIn("space", self.keyboard.down)
        self.command("release", "mouth", button="SPACE")
        self.assertNotIn("space", self.keyboard.down)

    def test_phone_space_duplicate_keydown_up_and_bare_tap_preserve_events_without_face(self):
        with patch.object(self.keyboard, "press", wraps=self.keyboard.press) as down, \
             patch.object(self.keyboard, "release", wraps=self.keyboard.release) as up:
            self.phone("KEY_DOWN:SPACE")
            self.phone("KEY_DOWN:SPACE")
            self.phone("KEY_UP:SPACE")
            self.phone("KEY_UP:SPACE")
            self.phone("SPACE")
        self.assertEqual(3, down.call_count)
        self.assertEqual(3, up.call_count)

    def test_phone_player2_space_does_not_gain_keyboard_output(self):
        self.phone("KEY_DOWN:SPACE", "player2")
        self.phone("SPACE", "player2")
        self.assertNotIn("space", self.keyboard.down)
        self.assertNotIn("SPACE", self.receiver.desktop_input.phone)

    def test_supported_space_aliases_share_facial_ownership_and_global_cleanup(self):
        self.arm()
        for alias in ('SPACEBAR', 'space bar', ' '):
            with self.subTest(alias=alias):
                self.receiver.handle_key_press(alias)
                self.command("hold", "mouth", button="SPACE")
                self.receiver.handle_button_press(alias)
                self.command("release", "mouth", button="SPACE")
                self.assertIn("space", self.keyboard.down)
                self.receiver.handle_key_release(alias)
                self.assertNotIn("space", self.keyboard.down)
        self.phone('KEY_DOWN:SPACEBAR')
        self.command("hold", "mouth", button="SPACE")
        self.receiver.release_outputs(tuple(self.receiver.gamepads), 'alias cleanup')
        self.assertFalse(self.keyboard.down)
        self.assertFalse(self.receiver.desktop_input.phone)

    def test_space_resync_with_face_hold_keeps_phone_source_for_later_face_release(self):
        self.arm()
        self.phone("KEY_DOWN:SPACE")
        self.command("hold", "mouth", button="SPACE")
        self.keyboard.down.clear()
        self.receiver.clean_key_states()
        self.command("release", "mouth", button="SPACE")
        self.assertIn("space", self.keyboard.down)

    def test_global_release_clears_all_desktop_phone_face_voice_sources(self):
        self.arm()
        self.phone("KEY_DOWN:SPACE")
        self.phone("MOUSE_LEFT_DOWN")
        self.command("hold", "mouth", button="SPACE")
        self.command("tap", "brows", button="MOUSE_RIGHT", duration_ms=100)
        self.receiver.gamepads["player1"].voice_set("A", True)
        self.receiver.release_all_outputs(str(uuid.uuid4()), 1)
        self.assertFalse(self.keyboard.down)
        self.assertFalse(self.mouse.down)
        self.assertFalse(self.receiver.desktop_input.phone)
        self.assertFalse(any(self.receiver.desktop_input.face.values()))
        self.assertFalse(self.receiver.gamepads["player1"].buttons)
        self.assertEqual("reset", self.command("hold", "mouth", button="SPACE")["op"])

    def test_desktop_face_packets_leave_phone_camera_follow_and_mouse_tracking_untouched(self):
        before = dict(self.receiver.camera_follow_last_input_at)
        lost = dict(self.receiver.camera_follow_input_lost)
        mouse_states = {k: dict(v) for k, v in self.receiver.mouse_states.items()}
        self.arm()
        self.command("hold", "mouth", button="MOUSE_LEFT")
        self.command("hold", "brows", button="SPACE")
        self.command("heartbeat")
        self.assertEqual({}, self.receiver.active_connections)
        self.assertEqual(before, self.receiver.camera_follow_last_input_at)
        self.assertEqual(lost, self.receiver.camera_follow_input_lost)
        self.assertEqual(mouse_states, self.receiver.mouse_states)
        self.assertTrue(all(not keys for keys in self.receiver.key_states.values()))

    def test_native_mouse_failure_does_not_skip_space_voice_phone_xbox_or_other_mouse_cleanup(self):
        self.arm()
        self.phone("KEY_DOWN:W")
        self.phone("MOUSE_RIGHT_DOWN")
        self.phone("MOUSE_MIDDLE_DOWN")
        self.phone("X360B_HOLD")
        self.receiver.gamepads["player1"].voice_set("A", True)
        self.command("tap", "mouth", button="MOUSE_LEFT", duration_ms=100)
        self.command("hold", "brows", button="SPACE")
        original = self.mouse.mouseUp

        def fail_left(button):
            if button == "left":
                raise OSError("synthetic left mouse release failure")
            original(button)

        with patch.object(self.mouse, "mouseUp", fail_left), self.assertLogs(level="WARNING") as logs:
            self.receiver.release_all_outputs(str(uuid.uuid4()), 1)
        self.assertTrue(any("Facial input cleanup failed" in line for line in logs.output))
        self.assertFalse(self.keyboard.down)
        self.assertEqual({"left"}, self.mouse.down)
        self.assertFalse(self.receiver.gamepads["player1"].buttons)
        self.assertFalse(any(self.receiver.desktop_input.face.values()))
        self.assertEqual("reset", self.command("hold", "brows", button="SPACE")["op"])
        self.assertTrue(all(not c["armed"] and c["deadline"] is None
                            for c in self.state.channels.values()))

    def test_failed_bare_space_tap_retains_cleanup_ownership_even_without_key_tracker(self):
        original = self.keyboard.release
        with patch.object(self.keyboard, "release", side_effect=OSError("synthetic Space up failure")):
            self.phone("SPACE")
        self.assertIn("SPACE", self.receiver.desktop_input.phone)
        self.assertFalse(self.receiver.key_states["player1"])
        self.receiver.release_outputs(tuple(self.receiver.gamepads), "failed tap cleanup")
        self.assertNotIn("SPACE", self.receiver.desktop_input.phone)
        self.assertNotIn("space", self.keyboard.down)

    def test_native_space_failure_does_not_skip_other_mouse_xbox_voice_cleanup(self):
        self.arm()
        self.phone('MOUSE_LEFT_DOWN')
        self.phone('MOUSE_MIDDLE_DOWN')
        self.phone('X360B_HOLD')
        self.receiver.gamepads['player1'].voice_set('A', True)
        self.command('tap', 'mouth', button='SPACE', duration_ms=100)
        self.command('hold', 'brows', button='MOUSE_RIGHT')
        original = self.keyboard.release

        def fail_space(key):
            if key == 'space':
                raise OSError('synthetic Space release failure')
            original(key)

        with patch.object(self.keyboard, 'release', fail_space), self.assertLogs(level='WARNING'):
            self.receiver.release_all_outputs(str(uuid.uuid4()), 1)
        self.assertFalse(self.mouse.down)
        self.assertFalse(self.receiver.gamepads['player1'].buttons)
        self.assertFalse(any(self.receiver.desktop_input.face.values()))
        self.assertTrue(all(not c['armed'] and c['deadline'] is None
                            for c in self.state.channels.values()))
        self.assertIsNone(self.receiver.desktop_input.emitted['SPACE'])
        self.assertEqual('reset', self.command('hold', 'mouth', button='SPACE')['op'])
        # Cleanup can retry only our uncertain injected output, without replaying holds.
        self.state.disconnect()
        self.assertNotIn('space', self.keyboard.down)

    def test_failed_face_space_disconnect_can_be_released_by_later_explicit_global_cleanup(self):
        self.arm()
        self.command('hold', 'mouth', button='SPACE')
        with patch.object(self.keyboard, 'release', side_effect=OSError('synthetic persistent release failure')):
            with self.assertRaises(OSError):
                self.state.disconnect()
        self.assertIsNone(self.state.session)
        self.assertIsNone(self.state.player)
        self.assertFalse(self.receiver.desktop_input.phone)
        self.assertFalse(any(self.receiver.desktop_input.face.values()))
        self.assertIsNone(self.receiver.desktop_input.emitted['SPACE'])
        self.assertIn('space', self.keyboard.down)
        self.receiver.release_all_outputs(str(uuid.uuid4()), 1)
        self.assertNotIn('space', self.keyboard.down)
        self.assertFalse(self.receiver.desktop_input.emitted['SPACE'])

    def test_global_release_serializes_with_space_resync_and_cannot_be_reasserted_afterward(self):
        self.phone('KEY_DOWN:SPACE')
        self.keyboard.down.clear()
        entered, finish, released = threading.Event(), threading.Event(), threading.Event()
        original = self.keyboard.press

        def blocked(key):
            entered.set()
            self.assertTrue(finish.wait(1))
            original(key)

        def release():
            self.receiver.release_outputs(tuple(self.receiver.gamepads), 'serialized resync cleanup')
            released.set()

        with patch.object(self.keyboard, 'press', blocked):
            resync = threading.Thread(target=self.receiver.clean_key_states)
            resync.start()
            self.assertTrue(entered.wait(1))
            resetter = threading.Thread(target=release)
            resetter.start()
            self.assertFalse(released.wait(.02))
            finish.set()
            resync.join(1)
            resetter.join(1)
        self.assertTrue(released.is_set())
        self.assertFalse(self.keyboard.down)
        self.assertFalse(self.receiver.desktop_input.phone)
        self.receiver.clean_key_states()
        self.assertFalse(self.keyboard.down)


if __name__ == "__main__":
    unittest.main()
