"""Actual receiver routing with the existing fake-native test harness only."""
import threading
import time
import unittest
from unittest.mock import patch
from urllib.parse import quote_plus

from test_receiver_release_all import receiver
from phone_output_protocol import PhoneOutputProtocolState, state_key


class PhoneOutputReceiverTests(unittest.TestCase):
    address = ("127.0.0.1", 31141)

    def setUp(self):
        receiver.release_outputs(tuple(receiver.gamepads), "scoped protocol test")
        receiver.release_barriers.clear()
        receiver.phone_output_protocol = PhoneOutputProtocolState(receiver.release_state_lock,
            receiver.output_packet_is_current, receiver.apply_phone_aggregate,
            receiver.dispatch_phone_event, receiver.phone_output_is_masked,
            receiver.neutralize_legacy_phone_outputs)
        self.send("PHONE_CAPS_V1:phone:nonce")
        self.send("PHONE_ACTIVATE_V1:phone:0")

    def tearDown(self):
        receiver.release_outputs(tuple(receiver.gamepads), "scoped protocol cleanup")

    def send(self, command, player="player1"):
        return receiver.process_command(f"{player}:{command}", self.address)

    def state(self, sequence, command, player="player1", session="phone"):
        key = state_key(command, player)
        return self.send(f"PHONE_STATE_V1:{session}:{sequence}:{quote_plus(key)}:{quote_plus(command)}",player)

    def event(self, sequence, command, event=0, scope="ring"):
        return self.send(f"PHONE_EVENT_V1:phone:{sequence}:{quote_plus(scope)}:0:{event}:{quote_plus(command)}")

    def test_scoped_phone_trigger_release_preserves_face_and_voice(self):
        pad=receiver.gamepads["player1"]
        pad.face_set("RT",True); pad.voice_set("RT",0.8)
        self.state(1,"RT:0.4"); self.state(2,"RT:0")
        self.assertEqual(1,pad.raw.right_trigger)
        pad.face_set("RT",False)
        self.assertEqual(0.8,pad.raw.right_trigger)
        pad.voice_set("RT",0)
        self.assertEqual(0,pad.raw.right_trigger)

    def test_ack_reports_real_foreign_trigger_and_mouse_ownership_then_clears(self):
        pad=receiver.gamepads["player1"]
        pad.voice_set("RT",0.4)
        self.assertIn(":MASKED:player1%7CTRIGGER%7CRT",self.state(1,"RT:1"))
        self.assertEqual(0.4,pad.raw.right_trigger if self.state(2,"RT:0") else -1)
        pad.voice_set("RT",0)
        self.assertIn(":CLEAR:player1%7CTRIGGER%7CRT",self.state(3,"RT:0"))
        pad.face_set("MOUSE_RIGHT",True)
        self.assertIn(":MASKED:GLOBAL%7CMOUSE%7CRIGHT",self.state(4,"MOUSE_RIGHT_DOWN"))
        pad.face_set("MOUSE_RIGHT",False)
        self.assertIn(":CLEAR:GLOBAL%7CMOUSE%7CRIGHT",self.state(5,"MOUSE_RIGHT_UP"))

    def test_state_reconciliation_does_not_create_legacy_xbox_repress(self):
        pad=receiver.gamepads["player1"]
        with patch.object(pad.raw,"release_button",wraps=pad.raw.release_button) as release:
            self.state(1,"X360A_HOLD"); self.state(2,"X360A_HOLD")
            self.state(2,"X360A_HOLD")
            self.assertEqual(0,release.call_count)
            self.state(3,"X360A_RELEASE")
            self.assertEqual(1,release.call_count)

    def test_phone_state_release_preserves_face_and_voice_digital_owners(self):
        pad=receiver.gamepads["player1"]
        pad.face_set("A",True); pad.voice_set("A",True)
        self.state(1,"X360A_HOLD"); self.state(2,"X360A_RELEASE")
        self.assertIn("A",pad.raw.buttons)
        pad.face_set("A",False)
        self.assertIn("A",pad.raw.buttons)
        pad.voice_set("A",False)
        self.assertNotIn("A",pad.raw.buttons)

    def test_old_rt_hold_and_mouse_down_are_dropped_after_new_release(self):
        pad=receiver.gamepads["player1"]
        self.state(3,"RT:0"); self.state(2,"RT:1")
        self.assertEqual(0,pad.raw.right_trigger)
        self.state(6,"MOUSE_RIGHT_UP"); self.state(5,"MOUSE_RIGHT_DOWN")
        self.assertNotIn("MOUSE_RIGHT",receiver.desktop_input.phone)

    def test_scoped_raw_wait_continuation_is_canceled_after_end(self):
        self.event(1,"X360A,WAIT_100,X360B")
        deadline=time.monotonic()+0.25
        while "A" not in receiver.gamepads["player1"].raw.buttons and time.monotonic()<deadline:
            time.sleep(0.001)
        self.send("PHONE_END_V1:phone:2:ring:0")
        time.sleep(0.12)
        self.assertNotIn("B",receiver.gamepads["player1"].raw.buttons)
        self.assertNotIn("A",receiver.gamepads["player1"].raw.buttons)

    def test_end_of_raw_tap_preserves_an_aggregate_phone_hold(self):
        self.state(1,"X360A_HOLD")
        self.event(2,"X360A")
        self.send("PHONE_END_V1:phone:3:ring:0")
        self.assertIn("A",receiver.gamepads["player1"].raw.buttons)
        self.state(4,"X360A_RELEASE")
        self.assertNotIn("A",receiver.gamepads["player1"].raw.buttons)

    def test_global_cleanup_clears_raw_and_aggregate_snapshots_before_later_end(self):
        self.state(1,"X360A_HOLD")
        self.event(2,"X360B_HOLD")
        receiver.release_outputs(tuple(receiver.gamepads),"voice-like global cleanup")
        self.send("PHONE_END_V1:phone:3:ring:0")
        self.assertFalse(receiver.gamepads["player1"].raw.buttons)

    def test_manual_macro_rs_and_camera_preference_remain_distinct(self):
        state=receiver.camera_follow_states["player1"]
        self.state(1,"CAMERA_FOLLOW:1")
        self.state(2,"STICK_R:0.3,0.2")
        self.state(3,"STICK_MACRO_R:0.8,0.1")
        self.assertEqual((0.8,0.1,"macro"),state.step())
        self.state(4,"STICK_MACRO_R:0.0,0.0")
        self.assertEqual((0.3,0.2,"manual"),state.step())
        self.state(5,"CAMERA_FOLLOW:0")
        self.state(0,"CAMERA_FOLLOW:1")
        self.assertFalse(state.enabled)

    def test_activation_rejects_old_legacy_hold_and_delta_and_preserves_other_sources(self):
        pad=receiver.gamepads["player1"]; other=receiver.gamepads["player2"]
        pad.face_set("A",True); pad.voice_set("RT",0.7)
        pad.face_vector((0.4,0.0)); other.press_button(button="B")
        self.send("OUTPUT:phone:1:X360A_HOLD")
        self.send("OUTPUT:phone:2:RT:1")
        self.send("STICK_L:phone:3:0.9,0")
        self.send("PHONE_ACTIVATE_V1:phone:10")
        self.assertFalse(pad.phone_buttons)
        self.assertIn("A",pad.raw.buttons)
        self.assertEqual(0.7,pad.raw.right_trigger)
        self.assertEqual((0.4,0.0),pad.raw.left_stick)
        self.assertIn("B",other.raw.buttons)
        self.send("OUTPUT:phone:2:RT:1")
        self.assertEqual(0.7,pad.raw.right_trigger)
        with patch.object(receiver,"handle_touchpad") as move:
            self.send("OUTPUT:phone:4:DELTA:99,0")
            move.assert_not_called()
        self.state(11,"RT:0.8")
        self.assertEqual(0.8,pad.raw.right_trigger)

    def test_new_scoped_stick_updates_also_block_older_legacy_ordered_stick(self):
        self.state(10,"STICK_L:0.8,0.1")
        self.send("STICK_L:phone:9:0.1,0.1")
        self.assertEqual((0.8,-0.1),receiver.gamepads["player1"].raw.left_stick)

    def test_scoped_ls_macro_has_priority_and_ending_it_restores_latest_manual_owner(self):
        pad=receiver.gamepads["player1"]
        self.state(1,"STICK_L:0.6,0.2")
        self.state(2,"STICK_MACRO_L:-0.8,0.0")
        self.state(3,"STICK_L:0.4,-0.3")
        self.assertEqual((-0.8,-0.0),pad.raw.left_stick)
        self.state(4,"STICK_MACRO_L:0.0,0.0")
        self.assertEqual((0.4,0.3),pad.raw.left_stick)

    def test_scoped_ls_manual_finish_preserves_macro_and_raw_macro_end_restores_manual(self):
        pad=receiver.gamepads["player1"]
        self.state(1,"STICK_L:0.6,0.0")
        self.state(2,"STICK_MACRO_L:0.9,0.0")
        self.state(3,"STICK_L:0.0,0.0")
        self.assertEqual((0.9,-0.0),pad.raw.left_stick)
        self.state(4,"STICK_MACRO_L:0.0,0.0")
        self.state(5,"STICK_L:0.3,0.0")
        self.event(6,"STICK_MACRO_L:-0.7,0.0",scope="raw")
        self.assertEqual((-0.7,-0.0),pad.raw.left_stick)
        self.send("PHONE_END_V1:phone:7:raw:0")
        self.assertEqual((0.3,-0.0),pad.raw.left_stick)

    def test_scoped_ls_manual_macro_player_channels_and_exclusion_follow_the_winner(self):
        self.state(1,"STICK_L:0.4,0.0")
        self.state(2,"STICK_MACRO_L_NO_FOLLOW:0.8,0.0")
        self.assertTrue(receiver.camera_follow_phone_left_excluded["player1"])
        self.send("PHONE_CAPS_V1:phone2:nonce","player2")
        self.send("PHONE_ACTIVATE_V1:phone2:0","player2")
        self.state(1,"STICK_L:0.2,0.0","player2",session="phone2")
        self.state(2,"STICK_MACRO_L:-0.9,0.0","player2",session="phone2")
        self.state(3,"STICK_MACRO_L:0.0,0.0","player2",session="phone2")
        self.assertEqual((0.2,-0.0),receiver.gamepads["player2"].raw.left_stick)
        self.assertEqual((0.8,-0.0),receiver.gamepads["player1"].raw.left_stick)
        self.state(3,"STICK_MACRO_L_NO_FOLLOW:0.0,0.0")
        self.assertEqual((0.4,-0.0),receiver.gamepads["player1"].raw.left_stick)
        self.assertFalse(receiver.camera_follow_phone_left_excluded["player1"])


if __name__ == "__main__":
    unittest.main()
