"""Optional loopback VoiceAttack input with receiver-owned timing.

This module uses only the Python standard library. Importing it does not start
threads, open sockets, or import native controller/camera packages. The receiver
supplies pads exposing ``voice_set(button, value)`` and ``voice_clear()`` and its
existing controller lock. Digital values are bools; LT/RT values are floats.

The wire protocol is one newline-terminated JSON request per TCP connection.
Read the ready message first and copy its receiver/epoch into the request. A
clear advances the generation; an exact duplicate of the last accepted command
is acknowledged without applying it again, including a duplicate clear.

Finite trigger requests temporarily overlay a persistent voice trigger using the
highest value. Expiry restores the persistent value. Another finite request
replaces the temporary value and deadline; a new persistent request cancels the
overlay. Release, trigger value zero, and cleanup clear both layers.
"""

from __future__ import annotations

import json
import math
import select
import socket
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Callable, Iterable


PROTOCOL_VERSION = 1
DEFAULT_PORT = 42736
MAX_DURATION_MS = 600_000
MAX_SEQUENCE = (1 << 53) - 1
MAX_SESSIONS = 128
MAX_CLIENTS = 8
MAX_REQUEST_BYTES = 4096
CLIENT_TIMEOUT_SECONDS = 1.0
TICK_SECONDS = 0.01
DIGITAL_BUTTONS = frozenset(
    {"A", "B", "X", "Y", "LB", "RB", "START", "BACK", "UP", "DOWN",
     "LEFT", "RIGHT", "LS", "RS"}
)
TRIGGER_BUTTONS = frozenset({"LT", "RT"})
PLAYER_IDS = frozenset({"player1", "player2"})
_COMMON_FIELDS = frozenset({"v", "receiver", "epoch", "session", "seq", "player", "op"})
_OP_FIELDS = {
    "tap": frozenset({"button", "duration_ms"}),
    "hold": frozenset({"button", "duration_ms"}),
    "release": frozenset({"button"}),
    "trigger": frozenset({"button", "value", "duration_ms"}),
    "clear": frozenset(),
    "release_all": frozenset(),
}


class ProtocolError(ValueError):
    """A request was rejected before dispatch."""


def _is_int(value: object) -> bool:
    return type(value) is int


def _strict_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ProtocolError("duplicate JSON field")
        result[key] = value
    return result


def _reject_constant(value: str) -> object:
    raise ProtocolError("JSON numbers must be finite")


def decode_request(data: bytes) -> dict:
    """Decode strict UTF-8 JSON, rejecting duplicate fields and NaN/Infinity."""
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=_strict_object,
                          parse_constant=_reject_constant)
    except ProtocolError:
        raise
    except (UnicodeDecodeError, ValueError, RecursionError) as exc:
        raise ProtocolError("invalid UTF-8 JSON") from exc


@dataclass(frozen=True)
class _AcceptedRequest:
    seq: int
    fingerprint: str
    epoch_after: int


class VoiceInputState:
    """Voice ownership and deadlines serialized by the receiver's shared RLock.

    ``global_release`` must call the receiver's existing release path, including
    ``state.reset(player_ids)``. The callback runs under the shared RLock.
    Intentional persistent inputs survive client disconnects.
    """

    def __init__(self, gamepads: dict, lock, global_release: Callable[[], None],
                 *, clock: Callable[[], float] = time.monotonic):
        self.gamepads = gamepads
        self.lock = lock
        self.global_release = global_release
        self.clock = clock
        self.receiver = str(uuid.uuid4())
        self.epoch = 0
        self._active: dict[tuple[str, str], bool | float] = {}
        self._deadlines: dict[tuple[str, str], float] = {}
        self._persistent_triggers: dict[tuple[str, str], float] = {}
        self._sessions: dict[str, _AcceptedRequest] = {}

    def ready(self) -> dict:
        with self.lock:
            return {"v": PROTOCOL_VERSION, "op": "ready",
                    "receiver": self.receiver, "epoch": self.epoch}

    def error(self, message: str) -> dict:
        with self.lock:
            return {"v": PROTOCOL_VERSION, "op": "error", "message": message,
                    "receiver": self.receiver, "epoch": self.epoch}

    def _ack(self, seq: int) -> dict:
        return {"v": PROTOCOL_VERSION, "op": "ack", "receiver": self.receiver,
                "epoch": self.epoch, "seq": seq}

    def _validate(self, request: object) -> dict:
        if not isinstance(request, dict):
            raise ProtocolError("request must be an object")
        if not all(isinstance(key, str) for key in request):
            raise ProtocolError("field names must be strings")
        if not _COMMON_FIELDS.issubset(request):
            raise ProtocolError("missing required field")
        if not _is_int(request["v"]) or request["v"] != PROTOCOL_VERSION:
            raise ProtocolError("unsupported protocol version")
        op = request["op"]
        if not isinstance(op, str) or op not in _OP_FIELDS:
            raise ProtocolError("unsupported operation")
        if set(request) - (_COMMON_FIELDS | _OP_FIELDS[op]):
            raise ProtocolError("unexpected field")
        if request["receiver"] != self.receiver or not isinstance(request["receiver"], str):
            raise ProtocolError("stale receiver")
        if not _is_int(request["epoch"]) or request["epoch"] < 0:
            raise ProtocolError("invalid epoch")
        session = request["session"]
        if not isinstance(session, str):
            raise ProtocolError("session must be a UUID")
        try:
            if str(uuid.UUID(session)) != session:
                raise ValueError("noncanonical UUID")
        except (ValueError, AttributeError) as exc:
            raise ProtocolError("session must be a canonical UUID") from exc
        if not _is_int(request["seq"]) or not 1 <= request["seq"] <= MAX_SEQUENCE:
            raise ProtocolError("seq must be a positive safe integer")
        player = request["player"]
        if not isinstance(player, str) or player not in PLAYER_IDS or player not in self.gamepads:
            raise ProtocolError("invalid or unavailable player")
        if op in {"tap", "hold", "release", "trigger"}:
            button = request.get("button")
            allowed = (DIGITAL_BUTTONS if op in {"tap", "hold"} else
                       TRIGGER_BUTTONS if op == "trigger" else
                       DIGITAL_BUTTONS | TRIGGER_BUTTONS)
            if not isinstance(button, str) or button not in allowed:
                raise ProtocolError("invalid button for operation")
        if "duration_ms" in request:
            duration = request["duration_ms"]
            if not _is_int(duration) or not 1 <= duration <= MAX_DURATION_MS:
                raise ProtocolError("duration_ms must be an integer from 1 to 600000")
        if op == "trigger":
            value = request.get("value")
            if type(value) not in (int, float) or not 0 <= value <= 1 or not math.isfinite(value):
                raise ProtocolError("trigger value must be finite and from 0 to 1")
        return request

    def handle(self, request: object) -> dict:
        """Validate and apply one command atomically, returning ack or error."""
        with self.lock:
            try:
                request = self._validate(request)
                fingerprint = json.dumps(request, sort_keys=True, separators=(",", ":"),
                                         allow_nan=False)
                session, seq = request["session"], request["seq"]
                previous = self._sessions.get(session)
                if previous is not None:
                    if seq == previous.seq:
                        if fingerprint == previous.fingerprint:
                            if self.epoch == previous.epoch_after:
                                return self._ack(seq)
                            raise ProtocolError("stale epoch")
                        raise ProtocolError("seq already used for a different request")
                    if seq < previous.seq:
                        raise ProtocolError("seq is older than the last accepted request")
                if request["epoch"] != self.epoch:
                    raise ProtocolError("stale epoch")
                if previous is None and len(self._sessions) >= MAX_SESSIONS:
                    raise ProtocolError("voice session limit reached; restart receiver to reset sessions")
                self._dispatch(request)
                self._sessions[session] = _AcceptedRequest(seq, fingerprint, self.epoch)
                return self._ack(seq)
            except ProtocolError as exc:
                return self.error(str(exc))

    def _release(self, key: tuple[str, str]) -> None:
        player, button = key
        self.gamepads[player].voice_set(button, 0.0 if button in TRIGGER_BUTTONS else False)
        self._active.pop(key, None)
        self._deadlines.pop(key, None)
        self._persistent_triggers.pop(key, None)

    def _dispatch(self, request: dict) -> None:
        op, player = request["op"], request["player"]
        if op == "clear":
            self.reset(tuple(self.gamepads))
            return
        if op == "release_all":
            self.global_release()
            return
        key = (player, request["button"])
        if op == "release" or (op == "trigger" and request["value"] == 0):
            self._release(key)
            return
        duration = request.get("duration_ms", 100 if op == "tap" else None)
        if op in {"tap", "hold"}:
            if duration is not None and key in self._active and key not in self._deadlines:
                # A finite pulse must not release a deliberately persistent hold.
                return
            self.gamepads[player].voice_set(key[1], True)
            self._active[key] = True
            if duration is None:
                self._deadlines.pop(key, None)
            else:
                deadline = self.clock() + duration / 1000.0
                self._deadlines[key] = max(self._deadlines.get(key, deadline), deadline)
            return
        value = float(request["value"])
        if duration is None:
            self._persistent_triggers[key] = value
            self._deadlines.pop(key, None)
        else:
            value = max(self._persistent_triggers.get(key, 0.0), value)
            self._deadlines[key] = self.clock() + duration / 1000.0
        self.gamepads[player].voice_set(key[1], value)
        self._active[key] = value

    def tick(self) -> None:
        """Expire finite voice inputs. Call approximately every 10 ms."""
        with self.lock:
            now = self.clock()
            expired = [key for key, deadline in self._deadlines.items() if deadline <= now]
            for key in expired:
                if key in self._persistent_triggers:
                    value = self._persistent_triggers[key]
                    self.gamepads[key[0]].voice_set(key[1], value)
                    self._active[key] = value
                    del self._deadlines[key]
                else:
                    self._release(key)

    def reset(self, player_ids: Iterable[str]) -> None:
        """Clear selected voice ownership/timers and invalidate queued requests."""
        with self.lock:
            players = set(player_ids)
            if not players.issubset(self.gamepads):
                raise ValueError("cannot reset unknown player")
            self._active = {key: value for key, value in self._active.items() if key[0] not in players}
            self._deadlines = {key: value for key, value in self._deadlines.items() if key[0] not in players}
            self._persistent_triggers = {
                key: value for key, value in self._persistent_triggers.items() if key[0] not in players
            }
            self.epoch += 1
            # Invalidate timing first, and still attempt the other player if a
            # native pad fails. The caller reports any native output failure.
            failure = None
            for player in sorted(players):
                try:
                    self.gamepads[player].voice_clear()
                except Exception as exc:
                    if failure is None:
                        failure = exc
            if failure is not None:
                raise failure


@dataclass
class _Client:
    socket: socket.socket
    deadline: float
    outgoing: bytes
    incoming: bytes = b""
    closing: bool = False


def _encode_message(message: dict) -> bytes:
    return (json.dumps(message, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")


class VoiceInputServer:
    """Bounded nonblocking localhost server; no native dependencies or workers."""

    def __init__(self, state: VoiceInputState, *, logger: Callable[[str], None] | None = None,
                 host: str = "127.0.0.1", port: int = DEFAULT_PORT):
        if host != "127.0.0.1":
            raise ValueError("voice server may bind only to 127.0.0.1")
        if not _is_int(port) or not 0 <= port <= 65535:
            raise ValueError("invalid port")
        self.state = state
        self.logger = logger
        self.host = host
        self.port = port
        self._listener: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    def _log(self, message: str) -> None:
        if self.logger is not None:
            try:
                if callable(self.logger):
                    self.logger(message)
                else:
                    self.logger.info(message)
            except Exception:
                pass

    def start(self) -> VoiceInputServer:
        if self._thread is not None and self._thread.is_alive():
            return self
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            listener.bind((self.host, self.port))
            listener.listen(MAX_CLIENTS)
            listener.setblocking(False)
        except Exception:
            listener.close()
            raise
        self.port = listener.getsockname()[1]
        self._listener = listener
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="VoiceAttackInput", daemon=True)
        self._thread.start()
        self._log(f"VoiceAttack input listening on {self.host}:{self.port}")
        return self

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None and self._thread is not threading.current_thread():
            self._thread.join(timeout=2.0)

    def _read(self, client: _Client) -> None:
        try:
            chunk = client.socket.recv(MAX_REQUEST_BYTES + 1 - len(client.incoming))
        except BlockingIOError:
            return
        if not chunk:
            client.closing = True
            client.outgoing = b""
            return
        client.incoming += chunk
        if len(client.incoming) > MAX_REQUEST_BYTES:
            response = self.state.error("request exceeds 4096 bytes")
        elif b"\n" in client.incoming:
            line, remaining = client.incoming.split(b"\n", 1)
            try:
                if remaining.strip():
                    raise ProtocolError("one request per connection")
                response = self.state.handle(decode_request(line))
            except ProtocolError as exc:
                response = self.state.error(str(exc))
        else:
            return
        client.outgoing += _encode_message(response)
        client.closing = True

    def _run(self) -> None:
        clients: dict[socket.socket, _Client] = {}
        listener = self._listener
        try:
            while not self._stop.is_set():
                self.state.tick()
                now = time.monotonic()
                for sock, client in list(clients.items()):
                    if client.deadline <= now or (client.closing and not client.outgoing):
                        sock.close()
                        del clients[sock]
                readable = [listener] + [sock for sock, client in clients.items() if not client.closing]
                writable = [sock for sock, client in clients.items() if client.outgoing]
                try:
                    ready_read, ready_write, _ = select.select(readable, writable, [], TICK_SECONDS)
                except (OSError, ValueError):
                    if self._stop.is_set():
                        break
                    raise
                if listener in ready_read:
                    # A bounded accept batch prevents connection floods from starving timers.
                    for _ in range(MAX_CLIENTS):
                        try:
                            sock, address = listener.accept()
                        except BlockingIOError:
                            break
                        if len(clients) >= MAX_CLIENTS or address[0] != "127.0.0.1":
                            sock.close()
                            continue
                        sock.setblocking(False)
                        clients[sock] = _Client(sock, time.monotonic() + CLIENT_TIMEOUT_SECONDS,
                                                _encode_message(self.state.ready()))
                for sock in ready_read:
                    if sock is listener or sock not in clients:
                        continue
                    try:
                        self._read(clients[sock])
                    except OSError:
                        sock.close()
                        del clients[sock]
                for sock in ready_write:
                    if sock not in clients:
                        continue
                    client = clients[sock]
                    try:
                        sent = sock.send(client.outgoing)
                        client.outgoing = client.outgoing[sent:]
                    except BlockingIOError:
                        pass
                    except OSError:
                        sock.close()
                        del clients[sock]
        except Exception as exc:
            self._log(f"VoiceAttack input stopped: {type(exc).__name__}: {exc}")
        finally:
            # A stopped scheduler must not leave finite or persistent voice
            # inputs held. This clears voice only, preserving phone/face.
            try:
                self.state.reset(tuple(self.state.gamepads))
            except Exception as exc:
                self._log(f"VoiceAttack cleanup failed: {type(exc).__name__}: {exc}")
            for sock in clients:
                sock.close()
            if listener is not None:
                listener.close()
            self._listener = None


__all__ = ["VoiceInputState", "VoiceInputServer", "ProtocolError", "decode_request",
           "DIGITAL_BUTTONS", "TRIGGER_BUTTONS"]
