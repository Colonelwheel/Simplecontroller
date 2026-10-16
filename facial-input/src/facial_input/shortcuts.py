"""Bounded shortcut key state; facial injected keys never recenter themselves."""
from __future__ import annotations

import ctypes
import os
import threading

FACE_INPUT_TAG = 0x46474933
KEY_CODES = {**{chr(code): code for code in range(65, 91)},
             **{str(number): 48 + number for number in range(10)},
             **{f"F{number}": 111 + number for number in range(1, 25)},
             "SPACE": 32, "ENTER": 13, "TAB": 9, "ESC": 27, "BACKSPACE": 8,
             "UP": 38, "DOWN": 40, "LEFT": 37, "RIGHT": 39, "SHIFT": 16,
             "CTRL": 17, "ALT": 18, "WIN": 91, "LSHIFT": 160, "RSHIFT": 161,
             "LCTRL": 162, "RCTRL": 163, "LALT": 164, "RALT": 165,
             "LWIN": 91, "RWIN": 92, "HOME": 36, "END": 35,
             "PAGEUP": 33, "PAGEDOWN": 34, "INSERT": 45, "DELETE": 46, "CAPSLOCK": 20}
_ALIASES = {"LSHIFT": "SHIFT", "RSHIFT": "SHIFT", "LCTRL": "CTRL", "RCTRL": "CTRL",
            "LALT": "ALT", "RALT": "ALT", "LWIN": "WIN", "RWIN": "WIN"}


class ShortcutKeys:
    """Observes only configured keys, stores a set, never logs keystrokes/text."""
    def __init__(self, keys=()):
        self.keys = frozenset(keys)
        if any(key not in KEY_CODES for key in self.keys):
            raise ValueError("Unsupported shortcut key")
        self._lock = threading.Lock()
        self._pressed = set()
        self._raw_pressed = set()
        self._events = []
        self._blocked_raw = set()
        self._thread = None
        self._thread_id = None
        self._stop = threading.Event()
        self.status = "not started"

    def observe(self, key, down, extra_info=0):
        """Pure event entry point also used by synthetic tests."""
        if extra_info == FACE_INPUT_TAG:
            # Startup state has no provenance. A tagged release can only remove
            # its initial block, never create a physical press or chord event.
            if not down:
                with self._lock:
                    self._blocked_raw.discard(key)
            return
        if not ({key, _ALIASES.get(key, key)} & self.keys):
            return
        with self._lock:
            if key in self._blocked_raw:
                if not down:
                    self._blocked_raw.discard(key)
                return
            if down:
                self._raw_pressed.add(key)
            else:
                self._raw_pressed.discard(key)
            self._pressed = ({name for raw in self._raw_pressed
                              for name in (raw, _ALIASES.get(raw, raw))} & self.keys)
            if len(self._events) >= 64:
                self._events.clear()
                self._raw_pressed.clear()
                self._pressed.clear()
                self.status = "Shortcut event overflow; release keys before retry"
            self._events.append(frozenset(self._pressed))

    def snapshot(self):
        with self._lock:
            return frozenset(self._pressed)

    def drain_events(self):
        with self._lock:
            events, self._events = self._events, []
            return events

    def start(self):
        if self._thread is not None or not self.keys:
            return
        if os.name != "nt":
            self.status = "Windows keyboard shortcuts unavailable"
            return
        self._thread = threading.Thread(target=self._run, name="facial-shortcut-keys", daemon=True)
        self._thread.start()

    def _run(self):
        from ctypes import wintypes
        user = ctypes.WinDLL("user32", use_last_error=True)
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        pointer = ctypes.c_size_t
        result_type = ctypes.c_ssize_t
        callback_type = ctypes.WINFUNCTYPE(result_type, ctypes.c_int, pointer, ctypes.c_ssize_t)

        class KeyEvent(ctypes.Structure):
            _fields_ = [("vkCode", wintypes.DWORD), ("scanCode", wintypes.DWORD),
                        ("flags", wintypes.DWORD), ("time", wintypes.DWORD), ("extra", pointer)]

        user.SetWindowsHookExW.argtypes = [ctypes.c_int, callback_type, wintypes.HINSTANCE, wintypes.DWORD]
        user.SetWindowsHookExW.restype = wintypes.HHOOK
        user.CallNextHookEx.argtypes = [wintypes.HHOOK, ctypes.c_int, pointer, ctypes.c_ssize_t]
        user.CallNextHookEx.restype = result_type
        user.UnhookWindowsHookEx.argtypes = [wintypes.HHOOK]
        kernel.GetCurrentThreadId.restype = wintypes.DWORD
        user.GetAsyncKeyState.argtypes = [ctypes.c_int]
        user.GetAsyncKeyState.restype = ctypes.c_short
        self._thread_id = kernel.GetCurrentThreadId()
        # Prime the message queue before another thread attempts WM_QUIT.
        message = wintypes.MSG()
        user.PeekMessageW(ctypes.byref(message), None, 0, 0, 0)
        code_names = {}
        for name, code in KEY_CODES.items():
            if name in self.keys or _ALIASES.get(name) in self.keys:
                code_names.setdefault(code, []).append(name)
        hook = None
        try:
            @callback_type
            def callback(code, event_type, address):
                if code >= 0 and event_type in (0x100, 0x101, 0x104, 0x105):
                    event = ctypes.cast(address, ctypes.POINTER(KeyEvent)).contents
                    for name in code_names.get(event.vkCode, ()):
                        self.observe(name, event_type in (0x100, 0x104), event.extra)
                return user.CallNextHookEx(None, code, event_type, address)

            hook = user.SetWindowsHookExW(13, callback, None, 0)
            if not hook:
                raise ctypes.WinError(ctypes.get_last_error())
            # A chord already held when a hook starts must release before use.
            # Seed physical sides, never a generic modifier that side-specific
            # low-level releases cannot clear.
            for code, names in code_names.items():
                if code in (16, 17, 18):
                    continue
                if user.GetAsyncKeyState(code) < 0:
                    with self._lock:
                        self._blocked_raw.update(names)
            self.status = "keyboard shortcuts listening"
            while not self._stop.is_set():
                result = user.GetMessageW(ctypes.byref(message), None, 0, 0)
                if result <= 0:
                    break
                user.TranslateMessage(ctypes.byref(message))
                user.DispatchMessageW(ctypes.byref(message))
        except Exception as exc:
            self.status = f"Keyboard shortcut unavailable: {type(exc).__name__}: {exc}"
        finally:
            if hook:
                user.UnhookWindowsHookEx(hook)
            with self._lock:
                self._pressed.clear()
                self._raw_pressed.clear()
                self._blocked_raw.clear()
            self._thread_id = None

    def stop(self):
        self._stop.set()
        if self._thread_id and os.name == "nt":
            ctypes.WinDLL("user32").PostThreadMessageW(self._thread_id, 0x12, 0, 0)
        if self._thread:
            self._thread.join(timeout=.3)
        with self._lock:
            self._pressed.clear()
            self._raw_pressed.clear()
            self._events.clear()
            self._blocked_raw.clear()


class ShortcutEdges:
    def __init__(self):
        self._held = set()

    def update(self, chords, keys, buttons):
        """Return union of target IDs triggered once, until each chord releases."""
        active = set()
        fired = set()
        for target, chord_keys, chord_buttons in chords:
            if not chord_keys and not chord_buttons:
                continue
            if set(chord_keys) <= set(keys) and set(chord_buttons) <= set(buttons):
                identity = (target, tuple(chord_keys), tuple(chord_buttons))
                active.add(identity)
                if identity not in self._held:
                    fired.add(target)
        self._held = active
        return fired
