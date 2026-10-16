"""Negotiated phone-only aggregate state and scoped event freshness.

No native imports. The receiver supplies its existing RLock and output adapters.
Acknowledgements confirm processing or supersession, never game recognition.
"""
import math
import re
from urllib.parse import unquote_plus, quote_plus

CAPABILITY = "PHONE_OUTPUT_V1"
_IDENTITY = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
_BUTTONS = frozenset(("A", "B", "X", "Y", "LB", "RB", "BACK", "START",
                      "UP", "DOWN", "LEFT", "RIGHT", "LS", "RS"))


def state_key(command, player):
    """Validate a single reversible command and derive its physical output key."""
    upper = command.upper()
    match = re.fullmatch(r"X360([A-Z0-9]+)_(HOLD|RELEASE)", upper)
    if match and match[1] in _BUTTONS:
        return f"{player}|XBOX|{match[1]}"
    match = re.fullmatch(r"MOUSE_(LEFT|RIGHT|MIDDLE)_(DOWN|UP)", upper)
    if match:
        return f"GLOBAL|MOUSE|{match[1]}"
    match = re.fullmatch(r"(LT|RT):(.+)", upper)
    if match:
        try:
            value = float(match[2])
        except ValueError:
            return None
        return f"{player}|TRIGGER|{match[1]}" if math.isfinite(value) and 0 <= value <= 1 else None
    match = re.fullmatch(r"STICK_(MACRO_)?([LR])(_NO_FOLLOW)?:(.+),(.+)", upper)
    if match:
        if match[3] and match[2] != "L":
            return None
        try:
            x, y = float(match[4]), float(match[5])
        except ValueError:
            return None
        if not all(math.isfinite(v) and -1 <= v <= 1 for v in (x, y)):
            return None
        return f"{player}|{'MACRO' if match[1] else 'MANUAL'}|{match[2]}"
    if upper.startswith(("KEY_DOWN:", "KEY_UP:")):
        key = command.split(":", 1)[1].strip()
        if not key or key.upper().startswith("WAIT_") or len(key) > 80 or player != "player1":
            return None
        aliases = {"SPACEBAR": "SPACE", "SPACE BAR": "SPACE", "ESCAPE": "ESC",
                   "CONTROL": "LCTRL", "LEFT CTRL": "LCTRL", "LEFT CONTROL": "LCTRL",
                   "CTRL": "LCTRL", "SHIFT": "LSHIFT", "LEFT SHIFT": "LSHIFT",
                   "ALT": "LALT", "LEFT ALT": "LALT", "WIN": "LWIN",
                   "WINDOWS": "LWIN", "LEFT WINDOWS": "LWIN", "RIGHT WINDOWS": "RWIN",
                   "RIGHT CTRL": "RCTRL", "RIGHT CONTROL": "RCTRL", "RIGHT SHIFT": "RSHIFT",
                   "RIGHT ALT": "RALT", "PAGE UP": "PAGEUP", "PAGE DOWN": "PAGEDOWN",
                   "CAPS LOCK": "CAPSLOCK"}
        return "GLOBAL|KEY|" + aliases.get(key.upper(), key.upper())
    if upper in ("CAMERA_FOLLOW:0", "CAMERA_FOLLOW:1"):
        return f"{player}|CAMERA|ENABLED"
    return None


def inactive_command(command):
    if command.startswith("X360"):
        return command.replace("_HOLD", "_RELEASE")
    if command.startswith("MOUSE_"):
        return command.replace("_DOWN", "_UP")
    if command.startswith("KEY_DOWN:"):
        return command.replace("KEY_DOWN:", "KEY_UP:", 1)
    if command.startswith(("LT:", "RT:")):
        return command.split(":", 1)[0] + ":0.0"
    if command.startswith("STICK_"):
        return command.split(":", 1)[0] + ":0.0,0.0"
    return command


def active_command(command):
    if command.startswith(("LT:", "RT:")):
        return float(command.split(":", 1)[1]) > 0
    return not (command.endswith(("_RELEASE", "_UP")) or command.startswith("KEY_UP:"))


class PhoneEventGuard:
    def __init__(self, protocol, session, player, scope, generation, sequence):
        self.protocol, self.session, self.player = protocol, session, player
        self.scope, self.generation, self.sequence = scope, generation, sequence

    def __call__(self):
        return self.protocol.event_is_current(self.session, self.player, self.scope, self.generation, self.sequence)

    def apply(self, command):
        return self.protocol.apply_event_state(self, command)

    def release(self, command):
        key = state_key(command, self.player)
        with self.protocol.lock:
            current = self.protocol.event_outputs.get((self.session, self.scope, self.generation), {}).get(key)
            if current is None or current[0] != self.sequence:
                return False
            return self.apply(command)


class PhoneOutputProtocolState:
    def __init__(self, lock, barrier_is_current, apply_state, dispatch_event, conflict_status=None, activate_phone=None):
        self.lock = lock
        self.barrier_is_current = barrier_is_current
        self.apply_state = apply_state
        self.dispatch_event = dispatch_event
        self.conflict_status = conflict_status
        self.activate_phone = activate_phone or (lambda player: None)
        self.negotiated = set()
        self.retired = set()
        self.players = {}
        self.revisions = {}
        self.commands = {}
        self.ended = {}
        self.events = {}
        self.event_outputs = {}
        self.activation_barriers = {}
        self.completed_activations = set()

    def legacy_packet_is_current(self, session, sequence):
        with self.lock:
            return sequence > self.activation_barriers.get(session, -1)

    def newest_sequence(self, session, keys):
        with self.lock:
            sequences = [self.revisions.get((session, key), -1) for key in keys]
            sequences += [value[0] for owner, values in self.event_outputs.items()
                          if owner[0] == session for key, value in values.items() if key in keys]
            return max(sequences, default=-1)

    def _allowed(self, session, player):
        return session in self.negotiated and session not in self.retired and self.players.get(player) == session

    def event_is_current(self, session, player, scope, generation, sequence):
        with self.lock:
            return (self._allowed(session, player)
                    and self.barrier_is_current(session, sequence)
                    and sequence > self.activation_barriers.get(session, -1)
                    and generation > self.ended.get((session, scope), -1))

    def current_command(self, session, key):
        with self.lock:
            return self.commands.get((session, key))

    def effective_command(self, session, key, fallback):
        with self.lock:
            return self._effective(session, key, fallback)

    def clear_outputs(self, players):
        """Hard cleanup clears remembered desired values, preserving freshness/tombstones."""
        with self.lock:
            def selected(key):
                return key.split("|", 1)[0] in players or key.startswith("GLOBAL|")
            for identity in tuple(self.commands):
                if selected(identity[1]):
                    del self.commands[identity]
            for values in self.event_outputs.values():
                for key in tuple(values):
                    if selected(key):
                        del values[key]

    def _effective(self, session, key, fallback):
        candidates = []
        base = self.commands.get((session, key))
        if (base and active_command(base)
                and self.barrier_is_current(session, self.revisions.get((session, key), -1))):
            candidates.append((self.revisions.get((session, key), -1), base))
        for (owner_session, scope, generation), values in self.event_outputs.items():
            if (owner_session == session and generation > self.ended.get((session, scope), -1)
                    and key in values and self.barrier_is_current(session, values[key][0])):
                candidates.append(values[key])
        if not candidates:
            return inactive_command(fallback)
        if "|TRIGGER|" in key:
            return max(candidates, key=lambda item: float(item[1].split(":", 1)[1]))[1]
        return max(candidates)[1]

    def apply_event_state(self, guard, command):
        with self.lock:
            if not guard():
                return False
            key = state_key(command, guard.player)
            if key is None:
                return False
            owner = guard.session, guard.scope, guard.generation
            values = self.event_outputs.setdefault(owner, {})
            if active_command(command):
                values[key] = guard.sequence, command
            else:
                values.pop(key, None)
            self.apply_state(self._effective(guard.session, key, command), guard.player)
            return True

    def process(self, data, player):
        if not data.startswith(("PHONE_CAPS_V1:", "PHONE_ACTIVATE_V1:", "PHONE_STATE_V1:", "PHONE_EVENT_V1:", "PHONE_END_V1:")):
            return False, None
        if len(data.encode("utf-8")) > 1000 or player not in ("player1", "player2"):
            return True, None
        parts = data.split(":")
        if parts[0] == "PHONE_CAPS_V1":
            if len(parts) != 3 or not all(_IDENTITY.fullmatch(p) for p in parts[1:]):
                return True, None
            session, nonce = parts[1:]
            with self.lock:
                if session in self.retired:
                    return True, None
                old = self.players.get(player)
                self.players[player] = session
                if old and old != session and old not in self.players.values():
                    self.retired.add(old)
                self.negotiated.add(session)
            return True, f"PHONE_CAPS_V1_ACK:{session}:{nonce}:{CAPABILITY}"
        if len(parts) < 3 or not _IDENTITY.fullmatch(parts[1]):
            return True, None
        session = parts[1]
        try:
            sequence = int(parts[2])
            if sequence < 0:
                raise ValueError()
        except ValueError:
            return True, None
        ack = f"PHONE_ACK_V1:{session}:{sequence}"
        def output_ack(key):
            if self.conflict_status is None:
                return ack
            status = "MASKED" if self.conflict_status(key, player) else "CLEAR"
            return ack + f":{status}:{quote_plus(key)}"
        with self.lock:
            if not self._allowed(session, player):
                return True, None
            if parts[0] == "PHONE_ACTIVATE_V1":
                if len(parts) != 3:
                    return True, None
                identity = session, sequence
                if identity in self.completed_activations or sequence < self.activation_barriers.get(session, -1):
                    return True, ack
                if not self.barrier_is_current(session, sequence) and sequence != self.activation_barriers.get(session):
                    return True, None
                self.activation_barriers[session] = sequence
                self.clear_outputs((player,))
                self.activate_phone(player)
                self.completed_activations.add(identity)
                return True, ack
            if not self.barrier_is_current(session, sequence):
                return True, None
            if sequence <= self.activation_barriers.get(session, -1):
                return True, None
            if session not in self.activation_barriers:
                return True, None
            if parts[0] == "PHONE_STATE_V1":
                if len(parts) != 5:
                    return True, None
                key, command = unquote_plus(parts[3]), unquote_plus(parts[4])
                if state_key(command, player) != key:
                    return True, None
                identity = session, key
                if sequence <= self.revisions.get(identity, -1):
                    return True, output_ack(key)
                # Commit freshness only after complete validation and successful dispatch.
                old_command, old_revision = self.commands.get(identity), self.revisions.get(identity)
                self.commands[identity] = command
                self.revisions[identity] = sequence
                try:
                    self.apply_state(self._effective(session, key, command), player)
                except Exception:
                    if old_command is None:
                        self.commands.pop(identity, None)
                        self.revisions.pop(identity, None)
                    else:
                        self.commands[identity], self.revisions[identity] = old_command, old_revision
                    raise
                return True, output_ack(key)
            if parts[0] == "PHONE_END_V1":
                if len(parts) != 5:
                    return True, None
                scope = unquote_plus(parts[3])
                try:
                    generation = int(parts[4])
                    if generation < 0 or not scope or len(scope) > 300:
                        raise ValueError()
                except ValueError:
                    return True, None
                identity = session, scope
                self.ended[identity] = max(generation, self.ended.get(identity, -1))
                for owner, values in tuple(self.event_outputs.items()):
                    if owner[0:2] == identity and owner[2] <= generation:
                        del self.event_outputs[owner]
                        for key, (_, command) in values.items():
                            self.apply_state(self._effective(session, key, command), player)
                return True, ack
            if parts[0] == "PHONE_EVENT_V1":
                if len(parts) != 7:
                    return True, None
                scope, command = unquote_plus(parts[3]), unquote_plus(parts[6])
                try:
                    generation, event = int(parts[4]), int(parts[5])
                    if min(generation, event) < 0 or not scope or len(scope) > 300 or len(command) > 300:
                        raise ValueError()
                except ValueError:
                    return True, None
                if not self.event_is_current(session, player, scope, generation, sequence):
                    return True, ack
                identity = session, scope, generation
                if event <= self.events.get(identity, -1):
                    return True, ack
                feedback_key = None
                if command.startswith("PHONE_REPLAY:"):
                    key = command.removeprefix("PHONE_REPLAY:")
                    feedback_key = key
                    output = session, key
                    if output not in self.commands or sequence <= self.revisions.get(output, -1):
                        return True, ack
                    self.revisions[output] = sequence
                elif command.startswith("PHONE_FORCE:"):
                    raw = command.removeprefix("PHONE_FORCE:")
                    key = state_key(raw, player)
                    feedback_key = key
                    if key is None:
                        return True, None
                    output = session, key
                    if sequence <= self.revisions.get(output, -1):
                        return True, ack
                    self.revisions[output] = sequence
                guard = PhoneEventGuard(self, session, player, scope, generation, sequence)
                self.dispatch_event(command, player, guard, session)
                self.events[identity] = event
                return True, output_ack(feedback_key) if feedback_key else ack
        return True, None
