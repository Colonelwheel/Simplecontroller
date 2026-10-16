"""Validated preferences; explicit saves create new revisions, never overwrite."""
from __future__ import annotations

from dataclasses import asdict, dataclass, field, replace
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
from uuid import uuid4
from .movement import MovementConfig, ExpressionMovementConfig, config_from_dict, chord_validate

BUTTONS = ("A", "B", "X", "Y", "LB", "RB", "START", "BACK", "UP", "DOWN", "LEFT", "RIGHT", "LS", "RS", "LT", "RT",
           "MOUSE_LEFT", "MOUSE_RIGHT", "SPACE")
OUTPUT_LABELS = {**{button: button for button in BUTTONS}, "MOUSE_LEFT": "Left click",
                 "MOUSE_RIGHT": "Right click", "SPACE": "Spacebar"}
DESKTOP_INPUTS = frozenset(("MOUSE_LEFT", "MOUSE_RIGHT", "SPACE"))
EXPRESSIONS = ("mouth", "brows")
BUTTON_EXPRESSIONS = (*EXPRESSIONS, "wink")
CAMERA_RESOLUTIONS = ("640x480", "1280x720", "1920x1080")

@dataclass(frozen=True)
class ExpressionBinding:
    mode: str = "tap"
    preset: str = "reliable"
    button: str = "B"

    def validate(self) -> ExpressionBinding:
        if self.mode not in ("tap", "hold") or self.preset not in ("reliable", "responsive") or self.button not in BUTTONS:
            raise ValueError("Choose a supported expression output, behavior and timing preset.")
        return self

@dataclass(frozen=True)
class AppSettings:
    expression: str = "mouth"
    mode: str = "tap"
    preset: str = "reliable"
    button: str = "A"
    player: str = "player1"
    fps: int = 15
    thresholds: dict = field(default_factory=lambda: {k: {"on": .6, "off": .35, "neutral": .35} for k in EXPRESSIONS})
    calibrated: tuple[str, ...] = ()
    model_path: str | None = None
    camera_source: str = "shared"
    camera_index: int = 0
    camera_resolution: str = "640x480"
    camera_roi: tuple[float, float, float, float] | None = None
    simultaneous: bool = False
    secondary_binding: ExpressionBinding = field(default_factory=ExpressionBinding)
    movement: MovementConfig = field(default_factory=MovementConfig)
    expression_movements: dict = field(default_factory=lambda: {e: ExpressionMovementConfig() for e in EXPRESSIONS})
    wink_enabled: bool = False
    wink_binding: ExpressionBinding = field(default_factory=ExpressionBinding)
    wink_thresholds: dict = field(default_factory=lambda: {"on": .6, "off": .35, "neutral": .35})
    wink_calibrated: bool = False
    disabled_expressions: tuple[str, ...] = ()
    live_toggle_keys: tuple[str, ...] = ()
    live_toggle_buttons: tuple[str, ...] = ()

    def with_wink_binding(self, **changes):
        return replace(self, wink_binding=replace(self.wink_binding, **changes).validate()).validate()

    @property
    def active_expressions(self) -> tuple[str, ...]:
        other = "brows" if self.expression == "mouth" else "mouth"
        selected = (self.expression, other) if self.simultaneous else (self.expression,)
        return tuple(e for e in selected if e not in self.disabled_expressions) + (("wink",) if self.wink_enabled else ())

    def binding_for(self, expression: str) -> ExpressionBinding:
        if expression == "wink": return self.wink_binding
        if expression not in EXPRESSIONS:
            raise ValueError("Unsupported expression.")
        return ExpressionBinding(self.mode, self.preset, self.button) if expression == self.expression else self.secondary_binding

    def with_binding(self, expression: str, **changes) -> AppSettings:
        if expression == "wink": return self.with_wink_binding(**changes)
        binding = replace(self.binding_for(expression), **changes).validate()
        return (replace(self, mode=binding.mode, preset=binding.preset, button=binding.button)
                if expression == self.expression else replace(self, secondary_binding=binding))

    def choose_primary(self, expression: str) -> AppSettings:
        selected = self.binding_for(expression)
        if expression == self.expression:
            return self
        return replace(self, expression=expression, mode=selected.mode, preset=selected.preset,
                       button=selected.button, secondary_binding=self.binding_for(self.expression))

    def validate(self) -> AppSettings:
        # Keyboard and phone Xbox chords are independent alternatives.
        chord_validate(self.live_toggle_keys, ())
        chord_validate((), self.live_toggle_buttons)
        if not isinstance(self.disabled_expressions, (tuple,list)) or len(set(self.disabled_expressions)) != len(self.disabled_expressions) or any(e not in EXPRESSIONS for e in self.disabled_expressions):
            raise ValueError("Invalid disabled expression list.")
        if not isinstance(self.movement, MovementConfig):
            raise ValueError("Invalid movement settings.")
        self.movement.validate()
        if not isinstance(self.expression_movements, dict) or set(self.expression_movements) != set(EXPRESSIONS):
            raise ValueError("Invalid expression movement settings.")
        for expression, config in self.expression_movements.items():
            if not isinstance(config, ExpressionMovementConfig): raise ValueError("Invalid movement configuration.")
            config.validate()
            if config.enabled and config.mode == "proportional" and config.full_score <= self.thresholds.get(expression, {}).get("on", 1):
                raise ValueError("Full-strength score must exceed expression activation.")
        chords = [(self.movement.shared_recenter_keys,self.movement.shared_recenter_buttons),
                  (self.movement.vertical.recenter_keys,self.movement.vertical.recenter_buttons),
                  (self.movement.horizontal.recenter_keys,self.movement.horizontal.recenter_buttons)]
        chords += [(c.recenter_keys,c.recenter_buttons) for c in self.expression_movements.values()]
        chords += [(self.live_toggle_keys, self.live_toggle_buttons)]
        if len({k for keys,_ in chords for k in keys}) > 96 or len({b for _,buttons in chords for b in buttons}) > 16:
            raise ValueError("Re-center shortcuts support at most ninety-six distinct keys and sixteen Xbox buttons.")
        if type(self.wink_enabled) is not bool or type(self.wink_calibrated) is not bool or not isinstance(self.wink_binding, ExpressionBinding):
            raise ValueError("Invalid wink settings.")
        self.wink_binding.validate()
        if not isinstance(self.wink_thresholds, dict): raise ValueError("Invalid wink thresholds.")
        on, off = self.wink_thresholds.get("on"), self.wink_thresholds.get("off")
        neutral = self.wink_thresholds.get("neutral", off)
        if not all(isinstance(v, (int, float)) and not isinstance(v, bool) and 0 <= v <= 1 and math.isfinite(v) for v in (on, off, neutral)) or not off < on or not neutral < on:
            raise ValueError("Wink release and neutral thresholds must be below activation.")
        if type(self.simultaneous) is not bool or not isinstance(self.secondary_binding, ExpressionBinding):
            raise ValueError("Invalid simultaneous-expression settings.")
        self.secondary_binding.validate()
        if self.expression not in EXPRESSIONS or self.mode not in ("tap", "hold"):
            raise ValueError("Choose a supported expression and tap/hold mode.")
        if self.preset not in ("reliable", "responsive") or self.button not in BUTTONS:
            raise ValueError("Unsupported timing preset or output.")
        if self.player not in ("player1", "player2") or self.fps not in (10, 15, 20, 30):
            raise ValueError("Choose Player 1/2 and a supported processing rate.")
        for expression in EXPRESSIONS:
            t = self.thresholds.get(expression, {})
            on, off = t.get("on"), t.get("off")
            neutral = t.get("neutral", off)  # Older settings retain their original neutral behavior.
            if not all(isinstance(x, (float, int)) and not isinstance(x, bool) and math.isfinite(x) for x in (on, off, neutral)):
                raise ValueError("Thresholds must be finite numbers.")
            if not 0 <= off < on <= 1:
                raise ValueError("Release threshold must be lower than activation threshold.")
            if not 0 <= neutral < on:
                raise ValueError("Neutral threshold must be lower than activation threshold.")
        if any(x not in EXPRESSIONS for x in self.calibrated):
            raise ValueError("Invalid calibrated expression.")
        if self.model_path is not None and not isinstance(self.model_path, str):
            raise ValueError("Invalid model path.")
        if self.camera_source not in ("shared", "webcam"):
            raise ValueError("Choose shared monitor frames or a standalone webcam.")
        if type(self.camera_index) is not int or not 0 <= self.camera_index <= 20:
            raise ValueError("Choose a webcam number between 0 and 20.")
        if self.camera_resolution not in CAMERA_RESOLUTIONS:
            raise ValueError("Choose a supported webcam capture resolution.")
        if self.camera_roi is not None:
            roi = self.camera_roi
            if (not isinstance(roi, (tuple, list)) or len(roi) != 4
                    or not all(isinstance(v, (int, float)) and not isinstance(v, bool)
                               and 0 <= v <= 1 and math.isfinite(v) for v in roi)):
                raise ValueError("Face region must contain four normalized coordinates.")
            x, y, width, height = roi
            if width < .02 or height < .02 or x + width > 1 + 1e-9 or y + height > 1 + 1e-9:
                raise ValueError("Face region must fit the camera view and span at least 2% on each side.")
        return self

def default_settings_path() -> Path:
    base = os.environ.get("LOCALAPPDATA")
    if not base:
        raise ValueError("LOCALAPPDATA is unavailable; choose an explicit settings path.")
    return Path(base) / "FacialGamingInput" / "settings.json"

def load_settings(path: Path | None = None) -> AppSettings:
    path = Path(path) if path is not None else default_settings_path()
    revisions = sorted(path.parent.glob(f"{path.stem}-*.json")) if path.parent.exists() else []
    if revisions:
        path = revisions[-1]
    elif not path.exists():
        return AppSettings()
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict) or raw.get("version") != 1:
            raise ValueError("Unsupported settings version.")
        values = raw["settings"]
        if not isinstance(values, dict):
            raise ValueError("Invalid settings document.")
        values["calibrated"] = tuple(values.get("calibrated", ()))
        values["disabled_expressions"] = tuple(values.get("disabled_expressions", ()))
        for name in ("live_toggle_keys", "live_toggle_buttons"):
            if name in values:
                if not isinstance(values[name], (list, tuple)):
                    raise ValueError("Invalid live toggle shortcut.")
                values[name] = tuple(values[name])
        if "secondary_binding" in values:
            if not isinstance(values["secondary_binding"], dict):
                raise ValueError("Invalid secondary expression binding.")
            values["secondary_binding"] = ExpressionBinding(**values["secondary_binding"])
        if "wink_binding" in values:
            values["wink_binding"] = ExpressionBinding(**values["wink_binding"])
        if "movement" in values:
            values["movement"] = config_from_dict(values["movement"])
        if "expression_movements" in values:
            if not isinstance(values["expression_movements"], dict): raise ValueError("Invalid expression movement settings.")
            values["expression_movements"] = {e: config_from_dict(c, expression=True) for e,c in values["expression_movements"].items()}
        if values.get("camera_roi") is not None:
            if not isinstance(values["camera_roi"], (tuple, list)):
                raise ValueError("Invalid saved face region.")
            values["camera_roi"] = tuple(values["camera_roi"])
        return AppSettings(**values).validate()
    except (KeyError, TypeError, AttributeError) as exc:
        raise ValueError("Invalid settings document; existing file preserved.") from exc

def save_settings(settings: AppSettings, path: Path | None = None) -> Path:
    settings.validate()
    path = Path(path) if path is not None else default_settings_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    revision = path.with_name(f"{path.stem}-{stamp}-{uuid4().hex[:8]}.json")
    with revision.open("x", encoding="utf-8") as out:
        json.dump({"version": 1, "settings": asdict(settings)}, out, indent=2)
        out.write("\n")
    return revision
