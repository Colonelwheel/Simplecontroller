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

FACE_INPUT_TAG = 0x46474933
KEY_NAMES = frozenset(tuple("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
    + tuple("F" + str(i) for i in range(1, 25))
    + ("SPACE", "ENTER", "TAB", "ESC", "BACKSPACE", "UP", "DOWN", "LEFT", "RIGHT",
       "SHIFT", "CTRL", "ALT", "WIN", "LSHIFT", "RSHIFT", "LCTRL", "RCTRL",
       "LALT", "RALT", "LWIN", "RWIN", "HOME", "END", "PAGEUP", "PAGEDOWN",
       "INSERT", "DELETE", "CAPSLOCK"))
KEY_INPUTS = frozenset("KEY:" + key for key in KEY_NAMES)

DESKTOP_INPUTS = frozenset(("MOUSE_LEFT", "MOUSE_RIGHT", "SPACE"))
BUTTONS = frozenset(("A", "B", "X", "Y", "LB", "RB", "START", "BACK",
                     "UP", "DOWN", "LEFT", "RIGHT", "LS", "RS", "LT", "RT")) | DESKTOP_INPUTS
V3_OUTPUTS = BUTTONS | KEY_INPUTS
CHANNELS = ("mouth", "brows", "wink", "movement", "nose_x", "nose_y", "lip_x",
            "expression_mouth", "expression_brows")
MOVEMENT_CHANNELS = frozenset(CHANNELS[3:])


def canonical_phone_key(key):
    """Only individual approved keys; compound legacy strings stay legacy."""
    if not isinstance(key, str):
        return None
    aliases = {"spacebar": "SPACE", "space bar": "SPACE", " ": "SPACE",
               "escape": "ESC", "control": "CTRL", "windows": "WIN",
               "left shift": "LSHIFT", "right shift": "RSHIFT",
               "left ctrl": "LCTRL", "right ctrl": "RCTRL",
               "left control": "LCTRL", "right control": "RCTRL",
               "left alt": "LALT", "right alt": "RALT",
               "left windows": "LWIN", "right windows": "RWIN",
               "page up": "PAGEUP", "page down": "PAGEDOWN", "caps lock": "CAPSLOCK"}
    name = aliases.get(key.lower(), key.upper())
    return ("SPACE" if name == "SPACE" else "KEY:" + name) if name in KEY_NAMES else None


def keyboard_name(target):
    name = target.removeprefix("KEY:")
    return {"ESC": "esc", "CTRL": "ctrl", "WIN": "windows",
        "LSHIFT": "left shift", "RSHIFT": "right shift", "LCTRL": "left ctrl",
        "RCTRL": "right ctrl", "LALT": "left alt", "RALT": "right alt",
        "LWIN": "left windows", "RWIN": "right windows", "PAGEUP": "page up",
        "PAGEDOWN": "page down", "CAPSLOCK": "caps lock"}.get(name, name.lower())


def desktop_target(target):
    return {"KEY:SPACE": "SPACE", "KEY:SHIFT": "KEY:LSHIFT", "KEY:CTRL": "KEY:LCTRL",
            "KEY:ALT": "KEY:LALT", "KEY:WIN": "KEY:LWIN"}.get(target, target)


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

    def __init__(self, write, lock, face_write=None):
        self.write = write
        self.face_write = face_write or write
        self.lock = lock
        self.phone = set()
        self.targets = DESKTOP_INPUTS | {desktop_target(target) for target in KEY_INPUTS}
        self.face = {target: set() for target in self.targets}
        self.tagged = set()
        self.phone_changed = None
        # Last callback result, not a poll of unrelated physical Windows input.
        # None means a callback failed and the injected state is uncertain.
        self.emitted = {target: False for target in self.targets}

    def _write(self, target, pressed, facial=False, write_target=None):
        self.emitted[target] = None
        (self.face_write if facial and target in self.tagged else self.write)(write_target or target, pressed)
        self.emitted[target] = pressed

    def phone_set(self, target, pressed):
        with self.lock:
            write_target = target
            target = desktop_target(target)
            if target not in self.targets:
                raise ValueError("unsupported desktop input")
            if pressed:
                self.phone.add(target)
            else:
                self.phone.discard(target)
            if self.phone_changed is not None:
                self.phone_changed()
            # Repeated phone DOWN/UP retains legacy behavior without face;
            # while face holds, neither source may insert an output release.
            if not self.face[target]:
                self._write(target, pressed, write_target=write_target)

    def face_set(self, player, target, pressed):
        with self.lock:
            if target in KEY_INPUTS:
                self.tagged.add(desktop_target(target))
            elif pressed and not self.face.get(target):
                self.tagged.discard(target)
            target = desktop_target(target)
            if target not in self.targets:
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
                self._write(target, after, facial=True)

    def face_clear(self, player):
        with self.lock:
            failure = None
            for target in self.targets:
                try:
                    self.face_set(player, target, False)
                except Exception as exc:
                    if failure is None:
                        failure = exc
            if failure is not None:
                raise failure

    def phone_tap(self, target):
        with self.lock:
            write_target = target
            target = desktop_target(target)
            if target not in self.targets:
                raise ValueError("unsupported desktop input")
            retained = target in self.phone
            self.phone.add(target)
            if self.phone_changed is not None:
                self.phone_changed()
            # A bare phone tap is transient and must not consume an existing
            # KEY_DOWN hold. Preserve its ordinary DOWN/UP events without face.
            if self.face[target]:
                if not retained:
                    self.phone.discard(target)
                    if self.phone_changed is not None:
                        self.phone_changed()
                return
            try:
                self._write(target, True, write_target=write_target)
                self._write(target, False, write_target=write_target)
            except Exception:
                # Retain ownership for explicit cleanup after a partial write.
                self.phone.add(target)
                raise
            if not retained:
                self.phone.discard(target)
                if self.phone_changed is not None:
                    self.phone_changed()

    def resync_phone(self, target):
        with self.lock:
            write_target = target
            target = desktop_target(target)
            if target not in self.targets:
                raise ValueError("unsupported desktop input")
            # Caller checked the OS state and the phone's KEY_DOWN tracker.
            self.phone.add(target)
            self._write(target, True, write_target=write_target)

    def clear_unowned(self, target):
        with self.lock:
            target = desktop_target(target)
            if target not in self.phone and not self.face[target] and self.emitted[target] is not False:
                # Explicit cleanup retries an uncertain/known injected DOWN,
                # including after the facial session/player has gone away.
                self._write(target, False, facial=True)


class MergedGamepad:
    """Proxy phone writes; face and voice independently own buttons/triggers.

    Every native mutation and report update shares the receiver's output RLock.
    The receiver additionally wraps multi-call phone transitions in that lock.
    """

    _OWNED_ATTRIBUTES = frozenset(("raw", "button_map", "lock", "phone_buttons",
                                   "face_buttons", "voice_buttons", "phone_triggers",
                                   "face_triggers", "voice_triggers", "desktop", "desktop_player",
                                   "phone_left", "face_left", "left_changed", "phone_left_changed",
                                   "phone_inputs_changed"))

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
        self.phone_left = (0.0, 0.0)
        self.face_left = (0.0, 0.0)
        self.left_changed = None
        self.phone_left_changed = None
        self.phone_inputs_changed = None

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
        if source == "phone" and self.phone_inputs_changed is not None:
            self.phone_inputs_changed()
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
        if source == "phone" and self.phone_inputs_changed is not None:
            self.phone_inputs_changed()
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
            previous = self.phone_left
            self.phone_left = (x_value_float, y_value_float)
            if self.phone_left_changed is not None and previous != self.phone_left:
                self.phone_left_changed(self.phone_left)
            self._left_output()

    def _left_output(self):
        x, y = self.phone_left if self.phone_left != (0.0, 0.0) else self.face_left
        self.raw.left_joystick_float(x_value_float=x, y_value_float=y)
        if self.left_changed is not None:
            self.left_changed(x, y)

    def face_vector(self, vector):
        with self.lock:
            self.face_left = tuple(vector)
            self._left_output()
            self.raw.update()

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
            self.phone_left = self.face_left = (0.0, 0.0)
            self.raw.reset()

    def face_set(self, button, pressed):
        with self.lock:
            if button in DESKTOP_INPUTS or button in KEY_INPUTS:
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
            if self.face_left != (0.0, 0.0):
                try:
                    self.face_vector((0.0, 0.0))
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
        self.notifications = deque(maxlen=16)
        self.movement_channels = {name: self._new_movement_channel() for name in CHANNELS}
        self.watched_keys = set()
        self.watched_buttons = set()
        self.source_revision = 0
        self.source_last = None
        self.source_overflow = False
        for player, pad in self.pads.items():
            if isinstance(pad, MergedGamepad):
                pad.phone_left_changed = lambda vector, player=player: self._phone_left_changed(player, vector)
                pad.phone_inputs_changed = self._source_notice
                if pad.desktop is not None:
                    pad.desktop.phone_changed = self._source_notice

    @staticmethod
    def _new_movement_channel():
        return {"armed": False, "held": set(), "taps": {}, "seen_taps": deque(maxlen=256),
                "lease": None, "frame_seq": -1, "captured_at": -1.0, "vector": (0.0, 0.0)}

    def _clear(self):
        self.deadlines.clear()
        self.binding = None
        for channel in self.channels.values():
            channel["button"] = channel["deadline"] = None
        for channel in self.movement_channels.values():
            channel.update(held=set(), taps={}, lease=None, vector=(0.0, 0.0))
        if self.player is not None:
            self.pads[self.player].face_clear()

    def _disarm(self):
        self.armed = False
        for channel in self.channels.values():
            channel["armed"] = False
        for channel in self.movement_channels.values():
            channel["armed"] = False

    def _notice(self, op):
        notice = {"v": self.version, "op": op, "epoch": self.epoch}
        if self.player is not None and getattr(self.pads[self.player], "desktop", None) is not None:
            notice["desktop_input"] = True
        if self.version == 3:
            notice["movement_input"] = True
            notice["retained_nose_movement"] = True
        return notice

    def _v3_outputs(self):
        result = set()
        for channel in self.movement_channels.values():
            result.update(channel["held"])
            for targets, deadline in channel["taps"].values():
                result.update(targets)
        return result

    def _reconcile_v3(self, previous):
        current = self._v3_outputs()
        failure = None
        for target, pressed in [(target, False) for target in previous - current] + [
                (target, True) for target in current - previous]:
            try:
                self.pads[self.player].face_set(target, pressed)
            except Exception as exc:
                failure = failure or exc
        try:
            vector = self.movement_channels["movement"]["vector"]
            if self.pads[self.player].face_left != vector:
                self.pads[self.player].face_vector(vector)
        except Exception as exc:
            failure = failure or exc
        if failure is not None:
            raise failure

    def _clear_v3_channels(self, names):
        previous = self._v3_outputs()
        for name in names:
            self.movement_channels[name].update(armed=False, held=set(), taps={},
                                               lease=None, vector=(0.0, 0.0))
        self._reconcile_v3(previous)

    def _phone_left_changed(self, player, vector):
        with self.lock:
            if self.version == 3 and self.session is not None and player == self.player:
                if vector != (0.0, 0.0):
                    was_armed = any(self.movement_channels[name]["armed"] for name in MOVEMENT_CHANNELS)
                    self._clear_v3_channels(MOVEMENT_CHANNELS)
                    if was_armed:
                        self.notifications.append({"v": 3, "op": "takeover", "epoch": self.epoch,
                                                   "channel": "movement", "channels": sorted(MOVEMENT_CHANNELS)})
                self._source_notice()

    def _source_notice(self, force=False):
        if self.version != 3 or self.player is None or (not force and self.source_last is None):
            return None
        pad = self.pads[self.player]
        desktop = getattr(pad, "desktop", None)
        keys = tuple(sorted(key for key in self.watched_keys
                     if desktop is not None and desktop_target(key) in desktop.phone))
        buttons = {name for name, value in pad.button_map.items()
                   if value in pad.phone_buttons and name in self.watched_buttons}
        buttons.update(name for name in ("LT", "RT")
                       if name in self.watched_buttons and pad.phone_triggers[name] >= .5)
        vector = tuple(pad.phone_left)
        view = (keys, tuple(sorted(buttons)), vector)
        if not force and view == self.source_last:
            return None
        self.source_last = view
        self.source_revision += 1
        result = {"v": 3, "op": "source", "epoch": self.epoch, "revision": self.source_revision,
                  "keys": list(keys), "buttons": sorted(buttons), "phone_stick": list(vector),
                  "stick_blocked": vector != (0.0, 0.0)}
        # Configured shortcut transitions must not lose a bare DOWN/UP edge.
        # Bound the queue and fail closed rather than silently erase events.
        if len(self.notifications) >= 16:
            # Do not throw into the phone output handler: optional shortcut
            # observation must not prevent its original native action.
            self.source_overflow = True
            return None
        self.notifications.append(result)
        return result

    @staticmethod
    def _v3_targets(value, maximum=96, normalize=True):
        if not isinstance(value, list) or len(value) > maximum or any(
                not isinstance(target, str) or target not in V3_OUTPUTS for target in value):
            raise ProtocolError("invalid output targets")
        # SPACE aliases share one owner; otherwise old SPACE + KEY:SPACE could
        # create a release gap when a snapshot changes spelling.
        return {"KEY:SPACE" if desktop_target(target) == "SPACE" else desktop_target(target)
                for target in value} if normalize else set(value)

    def _handle_v3(self, op, message):
        retain = message.get("retain_on_tracking_loss", False)
        if "retain_on_tracking_loss" in message and (type(retain) is not bool
                or op != "sample" or message.get("channel") != "movement"):
            raise ProtocolError("invalid retained nose movement")
        axes = message.get("axes")
        if "axes" in message and (op != "clear" or message.get("channel") != "movement"
                or not isinstance(axes, list) or not 1 <= len(axes) <= 2
                or any(axis not in ("x", "y") for axis in axes) or len(set(axes)) != len(axes)):
            raise ProtocolError("invalid scoped movement axes")
        if op == "heartbeat":
            self.last_heartbeat = self.clock()
            self._source_notice()
            return None
        if op == "watch":
            keys = self._v3_targets(message.get("keys", []), 96, normalize=False)
            buttons = self._v3_targets(message.get("buttons", []), 16, normalize=False)
            if any(key not in KEY_INPUTS for key in keys) or any(
                    button not in BUTTONS - DESKTOP_INPUTS for button in buttons):
                raise ProtocolError("invalid shortcut source targets")
            self.watched_keys, self.watched_buttons = keys, buttons
            self._source_notice(force=True)
            return None
        name = message.get("channel")
        if op == "clear" and name is None:
            self._disarm()
            self._clear()
            return None
        if name not in CHANNELS:
            raise ProtocolError("unsupported channel")
        channel = self.movement_channels[name]
        if op == "clear":
            if axes is not None:
                previous = self._v3_outputs()
                vector = list(channel["vector"])
                remove = set()
                if "x" in axes:
                    vector[0] = 0.0
                    remove.update(("KEY:A", "KEY:D"))
                if "y" in axes:
                    vector[1] = 0.0
                    remove.update(("KEY:W", "KEY:S"))
                channel["vector"] = tuple(vector)
                channel["held"].difference_update(remove)
                channel["taps"] = {action_id: (targets - remove, deadline)
                    for action_id, (targets, deadline) in channel["taps"].items()
                    if targets - remove}
                # Preserve the other component's original lease and arm. The
                # reset is not a fabricated fresh camera sample.
                self._reconcile_v3(previous)
                return None
            self._clear_v3_channels((name,))
            return None
        if op == "arm":
            if name in MOVEMENT_CHANNELS and self.pads[self.player].phone_left != (0.0, 0.0):
                self._source_notice(force=True)
                return None
            channel["armed"] = True
            return None
        if op != "sample":
            raise ProtocolError("unsupported v3 operation")
        frame_seq, captured_at = message.get("frame_seq"), message.get("captured_at")
        if type(frame_seq) is not int or frame_seq < 0 or frame_seq <= channel["frame_seq"]:
            raise ProtocolError("stale capture sequence")
        if type(captured_at) not in (int, float) or not math.isfinite(captured_at):
            raise ProtocolError("invalid capture timestamp")
        now = self.clock()
        if captured_at <= channel["captured_at"] or captured_at > now:
            raise ProtocolError("invalid capture ordering")
        held = self._v3_targets(message.get("held", []))
        taps = message.get("taps", [])
        if not isinstance(taps, list) or len(taps) > 8:
            raise ProtocolError("too many tap actions")
        parsed_taps = []
        tap_ids = set()
        for tap in taps:
            if not isinstance(tap, dict) or not isinstance(tap.get("id"), str) or not (
                    1 <= len(tap["id"]) <= 64) or not tap["id"].isascii():
                raise ProtocolError("invalid tap id")
            if tap["id"] in tap_ids:
                raise ProtocolError("duplicate tap id")
            tap_ids.add(tap["id"])
            parsed_taps.append((tap["id"], self._v3_targets(tap.get("targets"), 16)))
        vector = message.get("vector", [0.0, 0.0])
        if "vector" in message and name != "movement":
            raise ProtocolError("only movement owns stick vector")
        if not isinstance(vector, list) or len(vector) != 2 or any(
                type(v) not in (int, float) or not math.isfinite(v) or abs(v) > 1 for v in vector):
            raise ProtocolError("invalid stick vector")
        if math.hypot(*vector) > 1.000001:
            raise ProtocolError("stick vector exceeds circular full tilt")
        # Only the nose movement vector and its WASD route may outlive capture.
        # Boost/button ownership remains on separate 250 ms capture leases.
        if retain and (held - {"KEY:W", "KEY:A", "KEY:S", "KEY:D"} or parsed_taps):
            raise ProtocolError("retained nose movement accepts only WASD holds and no taps")
        if getattr(self.pads[self.player], "desktop", None) is None and any(
                target in KEY_INPUTS or target in DESKTOP_INPUTS
                for target in held | {target for _, targets in parsed_taps for target in targets}):
            raise ProtocolError("desktop input unavailable")
        previous = self._v3_outputs()
        channel["frame_seq"], channel["captured_at"] = frame_seq, float(captured_at)
        if now - captured_at >= .250:
            channel.update(armed=False, held=set(), taps={}, lease=None, vector=(0.0, 0.0))
            self.notifications.append({"v": 3, "op": "expired", "epoch": self.epoch,
                                       "channels": [name]})
        elif channel["armed"]:
            channel["held"] = held
            channel["lease"] = None if retain else captured_at + .250
            channel["vector"] = tuple(vector)
            for action_id, targets in parsed_taps:
                if action_id not in channel["seen_taps"]:
                    channel["seen_taps"].append(action_id)
                    channel["taps"][action_id] = (targets, now + .100)
            if len(channel["taps"]) > 32:
                raise ProtocolError("too many outstanding taps")
        self._reconcile_v3(previous)
        return None

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
                self.source_last = None
                self.source_overflow = False
                self.watched_keys.clear()
                self.watched_buttons.clear()

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
            if not isinstance(message, dict) or type(message.get("v")) is not int or message["v"] not in (1, 2, 3):
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
                self.movement_channels = {name: self._new_movement_channel() for name in CHANNELS}
                self.source_last = None
                self.source_overflow = False
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
            if self.version == 3:
                result = self._handle_v3(op, message)
                self.seq = seq
                return result
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
            if self.source_overflow:
                self.disconnect()
                return True
            if now - self.last_heartbeat >= 0.5:
                self.disconnect()
                return True
            if self.version == 3:
                previous = self._v3_outputs()
                expired = []
                for name, channel in self.movement_channels.items():
                    if channel["lease"] is not None and now >= channel["lease"]:
                        expired.append(name)
                        channel.update(armed=False, held=set(), taps={}, lease=None, vector=(0.0, 0.0))
                    else:
                        for action_id, (_, deadline) in tuple(channel["taps"].items()):
                            if now >= deadline:
                                channel["taps"].pop(action_id, None)
                self._reconcile_v3(previous)
                if expired:
                    self.notifications.append({"v": 3, "op": "expired", "epoch": self.epoch,
                                               "channels": expired})
                self._source_notice()
            elif self.version == 2:
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
