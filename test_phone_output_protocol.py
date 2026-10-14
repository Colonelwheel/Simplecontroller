"""Scoped phone protocol tests with no native imports, sockets, or live input."""
import threading
import unittest
from urllib.parse import quote_plus

from phone_output_protocol import PhoneOutputProtocolState, state_key


class PhoneProtocolTests(unittest.TestCase):
    def setUp(self):
        self.states = []
        self.events = []
        self.barrier = -1
        self.protocol = PhoneOutputProtocolState(threading.RLock(),
            lambda session, sequence: sequence > self.barrier,
            lambda command, player: self.states.append((command, player)),
            lambda command, player, guard, session: self.events.append((command, player, guard)))
        self.protocol.process("PHONE_CAPS_V1:process:nonce", "player1")
        self.protocol.process("PHONE_ACTIVATE_V1:process:0", "player1")

    def state(self, sequence, command, player="player1", session="process", key=None):
        key = key or state_key(command, player)
        return self.protocol.process(f"PHONE_STATE_V1:{session}:{sequence}:{quote_plus(key)}:{quote_plus(command)}", player)

    def event(self, sequence, command, scope="ring", generation=0, event=0):
        return self.protocol.process(f"PHONE_EVENT_V1:process:{sequence}:{quote_plus(scope)}:{generation}:{event}:{quote_plus(command)}", "player1")

    def end(self, sequence, scope="ring", generation=0):
        return self.protocol.process(f"PHONE_END_V1:process:{sequence}:{quote_plus(scope)}:{generation}", "player1")

    def test_capability_is_required_and_legacy_messages_are_unhandled(self):
        self.assertEqual((False, None), self.protocol.process("RT:1", "player1"))
        self.state(1, "RT:1", session="old")
        self.assertFalse(self.states)
        self.assertEqual((True, "PHONE_CAPS_V1_ACK:process:new:PHONE_OUTPUT_V1"),
                         self.protocol.process("PHONE_CAPS_V1:process:new", "player1"))

    def test_old_hold_cannot_follow_new_scoped_release(self):
        self.state(11, "RT:0.0")
        self.state(10, "RT:1.0")
        self.assertEqual([("RT:0.0", "player1")], self.states)

    def test_output_freshness_is_independent_and_duplicate_ack_does_not_repress(self):
        self.state(20, "RT:1")
        self.state(2, "LT:1")
        self.assertEqual((True, "PHONE_ACK_V1:process:20"), self.state(20, "RT:1"))
        self.assertEqual(2, len(self.states))

    def test_invalid_value_and_key_do_not_poison_freshness(self):
        self.state(100, "RT:1", key="player1|TRIGGER|LT")
        self.protocol.process("PHONE_STATE_V1:process:99:player1%7CTRIGGER%7CRT:RT%3Anan", "player1")
        self.state(1, "RT:0.4")
        self.assertEqual([("RT:0.4", "player1")], self.states)

    def test_end_tombstone_blocks_old_mouse_and_delayed_continuation_but_other_ring_lives(self):
        self.event(1, "A,WAIT_100,B")
        old_guard = self.events[0][2]
        self.assertTrue(old_guard())
        self.end(2)
        self.assertFalse(old_guard())
        self.event(3, "GAME_DELTA:1,2", event=1)
        self.event(4, "DELTA:3,4", scope="other")
        self.assertEqual(["A,WAIT_100,B", "DELTA:3,4"], [e[0] for e in self.events])

    def test_later_scope_generation_works_and_old_end_does_not_close_it(self):
        self.end(1)
        self.event(2, "DELTA:1,0", generation=1)
        self.end(3)
        self.assertTrue(self.events[0][2]())

    def test_scope_events_reject_duplicate_and_reordered_actions(self):
        self.event(10, "DELTA:1,0", event=2)
        self.event(11, "DELTA:99,0", event=1)
        self.event(12, "DELTA:99,0", event=2)
        self.assertEqual(1, len(self.events))

    def test_global_release_invalidates_existing_continuation_guard(self):
        self.event(1, "A,WAIT_100,B")
        self.barrier = 2
        self.assertFalse(self.events[0][2]())
        self.assertEqual((True, None), self.state(1, "RT:1"))
        self.assertEqual((True, None), self.end(1))

    def test_stale_replay_cannot_repress_a_newer_release(self):
        self.state(1, "X360A_HOLD")
        self.state(5, "X360A_RELEASE")
        self.event(4, "PHONE_REPLAY:player1|XBOX|A")
        self.assertFalse(self.events)

    def test_force_release_has_output_freshness_but_remains_explicit_event(self):
        self.state(1, "RT:1")
        self.event(3, "PHONE_FORCE:RT:0", event=1)
        self.state(2, "RT:1")
        self.assertEqual(1, len(self.states))
        self.assertEqual("PHONE_FORCE:RT:0", self.events[0][0])

    def test_mouse_is_global_keyboard_aliases_share_keys_and_player2_keyboard_rejected(self):
        self.assertEqual(state_key("MOUSE_LEFT_DOWN", "player1"), state_key("MOUSE_LEFT_UP", "player2"))
        self.assertEqual(state_key("KEY_DOWN:control", "player1"), state_key("KEY_UP:LCTRL", "player1"))
        self.assertIsNone(state_key("KEY_DOWN:W", "player2"))

    def test_new_process_negotiation_retires_previous_player_session(self):
        self.protocol.process("PHONE_CAPS_V1:newprocess:nonce", "player1")
        self.protocol.process("PHONE_ACTIVATE_V1:newprocess:0", "player1")
        self.state(1, "RT:1")
        self.state(2, "RT:0.3", session="newprocess")
        self.assertEqual([("RT:0.3", "player1")], self.states)

    def test_manual_and_macro_channels_preserve_independent_state(self):
        self.state(10, "STICK_MACRO_R:0.8,0.2")
        self.state(1, "STICK_R:0.4,0.1")
        self.assertEqual(2, len(self.states))
        self.assertNotEqual(state_key("STICK_MACRO_R:0,0", "player1"), state_key("STICK_R:0,0", "player1"))

    def test_malformed_negative_and_oversize_packets_have_no_effect(self):
        for data in ("PHONE_END_V1:process:-1:ring:0", "PHONE_EVENT_V1:process:1:ring:-1:0:A",
                     "PHONE_CAPS_V1::nonce", "PHONE_STATE_V1:process:1:" + "x" * 1100):
            self.assertEqual((True, None), self.protocol.process(data, "player1"))
        self.assertFalse(self.states)
        self.assertFalse(self.events)

    def test_activation_is_explicit_and_duplicates_never_repeat_neutralization(self):
        cleared=[]
        protocol=PhoneOutputProtocolState(threading.RLock(),lambda session,seq:True,
            lambda command,player:None,lambda *args:None,activate_phone=cleared.append)
        protocol.process("PHONE_CAPS_V1:new:nonce","player1")
        self.assertEqual((True,None),protocol.process("PHONE_STATE_V1:new:1:player1%7CTRIGGER%7CRT:RT%3A1","player1"))
        self.assertEqual((True,"PHONE_ACK_V1:new:2"),protocol.process("PHONE_ACTIVATE_V1:new:2","player1"))
        protocol.process("PHONE_ACTIVATE_V1:new:2","player1")
        self.assertEqual(["player1"],cleared)
        self.assertFalse(protocol.legacy_packet_is_current("new",1))
        self.assertTrue(protocol.legacy_packet_is_current("new",3))


if __name__ == "__main__":
    unittest.main()
