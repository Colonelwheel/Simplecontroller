"""Bounded loopback control client; disconnect/reset never replays old actions."""
from __future__ import annotations

import json
import math
import queue
import socket
import threading
import time
from uuid import uuid4

from .settings import DESKTOP_INPUTS, OUTPUT_LABELS

class ReceiverClient:
    def __init__(self, player="player1", port=42735, simultaneous=False, require_desktop_input=False,
                 require_movement_input=False, require_retained_nose_movement=False):
        self.player, self.port = player, port
        self.simultaneous = simultaneous
        self.require_desktop_input = require_desktop_input
        self.require_movement_input = require_movement_input or require_retained_nose_movement
        self.require_retained_nose_movement = require_retained_nose_movement
        self._movement_input = False
        self._retained_nose_movement = False
        self._desktop_input = False
        self.protocol = 3 if self.require_movement_input else (2 if simultaneous else 1)
        self._lock = threading.RLock()
        self._out = queue.Queue(maxsize=32)
        self._stop = threading.Event()
        self._thread = None
        self._socket = None
        self._epoch = None
        self._seq = 0
        self._generation = 0
        self._status = "disconnected"
        self._armed = False
        self._armed_bindings = set()
        self._sent_press_count = 0
        self._sent_held = {}
        self._last_sent_output = ""
        self._supervisor_at = time.monotonic()
        self._source = {"keys": (), "buttons": (), "phone_stick": (0.0, 0.0), "stick_blocked": False}
        self._source_revision = -1
        self._source_events = []
        self._takeover_generation = 0
        self._expiry_generation = 0
        self._expired_channels = ()

    def pulse(self):
        """A dead supervisor cannot keep a facial hold alive through IO heartbeats."""
        with self._lock:
            self._supervisor_at = time.monotonic()

    def snapshot(self):
        with self._lock:
            return {"status": self._status, "ready": self._epoch is not None, "armed": self._armed, "generation": self._generation,
                    "armed_bindings": tuple(sorted(self._armed_bindings)), "protocol": self.protocol,
                    "desktop_input": self._desktop_input,
                    "movement_input": self._movement_input,
                    "retained_nose_movement": self._retained_nose_movement, "source": dict(self._source),
                    "source_revision": self._source_revision, "takeover_generation": self._takeover_generation,
                    "expiry_generation": self._expiry_generation, "expired_channels": self._expired_channels,
                    "sent_press_count": self._sent_press_count, "last_sent_output": self._last_sent_output}

    def start(self):
        self._thread = threading.Thread(target=self._run, name="facial-receiver", daemon=True)
        self._thread.start()

    def drain_source_events(self):
        with self._lock:
            events, self._source_events = self._source_events, []
            return events

    def drain_expired_channels(self):
        with self._lock:
            channels, self._expired_channels = self._expired_channels, ()
            return channels

    def send(self, op, **fields):
        with self._lock:
            for name, current in (("expected_generation", self._generation),
                                  ("expected_takeover_generation", self._takeover_generation),
                                  ("expected_expiry_generation", self._expiry_generation)):
                expected = fields.pop(name, None)
                if expected is not None and (type(expected) is not int or expected != current):
                    return False
            if self._epoch is None:
                return False
            if fields.get("button") in DESKTOP_INPUTS and not self._desktop_input:
                return False
            if self.protocol == 3:
                return self._send_v3(op, fields)
            if "retain_on_tracking_loss" in fields:
                return False
            binding = fields.get("binding")
            if self.simultaneous and op in ("arm", "tap", "hold", "release") and binding not in ("mouth", "brows"):
                return False
            if self.simultaneous and "binding" in fields and binding not in ("mouth", "brows"):
                return False
            if not self.simultaneous and "binding" in fields:
                return False
            armed = binding in self._armed_bindings if self.simultaneous else self._armed
            if op in ("tap", "hold") and not armed:
                return False
            if op == "clear":
                retained = []
                while True:
                    try:
                        pending = self._out.get_nowait()
                    except queue.Empty:
                        break
                    if self.simultaneous and binding is not None and pending.get("binding") != binding:
                        retained.append(pending)
                for pending in retained:
                    self._out.put_nowait(pending)
            self._seq += 1
            message = {"v": self.protocol, "op": op, "epoch": self._epoch, "seq": self._seq, **fields}
            try:
                self._out.put_nowait(message)
            except queue.Full:
                self._invalidate("output queue full")
                if self._socket is not None:
                    try:
                        self._socket.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                return False
            if op == "arm":
                if self.simultaneous:
                    self._armed_bindings.add(binding)
                self._armed = True
            if op == "clear":
                if binding is None:
                    self._armed_bindings.clear()
                else:
                    self._armed_bindings.discard(binding)
                self._armed = bool(self._armed_bindings) if self.simultaneous else False
            return True

    def _send_v3(self, op, fields):
        """Called under the same lock as legacy sends; no native work here."""
        if not self._retention_allowed(op, fields):
            return False
        channel = fields.get("channel")
        channels = {"mouth", "brows", "wink", "movement", "nose_x", "nose_y", "lip_x",
                    "expression_mouth", "expression_brows"}
        if op in ("arm", "sample") and channel not in channels:
            return False
        if op == "clear" and channel is not None and channel not in channels:
            return False
        axes = fields.get("axes")
        if axes is not None and (op != "clear" or channel != "movement" or not isinstance(axes, list)
                                 or not axes or len(axes) > 2 or any(axis not in ("x", "y") for axis in axes)
                                 or len(set(axes)) != len(axes)):
            return False
        if op == "sample" and (not self._movement_input or channel not in self._armed_bindings):
            return False
        if op not in ("arm", "sample", "clear", "watch", "heartbeat"):
            return False
        retained = []
        if op in ("clear", "sample"):
            while True:
                try:
                    pending = self._out.get_nowait()
                except queue.Empty:
                    break
                same_channel = pending.get("channel") == channel
                discard = (op == "clear" and (channel is None or same_channel))
                if op == "clear" and axes is not None and pending.get("op") == "arm":
                    discard = False  # Partial clear preserves this channel's arm.
                # Latest scalar sample wins. Never discard a still-fresh tap event
                # while coalescing continuous holds/vectors.
                discard |= (op == "sample" and same_channel and pending.get("op") == "sample"
                            and not pending.get("taps"))
                if not discard:
                    retained.append(pending)
            for pending in retained:
                self._out.put_nowait(pending)
        self._seq += 1
        message = {"v": 3, "op": op, "epoch": self._epoch, "seq": self._seq, **fields}
        try:
            self._out.put_nowait(message)
        except queue.Full:
            self._invalidate("output queue full")
            if self._socket is not None:
                try:
                    self._socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            return False
        if op == "arm":
            self._armed_bindings.add(channel)
        elif op == "clear":
            if channel is None:
                self._armed_bindings.clear()
            elif axes is None:
                self._armed_bindings.discard(channel)
        self._armed = bool(self._armed_bindings)
        return True

    def _retention_allowed(self, op, fields):
        if "retain_on_tracking_loss" not in fields:
            return True
        retain = fields["retain_on_tracking_loss"]
        if type(retain) is not bool or op != "sample" or fields.get("channel") != "movement":
            return False
        if not retain:
            return True
        held, taps = fields.get("held", []), fields.get("taps", [])
        return (self._retained_nose_movement and isinstance(held, list) and len(held) <= 96
                and all(target in ("KEY:W", "KEY:A", "KEY:S", "KEY:D") for target in held)
                and isinstance(taps, list) and not taps)

    def _invalidate(self, status):
        with self._lock:
            self._epoch = None
            self._desktop_input = False
            self._movement_input = False
            self._retained_nose_movement = False
            self._armed = False
            self._armed_bindings.clear()
            self._sent_held.clear()
            self._generation += 1
            self._status = status
            self._source = {"keys": (), "buttons": (), "phone_stick": (0.0, 0.0), "stick_blocked": False}
            self._source_revision = -1
            self._source_events.clear()
            self._expired_channels = ()
            while True:
                try:
                    self._out.get_nowait()
                except queue.Empty:
                    break

    def _handle(self, msg):
        if not isinstance(msg, dict):
            self._reject_reply("Invalid receiver reply")
        if type(msg.get("v")) is not int or msg["v"] != self.protocol:
            self._reject_reply("Receiver update required for facial movement and wink" if self.protocol == 3 else
                               ("Receiver update required for both expressions" if self.simultaneous else "Unsupported receiver protocol"))
        if msg.get("op") in ("ready", "reset"):
            # Never retain capability or queued ownership across a reset, even
            # when the replacement reply is missing/malformed/unsupported.
            self._invalidate("checking receiver capabilities")
            epoch = msg.get("epoch")
            if not isinstance(epoch, int) or isinstance(epoch, bool) or epoch < 0:
                self._reject_reply("Invalid receiver epoch")
            desktop_input = msg.get("desktop_input", False)
            if type(desktop_input) is not bool:
                self._reject_reply("Invalid receiver desktop-input capability")
            if self.require_desktop_input and not desktop_input:
                self._reject_reply("Receiver update required for mouse clicks and Spacebar input")
            movement_input = msg.get("movement_input", False)
            if type(movement_input) is not bool:
                self._reject_reply("Invalid receiver movement capability")
            if self.require_movement_input and not movement_input:
                self._reject_reply("Receiver update required for facial movement and wink")
            retained_nose_movement = msg.get("retained_nose_movement", False)
            if type(retained_nose_movement) is not bool:
                self._reject_reply("Invalid receiver retained nose movement capability")
            if self.require_retained_nose_movement and not retained_nose_movement:
                self._reject_reply("Receiver update required for retained nose movement")
            with self._lock:
                self._epoch = epoch
                self._desktop_input = desktop_input
                self._movement_input = movement_input
                self._retained_nose_movement = retained_nose_movement
                self._status = "ready; waiting for neutral"
        elif self.protocol == 3 and msg.get("op") == "source":
            if msg.get("epoch", self._epoch) != self._epoch or self._epoch is None:
                return
            keys, buttons = msg.get("keys"), msg.get("buttons")
            vector = msg.get("phone_stick", (0.0, 0.0))
            revision = msg.get("revision")
            blocked = msg.get("stick_blocked", False)
            if (not isinstance(keys, list) or not isinstance(buttons, list)
                    or len(keys) > 96 or len(buttons) > 32
                    or not all(isinstance(token, str) and len(token) <= 32 for token in keys + buttons)
                    or not isinstance(vector, (list, tuple)) or len(vector) != 2
                    or not all(type(x) in (int, float) and math.isfinite(x) and -1 <= x <= 1 for x in vector)
                    or type(revision) is not int or revision < 0 or type(blocked) is not bool):
                self._reject_reply("Invalid receiver input-source snapshot")
            with self._lock:
                if revision > self._source_revision:
                    self._source_revision = revision
                    self._source = {"keys": tuple(keys), "buttons": tuple(buttons),
                                    "phone_stick": tuple(vector), "stick_blocked": blocked}
                    if len(self._source_events) >= 64:
                        self._reject_reply("Receiver shortcut event overflow")
                    self._source_events.append(dict(self._source))
        elif self.protocol == 3 and msg.get("op") == "takeover":
            if msg.get("epoch", self._epoch) != self._epoch or self._epoch is None:
                return
            with self._lock:
                self._takeover_generation += 1
                for channel in ("movement", "nose_x", "nose_y", "lip_x", "expression_mouth", "expression_brows"):
                    self._sent_held.pop(channel, None)
                self._armed_bindings.difference_update({"movement", "nose_x", "nose_y", "lip_x",
                                                        "expression_mouth", "expression_brows"})
                self._armed = bool(self._armed_bindings)
                retained = []
                while True:
                    try:
                        pending = self._out.get_nowait()
                    except queue.Empty:
                        break
                    if pending.get("channel") in ("mouth", "brows", "wink") or pending.get("op") in ("watch", "heartbeat") or (pending.get("op") == "clear" and pending.get("channel") is None):
                        retained.append(pending)
                for pending in retained:
                    self._out.put_nowait(pending)
        elif self.protocol == 3 and msg.get("op") == "expired":
            if msg.get("epoch", self._epoch) != self._epoch or self._epoch is None:
                return
            channels = msg.get("channels")
            if (not isinstance(channels, list) or len(channels) > 16
                    or any(not isinstance(channel, str) or channel not in {"mouth", "brows", "wink", "movement", "nose_x", "nose_y", "lip_x",
                                           "expression_mouth", "expression_brows"} for channel in channels)):
                self._reject_reply("Invalid receiver movement expiry")
            with self._lock:
                self._armed_bindings.difference_update(channels)
                for channel in channels:
                    self._sent_held.pop(channel, None)
                self._armed = bool(self._armed_bindings)
                self._expiry_generation += 1
                self._expired_channels = tuple(sorted(set(self._expired_channels) | set(channels)))
                retained = []
                while True:
                    try:
                        pending = self._out.get_nowait()
                    except queue.Empty:
                        break
                    if pending.get("channel") not in channels or pending.get("op") == "clear":
                        retained.append(pending)
                for pending in retained:
                    self._out.put_nowait(pending)
        elif msg.get("op") == "error":
            self._reject_reply(str(msg.get("message", "Receiver rejected request")))

    def _reject_reply(self, detail):
        self._invalidate(detail)
        raise ValueError(detail)

    def _send_message(self, connection, message):
        with self._lock:
            if (message["epoch"] != self._epoch
                    or message.get("button") in DESKTOP_INPUTS and not self._desktop_input):
                return False
            if message["op"] == "sample":
                captured = message.get("captured_at")
                if (not self._movement_input or type(captured) not in (int, float)
                        or not self._retention_allowed("sample", message)
                        or message.get("channel") not in self._armed_bindings
                        or not math.isfinite(captured) or not 0 <= time.monotonic() - captured <= .250):
                    return False
            connection.sendall((json.dumps(message) + "\n").encode())
            if message["op"] == "sample":
                selected = set(message.get("held", ()))
                self._sent_press_count += len(message.get("taps", ())) + len(selected - self._sent_held.get(message["channel"], set()))
                self._sent_held[message["channel"]] = selected
                self._last_sent_output = f"{message['channel']}: {message.get('held', [])}; stick {message.get('vector', '—')}"
            if message["op"] == "clear" and self.protocol == 3:
                channel = message.get("channel")
                if channel is None:
                    self._sent_held.clear()
                elif message.get("axes"):
                    removed = {"KEY:A", "KEY:D"} if "x" in message["axes"] else set()
                    if "y" in message["axes"]:
                        removed |= {"KEY:W", "KEY:S"}
                    self._sent_held.setdefault(channel, set()).difference_update(removed)
                else:
                    self._sent_held.pop(channel, None)
            if message["op"] in ("tap", "hold"):
                self._sent_press_count += 1
            if message["op"] in ("tap", "hold", "release"):
                expression = f" ({message['binding']})" if message.get("binding") else ""
                label = OUTPUT_LABELS.get(message["button"], message["button"])
                target = "" if message["button"] in DESKTOP_INPUTS else f" for {self.player}"
                self._last_sent_output = f"{message['op'].capitalize()} {label}{expression}{target}"
            return True

    def _run(self):
        while not self._stop.is_set():
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=.20) as connection:
                    connection.settimeout(.025)
                    with self._lock:
                        self._socket = connection
                        self._seq = 0
                    hello = {"v": self.protocol, "op": "hello", "session": uuid4().hex, "player": self.player}
                    connection.sendall((json.dumps(hello) + "\n").encode())
                    buffer = b""
                    last_heartbeat = time.monotonic()
                    while not self._stop.is_set():
                        with self._lock:
                            if time.monotonic() - self._supervisor_at > .250:
                                raise TimeoutError("supervisor heartbeat expired")
                        try:
                            data = connection.recv(4096)
                            if not data:
                                raise ConnectionError("receiver disconnected")
                            buffer += data
                            if len(buffer) > 8192:
                                raise ValueError("Oversized receiver reply")
                            while b"\n" in buffer:
                                line, buffer = buffer.split(b"\n", 1)
                                self._handle(json.loads(line))
                        except socket.timeout:
                            pass
                        if time.monotonic() - last_heartbeat >= .100:
                            self.send("heartbeat")
                            last_heartbeat = time.monotonic()
                        # Send only messages still belonging to this receiver epoch.
                        for _ in range(32):
                            try:
                                message = self._out.get_nowait()
                            except queue.Empty:
                                break
                            self._send_message(connection, message)
            except (OSError, ValueError, ConnectionError, TypeError) as exc:
                self._invalidate(f"disconnected: {exc}")
            finally:
                with self._lock:
                    self._socket = None
                    # Preserve the cause while invalidating even unexpected exits.
                    status = "stopped" if self._stop.is_set() else (
                        self._status if self._epoch is None else "disconnected")
                    self._invalidate(status)
            self._stop.wait(.5)

    def stop(self):
        self._stop.set()
        with self._lock:
            sock = self._socket
            if sock:
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
        if self._thread:
            self._thread.join(timeout=1)
        self._invalidate("stopped")
