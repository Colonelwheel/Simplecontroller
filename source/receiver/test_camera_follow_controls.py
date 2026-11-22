"""Camera Follow settings and source tags, with fake Windows input only."""
import unittest
from unittest.mock import patch

from camera_follow import CameraFollowSettings, CameraFollowState
from test_receiver_release_all import receiver


class CameraFollowStrengthTests(unittest.TestCase):
    def active(self, percent=100, x=1.0, **settings):
        state = CameraFollowState(CameraFollowSettings(**settings), now=0)
        state.set_strength_percent(percent, now=0)
        state.set_enabled(True, now=0)
        state.set_left_stick(x, -0.5, now=0)
        state.step(now=0.2)
        state.step(now=0.5)
        return state

    def test_default_100_preserves_original_response_ceiling_and_ramp(self):
        state = self.active(x=0.4)
        self.assertEqual(100.0, state.strength_percent)
        self.assertAlmostEqual(0.22, state.step(now=0.6)[0])
        self.assertEqual(0.45, self.active().step(now=0.6)[0])
        state = CameraFollowState(now=0)
        state.set_enabled(True, now=0)
        state.set_left_stick(1, 0, now=0)
        self.assertEqual(0, state.step(now=0.199)[0])
        self.assertEqual(0, state.step(now=0.2)[0])
        self.assertAlmostEqual(0.25, state.step(now=0.3)[0])

    def test_125_scales_both_response_and_output_ceiling(self):
        self.assertAlmostEqual(0.275, self.active(125, x=0.4).step(now=0.6)[0])
        self.assertAlmostEqual(0.5625, self.active(125).step(now=0.6)[0])
        self.assertAlmostEqual(-0.5625, self.active(125, x=-1).step(now=0.6)[0])
        state = CameraFollowState(now=0)
        state.set_strength_percent(125, now=0)
        state.set_enabled(True, now=0)
        state.set_left_stick(1, 0, now=0)
        state.step(now=0.2)
        self.assertAlmostEqual(0.3125, state.step(now=0.3)[0])

    def test_live_reduction_and_zero_do_not_leave_old_generated_turn(self):
        state = self.active()
        state.set_strength_percent(50, now=0.5)
        self.assertAlmostEqual(0.225, state.step(now=0.5)[0])
        state.set_strength_percent(0, now=0.6)
        self.assertEqual((0, 0, "neutral"), state.step(now=0.6))
        self.assertTrue(state.enabled)
        self.assertEqual(1.0, state.left_x)
        state.set_strength_percent(125, now=0.7)
        self.assertEqual(0, state.step(now=0.7)[0])
        self.assertGreater(state.step(now=0.8)[0], 0)

    def test_live_increase_preserves_ramp_progress_and_ceiling(self):
        state = self.active()
        self.assertTrue(state.set_strength_percent(125, now=0.5))
        self.assertAlmostEqual(0.5625, state.step(now=0.5)[0])
        self.assertFalse(state.set_strength_percent(125, now=0.6))
        self.assertAlmostEqual(0.5625, state.generated_x)

    def test_zero_strength_keeps_manual_and_macro_priority(self):
        state = self.active()
        state.set_manual_right_stick(0.6, -0.2, now=0.5)
        state.set_strength_percent(0, now=0.5)
        self.assertEqual((0.6, -0.2, "manual"), state.step(now=0.5))
        state.set_macro_right_stick(-0.8, 0.3)
        self.assertEqual((-0.8, 0.3, "macro"), state.step(now=0.5))

    def test_invalid_strength_is_atomic_and_release_keeps_setting(self):
        state = self.active(125)
        for value in (True, -1, 201, float("nan"), float("inf"), "bad"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                state.set_strength_percent(value, now=0.6)
            self.assertEqual(125, state.strength_percent)
            self.assertAlmostEqual(0.5625, state.generated_x)
        state.clear_inputs(now=0.7)
        self.assertEqual(125, state.strength_percent)
        self.assertEqual((0, 0, "neutral"), state.step(now=0.7))

    def test_tiny_finite_strength_does_not_overflow_or_create_turn_from_zero(self):
        state = CameraFollowState(now=0)
        state.set_enabled(True, now=0)
        state.set_left_stick(1, 0, now=0)
        state.set_strength_percent(1e-320, now=0)
        state.set_strength_percent(125, now=0)
        self.assertEqual(0, state.generated_x)
        self.assertEqual(0, state.step(now=0.199)[0])

    def test_maximum_output_never_exceeds_normalized_full_stick(self):
        state = self.active(200, maximum_output=0.8, strength=1)
        self.assertEqual(1.0, state.step(now=1)[0])

    def test_exclusion_clears_follow_immediately_without_losing_manual(self):
        state = self.active()
        state.set_left_stick(1, 0, now=0.5, camera_follow_allowed=False)
        self.assertEqual((0, 0, "neutral"), state.step(now=0.5))
        self.assertTrue(state.enabled)
        self.assertIsNone(state.movement_started_at)
        state.set_manual_right_stick(0.5, 0.3, now=0.5)
        state.set_left_stick(1, 0, now=0.5, camera_follow_allowed=False)
        self.assertEqual((0.5, 0.3, "manual"), state.step(now=0.5))


class CameraFollowReceiverControlsTests(unittest.TestCase):
    address = ("127.0.0.1", 31031)

    def setUp(self):
        receiver.face_input_state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), "follow controls test isolation")
        receiver.release_barriers.clear()
        receiver.latest_stick_packets.clear()
        receiver.latest_camera_follow_strength_packets.clear()
        receiver.active_connections.clear()
        for state in receiver.camera_follow_states.values():
            state.set_enabled(True, now=0)
            state.set_strength_percent(100, now=0)
            state.clear_inputs(now=0)

    def tearDown(self):
        receiver.face_input_state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), "follow controls test cleanup")
        for state in receiver.camera_follow_states.values():
            state.set_strength_percent(100)
            state.set_enabled(False)
        receiver.latest_camera_follow_strength_packets.clear()

    def command(self, text, now=0):
        with patch.object(receiver.time, "monotonic", return_value=now):
            return receiver.process_command(text, self.address)

    def test_excluded_ls_moves_character_and_clears_previous_follow_immediately(self):
        self.command("player1:STICK_L:phone:1:1,-0.5")
        receiver.apply_camera_follow_output("player1", now=0.2)
        receiver.apply_camera_follow_output("player1", now=0.5)
        self.assertEqual((0.45, 0), receiver.gamepads["player1"].right_stick)
        self.command("player1:STICK_L_NO_FOLLOW:phone:2:1,-0.5", now=0.5)
        self.assertEqual((1, 0.5), receiver.gamepads["player1"].left_stick)
        self.assertEqual((0, 0), receiver.gamepads["player1"].right_stick)
        self.assertTrue(receiver.camera_follow_states["player1"].enabled)
        self.assertEqual(0, receiver.camera_follow_states["player1"].left_x)

    def test_identical_coordinates_can_switch_eligibility_and_retained_resends_keep_tag(self):
        self.command("player1:STICK_L_NO_FOLLOW:phone:1:0.7,0")
        self.command("player1:STICK_L_NO_FOLLOW:phone:2:0.7,0", now=0.1)
        self.assertEqual(0, receiver.camera_follow_states["player1"].left_x)
        self.command("player1:STICK_L:phone:3:0.7,0", now=0.2)
        self.assertEqual(0.7, receiver.camera_follow_states["player1"].left_x)
        self.assertFalse(receiver.camera_follow_phone_left_excluded["player1"])
        receiver.apply_camera_follow_output("player1", now=0.4)
        receiver.apply_camera_follow_output("player1", now=0.7)
        self.assertGreater(receiver.gamepads["player1"].right_stick[0], 0)

    def test_manual_macro_and_tagged_packets_share_one_left_freshness_gate(self):
        self.command("player1:STICK_MACRO_L_NO_FOLLOW:phone:10:0.7,-0.2")
        for text in ("STICK_L:phone:9:-1,0", "STICK_MACRO_L:phone:10:-1,0"):
            self.command("player1:" + text)
        self.assertTrue(receiver.camera_follow_phone_left_excluded["player1"])
        self.assertEqual((0.7, 0.2), receiver.gamepads["player1"].left_stick)
        self.command("player1:STICK_MACRO_L:phone:11:0.7,-0.2")
        self.command("player1:STICK_L_NO_FOLLOW:phone:10:0.5,0")
        self.assertFalse(receiver.camera_follow_phone_left_excluded["player1"])
        self.assertEqual(0.7, receiver.camera_follow_states["player1"].left_x)

    def test_face_winner_follows_and_excluded_phone_winner_does_not(self):
        pad = receiver.gamepads["player1"]
        self.command("player1:STICK_L_NO_FOLLOW:phone:1:0.7,0")
        with patch.object(receiver.time, "monotonic", return_value=0):
            pad.face_vector((0.4, 0.3))
        self.assertEqual((0.7, 0), pad.left_stick)
        self.assertEqual(0, receiver.camera_follow_states["player1"].left_x)
        self.command("player1:STICK_L_NO_FOLLOW:phone:2:0,0", now=0.1)
        self.assertEqual((0.4, 0.3), pad.left_stick)
        self.assertEqual((0.4, -0.3), (receiver.camera_follow_states["player1"].left_x,
                                     receiver.camera_follow_states["player1"].left_y))

    def test_exclusion_never_suppresses_manual_or_macro_rs_and_player2_is_independent(self):
        self.command("player1:STICK_R:phone:1:0.6,-0.3")
        self.command("player1:STICK_L_NO_FOLLOW:phone:2:1,0")
        self.assertEqual((0.6, 0.3), receiver.gamepads["player1"].right_stick)
        self.command("player1:STICK_MACRO_R:phone:3:-1,0.5")
        self.command("player1:STICK_MACRO_L_NO_FOLLOW:phone:4:1,0")
        self.assertEqual((-1, -0.5), receiver.gamepads["player1"].right_stick)
        self.command("player2:STICK_L:other:1:0.6,0")
        self.assertFalse(receiver.camera_follow_phone_left_excluded["player2"])
        self.assertEqual(0.6, receiver.camera_follow_states["player2"].left_x)

    def test_release_barrier_and_release_race_reject_stale_tagged_packet(self):
        self.command("player1:RELEASE_ALL:phone:10")
        self.command("player1:STICK_L_NO_FOLLOW:phone:10:1,0")
        self.assertFalse(receiver.camera_follow_phone_left_excluded["player1"])
        self.assertEqual((0, 0), receiver.gamepads["player1"].left_stick)
        original = receiver.output_packet_is_current
        calls = []
        def check(session, sequence):
            accepted = original(session, sequence)
            calls.append(accepted)
            if len(calls) == 1:
                receiver.release_all_outputs(session, sequence + 1)
            return accepted
        with patch.object(receiver, "output_packet_is_current", side_effect=check):
            self.command("player1:OUTPUT:phone:11:STICK_L_NO_FOLLOW:phone:11:1,0")
        self.assertEqual((0, 0), receiver.gamepads["player1"].left_stick)
        self.assertFalse(receiver.camera_follow_phone_left_excluded["player1"])

    def test_invalid_tagged_packets_do_not_commit_coordinates_or_freshness(self):
        for tail in ("phone:1:nan,0", "phone:1:1,inf", "phone:-1:1,0", ":1:1,0"):
            with self.assertLogs(receiver.logger, "WARNING"):
                self.command("player1:STICK_L_NO_FOLLOW:" + tail)
        self.assertEqual({}, receiver.latest_stick_packets)
        self.assertEqual((0, 0), receiver.gamepads["player1"].left_stick)

    def test_live_strength_updates_output_and_isolation_in_both_players(self):
        for player in ("player1", "player2"):
            self.command(player + ":STICK_L:phone:1:1,0")
            receiver.apply_camera_follow_output(player, now=0.2)
            receiver.apply_camera_follow_output(player, now=0.5)
        self.command("player1:CAMERA_FOLLOW_STRENGTH:phone:2:125", now=0.5)
        self.assertAlmostEqual(0.5625, receiver.gamepads["player1"].right_stick[0])
        self.assertEqual(0.45, receiver.gamepads["player2"].right_stick[0])
        self.command("player2:CAMERA_FOLLOW_STRENGTH:phone:3:50", now=0.5)
        self.assertAlmostEqual(0.225, receiver.gamepads["player2"].right_stick[0])
        self.command("player1:CAMERA_FOLLOW_STRENGTH:phone:4:0", now=0.6)
        self.assertEqual((0, 0), receiver.gamepads["player1"].right_stick)
        self.assertTrue(receiver.camera_follow_states["player1"].enabled)

    def test_strength_gate_is_independent_of_stick_gate_but_rejects_own_stale_values(self):
        self.command("player1:STICK_L:phone:20:1,0")
        self.command("player1:CAMERA_FOLLOW_STRENGTH:phone:10:125")
        self.command("player1:CAMERA_FOLLOW_STRENGTH:phone:9:50")
        self.command("player1:CAMERA_FOLLOW_STRENGTH:phone:10:50")
        self.assertEqual(125, receiver.camera_follow_states["player1"].strength_percent)
        self.command("player1:STICK_L_NO_FOLLOW:phone:19:1,0")
        self.assertFalse(receiver.camera_follow_phone_left_excluded["player1"])

    def test_invalid_strength_does_not_poison_valid_sequence_or_mutate_runtime(self):
        for tail in ("phone:1:nan", "phone:1:inf", "phone:1:-1", "phone:1:201", "phone:1:bad",
                     "phone:-1:125", ":1:125", "phone:1", "phone:1:125:bad"):
            with self.assertLogs(receiver.logger, "WARNING"):
                self.command("player1:CAMERA_FOLLOW_STRENGTH:" + tail)
        self.assertEqual({}, receiver.latest_camera_follow_strength_packets)
        self.assertEqual(100, receiver.camera_follow_states["player1"].strength_percent)
        self.command("player1:CAMERA_FOLLOW_STRENGTH:phone:1:125")
        self.assertEqual(125, receiver.camera_follow_states["player1"].strength_percent)

    def test_strength_release_barrier_and_race_reject_pre_release_setting(self):
        self.command("player1:RELEASE_ALL:phone:10")
        self.command("player1:CAMERA_FOLLOW_STRENGTH:phone:9:125")
        self.assertEqual(100, receiver.camera_follow_states["player1"].strength_percent)
        original = receiver.output_packet_is_current
        calls = []
        def check(session, sequence):
            accepted = original(session, sequence)
            calls.append(accepted)
            if len(calls) == 1:
                receiver.release_all_outputs(session, sequence + 1)
            return accepted
        with patch.object(receiver, "output_packet_is_current", side_effect=check):
            self.command("player1:OUTPUT:phone:11:CAMERA_FOLLOW_STRENGTH:phone:11:125")
        self.assertEqual(100, receiver.camera_follow_states["player1"].strength_percent)

    def test_release_and_input_loss_preserve_strength_and_do_not_resurrect_excluded_assistance(self):
        self.command("player1:CAMERA_FOLLOW_STRENGTH:phone:1:125")
        self.command("player1:STICK_L_NO_FOLLOW:phone:2:1,0")
        receiver.clear_camera_follow_player("player1")
        receiver.gamepads["player1"].face_vector((0.7, 0))
        self.assertEqual(0, receiver.camera_follow_states["player1"].left_x)
        self.command("player1:RELEASE_ALL:phone:10")
        self.assertFalse(receiver.camera_follow_phone_left_excluded["player1"])
        self.assertEqual(125, receiver.camera_follow_states["player1"].strength_percent)
        self.command("player1:STICK_L:phone:11:0.5,0")
        self.assertEqual(0.5, receiver.camera_follow_states["player1"].left_x)


if __name__ == "__main__":
    unittest.main()
