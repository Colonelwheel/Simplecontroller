import unittest

from camera_follow import CameraFollowSettings, CameraFollowState


class CameraFollowStateTest(unittest.TestCase):
    def new_state(self, **overrides):
        settings = CameraFollowSettings(**overrides)
        return CameraFollowState(settings, now=0.0)

    def activate(self, state, x=0.8, y=-0.5):
        state.set_enabled(True, now=0.0)
        state.set_left_stick(x, y, now=0.0)
        state.step(now=0.2)
        return state.step(now=0.3)

    def test_disabled_preserves_manual_right_stick(self):
        state = self.new_state()
        state.set_left_stick(1.0, -1.0, now=0.0)
        state.set_manual_right_stick(0.7, -0.4, now=0.0)
        self.assertEqual((0.7, -0.4, "manual"), state.step(now=1.0))
        self.assertEqual(0.0, state.generated_x)

    def test_centered_left_stick_generates_nothing(self):
        state = self.new_state()
        state.set_enabled(True, now=0.0)
        self.assertEqual((0.0, 0.0, "neutral"), state.step(now=1.0))

    def test_brief_movement_does_not_activate(self):
        state = self.new_state()
        state.set_enabled(True, now=0.0)
        state.set_left_stick(0.8, 0.0, now=0.0)
        self.assertEqual((0.0, 0.0, "neutral"), state.step(now=0.199))
        state.set_left_stick(0.0, 0.0, now=0.199)
        self.assertEqual((0.0, 0.0, "neutral"), state.step(now=0.4))

    def test_sustained_steering_is_proportional_and_directional(self):
        right = self.new_state()
        right_x, right_y, source = self.activate(right, x=0.4)
        left = self.new_state()
        left_x, left_y, _ = self.activate(left, x=-0.8)
        self.assertEqual("camera_follow", source)
        self.assertGreater(right_x, 0.0)
        self.assertLess(left_x, 0.0)
        self.assertGreater(abs(left_x), abs(right_x))
        self.assertEqual(0.0, right_y)
        self.assertEqual(0.0, left_y)

    def test_output_is_clamped(self):
        state = self.new_state(strength=2.0, maximum_output=0.45)
        self.activate(state, x=1.0)
        x, _, _ = state.step(now=1.0)
        self.assertEqual(0.45, x)

    def test_centering_left_stick_ramps_out(self):
        state = self.new_state()
        active_x, _, _ = self.activate(state)
        state.set_left_stick(0.0, 0.0, now=0.3)
        ramp_x, _, source = state.step(now=0.31)
        self.assertGreater(ramp_x, 0.0)
        self.assertLess(ramp_x, active_x)
        self.assertEqual("camera_follow", source)
        self.assertEqual((0.0, 0.0, "neutral"), state.step(now=0.5))

    def test_manual_right_stick_yields_immediately(self):
        state = self.new_state()
        self.activate(state)
        state.set_manual_right_stick(-0.7, 0.3, now=0.31)
        self.assertEqual((-0.7, 0.3, "manual"), state.step(now=0.31))

    def test_manual_release_waits_for_override_timeout(self):
        state = self.new_state()
        self.activate(state)
        state.set_manual_right_stick(0.7, 0.0, now=0.31)
        state.step(now=0.31)
        state.set_manual_right_stick(0.0, 0.0, now=0.4)
        self.assertEqual((0.0, 0.0, "manual_override"), state.step(now=1.149))
        self.assertEqual((0.0, 0.0, "neutral"), state.step(now=1.15))
        x, _, source = state.step(now=1.25)
        self.assertGreater(x, 0.0)
        self.assertEqual("camera_follow", source)

    def test_explicit_rs_macro_has_highest_priority(self):
        state = self.new_state()
        self.activate(state)
        state.set_manual_right_stick(0.6, 0.2, now=0.31)
        state.set_macro_right_stick(-1.0, 0.5)
        self.assertEqual((-1.0, 0.5, "macro"), state.step(now=0.31))

    def test_disabling_while_active_clears_generated_output(self):
        state = self.new_state()
        self.activate(state)
        state.set_enabled(False, now=0.31)
        self.assertEqual((0.0, 0.0, "manual"), state.step(now=0.31))
        self.assertEqual(0.0, state.generated_x)

    def test_input_loss_clear_removes_generated_and_source_state(self):
        state = self.new_state()
        self.activate(state)
        state.set_macro_right_stick(1.0, 0.0)
        state.clear_inputs(now=0.4)
        self.assertEqual((0.0, 0.0, "neutral"), state.step(now=0.4))

    def test_duplicate_explicit_state_is_idempotent(self):
        state = self.new_state()
        self.assertTrue(state.set_enabled(True, now=0.0))
        state.set_left_stick(0.8, 0.0, now=0.0)
        state.step(now=0.2)
        state.step(now=0.3)
        generated_before_duplicate = state.generated_x
        self.assertFalse(state.set_enabled(True, now=0.1))
        self.assertTrue(state.enabled)
        self.assertEqual(generated_before_duplicate, state.generated_x)


if __name__ == "__main__":
    unittest.main()
