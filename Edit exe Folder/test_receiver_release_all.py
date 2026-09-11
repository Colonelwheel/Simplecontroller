import os
import sys
import tempfile
import threading
import time
import types
import unittest


class FakeKeyboard(types.ModuleType):
    def __init__(self):
        super().__init__("keyboard")
        self.down = set()
        self.released = []

    def press(self, key):
        self.down.add(key)

    def release(self, key):
        self.down.discard(key)
        self.released.append(key)

    def press_and_release(self, key):
        self.released.append(key)

    def is_pressed(self, key):
        return key in self.down


class FakePyAutoGui(types.ModuleType):
    def __init__(self):
        super().__init__("pyautogui")
        self.FAILSAFE = False
        self.PAUSE = 0
        self.down = set()
        self.moves = []
        self.scrolls = []
        self.ups = []

    def mouseDown(self, button):
        self.down.add(button)

    def mouseUp(self, button):
        self.down.discard(button)
        self.ups.append(button)

    def moveRel(self, x, y):
        self.moves.append((x, y))

    def scroll(self, amount):
        self.scrolls.append(amount)


class FakeGamepad:
    def __init__(self):
        self.reset_count = 0
        self.reset()
        self.reset_count = 0

    def reset(self):
        self.reset_count += 1
        self.buttons = set()
        self.left_trigger = 0.0
        self.right_trigger = 0.0
        self.left_stick = (0.0, 0.0)
        self.right_stick = (0.0, 0.0)

    def update(self):
        pass

    def press_button(self, button):
        self.buttons.add(button)

    def release_button(self, button):
        self.buttons.discard(button)

    def left_trigger_float(self, value_float):
        self.left_trigger = value_float

    def right_trigger_float(self, value_float):
        self.right_trigger = value_float

    def left_joystick_float(self, x_value_float, y_value_float):
        self.left_stick = (x_value_float, y_value_float)

    def right_joystick_float(self, x_value_float, y_value_float):
        self.right_stick = (x_value_float, y_value_float)


class FakeXboxButtons:
    XUSB_GAMEPAD_A = "A"
    XUSB_GAMEPAD_B = "B"
    XUSB_GAMEPAD_X = "X"
    XUSB_GAMEPAD_Y = "Y"
    XUSB_GAMEPAD_LEFT_SHOULDER = "LB"
    XUSB_GAMEPAD_RIGHT_SHOULDER = "RB"
    XUSB_GAMEPAD_START = "START"
    XUSB_GAMEPAD_BACK = "BACK"
    XUSB_GAMEPAD_DPAD_UP = "UP"
    XUSB_GAMEPAD_DPAD_DOWN = "DOWN"
    XUSB_GAMEPAD_DPAD_LEFT = "LEFT"
    XUSB_GAMEPAD_DPAD_RIGHT = "RIGHT"
    XUSB_GAMEPAD_LEFT_THUMB = "LS"
    XUSB_GAMEPAD_RIGHT_THUMB = "RS"


fake_keyboard = FakeKeyboard()
fake_pyautogui = FakePyAutoGui()
fake_vgamepad = types.ModuleType("vgamepad")
fake_vgamepad.VX360Gamepad = FakeGamepad
fake_vgamepad.XUSB_BUTTON = FakeXboxButtons
sys.modules["keyboard"] = fake_keyboard
sys.modules["mouse"] = types.ModuleType("mouse")
sys.modules["pyautogui"] = fake_pyautogui
sys.modules["vgamepad"] = fake_vgamepad
os.environ["LOCALAPPDATA"] = tempfile.gettempdir()

import simple_controller_receiver as receiver


class ReleaseAllReceiverTest(unittest.TestCase):
    address = ("127.0.0.1", 31000)

    def setUp(self):
        for player_id in receiver.gamepads:
            receiver.gamepads[player_id].reset()
            receiver.gamepads[player_id].reset_count = 0
            receiver.button_states[player_id].clear()
            receiver.key_states[player_id].clear()
            receiver.mouse_states[player_id].update(
                left_down=False,
                is_touchpad_active=False,
            )
            receiver.pending_button_release_timers[player_id].clear()
            receiver.release_generations[player_id] = 0
            receiver.camera_follow_states[player_id].clear_inputs()
            receiver.camera_follow_last_right_output[player_id] = (0.0, 0.0, "neutral")
        receiver.release_barriers.clear()
        receiver.latest_stick_packets.clear()
        receiver.active_connections.clear()
        receiver.smoother.end_touch()
        fake_keyboard.down.clear()
        fake_keyboard.released.clear()
        fake_pyautogui.down.clear()
        fake_pyautogui.moves.clear()
        fake_pyautogui.scrolls.clear()
        fake_pyautogui.ups.clear()

    def command(self, payload):
        return receiver.process_command(payload, self.address)

    def test_release_all_neutralizes_every_receiver_output_for_both_players(self):
        self.command("player1:OUTPUT:phone:1:X360A_HOLD")
        self.command("player1:OUTPUT:phone:2:LT:1.0")
        self.command("player1:OUTPUT:phone:3:KEY_DOWN:W")
        self.command("player1:OUTPUT:phone:4:MOUSE_LEFT_DOWN")
        self.command("player1:OUTPUT:phone:5:MOUSE_RIGHT_DOWN")
        self.command("player1:OUTPUT:phone:6:MOUSE_MIDDLE_DOWN")
        self.command("player1:STICK_L:phone:7:0.75,-0.25")
        self.command("player2:OUTPUT:phone:8:X360B_HOLD")
        self.command("player2:OUTPUT:phone:9:RT:0.8")
        self.command("player2:STICK_R:phone:10:-0.65,0.35")
        receiver.smoother.reset()
        receiver.camera_follow_states["player1"].set_enabled(True)
        receiver.camera_follow_states["player1"].set_left_stick(1.0, 0.0, now=0.0)
        receiver.camera_follow_states["player1"].step(now=1.0)
        receiver.camera_follow_last_right_output["player1"] = (0.4, 0.0, "camera_follow")

        acknowledgement = self.command("player1:RELEASE_ALL:phone:20")

        self.assertEqual("RELEASE_ALL_ACK:phone:20", acknowledgement)
        self.assertEqual(20, receiver.release_barriers["phone"])
        self.assertFalse(receiver.smoother.is_active())
        self.assertEqual(set(), fake_keyboard.down)
        self.assertEqual({"left", "right", "middle"}, set(fake_pyautogui.ups))
        for player_id, gamepad in receiver.gamepads.items():
            self.assertEqual(set(), gamepad.buttons, player_id)
            self.assertEqual(0.0, gamepad.left_trigger, player_id)
            self.assertEqual(0.0, gamepad.right_trigger, player_id)
            self.assertEqual((0.0, 0.0), gamepad.left_stick, player_id)
            self.assertEqual((0.0, 0.0), gamepad.right_stick, player_id)
            self.assertEqual({}, receiver.button_states[player_id], player_id)
            self.assertEqual({}, receiver.key_states[player_id], player_id)
            self.assertFalse(receiver.mouse_states[player_id]["left_down"], player_id)
            self.assertEqual(
                (0.0, 0.0, "neutral"),
                receiver.camera_follow_last_right_output[player_id],
                player_id,
            )
            camera_x, camera_y, camera_source = receiver.camera_follow_states[player_id].step()
            self.assertEqual((0.0, 0.0), (camera_x, camera_y), player_id)
            self.assertIn(camera_source, ("neutral", "manual"), player_id)

    def test_duplicate_release_all_is_idempotent(self):
        first = self.command("player1:RELEASE_ALL:retry-session:7")
        self.command("player1:OUTPUT:retry-session:8:X360A_HOLD")
        second = self.command("player1:RELEASE_ALL:retry-session:7")

        self.assertEqual(first, second)
        self.assertEqual("RELEASE_ALL_ACK:retry-session:7", second)
        self.assertEqual(7, receiver.release_barriers["retry-session"])
        self.assertEqual(1, receiver.gamepads["player1"].reset_count)
        self.assertIn("A", receiver.gamepads["player1"].buttons)

        third = self.command("player1:RELEASE_ALL:retry-session:9")
        self.assertEqual("RELEASE_ALL_ACK:retry-session:9", third)
        self.assertEqual(set(), receiver.gamepads["player1"].buttons)
        self.assertEqual(2, receiver.gamepads["player1"].reset_count)
        for gamepad in receiver.gamepads.values():
            self.assertEqual((0.0, 0.0), gamepad.left_stick)
            self.assertEqual((0.0, 0.0), gamepad.right_stick)

    def test_packets_older_than_release_barrier_cannot_reactivate_outputs(self):
        self.command("player1:RELEASE_ALL:ordered-session:50")

        self.command("player1:OUTPUT:ordered-session:49:X360A_HOLD")
        self.command("player1:OUTPUT:ordered-session:48:KEY_DOWN:W")
        self.command("player2:OUTPUT:ordered-session:47:MOUSE_LEFT_DOWN")
        self.command("player2:STICK_L:ordered-session:46:1.0,1.0")

        self.assertEqual(set(), receiver.gamepads["player1"].buttons)
        self.assertEqual({}, receiver.key_states["player1"])
        self.assertEqual(set(), fake_pyautogui.down)
        self.assertEqual((0.0, 0.0), receiver.gamepads["player2"].left_stick)

        self.command("player2:OUTPUT:ordered-session:51:RT:0.5")
        self.assertEqual(0.5, receiver.gamepads["player2"].right_trigger)

    def test_release_all_cancels_waiting_macro_sequence(self):
        generation = receiver.current_release_generation("player1")
        worker = threading.Thread(
            target=receiver.process_timed_sequence,
            args=(["WAIT_150", "X360Y_HOLD"], "player1", generation),
        )
        worker.start()
        time.sleep(0.02)

        self.command("player1:RELEASE_ALL:macro-session:4")
        worker.join(timeout=0.5)

        self.assertFalse(worker.is_alive())
        self.assertEqual(set(), receiver.gamepads["player1"].buttons)

    def test_release_all_cancels_pending_button_auto_release_timer(self):
        self.command("player1:OUTPUT:timer-session:1:X360A")
        self.assertTrue(receiver.pending_button_release_timers["player1"])

        self.command("player1:RELEASE_ALL:timer-session:2")
        time.sleep(0.15)

        self.assertEqual(set(), receiver.pending_button_release_timers["player1"])
        self.assertEqual(set(), receiver.gamepads["player1"].buttons)
        self.assertEqual({}, receiver.button_states["player1"])

    def test_camera_follow_cannot_write_a_value_after_release_all(self):
        state = receiver.camera_follow_states["player1"]
        original_step = state.step
        step_started = threading.Event()
        allow_step_to_finish = threading.Event()

        def blocked_step(now=None):
            step_started.set()
            allow_step_to_finish.wait(timeout=0.5)
            return 0.45, 0.0, "camera_follow"

        state.step = blocked_step
        camera_worker = threading.Thread(
            target=receiver.apply_camera_follow_output,
            args=("player1",),
        )
        camera_worker.start()
        self.assertTrue(step_started.wait(timeout=0.5))

        release_worker = threading.Thread(
            target=receiver.release_all_outputs,
            args=("camera-race", 9),
        )
        release_worker.start()
        allow_step_to_finish.set()
        camera_worker.join(timeout=0.5)
        release_worker.join(timeout=0.5)
        state.step = original_step

        self.assertFalse(camera_worker.is_alive())
        self.assertFalse(release_worker.is_alive())
        self.assertEqual((0.0, 0.0), receiver.gamepads["player1"].right_stick)
        self.assertEqual(
            (0.0, 0.0, "neutral"),
            receiver.camera_follow_last_right_output["player1"],
        )

    def test_ordinary_commands_are_unchanged_without_release_all(self):
        self.command("player1:OUTPUT:normal-session:1:X360RB_HOLD")
        self.command("player1:OUTPUT:normal-session:2:LT:0.6")
        self.command("player1:OUTPUT:normal-session:3:KEY_DOWN:A")
        self.command("player1:STICK_L:normal-session:4:0.4,-0.2")
        self.command("player2:OUTPUT:normal-session:5:RT:0.7")

        self.assertIn("RB", receiver.gamepads["player1"].buttons)
        self.assertEqual(0.6, receiver.gamepads["player1"].left_trigger)
        self.assertIn("A", receiver.key_states["player1"])
        self.assertEqual((0.4, 0.2), receiver.gamepads["player1"].left_stick)
        self.assertEqual(0.7, receiver.gamepads["player2"].right_trigger)


if __name__ == "__main__":
    unittest.main()
