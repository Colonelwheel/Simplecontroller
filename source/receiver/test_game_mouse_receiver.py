"""Game Mouse packet and complete Win32 ABI checks; all native output is fake."""
import ctypes
import unittest
import uuid
from unittest.mock import Mock, patch

import relative_mouse
from face_input_server import keyboard_name
from test_receiver_release_all import receiver, fake_keyboard, fake_pyautogui


class RelativeMouseTests(unittest.TestCase):
    def test_complete_input_layout_matches_both_windows_architectures(self):
        for pointer_size, expected in ((4, (24, 16, 28, 4, 20)),
                                      (8, (32, 24, 40, 8, 24))):
            with self.subTest(pointer_size=pointer_size):
                mouse, keyboard, hardware, item = relative_mouse.win32_input_types(pointer_size)
                self.assertEqual(expected, (ctypes.sizeof(mouse), ctypes.sizeof(keyboard),
                    ctypes.sizeof(item), item.data.offset, mouse.dwExtraInfo.offset))
                self.assertEqual(8, ctypes.sizeof(hardware))
                self.assertEqual({'mi', 'ki', 'hi'},
                    {name for name, _ in item._fields_[1][1]._fields_})
                self.assertEqual(4, ctypes.sizeof(mouse._fields_[0][1]))

    def test_relative_move_submits_exact_signed_delta_and_move_only_flag(self):
        events = []
        def send(count, pointer, size):
            item = ctypes.cast(pointer, ctypes.POINTER(relative_mouse.Input)).contents
            events.append((count, size, item.type, item.mi.dx, item.mi.dy,
                           item.mi.mouseData, item.mi.dwFlags, item.mi.time, item.mi.dwExtraInfo))
            return 1
        clear_error = Mock()
        with patch.object(relative_mouse, '_send_input_api', (send, lambda: 0, clear_error)):
            self.assertTrue(relative_mouse.send_relative_mouse(40, -20))
        self.assertEqual([(1, ctypes.sizeof(relative_mouse.Input), 0, 40, -20, 0, 1, 0, 0)], events)
        clear_error.assert_called_once_with(0)

    def test_zero_vector_does_not_bind_or_submit_native_input(self):
        with patch.object(relative_mouse, '_native_api', side_effect=AssertionError('native bind')):
            self.assertFalse(relative_mouse.send_relative_mouse(0, 0))

    def test_invalid_and_overflowing_deltas_never_reach_native_api(self):
        for value in (True, 1.0, float('nan'), float('inf'), '1',
                      relative_mouse.LONG_MIN - 1, relative_mouse.LONG_MAX + 1):
            with self.subTest(value=value), patch.object(relative_mouse, '_native_api') as bind:
                with self.assertRaises(ValueError):
                    relative_mouse.send_relative_mouse(value, 0)
                bind.assert_not_called()

    def test_zero_short_or_unexpected_native_result_raises_explicit_error(self):
        for returned in (0, 2):
            with self.subTest(returned=returned), patch.object(relative_mouse, '_send_input_api',
                    (lambda *args: returned, lambda: 5, lambda error: None)):
                with self.assertRaisesRegex(OSError, f'accepted {returned}/1 events') as failure:
                    relative_mouse.send_relative_mouse(1, -1)
                self.assertEqual(5, failure.exception.errno)

    def test_signed_long_boundaries_do_not_wrap(self):
        events = []
        def send(count, pointer, size):
            item = ctypes.cast(pointer, ctypes.POINTER(relative_mouse.Input)).contents
            events.append((item.mi.dx, item.mi.dy))
            return 1
        with patch.object(relative_mouse, '_send_input_api', (send, lambda: 0, lambda error: None)):
            relative_mouse.send_relative_mouse(relative_mouse.LONG_MIN, relative_mouse.LONG_MAX)
        self.assertEqual([(relative_mouse.LONG_MIN, relative_mouse.LONG_MAX)], events)


class GameMouseReceiverTests(unittest.TestCase):
    address = ('127.0.0.1', 31030)

    def setUp(self):
        receiver.face_input_state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), 'game mouse mock isolation')
        receiver.active_connections.clear()
        receiver.release_barriers.clear()
        receiver.latest_stick_packets.clear()
        receiver.smoother.end_touch()
        fake_pyautogui.moves.clear()
        fake_keyboard.down.clear()
        fake_keyboard.released.clear()
        self.native = patch.object(receiver, 'send_relative_mouse')
        self.send_mouse = self.native.start()
        def fake_tagged(target, pressed):
            (fake_keyboard.press if pressed else fake_keyboard.release)(keyboard_name(target))
        self.keyboard = patch.object(receiver, 'send_tagged_key', side_effect=fake_tagged)
        self.keyboard.start()
        self.addCleanup(self.native.stop)
        self.addCleanup(self.keyboard.stop)

    def tearDown(self):
        receiver.face_input_state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), 'game mouse mock cleanup')

    def command(self, command):
        return receiver.process_command(command, self.address)

    def test_game_delta_uses_same_gain_without_legacy_cursor_warp(self):
        self.command('player1:OUTPUT:game-mouse:1:GAME_DELTA:1.25,-0.5125')
        self.send_mouse.assert_called_once_with(50, -20)
        self.assertEqual([], fake_pyautogui.moves)

    def test_legacy_delta_and_absolute_packets_keep_original_backend(self):
        # Legacy's debug arrow is outside cp1252 in redirected Windows logs.
        # Keep the test about movement, independent of the console encoding.
        with patch('builtins.print'):
            self.command('player1:DELTA:1.25,-0.5125')
        self.assertEqual([(50, -20)], fake_pyautogui.moves)
        with patch.object(receiver.smoother, 'process_movement', return_value=(3, -4)) as smooth:
            self.command('player1:TOUCHPAD:0.1,-0.2')
            self.command('player1:POS:0.3,-0.4')
        self.assertEqual([(50, -20), (3, -4), (3, -4)], fake_pyautogui.moves)
        self.assertEqual(2, smooth.call_count)
        self.send_mouse.assert_not_called()

    def test_player2_and_legacy_unwrapped_game_delta_use_same_global_mouse(self):
        self.command('player2:OUTPUT:game-mouse:1:GAME_DELTA:-0.5,0.25')
        self.command('GAME_DELTA:0.25,-0.5')
        self.assertEqual([((-20, 10),), ((10, -20),)],
                         [(call.args,) for call in self.send_mouse.call_args_list])
        self.assertEqual('player2', receiver.active_connections['127.0.0.1:31030']['player_id'])

    def test_pre_release_game_delta_is_blocked_and_later_packet_still_works(self):
        self.command('player1:RELEASE_ALL:game-mouse:10')
        self.command('player1:OUTPUT:game-mouse:9:GAME_DELTA:1,1')
        self.command('player1:OUTPUT:game-mouse:10:GAME_DELTA:1,1')
        self.send_mouse.assert_not_called()
        self.command('player1:OUTPUT:game-mouse:11:GAME_DELTA:1,1')
        self.send_mouse.assert_called_once_with(40, 40)

    def test_release_between_initial_check_and_dispatch_blocks_stale_delta(self):
        original = receiver.output_packet_is_current
        checks = []
        def check(session, sequence):
            result = original(session, sequence)
            checks.append(result)
            if len(checks) == 1:
                receiver.release_all_outputs(session, sequence + 1)
            return result
        with patch.object(receiver, 'output_packet_is_current', side_effect=check):
            self.command('player1:OUTPUT:game-mouse:1:GAME_DELTA:1,1')
        self.assertEqual([True, False], checks)
        self.send_mouse.assert_not_called()

    def test_malformed_nonfinite_and_scaled_overflow_packets_are_logged_without_output(self):
        for coordinates in ('', '1', '1,2,3', 'abc,1', 'nan,1', '1,inf',
                            '-inf,0', '1e309,0', '1e308,1'):
            with self.subTest(coordinates=coordinates), self.assertLogs(receiver.logger, 'WARNING') as logs:
                self.command('GAME_DELTA:' + coordinates)
            self.assertIn('Bad GAME_DELTA packet', logs.output[0])
        self.send_mouse.assert_not_called()
        self.assertEqual([], fake_pyautogui.moves)

    def test_native_range_validation_and_zero_vector_have_no_output(self):
        # Use the real helper with a fake API, so overflow cannot be hidden by
        # mocking the receiver's conversion/output boundary.
        send = Mock(return_value=1)
        with patch.object(receiver, 'send_relative_mouse', relative_mouse.send_relative_mouse), \
                patch.object(relative_mouse, '_send_input_api', (send, lambda: 0, lambda error: None)):
            with self.assertLogs(receiver.logger, 'WARNING'):
                self.command('GAME_DELTA:53687092,0')
            self.command('GAME_DELTA:0.001,-0.001')
        send.assert_not_called()

    def test_failed_native_write_is_reported_without_legacy_fallback(self):
        self.send_mouse.side_effect = OSError(5, 'SendInput accepted 0/1 events')
        with self.assertLogs(receiver.logger, 'ERROR') as logs:
            self.command('GAME_DELTA:1,1')
        self.assertIn('Game mouse movement failed', logs.output[0])
        self.assertIn('0/1 events', logs.output[0])
        self.assertEqual([], fake_pyautogui.moves)
        self.send_mouse.side_effect = None
        self.command('GAME_DELTA:1,1')
        self.assertEqual(2, self.send_mouse.call_count)

    def test_movement_preserves_phone_face_voice_ownership_and_retained_nose(self):
        state = receiver.face_input_state
        old_clock = state.clock
        now = [10.0]
        state.clock = lambda: now[0]
        self.addCleanup(setattr, state, 'clock', old_clock)
        ready = state.handle({'v': 3, 'op': 'hello', 'session': str(uuid.uuid4())})
        self.assertTrue(ready['retained_nose_movement'])
        epoch = ready['epoch']
        state.handle({'v': 3, 'op': 'arm', 'channel': 'movement', 'epoch': epoch, 'seq': 1})
        state.handle({'v': 3, 'op': 'sample', 'channel': 'movement', 'epoch': epoch,
            'seq': 2, 'frame_seq': 1, 'captured_at': now[0], 'vector': [0.0, 0.6],
            'held': ['KEY:W'], 'retain_on_tracking_loss': True})
        self.command('player1:RT:0.4')
        self.command('player1:MOUSE_LEFT_DOWN')
        receiver.gamepads['player1'].voice_set('A', True)
        self.command('player1:GAME_DELTA:1,-1')
        now[0] += 0.3
        state.handle({'v': 3, 'op': 'heartbeat', 'epoch': epoch, 'seq': 3})
        state.tick()
        pad = receiver.gamepads['player1']
        self.assertEqual((0.0, 0.6), pad.left_stick)
        self.assertIn('w', fake_keyboard.down)
        self.assertEqual(0.4, pad.right_trigger)
        self.assertIn('A', pad.buttons)
        self.assertIn('left', fake_pyautogui.down)
        self.send_mouse.assert_called_once_with(40, -40)
        self.command('player1:RELEASE_ALL:game-mouse:10')
        self.assertEqual((0.0, 0.0), pad.left_stick)
        self.assertEqual(0.0, pad.right_trigger)
        self.assertFalse(pad.buttons)
        self.assertFalse(fake_keyboard.down)
        self.assertFalse(fake_pyautogui.down)


if __name__ == '__main__':
    unittest.main()
