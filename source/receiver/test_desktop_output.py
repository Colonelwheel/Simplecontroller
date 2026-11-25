"""Offline native-isolation tests: fake processes/backends, no hooks or input."""
import io
import json
import queue
import threading
import time
import unittest
from unittest import mock

import desktop_output as output


class _Replies:
    def __init__(self):
        self.lines = queue.Queue()
        self.lines.put(output._encode({'v': 1, 'ready': True}))

    def readline(self, limit):
        return self.lines.get(timeout=3)

    def close(self):
        pass


class _Input:
    def __init__(self, process):
        self.process = process

    def write(self, packet):
        request = json.loads(packet)
        self.process.requests.append(request)
        self.process.entered.set()
        if self.process.block is not None:
            self.process.block.wait(3)
        if self.process.reply is not None:
            response = self.process.reply(request)
        else:
            response = {'v': 1, 'id': request['id'], 'ok': True,
                        'result': False if request['op'] == 'key_state' else None}
        self.process.stdout.lines.put(output._encode(response))
        return len(packet)

    def flush(self):
        if self.process.flush_block is not None:
            self.process.flush_block.wait(3)

    def close(self):
        pass


class _Process:
    def __init__(self, *, block=None, flush_block=None, reply=None):
        self.block, self.flush_block, self.reply = block, flush_block, reply
        self.stdout = _Replies()
        self.stdin = _Input(self)
        self.requests = []
        self.entered = threading.Event()
        self.terminations = 0

    def terminate(self):
        self.terminations += 1
        self.stdout.lines.put(b'')

    def wait(self, timeout):
        return 0


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.bridges = []
        self.blocks = []

    def tearDown(self):
        for event in self.blocks:
            event.set()
        for bridge in self.bridges:
            bridge.close(timeout=0.2)

    def make(self, process=None, timeout=0.04, factory=None):
        process = process or _Process()
        factory = factory or mock.Mock(return_value=process)
        bridge = output.DesktopOutputBridge(timeout=timeout, process_factory=factory)
        self.bridges.append(bridge)
        return bridge, process, factory

    def block(self):
        event = threading.Event()
        self.blocks.append(event)
        return event

    def test_constructor_is_inert_and_start_owns_only_private_process_pipes(self):
        bridge, process, factory = self.make()
        factory.assert_not_called()
        with self.assertRaisesRegex(output.DesktopOutputError, 'not been started'):
            bridge.call('move', 1, 2)
        bridge.start(timeout=1)
        bridge.start(timeout=1)
        self.assertEqual(factory.call_count, 1)
        args, options = factory.call_args
        self.assertIn('--desktop-output-worker', args[0])
        self.assertTrue(options['close_fds'])
        self.assertEqual(options['bufsize'], output.MAX_MESSAGE_BYTES)
        self.assertFalse(bridge.call('key_state', 'w'))
        self.assertEqual(process.requests[0]['id'], 1)
        self.assertEqual(process.requests[0]['args'], ['w'])

    def test_blocked_output_times_out_and_never_replays_or_restarts(self):
        process = _Process(block=self.block())
        bridge, process, factory = self.make(process)
        bridge.start(timeout=1)
        started = time.monotonic()
        with self.assertRaisesRegex(output.DesktopOutputError, 'timed out'):
            bridge.call('mouse_button', 'left', False)
        self.assertLess(time.monotonic() - started, 0.3)
        self.assertTrue(bridge.fault)
        started = time.monotonic()
        with self.assertRaises(output.DesktopOutputError):
            bridge.call('key', 'w', True)
        with self.assertRaises(output.DesktopOutputError):
            bridge.start(timeout=1)
        self.assertLess(time.monotonic() - started, 0.1)
        self.assertEqual(len(process.requests), 1)
        self.assertEqual(factory.call_count, 1)

    def test_process_creation_and_startup_wait_are_bounded(self):
        blocked = self.block()
        process = _Process()
        def factory(*args, **kwargs):
            blocked.wait(3)
            return process
        bridge, _, _ = self.make(process, factory=factory)
        started = time.monotonic()
        with self.assertRaisesRegex(output.DesktopOutputError, 'startup timed out'):
            bridge.start(timeout=0.04)
        self.assertLess(time.monotonic() - started, 0.3)
        self.assertEqual(process.requests, [])
        blocked.set()

    def test_pipe_flush_is_inside_same_timeout(self):
        bridge, process, _ = self.make(_Process(flush_block=self.block()))
        bridge.start(timeout=1)
        started = time.monotonic()
        with self.assertRaises(output.DesktopOutputError):
            bridge.call('move', 3, 4)
        self.assertLess(time.monotonic() - started, 0.3)
        self.assertTrue(bridge.fault)

    def test_response_read_is_inside_timeout(self):
        bridge, process, _ = self.make()
        process.stdin.write = lambda packet: len(packet)  # No child reply arrives.
        bridge.start(timeout=1)
        started = time.monotonic()
        with self.assertRaises(output.DesktopOutputError):
            bridge.call('move', 3, 4)
        self.assertLess(time.monotonic() - started, 0.3)
        self.assertTrue(bridge.fault)

    def test_thread_start_failure_is_latched_as_desktop_error(self):
        bridge, _, factory = self.make()
        with mock.patch.object(threading.Thread, 'start', side_effect=RuntimeError('no threads')):
            with self.assertRaisesRegex(output.DesktopOutputError, 'startup failed'):
                bridge.start(timeout=1)
        self.assertTrue(bridge.fault)
        factory.assert_not_called()
        with self.assertRaises(output.DesktopOutputError):
            bridge.start(timeout=1)

    def test_termination_thread_start_failure_preserves_original_timeout(self):
        bridge, _, _ = self.make(_Process(block=self.block()))
        bridge.start(timeout=1)
        with mock.patch.object(threading.Thread, 'start', side_effect=RuntimeError('no threads')):
            with self.assertRaisesRegex(output.DesktopOutputError, 'timed out'):
                bridge.call('mouse_button', 'left', False)
        self.assertIn('timed out', bridge.fault)

    def test_lock_contention_is_inside_timeout_and_does_not_queue_output(self):
        bridge, process, _ = self.make()
        bridge.start(timeout=1)
        bridge._rpc_lock.acquire()
        try:
            started = time.monotonic()
            with self.assertRaisesRegex(output.DesktopOutputError, 'lock timed out'):
                bridge.call('move', 1, 2)
            self.assertLess(time.monotonic() - started, 0.3)
            self.assertEqual(process.requests, [])
        finally:
            bridge._rpc_lock.release()

    def test_invalid_keyboard_name_is_recoverable_but_native_error_is_not(self):
        def reply(request):
            if request['args'][0] == 'not a real key':
                return {'v': 1, 'id': request['id'], 'ok': False,
                        'invalid': True, 'error': 'Unknown key'}
            if request['args'][0] == 'failed':
                return {'v': 1, 'id': request['id'], 'ok': False,
                        'invalid': False, 'error': 'Native write failed'}
            return {'v': 1, 'id': request['id'], 'ok': True, 'result': None}
        bridge, process, _ = self.make(_Process(reply=reply))
        bridge.start(timeout=1)
        with self.assertRaisesRegex(ValueError, 'Unknown key'):
            bridge.call('key', 'not a real key', True)
        self.assertEqual(bridge.fault, '')
        bridge.call('key', 'w', True)
        with self.assertRaisesRegex(output.DesktopOutputError, 'Native write failed'):
            bridge.call('key', 'failed', False)
        self.assertTrue(bridge.fault)
        self.assertEqual([r['id'] for r in process.requests], [1, 2, 3])

    def test_wrong_response_identity_permanently_faults(self):
        process = _Process(reply=lambda r: {'v': 1, 'id': r['id'] + 1,
                                           'ok': True, 'result': None})
        bridge, _, _ = self.make(process)
        bridge.start(timeout=1)
        with self.assertRaisesRegex(output.DesktopOutputError, 'identity mismatch'):
            bridge.call('scroll', 120)
        self.assertTrue(bridge.fault)

    def test_invalid_call_does_not_consume_sequence_or_fault_bridge(self):
        bridge, process, _ = self.make()
        bridge.start(timeout=1)
        for operation, args in [('unknown', ()), ('key', ('w', 1)),
                                ('key', ('\U0001f600' * 256, True)),
                                ('move', (10**1000, 0)),
                                ('move', (float('nan'), 0)), ('game_move', (1.1, 2))]:
            with self.assertRaises(ValueError):
                bridge.call(operation, *args)
        self.assertEqual(bridge.fault, '')
        bridge.call('move', 2, 3)
        self.assertEqual(process.requests[0]['id'], 1)

    def test_close_does_not_wait_for_blocked_native_or_termination(self):
        process = _Process(block=self.block())
        termination = self.block()
        process.terminate = lambda: termination.wait(3)
        bridge, _, _ = self.make(process)
        bridge.start(timeout=1)
        with self.assertRaises(output.DesktopOutputError):
            bridge.call('mouse_button', 'right', True)
        started = time.monotonic()
        bridge.close(timeout=0.02)
        self.assertLess(time.monotonic() - started, 0.2)


class WorkerTests(unittest.TestCase):
    def run_worker(self, requests, backend=None, clock=lambda: 10.0):
        backend = backend or mock.Mock()
        backend.call.return_value = None
        incoming = io.BytesIO(b''.join(output._encode(request) for request in requests))
        outgoing = io.BytesIO()
        result = output.worker_main(backend, incoming, outgoing, clock=clock)
        messages = [json.loads(line) for line in outgoing.getvalue().splitlines()]
        return result, messages, backend

    def request(self, number=1, **changes):
        value = {'v': 1, 'id': number, 'deadline': 11.0,
                 'op': 'mouse_button', 'args': ['left', True]}
        value.update(changes)
        return value

    def test_worker_dispatches_in_order_and_preserves_native_result(self):
        result, messages, backend = self.run_worker([
            self.request(), self.request(2, op='move', args=[3, -4])])
        self.assertEqual(result, 0)
        self.assertEqual(messages[0], {'v': 1, 'ready': True})
        self.assertTrue(all(message['ok'] for message in messages[1:]))
        self.assertEqual(backend.call.call_args_list,
                         [mock.call('mouse_button', 'left', True), mock.call('move', 3, -4)])

    def test_expired_input_never_reaches_native_backend(self):
        _, messages, backend = self.run_worker([self.request(deadline=10.0)])
        backend.call.assert_not_called()
        self.assertFalse(messages[1]['ok'])
        self.assertFalse(messages[1]['invalid'])
        self.assertIn('expired', messages[1]['error'])

    def test_duplicate_or_out_of_order_sequence_cannot_replay_input(self):
        result, messages, backend = self.run_worker([self.request(), self.request()])
        self.assertEqual(result, 1)
        self.assertEqual(backend.call.call_count, 1)
        self.assertEqual(len(messages), 2)

    def test_validation_errors_are_recoverable_and_native_errors_explicit(self):
        backend = mock.Mock()
        backend.call.side_effect = [ValueError('unknown key'), OSError('denied')]
        _, messages, _ = self.run_worker([
            self.request(op='key', args=['unknown', True]),
            self.request(2, op='key', args=['w', True])], backend)
        self.assertTrue(messages[1]['invalid'])
        self.assertFalse(messages[2]['invalid'])
        self.assertIn('denied', messages[2]['error'])

    def test_bad_or_oversized_json_never_dispatches(self):
        for payload in (b'x\n', b'{}\n', b'x' * (output.MAX_MESSAGE_BYTES + 1),
                        b'{"v":1,"id":NaN}\n'):
            with self.subTest(payload=payload[:30]):
                backend, outgoing = mock.Mock(), io.BytesIO()
                output.worker_main(backend, io.BytesIO(payload), outgoing)
                backend.call.assert_not_called()

    def test_native_backend_forwards_all_operations_only_to_fake_libraries(self):
        keyboard, mouse = mock.Mock(), mock.Mock()
        keyboard.is_pressed.return_value = True
        with mock.patch.dict('sys.modules', {'keyboard': keyboard, 'pyautogui': mouse}), \
                mock.patch.object(output, 'send_tagged_key') as tagged, \
                mock.patch('relative_mouse.send_relative_mouse') as game_move:
            backend = output.NativeDesktopBackend()
            keyboard.key_to_scan_codes.assert_called_once_with('space')
            self.assertFalse(mouse.FAILSAFE)
            self.assertEqual(mouse.PAUSE, 0)
            for operation, args in [('key', ('shift', True)), ('key', ('shift', False)),
                    ('key_tap', ('ctrl+a',)), ('key_state', ('w',)),
                    ('tagged_key', ('KEY:RCTRL', False)), ('mouse_button', ('middle', True)),
                    ('mouse_button', ('middle', False)), ('move', (1.5, -2)),
                    ('game_move', (4, 5)), ('scroll', (120,))]:
                backend.call(operation, *args)
            keyboard.press.assert_called_once_with('shift')
            keyboard.release.assert_called_once_with('shift')
            keyboard.press_and_release.assert_called_once_with('ctrl+a')
            keyboard.is_pressed.assert_called_once_with('w')
            tagged.assert_called_once_with('KEY:RCTRL', False)
            mouse.mouseDown.assert_called_once_with(button='middle')
            mouse.mouseUp.assert_called_once_with(button='middle')
            mouse.moveRel.assert_called_once_with(1.5, -2)
            game_move.assert_called_once_with(4, 5)
            mouse.scroll.assert_called_once_with(120)

    def test_probe_does_not_construct_native_backend(self):
        stream = io.BytesIO()
        with mock.patch.object(output, 'NativeDesktopBackend', side_effect=AssertionError('native')):
            self.assertEqual(output.probe_main(stream), 0)
        self.assertEqual(stream.getvalue(), b'SC_DESKTOP_OUTPUT_PROBE_OK\n')


if __name__ == '__main__':
    unittest.main()
