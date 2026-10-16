"""Pure, bounded movement math. No camera, files, hooks or native outputs."""
from __future__ import annotations

from dataclasses import dataclass, field
import math
from .detection import ExpressionGate, Sample

DIRECTIONS = ("U", "UR", "R", "DR", "D", "DL", "L", "UL")
KEYS = tuple("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") + tuple(f"F{i}" for i in range(1, 25)) + (
    "SPACE", "ENTER", "TAB", "ESC", "BACKSPACE", "SHIFT", "CTRL", "ALT", "LCTRL", "RCTRL",
    "LSHIFT", "RSHIFT", "LALT", "RALT", "UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PAGEUP", "PAGEDOWN", "INSERT", "DELETE", "WIN", "LWIN", "RWIN", "CAPSLOCK")
XBOX_BUTTONS = ("A", "B", "X", "Y", "LB", "RB", "START", "BACK", "UP", "DOWN", "LEFT", "RIGHT", "LS", "RS", "LT", "RT")
ACTIONS = XBOX_BUTTONS + ("MOUSE_LEFT", "MOUSE_RIGHT", "SPACE") + tuple("KEY:" + k for k in KEYS)

def finite(value, low=-1e6, high=1e6):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and low <= value <= high and math.isfinite(value)

def chord_validate(keys, buttons):
    if not isinstance(keys, (tuple, list)) or not isinstance(buttons, (tuple, list)):
        raise ValueError("Shortcut components must be lists of canonical keys/buttons.")
    if len(keys) + len(buttons) > 8 or len(set(keys)) != len(keys) or len(set(buttons)) != len(buttons):
        raise ValueError("Shortcut supports up to eight distinct components.")
    if any(k not in KEYS for k in keys) or any(b not in XBOX_BUTTONS for b in buttons):
        raise ValueError("Unsupported shortcut key or Xbox button.")

@dataclass(frozen=True)
class DirectionAnchor:
    threshold: float = .1
    direction: str = "DL"
    enabled: bool = True
    path: str = "cw"

    def validate(self):
        if not finite(self.threshold, 0, 1) or self.direction not in DIRECTIONS or type(self.enabled) is not bool or self.path not in ("cw", "ccw"):
            raise ValueError("Invalid direction threshold/path.")
        return self

@dataclass(frozen=True)
class BoostStage:
    direction: str = "U"
    level: str = "regular"
    threshold: float = .5
    release_threshold: float = .4
    actions: tuple[str, ...] = ("A",)
    mode: str = "hold"
    retain: bool = True
    enabled: bool = True
    inherit: bool = False

    @property
    def action_id(self):
        return f"{self.direction}:{self.level}"

    def validate(self):
        if self.direction not in DIRECTIONS or self.level not in ("regular", "super", "outer"):
            raise ValueError("Invalid boost direction/stage.")
        if not finite(self.threshold, 0, 100) or not finite(self.release_threshold, 0, 100) or not self.release_threshold < self.threshold:
            raise ValueError("Boost release must be below activation; units are input amplitude.")
        if self.mode not in ("tap", "hold") or any(type(v) is not bool for v in (self.retain, self.enabled, self.inherit)):
            raise ValueError("Invalid boost behavior.")
        if not isinstance(self.actions, (tuple, list)) or not (1 if self.enabled else 0) <= len(self.actions) <= 8 or len(set(self.actions)) != len(self.actions) or any(a not in ACTIONS for a in self.actions):
            raise ValueError("Enabled boost stages need one to eight approved key/button actions; unused stages may be empty.")
        return self

def validate_boosts(stages):
    if not isinstance(stages, (tuple, list)) or len(stages) > 24 or any(not isinstance(s, BoostStage) for s in stages):
        raise ValueError("Invalid boost stages.")
    ids = set()
    for stage in stages:
        stage.validate()
        if stage.action_id in ids:
            raise ValueError("One boost per direction and stage is allowed.")
        ids.add(stage.action_id)
    for direction in DIRECTIONS:
        enabled = sorted((s for s in stages if s.direction == direction and s.enabled), key=lambda s: ("regular", "super", "outer").index(s.level))
        if any(a.threshold >= b.threshold for a, b in zip(enabled, enabled[1:])):
            raise ValueError("Boost thresholds must increase by stage.")

@dataclass(frozen=True)
class AxisConfig:
    enabled: bool = False
    source: str = "nose"
    deadzone: float = .03
    negative_range: float = .20
    positive_range: float = .20
    smoothing_ms: float = 60
    recenter_keys: tuple[str, ...] = ()
    recenter_buttons: tuple[str, ...] = ()
    orientation_degrees: float = 0
    inverted: bool = False
    deadzone_lower: float | None = None
    deadzone_upper: float | None = None

    @property
    def deadzone_bounds(self):
        # Old settings keep their symmetric behavior until explicitly edited.
        return (-self.deadzone if self.deadzone_lower is None else self.deadzone_lower,
                self.deadzone if self.deadzone_upper is None else self.deadzone_upper)

    def validate(self):
        if type(self.enabled) is not bool or type(self.inverted) is not bool or self.source not in ("nose", "lip", "cheek", "combined"):
            raise ValueError("Invalid motion axis/source.")
        if not finite(self.deadzone, 0, 2):
            raise ValueError("Invalid legacy motion deadzone.")
        lower, upper = self.deadzone_bounds
        if not finite(lower, -2, 0) or not finite(upper, 0, 2):
            raise ValueError("Deadzone lower must be between -2 and 0; upper must be between 0 and 2.")
        for label, travel, boundary in (("Negative", self.negative_range, -lower), ("Positive", self.positive_range, upper)):
            if not finite(travel, .0001, 10) or travel <= boundary:
                raise ValueError(f"{label} sensitivity / full-output travel must exceed its deadzone magnitude ({boundary:g}).")
        if not finite(self.smoothing_ms, 0, 500) or not finite(self.orientation_degrees, -180, 180):
            raise ValueError("Invalid smoothing or axis tilt.")
        chord_validate(self.recenter_keys, self.recenter_buttons)
        return self

@dataclass(frozen=True)
class MovementConfig:
    output: str = "stick"
    vertical: AxisConfig = field(default_factory=AxisConfig)
    horizontal: AxisConfig = field(default_factory=AxisConfig)
    tilt_degrees: float = 0
    boosts: tuple[BoostStage, ...] = ()
    shared_recenter_keys: tuple[str, ...] = ()
    shared_recenter_buttons: tuple[str, ...] = ()
    vertical_to_horizontal: bool = False
    invert_vertical_output: bool = False

    @property
    def enabled(self):
        return self.vertical.enabled or self.horizontal.enabled

    def validate(self):
        if type(self.vertical_to_horizontal) is not bool or type(self.invert_vertical_output) is not bool:
            raise ValueError("Invalid vertical output mapping.")
        if self.output not in ("stick", "wasd") or not finite(self.tilt_degrees, -180, 180):
            raise ValueError("Invalid movement route or tilt.")
        if not isinstance(self.vertical, AxisConfig) or not isinstance(self.horizontal, AxisConfig):
            raise ValueError("Invalid axis configuration.")
        self.vertical.validate(); self.horizontal.validate()
        if self.vertical.source != "nose":
            raise ValueError("Vertical movement uses nose motion.")
        chord_validate(self.shared_recenter_keys, self.shared_recenter_buttons)
        validate_boosts(self.boosts)
        return self

    def map_output(self, vector):
        """Route processed Y after input-direction boosts, keeping Y's units."""
        if not self.vertical_to_horizontal:
            return vector
        x, y = vector
        return (max(-1., min(1., x + (y if self.invert_vertical_output else -y))), 0.)

def default_anchors():
    # Short paths for Tyler's example; the opposite DR->UL transition has an
    # explicit clockwise half-circle. Every segment remains editable.
    return tuple(DirectionAnchor((i + 1) / 10, d, path="ccw" if i<2 else "cw")
                 for i,d in enumerate(("DL", "D", "DR", "UL", "U", "UR")))

@dataclass(frozen=True)
class ExpressionMovementConfig:
    enabled: bool = False
    output: str = "stick"
    mode: str = "fixed"
    direction: str = "U"
    strength_percent: float = 100
    full_score: float = .9
    anchors: tuple[DirectionAnchor, ...] = field(default_factory=default_anchors)
    relaxation: str = "follow"
    rise_tolerance: float = .01
    rise_confirm_ms: float = 50
    boosts: tuple[BoostStage, ...] = ()
    recenter_keys: tuple[str, ...] = ()
    recenter_buttons: tuple[str, ...] = ()

    def validate(self):
        if type(self.enabled) is not bool or self.output not in ("stick", "wasd") or self.mode not in ("fixed", "proportional", "step", "sweep") or self.direction not in DIRECTIONS:
            raise ValueError("Invalid expression movement mode.")
        if not finite(self.strength_percent, 0, 100) or not finite(self.full_score, .0001, 1) or self.relaxation not in ("follow", "retain"):
            raise ValueError("Invalid movement strength/relaxation.")
        if not finite(self.rise_tolerance, 0, .5) or not finite(self.rise_confirm_ms, 0, 1000):
            raise ValueError("Invalid direction-rise confirmation.")
        if not isinstance(self.anchors, (tuple, list)) or not 1 <= len(self.anchors) <= 32 or any(not isinstance(a, DirectionAnchor) for a in self.anchors):
            raise ValueError("Invalid direction anchors.")
        for anchor in self.anchors: anchor.validate()
        active = [a for a in self.anchors if a.enabled]
        if not active or any(a.threshold >= b.threshold for a, b in zip(active, active[1:])):
            raise ValueError("Enabled direction thresholds must strictly increase.")
        validate_boosts(self.boosts)
        chord_validate(self.recenter_keys, self.recenter_buttons)
        return self

def config_from_dict(values, expression=False):
    if not isinstance(values, dict): raise ValueError("Invalid movement settings document.")
    values = dict(values)
    def sequence(value):
        if not isinstance(value,(tuple,list)): raise ValueError("Movement lists cannot be strings or scalar values.")
        return tuple(value)
    boosts=[]
    for stage in sequence(values.get("boosts",())):
        if not isinstance(stage,dict): raise ValueError("Invalid boost document.")
        stage=dict(stage)
        if "actions" in stage: stage["actions"]=sequence(stage["actions"])
        boosts.append(BoostStage(**stage))
    values["boosts"] = tuple(boosts)
    for key in ("shared_recenter_keys", "shared_recenter_buttons", "recenter_keys", "recenter_buttons"):
        if key in values: values[key] = sequence(values[key])
    if expression:
        if "anchors" in values: values["anchors"] = tuple(DirectionAnchor(**a) for a in sequence(values["anchors"]))
        return ExpressionMovementConfig(**values).validate()
    for key in ("vertical", "horizontal"):
        if key in values:
            if not isinstance(values[key],dict): raise ValueError("Invalid motion axis document.")
            axis = dict(values[key])
            for chord in ("recenter_keys", "recenter_buttons"):
                if chord in axis: axis[chord] = sequence(axis[chord])
            values[key] = AxisConfig(**axis)
    return MovementConfig(**values).validate()

def direction_vector(direction, strength=1.):
    angle = math.pi / 2 - DIRECTIONS.index(direction) * math.pi / 4
    return tuple(0. if abs(v) < 1e-12 else v for v in (math.cos(angle)*strength, math.sin(angle)*strength))

def circular_cap(x, y):
    length = math.hypot(x, y)
    return (x / max(1., length), y / max(1., length))

def vector_direction(vector, previous=None, hysteresis_degrees=5):
    x, y = vector
    if math.hypot(x, y) < 1e-8: return None
    clockwise = (math.pi/2 - math.atan2(y, x)) % (2*math.pi)
    if previous in DIRECTIONS:
        delta = (clockwise - DIRECTIONS.index(previous)*math.pi/4 + math.pi) % (2*math.pi) - math.pi
        if abs(delta) <= math.pi/8 + math.radians(hysteresis_degrees): return previous
    return DIRECTIONS[int(math.floor(clockwise/(math.pi/4)+.5)) % 8]

def wasd_keys(vector, previous=None):
    direction = vector_direction(vector, previous)
    return tuple("KEY:" + k for k in ({"U": ("W",), "UR": ("W", "D"), "R": ("D",), "DR": ("S", "D"), "D": ("S",), "DL": ("S", "A"), "L": ("A",), "UL": ("W", "A")}.get(direction, ())))

direction_for_vector = vector_direction

@dataclass(frozen=True)
class MotionSample:
    sequence: int
    captured_at: float
    features: dict
    generation: tuple = (0, 0)

@dataclass(frozen=True)
class MotionResult:
    vector: tuple[float, float] = (0., 0.)
    amplitudes: dict = field(default_factory=lambda: {"x": 0., "y": 0.})
    ready_axes: tuple[str, ...] = ()
    neutral_axes: tuple[str, ...] = ()
    status: str = "Re-center required"
    raw: dict = field(default_factory=dict)

class MotionProcessor:
    """Center acquisition is explicit. All origins live only in this session."""
    def __init__(self, config):
        self.config = config.validate()
        self._generation = None
        self._sequence = self._captured = None
        self._centers = {}
        self._pending = {}
        self._filtered = {"x": 0., "y": 0.}
        self._neutral = {}; self._ready = set()

    def request_center(self, axes=("x", "y")):
        for axis in axes:
            if axis not in ("x", "y"): raise ValueError("Unknown movement axis.")
            self._pending[axis] = []
            self._centers.pop(axis, None); self._ready.discard(axis)
            self._neutral.pop(axis, None); self._filtered[axis] = 0.

    def invalidate(self, clear_centers=False, preserve_center=None, tracking_loss=False):
        if preserve_center is not None: clear_centers = not preserve_center
        self._ready.clear(); self._neutral.clear()
        self._filtered = {"x": 0., "y": 0.}
        # Keep accepted frame identity across pauses/loss/takeover. Nose can
        # resume without neutral, but an old observation cannot rearm it.
        if clear_centers:
            self._sequence = self._captured = None
        for samples in self._pending.values(): samples.clear()
        if clear_centers:
            self._centers.clear(); self._pending.clear()

    @staticmethod
    def _features(features, axis, source):
        # nose coordinates and face_scale share image-height units; optional
        # relative features are already normalized to facial landmark distance.
        needed = ("nose_x", "nose_y", "face_scale", "face_roll") if source == "nose" else (source + "_x",)
        if not isinstance(features, dict) or any(not finite(features.get(k)) for k in needed): return None
        if source == "nose":
            if not .0001 <= features["face_scale"] <= 4: return None
            return (features["nose_x"], features["nose_y"], features["face_scale"], features["face_roll"])
        return (features[source + "_x"], 0., 1., 0.)

    def _displacement(self, point, center, axis, cfg):
        dx, dy = (point[0]-center[0])/center[2], (point[1]-center[1])/center[2]
        angle = center[3] + math.radians(self.config.tilt_degrees + cfg.orientation_degrees) if cfg.source == "nose" else 0.
        rotated_x, rotated_y = math.cos(angle)*dx+math.sin(angle)*dy, -math.sin(angle)*dx+math.cos(angle)*dy
        raw = rotated_x if axis == "x" else -rotated_y
        return -raw if cfg.inverted else raw

    def displacement(self, features):
        """Read-only values for tuning while output is paused; never arms axes."""
        values = {}
        for axis, cfg in (("x", self.config.horizontal), ("y", self.config.vertical)):
            center = self._centers.get(axis)
            point = self._features(features, axis, cfg.source)
            if cfg.enabled and center is not None and point is not None:
                values[axis] = self._displacement(point, center, axis, cfg)
        return values

    def observe(self, sample, now):
        if sample is None or type(sample.sequence) is not int or sample.sequence < 0 or not finite(now, 0, 1e12) or not finite(sample.captured_at, 0, 1e12) or not 0 <= now-sample.captured_at <= .250:
            self.invalidate(tracking_loss=True); return MotionResult(status="Missing/stale movement features")
        if self._generation is not None and self._generation != sample.generation:
            self.invalidate(clear_centers=True)
        self._generation = sample.generation
        if self._sequence is not None and sample.sequence <= self._sequence:
            return self._result(status="Waiting for a fresh movement frame")
        if self._captured is not None and sample.captured_at <= self._captured:
            self.invalidate(tracking_loss=True)
            return MotionResult(status="Waiting for increasing movement timestamps")
        if self._captured is not None and sample.captured_at-self._captured > .250:
            self.invalidate(tracking_loss=True)
        dt = 0 if self._captured is None else sample.captured_at-self._captured
        self._sequence, self._captured = sample.sequence, sample.captured_at
        amplitudes = {}; raw_values = {}; neutral_axes = []
        for axis, cfg in (("x", self.config.horizontal), ("y", self.config.vertical)):
            if not cfg.enabled: continue
            point = self._features(sample.features, axis, cfg.source)
            if point is None:
                self._ready.discard(axis); self._neutral.pop(axis, None); self._filtered[axis] = 0.; self._pending.get(axis, []).clear()
                continue
            if axis in self._pending:
                history = self._pending[axis]
                # Bounded temporary center samples, no image or history recording.
                if history and math.hypot(point[0]-history[0][1][0], point[1]-history[0][1][1])/history[0][1][2] > .03:
                    history.clear()
                history.append((sample.captured_at, point)); del history[:-30]
                if len(history) >= 3 and history[-1][0]-history[0][0] >= .100-1e-9:
                    self._centers[axis] = tuple(sum(p[i] for _, p in history)/len(history) for i in range(4))
                    del self._pending[axis]
                    self._ready.add(axis)
                continue
            center = self._centers.get(axis)
            if center is None: continue
            raw = self._displacement(point, center, axis, cfg)
            raw_values[axis] = raw
            if cfg.source == "nose":
                # Explicit centering supplies zero; nose movement needs no
                # neutral rearming after loss, pause, expiry or takeover.
                self._ready.add(axis)
            travel = cfg.positive_range if raw >= 0 else cfg.negative_range
            amplitudes[axis] = raw/travel
            lower, upper = cfg.deadzone_bounds
            is_neutral = lower <= raw <= upper
            if is_neutral:
                neutral_axes.append(axis)
                self._neutral.setdefault(axis, sample.captured_at)
                if sample.captured_at-self._neutral[axis] >= .100-1e-9: self._ready.add(axis)
            else: self._neutral.pop(axis, None)
            boundary = upper if raw >= 0 else -lower
            value = 0. if is_neutral else math.copysign(min(1., (abs(raw)-boundary)/(travel-boundary)), raw)
            alpha = 1. if cfg.smoothing_ms == 0 else 1-math.exp(-dt/(cfg.smoothing_ms/1000))
            self._filtered[axis] += alpha*(value-self._filtered[axis])
            if is_neutral: self._filtered[axis] = 0.  # Immediate intentional stop.
        return self._result(amplitudes, tuple(neutral_axes), raw=raw_values)

    def _result(self, amplitudes=None, neutral=(), status=None, raw=None):
        vector = circular_cap(*(self._filtered[a] if a in self._ready else 0. for a in ("x", "y")))
        return MotionResult(vector, amplitudes or {"x":0., "y":0.}, tuple(sorted(self._ready)), neutral,
            status or ("Ready" if self._ready else "Collecting stable neutral" if self._pending else
                       "Waiting for fresh nose tracking" if any(
                           axis in self._centers and cfg.enabled and cfg.source == "nose"
                           for axis, cfg in (("x", self.config.horizontal), ("y", self.config.vertical))) else
                       "Waiting for fresh neutral" if self._centers else "Re-center required"), raw or {})

@dataclass(frozen=True)
class ExpressionMovementResult:
    vector: tuple[float, float] = (0., 0.)
    amplitude: float = 0.
    ready: bool = False
    active: bool = False

class ExpressionMovementProcessor:
    def __init__(self, config, on=.6, off=.35, neutral=None, preset="reliable"):
        self.config = config.validate()
        if config.mode == "proportional" and config.full_score <= on:
            raise ValueError("Full-strength score must exceed activation.")
        self.gate = ExpressionGate(on, off, preset, "hold", neutral)
        self._retained = None; self._last_score = None; self._rise_since = None; self._rise_count = 0
        self._last_processed = None
        self._last_result = ExpressionMovementResult()

    def invalidate(self):
        self.gate.invalidate()
        self._retained = self._last_score = self._rise_since = None; self._rise_count = 0
        self._last_processed = None; self._last_result = ExpressionMovementResult()

    def observe(self, sample, now):
        if sample is not None and not finite(sample.score,0,1): sample=None
        self.gate.update(sample, now)
        if not self.config.enabled or not self.gate.active:
            self._retained = self._last_score = self._rise_since = None; self._rise_count = 0
            self._last_processed = None
            self._last_result = ExpressionMovementResult(ready=self.gate.ready)
            return self._last_result
        identity = (sample.generation, sample.sequence)
        if identity == self._last_processed: return self._last_result
        self._last_processed = identity
        score = sample.score
        if self.config.mode in ("fixed", "proportional"):
            magnitude = self.config.strength_percent/100
            if self.config.mode == "proportional": magnitude *= max(0., min(1., (score-self.gate.on)/(self.config.full_score-self.gate.on)))
            vector = direction_vector(self.config.direction, magnitude)
        else:
            vector = self._mapped(score)
            if self.config.relaxation == "retain":
                if self._retained is None:
                    self._retained, self._last_score = vector, score
                elif score > self._last_score+self.config.rise_tolerance:
                    if self._rise_since is None: self._rise_since = sample.captured_at; self._rise_count = 0
                    self._rise_count += 1
                    if self._rise_count >= 2 and sample.captured_at-self._rise_since >= self.config.rise_confirm_ms/1000-1e-9:
                        self._retained, self._last_score = vector, score
                        self._rise_since = None; self._rise_count = 0
                else:
                    self._rise_since = None; self._rise_count = 0
                    # A real descent sets a new rise baseline, while keeping
                    # the selected direction. Subsequent confirmed ascent can
                    # choose an earlier anchor without going neutral first.
                    if score < self._last_score: self._last_score = score
                vector = self._retained
        self._last_result = ExpressionMovementResult(vector, score, self.gate.ready, self.gate.active)
        return self._last_result

    def _mapped(self, score):
        anchors = [a for a in self.config.anchors if a.enabled]
        if score <= anchors[0].threshold: return direction_vector(anchors[0].direction)
        for left, right in zip(anchors, anchors[1:]):
            if score < right.threshold:
                if self.config.mode == "step": return direction_vector(left.direction)
                a = math.pi/2-DIRECTIONS.index(left.direction)*math.pi/4
                b = math.pi/2-DIRECTIONS.index(right.direction)*math.pi/4
                delta = -((a-b) % (2*math.pi)) if left.path == "cw" else (b-a) % (2*math.pi)
                angle = a + delta*(score-left.threshold)/(right.threshold-left.threshold)
                return (math.cos(angle), math.sin(angle))
        return direction_vector(anchors[-1].direction)

@dataclass(frozen=True)
class BoostResult:
    holds: tuple = ()
    taps: tuple = ()

class BoostProcessor:
    """Stable claims; only the final observed tap stage enters after a jump."""
    def __init__(self, stages):
        validate_boosts(stages); self.stages = tuple(stages); self.reset()

    def reset(self, directions=None):
        if directions is not None:
            self._entered={key for key in self._entered if key.split(":",1)[0] not in directions}
            self._exit_since={key:t for key,t in self._exit_since.items() if key.split(":",1)[0] not in directions}
            return
        self._entered = set(); self._exit_since = {}; self._last_time = None

    def observe(self, direction, amplitude, now):
        if direction not in DIRECTIONS or not finite(amplitude, 0, 100) or not finite(now, 0, 1e12) or (self._last_time is not None and now < self._last_time):
            self.reset(); return BoostResult()
        self._last_time = now
        applicable = [s for s in self.stages if s.enabled and (s.direction == direction or (len(direction)==2 and s.inherit and s.direction in direction))]
        # Inherited cardinals have one stable ID each, never duplicate taps.
        applicable_ids = {s.action_id for s in applicable}
        for stage in self.stages:
            key = stage.action_id
            if key in self._entered and (key not in applicable_ids or amplitude <= stage.release_threshold):
                self._exit_since.setdefault(key, now)
                if now-self._exit_since[key] >= .050-1e-9:
                    self._entered.discard(key); self._exit_since.pop(key, None)
            elif key in self._entered:
                self._exit_since.pop(key, None)
        final_by_direction = {}
        for stage in applicable:
            if amplitude >= stage.threshold or stage.action_id in self._entered:
                rank = ("regular", "super", "outer").index(stage.level)
                if stage.direction not in final_by_direction or rank > final_by_direction[stage.direction][0]: final_by_direction[stage.direction] = (rank, stage)
        holds = []; taps = []
        for stage in applicable:
            key = stage.action_id
            crossed = amplitude >= stage.threshold and key not in self._entered
            if crossed: self._entered.add(key)
            final = final_by_direction.get(stage.direction, (None,None))[1]
            if stage.mode == "tap" and crossed and final is stage: taps.append((key, tuple(stage.actions)))
            if stage.mode == "hold" and key in self._entered and (stage.retain or final is stage): holds.append((key, tuple(stage.actions)))
        return BoostResult(tuple(holds), tuple(taps))
