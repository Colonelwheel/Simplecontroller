"""Relative Windows mouse events for the opt-in Touch Aim Game Mouse control.

Importing this module neither loads user32 nor submits input. Native binding is
lazy so the complete ABI and packet handling can be tested with fake outputs.
"""
import ctypes
import os


INPUT_MOUSE = 0
MOUSEEVENTF_MOVE = 0x0001
LONG_MIN = -(2 ** 31)
LONG_MAX = 2 ** 31 - 1


def win32_input_types(pointer_size=None):
    """Return the complete INPUT ABI using fixed Win32 widths on any host."""
    pointer_size = ctypes.sizeof(ctypes.c_void_p) if pointer_size is None else pointer_size
    if pointer_size not in (4, 8):
        raise ValueError("Windows INPUT requires a 4- or 8-byte pointer")
    ulong_ptr = ctypes.c_uint64 if pointer_size == 8 else ctypes.c_uint32

    class MouseInput(ctypes.Structure):
        _fields_ = [('dx', ctypes.c_int32), ('dy', ctypes.c_int32),
                    ('mouseData', ctypes.c_uint32), ('dwFlags', ctypes.c_uint32),
                    ('time', ctypes.c_uint32), ('dwExtraInfo', ulong_ptr)]

    class KeyboardInput(ctypes.Structure):
        _fields_ = [('wVk', ctypes.c_uint16), ('wScan', ctypes.c_uint16),
                    ('dwFlags', ctypes.c_uint32), ('time', ctypes.c_uint32),
                    ('dwExtraInfo', ulong_ptr)]

    class HardwareInput(ctypes.Structure):
        _fields_ = [('uMsg', ctypes.c_uint32), ('wParamL', ctypes.c_uint16),
                    ('wParamH', ctypes.c_uint16)]

    class InputUnion(ctypes.Union):
        _fields_ = [('mi', MouseInput), ('ki', KeyboardInput), ('hi', HardwareInput)]

    class Input(ctypes.Structure):
        _anonymous_ = ('data',)
        _fields_ = [('type', ctypes.c_uint32), ('data', InputUnion)]

    return MouseInput, KeyboardInput, HardwareInput, Input


MouseInput, KeyboardInput, HardwareInput, Input = win32_input_types()
_send_input_api = None


def _native_api():
    global _send_input_api
    if _send_input_api is None:
        if os.name != 'nt':
            raise OSError("Touch Aim Game Mouse requires Windows SendInput")
        send = ctypes.WinDLL('user32', use_last_error=True).SendInput
        send.argtypes = (ctypes.c_uint32, ctypes.POINTER(Input), ctypes.c_int)
        send.restype = ctypes.c_uint32
        _send_input_api = (send, ctypes.get_last_error, ctypes.set_last_error)
    return _send_input_api


def send_relative_mouse(dx, dy):
    """Submit one relative movement; raise visibly on invalid or failed output.

    No ABSOLUTE flag, cursor lookup, cursor warp, key/button or scroll event is
    used. A zero vector is a no-op and does not even bind the native API.
    """
    for value in (dx, dy):
        if type(value) is not int or not LONG_MIN <= value <= LONG_MAX:
            raise ValueError("Game mouse delta must fit a signed 32-bit Windows LONG")
    if not (dx or dy):
        return False
    item = Input(type=INPUT_MOUSE, mi=MouseInput(dx, dy, 0, MOUSEEVENTF_MOVE, 0, 0))
    send, get_error, set_error = _native_api()
    set_error(0)
    sent = send(1, ctypes.byref(item), ctypes.sizeof(Input))
    if sent != 1:
        raise OSError(get_error(),
            f"Touch Aim Game Mouse SendInput accepted {sent}/1 events; "
            "check application elevation and Windows input restrictions")
    return True
