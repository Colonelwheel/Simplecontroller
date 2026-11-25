"""Mouse-only isolation. One outstanding call; releases outlive the caller's wait."""
import logging
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from collections import deque
from multiprocessing.connection import Pipe, PipeConnection


def worker(handle, backend=None):
    """Only the inherited pipe is owned here: no receiver, sockets or virtual pads."""
    pipe = PipeConnection(handle)
    sys.stdout = sys.stderr  # The protocol uses its own handle, never stdout.
    held = set()
    try:
        if backend is None:
            import pyautogui
            from relative_mouse import send_relative_mouse
            pyautogui.FAILSAFE = False
            pyautogui.PAUSE = 0
            backend = pyautogui
            backend.game_move = send_relative_mouse
        pipe.send((True, os.getpid()))
        while True:
            op, args = pipe.recv()
            if op == "close":
                break
            try:
                if op == "mouseDown":
                    held.add(args[0])  # A partial native DOWN still owes cleanup.
                    backend.mouseDown(button=args[0])
                elif op == "mouseUp":
                    backend.mouseUp(button=args[0])
                    held.discard(args[0])
                elif op in ("moveRel", "game_move", "scroll"):
                    getattr(backend, op)(*args)
                else:
                    raise ValueError("unsupported mouse operation")
                reply = (True, None)
            except Exception as error:
                reply = (False, repr(error))
            pipe.send(reply)
    except (EOFError, OSError):
        pass
    finally:
        for button in tuple(held):
            try:
                backend.mouseUp(button=button)
            except Exception:
                logging.exception("Mouse helper could not release %s", button)
        pipe.close()


class MouseOutput:
    """The only background thread owns IPC; callers never wait behind one another.

    wanted contains accepted continuous holds, not every requested DOWN. A busy
    DOWN is latched as dropped until UP; it is never replayed during recovery.
    """

    def __init__(self, command=None):
        self._condition = threading.Condition()
        self._wanted = set()
        self._held = set()
        self._releases = set()
        self._dropped = set()
        self._closing = False
        self.error = None
        self.process = None
        self._worker_matches = True
        self._request = self._item("ready", ())
        self._command = command
        self._respawns = deque()
        self._paused = False
        self._resume = False
        self._thread = threading.Thread(target=self._run, name="mouse-output-io", daemon=True)
        self._thread.start()

    @staticmethod
    def _item(op, args):
        return {"op": op, "args": args, "done": threading.Event(), "ok": False}

    @property
    def pending(self):
        with self._condition:
            return bool(self._request or self._releases or self.error)

    def _call(self, op, *args):
        with self._condition:
            if op == "retry" and self.error:
                self._resume = self._paused
                self._condition.notify()
                return False
            if op == "retry":
                if self._request:
                    return False
                button = next(iter(self._releases), None)
                if button is None:
                    return True
                op, args = "mouseUp", (button,)
            button = args[0] if op in ("mouseDown", "mouseUp") else None
            if op == "mouseUp":
                self._wanted.discard(button)
                self._dropped.discard(button)
                self._releases.add(button)
            if (self._closing or self.error or self._request
                    or self._releases and op != "mouseUp"):
                if op == "mouseDown" and button not in self._wanted:
                    self._dropped.add(button)
                return False
            if op == "mouseDown":
                if button in self._dropped:
                    return False
                self._wanted.add(button)
                self._held.add(button)
            item = self._item(op, args)
            self._request = item
            self._condition.notify()
        # This is a soft caller budget, never a native-call expiry or kill timer.
        return item["done"].wait(0.100) and item["ok"]

    def mouseDown(self, button):
        return self._call("mouseDown", button)

    def mouseUp(self, button):
        return self._call("mouseUp", button)

    def moveRel(self, x, y):
        return self._call("moveRel", x, y)

    def game_move(self, x, y):
        return self._call("game_move", x, y)

    def scroll(self, amount):
        return self._call("scroll", amount)

    def retry_releases(self):
        return self._call("retry")

    def close(self):
        with self._condition:
            self._closing = True
            self._wanted.clear()
            self._releases.update(self._held)
            self._condition.notify()
        # Never terminate a helper inside a native call. EOF also releases its ledger.

    def _spawn(self):
        parent, child = Pipe(duplex=True)
        try:
            command = self._command
            if command is None:
                command = [sys.executable]
                if not getattr(sys, "frozen", False):
                    command += [str(Path(__file__).with_name("receiver_diagnostics.py"))]
            startup = subprocess.STARTUPINFO()
            startup.lpAttributeList = {"handle_list": [child.fileno()]}
            os.set_handle_inheritable(child.fileno(), True)
            self.process = subprocess.Popen(
                [*command, "--mouse-output-worker", str(child.fileno())],
                startupinfo=startup, close_fds=True,
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                creationflags=subprocess.CREATE_NO_WINDOW)
            self._worker_matches = not getattr(sys, "frozen", False)
        except BaseException:
            parent.close()
            raise
        finally:
            child.close()
        return parent

    def _exchange(self, pipe, op, args):
        pipe.send((op, args))
        late = not pipe.poll(0.100)
        if late:
            logging.warning("Mouse call pending; new mouse presses/motion dropped, releases retained; "
                            "keyboard/Xbox remain independent. Mouse resumes after reply and cleanup.")
        ok, error = pipe.recv()
        if late:
            logging.info("Late mouse reply received; reconciling retained button releases")
        if not ok:
            logging.warning("Mouse helper operation failed: %s", error)
        return ok

    def _serve(self, pipe):
        while True:
            with self._condition:
                while not self._request and not self._closing:
                    self._condition.wait(0.250)
                    if self.process.poll() is not None:
                        raise OSError("mouse helper exited while idle")
                item = self._request
                if item is None:
                    item = self._item("close", ())
                    self._request = item
            ok = True
            if item["op"] not in ("close", "ready"):
                ok = self._exchange(pipe, item["op"], item["args"])
                if ok and item["op"] == "mouseUp":
                    with self._condition:
                        self._releases.discard(item["args"][0])
                        self._held.discard(item["args"][0])
            # Finalize atomically with the last release check: no lost-UP window.
            while True:
                with self._condition:
                    button = next(iter(self._releases - self._wanted), None)
                    if not ok or button is None:
                        item["ok"] = ok
                        item["done"].set()
                        self._request = None
                        break
                ok = self._exchange(pipe, "mouseUp", (button,))
                if ok:
                    with self._condition:
                        self._releases.discard(button)
                        self._held.discard(button)
            if item["op"] == "ready" and ok and self._respawns:
                logging.info("Mouse recovery complete; previous mouse Holds ended; release and press again")
            with self._condition:
                if self._closing:
                    break
        pipe.send(("close", ()))

    def _await_respawn(self, error):
        with self._condition:
            self.error = repr(error)
            self._releases.update(self._held)
            self._dropped.update(self._wanted)
            self._wanted.clear()  # A confirmed crash ends mouse Holds; never replay DOWN.
            if self._request:
                self._request["done"].set()
            self._request = None
        logging.error("Mouse helper unavailable; mouse cleanup/recovery pending: %s", error)
        earliest = time.monotonic() + 1.0
        while True:
            warn = False
            with self._condition:
                if self._closing:
                    return False
                now = time.monotonic()
                while self._respawns and now - self._respawns[0] >= 60.0:
                    self._respawns.popleft()
                exited = self.process is None or (self._worker_matches and self.process.poll() is not None)
                if exited and len(self._respawns) >= 3 and not self._paused:
                    self._paused, warn = True, True
                if (exited and now >= earliest and len(self._respawns) < 3
                        and (not self._paused or self._resume)):
                    self._respawns.append(now)
                    self._paused = self._resume = False
                    self._request = self._item("ready", ())
                    return True
                self._condition.wait(0.250)
            if warn:
                logging.error("Mouse recovery paused after 3 respawns in 60 seconds; "
                              "Release All requests another attempt when the rate limit permits. "
                              "Keyboard/Xbox remain enabled.")

    def _run(self):
        while True:
            pipe = None
            try:
                pipe = self._spawn()
                if not pipe.poll(10.0):
                    logging.warning("Mouse helper not ready after 10 seconds; mouse pending, "
                                    "keyboard/Xbox remain enabled; waiting without killing it")
                ok, detail = pipe.recv()
                if not ok:
                    raise OSError(detail)
                self._worker_matches = detail == self.process.pid
                if not self._worker_matches:
                    logging.error("Mouse worker PID differs from launcher; automatic respawn disabled "
                                  "because worker exit cannot be verified")
                with self._condition:
                    self.error = None  # The occupied ready slot still fences cleanup.
                self._serve(pipe)
                return
            except Exception as error:
                if pipe is not None:
                    pipe.close()
                if not self._await_respawn(error):
                    return
            finally:
                if pipe is not None:
                    pipe.close()
