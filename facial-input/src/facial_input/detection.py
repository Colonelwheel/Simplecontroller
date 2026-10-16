"""Deterministic expression gate, independent of camera/native inference/output."""
from __future__ import annotations

from dataclasses import dataclass
import math

@dataclass(frozen=True)
class Sample:
    sequence: int
    captured_at: float
    score: float | None
    generation: tuple[object, ...] = (0, 0)

class ExpressionGate:
    def __init__(self, on=.6, off=.35, preset="reliable", mode="tap", neutral=None):
        neutral = off if neutral is None else neutral
        if not (0 <= off < on <= 1 and 0 <= neutral < on) or preset not in ("reliable", "responsive") or mode not in ("tap", "hold"):
            raise ValueError("Invalid expression gate configuration")
        self.on, self.off, self.neutral = on, off, neutral
        self.confirm = .150 if preset == "reliable" else .050
        self.mode = mode
        self.reset()

    def reset(self):
        self.ready = False
        self.active = False
        self._neutral_since = None
        self._high_since = None
        self._low_since = None
        self._high_count = 0
        self._last_sequence = None
        self._generation = None
        self._last_capture = None

    def invalidate(self) -> list[str]:
        actions = ["release"] if self.active and self.mode == "hold" else []
        self.reset()
        return actions

    def update(self, sample: Sample | None, now: float) -> list[str]:
        if (sample is None or sample.score is None or not math.isfinite(sample.score)
            or not 0 <= sample.score <= 1 or not 0 <= now - sample.captured_at <= .250):
            return self.invalidate()
        actions = []
        if self._generation is not None and sample.generation != self._generation:
            actions = self.invalidate()
        if self._last_capture is not None and sample.captured_at - self._last_capture > .250:
            actions += self.invalidate()
        self._generation = sample.generation
        if self._last_sequence is not None and sample.sequence <= self._last_sequence:
            # Reusing one frame can never complete confirmation or neutral recovery.
            return actions
        if self._last_capture is not None and sample.captured_at <= self._last_capture:
            return actions + self.invalidate()
        self._last_sequence = sample.sequence
        self._last_capture = sample.captured_at
        t = sample.captured_at
        value = sample.score
        if not self.ready:
            if value <= self.neutral:
                if self._neutral_since is None:
                    self._neutral_since = t
            else:
                self._neutral_since = None
        neutral_complete = self._neutral_since is not None and t - self._neutral_since >= .100 - 1e-9
        if not self.ready and not self.active:
            if neutral_complete:
                self.ready = True
            return actions
        if self.active:
            if self.mode == "tap":
                if neutral_complete:
                    self.active = False
                    self.ready = True
                    self._high_since = None
                    self._high_count = 0
            elif value <= self.off:
                if self._low_since is None:
                    self._low_since = t
                if self.mode == "hold" and t - self._low_since >= .050 - 1e-9:
                    actions.append("release")
                    self.active = False
                    self.ready = False
                    self._high_since = None
                    self._high_count = 0
            else:
                self._low_since = None
            return actions
        if value >= self.on:
            if self._high_since is None:
                self._high_since = t
            self._high_count += 1
            if self._high_count >= 2 and t - self._high_since >= self.confirm - 1e-9:
                self.active = True
                self.ready = False
                self._neutral_since = self._low_since = None
                actions.append("tap" if self.mode == "tap" else "hold")
        else:
            self._high_since = None
            self._high_count = 0
        return actions

class Calibration:
    """Only expression scalars, retained in memory until explicit settings save."""
    def __init__(self):
        self.samples = {k: [] for k in ("neutral", "mouth", "brows")}
        self.stage = None

    def begin(self, stage):
        if stage not in self.samples:
            raise ValueError("Unknown calibration stage")
        self.stage = stage
        self.samples[stage] = []

    def observe(self, scores):
        if self.stage and all(isinstance(scores.get(k), (float, int)) and math.isfinite(scores[k]) and 0 <= scores[k] <= 1 for k in ("mouth", "brows")):
            if len(self.samples[self.stage]) < 1800:
                self.samples[self.stage].append(dict(scores))

    @staticmethod
    def percentile(values, fraction):
        values = sorted(values)
        return values[min(len(values) - 1, int((len(values) - 1) * fraction))]

    def finish(self):
        self.stage = None
        neutral = self.samples["neutral"]
        if len(neutral) < 15:
            raise ValueError("Collect more neutral samples first (at least 15 fresh frames).")
        thresholds = {}
        for expression in ("mouth", "brows"):
            active = self.samples[expression]
            if len(active) < 15:
                continue
            baseline = self.percentile([s[expression] for s in neutral], .95)
            peak = self.percentile([s[expression] for s in active], .90)
            if peak - baseline < .10:
                continue
            release = baseline + .35 * (peak - baseline)
            thresholds[expression] = {"on": baseline + .65 * (peak - baseline), "off": release, "neutral": release}
        if not thresholds:
            raise ValueError("No clear expression separation yet. Check face detail/lighting or collect comfortable repetitions again.")
        return thresholds
