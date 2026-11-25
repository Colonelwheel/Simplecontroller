"""Composition/protocol checks with an explicitly fake, bounded desktop bridge."""
import subprocess
import sys
import threading
import time
from pathlib import Path
from urllib.parse import quote_plus
from unittest.mock import patch
import unittest

from desktop_output import DesktopOutputError
from phone_output_protocol import PhoneOutputProtocolState, state_key
from test_receiver_release_all import receiver, InlineFakeBridge, fake_pyautogui, fake_keyboard


class FailedReleaseBridge(InlineFakeBridge):
    def call(self, operation, *args):
        if operation == 'mouse_button' and not args[1]:
            raise DesktopOutputError('synthetic unconfirmed mouse release')
        return super().call(operation, *args)


class NativeOutputReceiverTests(unittest.TestCase):
    address = ('127.0.0.1', 31999)

    def setUp(self):
        self.old_bridge = receiver.desktop_output_bridge
        receiver.desktop_output_bridge = InlineFakeBridge()
        receiver.release_outputs(tuple(receiver.gamepads), 'native mock isolation')
        receiver.release_barriers.clear()
        receiver.release_confirmations.clear()
        receiver.pending_legacy_key_releases.clear()
        receiver._last_desktop_fault = None
        self.old_protocol = receiver.phone_output_protocol
        receiver.phone_output_protocol = PhoneOutputProtocolState(
            receiver.release_state_lock, receiver.output_packet_is_current,
            receiver.apply_phone_aggregate, receiver.dispatch_phone_event,
            receiver.phone_output_is_masked, receiver.neutralize_legacy_phone_outputs)

    def tearDown(self):
        receiver.desktop_output_bridge = InlineFakeBridge()
        receiver.release_outputs(tuple(receiver.gamepads), 'native mock cleanup')
        receiver.phone_output_protocol = self.old_protocol
        receiver.desktop_output_bridge = self.old_bridge

    def send(self, command):
        return receiver.process_command(command, self.address)

    def negotiate(self):
        self.assertIn('PHONE_CAPS_V1_ACK', self.send('PHONE_CAPS_V1:phone:nonce'))
        self.assertIn('PHONE_ACK_V1', self.send('PHONE_ACTIVATE_V1:phone:0'))

    def state(self, sequence, command):
        return self.send('PHONE_STATE_V1:phone:%d:%s:%s' %
                         (sequence, quote_plus(state_key(command, 'player1')), quote_plus(command)))

    def event(self, sequence, command, event=0):
        return self.send('PHONE_EVENT_V1:phone:%d:ring:0:%d:%s' %
                         (sequence, event, quote_plus(command)))

    def test_stalled_desktop_contract_releases_router_lock_and_preserves_controller_progress(self):
        self.send('MOUSE_LEFT_DOWN')
        native_entered, native_release = threading.Event(), threading.Event()
        fake_native = threading.Thread(target=lambda: (native_entered.set(), native_release.wait(2)))
        fake_native.start()
        self.assertTrue(native_entered.wait(1))

        class BoundedFault(InlineFakeBridge):
            fault = ''
            def call(self, operation, *args):
                if self.fault:
                    raise DesktopOutputError(self.fault)
                if operation == 'mouse_button':
                    native_release.wait(.03)
                    self.fault = 'synthetic helper timed out'
                    raise DesktopOutputError(self.fault)
                return super().call(operation, *args)

        receiver.desktop_output_bridge = BoundedFault()
        result = []
        cleanup = threading.Thread(target=lambda: result.append(self.send('RELEASE_ALL:stall:10')))
        try:
            cleanup.start()
            cleanup.join(1)
            self.assertFalse(cleanup.is_alive())
            self.assertEqual([None], result)
            self.send('OUTPUT:stall:11:X360A_HOLD')
            self.assertIn('A', receiver.gamepads['player1'].raw.buttons)
            self.assertEqual('PONG', self.send('PING'))
            self.assertTrue(fake_native.is_alive(), 'Native fake should still be blocked while the receiver progresses')
        finally:
            native_release.set()
            fake_native.join(1)
            cleanup.join(1)

    def test_failed_release_duplicate_never_acks_or_resets_a_newer_controller_hold(self):
        self.send('MOUSE_LEFT_DOWN')
        self.send('X360B_HOLD')
        receiver.desktop_output_bridge = FailedReleaseBridge()
        self.assertIsNone(self.send('RELEASE_ALL:failed:10'))
        self.assertEqual(10, receiver.release_barriers['failed'])
        self.assertNotIn('failed', receiver.release_confirmations)
        self.assertFalse(receiver.gamepads['player1'].raw.buttons)
        self.send('OUTPUT:failed:11:X360A_HOLD')
        resets = receiver.gamepads['player1'].raw.reset_count
        self.assertIsNone(self.send('RELEASE_ALL:failed:10'))
        self.assertIn('A', receiver.gamepads['player1'].raw.buttons)
        self.assertEqual(resets, receiver.gamepads['player1'].raw.reset_count)

    def test_failed_mouse_state_does_not_commit_revision_or_ack_on_retry(self):
        self.negotiate()
        receiver.desktop_output_bridge = FailedReleaseBridge()
        self.assertIsNone(self.state(1, 'MOUSE_LEFT_UP'))
        self.assertIsNone(self.state(1, 'MOUSE_LEFT_UP'))
        self.assertNotIn(('phone', 'GLOBAL|MOUSE|LEFT'), receiver.phone_output_protocol.revisions)
        self.assertIsNone(receiver.desktop_input.emitted['MOUSE_LEFT'])
        receiver.desktop_output_bridge = InlineFakeBridge()
        self.assertIn('PHONE_ACK_V1:phone:1', self.state(1, 'MOUSE_LEFT_UP'))

    def test_failed_scope_end_finishes_other_keys_and_retry_preserves_new_mouse_owner(self):
        self.negotiate()
        self.event(1, 'MOUSE_LEFT_DOWN')
        self.event(2, 'X360A_HOLD', event=1)
        receiver.desktop_output_bridge = FailedReleaseBridge()
        self.assertIsNone(self.send('PHONE_END_V1:phone:3:ring:0'))
        self.assertNotIn('A', receiver.gamepads['player1'].raw.buttons)
        self.assertIn('GLOBAL|MOUSE|LEFT', receiver.phone_output_protocol.pending_ends[('phone', 'ring')])
        receiver.desktop_output_bridge = InlineFakeBridge()
        self.assertIn('PHONE_ACK_V1', self.state(4, 'MOUSE_LEFT_DOWN'))
        fake_pyautogui.ups.clear()
        self.assertEqual('PHONE_ACK_V1:phone:3', self.send('PHONE_END_V1:phone:3:ring:0'))
        self.assertIn('left', fake_pyautogui.down)
        self.assertEqual([], fake_pyautogui.ups)
        self.assertNotIn(('phone', 'ring'), receiver.phone_output_protocol.pending_ends)

    def test_activation_failure_is_retryable_without_confirming_uncertain_native_cleanup(self):
        self.send('MOUSE_LEFT_DOWN')
        self.send('PHONE_CAPS_V1:phone:nonce')
        receiver.desktop_output_bridge = FailedReleaseBridge()
        self.assertIsNone(self.send('PHONE_ACTIVATE_V1:phone:0'))
        self.assertNotIn(('phone', 0), receiver.phone_output_protocol.completed_activations)
        receiver.desktop_output_bridge = InlineFakeBridge()
        self.assertEqual('PHONE_ACK_V1:phone:0', self.send('PHONE_ACTIVATE_V1:phone:0'))
        self.assertNotIn('left', fake_pyautogui.down)
        self.send('OUTPUT:phone:1:X360A_HOLD')
        self.assertEqual('PHONE_ACK_V1:phone:0', self.send('PHONE_ACTIVATE_V1:phone:0'))
        self.assertIn('A', receiver.gamepads['player1'].raw.buttons)

    def test_failed_activation_retry_preserves_new_controller_hold_and_gates_scoped_state(self):
        self.send('MOUSE_LEFT_DOWN')
        self.send('PHONE_CAPS_V1:phone:nonce')
        receiver.desktop_output_bridge = FailedReleaseBridge()
        self.assertIsNone(self.send('PHONE_ACTIVATE_V1:phone:0'))
        self.assertIsNone(self.state(2, 'X360B_HOLD'))
        self.assertNotIn('B', receiver.gamepads['player1'].raw.buttons)
        self.send('OUTPUT:phone:1:X360A_HOLD')
        self.assertIsNone(self.send('PHONE_ACTIVATE_V1:phone:0'))
        self.assertIn('A', receiver.gamepads['player1'].raw.buttons)
        receiver.desktop_output_bridge = InlineFakeBridge()
        self.assertEqual('PHONE_ACK_V1:phone:0', self.send('PHONE_ACTIVATE_V1:phone:0'))
        self.assertIn('A', receiver.gamepads['player1'].raw.buttons)

    def test_failed_activation_identity_is_not_acked_from_a_newer_failed_barrier(self):
        self.send('MOUSE_LEFT_DOWN')
        self.send('PHONE_CAPS_V1:phone:nonce')
        receiver.desktop_output_bridge = FailedReleaseBridge()
        self.assertIsNone(self.send('PHONE_ACTIVATE_V1:phone:0'))
        self.assertIsNone(self.send('PHONE_ACTIVATE_V1:phone:3'))
        self.assertIsNone(self.send('PHONE_ACTIVATE_V1:phone:0'))

    def test_force_failure_does_not_advance_revision_and_duplicate_can_retry(self):
        self.negotiate()
        receiver.desktop_output_bridge = FailedReleaseBridge()
        self.assertIsNone(self.event(2, 'PHONE_FORCE:MOUSE_LEFT_UP'))
        self.assertNotIn(('phone', 'GLOBAL|MOUSE|LEFT'), receiver.phone_output_protocol.revisions)
        receiver.desktop_output_bridge = InlineFakeBridge()
        self.assertIn('PHONE_ACK_V1:phone:2', self.event(2, 'PHONE_FORCE:MOUSE_LEFT_UP'))

    def test_ordinary_desktop_operations_keep_scaling_backend_and_order(self):
        bridge = receiver.desktop_output_bridge
        bridge.calls.clear()
        for command in ('KEY_DOWN:W', 'KEY_UP:W', 'MOUSE_LEFT_DOWN', 'MOUSE_LEFT_UP',
                        'MOUSE_MIDDLE_DOWN', 'MOUSE_MIDDLE_UP', 'DELTA:1.25,-0.5',
                        'GAME_DELTA:0.25,-0.5', 'SCROLL:0.5'):
            self.send(command)
        self.assertEqual([
            ('key', ('w', True)), ('key', ('w', False)),
            ('mouse_button', ('left', True)), ('mouse_button', ('left', False)),
            ('mouse_button', ('middle', True)), ('mouse_button', ('middle', False)),
            ('move', (50, -20)), ('game_move', (10, -20)), ('scroll', (60,))], bridge.calls)

    def test_latched_fault_logs_once_for_repeated_mouse_packets_and_housekeeping(self):
        class LatchedBridge(InlineFakeBridge):
            def call(self, operation, *args):
                raise DesktopOutputError(self.fault)
        bridge = LatchedBridge()
        bridge.fault = 'synthetic latched fault'
        receiver.desktop_output_bridge = bridge
        receiver.key_states['player1']['W'] = True
        with self.assertLogs(receiver.logger, 'ERROR') as logs:
            for _ in range(20):
                self.send('MOUSE_LEFT_UP')
            receiver.clean_key_states()
            receiver.clean_key_states()
        self.assertEqual(1, len(logs.output))

    def test_worker_probe_dispatch_precedes_receiver_and_native_imports(self):
        script = '''import importlib.abc,runpy,sys
class Guard(importlib.abc.MetaPathFinder):
 def find_spec(self,fullname,path=None,target=None):
  if fullname.split('.')[0] in ('vgamepad','keyboard','pyautogui','receiver_timing'):
   raise AssertionError('Unsafe probe import: '+fullname)
sys.meta_path.insert(0,Guard())
sys.argv=[sys.argv[1],'--desktop-output-probe']
runpy.run_path(sys.argv[0],run_name='__main__')
'''
        for name in ('receiver_diagnostics.py', 'simple_controller_receiver.py'):
            path = Path(__file__).with_name(name)
            result = subprocess.run([sys.executable, '-B', '-c', script, str(path)],
                                    cwd=path.parent, capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual('SC_DESKTOP_OUTPUT_PROBE_OK', result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
