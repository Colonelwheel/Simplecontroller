"""Synthetic VoiceAttack protocol tests; never native controller/game output.

Network tests bind only to an OS-assigned ephemeral loopback port (port=0).
They do not connect to the production receiver port or run VoiceAttack.
"""

import copy
import json
import socket
import threading
import time
import unittest
import uuid

from voice_input_server import (
    MAX_CLIENTS, MAX_DURATION_MS, MAX_SEQUENCE, MAX_SESSIONS,
    DIGITAL_BUTTONS, VoiceInputServer, VoiceInputState, ProtocolError,
    decode_request,
)


class FakeClock:
    def __init__(self):
        self.now = 100.0

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


class MockPad:
    def __init__(self):
        self.voice = {}
        self.calls = []
        self.phone = {}
        self.face = {}

    def voice_set(self, button, value):
        self.voice[button] = value
        self.calls.append((button, value))

    def voice_clear(self):
        self.voice.clear()
        self.calls.append(("clear", None))


class StateTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.pads = {"player1": MockPad(), "player2": MockPad()}
        self.global_releases = []

        def release_all():
            self.global_releases.append(True)
            for pad in self.pads.values():
                pad.phone.clear()
                pad.face.clear()
            self.state.reset(tuple(self.pads))

        self.state = VoiceInputState(self.pads, threading.RLock(), release_all, clock=self.clock)
        self.session = str(uuid.uuid4())
        self.seq = 0

    def request(self, op="tap", **kwargs):
        self.seq += 1
        request = {"v": 1, "receiver": self.state.receiver, "epoch": self.state.epoch,
                   "session": self.session, "seq": self.seq, "player": "player1", "op": op}
        if isinstance(op, str) and op in {"tap", "hold", "release"}:
            request["button"] = "A"
        elif op == "trigger":
            request.update(button="RT", value=1.0)
        request.update(kwargs)
        return request

    def command(self, op="tap", **kwargs):
        response = self.state.handle(self.request(op, **kwargs))
        self.assertEqual(response["op"], "ack", response)
        return response

    def test_default_tap_receiver_timer(self):
        self.command()
        self.assertIs(self.pads["player1"].voice["A"], True)
        self.clock.advance(.099)
        self.state.tick()
        self.assertIs(self.pads["player1"].voice["A"], True)
        self.clock.advance(.002)
        self.state.tick()
        self.assertIs(self.pads["player1"].voice["A"], False)

    def test_all_digital_buttons(self):
        for button in DIGITAL_BUTTONS:
            self.command("hold", button=button)
            self.assertIs(self.pads["player1"].voice[button], True)
            self.command("release", button=button)
            self.assertIs(self.pads["player1"].voice[button], False)

    def test_persistent_hold_survives_taps_and_finite_holds(self):
        self.command("hold")
        for op in ("tap", "hold"):
            self.command(op, duration_ms=5)
            self.clock.advance(1000)
            self.state.tick()
            self.assertIs(self.pads["player1"].voice["A"], True)
        self.command("release")
        self.assertIs(self.pads["player1"].voice["A"], False)

    def test_finite_taps_and_holds_extend_without_shortening(self):
        self.command("hold", duration_ms=200)
        self.clock.advance(.01)
        self.command("tap", duration_ms=20)
        self.command("hold", duration_ms=10)
        self.clock.advance(.10)
        self.state.tick()
        self.assertIs(self.pads["player1"].voice["A"], True)
        self.command("tap", duration_ms=200)
        self.clock.advance(.1)
        self.state.tick()
        self.assertIs(self.pads["player1"].voice["A"], True)
        self.clock.advance(.11)
        self.state.tick()
        self.assertIs(self.pads["player1"].voice["A"], False)

    def test_persistent_hold_cancels_prior_timer(self):
        self.command("tap")
        self.command("hold")
        self.clock.advance(1)
        self.state.tick()
        self.assertIs(self.pads["player1"].voice["A"], True)

    def test_release_discards_timer_for_later_persistent_hold(self):
        self.command("tap")
        self.command("release")
        self.command("hold")
        self.clock.advance(1)
        self.state.tick()
        self.assertIs(self.pads["player1"].voice["A"], True)

    def test_trigger_lower_overlay_preserves_persistent_baseline(self):
        self.command("trigger", value=.8)
        self.assertEqual(self.pads["player1"].voice["RT"], .8)
        self.command("trigger", value=.4, duration_ms=20)
        self.assertEqual(self.pads["player1"].voice["RT"], .8)
        self.clock.advance(.021)
        self.state.tick()
        self.assertEqual(self.pads["player1"].voice["RT"], .8)

    def test_finite_trigger_restores_persistent_baseline(self):
        self.command("trigger", value=.4)
        self.command("trigger", value=1.0, duration_ms=200)
        self.assertEqual(self.pads["player1"].voice["RT"], 1.0)
        self.clock.advance(.199)
        self.state.tick()
        self.assertEqual(self.pads["player1"].voice["RT"], 1.0)
        self.clock.advance(.002)
        self.state.tick()
        self.assertEqual(self.pads["player1"].voice["RT"], .4)
        self.assertNotIn(("player1", "RT"), self.state._deadlines)

    def test_persistent_trigger_during_overlay_cancels_old_timer(self):
        self.command("trigger", button="LT", value=.4)
        self.command("trigger", button="LT", value=1, duration_ms=100)
        self.command("trigger", button="LT", value=.5)
        self.assertEqual(self.pads["player1"].voice["LT"], .5)
        self.clock.advance(1)
        self.state.tick()
        self.assertEqual(self.pads["player1"].voice["LT"], .5)
        self.assertNotIn(("player1", "LT"), self.state._deadlines)

    def test_finite_trigger_without_baseline_releases_to_zero(self):
        self.command("trigger", button="LT", value=1, duration_ms=100)
        self.clock.advance(1)
        self.state.tick()
        self.assertEqual(self.pads["player1"].voice["LT"], 0)

    def test_second_finite_trigger_replaces_overlay_value_and_deadline(self):
        self.command("trigger", value=.4)
        self.command("trigger", value=1, duration_ms=200)
        self.clock.advance(.02)
        self.command("trigger", value=.7, duration_ms=50)
        self.assertEqual(self.pads["player1"].voice["RT"], .7)
        self.clock.advance(.051)
        self.state.tick()
        self.assertEqual(self.pads["player1"].voice["RT"], .4)

    def test_trigger_overlay_baselines_are_player_specific(self):
        self.command("trigger", value=.4)
        self.command("trigger", value=.6, player="player2")
        self.command("trigger", value=1, duration_ms=20)
        self.command("trigger", value=.9, duration_ms=200, player="player2")
        self.clock.advance(.021)
        self.state.tick()
        self.assertEqual(self.pads["player1"].voice["RT"], .4)
        self.assertEqual(self.pads["player2"].voice["RT"], .9)
        self.clock.advance(.18)
        self.state.tick()
        self.assertEqual(self.pads["player2"].voice["RT"], .6)

    def test_trigger_zero_and_explicit_release_cancel_timer(self):
        for op in ("release", "trigger"):
            self.command("trigger", value=.4)
            self.command("trigger", duration_ms=10)
            self.command(op, button="RT", **({"value": 0} if op == "trigger" else {}))
            self.assertEqual(self.pads["player1"].voice["RT"], 0)
            self.assertNotIn(("player1", "RT"), self.state._deadlines)
            self.assertNotIn(("player1", "RT"), self.state._persistent_triggers)
            self.clock.advance(.011)
            self.state.tick()
            self.assertEqual(self.pads["player1"].voice["RT"], 0)

    def test_cleanup_removes_trigger_baselines_and_pending_overlays(self):
        for op in ("clear", "release_all"):
            for player in self.pads:
                self.command("trigger", value=.4, player=player)
                self.command("trigger", value=1, duration_ms=200, player=player)
            self.command(op)
            self.assertFalse(self.state._persistent_triggers)
            self.assertFalse(self.state._deadlines)
            self.clock.advance(1)
            self.state.tick()
            self.assertFalse(self.pads["player1"].voice)
            self.assertFalse(self.pads["player2"].voice)

    def test_players_independent(self):
        self.command("hold", player="player2")
        self.command("tap")
        self.clock.advance(.11)
        self.state.tick()
        self.assertIs(self.pads["player1"].voice["A"], False)
        self.assertIs(self.pads["player2"].voice["A"], True)

    def test_clear_both_players_preserves_other_sources(self):
        for player, pad in self.pads.items():
            pad.phone["A"] = True
            pad.face["RT"] = 1.0
            self.command("hold", player=player)
        old_epoch = self.state.epoch
        ack = self.command("clear")
        self.assertEqual(ack["epoch"], old_epoch + 1)
        for pad in self.pads.values():
            self.assertFalse(pad.voice)
            self.assertEqual(pad.phone, {"A": True})
            self.assertEqual(pad.face, {"RT": 1.0})
        self.assertFalse(self.global_releases)

    def test_global_release_both_players_invalidates_timers(self):
        for player, pad in self.pads.items():
            pad.phone["A"] = True
            pad.face["RT"] = 1.0
            self.command("tap", player=player)
        self.command("release_all")
        self.assertEqual(self.global_releases, [True])
        self.assertEqual(self.state.epoch, 1)
        self.clock.advance(1)
        self.state.tick()
        for pad in self.pads.values():
            self.assertFalse(pad.voice)
            self.assertFalse(pad.phone)
            self.assertFalse(pad.face)

    def test_selected_player_reset(self):
        self.command("tap")
        self.command("hold", player="player2")
        self.state.reset(("player1",))
        self.assertEqual(self.state.epoch, 1)
        self.assertFalse(self.pads["player1"].voice)
        self.assertIs(self.pads["player2"].voice["A"], True)

    def test_reset_attempts_both_pads_on_native_failure(self):
        self.command("tap")
        self.command("tap", player="player2")

        def failed_clear():
            raise RuntimeError("mock native failure")

        self.pads["player1"].voice_clear = failed_clear
        with self.assertRaises(RuntimeError):
            self.state.reset(tuple(self.pads))
        self.assertFalse(self.state._deadlines)
        self.assertEqual(self.state.epoch, 1)
        self.assertFalse(self.pads["player2"].voice)

    def test_duplicate_tap_does_not_extend_deadline(self):
        request = self.request()
        self.assertEqual(self.state.handle(request)["op"], "ack")
        self.clock.advance(.06)
        self.assertEqual(self.state.handle(copy.deepcopy(request))["op"], "ack")
        self.clock.advance(.05)
        self.state.tick()
        self.assertIs(self.pads["player1"].voice["A"], False)

    def test_duplicate_clear_and_release_all_do_not_reset_twice(self):
        for op in ("clear", "release_all"):
            request = self.request(op)
            first = self.state.handle(request)
            self.assertEqual(first["op"], "ack")
            calls = sum(len(pad.calls) for pad in self.pads.values())
            self.assertEqual(self.state.handle(request), first)
            self.assertEqual(sum(len(pad.calls) for pad in self.pads.values()), calls)
        self.assertEqual(self.state.epoch, 2)
        self.assertEqual(self.global_releases, [True])

    def test_sequence_reuse_changed_or_older_rejected(self):
        request = self.request("hold")
        self.assertEqual(self.state.handle(request)["op"], "ack")
        changed = dict(request, button="B")
        self.assertEqual(self.state.handle(changed)["op"], "error")
        self.command("release")
        self.assertEqual(self.state.handle(request)["op"], "error")
        self.assertIs(self.pads["player1"].voice["A"], False)

    def test_duplicate_ack_cannot_cross_unrelated_reset(self):
        for op in ("hold", "clear", "release_all"):
            request = self.request(op)
            self.assertEqual(self.state.handle(request)["op"], "ack")
            self.state.reset(tuple(self.pads))
            self.assertEqual(self.state.handle(request)["op"], "error")
            self.assertFalse(self.pads["player1"].voice)

    def test_stale_receiver_epoch_and_prior_generation_changed_request(self):
        old = self.request("hold")
        self.command("clear")
        for invalid in (old, dict(old, receiver=str(uuid.uuid4())),
                        dict(old, seq=self.seq + 1)):
            self.assertEqual(self.state.handle(invalid)["op"], "error")
        self.assertFalse(self.pads["player1"].voice)

    def test_rejected_request_does_not_consume_seq(self):
        request = self.request("hold")
        self.assertEqual(self.state.handle(dict(request, button="KEY_A"))["op"], "error")
        self.assertEqual(self.state.handle(request)["op"], "ack")

    def test_session_limit_rejects_new_preserves_existing(self):
        for _ in range(MAX_SESSIONS):
            request = self.request("release", session=str(uuid.uuid4()))
            self.assertEqual(self.state.handle(request)["op"], "ack")
        self.assertEqual(self.state.handle(self.request("hold"))["op"], "error")
        session = next(iter(self.state._sessions))
        self.assertEqual(self.state.handle(self.request("hold", session=session))["op"], "ack")
        self.assertEqual(len(self.state._sessions), MAX_SESSIONS)

    def test_invalid_requests_no_mutation(self):
        cases = [None, [], True, {}, "hold", self.request(v=True), self.request(seq=True),
                 self.request(epoch=True), self.request(seq=0), self.request(seq=MAX_SEQUENCE + 1),
                 self.request(player="player3"), self.request(player=[]), self.request(op="keys"),
                 self.request(op=[]), self.request(button="LS:R50"), self.request(button="LT"),
                 self.request(button=[]), self.request(unexpected="ignored"),
                 self.request(session="not-a-uuid"), self.request(session=True)]
        for value in (True, False, 0, -1, MAX_DURATION_MS + 1, 1.5, "100", None):
            cases.append(self.request(duration_ms=value))
        for value in (True, False, float("nan"), float("inf"), float("-inf"), -.1, 1.1, "1", None,
                      10 ** 400):
            cases.append(self.request("trigger", value=value))
        for op in ("clear", "release_all", "release"):
            cases.append(self.request(op, duration_ms=10))
        cases.append(self.request("hold", value=1))
        cases.append(self.request("trigger", button="A"))
        cases.append(self.request("clear", button="A"))
        for request in cases:
            with self.subTest(request=request):
                self.assertEqual(self.state.handle(request)["op"], "error")
                self.assertFalse(self.state._active)
                self.assertFalse(self.state._deadlines)
                self.assertFalse(self.state._persistent_triggers)
                self.assertFalse(self.state._sessions)
                self.assertFalse(self.pads["player1"].calls)

    def test_required_button_and_trigger_value(self):
        for op in ("tap", "hold", "release", "trigger"):
            request = self.request(op)
            del request["button"]
            self.assertEqual(self.state.handle(request)["op"], "error")
        request = self.request("trigger")
        del request["value"]
        self.assertEqual(self.state.handle(request)["op"], "error")

    def test_unavailable_player(self):
        del self.pads["player2"]
        self.assertEqual(self.state.handle(self.request(player="player2"))["op"], "error")

    def test_ready_instance_identity(self):
        ready = self.state.ready()
        self.assertEqual(ready["op"], "ready")
        self.assertEqual(ready["epoch"], 0)
        self.assertEqual(str(uuid.UUID(ready["receiver"])), ready["receiver"])
        other = VoiceInputState(self.pads, threading.RLock(), lambda: None)
        self.assertNotEqual(other.receiver, ready["receiver"])

    def test_strict_json_parser(self):
        for payload in (b'{"x":1,"x":2}', b'{"value":NaN}', b'{"value":Infinity}',
                        b'{"value":-Infinity}', b'\xff', b'{}{}', b'{',
                        b"[" * 1500 + b"0" + b"]" * 1499,
                        b'{"n":' + b"9" * 5000 + b"}"):
            with self.subTest(payload=payload):
                with self.assertRaises(ProtocolError):
                    decode_request(payload)
        self.assertEqual(decode_request(b'{"v":1}'), {"v": 1})


class NetworkTests(unittest.TestCase):
    def setUp(self):
        self.pads = {"player1": MockPad(), "player2": MockPad()}
        self.state = VoiceInputState(self.pads, threading.RLock(),
                                    lambda: self.state.reset(tuple(self.pads)))
        self.logs = []
        self.server = VoiceInputServer(self.state, logger=self.logs.append, port=0).start()
        self.addCleanup(self.server.stop)
        self.sockets = []
        self.addCleanup(self.close_sockets)

    def close_sockets(self):
        for sock in self.sockets:
            sock.close()

    def connect(self):
        sock = socket.create_connection(("127.0.0.1", self.server.port), timeout=2)
        self.sockets.append(sock)
        return sock

    def read_line(self, sock):
        data = b""
        while b"\n" not in data:
            chunk = sock.recv(4096)
            self.assertTrue(chunk, "connection closed before response")
            data += chunk
        return json.loads(data.split(b"\n", 1)[0])

    def send_request(self, op="tap", **kwargs):
        sock = self.connect()
        ready = self.read_line(sock)
        request = {"v": 1, "receiver": ready["receiver"], "epoch": ready["epoch"],
                   "session": str(uuid.uuid4()), "seq": 1, "player": "player1", "op": op,
                   "button": "A"}
        request.update(kwargs)
        sock.sendall((json.dumps(request) + "\n").encode())
        ack = self.read_line(sock)
        return sock, ack

    def wait_until(self, predicate, timeout=.75):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(.005)
        self.assertTrue(predicate(), "expected mock state did not occur")

    def test_ready_ack_one_request_and_persistent_disconnect(self):
        sock, ack = self.send_request("hold")
        self.assertEqual(ack["op"], "ack")
        self.assertEqual(sock.recv(1), b"")
        time.sleep(.03)
        self.assertIs(self.pads["player1"].voice["A"], True)

    def test_slow_client_does_not_delay_timer_or_other_request(self):
        slow = self.connect()
        self.assertEqual(self.read_line(slow)["op"], "ready")
        slow.sendall(b'{"v":')
        sock, ack = self.send_request(duration_ms=30)
        self.assertEqual(ack["op"], "ack")
        self.wait_until(lambda: self.pads["player1"].voice.get("A") is False)
        self.assertEqual(sock.recv(1), b"")

    def test_idle_client_deadline_closes(self):
        slow = self.connect()
        self.read_line(slow)
        self.assertEqual(slow.recv(1), b"")

    def test_eight_slow_clients_bounded_and_timers_continue(self):
        self.state.handle({"v": 1, "receiver": self.state.receiver, "epoch": 0,
                           "session": str(uuid.uuid4()), "seq": 1, "player": "player1",
                           "op": "tap", "button": "A", "duration_ms": 40})
        for _ in range(MAX_CLIENTS):
            sock = self.connect()
            self.assertEqual(self.read_line(sock)["op"], "ready")
            sock.sendall(b"{")
        extra = self.connect()
        self.assertEqual(extra.recv(1), b"")
        self.wait_until(lambda: self.pads["player1"].voice.get("A") is False)

    def test_oversized_request_and_duplicate_json_fields_reject(self):
        for payload in (b" " * 4097, b'{"v":1,"v":1}\n', b'{"value":NaN}\n', b'{}\n{}\n',
                        b"[" * 1500 + b"0" + b"]" * 1500 + b"\n"):
            sock = self.connect()
            self.read_line(sock)
            sock.sendall(payload)
            self.assertEqual(self.read_line(sock)["op"], "error")
        self.assertFalse(self.pads["player1"].voice)
        _, ack = self.send_request("hold")
        self.assertEqual(ack["op"], "ack")

    def test_stopping_scheduler_clears_voice_only(self):
        self.pads["player1"].phone["A"] = True
        self.pads["player1"].face["X"] = True
        _, ack = self.send_request("hold")
        self.assertEqual(ack["op"], "ack")
        self.server.stop()
        self.assertFalse(self.pads["player1"].voice)
        self.assertEqual(self.pads["player1"].phone, {"A": True})
        self.assertEqual(self.pads["player1"].face, {"X": True})

    def test_scheduler_failure_clears_voice_only_and_logs(self):
        self.pads["player1"].phone["A"] = True
        self.pads["player1"].face["X"] = True
        _, ack = self.send_request("hold")
        self.assertEqual(ack["op"], "ack")

        def failed_tick():
            raise RuntimeError("synthetic scheduler failure")

        self.state.tick = failed_tick
        self.wait_until(lambda: not self.server._thread.is_alive())
        self.assertFalse(self.pads["player1"].voice)
        self.assertEqual(self.pads["player1"].phone, {"A": True})
        self.assertEqual(self.pads["player1"].face, {"X": True})
        self.assertTrue(any("synthetic scheduler failure" in message for message in self.logs))

    def test_public_bind_refused(self):
        with self.assertRaises(ValueError):
            VoiceInputServer(self.state, host="0.0.0.0", port=0)


if __name__ == "__main__":
    unittest.main()
