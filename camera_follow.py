"""Pure Camera Follow state and priority logic for the PC receiver."""

from dataclasses import dataclass
import math
import threading
import time


@dataclass(frozen=True)
class CameraFollowSettings:
    enabled_by_default: bool = False
    movement_deadzone: float = 0.20
    manual_rs_deadzone: float = 0.15
    activation_delay_ms: int = 200
    manual_override_timeout_ms: int = 750
    strength: float = 0.55
    maximum_output: float = 0.45
    ramp_in_ms: int = 180
    ramp_out_ms: int = 120
    invert_direction: bool = False
    allow_sideways_steering: bool = True


def _clamp(value, low=-1.0, high=1.0):
    return max(low, min(high, float(value)))


def _magnitude(x, y):
    return math.hypot(x, y)


def _move_toward(current, target, maximum_change):
    if current < target:
        return min(current + maximum_change, target)
    return max(current - maximum_change, target)


class CameraFollowState:
    """Tracks stick sources and selects RS output without touching vgamepad."""

    def __init__(self, settings=None, now=None):
        self.settings = settings or CameraFollowSettings()
        self._lock = threading.RLock()
        self.enabled = self.settings.enabled_by_default
        self.left_x = 0.0
        self.left_y = 0.0
        self.manual_rs_x = 0.0
        self.manual_rs_y = 0.0
        self.macro_rs_x = 0.0
        self.macro_rs_y = 0.0
        self.macro_rs_active = False
        self.manual_rs_active = False
        self.manual_override_until = 0.0
        self.movement_started_at = None
        self.generated_x = 0.0
        self._last_target = 0.0
        self._last_step_at = time.monotonic() if now is None else float(now)

    def set_enabled(self, enabled, now=None):
        now = time.monotonic() if now is None else float(now)
        with self._lock:
            enabled = bool(enabled)
            changed = self.enabled != enabled
            if not changed:
                return False
            self.enabled = enabled
            self.generated_x = 0.0
            self._last_target = 0.0
            self._last_step_at = now
            self.movement_started_at = now if enabled and self._left_is_moving() else None
            return changed

    def set_left_stick(self, x, y, now=None):
        now = time.monotonic() if now is None else float(now)
        with self._lock:
            was_moving = self._left_is_moving()
            self.left_x = _clamp(x)
            self.left_y = _clamp(y)
            is_moving = self._left_is_moving()
            if is_moving and not was_moving:
                self.movement_started_at = now
            elif not is_moving:
                self.movement_started_at = None

    def set_manual_right_stick(self, x, y, now=None):
        now = time.monotonic() if now is None else float(now)
        with self._lock:
            was_active = self.manual_rs_active
            self.manual_rs_x = _clamp(x)
            self.manual_rs_y = _clamp(y)
            self.manual_rs_active = (
                _magnitude(self.manual_rs_x, self.manual_rs_y)
                > self.settings.manual_rs_deadzone
            )
            if was_active and not self.manual_rs_active:
                self.manual_override_until = (
                    now + self.settings.manual_override_timeout_ms / 1000.0
                )

    def set_macro_right_stick(self, x, y):
        with self._lock:
            self.macro_rs_x = _clamp(x)
            self.macro_rs_y = _clamp(y)
            self.macro_rs_active = _magnitude(self.macro_rs_x, self.macro_rs_y) > 0.0001

    def clear_inputs(self, now=None):
        now = time.monotonic() if now is None else float(now)
        with self._lock:
            self.left_x = 0.0
            self.left_y = 0.0
            self.manual_rs_x = 0.0
            self.manual_rs_y = 0.0
            self.macro_rs_x = 0.0
            self.macro_rs_y = 0.0
            self.macro_rs_active = False
            self.manual_rs_active = False
            self.manual_override_until = 0.0
            self.movement_started_at = None
            self.generated_x = 0.0
            self._last_target = 0.0
            self._last_step_at = now

    def step(self, now=None):
        """Return ``(x, y, source)`` using macro/manual/follow/neutral priority."""
        now = time.monotonic() if now is None else float(now)
        with self._lock:
            elapsed = max(0.0, now - self._last_step_at)
            self._last_step_at = now
            target = self._camera_target(now)

            # The activation-delay interval must not count as ramp time.
            if abs(target) > 0.0001 and abs(self._last_target) <= 0.0001:
                elapsed = 0.0

            ramp_ms = (
                self.settings.ramp_in_ms
                if abs(target) > abs(self.generated_x)
                else self.settings.ramp_out_ms
            )
            if ramp_ms <= 0:
                self.generated_x = target
            else:
                maximum_change = (
                    self.settings.maximum_output * elapsed * 1000.0 / ramp_ms
                )
                self.generated_x = _move_toward(
                    self.generated_x, target, maximum_change
                )
            self.generated_x = _clamp(
                self.generated_x,
                -self.settings.maximum_output,
                self.settings.maximum_output,
            )
            self._last_target = target

            if self.macro_rs_active:
                return self.macro_rs_x, self.macro_rs_y, "macro"
            if self.manual_rs_active:
                return self.manual_rs_x, self.manual_rs_y, "manual"
            if now < self.manual_override_until:
                return self.manual_rs_x, self.manual_rs_y, "manual_override"
            if self.enabled and abs(self.generated_x) > 0.0001:
                return self.generated_x, 0.0, "camera_follow"
            if not self.enabled:
                return self.manual_rs_x, self.manual_rs_y, "manual"
            return 0.0, 0.0, "neutral"

    def _left_is_moving(self):
        return _magnitude(self.left_x, self.left_y) > self.settings.movement_deadzone

    def _camera_target(self, now):
        if not self.enabled or self.macro_rs_active or self.manual_rs_active:
            return 0.0
        if now < self.manual_override_until or not self._left_is_moving():
            return 0.0
        if self.movement_started_at is None:
            return 0.0
        if (now - self.movement_started_at) * 1000.0 < self.settings.activation_delay_ms:
            return 0.0
        if (
            not self.settings.allow_sideways_steering
            and abs(self.left_y) <= self.settings.movement_deadzone
        ):
            return 0.0

        direction = -1.0 if self.settings.invert_direction else 1.0
        return _clamp(
            self.left_x * self.settings.strength * direction,
            -self.settings.maximum_output,
            self.settings.maximum_output,
        )
