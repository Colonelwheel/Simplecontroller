"""Local facial input protocol and source-aware virtual-pad ownership.

Standard library only. This module creates no virtual controller and imports no
native input libraries, so its protocol/ownership can be tested without hardware.
"""

from collections import deque
import functools
import json
import math
import select
import socket
import threading
import time
import uuid

DESKTOP_INPUTS = frozenset(("MOUSE_LEFT", "MOUSE_RIGHT", "SPACE"))
BUTTONS = frozenset(("A", "B", "X", "Y", "LB", "RB", "START", "BACK",
                     "UP", "DOWN", "LEFT", "RIGHT", "LS", "RS", "LT", "RT")) | DESKTOP_INPUTS


def synchronized(lock):
    def decorate(fn):
        @functools.wraps(fn)
        def wrapped(*args, **kwargs):
            with lock:
                return fn(*args, **kwargs)
        return wrapped
    return decorate


class MergedDesktopInput:
    """Global Windows outputs: preserve phone events while face is inactive.

    The receiver supplies a write(target, pressed) callback and its existing
    shared lock. No native input dependency is imported here. Phone retains
    its existing single global mouse stream and Player 1 keyboard rule.
    """

    def __init__(self, write, lock):
        self.write = write
        self.lock = lock
        self.phone = set()
        self.face = {target: set() for target in DESKTOP_INPUTS}
        # Last callback result, not a poll of unrelated physical Windows input.
        # None means a callback failed and the injected state is uncertain.
        self.emitted = {target: False for target in DESKTOP_INPUTS}

    def _write(self, target, pressed):
        self.emitted[target] = None
        self.write(target, pressed)
        self.emitted[target] = pressed

    def phone_set(self, target, pressed):
        with self.lock:
            if target not in DESKTOP_INPUTS:
                raise ValueError("unsupported desktop input")
            if pressed:
                self.phone.add(target)
            else:
                self.phone.discard(target)
            # Repeated phone DOWN/UP retains legacy behavior without face;
            # while face holds, neither source may insert an output release.
            if not self.face[target]:
                self._write(target, pressed)

    def face_set(self, player, target, pressed):
        with self.lock:
            if target not in DESKTOP_INPUTS:
                raise ValueError("unsupported desktop input")
            owners = self.face[target]
            before = target in self.phone or bool(owners)
            if pressed:
                owners.add(player)
            else:
                owners.discard(player)
            after = target in self.phone or bool(owners)
            if (before != after or pressed and self.emitted[target] is not True
                    or not after and self.emitted[target] is None):
                # A legacy bare phone tap may have emitted UP while its
                # persistent KEY_DOWN tracker still owns Space. A new facial
                # hold must restore DOWN immediately, without waiting for resync.
                self._write(target, after)

    def face_clear(self, player):
        with self.lock:
            failure = None
            for target in DESKTOP_INPUTS:
                try:
                    self.face_set(player, target, False)
                except Exception as exc:
                    if failure is None:
                        failure = exc
            if failure is not None:
                raise failure

    def phone_tap(self, target):
        with self.lock:
            if target not in DESKTOP_INPUTS:
                raise ValueError("unsupported desktop input")
            # A bare phone tap is transient and must not consume an existing
            # KEY_DOWN hold. Preserve its ordinary DOWN/UP events without face.
            if self.face[target]:
                return
            try:
                self._write(target, True)
                self._write(target, False)
            except Exception:
                # Retain ownership for explicit cleanup after a partial write.
                self.phone.add(target)
                raise

    def resync_phone(self, target):
        with self.lock:
            if target not in DESKTOP_INPUTS:
                raise ValueError("unsupported desktop input")
            # Caller checked the OS state and the phone's KEY_DOWN tracker.
            self.phone.add(target)
            self._write(target, True)

    def clear_unowned(self, target):
        with self.lock:
            if target not in self.phone and not self.face[target] and self.emitted[target] is not False:
                # Explicit cleanup retries an uncertain/known injected DOWN,
                # including after the facial session/player has gone away.
                self._write(target, False)


class MergedGamepad:
    """Proxy phone writes; face and voice independently own buttons/triggers.

    Every native mutation and report update shares the receiver's output RLock.
    The receiver additionally wraps multi-call phone transitions in that lock.
    """

    _OWNED_ATTRIBUTES = frozenset(("raw", "button_map", "lock", "phone_buttons",
                                   "face_buttons", "voice_buttons", "phone_triggers",
                                   "face_triggers", "voice_triggers", "desktop", "desktop_player"))

    def __setattr__(self, name, value):
        # Preserve existing pad attribute writes (also used by the legacy mock
        # harness) while keeping ownership bookkeeping on this proxy.
        raw = self.__dict__.get("raw")
        if name not in self._OWNED_ATTRIBUTES and raw is not None and hasattr(raw, name):
            with self.lock:
                setattr(raw, name, value)
        else:
            object.__setattr__(self, name, value)

    def __init__(self, raw, button_map, lock, desktop=None, player=None):
        self.raw = raw
        self.button_map = button_map
        self.lock = lock
        self.phone_buttons = set()
        self.face_buttons = set()
        self.voice_buttons = set()
        self.phone_triggers = {"LT": 0.0, "RT": 0.0}
        self.face_triggers = {"LT": 0.0, "RT": 0.0}
        self.voice_triggers = {"LT": 0.0, "RT": 0.0}
        self.desktop = desktop
        self.desktop_player = player

    def __getattr__(self, name):
        return getattr(self.raw, name)

    def _button(self, button, pressed, source):
        owners = getattr(self, source + "_buttons")
        before = (button in self.phone_buttons or button in self.face_buttons
                  or button in self.voice_buttons)
        if pressed:
            owners.add(button)
        else:
            owners.discard(button)
        after = (button in self.phone_buttons or button in self.face_buttons
                 or button in self.voice_buttons)
        if before != after:
            method = self.raw.press_button if after else self.raw.release_button
            method(button=button)

    def press_button(self, button):
        with self.lock:
            self._button(button, True, "phone")

    def release_button(self, button):
        with self.lock:
            self._button(button, False, "phone")

    def _trigger(self, button, value, source):
        if not math.isfinite(value):
            value = 0.0
        owners = getattr(self, source + "_triggers")
        owners[button] = max(0.0, min(1.0, value))
        merged = max(self.phone_triggers[button], self.face_triggers[button],
                     self.voice_triggers[button])
        method = self.raw.left_trigger_float if button == "LT" else self.raw.right_trigger_float
        method(value_float=merged)

    def left_trigger_float(self, value_float):
        with self.lock:
            self._trigger("LT", value_float, "phone")

    def right_trigger_float(self, value_float):
        with self.lock:
            self._trigger("RT", value_float, "phone")

    def left_joystick_float(self, x_value_float, y_value_float):
        with self.lock:
            self.raw.left_joystick_float(x_value_float=x_value_float, y_value_float=y_value_float)

    def right_joystick_float(self, x_value_float, y_value_float):
        with self.lock:
            self.raw.right_joystick_float(x_value_float=x_value_float, y_value_float=y_value_float)

    def update(self):
        with self.lock:
            self.raw.update()

    def reset(self):
        with self.lock:
            self.phone_buttons.clear()
            self.face_buttons.clear()
            self.voice_buttons.clear()
            self.phone_triggers = {"LT": 0.0, "RT": 0.0}
            self.face_triggers = {"LT": 0.0, "RT": 0.0}
            self.voice_triggers = {"LT": 0.0, "RT": 0.0}
            self.raw.reset()

    def face_set(self, button, pressed):
        with self.lock:
            if button in DESKTOP_INPUTS:
                if self.desktop is None:
                    raise ProtocolError("desktop input unavailable")
                self.desktop.face_set(self.desktop_player, button, pressed)
                return
            if button in ("LT", "RT"):
                self._trigger(button, 1.0 if pressed else 0.0, "face")
            else:
                self._button(self.button_map[button], pressed, "face")
            self.raw.update()

    def face_clear(self):
        with self.lock:
            failure = None
            if self.desktop is not None:
                try:
                    self.desktop.face_clear(self.desktop_player)
                except Exception as exc:
                    failure = exc
            for button in tuple(self.face_buttons):
                try:
                    self._button(button, False, "face")
                except Exception as exc:
                    if failure is None:
                        failure = exc
            for button in ("LT", "RT"):
                if self.face_triggers[button]:
                    try:
                        self._trigger(button, 0.0, "face")
                    except Exception as exc:
                        if failure is None:
                            failure = exc
            self.raw.update()
            if failure is not None:
                raise failure

    def voice_set(self, button, value):
        with self.lock:
            if button in ("LT", "RT"):
                self._trigger(button, float(value), "voice")
            else:
                self._button(self.button_map[button], bool(value), "voice")
            self.raw.update()

    def voice_clear(self):
        with self.lock:
            for button in tuple(self.voice_buttons):
                self._button(button, False, "voice")
            for button in ("LT", "RT"):
                if self.voice_triggers[button]:
                    self._trigger(button, 0.0, "voice")
            self.raw.update()


class ProtocolError(ValueError):
    pass


class FaceInputState:
    """One session, v1 single/v2 dual bindings, and heartbeat fail-safe."""

    EXPRESSION_BINDINGS = ("mouth", "brows")

    def __init__(self, pads, lock, clock=time.monotonic):
        self.pads = pads
        self.lock = lock
        self.clock = clock
        self.epoch = 0
        self.session = None
        self.player = None
        self.armed = False
        self.seq = -1
        self.last_heartbeat = 0.0
        self.deadlines = {}
        self.binding = None
        self.version = 1
        self.channels = {name: {"armed": False, "button": None, "deadline": None}
                         for name in self.EXPRESSION_BINDINGS}
        self.notifications = deque(maxlen=4)

    def _clear(self):
        self.deadlines.clear()
        self.binding = None
        for channel in self.channels.values():
            channel["button"] = channel["deadline"] = None
        if self.player is not None:
            self.pads[self.player].face_clear()

    def _disarm(self):
        self.armed = False
        for channel in self.channels.values():
            channel["armed"] = False

    def _notice(self, op):
        notice = {"v": self.version, "op": op, "epoch": self.epoch}
        if self.player is not None and getattr(self.pads[self.player], "desktop", None) is not None:
            notice["desktop_input"] = True
        return notice

    def _outputs(self):
        return {channel["button"] for channel in self.channels.values()
                if channel["button"] is not None}

    def _reconcile(self, previous):
        """Change aggregate face ownership without gaps in a shared button."""
        current = self._outputs()
        for button in previous - current:
            self.pads[self.player].face_set(button, False)
        for button in current - previous:
            self.pads[self.player].face_set(button, True)

    def _handle_dual(self, op, binding, button):
        if op == "clear" and binding is None:
            self._disarm()
            self._clear()
            return
        channel = self.channels[binding]
        previous = self._outputs()
        if op == "arm":
            channel["armed"] = True
        elif op == "clear":
            channel.update(armed=False, button=None, deadline=None)
        elif op == "release":
            if channel["button"] == button:
                channel["button"] = channel["deadline"] = None
        elif channel["armed"]:
            channel["button"] = button
            channel["deadline"] = self.clock() + 0.1 if op == "tap" else None
        self._reconcile(previous)

    def disconnect(self):
        with self.lock:
            self._disarm()
            try:
                self._clear()
            finally:
                self.session = self.player = None
                self.notifications.clear()

    def reset(self, player_ids):
        """Called inside the receiver's atomic global release transition."""
        with self.lock:
            if self.player not in player_ids:
                return
            self._disarm()
            self.epoch += 1
            self.notifications.clear()
            self.notifications.append(self._notice("reset"))
            self._clear()

    def handle(self, message):
        with self.lock:
            if not isinstance(message, dict) or type(message.get("v")) is not int or message["v"] not in (1, 2):
                raise ProtocolError("unsupported protocol")
            if self.session is not None and message["v"] != self.version:
                raise ProtocolError("session protocol changed")
            op = message.get("op")
            if op == "hello":
                if self.session is not None:
                    raise ProtocolError("session already established")
                try:
                    session = str(uuid.UUID(message.get("session", "")))
                except (ValueError, TypeError, AttributeError):
                    raise ProtocolError("invalid session") from None
                player = message.get("player", "player1")
                if player not in self.pads:
                    raise ProtocolError("invalid player")
                self.epoch += 1
                self.session, self.player = session, player
                self.version = message["v"]
                self.seq = -1
                self._disarm()
                self.last_heartbeat = self.clock()
                return self._notice("ready")
            if self.session is None:
                raise ProtocolError("hello required")
            if self.clock() - self.last_heartbeat >= 0.5:
                self.disconnect()
                raise ProtocolError("heartbeat timeout")
            epoch, seq = message.get("epoch"), message.get("seq")
            if type(epoch) is not int or epoch != self.epoch:
                # Never execute a delayed action from the preceding reset epoch.
                return self._notice("reset")
            if type(seq) is not int or seq < 0 or seq <= self.seq:
                raise ProtocolError("stale sequence")
            if op not in ("arm", "heartbeat", "tap", "hold", "release", "clear"):
                raise ProtocolError("unsupported operation")
            button = message.get("button")
            if op in ("tap", "hold", "release") and button not in BUTTONS:
                raise ProtocolError("unsupported button")
            if button in DESKTOP_INPUTS and getattr(self.pads[self.player], "desktop", None) is None:
                raise ProtocolError("desktop input unavailable")
            if op == "tap" and (type(message.get("duration_ms")) is not int or message["duration_ms"] != 100):
                raise ProtocolError("tap duration must be 100 ms")
            binding = message.get("binding")
            if self.version == 2:
                if op in ("arm", "tap", "hold", "release") or "binding" in message:
                    if binding not in self.EXPRESSION_BINDINGS:
                        raise ProtocolError("unsupported expression binding")
                if op == "heartbeat" and "binding" in message:
                    raise ProtocolError("heartbeat must be unscoped")
            self.seq = seq
            if op == "heartbeat":
                self.last_heartbeat = self.clock()
            elif self.version == 2:
                self._handle_dual(op, binding, button)
            elif op == "arm":
                self.armed = True
            elif op == "clear":
                self.armed = False
                self._clear()
            elif op == "release":
                self.deadlines.pop(button, None)
                self.pads[self.player].face_set(button, False)
                if self.binding == button:
                    self.binding = None
            elif self.armed:
                # One active binding: changing bindings cannot leave old ownership.
                if self.binding != button:
                    self._clear()
                self.deadlines.pop(button, None)
                self.binding = button
                self.pads[self.player].face_set(button, True)
                if op == "tap":
                    self.deadlines[button] = self.clock() + 0.1
            return None

    def tick(self):
        """Return True when the socket must close for heartbeat timeout."""
        with self.lock:
            now = self.clock()
            if self.session is None:
                return False
            if now - self.last_heartbeat >= 0.5:
                self.disconnect()
                return True
            if self.version == 2:
                previous = self._outputs()
                for channel in self.channels.values():
                    if channel["deadline"] is not None and now >= channel["deadline"]:
                        channel["button"] = channel["deadline"] = None
                self._reconcile(previous)
            else:
                for button, deadline in tuple(self.deadlines.items()):
                    if now >= deadline:
                        self.deadlines.pop(button, None)
                        self.pads[self.player].face_set(button, False)
            return False

    def drain_notifications(self):
        with self.lock:
            result = list(self.notifications)
            self.notifications.clear()
            return result


class FaceInputServer:
    """Bounded, nonblocking loopback TCP server; no phone parser involvement."""

    def __init__(self, state, port=42735, logger=None):
        self.state = state
        self.port = port
        self.logger = logger
        self.stop_event = threading.Event()
        self.listener = None
        self.thread = None

    def start(self):
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        # Windows exclusive bind prevents an unrelated listener sharing the port.
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        try:
            listener.bind(("127.0.0.1", self.port))
            listener.listen(2)
            listener.setblocking(False)
        except Exception:
            listener.close()
            raise
        self.listener = listener
        self.port = listener.getsockname()[1]
        self.thread = threading.Thread(target=self._run, name="FacialInputTCP", daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread is not None:
            self.thread.join(timeout=1.0)

    def _run(self):
        client = None
        incoming = bytearray()
        outgoing = bytearray()
        hello_deadline = 0.0

        def close_client():
            nonlocal client
            if client is not None:
                client.close()
                client = None
            incoming.clear()
            outgoing.clear()
            try:
                self.state.disconnect()
            except Exception as exc:
                if self.logger:
                    self.logger.warning("Facial input cleanup failed: %s", exc)

        def enqueue(message):
            outgoing.extend((json.dumps(message, separators=(",", ":")) + "\n").encode("utf-8"))
            if len(outgoing) > 4096:
                raise ProtocolError("slow consumer")

        try:
            while not self.stop_event.is_set():
                try:
                    if self.state.tick() or (client is not None and self.state.session is None and time.monotonic() >= hello_deadline):
                        close_client()
                    if client is not None:
                        for notice in self.state.drain_notifications():
                            enqueue(notice)
                    readable, writable, _ = select.select(
                        [self.listener] + ([client] if client else []),
                        [client] if client is not None and outgoing else [], [], 0.01)
                    if self.listener in readable:
                        candidate, address = self.listener.accept()
                        if client is not None or address[0] != "127.0.0.1":
                            candidate.close()
                        else:
                            candidate.setblocking(False)
                            candidate.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                            client = candidate
                            hello_deadline = time.monotonic() + 0.5
                    if client is not None and client in readable:
                        chunk = client.recv(4096)
                        if not chunk:
                            close_client()
                            continue
                        incoming.extend(chunk)
                        if len(incoming) > 8192:
                            raise ProtocolError("input buffer too large")
                    if client is not None:
                        # Per-iteration cap keeps a noisy local client from starving timeout checks.
                        for _ in range(8):
                            newline = incoming.find(b"\n")
                            if newline < 0:
                                if len(incoming) > 4096:
                                    raise ProtocolError("line too large")
                                break
                            if newline > 4096:
                                raise ProtocolError("line too large")
                            line = bytes(incoming[:newline])
                            del incoming[:newline + 1]
                            result = self.state.handle(json.loads(line.decode("utf-8")))
                            if result is not None:
                                enqueue(result)
                    if client is not None and client in writable and outgoing:
                        count = client.send(outgoing)
                        del outgoing[:count]
                except (OSError, ValueError, UnicodeError, TypeError, KeyError) as exc:
                    if self.logger:
                        self.logger.warning("Facial input disconnected: %s", exc)
                    close_client()
        finally:
            close_client()
            self.listener.close()
