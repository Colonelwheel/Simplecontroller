"""Explicit, independent expression-calibration saves; existing files stay intact."""
from __future__ import annotations

from dataclasses import asdict, dataclass, replace
from datetime import datetime, timezone
import json
import math
from pathlib import Path
from uuid import uuid4

from .settings import AppSettings, EXPRESSIONS, BUTTON_EXPRESSIONS, default_settings_path
from .movement import MovementConfig, ExpressionMovementConfig, config_from_dict


@dataclass(frozen=True)
class CalibrationProfile:
    name: str
    expression: str
    thresholds: dict
    created_at: str


def default_profiles_directory() -> Path:
    return default_settings_path().parent / "profiles"


def _validated_profile(profile: CalibrationProfile) -> CalibrationProfile:
    if not isinstance(profile.name, str) or not profile.name.strip() or len(profile.name) > 100:
        raise ValueError("Profile name must contain 1 to 100 characters.")
    if any(ord(character) < 32 for character in profile.name):
        raise ValueError("Profile name cannot contain control characters.")
    if profile.expression not in BUTTON_EXPRESSIONS:
        raise ValueError("Unsupported profile expression.")
    if not isinstance(profile.thresholds, dict):
        raise ValueError("Invalid profile thresholds.")
    thresholds = profile.thresholds
    on, off = thresholds.get("on"), thresholds.get("off")
    neutral = thresholds.get("neutral", off)
    if not all(isinstance(value, (int, float)) and not isinstance(value, bool)
               and 0 <= value <= 1 and math.isfinite(value) for value in (on, off, neutral)):
        # Check bounds before float conversion: malformed giant JSON integers
        # can otherwise make math.isfinite raise OverflowError.
        raise ValueError("Profile thresholds must be finite numbers between 0 and 1.")
    if not 0 <= off < on <= 1 or not 0 <= neutral < on:
        raise ValueError("Profile release and neutral thresholds must be below activation.")
    if not isinstance(profile.created_at, str):
        raise ValueError("Invalid profile creation time.")
    try:
        created = datetime.fromisoformat(profile.created_at)
        if created.tzinfo is None or created.utcoffset() is None:
            raise ValueError("Profile creation time must include a time zone.")
    except ValueError as exc:
        raise ValueError("Invalid profile creation time.") from exc
    return replace(profile, name=profile.name.strip(),
                   thresholds={"on": on, "off": off, "neutral": neutral})


def save_profile(settings: AppSettings, name: str, directory: Path | None = None, expression: str | None = None) -> Path:
    """Explicitly save valid selected-expression thresholds in a new file.

    Manual thresholds need not pass automatic sample-separation checks.
    Saving alone does not mutate settings or enable controller output.
    """
    settings.validate()
    now = datetime.now(timezone.utc)
    expression = settings.expression if expression is None else expression
    thresholds = settings.wink_thresholds if expression == "wink" else settings.thresholds.get(expression, {})
    profile = _validated_profile(CalibrationProfile(name, expression, dict(thresholds), now.isoformat()))
    base = Path(directory) if directory is not None else default_profiles_directory()
    destination = base / profile.expression
    destination.mkdir(parents=True, exist_ok=True)
    path = destination / f"{now.strftime('%Y%m%dT%H%M%S%fZ')}-{uuid4().hex}.json"
    with path.open("x", encoding="utf-8") as output:
        json.dump({"version": 1, "profile": {
            "name": profile.name, "expression": profile.expression,
            "thresholds": profile.thresholds, "created_at": profile.created_at,
        }}, output, indent=2)
        output.write("\n")
    return path


def load_profile(path: Path) -> CalibrationProfile:
    """Read and validate a calibration without altering its file."""
    try:
        document = json.loads(Path(path).read_text(encoding="utf-8"))
        if not isinstance(document, dict) or type(document.get("version")) is not int or document["version"] != 1:
            raise ValueError("Unsupported calibration profile version.")
        values = document["profile"]
        if not isinstance(values, dict):
            raise ValueError("Invalid calibration profile document.")
        return _validated_profile(CalibrationProfile(**values))
    except (KeyError, TypeError, AttributeError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError("Invalid calibration profile; existing file preserved.") from exc


def list_profiles(expression: str, directory: Path | None = None) -> list[Path]:
    """List valid matching calibrations newest first; ignore invalid files."""
    if expression not in BUTTON_EXPRESSIONS:
        raise ValueError("Unsupported profile expression.")
    base = Path(directory) if directory is not None else default_profiles_directory()
    profiles = []
    for path in (base / expression).glob("*.json"):
        try:
            profile = load_profile(path)
        except (ValueError, OSError):
            continue
        if profile.expression == expression:
            timestamp = datetime.fromisoformat(profile.created_at).timestamp()
            profiles.append((timestamp, path.name, path))
    return [path for _, _, path in sorted(profiles, reverse=True)]


def apply_profile(settings: AppSettings, profile: CalibrationProfile) -> AppSettings:
    """Replace one expression's calibration, preserving bindings and preferences."""
    settings.validate()
    profile = _validated_profile(profile)
    if profile.expression == "wink":
        return replace(settings, wink_thresholds=dict(profile.thresholds), wink_calibrated=True).validate()
    thresholds = {expression: dict(values) for expression, values in settings.thresholds.items()}
    thresholds[profile.expression] = dict(profile.thresholds)
    calibrated = settings.calibrated
    if profile.expression not in calibrated:
        calibrated += (profile.expression,)
    return replace(settings, thresholds=thresholds, calibrated=calibrated).validate()

def save_wink_profile(settings, name, directory=None):
    return save_profile(settings, name, directory, expression="wink")

@dataclass(frozen=True)
class MovementProfile:
    name: str
    source: str
    config: MovementConfig | ExpressionMovementConfig
    created_at: str

def default_movement_profiles_directory():
    return default_settings_path().parent / "movement-profiles"

def _validated_movement_profile(profile):
    # Reuse name/time validation without tying movement files to old thresholds.
    checked = _validated_profile(CalibrationProfile(profile.name, "mouth", {"on":.6,"off":.35}, profile.created_at))
    if profile.source not in ("motion", "mouth", "brows"):
        raise ValueError("Unsupported movement profile source.")
    expected = MovementConfig if profile.source == "motion" else ExpressionMovementConfig
    if not isinstance(profile.config, expected): raise ValueError("Invalid movement profile configuration.")
    profile.config.validate()
    return replace(profile, name=checked.name)

def save_movement_profile(settings, name, source="motion", directory=None):
    """Append-only response configuration; no session neutral origin is saved."""
    settings.validate()
    config = settings.movement if source == "motion" else settings.expression_movements.get(source)
    now = datetime.now(timezone.utc)
    profile = _validated_movement_profile(MovementProfile(name, source, config, now.isoformat()))
    base = Path(directory) if directory is not None else default_movement_profiles_directory()
    destination = base / profile.source
    destination.mkdir(parents=True, exist_ok=True)
    path = destination / f"{now.strftime('%Y%m%dT%H%M%S%fZ')}-{uuid4().hex}.json"
    with path.open("x", encoding="utf-8") as output:
        json.dump({"version":1,"movement_profile":{"name":profile.name,"source":profile.source,
            "config":asdict(profile.config),"created_at":profile.created_at}},output,indent=2)
        output.write("\n")
    return path

def load_movement_profile(path):
    try:
        document = json.loads(Path(path).read_text(encoding="utf-8"))
        if not isinstance(document,dict) or type(document.get("version")) is not int or document["version"] != 1:
            raise ValueError("Unsupported movement profile version.")
        values = dict(document["movement_profile"])
        source = values.get("source")
        values["config"] = config_from_dict(values["config"], expression=source != "motion")
        return _validated_movement_profile(MovementProfile(**values))
    except (KeyError,TypeError,AttributeError,UnicodeError,json.JSONDecodeError,OverflowError) as exc:
        raise ValueError("Invalid movement profile; existing file preserved.") from exc

def list_movement_profiles(source="motion", directory=None):
    if source not in ("motion","mouth","brows"): raise ValueError("Unsupported movement profile source.")
    base = Path(directory) if directory is not None else default_movement_profiles_directory()
    profiles = []
    for path in (base/source).glob("*.json"):
        try: profile = load_movement_profile(path)
        except (ValueError,OSError): continue
        if profile.source == source:
            profiles.append((datetime.fromisoformat(profile.created_at).timestamp(),path.name,path))
    return [p for _,_,p in sorted(profiles,reverse=True)]

def apply_movement_profile(settings, profile):
    settings.validate(); profile = _validated_movement_profile(profile)
    if profile.source == "motion": return replace(settings,movement=profile.config).validate()
    configs = dict(settings.expression_movements)
    configs[profile.source] = profile.config
    return replace(settings,expression_movements=configs).validate()
