"""Bounded desktop output; importing this module never loads native input APIs.

Only the child owns Windows keyboard/mouse calls. A timeout quarantines the
bridge permanently: it cannot cancel a native call already executing, and must
not replay that call or start another helper while its outcome is uncertain.
"""
from dataclasses import dataclass, field
import json
import math
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time


FACE_INPUT_TAG = 0x46474933
MAX_MESSAGE_BYTES = 2048
_VERSION = 1
_STOP = object()
_tagged_keyboard_api = None


class DesktopOutputError(OSError):
    """Desktop output failed or its native result is unconfirmed."""


def send_tagged_key(target, pressed):
    """Preserve facial-key provenance and the existing complete Windows ABI."""
    global _tagged_keyboard_api
    import ctypes
    from ctypes import wintypes
    if _tagged_keyboard_api is None:
        class KeyboardInput(ctypes.Structure):
            _fields_ = [('wVk', wintypes.WORD), ('wScan', wintypes.WORD),
                        ('dwFlags', wintypes.DWORD), ('time', wintypes.DWORD),
                        ('dwExtraInfo', ctypes.c_size_t)]
        class MouseInput(ctypes.Structure):
            _fields_ = [('dx', wintypes.LONG), ('dy', wintypes.LONG),
                        ('mouseData', wintypes.DWORD), ('dwFlags', wintypes.DWORD),
                        ('time', wintypes.DWORD), ('dwExtraInfo', ctypes.c_size_t)]
        class InputUnion(ctypes.Union):
            _fields_ = [('ki', KeyboardInput), ('mi', MouseInput)]
        class Input(ctypes.Structure):
            _anonymous_ = ('data',)
            _fields_ = [('type', wintypes.DWORD), ('data', InputUnion)]
        send = ctypes.WinDLL('user32', use_last_error=True).SendInput
        send.argtypes = (wintypes.UINT, ctypes.POINTER(Input), ctypes.c_int)
        send.restype = wintypes.UINT
        _tagged_keyboard_api = (KeyboardInput, Input, send)
    name = target.removeprefix('KEY:')
    vk_names = {'SPACE': 0x20, 'ENTER': 0x0D, 'TAB': 0x09, 'ESC': 0x1B,
        'BACKSPACE': 0x08, 'UP': 0x26, 'DOWN': 0x28, 'LEFT': 0x25, 'RIGHT': 0x27,
        'SHIFT': 0x10, 'CTRL': 0x11, 'ALT': 0x12, 'WIN': 0x5B,
        'LSHIFT': 0xA0, 'RSHIFT': 0xA1, 'LCTRL': 0xA2, 'RCTRL': 0xA3,
        'LALT': 0xA4, 'RALT': 0xA5, 'LWIN': 0x5B, 'RWIN': 0x5C,
        'HOME': 0x24, 'END': 0x23, 'PAGEUP': 0x21, 'PAGEDOWN': 0x22,
        'INSERT': 0x2D, 'DELETE': 0x2E, 'CAPSLOCK': 0x14}
    vk = (ord(name) if len(name) == 1 else 0x6F + int(name[1:])
          if name.startswith('F') and name[1:].isdigit() else vk_names[name])
    flags = 0 if pressed else 2
    if name in ('UP', 'DOWN', 'LEFT', 'RIGHT', 'HOME', 'END', 'PAGEUP', 'PAGEDOWN',
                'INSERT', 'DELETE', 'RCTRL', 'RALT', 'WIN', 'LWIN', 'RWIN'):
        flags |= 1
    KeyboardInput, Input, send = _tagged_keyboard_api
    item = Input(type=1, ki=KeyboardInput(vk, 0, flags, 0, FACE_INPUT_TAG))
    if send(1, ctypes.byref(item), ctypes.sizeof(Input)) != 1:
        raise OSError(ctypes.get_last_error(),
                      'Facial SendInput failed; check application elevation')


def _validate_call(operation, args):
    sizes = {'key': 2, 'key_tap': 1, 'key_state': 1, 'tagged_key': 2,
             'mouse_button': 2, 'move': 2, 'game_move': 2, 'scroll': 1}
    if operation not in sizes or len(args) != sizes[operation]:
        raise ValueError('Unsupported desktop output operation or arguments')
    if operation in ('key', 'key_tap', 'key_state', 'tagged_key'):
        if not isinstance(args[0], str) or not 0 < len(args[0]) <= 256:
            raise ValueError('Invalid desktop key name')
    if operation == 'mouse_button' and args[0] not in ('left', 'right', 'middle'):
        raise ValueError('Invalid mouse button')
    if operation in ('key', 'tagged_key', 'mouse_button') and type(args[1]) is not bool:
        raise ValueError('Desktop button state must be boolean')
    if operation in ('move', 'game_move', 'scroll'):
        for value in args:
            try:
                finite = type(value) in (int, float) and math.isfinite(value)
            except OverflowError:
                finite = False
            if not finite:
                raise ValueError('Desktop movement must be finite')
            if operation != 'move' and (type(value) is not int or not -(2**31) <= value < 2**31):
                raise ValueError('Desktop movement must fit a Windows LONG')


class NativeDesktopBackend:
    """Construct only in the helper; no receiver, gamepad or network imports."""
    def __init__(self):
        import keyboard
        import pyautogui
        self.keyboard = keyboard
        self.mouse = pyautogui
        self.mouse.FAILSAFE = False
        self.mouse.PAUSE = 0
        # keyboard 0.13.5 lazily builds Windows name/scan-code tables. Do this
        # read-only lookup within startup's longer budget, not the first RPC.
        # It neither injects input nor installs the keyboard event listener.
        self.keyboard.key_to_scan_codes('space')

    def call(self, operation, *args):
        _validate_call(operation, args)
        if operation == 'key':
            return (self.keyboard.press if args[1] else self.keyboard.release)(args[0])
        if operation == 'key_tap':
            return self.keyboard.press_and_release(args[0])
        if operation == 'key_state':
            return bool(self.keyboard.is_pressed(args[0]))
        if operation == 'tagged_key':
            return send_tagged_key(*args)
        if operation == 'mouse_button':
            return (self.mouse.mouseDown if args[1] else self.mouse.mouseUp)(button=args[0])
        if operation == 'move':
            return self.mouse.moveRel(*args)
        if operation == 'game_move':
            from relative_mouse import send_relative_mouse
            return send_relative_mouse(*args)
        return self.mouse.scroll(args[0])


def _encode(message):
    data = (json.dumps(message, ensure_ascii=True, allow_nan=False,
                       separators=(',', ':')) + '\n').encode('ascii')
    if len(data) > MAX_MESSAGE_BYTES:
        raise ValueError('Desktop output message exceeds its size limit')
    return data


def _read(stream):
    line = stream.readline(MAX_MESSAGE_BYTES + 1)
    if not line:
        raise DesktopOutputError('Desktop helper closed its output')
    if len(line) > MAX_MESSAGE_BYTES or not line.endswith(b'\n'):
        raise DesktopOutputError('Invalid desktop helper message size')
    def bad_constant(value):
        raise ValueError('Nonfinite JSON value')
    try:
        value = json.loads(line.decode('ascii'), parse_constant=bad_constant)
    except (ValueError, UnicodeError) as error:
        raise DesktopOutputError('Invalid desktop helper JSON') from error
    if (not isinstance(value, dict) or type(value.get('v')) is not int
            or value['v'] != _VERSION):
        raise DesktopOutputError('Invalid desktop helper protocol')
    return value


def worker_main(backend=None, input_stream=None, output_stream=None, clock=time.monotonic):
    """Serve bounded JSON lines. Tests inject streams/backend, never native APIs."""
    incoming = sys.stdin.buffer if input_stream is None else input_stream
    outgoing = sys.stdout.buffer if output_stream is None else output_stream
    try:
        backend = NativeDesktopBackend() if backend is None else backend
        outgoing.write(_encode({'v': _VERSION, 'ready': True}))
        outgoing.flush()
    except Exception as error:
        try:
            outgoing.write(_encode({'v': _VERSION, 'ready': False,
                                    'error': str(error)[:400]}))
            outgoing.flush()
        except Exception:
            pass
        return 1
    sequence = 0
    while True:
        try:
            request = _read(incoming)
        except DesktopOutputError:
            return 0
        request_id = request.get('id')
        if (set(request) != {'v', 'id', 'deadline', 'op', 'args'}
                or type(request_id) is not int or request_id != sequence + 1
                or type(request.get('deadline')) not in (int, float)
                or not math.isfinite(request['deadline'])
                or not isinstance(request.get('op'), str)
                or not isinstance(request.get('args'), list)):
            return 1
        sequence = request_id
        try:
            _validate_call(request['op'], request['args'])
            if clock() >= request['deadline']:
                raise DesktopOutputError('Desktop output expired before native dispatch')
            result = backend.call(request['op'], *request['args'])
            if result is not None and type(result) is not bool:
                raise DesktopOutputError('Invalid desktop backend result')
            reply = {'v': _VERSION, 'id': request_id, 'ok': True, 'result': result}
        except (ValueError, KeyError, TypeError) as error:
            reply = {'v': _VERSION, 'id': request_id, 'ok': False,
                     'invalid': True, 'error': str(error)[:400]}
        except Exception as error:
            reply = {'v': _VERSION, 'id': request_id, 'ok': False,
                     'invalid': False, 'error': str(error)[:400]}
        try:
            outgoing.write(_encode(reply))
            outgoing.flush()
        except Exception:
            return 1


@dataclass
class _Request:
    payload: dict
    deadline: float
    done: threading.Event = field(default_factory=threading.Event)
    result: object = None
    error: object = None


class DesktopOutputBridge:
    """One child and one outstanding RPC; permanently fail closed on uncertainty.

    A daemon I/O thread owns Popen and both pipe streams. Callers only perform
    bounded lock/event waits, so even process creation or a pipe write cannot
    freeze the receiver. No native helper is started by this constructor.
    """
    def __init__(self, timeout=0.1, *, process_factory=None, clock=time.monotonic):
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError('Desktop output timeout must be positive')
        self.timeout = timeout
        self._factory = subprocess.Popen if process_factory is None else process_factory
        self._clock = clock
        self._fault = ''
        self._closed = False
        self._started = False
        self._ready = threading.Event()
        self._ready_ok = False
        self._halt = threading.Event()
        self._rpc_lock = threading.Lock()
        self._requests = queue.Queue(maxsize=1)
        self._thread = None
        self._process = None
        self._termination_process = None
        self._sequence = 0

    @property
    def fault(self):
        return self._fault

    def _check(self):
        if self._fault:
            raise DesktopOutputError(self._fault)
        if self._closed:
            raise DesktopOutputError('Desktop output bridge is closed')

    def _terminate(self):
        process = self._process
        if process is None or self._termination_process is process:
            return
        self._termination_process = process
        def stop_child():
            try:
                process.terminate()
            except Exception:
                pass
            try:
                process.wait(timeout=0.1)
            except Exception:
                pass
        try:
            threading.Thread(target=stop_child, name='Desktop output termination', daemon=True).start()
        except Exception:
            # Failure to allocate a cleanup thread cannot mask the original
            # bounded-output failure or turn it into an apparent success.
            pass

    def _fail(self, reason):
        if not self._fault:
            self._fault = str(reason)
        self._halt.set()
        self._terminate()
        return DesktopOutputError(self._fault)

    def start(self, timeout=10):
        self._check()
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError('Desktop helper startup timeout must be positive')
        deadline = self._clock() + timeout
        if not self._rpc_lock.acquire(timeout=max(0, deadline - self._clock())):
            raise self._fail('Desktop helper startup lock timed out; output is unconfirmed')
        try:
            self._check()
            if not self._started:
                self._started = True
                try:
                    self._thread = threading.Thread(target=self._io_loop,
                                                    name='Desktop output IPC', daemon=True)
                    self._thread.start()
                except Exception as error:
                    self._thread = None
                    raise self._fail('Desktop helper startup failed: ' + str(error)) from error
            if not self._ready.wait(max(0, deadline - self._clock())):
                raise self._fail('Desktop helper startup timed out; output is unconfirmed')
            self._check()
            if not self._ready_ok:
                raise self._fail('Desktop helper did not become ready')
            return self
        finally:
            self._rpc_lock.release()

    def call(self, operation, *args):
        deadline = self._clock() + self.timeout
        self._check()
        _validate_call(operation, args)
        if not self._started or not self._ready_ok:
            raise DesktopOutputError('Desktop helper has not been started')
        if not self._rpc_lock.acquire(timeout=max(0, deadline - self._clock())):
            raise self._fail('Desktop output lock timed out; native output is unconfirmed')
        try:
            self._check()
            payload = {'v': _VERSION, 'id': self._sequence + 1, 'deadline': deadline,
                       'op': operation, 'args': list(args)}
            _encode(payload)  # Validate size before handing anything to the I/O thread.
            self._sequence = payload['id']
            request = _Request(payload, deadline)
            try:
                self._requests.put_nowait(request)
            except queue.Full:
                raise self._fail('Desktop output handoff is full; output is unconfirmed')
            if not request.done.wait(max(0, deadline - self._clock())):
                raise self._fail('Desktop output timed out; native output is unconfirmed')
            self._check()
            if request.error is not None:
                if isinstance(request.error, ValueError):
                    raise request.error
                raise self._fail(request.error)
            return request.result
        finally:
            self._rpc_lock.release()

    def _io_loop(self):
        current = None
        try:
            command = ([sys.executable, '--desktop-output-worker']
                       if getattr(sys, 'frozen', False) else
                       [sys.executable, '-B', str(Path(__file__).resolve()), '--desktop-output-worker'])
            self._process = self._factory(command, stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=MAX_MESSAGE_BYTES,
                close_fds=True, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            if self._halt.is_set():
                return
            ready = _read(self._process.stdout)
            if ready != {'v': _VERSION, 'ready': True}:
                raise DesktopOutputError('Desktop helper startup failed: ' + str(ready.get('error', 'invalid greeting')))
            self._ready_ok = True
            self._ready.set()
            while not self._halt.is_set():
                current = self._requests.get()
                if current is _STOP:
                    current = None
                    break
                if self._halt.is_set() or self._clock() >= current.deadline:
                    raise DesktopOutputError('Desktop output expired before IPC dispatch')
                packet = _encode(current.payload)
                sent = self._process.stdin.write(packet)
                if sent != len(packet):
                    raise DesktopOutputError('Incomplete desktop helper request')
                self._process.stdin.flush()
                response = _read(self._process.stdout)
                if self._clock() >= current.deadline or self._halt.is_set():
                    raise DesktopOutputError('Desktop output completed after its deadline; result is unconfirmed')
                if (response.get('id') != current.payload['id']
                        or type(response.get('id')) is not int
                        or type(response.get('ok')) is not bool):
                    raise DesktopOutputError('Desktop helper response identity mismatch')
                if not response['ok']:
                    if (set(response) != {'v', 'id', 'ok', 'invalid', 'error'}
                            or type(response['invalid']) is not bool
                            or not isinstance(response['error'], str)):
                        raise DesktopOutputError('Invalid desktop helper error')
                    if response['invalid']:
                        current.error = ValueError(response['error'])
                        current.done.set()
                        current = None
                        continue
                    raise DesktopOutputError('Desktop output failed: ' + str(response.get('error', 'unknown error')))
                if set(response) != {'v', 'id', 'ok', 'result'} or (response['result'] is not None and type(response['result']) is not bool):
                    raise DesktopOutputError('Invalid desktop helper result')
                current.result = response['result']
                current.done.set()
                current = None
        except Exception as error:
            self._fail(error)
            if current is not None:
                current.error = error
                current.done.set()
        finally:
            self._ready.set()
            if self._halt.is_set() or self._fault:
                self._terminate()
            if self._process is not None:
                for stream in (self._process.stdin, self._process.stdout):
                    try:
                        stream.close()
                    except Exception:
                        pass

    def close(self, timeout=0.2):
        """Bounded teardown attempt; never claims that a stuck native call ended."""
        deadline = self._clock() + max(0, timeout)
        self._closed = True
        self._halt.set()
        try:
            self._requests.put_nowait(_STOP)
        except queue.Full:
            pass
        self._terminate()
        if self._thread is not None and self._thread is not threading.current_thread():
            self._thread.join(max(0, deadline - self._clock()))


def probe_main(output_stream=None):
    """Verify packaged early dispatch without loading any native input library."""
    stream = sys.stdout.buffer if output_stream is None else output_stream
    stream.write(b'SC_DESKTOP_OUTPUT_PROBE_OK\n')
    stream.flush()
    return 0


if __name__ == '__main__':
    if sys.argv[1:] == ['--desktop-output-probe']:
        raise SystemExit(probe_main())
    if sys.argv[1:] == ['--desktop-output-worker']:
        raise SystemExit(worker_main())
    raise SystemExit('This module is the private desktop-output worker.')
