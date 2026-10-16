"""App orchestration; native inference and receiver IO never run on the UI thread."""
from __future__ import annotations

import multiprocessing as mp
import math
from pathlib import Path
import queue
import threading
import time

from .detection import Calibration, ExpressionGate, Sample
from .receiver_client import ReceiverClient
from .settings import AppSettings, EXPRESSIONS, DESKTOP_INPUTS
from .worker import demo_worker, inference_worker
from .movement import MotionProcessor, MotionSample, ExpressionMovementProcessor, BoostProcessor, direction_for_vector, wasd_keys
from .shortcuts import ShortcutKeys, ShortcutEdges


def _worker_boot(allowed, stop, target, args):
    """No native work before the GUI owns a kill-on-exit process job."""
    if allowed.wait(3) and not stop.is_set():
        target(*args)


def _owned_worker_job(worker):
    from .camera_source import _CaptureJob
    return _CaptureJob(worker)

class AppRuntime:
    def __init__(self, settings: AppSettings, model_path: Path | None, feed_name=None, demo=False):
        self.settings = settings.validate()
        self.model_path = model_path
        self.feed_name = feed_name
        self.demo = demo
        self._lock = threading.RLock()
        self._context = mp.get_context("spawn")
        self._output = self._context.Queue(maxsize=2)
        self._worker_stop = self._context.Event()
        self._preview = self._context.Event()
        # One writer, aligned scalar readers; avoid a killed worker retaining a
        # multiprocessing lock and blocking a later FPS edit on the GUI thread.
        self._fps = self._context.Value("i", settings.fps, lock=False)
        self._stop = threading.Event()
        self._restart = threading.Event()
        self._mailbox = queue.Queue(maxsize=2)
        self._worker_generation = 0
        self._manager = None
        self._worker = self._thread = None
        self.client = None if demo else self._new_client(settings)
        self.calibration = Calibration()
        self._gates = {expression: self._make_gate(expression) for expression in self._channels()}
        self._last_samples = {expression: None for expression in self._channels()}
        self._gate = self._gates[settings.expression]
        self._last_client_generation = None
        self._last_sample = None
        self._last_result_identity = None
        self._last_result_generation = None
        self._last_result_at = None
        self._last_result_captured = None
        self._delivered = []
        self._state = {"camera_status": "waiting for shared feed", "receiver_status": "disconnected",
            "worker_status": "not started", "scores": {}, "frame": None, "activated": False,
            "armed": False, "mode": "paused", "fps": 0.0, "result_age_ms": None,
            "inference_ms": None, "event_count": 0, "output_queued_count": 0, "output_sent_count": 0,
            "last_output_sent": "", "last_error": "", "calibration_status": "Not calibrated", "capture_info": {},
            "expression_states": {expression: {"activated": False, "armed": False, "event_count": 0,
                                    "enabled": expression in settings.active_expressions} for expression in self._channels()}}
        self._motion = MotionProcessor(settings.movement)
        self._expression_motion = self._make_expression_motion()
        self._boosts = {"motion": BoostProcessor(settings.movement.boosts), **{e:BoostProcessor(c.boosts) for e,c in settings.expression_movements.items()}}
        self._last_features = {}; self._processed_movement_identity = None
        self._output_frame_sequence = 0
        self._center_deadlines = {}; self._motion_owner = None
        self._wasd_direction = None
        self._last_takeover_generation = self._last_expiry_generation = None
        self._shortcuts = ShortcutKeys(self._shortcut_keys())
        self._shortcut_edges = ShortcutEdges()
        self._shortcut_source_initialized=False
        self._toggle_local_edges = ShortcutEdges()
        self._toggle_phone_edges = ShortcutEdges()
        self._toggle_source_generation = None
        self._toggle_source_initialized = False
        self._toggle_start_at = None
        self._last_toggle_at = -1e12
        self._shortcut_local_events = ()
        self._shortcut_phone_events = ()
        self._recent_recenter={}
        self._last_v3_held={}
        self._last_motion_claim = None
        self._telemetry = None
        self._state["movement"] = {"vector":(0.,0.),"amplitudes":{},"raw":{},"ready_axes":(),"neutral_axes":(),"status":"Re-center required","owner":None,"boosts":{},"boost_input_amplitude":None,"boost_tap_count":0}

    def _channels(self):
        return (*EXPRESSIONS, "wink") if self.settings.wink_enabled else EXPRESSIONS

    @staticmethod
    def _requires_movement_input(settings):
        # Phone Xbox toggle observation uses the existing v3 watch endpoint.
        return bool(settings.live_toggle_buttons) or settings.wink_enabled or settings.movement.enabled or any(c.enabled for e,c in settings.expression_movements.items() if e in settings.active_expressions)

    def _new_client(self, settings):
        options = dict(simultaneous=settings.simultaneous,require_desktop_input=self._requires_desktop_input(settings))
        if self._requires_movement_input(settings): options["require_movement_input"] = True
        return ReceiverClient(settings.player,**options)

    @staticmethod
    def _nose_only_movement(settings):
        axes = [c for c in (settings.movement.horizontal, settings.movement.vertical) if c.enabled]
        return bool(axes) and all(c.source == "nose" for c in axes)

    def _make_expression_motion(self):
        return {e:ExpressionMovementProcessor(c, self.settings.thresholds[e]["on"], self.settings.thresholds[e]["off"],
                    self.settings.thresholds[e].get("neutral",self.settings.thresholds[e]["off"]),self.settings.binding_for(e).preset)
                for e,c in self.settings.expression_movements.items()}

    def _shortcut_chords(self):
        m=self.settings.movement
        chords=[("all",m.shared_recenter_keys,m.shared_recenter_buttons)]
        for name,cfg in (("x",m.horizontal),("y",m.vertical)):
            if cfg.enabled: chords.append((name,cfg.recenter_keys,cfg.recenter_buttons))
        for name,cfg in self.settings.expression_movements.items():
            if cfg.enabled and name in self.settings.active_expressions: chords.append((name,cfg.recenter_keys,cfg.recenter_buttons))
        return chords

    def _shortcut_keys(self):
        return tuple(sorted({k for _,keys,_ in self._shortcut_chords() for k in keys} | set(self.settings.live_toggle_keys)))

    def _request_live_toggle(self, now):
        # Coalesce the same phone key observed through both TCP and the hook.
        if now - self._last_toggle_at < .150:
            return
        self._last_toggle_at = now
        if self._state["mode"] == "live":
            self.set_mode("paused")
        elif self._state["mode"] == "calibrate":
            self._state["last_error"] = "Finish calibration before using the Start / Pause shortcut."
        else:
            self._toggle_start_at = None if self._toggle_start_at is not None else now

    def consume_live_toggle_start(self):
        """UI consumes a short-lived request through its ordinary Start guards."""
        with self._lock:
            requested, self._toggle_start_at = self._toggle_start_at, None
            return requested is not None and 0 <= time.monotonic() - requested <= .500

    def _tick_live_toggle(self, now, connection):
        self._shortcut_local_events = tuple(self._shortcuts.drain_events())
        self._shortcut_phone_events = tuple(getattr(self.client, "drain_source_events", lambda: ())()) if self.client else ()
        local_chords = [("toggle", self.settings.live_toggle_keys, ())]
        phone_chords = [*local_chords, ("toggle", (), self.settings.live_toggle_buttons)]
        if connection.get("generation") != self._toggle_source_generation:
            self._toggle_source_generation = connection.get("generation")
            self._toggle_source_initialized = False
            self._toggle_phone_edges = ShortcutEdges()
            self._toggle_start_at = None
        def phone(event, prime=False):
            keys = {k.removeprefix("KEY:") for k in event.get("keys", ())}
            fired = self._toggle_phone_edges.update(phone_chords, keys, event.get("buttons", ()))
            if fired and not prime:
                self._request_live_toggle(now)
        for event in self._shortcut_phone_events:
            phone(event, prime=not self._toggle_source_initialized)
            self._toggle_source_initialized = True
        if not self._toggle_source_initialized and connection.get("source_revision", -1) >= 0:
            phone(connection.get("source", {}), prime=True)
            self._toggle_source_initialized = True
        if self._toggle_source_initialized:
            phone(connection.get("source", {}))
        for keys in (*self._shortcut_local_events, self._shortcuts.snapshot()):
            if self._toggle_local_edges.update(local_chords, keys, ()):
                self._request_live_toggle(now)
        if self.client and connection.get("ready") and connection.get("protocol") == 3:
            if getattr(self, "_watch_generation", None) != connection.get("generation"):
                keys = sorted({"KEY:" + k for k in self._shortcut_keys()})
                buttons = sorted({b for _, _, bs in self._shortcut_chords() for b in bs} | set(self.settings.live_toggle_buttons))
                if self.client.send("watch", keys=keys, buttons=buttons):
                    self._watch_generation = connection.get("generation")

    def _make_gate(self, expression=None):
        expression = self.settings.expression if expression is None else expression
        t = self.settings.wink_thresholds if expression == "wink" else self.settings.thresholds[expression]
        binding = self.settings.binding_for(expression)
        return ExpressionGate(t["on"], t["off"], binding.preset, binding.mode,
                              neutral=t.get("neutral", t["off"]))

    @staticmethod
    def _requires_desktop_input(settings):
        return (any(settings.binding_for(e).button in DESKTOP_INPUTS for e in settings.active_expressions)
                or settings.movement.enabled and settings.movement.output=="wasd"
                or any(c.enabled and (c.output=="wasd" or any(a.startswith("KEY:") or a in DESKTOP_INPUTS for s in c.boosts if s.enabled for a in s.actions)) for e,c in settings.expression_movements.items() if e in settings.active_expressions)
                or settings.movement.enabled and any(a.startswith("KEY:") or a in DESKTOP_INPUTS for s in settings.movement.boosts if s.enabled for a in s.actions))

    def _sync_gate_state(self):
        for expression, gate in self._gates.items():
            state = self._state["expression_states"][expression]
            enabled = expression in self.settings.active_expressions
            state.update(enabled=enabled, activated=enabled and gate.active,
                         armed=enabled and (gate.ready or gate.active))
        self._state["activated"] = any(self._state["expression_states"][e]["activated"]
                                       for e in self.settings.active_expressions)
        self._state["armed"] = all(self._state["expression_states"][e]["armed"]
                                  for e in self.settings.active_expressions)
        if self._requires_movement_input(self.settings) and hasattr(self,"_motion"):
            movement_expressions={e for e,c in self.settings.expression_movements.items() if c.enabled and e in self.settings.active_expressions}
            self._state["armed"] &= all(self._state["expression_states"][e]["armed"] for e in movement_expressions)
            if self.settings.movement.enabled and self._motion_owner not in EXPRESSIONS:
                needed={a for a,c in (("x",self.settings.movement.horizontal),("y",self.settings.movement.vertical)) if c.enabled}
                self._state["armed"] &= bool(self._state["movement"].get("bridging")) or needed<=self._motion._ready
            vector=self._state["movement"]["vector"]
            self._state["activated"] |= math.hypot(*vector)>1e-8
        self._gate = self._gates[self.settings.expression]
        self._last_sample = self._last_samples[self.settings.expression]

    def start_workers(self):
        with self._lock:
            if self._manager is not None:
                return
            self._state["worker_status"] = "starting"
            if self.client:
                self.client.start()
            if not self.demo: self._shortcuts.start()
            try:
                from .telemetry import ScalarTelemetry
                self._telemetry = ScalarTelemetry()
            except Exception:
                self._telemetry = None
            self._thread = threading.Thread(target=self._run, name="facial-supervisor", daemon=True)
            self._manager = threading.Thread(target=self._manage_workers, name="facial-worker-management", daemon=True)
            self._thread.start()
            self._manager.start()

    def _read_output(self, output, stop, generation):
        # multiprocessing.Queue.get_nowait() may still wait for the rest of a
        # pickled pipe payload. Never perform that read under the GUI lock or
        # in the freshness/receiver-heartbeat supervisor.
        try:
            while not self._stop.is_set() and not stop.is_set():
                try:
                    message = output.get(timeout=.1)
                except queue.Empty:
                    continue
                except (EOFError, OSError, ValueError):
                    return
                item = (generation, message)
                try:
                    self._mailbox.put_nowait(item)
                except queue.Full:
                    try:
                        self._mailbox.get_nowait()
                    except queue.Empty:
                        pass
                    try:
                        self._mailbox.put_nowait(item)
                    except queue.Full:
                        pass
        finally:
            endpoint = getattr(output, "_reader", None)
            if endpoint is not None:
                endpoint.close()  # Its own thread is no longer waiting on this handle.

    @staticmethod
    def _close_output(output, reader=None):
        output.cancel_join_thread()
        try:
            output.close()
        finally:
            # A consumer-only Queue never started its feeder/finalizer. Closing
            # its local writer is necessary for EOF after a producer dies in a
            # partial payload; otherwise that writer can keep a reader alive.
            writer = getattr(output, "_writer", None)
            if writer is not None:
                writer.close()
            if reader is not None and reader.ident is not None:
                reader.join(timeout=.25)
            if reader is None or not reader.is_alive():
                endpoint = getattr(output, "_reader", None)
                if endpoint is not None:
                    endpoint.close()
            # Never close a handle another thread is polling on Windows. A
            # blocked reader owns its eventual closure after EOF/cancellation.

    @staticmethod
    def _close_worker(worker, stop, output, job=None, reader=None):
        stop.set()
        try:
            if worker is not None and worker.pid is not None:
                worker.join(timeout=2)
                if worker.is_alive():
                    # Only our own process. Shared-mode camera ownership is external.
                    worker.terminate()
                    worker.join(timeout=1)
                if worker.is_alive():
                    worker.kill()
                    worker.join(timeout=.5)
                if not worker.is_alive():
                    worker.close()
        finally:
            try:
                if job is not None:
                    # Includes descendants if native shutdown is wedged. The
                    # handle closes automatically when the GUI process dies.
                    job.close()
            finally:
                AppRuntime._close_output(output, reader)

    def _manage_workers(self):
        current = None
        try:
            self._close_output(self._output)
            while not self._stop.is_set():
                with self._lock:
                    generation = self._worker_generation
                    settings = self.settings
                    self._restart.clear()
                output = self._context.Queue(maxsize=2)
                stop = self._context.Event()
                target = demo_worker if self.demo else inference_worker
                args = (output, stop, self._preview, self._fps)
                if not self.demo:
                    args += (str(self.model_path) if self.model_path else None, self.feed_name,
                             settings.camera_source, settings.camera_index,
                             settings.camera_resolution, settings.camera_roi)
                allowed = self._context.Event()
                worker = self._context.Process(target=_worker_boot, args=(allowed, stop, target, args),
                                               name="facial-inference", daemon=False)
                current = (worker, stop, output, None, None)
                worker.start()
                job = _owned_worker_job(worker)
                current = (worker, stop, output, job, None)
                allowed.set()
                with self._lock:
                    self._worker, self._worker_stop, self._output = worker, stop, output
                reader = threading.Thread(target=self._read_output, args=(output, stop, generation),
                                          name="facial-result-reader", daemon=True)
                current = (worker, stop, output, job, reader)
                reader.start()
                while not self._stop.is_set() and not self._restart.wait(.05):
                    pass
                with self._lock:
                    self._worker = None
                retiring = current
                current = None
                self._close_worker(*retiring)
        except Exception as exc:
            with self._lock:
                self._clear()
                self._state["mode"] = "paused"
                self._state["worker_status"] = "fault"
                self._state["last_error"] = f"Worker management stopped safely: {exc}"
        finally:
            with self._lock:
                self._worker = None
            if current is not None:
                self._close_worker(*current)

    def snapshot(self):
        with self._lock:
            value = dict(self._state)
            value["scores"] = dict(value["scores"])
            value["capture_info"] = dict(value["capture_info"])
            value["expression_states"] = {e: dict(state) for e, state in value["expression_states"].items()}
            value["movement"] = {**value["movement"],"amplitudes":dict(value["movement"]["amplitudes"]),"raw":dict(value["movement"]["raw"]),"boosts":dict(value["movement"]["boosts"])}
            value["shortcut_status"] = self._shortcuts.status
            return value

    def set_preview(self, enabled):
        self._preview.set() if enabled else self._preview.clear()
        if not enabled:
            with self._lock:
                self._state["frame"] = None

    def _bridge_motion(self, now):
        """Keep an accepted nose claim while the session lives; no cached samples."""
        claim = self._last_motion_claim
        if not claim or self._state["mode"] != "live" or not self.client:
            return False
        connection = self.client.snapshot()
        if (not connection.get("ready") or not connection.get("retained_nose_movement")
                or connection.get("source", {}).get("stick_blocked")
                or any(connection.get(name, 0) != claim[name] for name in ("generation", "takeover_generation"))
                or now < claim["captured_at"]):
            return False
        self._state["movement"].update(vector=claim["vector"], keys=claim["keys"], owner="motion", bridging=True,
            status="Holding last nose output until tracking returns or Pause")
        return True

    def _clear(self, expression=None, *, tracking_loss=False, now=None):
        bridge = expression is None and tracking_loss and self._bridge_motion(time.monotonic() if now is None else now)
        if expression is None and not bridge:
            self._last_motion_claim = None
        if self.client:
            fields = ({"channel":expression} if expression is not None else {}) if self._requires_movement_input(self.settings) else ({"binding": expression} if expression is not None and self.settings.simultaneous else {})
            if bridge:
                for channel in ("mouth", "brows", "wink", "nose_x", "nose_y", "lip_x", "expression_mouth", "expression_brows"):
                    self._clear_channel(channel)
            else:
                self.client.send("clear", **fields)
        for target in self._channels() if expression is None else (expression,):
            self._gates[target] = self._make_gate(target)
            self._last_samples[target] = None
        if hasattr(self,"_motion"):
            if expression is None:
                self._last_v3_held.clear()
                self._motion.invalidate(tracking_loss=tracking_loss)
                for p in self._expression_motion.values(): p.invalidate()
                for b in self._boosts.values(): b.reset()
                self._motion_owner=None;self._wasd_direction=None
                self._processed_movement_identity=self._last_result_identity
                self._state["movement"].update(vector=self._last_motion_claim["vector"] if bridge else (0.,0.),
                    owner="motion" if bridge else None, bridging=bridge, ready_axes=(),boosts={},boost_input_amplitude=None,raw={},amplitudes={})
            elif expression in self._expression_motion:
                self._expression_motion[expression].invalidate();self._boosts[expression].reset()
        self._sync_gate_state()

    def set_settings(self, settings):
        with self._lock:
            self._toggle_start_at = None
            previous_player = self.settings.player
            previous_simultaneous = self.settings.simultaneous
            previous_desktop_input = self._requires_desktop_input(self.settings)
            previous_movement_input = self._requires_movement_input(self.settings)
            previous_movement = self.settings.movement
            previous_source = (self.settings.camera_source, self.settings.camera_index,
                               self.settings.camera_resolution, self.settings.camera_roi)
            self._clear()
            self._state["mode"] = "paused"
            settings.validate()
            self.settings = settings
            self._gates = {e: self._make_gate(e) for e in self._channels()}
            self._last_samples = {e:None for e in self._channels()}
            self._state["expression_states"] = {e:self._state["expression_states"].get(e,{"event_count":0}) for e in self._channels()}
            if previous_movement != settings.movement:
                def geometry(config):
                    return (config.tilt_degrees, *((axis.enabled, axis.source, axis.orientation_degrees, axis.inverted)
                        for axis in (config.horizontal, config.vertical)))
                if geometry(previous_movement) != geometry(settings.movement):
                    self._motion=MotionProcessor(settings.movement);self._center_deadlines.clear()
                else:
                    # Travel, deadzone and smoothing edits do not change origin
                    # or reference units. Settings edits still pause all output.
                    self._motion.config = settings.movement
            self._expression_motion=self._make_expression_motion()
            self._boosts={"motion":BoostProcessor(settings.movement.boosts),**{e:BoostProcessor(c.boosts) for e,c in settings.expression_movements.items()}}
            old_shortcuts=self._shortcuts
            self._shortcuts=ShortcutKeys(self._shortcut_keys());self._shortcut_edges=ShortcutEdges()
            self._shortcut_source_initialized=False;self._watch_generation=None
            self._toggle_local_edges = ShortcutEdges(); self._toggle_phone_edges = ShortcutEdges()
            self._toggle_source_initialized = False; self._toggle_source_generation = None
            if self._manager is not None and not self.demo: self._shortcuts.start()
            threading.Thread(target=old_shortcuts.stop,name="facial-old-shortcut-stop",daemon=True).start()
            self._sync_gate_state()
            self._fps.value = settings.fps
            self._state["mode"] = "paused"
            self._state["last_error"] = ""
            if previous_source != (settings.camera_source, settings.camera_index,
                                   settings.camera_resolution, settings.camera_roi):
                self.restart_camera()
            if self.client and (previous_player != settings.player or previous_simultaneous != settings.simultaneous
                                or previous_desktop_input != self._requires_desktop_input(settings)
                                or previous_movement_input != self._requires_movement_input(settings)):
                old_client = self.client
                self.client = self._new_client(settings)
                self._state["output_queued_count"] = self._state["output_sent_count"] = 0
                self._state["last_output_sent"] = ""
                self._last_client_generation = None
                self._watch_generation=None
                self._last_takeover_generation=self._last_expiry_generation=None
                if self._manager is not None:
                    self.client.start()
                # Socket shutdown/join also stays off the controls' thread.
                threading.Thread(target=old_client.stop, name="facial-old-receiver-stop", daemon=True).start()

    def restart_camera(self):
        """Explicit retry/source switch; startup remains paused and needs neutral."""
        with self._lock:
            self._toggle_start_at = None
            self._clear()
            self._motion.invalidate(clear_centers=True)
            self._center_deadlines.clear()
            self._last_features={}
            self._state["mode"] = "paused"
            self._worker_generation += 1
            self._last_result_identity = self._last_result_generation = None
            self._last_result_at = self._last_result_captured = None
            self._delivered = []
            self.calibration = Calibration()
            self._state.update(camera_status="switching camera source", scores={}, frame=None,
                               fps=0.0, result_age_ms=None, inference_ms=None, last_error="", capture_info={})
            self._restart.set()

    def set_mode(self, mode):
        if mode not in ("paused", "test", "live", "calibrate"):
            raise ValueError("Unknown operating mode")
        with self._lock:
            self._toggle_start_at = None
            handoff = mode == "live" and self._state["mode"] == "test"
            self._clear(tracking_loss=handoff)
            if mode == "live":
                if self.demo:
                    self._state["last_error"] = "Demo cannot send controller input."
                    self._state["mode"] = "paused"
                    return False
                required_scores = set(self.settings.active_expressions)
                if not required_scores and not self.settings.movement.enabled:
                    self._state.update(last_error="Enable a button or movement input first.",mode="paused")
                    return False
                if any((not self.settings.wink_calibrated if e=="wink" else e not in self.settings.calibrated) for e in required_scores):
                    self._state["last_error"] = "Calibrate and review every enabled expression, or explicitly save its tested manual thresholds first."
                    self._state["mode"] = "paused"
                    return False
                if not self.client.snapshot()["ready"]:
                    self._state["last_error"] = "Receiver unavailable. Start the updated receiver first."
                    self._state["mode"] = "paused"
                    return False
                if self._nose_only_movement(self.settings) and not self.client.snapshot().get("retained_nose_movement"):
                    self._state.update(last_error="Receiver update required for retained nose movement. Start the new receiver build.", mode="paused")
                    return False
                if (self._state["camera_status"] != "ready" or self._last_result_captured is None
                        or not 0 <= time.monotonic() - self._last_result_captured <= .250
                        or any(not self._valid_score(self._state["scores"].get(e)) for e in required_scores)):
                    self._state["last_error"] = "A fresh camera frame with valid scores for every enabled expression is required."
                    self._state["mode"] = "paused"
                    return False
                if self.settings.movement.enabled:
                    needed={a for a,c in (("x",self.settings.movement.horizontal),("y",self.settings.movement.vertical)) if c.enabled}
                    if not needed <= set(self._motion._centers):
                        self._state.update(last_error="Re-center each enabled movement axis in Test before Start.",mode="paused")
                        return False
            self.calibration.stage = None
            self._state["mode"] = mode
            self._state["last_error"] = ""
            return True

    @staticmethod
    def _valid_score(score):
        return (isinstance(score, (float, int)) and not isinstance(score, bool)
                and math.isfinite(score) and 0 <= score <= 1)

    def begin_calibration(self, stage="neutral"):
        with self._lock:
            self._toggle_start_at = None
            self.set_mode("calibrate")
            self.calibration.begin(stage)
            self._state["calibration_status"] = f"Collecting {stage}; Stop/Review ends collection"

    def finish_calibration(self):
        with self._lock:
            self._clear()
            self._state["mode"] = "paused"
            thresholds = self.calibration.finish()
            self._state["calibration_status"] = "Review thresholds and test without outputs before Start"
            return thresholds

    def _observe(self, msg, now):
        if isinstance(msg.get("capture_info"), dict):
            self._state["capture_info"] = dict(msg["capture_info"])
        if msg["kind"] != "result":
            self._toggle_start_at = None
            self._clear(tracking_loss=msg["kind"] == "camera", now=now)
            # A camera diagnostic invalidates the previous result. Its old
            # timestamps must not overwrite a specific fault/startup phase
            # with generic stale/stalled labels on the following timer tick.
            self._last_result_captured = self._last_result_at = None
            self._state["result_age_ms"] = None
            self._state["fps"] = 0.0
            self._state["inference_ms"] = None
            self._state["scores"] = {}
            self._state["frame"] = None
            self._state["camera_status"] = msg["detail"] if msg["kind"] == "camera" else "unavailable"
            self._state["worker_status"] = "waiting for frames" if msg["kind"] == "camera" else "fault"
            if msg["kind"] == "fault":
                self._state["last_error"] = msg["detail"]
                self._state["mode"] = "paused"
            return
        identity = (*msg["generation"], msg["sequence"])
        if identity == self._last_result_identity:
            return
        generation = tuple(msg["generation"])
        if self._last_result_generation is not None and generation != self._last_result_generation:
            self._toggle_start_at = None
            self._clear()
            self._motion.invalidate(clear_centers=True);self._center_deadlines.clear()
            self.calibration = Calibration()
            self._state["mode"] = "paused"
            self._state["last_error"] = "Camera/scene changed. Review the feed and Start again."
        self._last_result_generation = generation
        self._last_result_identity = identity
        self._last_result_at = now
        self._last_result_captured = msg["captured_at"]
        self._output_frame_sequence += 1
        self._last_features = dict(msg.get("features",{})) if isinstance(msg.get("features",{}),dict) else {}
        self._state["scores"] = msg["scores"]
        self._state["frame"] = msg["frame"] if self._preview.is_set() else None
        self._state["inference_ms"] = msg["inference_ms"]
        self._state["worker_status"] = "running (synthetic)" if self.demo else "running"
        fresh = 0 <= now - msg["captured_at"] <= .250
        valid = {e: self._valid_score(msg["scores"].get(e)) and fresh for e in self._channels()}
        any_active_valid = (any(valid[e] for e in self.settings.active_expressions)
            or fresh and self.settings.movement.enabled and bool(self._last_features))
        self._state["camera_status"] = "ready" if any_active_valid else ("no face" if fresh else "stale")
        self._delivered = [t for t in self._delivered if now - t <= 2]
        self._delivered.append(now)
        self._state["fps"] = ((len(self._delivered) - 1) / (now - self._delivered[0])) if len(self._delivered) > 1 and now > self._delivered[0] else 0
        if not any_active_valid:
            self._clear(tracking_loss=True, now=now)
            return
        for expression in self._channels():
            if valid[expression]:
                self._last_samples[expression] = Sample(msg["sequence"], msg["captured_at"],
                                                        msg["scores"][expression], generation)
            elif expression in self.settings.active_expressions:
                self._clear(expression)
            else:
                self._last_samples[expression] = None
        self._sync_gate_state()
        if self._state["mode"] == "calibrate":
            self.calibration.observe(msg["scores"])
            stage = self.calibration.stage
            self._state["calibration_status"] = f"{stage}: {len(self.calibration.samples[stage])} fresh samples" if stage else "Review collection"

    def _tick(self, now):
        if self.client:
            self.client.pulse()
            connection = self.client.snapshot()
            self._state["receiver_status"] = connection["status"]
            self._state["output_sent_count"] = connection.get("sent_press_count", 0)
            self._state["last_output_sent"] = connection.get("last_sent_output", "")
            if connection["generation"] != self._last_client_generation:
                self._last_client_generation = connection["generation"]
                if self._state["mode"] == "live":
                    # Receiver epochs protect game output, not offline expression tests.
                    self._clear()
                    if self.settings.movement.enabled:
                        self._motion.invalidate(clear_centers=True)
                        self._center_deadlines.clear()
                    self._state["mode"] = "paused"
                    self._state["last_error"] = "Receiver reset/disconnected. Relax to neutral, then Start again."
                    if self.settings.movement.enabled:
                        self._state["last_error"] += " Re-center enabled movement axes in Test first."
        else:
            self._state["receiver_status"] = "synthetic demo; no connection"
        self._tick_live_toggle(now, connection if self.client else {})
        if self._last_result_captured is not None:
            self._state["result_age_ms"] = max(0, (now - self._last_result_captured) * 1000)
            if not 0 <= now - self._last_result_captured <= .250:
                self._clear(tracking_loss=True, now=now)
                self._state["camera_status"] = "stale"
                self._state["scores"] = {}
                self._state["frame"] = None
        if self._last_result_at is not None and now - self._last_result_at > .500:
            self._state["worker_status"] = "waiting/stalled"
        if self._state["mode"] not in ("live", "test"):
            if self._state["mode"] == "paused" and self._last_result_captured is not None and 0 <= now-self._last_result_captured <= .250:
                raw = self._motion.displacement(self._last_features)
                amplitudes = {}
                for axis, value in raw.items():
                    cfg = self.settings.movement.horizontal if axis == "x" else self.settings.movement.vertical
                    amplitudes[axis] = value/(cfg.positive_range if value >= 0 else cfg.negative_range)
                self._state["movement"].update(raw=raw, amplitudes=amplitudes,
                    boost_input_amplitude=math.hypot(*amplitudes.values()) if amplitudes else None)
            return
        if self._requires_movement_input(self.settings):
            self._tick_extended(now,connection if self.client else {})
            self._sync_gate_state()
            return
        for expression in self.settings.active_expressions:
            gate = self._gates[expression]
            actions = gate.update(self._last_samples[expression], now)
            if any(a in ("tap", "hold") for a in actions):
                self._state["event_count"] += 1
                self._state["expression_states"][expression]["event_count"] += 1
            if self._state["mode"] == "live" and self.client:
                scoped = {"binding": expression} if self.settings.simultaneous else {}
                connection = self.client.snapshot()
                armed = expression in connection.get("armed_bindings", ()) if self.settings.simultaneous else connection["armed"]
                if gate.ready and not armed and not self.client.send("arm", **scoped):
                    self._clear()
                    self._state["mode"] = "paused"
                    self._state["last_error"] = "Output unavailable; facial input paused."
                    break
                for action in actions:
                    fields = {"button": self.settings.binding_for(expression).button, **scoped}
                    if action == "tap":
                        fields["duration_ms"] = 100
                    if not self.client.send(action, **fields):
                        self._clear()
                        self._state["mode"] = "paused"
                        self._state["last_error"] = "Output unavailable; facial input paused."
                        break
                    if action in ("tap", "hold"):
                        self._state["output_queued_count"] += 1
                if self._state["mode"] != "live":
                    break
        self._sync_gate_state()

    def recenter(self, target="all"):
        """Clear selected movement, then acquire fresh stable neutral in memory."""
        if target not in ("all","x","y","mouth","brows"):
            raise ValueError("Unknown movement re-center target")
        with self._lock:
            self._last_motion_claim = None
            self._state["movement"]["bridging"] = False
            requested_at=time.monotonic()
            if requested_at-self._recent_recenter.get(target,-1e12)<.150:
                return True  # One phone key can arrive through hook and TCP.
            self._recent_recenter[target]=requested_at
            if target in ("all","x","y"):
                axes=tuple(a for a,c in (("x",self.settings.movement.horizontal),("y",self.settings.movement.vertical))
                           if c.enabled and (target=="all" or target==a))
                self._motion.request_center(axes)
                for axis in axes: self._center_deadlines[axis]=time.monotonic()+1.
                if target=="all":
                    self._boosts["motion"].reset()
                    self._clear_channel("movement")
                    self._clear_channel("nose_x");self._clear_channel("nose_y");self._clear_channel("lip_x")
                else:
                    directions={"L","R","UL","UR","DL","DR"} if target=="x" else {"U","D","UL","UR","DL","DR"}
                    self._boosts["motion"].reset(directions)
                    if self._motion_owner not in EXPRESSIONS:
                        output_axis = "x" if target == "y" and self.settings.movement.vertical_to_horizontal else target
                        self._clear_channel("movement",axes=[output_axis])
                    self._clear_channel(self._motion_boost_channel("R" if target=="x" else "U"))
                    self._clear_channel(self._motion_boost_channel("UR"))
                vector=self._state["movement"]["vector"]
                if self._motion_owner not in EXPRESSIONS:
                    output_axis = "x" if target == "y" and self.settings.movement.vertical_to_horizontal else target
                    vector=tuple(0. if target=="all" or output_axis==axis else value for axis,value in zip(("x","y"),vector))
                self._state["movement"].update(vector=vector,status="Collecting stable neutral")
            if target in ("all","mouth","brows"):
                for e,p in self._expression_motion.items():
                    if target=="all" or target==e:
                        p.invalidate();self._boosts[e].reset();self._clear_channel("expression_"+e)
                        if self._motion_owner==e:
                            self._clear_channel("movement")
                            self._state["movement"].update(vector=(0.,0.),owner=None,boosts={},status="Waiting for fresh expression neutral")
            if target=="all" or target==self._motion_owner: self._motion_owner=None
        return True

    def _clear_channel(self, channel, **fields):
        if channel == "movement":
            self._last_motion_claim = None
            self._state["movement"]["bridging"] = False
        if not fields.get("axes"): self._last_v3_held.pop(channel,None)
        if self.client and self._requires_movement_input(self.settings):
            self.client.send("clear",channel=channel,**fields)

    def _motion_boost_channel(self, direction):
        horizontal = "nose_x" if self.settings.movement.horizontal.source=="nose" else "lip_x"
        diagonal = "lip_x" if horizontal=="nose_x" else "nose_x"
        return diagonal if len(direction)==2 else "nose_y" if direction in ("U","D") else horizontal

    def _reset_movement_sources(self, *, tracking_loss=False):
        self._motion.invalidate(tracking_loss=tracking_loss)
        for p in self._expression_motion.values(): p.invalidate()
        for b in self._boosts.values(): b.reset()
        self._motion_owner=None;self._wasd_direction=None
        for channel in ("movement","nose_x","nose_y","lip_x","expression_mouth","expression_brows"):
            self._clear_channel(channel)
        self._state["movement"].update(vector=(0.,0.),owner=None,ready_axes=(),boosts={},boost_input_amplitude=None,
            status="Waiting for fresh nose result" if tracking_loss else "Waiting for fresh neutral")

    def _expire_channels(self, channels):
        """A boost lease owns its actions, not the separately retained stick."""
        for channel in channels:
            if channel in ("mouth", "brows", "wink"):
                self._gates[channel].invalidate()
            elif channel == "movement":
                self._reset_movement_sources()
            elif channel in ("nose_x", "nose_y", "lip_x"):
                self._boosts["motion"].reset()
                self._clear_channel(channel)
                self._state["movement"].update(boosts={}, boost_input_amplitude=None)
            elif channel in ("expression_mouth", "expression_brows"):
                expression = channel.removeprefix("expression_")
                self._boosts[expression].reset()
                self._expression_motion[expression].invalidate()
                self._clear_channel(channel)
                if self._motion_owner == expression:
                    self._clear_channel("movement")
                    self._state["movement"].update(vector=(0.,0.), keys=(), owner=None, boosts={})

    def _send_sample(self, channel, held=(), taps=(), vector=None, ready=False, retain=False):
        if self._state["mode"]!="live" or not self.client: return True
        connection=self.client.snapshot()
        if connection.get("generation")!=self._last_client_generation:
            self._motion.invalidate(clear_centers=True);self._center_deadlines.clear()
            return self._output_failure()
        movement_channel=channel not in ("mouth","brows","wink")
        if movement_channel and connection.get("takeover_generation",0)!=self._last_takeover_generation:
            self._reset_movement_sources()
            return False
        if connection.get("expiry_generation",0)!=self._last_expiry_generation:
            expired=getattr(self.client,"drain_expired_channels",lambda:connection.get("expired_channels",()))()
            self._expire_channels(expired)
            if channel in expired or movement_channel and "movement" in expired:
                return False
        armed=channel in connection.get("armed_bindings",())
        guards={"expected_generation":connection.get("generation"),
                "expected_takeover_generation":connection.get("takeover_generation",0),
                "expected_expiry_generation":connection.get("expiry_generation",0)}
        if not armed:
            if not ready: return True
            if not self.client.send("arm",channel=channel,**guards): return self._guard_failure(connection,channel)
        fields={"channel":channel,"frame_seq":self._output_frame_sequence,"captured_at":self._last_result_captured,
                "held":sorted(set(held)),"taps":[{"id":f"{action_id}:{self._output_frame_sequence}","targets":list(actions)} for action_id,actions in taps]}
        if vector is not None: fields["vector"]=list(vector)
        if retain: fields["retain_on_tracking_loss"] = True
        if not self.client.send("sample",**fields,**guards): return self._guard_failure(connection,channel)
        selected=set(held)
        self._state["output_queued_count"]+=len(taps)+len(selected-self._last_v3_held.get(channel,set()))
        self._last_v3_held[channel]=selected
        return True

    def _guard_failure(self, previous, channel):
        current=self.client.snapshot()
        if current.get("generation")!=previous.get("generation"):
            self._motion.invalidate(clear_centers=True);self._center_deadlines.clear()
            return self._output_failure()
        if current.get("takeover_generation",0)!=previous.get("takeover_generation",0) or current.get("expiry_generation",0)!=previous.get("expiry_generation",0):
            if channel in ("mouth","brows","wink"):
                self._gates[channel].invalidate();self._clear_channel(channel)
            elif current.get("takeover_generation",0)!=previous.get("takeover_generation",0):
                self._reset_movement_sources()
            else:
                self._expire_channels(getattr(self.client,"drain_expired_channels",lambda:current.get("expired_channels",()))())
            return False
        return self._output_failure()

    def _output_failure(self):
        self._clear();self._state.update(mode="paused",last_error="Output unavailable; facial input paused.")
        return False

    def _tick_shortcuts(self, connection):
        chords=self._shortcut_chords()
        source=connection.get("source",{})
        def fire(keys,buttons):
            # Phone snapshot tokens carry KEY: prefix; physical observer is raw.
            keynames={k[4:] if k.startswith("KEY:") else k for k in keys}
            for target in self._shortcut_edges.update(chords,keynames,buttons): self.recenter(target)
        def prime(keys,buttons):
            keynames={k[4:] if k.startswith("KEY:") else k for k in keys}
            self._shortcut_edges.update(chords,keynames,buttons)
            self._shortcut_source_initialized=True
        local=self._shortcuts.snapshot()
        for event in self._shortcut_phone_events:
            if not self._shortcut_source_initialized:
                prime(set(event.get("keys",()))|set(local),event.get("buttons",()))
            else:
                fire(set(event.get("keys",()))|set(local),event.get("buttons",()))
        if not self._shortcut_source_initialized and connection.get("source_revision",0)>=0:
            prime(set(source.get("keys",()))|set(local),source.get("buttons",()))
        for event in self._shortcut_local_events:
            fire(set(source.get("keys",()))|set(event),source.get("buttons",()))
        fire(set(source.get("keys",()))|set(local),source.get("buttons",()))

    def _tick_extended(self, now, connection):
        self._tick_shortcuts(connection)
        if self._state["mode"]=="live":
            takeover=connection.get("takeover_generation",0)
            expiry=connection.get("expiry_generation",0)
            if self._last_takeover_generation is not None and takeover!=self._last_takeover_generation:
                self._reset_movement_sources()
            if self._last_expiry_generation is not None and expiry!=self._last_expiry_generation:
                expired=getattr(self.client,"drain_expired_channels",lambda:connection.get("expired_channels",()))()
                self._expire_channels(expired)
            self._last_takeover_generation=takeover;self._last_expiry_generation=expiry
        for axis,deadline in list(self._center_deadlines.items()):
            if axis in self._motion._centers:
                del self._center_deadlines[axis]
            elif now>deadline:
                self._motion._pending.pop(axis,None);del self._center_deadlines[axis]
                self._state["movement"]["status"]="Re-center failed: no fresh stable neutral; retry"
        if self._processed_movement_identity==self._last_result_identity or self._last_result_captured is None:
            return
        self._processed_movement_identity=self._last_result_identity
        if not 0<=now-self._last_result_captured<=.250: return
        # Original button gates are untouched when no new feature is configured.
        for expression in self.settings.active_expressions:
            if expression in self.settings.expression_movements and self.settings.expression_movements[expression].enabled: continue
            gate=self._gates[expression]
            actions=gate.update(self._last_samples[expression],now)
            if any(a in ("tap","hold") for a in actions):
                self._state["event_count"]+=1;self._state["expression_states"][expression]["event_count"]+=1
            button=self.settings.binding_for(expression).button
            held=(button,) if gate.active and gate.mode=="hold" else ()
            taps=(("button",(button,)),) if "tap" in actions else ()
            if self._last_samples[expression] is not None:
                if not self._send_sample(expression,held,taps,ready=gate.ready or gate.active): return
        sample=MotionSample(self._last_result_identity[-1],self._last_result_captured,self._last_features,self._last_result_generation)
        motion=self._motion.observe(sample,now)
        outputs={}
        for e,p in self._expression_motion.items():
            if not p.config.enabled or e not in self.settings.active_expressions: continue
            was_active=p.gate.active
            outputs[e]=p.observe(self._last_samples[e],now)
            if outputs[e].active and not was_active:
                self._state["event_count"]+=1;self._state["expression_states"][e]["event_count"]+=1
        for e,p in self._expression_motion.items():
            if e in outputs: self._gates[e]=p.gate
        blocked=self._state["mode"]=="live" and connection.get("source",{}).get("stick_blocked",False)
        if blocked:
            self._reset_movement_sources()
            return
        owner=next((e for e in EXPRESSIONS if e in outputs and outputs[e].active),None)
        if owner is None and self.settings.movement.enabled: owner="motion"
        # Interrupt lower-priority routes, so release cannot unexpectedly replay
        # a still-held lower expression or off-center nose.
        if owner and owner!="motion":
            for e,p in self._expression_motion.items():
                if p.config.enabled and e!=owner:
                    p.invalidate();self._boosts[e].reset();self._clear_channel("expression_"+e)
            if owner!=self._motion_owner: self._motion.invalidate()
            self._boosts["motion"].reset()
            self._clear_channel("nose_x");self._clear_channel("nose_y");self._clear_channel("lip_x")
        vector=(0.,0.);amplitude=0.;ready=False;route="stick"
        if owner=="motion":
            vector=motion.vector;amplitude=math.hypot(*motion.amplitudes.values())
            needed={a for a,c in (("x",self.settings.movement.horizontal),("y",self.settings.movement.vertical)) if c.enabled}
            ready=bool(needed) and needed<=set(motion.ready_axes)
            route=self.settings.movement.output
            if not ready and self._bridge_motion(now):
                self._boosts["motion"].reset()
                for channel in ("nose_x", "nose_y", "lip_x"):
                    self._clear_channel(channel)
                self._state["movement"].update(ready_axes=motion.ready_axes, raw=dict(motion.raw), boosts={}, boost_input_amplitude=None)
                return
        elif owner:
            result=outputs[owner];vector=result.vector;amplitude=result.amplitude;ready=result.ready or result.active
            route=self.settings.expression_movements[owner].output
        self._motion_owner=owner
        previous_boost_owner=getattr(self,"_last_boost_owner",None)
        if previous_boost_owner!=owner and previous_boost_owner is not None:
            self._clear_channel("nose_y" if previous_boost_owner=="motion" else "expression_"+previous_boost_owner)
            self._boosts[previous_boost_owner].reset()
        self._last_boost_owner=owner
        direction=direction_for_vector(vector,self._wasd_direction)
        self._wasd_direction=direction
        boosts=self._boosts[owner].observe(direction,amplitude,now) if owner else None
        boost_held=tuple(a for _,actions in boosts.holds for a in actions) if boosts else ()
        boost_taps=boosts.taps if boosts else ()
        self._state["movement"]["boost_tap_count"]+=len(boost_taps)
        if owner=="motion":
            for channel in ("nose_x","nose_y","lip_x"):
                held_for_channel=tuple(a for key,actions in boosts.holds if self._motion_boost_channel(key.split(":",1)[0])==channel for a in actions) if boosts else ()
                taps_for_channel=tuple((key,actions) for key,actions in boosts.taps if self._motion_boost_channel(key.split(":",1)[0])==channel) if boosts else ()
                # Independent axis readiness permits the other axis/boost to
                # continue while one axis collects a new center.
                channel_ready=("y" in motion.ready_axes if channel=="nose_y" else "x" in motion.ready_axes if channel==self._motion_boost_channel("R") else ready)
                if not self._send_sample(channel,held_for_channel,taps_for_channel,ready=channel_ready): return
        elif owner:
            if not self._send_sample("expression_"+owner,boost_held,boost_taps,ready=ready): return
        if owner == "motion":
            vector = self.settings.movement.map_output(vector)
        held=wasd_keys(vector,self._wasd_direction) if route=="wasd" else ()
        native_vector=(0.,0.) if route=="wasd" else vector
        retain = owner == "motion" and self._nose_only_movement(self.settings)
        if not self._send_sample("movement",held,vector=native_vector,ready=ready, retain=retain): return
        if retain and ready and any(vector):
            self._last_motion_claim = dict(vector=vector, keys=held, captured_at=self._last_result_captured,
                **{name:connection.get(name,0) for name in ("generation", "takeover_generation", "expiry_generation")})
        else:
            self._last_motion_claim = None
        self._state["movement"].update(vector=vector,amplitudes=dict(motion.amplitudes),raw=dict(motion.raw),ready_axes=motion.ready_axes,
            neutral_axes=motion.neutral_axes,status=motion.status if owner in ("motion",None) else f"{owner}: {'active' if outputs[owner].active else 'waiting for neutral'}",
            owner=owner,bridging=False,boosts={"holds":boost_held,"taps":tuple(a for _,actions in boost_taps for a in actions)},boost_input_amplitude=amplitude,keys=held)

    def _run(self):
        try:
            while not self._stop.is_set():
                now = time.monotonic()
                with self._lock:
                    for _ in range(2):
                        try:
                            generation, msg = self._mailbox.get_nowait()
                        except queue.Empty:
                            break
                        if generation == self._worker_generation:
                            self._observe(msg, now)
                    self._tick(now)
                    if self._telemetry:
                        try: self._telemetry.publish(self._state)
                        except Exception: pass
                    if self._worker and not self._worker.is_alive() and not self._worker_stop.is_set():
                        self._clear()
                        self._state["mode"] = "paused"
                        self._state["worker_status"] = "stopped"
                self._stop.wait(.015)
        except Exception as exc:
            with self._lock:
                self._clear()
                self._state["mode"] = "paused"
                self._state["last_error"] = f"Supervisor stopped safely: {exc}"

    def stop(self):
        self._stop.set()
        self._restart.set()
        with self._lock:
            self._clear()
            self._state["mode"] = "paused"
        if self.client:
            self.client.stop()
        self._shortcuts.stop()
        if self._telemetry:
            self._telemetry.close();self._telemetry=None
        if self._thread:
            self._thread.join(timeout=1)
        if self._manager:
            self._manager.join(timeout=4)
        else:
            self._close_output(self._output)
