"""Synthetic dual-expression checks with mocked native output and local sockets."""
import json
import socket
import threading
import time
import unittest
import uuid

from face_input_server import FaceInputState, FaceInputServer, MergedGamepad, ProtocolError
from test_face_input_server import Pad


class DualStateTests(unittest.TestCase):
    def setUp(self):
        self.now = 10.0
        self.lock = threading.RLock()
        self.raw = Pad()
        self.pad = MergedGamepad(self.raw, {b: b for b in ("A", "B", "X", "Y", "RB")}, self.lock)
        self.other = MergedGamepad(Pad(), self.pad.button_map, self.lock)
        self.state = FaceInputState({"player1": self.pad, "player2": self.other}, self.lock,
                                    clock=lambda: self.now)
        self.seq = 0
        self.epoch = self.hello()["epoch"]

    def hello(self, version=2, player="player1"):
        return self.state.handle({"v": version, "op": "hello", "session": str(uuid.uuid4()),
                                  "player": player})

    def command(self, op, binding=None, **fields):
        self.seq += 1
        message = {"v": 2, "op": op, "epoch": self.epoch, "seq": self.seq, **fields}
        if binding is not None:
            message["binding"] = binding
        return self.state.handle(message)

    def arm(self):
        for binding in ("mouth", "brows"):
            self.command("arm", binding)

    def test_both_slots_start_unarmed_and_arm_independently(self):
        self.command("arm", "mouth")
        self.command("hold", "mouth", button="A")
        self.command("hold", "brows", button="B")
        self.assertEqual({"A"}, self.raw.buttons)
        self.command("arm", "brows")
        self.command("hold", "brows", button="B")
        self.assertEqual({"A", "B"}, self.raw.buttons)

    def test_shared_hold_release_keeps_other_expression_without_gap(self):
        self.arm()
        self.command("hold", "mouth", button="A")
        self.command("hold", "brows", button="A")
        self.raw.reports.clear()
        self.command("release", "mouth", button="A")
        self.assertEqual({"A"}, self.raw.buttons)
        self.assertTrue(all("A" in buttons for buttons, _ in self.raw.reports))
        self.command("release", "brows", button="A")
        self.assertFalse(self.raw.buttons)

    def test_overlapping_taps_have_separate_deadlines(self):
        self.arm()
        self.command("tap", "mouth", button="A", duration_ms=100)
        self.now += .05
        self.command("tap", "brows", button="A", duration_ms=100)
        self.now += .051
        self.state.tick()
        self.assertEqual({"A"}, self.raw.buttons)
        self.assertIsNone(self.state.channels["mouth"]["button"])
        self.now += .05
        self.state.tick()
        self.assertFalse(self.raw.buttons)

    def test_tap_expiry_preserves_other_hold_and_phone_and_voice(self):
        self.pad.press_button("A")
        self.pad.voice_set("A", True)
        self.arm()
        self.command("hold", "mouth", button="A")
        self.command("tap", "brows", button="A", duration_ms=100)
        self.now += .101
        self.state.tick()
        self.assertEqual({"A"}, self.pad.face_buttons)
        self.command("release", "mouth", button="A")
        self.assertFalse(self.pad.face_buttons)
        self.assertEqual({"A"}, self.raw.buttons)
        self.pad.release_button("A")
        self.assertEqual({"A"}, self.raw.buttons)
        self.pad.voice_clear()
        self.assertFalse(self.raw.buttons)

    def test_hold_replaces_only_its_own_tap_timer(self):
        self.arm()
        self.command("tap", "mouth", button="A", duration_ms=100)
        self.command("tap", "brows", button="B", duration_ms=100)
        self.now += .02
        self.command("hold", "mouth", button="A")
        self.now += .101
        self.state.tick()
        self.assertEqual({"A"}, self.raw.buttons)

    def test_rebind_does_not_release_other_owner_of_old_button(self):
        self.arm()
        self.command("hold", "mouth", button="A")
        self.command("hold", "brows", button="A")
        self.raw.reports.clear()
        self.command("hold", "mouth", button="B")
        self.assertEqual({"A", "B"}, self.raw.buttons)
        self.assertTrue(all("A" in buttons for buttons, _ in self.raw.reports))
        self.command("release", "brows", button="A")
        self.assertEqual({"B"}, self.raw.buttons)

    def test_wrong_button_release_cannot_clear_another_slot(self):
        self.arm()
        self.command("hold", "mouth", button="A")
        self.command("hold", "brows", button="B")
        self.command("release", "mouth", button="B")
        self.assertEqual({"A", "B"}, self.raw.buttons)

    def test_scoped_clear_disarms_only_its_slot(self):
        self.arm()
        self.command("hold", "mouth", button="A")
        self.command("hold", "brows", button="B")
        self.command("clear", "mouth")
        self.command("hold", "mouth", button="A")
        self.assertEqual({"B"}, self.raw.buttons)
        self.assertTrue(self.state.channels["brows"]["armed"])
        self.command("arm", "mouth")
        self.command("hold", "mouth", button="A")
        self.assertEqual({"A", "B"}, self.raw.buttons)

    def test_global_clear_cancels_and_disarms_both_preserving_other_sources(self):
        self.pad.press_button("X")
        self.pad.voice_set("Y", True)
        self.arm()
        self.command("hold", "mouth", button="A")
        self.command("tap", "brows", button="B", duration_ms=100)
        self.command("clear")
        self.command("hold", "mouth", button="A")
        self.command("hold", "brows", button="B")
        self.now += .11
        self.state.tick()
        self.assertEqual({"X", "Y"}, self.raw.buttons)
        self.assertTrue(all(not c["armed"] and c["deadline"] is None
                            for c in self.state.channels.values()))

    def test_both_trigger_slots_merge_with_phone_and_voice(self):
        for button in ("LT", "RT"):
            with self.subTest(button=button):
                self.command("clear")
                self.pad._trigger(button, .4, "phone")
                self.pad.voice_set(button, .7)
                self.arm()
                self.command("hold", "mouth", button=button)
                self.command("hold", "brows", button=button)
                self.command("release", "mouth", button=button)
                self.assertEqual(1.0, self.raw.triggers[button])
                self.command("release", "brows", button=button)
                self.assertEqual(.7, self.raw.triggers[button])
                self.pad.voice_clear()
                self.assertEqual(.4, self.raw.triggers[button])

    def test_reset_v2_barrier_requires_separate_fresh_arms(self):
        self.arm()
        self.command("tap", "mouth", button="A", duration_ms=100)
        self.command("hold", "brows", button="B")
        self.state.reset(("player1",))
        notice = self.state.drain_notifications()[0]
        self.assertEqual({"v": 2, "op": "reset", "epoch": self.epoch + 1}, notice)
        self.assertEqual(notice, self.command("hold", "mouth", button="A"))
        self.epoch = notice["epoch"]
        self.command("hold", "brows", button="B")
        self.assertFalse(self.raw.buttons)
        self.command("arm", "mouth")
        self.command("hold", "mouth", button="A")
        self.now += .11
        self.state.tick()
        self.assertEqual({"A"}, self.raw.buttons)
        self.assertFalse(self.state.channels["brows"]["armed"])

    def test_timeout_clears_every_slot_only_and_never_actions_extend_lease(self):
        self.pad.voice_set("Y", True)
        self.pad.press_button("X")
        self.arm()
        self.command("hold", "mouth", button="A")
        self.now += .4
        self.command("hold", "brows", button="B")
        self.now += .1
        self.assertTrue(self.state.tick())
        self.assertEqual({"X", "Y"}, self.raw.buttons)
        self.assertIsNone(self.state.session)

    def test_heartbeat_keeps_both_holds_alive(self):
        self.arm()
        self.command("hold", "mouth", button="A")
        self.command("hold", "brows", button="B")
        for _ in range(12):
            self.now += .1
            self.command("heartbeat")
            self.assertFalse(self.state.tick())
        self.assertEqual({"A", "B"}, self.raw.buttons)

    def test_disconnect_then_legacy_reconnect_remains_single_binding(self):
        self.arm()
        self.command("hold", "mouth", button="A")
        self.command("hold", "brows", button="B")
        self.state.disconnect()
        ready = self.hello(version=1)
        self.assertEqual(1, ready["v"])
        for seq, fields in enumerate(({"op": "arm"}, {"op": "hold", "button": "A"},
                                      {"op": "hold", "button": "B"}), 1):
            self.state.handle({"v": 1, "epoch": ready["epoch"], "seq": seq, **fields})
        self.assertEqual({"B"}, self.raw.buttons)
        self.assertTrue(all(not c["armed"] for c in self.state.channels.values()))

    def test_protocol_fields_rejected_without_mutation(self):
        self.arm()
        self.command("hold", "mouth", button="A")
        cases = [dict(op="arm"), dict(op="hold", binding="nose", button="B"),
                 dict(op="release", binding=None, button="A"),
                 dict(op="clear", binding="nose"), dict(op="heartbeat", binding="mouth"),
                 dict(op="tap", binding="mouth", button="B", duration_ms=True),
                 dict(op="tap", binding="mouth", button="B", duration_ms=99),
                 dict(op="hold", binding="mouth", button="B", v=1),
                 dict(op="hold", binding="mouth", button="B", seq=True),
                 dict(op="hold", binding="mouth", button="B", seq=self.seq)]
        for fields in cases:
            with self.subTest(fields=fields), self.assertRaises(ProtocolError):
                self.state.handle({"v": 2, "epoch": self.epoch, "seq": self.seq + 1, **fields})
            self.assertEqual({"A"}, self.raw.buttons)

    def test_player2_dual_slots_do_not_change_player1(self):
        self.pad.voice_set("Y", True)
        self.state.disconnect()
        self.epoch = self.hello(player="player2")["epoch"]
        self.arm()
        self.command("hold", "mouth", button="A")
        self.command("hold", "brows", button="B")
        self.assertEqual({"Y"}, self.raw.buttons)
        self.assertEqual({"A", "B"}, self.other.raw.buttons)
        self.state.reset(("player1",))
        self.assertEqual([], self.state.drain_notifications())
        self.assertEqual({"A", "B"}, self.other.raw.buttons)


class DualReceiverTests(unittest.TestCase):
    def setUp(self):
        from test_receiver_release_all import receiver
        self.receiver = receiver
        receiver.face_input_state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), "dual mock isolation")
        receiver.active_connections.clear()
        self.state = receiver.face_input_state
        self.epoch = self.state.handle({"v": 2, "op": "hello", "session": str(uuid.uuid4()),
                                      "player": "player1"})["epoch"]
        self.seq = 0

    def tearDown(self):
        self.state.disconnect()
        self.receiver.release_outputs(tuple(self.receiver.gamepads), "dual mock cleanup")

    command = DualStateTests.command
    arm = DualStateTests.arm

    def test_dual_messages_do_not_touch_phone_or_camera_follow(self):
        before = dict(self.receiver.camera_follow_last_input_at)
        lost = dict(self.receiver.camera_follow_input_lost)
        self.arm()
        self.command("hold", "mouth", button="A")
        self.command("tap", "brows", button="RT", duration_ms=100)
        self.command("heartbeat")
        self.command("clear", "mouth")
        self.assertEqual({}, self.receiver.active_connections)
        self.assertEqual(before, self.receiver.camera_follow_last_input_at)
        self.assertEqual(lost, self.receiver.camera_follow_input_lost)

    def test_release_all_clears_phone_voice_both_faces_and_both_epoch_barriers(self):
        self.arm()
        self.command("hold", "mouth", button="A")
        self.command("tap", "brows", button="B", duration_ms=100)
        self.receiver.handle_button_press("X360X_HOLD")
        voice = self.receiver.voice_input_state
        pin = voice.ready()
        voice_message = {"v": 1, "receiver": pin["receiver"], "epoch": pin["epoch"],
                         "session": str(uuid.uuid4()), "seq": 1, "player": "player1",
                         "op": "hold", "button": "Y"}
        self.assertEqual("ack", voice.handle(voice_message)["op"])
        self.receiver.release_all_outputs(str(uuid.uuid4()), 1)
        self.assertFalse(self.receiver.gamepads["player1"].buttons)
        self.assertEqual("reset", self.command("hold", "mouth", button="A")["op"])
        stale_voice = voice.handle({**voice_message, "seq": 2})
        self.assertEqual("error", stale_voice["op"])
        self.assertEqual("stale epoch", stale_voice["message"])
        self.assertTrue(all(not c["armed"] and c["deadline"] is None
                            for c in self.state.channels.values()))


class DualSocketTests(unittest.TestCase):
    def setUp(self):
        self.lock = threading.RLock()
        self.raw = Pad()
        self.pad = MergedGamepad(self.raw, {b: b for b in ("A", "B", "X", "Y")}, self.lock)
        self.state = FaceInputState({"player1": self.pad}, self.lock)
        self.server = FaceInputServer(self.state, port=0)
        self.server.start()
        self.addCleanup(self.server.stop)
        self.client = socket.create_connection(("127.0.0.1", self.server.port), timeout=1)
        self.client.settimeout(1)
        self.addCleanup(self.client.close)
        self.send({"v": 2, "op": "hello", "session": str(uuid.uuid4()), "player": "player1"})
        ready = json.loads(self.client.recv(4096))
        self.assertEqual((2, "ready"), (ready["v"], ready["op"]))
        self.epoch, self.seq = ready["epoch"], 0

    def send(self, message):
        self.client.sendall((json.dumps(message) + "\n").encode())

    def command(self, op, binding=None, **fields):
        self.seq += 1
        self.send({"v": 2, "op": op, "epoch": self.epoch, "seq": self.seq,
                   **({"binding": binding} if binding is not None else {}), **fields})

    def until(self, predicate):
        deadline = time.monotonic() + 1
        while not predicate() and time.monotonic() < deadline:
            time.sleep(.005)
        self.assertTrue(predicate())

    def hold_both(self):
        for binding, button in (("mouth", "A"), ("brows", "B")):
            self.command("arm", binding)
            self.command("hold", binding, button=button)
        self.until(lambda: self.raw.buttons >= {"A", "B"})

    def test_malformed_packet_closes_all_facial_slots_preserves_phone_voice(self):
        self.pad.press_button("X")
        self.pad.voice_set("Y", True)
        self.hold_both()
        self.command("clear", "nose")
        self.until(lambda: self.state.session is None)
        self.assertEqual({"X", "Y"}, self.raw.buttons)
        self.assertEqual(b"", self.client.recv(1))

    def test_disconnect_clears_both_slots_preserves_voice(self):
        self.pad.voice_set("Y", True)
        self.hold_both()
        self.client.close()
        self.until(lambda: self.state.session is None)
        self.assertEqual({"Y"}, self.raw.buttons)

    def test_reset_notification_is_v2_and_requires_two_arms(self):
        self.hold_both()
        self.state.reset(("player1",))
        reset = json.loads(self.client.recv(4096))
        self.assertEqual((2, "reset"), (reset["v"], reset["op"]))
        self.epoch = reset["epoch"]
        self.command("hold", "brows", button="B")
        self.command("arm", "mouth")
        self.command("hold", "mouth", button="A")
        self.until(lambda: "A" in self.raw.buttons)
        self.assertEqual({"A"}, self.raw.buttons)

    def test_timeout_clears_both_slots_preserves_voice(self):
        self.pad.voice_set("Y", True)
        self.hold_both()
        self.until(lambda: self.state.session is None)
        self.assertEqual({"Y"}, self.raw.buttons)
        self.assertEqual(b"", self.client.recv(1))


if __name__ == "__main__":
    unittest.main()
