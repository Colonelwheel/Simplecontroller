"""Single-pointer native desktop controls; the UI never opens a webcam."""
from __future__ import annotations

from dataclasses import replace
from datetime import datetime
from pathlib import Path
from typing import Callable
import subprocess
import sys
import os
import math

from PySide6.QtCore import Qt, QTimer, Signal, QRectF
from PySide6.QtGui import QAction, QImage, QPixmap, QPainter, QPen
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QDoubleSpinBox, QFileDialog,
    QFormLayout, QGridLayout, QGroupBox, QHBoxLayout, QLabel, QLineEdit, QMainWindow,
    QMessageBox, QProgressBar, QPushButton, QScrollArea, QStyle,
    QSystemTrayIcon, QMenu, QVBoxLayout, QWidget, QTabBar, QTabWidget,
)

from .runtime import AppRuntime
from .settings import AppSettings, BUTTONS, OUTPUT_LABELS, default_settings_path, save_settings
from .profiles import (apply_profile, list_profiles, load_profile, save_profile,
                       apply_movement_profile, list_movement_profiles,
                       load_movement_profile, save_movement_profile)
from .movement import KEYS as KEY_CHOICES, XBOX_BUTTONS


class PointerComboBox(QComboBox):
    def wheelEvent(self, event):
        event.ignore()


class PointerSpinBox(QDoubleSpinBox):
    def wheelEvent(self, event):
        event.ignore()


class PointerTabBar(QTabBar):
    def wheelEvent(self, event):
        event.ignore()


def config_combo(entries, value, changed):
    widget = PointerComboBox()
    widget.setSizeAdjustPolicy(QComboBox.SizeAdjustPolicy.AdjustToMinimumContentsLengthWithIcon)
    widget.setMinimumContentsLength(12)
    for label, key in entries:
        widget.addItem(label, key)
    widget.setCurrentIndex(max(0, widget.findData(value)))
    widget.currentIndexChanged.connect(lambda *_: changed())
    return widget


def config_number(value, minimum, maximum, changed, decimals=3, step=.01):
    widget = PointerSpinBox()
    widget.setRange(minimum, maximum)
    widget.setDecimals(decimals)
    widget.setSingleStep(step)
    widget.setValue(value)
    widget.valueChanged.connect(lambda *_: changed())
    return widget


def config_check(text, checked, changed):
    widget = QCheckBox(text)
    widget.setMinimumHeight(44)
    widget.setChecked(checked)
    widget.toggled.connect(lambda *_: changed())
    return widget


DIRECTION_CHOICES = (("Forward", "U"), ("Forward-right", "UR"), ("Right", "R"),
                     ("Back-right", "DR"), ("Back", "D"), ("Back-left", "DL"),
                     ("Left", "L"), ("Forward-left", "UL"))


class ChordPicker(QWidget):
    """Build a bounded chord using separate pointer actions, never key capture."""
    changed = Signal()

    def __init__(self, choices, values=()):
        super().__init__()
        self.values = tuple(values)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        self.summary = QLabel()
        self.summary.setWordWrap(True)
        layout.addWidget(self.summary)
        self.choice = config_combo(choices, choices[0][1], lambda: None)
        layout.addWidget(self.choice)
        buttons = QHBoxLayout()
        self.add_button = QPushButton("Add selected")
        self.remove_button = QPushButton("Remove last")
        self.clear_button = QPushButton("Clear chord")
        for button in (self.add_button, self.remove_button, self.clear_button):
            button.setMinimumHeight(48)
            buttons.addWidget(button)
        layout.addLayout(buttons)
        self.add_button.clicked.connect(self.add_selected)
        self.remove_button.clicked.connect(lambda: self.set_values(self.values[:-1], emit=True))
        self.clear_button.clicked.connect(lambda: self.set_values((), emit=True))
        self.set_values(values)

    def set_values(self, values, emit=False):
        self.values = tuple(values)
        self.summary.setText(" + ".join(self.values) if self.values else "No chord")
        if emit:
            self.changed.emit()

    def add_selected(self):
        value = self.choice.currentData()
        if value not in self.values and len(self.values) < 8:
            self.set_values((*self.values, value), emit=True)


class BoostEditor(QGroupBox):
    """One visible direction/stage editor, with all 24 states retained."""
    changed = Signal()

    def __init__(self, stages, vertical_axis=None):
        super().__init__("Direction boosts — thresholds use normalized input amplitude")
        from .movement import BoostStage
        self.vertical_axis = vertical_axis
        self._shown_units = None
        self._loading = True
        self.stages = {(stage.direction, stage.level): stage for stage in stages}
        for _, direction in DIRECTION_CHOICES:
            for level, threshold in (("regular", .5), ("super", .7), ("outer", .9)):
                self.stages.setdefault((direction, level), BoostStage(direction=direction, level=level,
                    threshold=threshold, release_threshold=threshold-.1, enabled=False))
        form = QFormLayout(self)
        self.direction = config_combo(DIRECTION_CHOICES, "U", self.show_stage)
        self.level = config_combo([(name.capitalize(), name) for name in ("regular", "super", "outer")], "regular", self.show_stage)
        form.addRow("Editing direction", self.direction)
        form.addRow("Editing stage", self.level)
        self.enabled = config_check("Enable this stage (Outer is optional)", False, self.update_stage)
        self.threshold = config_number(.5, 0, 100, self.update_stage)
        self.release = config_number(.4, 0, 100, self.update_stage)
        self.mode = config_combo([("Hold", "hold"), ("Tap once on entry — 100 ms", "tap")], "hold", self.update_stage)
        self.retain = config_check("Keep this Hold action active at higher thresholds", True, self.update_stage)
        self.inherit = config_check("For diagonals, inherit component cardinal actions", False, self.update_stage)
        actions = [(OUTPUT_LABELS[button], button) for button in BUTTONS]
        actions += [(f"Keyboard {key}", f"KEY:{key}") for key in KEY_CHOICES]
        self.actions = ChordPicker(actions, ("A",))
        self.actions.changed.connect(self.update_stage)
        for label, widget in (("", self.enabled), ("Activate at", self.threshold), ("Release below", self.release),
                              ("Action behavior", self.mode), ("", self.retain), ("", self.inherit), ("Action chord", self.actions)):
            form.addRow(label, widget)
        help_text = QLabel("Choose a direction and stage, enable it, set activation/release, then Add selected actions. Saved motion boosts use normalized amplitude: 1.0 at full-output travel. Forward/back fields can display equivalent Live Y values directly; check their labels. Mouth/brow boosts use expression scores. Tap does not repeat while a stage remains active. Higher-threshold Hold retention is explicit; inherited diagonal actions share ownership.")
        help_text.setWordWrap(True)
        form.addRow(help_text)
        self.units_note = QLabel()
        self.units_note.setWordWrap(True)
        form.addRow(self.units_note)
        self._loading = False
        self.show_stage()

    def threshold_units(self):
        axis = self.vertical_axis() if self.vertical_axis else None
        direction = self.direction.currentData()
        if axis is not None and direction in ("U", "D"):
            return True, axis.positive_range if direction == "U" else -axis.negative_range
        return False, 1.

    def refresh_units(self):
        if self.threshold_units() != self._shown_units:
            self.show_stage()

    def show_stage(self):
        if not hasattr(self, "enabled"):
            return
        self._loading = True
        stage = self.stages[(self.direction.currentData(), self.level.currentData())]
        live_y, scale = self.threshold_units()
        self._shown_units = live_y, scale
        self.setTitle("Direction boosts — thresholds match Live Y" if live_y else "Direction boosts — thresholds use normalized input amplitude")
        for widget in (self.threshold, self.release):
            widget.setDecimals(6 if live_y else 3)
            widget.setRange(min(0.,100*scale), max(0.,100*scale))
            widget.setSingleStep(.01 if live_y else .05)
        form = self.layout()
        form.labelForField(self.threshold).setText("Activate at (Live Y)" if live_y else "Activate at (normalized amplitude)")
        form.labelForField(self.release).setText("Release toward zero (Live Y)" if live_y else "Release below (normalized amplitude)")
        self.units_note.setText(
            "These signed thresholds match Live Y directly for vertical-only forward/back boosts. Release should be closer to zero than activation. Movement must be outside the deadzone and in this direction. Changing directional travel changes the equivalent threshold shown here; saved normalized boost behavior is preserved."
            if live_y else "These thresholds use normalized amplitude, not Live X/Y. Live Y units are available for Forward/Backward when horizontal motion is disabled. Mouth/brow boost units remain expression scores.")
        self.enabled.setChecked(stage.enabled)
        self.threshold.setValue(stage.threshold*scale)
        self.release.setValue(stage.release_threshold*scale)
        self.mode.setCurrentIndex(self.mode.findData(stage.mode))
        self.retain.setChecked(stage.retain)
        self.inherit.setChecked(stage.inherit)
        self.actions.set_values(stage.actions)
        self.retain.setEnabled(stage.mode == "hold")
        self.inherit.setEnabled(len(stage.direction) == 2)
        self._loading = False

    def update_stage(self):
        if self._loading:
            return
        key = (self.direction.currentData(), self.level.currentData())
        _, scale = self._shown_units
        self.stages[key] = replace(self.stages[key], enabled=self.enabled.isChecked(), threshold=self.threshold.value()/scale,
            release_threshold=self.release.value()/scale, mode=self.mode.currentData(), retain=self.retain.isChecked(),
            inherit=self.inherit.isChecked(), actions=self.actions.values)
        self.retain.setEnabled(self.mode.currentData() == "hold")
        self.changed.emit()

    def configs(self):
        return tuple(self.stages.values())


class AnchorEditor(QGroupBox):
    changed = Signal()

    def __init__(self, anchors):
        super().__init__("Ordered direction anchors — disabled entries are retained")
        self.anchors = list(anchors)
        self._loading = True
        form = QFormLayout(self)
        self.row = PointerComboBox()
        self.row.currentIndexChanged.connect(self.show_row)
        form.addRow("Editing anchor", self.row)
        self.enabled = config_check("Enable this anchor", True, self.update_row)
        self.threshold = config_number(.1, 0, 1, self.update_row)
        self.direction = config_combo(DIRECTION_CHOICES, "U", self.update_row)
        self.path = config_combo([("Clockwise to next anchor", "cw"), ("Counterclockwise to next anchor", "ccw")], "cw", self.update_row)
        for label, widget in (("", self.enabled), ("Expression score", self.threshold), ("Direction", self.direction), ("Sweep path", self.path)):
            form.addRow(label, widget)
        buttons = QHBoxLayout()
        for text, operation in (("Add anchor", self.add), ("Remove anchor", self.remove), ("Move earlier", lambda: self.move(-1)), ("Move later", lambda: self.move(1))):
            button = QPushButton(text)
            button.setMinimumHeight(48)
            button.clicked.connect(operation)
            buttons.addWidget(button)
        form.addRow(buttons)
        notice = QLabel("Enabled scores must increase in this order. Reordering may require adjusting scores before saving. Sweep follows the selected circular path at full radius; Step jumps directly. Above the last enabled score, retain its direction.")
        notice.setWordWrap(True)
        form.addRow(notice)
        self._loading = False
        self.refresh_rows()

    def refresh_rows(self, selected=0):
        self._loading = True
        self.row.clear()
        for number, anchor in enumerate(self.anchors):
            self.row.addItem(f"{number+1}: {anchor.direction} at {anchor.threshold:.3f}" + (" (disabled)" if not anchor.enabled else ""))
        self.row.setCurrentIndex(min(selected, len(self.anchors)-1))
        self._loading = False
        self.show_row()

    def show_row(self, *_):
        if self._loading:
            return
        index = self.row.currentIndex()
        self._loading = True
        for widget in (self.enabled, self.threshold, self.direction, self.path):
            widget.setEnabled(index >= 0)
        if index >= 0:
            anchor = self.anchors[index]
            self.enabled.setChecked(anchor.enabled)
            self.threshold.setValue(anchor.threshold)
            self.direction.setCurrentIndex(self.direction.findData(anchor.direction))
            self.path.setCurrentIndex(self.path.findData(anchor.path))
        self._loading = False

    def update_row(self):
        index = self.row.currentIndex()
        if self._loading or index < 0:
            return
        self.anchors[index] = replace(self.anchors[index], enabled=self.enabled.isChecked(), threshold=self.threshold.value(),
                                      direction=self.direction.currentData(), path=self.path.currentData())
        self.refresh_rows(index)
        self.changed.emit()

    def add(self):
        from .movement import DirectionAnchor
        if len(self.anchors) >= 32:
            return
        threshold = min(1., (self.anchors[-1].threshold if self.anchors else 0.)+.1)
        self.anchors.append(DirectionAnchor(threshold=threshold, direction="U"))
        self.refresh_rows(len(self.anchors)-1)
        self.changed.emit()

    def remove(self):
        index = self.row.currentIndex()
        if index >= 0:
            del self.anchors[index]
            self.refresh_rows(max(0, index-1))
            self.changed.emit()

    def move(self, offset):
        index = self.row.currentIndex()
        other = index+offset
        if index >= 0 and 0 <= other < len(self.anchors):
            self.anchors[index], self.anchors[other] = self.anchors[other], self.anchors[index]
            self.refresh_rows(other)
            self.changed.emit()


class MovementProfileControls(QGroupBox):
    def __init__(self, owner, source):
        super().__init__("Independent movement profiles — explicit new-file saves")
        self.source = source
        form = QFormLayout(self)
        self.choice = PointerComboBox()
        self.name = QLineEdit(f"{source.capitalize()} movement")
        self.name.setMaxLength(100)
        self.status = QLabel("Movement profiles do not replace expression thresholds or saved camera settings.")
        self.status.setWordWrap(True)
        form.addRow("Saved movement profile", self.choice)
        form.addRow(owner._button("Load movement profile", lambda: owner._load_movement_profile(source)))
        form.addRow("New profile name", self.name)
        form.addRow(owner._button("Save movement profile", lambda: owner._save_movement_profile(source)))
        form.addRow(self.status)


class ExpressionMovementEditor(QGroupBox):
    def __init__(self, owner, expression):
        super().__init__("Expression movement response")
        self.owner, self.expression = owner, expression
        self.base = owner.settings.expression_movements[expression]
        form = QFormLayout(self)
        changed = lambda: owner._movement_config_changed(expression)
        self.mode = config_combo([("Fixed direction", "fixed"), ("Proportional strength", "proportional"),
                                 ("Experimental Step", "step"), ("Experimental Sweep", "sweep")], self.base.mode, changed)
        self.direction = config_combo(DIRECTION_CHOICES, self.base.direction, changed)
        self.strength = config_number(self.base.strength_percent, 0, 100, changed, 0, 5)
        self.full_score = config_number(self.base.full_score, .0001, 1, changed, 4)
        self.relaxation = config_combo([("Follow strength up and down", "follow"), ("Keep last direction while relaxing; stop at neutral", "retain")], self.base.relaxation, changed)
        self.rise_tolerance = config_number(self.base.rise_tolerance, 0, .5, changed)
        self.rise_confirm = config_number(self.base.rise_confirm_ms, 0, 1000, changed, 0, 10)
        for label, widget in (("Mapping", self.mode), ("Fixed/proportional direction", self.direction),
                              ("Fixed stick strength (%)", self.strength), ("Proportional full-strength score", self.full_score),
                              ("On relaxation", self.relaxation), ("Retain-mode rise tolerance", self.rise_tolerance),
                              ("Retain-mode rise confirmation (ms)", self.rise_confirm)):
            form.addRow(label, widget)
        self.anchors = AnchorEditor(self.base.anchors)
        self.anchors.changed.connect(changed)
        form.addRow(self.anchors)
        self.boosts = BoostEditor(self.base.boosts)
        self.boosts.changed.connect(changed)
        form.addRow(self.boosts)
        self.keys = ChordPicker([(key, key) for key in KEY_CHOICES], self.base.recenter_keys)
        self.buttons = ChordPicker([(OUTPUT_LABELS[key], key) for key in XBOX_BUTTONS], self.base.recenter_buttons)
        self.keys.changed.connect(changed)
        self.buttons.changed.connect(changed)
        form.addRow("Expression re-center keyboard chord", self.keys)
        form.addRow("Expression re-center Xbox chord", self.buttons)
        form.addRow(owner._button("Stop / re-center this expression route", lambda: owner._recenter(expression)))
        self.error = QLabel("")
        self.error.setWordWrap(True)
        form.addRow(self.error)
        self.profiles = MovementProfileControls(owner, expression)
        form.addRow(self.profiles)
        self.update_controls()

    def update_controls(self):
        mode = self.mode.currentData()
        output = self.owner.settings.expression_movements[self.expression].output
        self.direction.setEnabled(mode in ("fixed", "proportional"))
        self.strength.setEnabled(mode == "fixed" and output == "stick")
        self.full_score.setEnabled(mode == "proportional")
        self.anchors.setVisible(mode in ("step", "sweep"))
        self.relaxation.setEnabled(mode in ("step", "sweep"))
        self.rise_tolerance.setEnabled(mode in ("step", "sweep") and self.relaxation.currentData() == "retain")
        self.rise_confirm.setEnabled(self.rise_tolerance.isEnabled())

    def config(self):
        return replace(self.owner.settings.expression_movements[self.expression], mode=self.mode.currentData(),
            direction=self.direction.currentData(), strength_percent=self.strength.value(), full_score=self.full_score.value(),
            relaxation=self.relaxation.currentData(), rise_tolerance=self.rise_tolerance.value(), rise_confirm_ms=int(self.rise_confirm.value()),
            anchors=tuple(self.anchors.anchors), boosts=self.boosts.configs(),
            recenter_keys=self.keys.values, recenter_buttons=self.buttons.values)


class AxisEditor(QGroupBox):
    def __init__(self, title, config, changed):
        super().__init__(title)
        self.base = config
        form = QFormLayout(self)
        self.enabled = config_check("Enable this axis", config.enabled, changed)
        self.source = config_combo([("Nose displacement", "nose"), ("Upper lip", "lip"), ("Cheeks", "cheek"),
                                    ("Calibrated lip + cheek fusion", "combined")], config.source, changed)
        lower, upper = config.deadzone_bounds
        self.deadzone_lower = config_number(lower, -2, 0, changed, 4)
        self.deadzone_upper = config_number(upper, 0, 2, changed, 4)
        self.negative = config_number(config.negative_range, .0001, 10, changed, 4)
        self.positive = config_number(config.positive_range, .0001, 10, changed, 4)
        self.smoothing = config_number(config.smoothing_ms, 0, 500, changed, 0, 10)
        self.orientation = config_number(config.orientation_degrees, -180, 180, changed, 1, 1)
        self.inverted = config_check("Invert positive and negative direction", config.inverted, changed)
        self.live_displacement = QLabel("Live displacement: center this axis to see its value.")
        self.live_displacement.setWordWrap(True)
        form.addRow(self.live_displacement)
        self.keys = ChordPicker([(key, key) for key in KEY_CHOICES], config.recenter_keys)
        self.buttons = ChordPicker([(OUTPUT_LABELS[button], button) for button in BUTTONS if button not in ("MOUSE_LEFT", "MOUSE_RIGHT", "SPACE")], config.recenter_buttons)
        self.keys.changed.connect(changed)
        self.buttons.changed.connect(changed)
        negative_direction, positive_direction = ("Backward / down", "Forward / up") if config.source == "nose" and title.startswith("Vertical") else ("Left", "Right")
        for label, widget in (("", self.enabled), ("Feature source", self.source),
                              ("Deadzone lower limit (signed)", self.deadzone_lower), ("Deadzone upper limit (signed)", self.deadzone_upper),
                              (negative_direction + " sensitivity — full-output travel", self.negative),
                              (positive_direction + " sensitivity — full-output travel", self.positive),
                              ("Smoothing (ms)", self.smoothing), ("Directional tilt correction (degrees)", self.orientation),
                              ("", self.inverted), ("Axis re-center keyboard chord", self.keys), ("Axis re-center Xbox chord", self.buttons)):
            form.addRow(label, widget)
        help_text = QLabel("Everything from the lower to upper deadzone limit is ignored. Tune each direction independently: smaller full-output travel means higher sensitivity; larger travel means lower sensitivity. Travel is a positive magnitude beyond that direction's deadzone. Up/down labels describe the default mapping; inversion or tilt can change your physical movement direction.")
        help_text.setWordWrap(True)
        form.addRow(help_text)

    def config(self):
        return replace(self.base, enabled=self.enabled.isChecked(), source=self.source.currentData(),
            deadzone_lower=self.deadzone_lower.value(), deadzone_upper=self.deadzone_upper.value(),
            negative_range=self.negative.value(), positive_range=self.positive.value(), smoothing_ms=self.smoothing.value(),
            orientation_degrees=self.orientation.value(), inverted=self.inverted.isChecked(),
            recenter_keys=self.keys.values, recenter_buttons=self.buttons.values)

    def show_live_displacement(self, axis, raw, movement, mode, route="stick", boost_y_units=False, output_axis=None):
        value = raw.get(axis)
        valid = isinstance(value, (int, float)) and math.isfinite(value)
        live = f"{value:+.4f}" if valid else "unavailable — center this axis and check tracking"
        vector = movement.get("vector", (0., 0.))
        output_axis = (output_axis or axis) if movement.get("owner") in (None, "motion") else axis
        component = vector[0 if output_axis == "x" else 1]
        component = max(-1., min(1., component)) if isinstance(component, (int, float)) and math.isfinite(component) else 0.
        if route == "wasd" and any(vector):
            keys = movement.get("keys", ())
            positive, negative = ("KEY:D", "KEY:A") if output_axis == "x" else ("KEY:W", "KEY:S")
            component = float(positive in keys) - float(negative in keys)
        in_deadzone = valid and self.deadzone_lower.value() <= value <= self.deadzone_upper.value()
        if mode not in ("live", "test"):
            output = "Deadzone — 0%" if in_deadzone else "No output — 0%"
            output += f" ({mode})"
        elif component:
            direction = ("Forward" if component > 0 else "Backward") if output_axis == "y" else ("Right" if component > 0 else "Left")
            output = f"{direction} — {abs(component)*100:.1f}%"
            if movement.get("bridging"):
                output += " (tracking held — Pause to stop)"
            owner = movement.get("owner")
            if owner not in (None, "motion"):
                output += f" ({owner})"
        elif in_deadzone and movement.get("owner") in (None, "motion"):
            output = "Deadzone — 0%"
        else:
            output = "No output — 0% (" + str(movement.get("status", "unavailable")) + ")"
        if mode == "test":
            output += " (Test only; no inputs sent)"
        amplitude = None
        if boost_y_units and valid:
            amplitude = value
        elif not boost_y_units and mode in ("live", "test", "paused"):
            amplitude = movement.get("boost_input_amplitude")
        boost_input = (f"{amplitude:+.4f}" if boost_y_units else f"{amplitude:.4f}") if isinstance(amplitude, (int, float)) and math.isfinite(amplitude) else "unavailable"
        boost_units = "Live Y; matches forward/back threshold fields" if boost_y_units else "normalized amplitude; matches normalized threshold fields"
        if mode == "paused" and amplitude is not None:
            boost_units += "; preview while paused"
        boosts = movement.get("boosts", {}) if mode in ("live", "test") else {}
        def action_names(actions):
            return ", ".join(OUTPUT_LABELS.get(action, "Keyboard " + action[4:] if action.startswith("KEY:") else action)
                             for action in sorted(set(actions)))
        boost_parts = []
        if boosts.get("holds"):
            boost_parts.append("Hold: " + action_names(boosts["holds"]))
        if boosts.get("taps"):
            boost_parts.append("Tap event: " + action_names(boosts["taps"]))
        boost_output = "; ".join(boost_parts) or "none"
        if mode == "test":
            boost_output += " (Test only; no inputs sent)"
        self.live_displacement.setText(
            f"Live {axis.upper()}: {live} (same units as the fields below)\n"
            f"Current output: {output}\n"
            f"Boost input: {boost_input} ({boost_units})\n"
            f"Current boosts: {boost_output}\n"
            f"Stop zone: {self.deadzone_lower.value():+.4f} to {self.deadzone_upper.value():+.4f}; "
            f"full {'backward' if axis == 'y' else 'left'}: {-self.negative.value():+.4f}; "
            f"full {'forward' if axis == 'y' else 'right'}: {self.positive.value():+.4f}.")


class MotionEditor(QGroupBox):
    def __init__(self, owner):
        super().__init__("Optional face-position movement — each axis starts disabled")
        self.owner = owner
        self.base = owner.settings.movement
        changed = lambda: owner._movement_config_changed("motion")
        form = QFormLayout(self)
        self.output = config_combo([("Left stick", "stick"), ("Keyboard WASD only", "wasd")], self.base.output, changed)
        self.tilt = config_number(self.base.tilt_degrees, -180, 180, changed, 1, 1)
        form.addRow("Movement route", self.output)
        form.addRow("Neutral pose tilt (degrees)", self.tilt)
        self.horizontal = AxisEditor("Horizontal / strafe", self.base.horizontal, changed)
        self.vertical = AxisEditor("Vertical / forward-back", self.base.vertical, changed)
        self.vertical.source.setEnabled(False)
        vertical_form = self.vertical.layout()
        self.vertical_to_horizontal = config_check("Use Y movement for left / right output", self.base.vertical_to_horizontal, changed)
        self.invert_vertical_output = config_check("Invert Y left / right output (up moves right)", self.base.invert_vertical_output, changed)
        self.invert_vertical_output.setEnabled(self.base.vertical_to_horizontal)
        self.vertical_to_horizontal.toggled.connect(self.invert_vertical_output.setEnabled)
        vertical_form.insertRow(1, self.vertical_to_horizontal)
        vertical_form.insertRow(2, self.invert_vertical_output)
        mapping_help = QLabel("Y routing keeps these same signed Y thresholds, sensitivity, smoothing and re-center controls. Up outputs Left; inversion outputs Right. Existing Forward/Backward boost stages still follow positive/negative Y input. Axis inversion above can also reverse input. If horizontal movement is enabled too, its X contribution adds to this output, capped at 100%; opposite directions cancel.")
        mapping_help.setWordWrap(True)
        vertical_form.insertRow(3, mapping_help)
        form.addRow(self.horizontal)
        self.center_x = owner._button("Center horizontal axis only", lambda: owner._recenter("x"))
        form.addRow(self.center_x)
        form.addRow(self.vertical)
        self.center_y = owner._button("Center vertical axis only", lambda: owner._recenter("y"))
        form.addRow(self.center_y)
        self.keys = ChordPicker([(key, key) for key in KEY_CHOICES], self.base.shared_recenter_keys)
        self.buttons = ChordPicker([(OUTPUT_LABELS[button], button) for button in BUTTONS if button not in ("MOUSE_LEFT", "MOUSE_RIGHT", "SPACE")], self.base.shared_recenter_buttons)
        self.keys.changed.connect(changed)
        self.buttons.changed.connect(changed)
        form.addRow("Shared re-center keyboard chord", self.keys)
        form.addRow("Shared re-center Xbox chord", self.buttons)
        help_text = QLabel("Configure chords using individual Add actions, not simultaneous physical presses. Keyboard and Xbox selections combine into one chord. Shared and per-axis shortcuts may coexist. Center gathers fresh stable samples; a saved profile never proves current posture. WASD is keyboard-only and does not emulate analog speed. Mouth movement wins over eyebrows, which win over this motion vector. Centered nose movement resumes without neutral; nose-only output holds through tracking loss until tracking returns or Pause. Buttons and boosts release on loss. Lip/cheek and expression movement still require neutral.")
        help_text.setWordWrap(True)
        form.addRow(help_text)
        self.center_all = owner._button("Center all facial movement routes", lambda: owner._recenter("all"))
        form.addRow(self.center_all)
        self.boosts = BoostEditor(self.base.boosts, vertical_axis=lambda: None if self.horizontal.enabled.isChecked() else self.vertical.config())
        self.boosts.changed.connect(changed)
        form.addRow(self.boosts)
        self.error = QLabel("")
        self.error.setWordWrap(True)
        form.addRow(self.error)
        self.profiles = MovementProfileControls(owner, "motion")
        form.addRow(self.profiles)

    def config(self):
        return replace(self.base, output=self.output.currentData(), tilt_degrees=self.tilt.value(),
            horizontal=self.horizontal.config(), vertical=self.vertical.config(), boosts=self.boosts.configs(),
            shared_recenter_keys=self.keys.values, shared_recenter_buttons=self.buttons.values,
            vertical_to_horizontal=self.vertical_to_horizontal.isChecked(), invert_vertical_output=self.invert_vertical_output.isChecked())


class BindingEditor(QWidget):
    """One expression's controls; callbacks retain their expression identity."""
    def __init__(self, owner, expression):
        super().__init__()
        self.expression = expression
        form = QFormLayout(self)
        binding = owner.settings.wink_binding if expression == "wink" else owner.settings.binding_for(expression)

        def combo(entries, value):
            widget = PointerComboBox()
            for label, key in entries:
                widget.addItem(label, key)
            widget.setCurrentIndex(max(0, widget.findData(value)))
            widget.currentIndexChanged.connect(lambda *_: owner._binding_changed(expression))
            return widget

        if expression != "wink" and hasattr(owner.settings, "disabled_expressions"):
            self.enabled = config_check("Enable this expression", expression not in owner.settings.disabled_expressions,
                                        lambda: owner._expression_enabled_changed(expression))
            form.addRow(self.enabled)
        self.output_mode = combo([("Tap — 100 ms", "tap"), ("Hold while active", "hold")], binding.mode)
        choices = [(OUTPUT_LABELS[button], button) for button in BUTTONS]
        selected = binding.button
        if expression != "wink" and hasattr(owner.settings, "expression_movements"):
            choices += [("Left-stick movement", "STICK_MOVEMENT"), ("Keyboard WASD movement", "WASD_MOVEMENT")]
            config = owner.settings.expression_movements[expression]
            if config.enabled:
                selected = "STICK_MOVEMENT" if config.output == "stick" else "WASD_MOVEMENT"
        self.button = combo(choices, selected)
        self.preset = combo([("Reliable — 150 ms", "reliable"), ("Responsive — 50 ms", "responsive")], binding.preset)
        form.addRow("Behavior", self.output_mode)
        form.addRow("Output", self.button)
        output_help = QLabel("Mouse clicks use the current pointer position; Spacebar goes to the focused app. Player selects Xbox output only.")
        output_help.setWordWrap(True)
        form.addRow(output_help)
        form.addRow("Timing", self.preset)
        self.on_threshold = PointerSpinBox()
        self.off_threshold = PointerSpinBox()
        self.neutral_threshold = PointerSpinBox()
        thresholds = owner.settings.wink_thresholds if expression == "wink" else owner.settings.thresholds[expression]
        for widget, key in ((self.on_threshold, "on"), (self.off_threshold, "off"), (self.neutral_threshold, "neutral")):
            widget.setRange(0., 1.)
            widget.setDecimals(3)
            widget.setSingleStep(.01)
            widget.setValue(thresholds.get(key, thresholds["off"]))
            widget.valueChanged.connect(lambda *_: owner._threshold_changed(expression))
        form.addRow("Activate above", self.on_threshold)
        form.addRow("Release below", self.off_threshold)
        form.addRow("Neutral at or below", self.neutral_threshold)
        neutral_help = QLabel("If neutral is hard to reach, raise its threshold slightly above your relaxed score, keeping it below activation. Neutral needs 100 ms; Hold still uses Release below.")
        neutral_help.setWordWrap(True)
        form.addRow(neutral_help)
        self.threshold_error = QLabel("")
        self.threshold_error.setWordWrap(True)
        form.addRow(self.threshold_error)
        profiles = QGroupBox("Calibration profiles — this expression only")
        profile_form = QFormLayout(profiles)
        self.profile_choice = PointerComboBox()
        self.profile_name = QLineEdit()
        self.profile_name.setMaxLength(100)
        self.profile_name.setText({"mouth": "Mouth calibration", "brows": "Eyebrow calibration", "wink": "Left wink calibration"}[expression])
        self.profile_load_button = owner._button("Load profile", lambda: owner._load_profile(expression))
        self.profile_save_button = owner._button("Save current calibration", lambda: owner._save_profile(expression))
        profile_form.addRow("Saved profiles", self.profile_choice)
        profile_form.addRow(self.profile_load_button)
        profile_form.addRow("New profile name", self.profile_name)
        profile_form.addRow(self.profile_save_button)
        self.profile_status = QLabel("Mouth and eyebrow profiles are independent. Save accepts valid manual thresholds without automatic calibration. Saving/loading pauses all output; use Test before Start. Save settings keeps the choice for next launch.")
        if expression == "wink":
            self.profile_status.setText("Subject-left wink: close your left eye while keeping your right eye open. Ordinary bilateral blinks are rejected. Begin with Test, adjust comfortable manual thresholds, then Save current calibration to accept wink for Start. Automatic calibration samples mouth and eyebrows only. Wink profiles are independent; Save settings keeps the choice next launch.")
        self.profile_status.setWordWrap(True)
        profile_form.addRow(self.profile_status)
        self.profile_directory_status = QLabel(f"Profile folder: {owner.profiles_directory / expression}")
        self.profile_directory_status.setWordWrap(True)
        self.profile_directory_status.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        profile_form.addRow(self.profile_directory_status)
        form.addRow(profiles)
        self.movement_editor = None
        if expression != "wink" and hasattr(owner.settings, "expression_movements"):
            self.movement_editor = ExpressionMovementEditor(owner, expression)
            self.movement_editor.setVisible(owner.settings.expression_movements[expression].enabled)
            form.addRow(self.movement_editor)
            self.output_mode.setEnabled(not owner.settings.expression_movements[expression].enabled)


class PreviewLabel(QLabel):
    """Rendering uses a detached QImage, so worker frame replacement is safe."""
    corner_selected = Signal(tuple)

    def __init__(self):
        super().__init__("Preview off")
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setMinimumHeight(160)
        self.setMaximumHeight(280)
        self.setStyleSheet("background:#202329;color:#eee;border-radius:5px")
        self.selection_enabled = False
        self.first_corner = None
        self._has_frame = False

    def clear(self):
        self._has_frame = False
        super().clear()

    def image_rect(self):
        picture = self.pixmap()
        if not self._has_frame or picture is None or picture.isNull():
            return None
        width, height = picture.width() / picture.devicePixelRatio(), picture.height() / picture.devicePixelRatio()
        content = self.contentsRect()
        return QRectF(content.x() + (content.width() - width) / 2,
                      content.y() + (content.height() - height) / 2, width, height)

    def mousePressEvent(self, event):
        area = self.image_rect()
        point = event.position()
        if (self.selection_enabled and event.button() == Qt.MouseButton.LeftButton
                and area is not None and area.contains(point)):
            self.corner_selected.emit((min(1., max(0., (point.x() - area.x()) / area.width())),
                                       min(1., max(0., (point.y() - area.y()) / area.height()))))
            event.accept()
        else:
            super().mousePressEvent(event)

    def paintEvent(self, event):
        super().paintEvent(event)
        area = self.image_rect()
        if self.selection_enabled and self.first_corner is not None and area is not None:
            painter = QPainter(self)
            painter.setPen(QPen(Qt.GlobalColor.yellow, 3))
            x = area.x() + self.first_corner[0] * area.width()
            y = area.y() + self.first_corner[1] * area.height()
            painter.drawEllipse(QRectF(x - 6, y - 6, 12, 12))

    def show_frame(self, frame):
        if frame is None:
            self.clear()
            self.setText("Waiting for camera frames")
            return
        # Shared frames are BGR, and may be non-contiguous crop views.
        import numpy as np
        rgb = np.ascontiguousarray(frame[:, :, ::-1])
        height, width = rgb.shape[:2]
        picture = QImage(rgb.data, width, height, rgb.strides[0], QImage.Format.Format_RGB888).copy()
        self.setPixmap(QPixmap.fromImage(picture).scaled(
            self.size(), Qt.AspectRatioMode.KeepAspectRatio,
            Qt.TransformationMode.SmoothTransformation,
        ))
        self._has_frame = True


class MainWindow(QMainWindow):
    """All setting changes pause output; saving is a separate explicit action."""
    stopped = Signal()

    def __init__(self, settings: AppSettings, model_path: Path | None = None,
                 feed_name: str | None = None, settings_path: Path | None = None,
                 demo: bool = False, runtime_factory: Callable = AppRuntime):
        super().__init__()
        self.settings = settings
        self.model_path = model_path
        self.feed_name = feed_name
        self.settings_path = settings_path or default_settings_path()
        self.profiles_directory = self.settings_path.parent / "profiles"
        self.movement_profiles_directory = self.settings_path.parent / "movement-profiles"
        self.demo = demo
        self._runtime_factory = runtime_factory
        self._quitting = False
        self._building = True
        self._last_snapshot = {}
        self._stage = None
        self._calibration_completed = set()
        self._selecting_region = False
        self._region_before_selection = None
        self._preview_before_selection = False
        self._first_region_geometry = None
        self.editing_expression = settings.expression
        self.runtime = runtime_factory(settings, model_path, feed_name=feed_name, demo=demo)
        self.setWindowTitle("Facial Input" + (" — synthetic demo" if demo else ""))
        self.resize(760, 840)
        self.setMinimumSize(550, 580)
        self.setStyleSheet("QPushButton,QComboBox,QDoubleSpinBox,QLineEdit { min-height:44px; font-size:15px; } "
                           "QLabel,QCheckBox { font-size:14px; } QGroupBox { font-size:15px; margin-top:12px; } "
                           "QGroupBox::title { subcontrol-origin:margin; } QMenu::item { padding:14px 20px; } "
                           "QComboBox QAbstractItemView::item { min-height:44px; } "
                           "QTabBar::tab { min-height:44px; min-width:180px; font-size:15px; }")
        self._build_ui()
        for form in self.findChildren(QFormLayout):
            form.setRowWrapPolicy(QFormLayout.RowWrapPolicy.WrapLongRows)
            form.setFieldGrowthPolicy(QFormLayout.FieldGrowthPolicy.AllNonFixedFieldsGrow)
        self._build_tray()
        self._building = False
        self.runtime.start_workers()
        self.timer = QTimer(self)
        self.timer.setInterval(100)
        self.timer.timeout.connect(self.refresh)
        self.timer.start()
        self.refresh()

    def _button(self, text, callback):
        button = QPushButton(text)
        button.setMinimumHeight(48)
        button.clicked.connect(callback)
        return button

    def _combo(self, entries, selected):
        combo = PointerComboBox()
        for label, value in entries:
            combo.addItem(label, value)
        combo.setCurrentIndex(max(0, combo.findData(selected)))
        combo.currentIndexChanged.connect(self._settings_changed)
        return combo

    def _build_ui(self):
        area = QScrollArea()
        self._scroll = area
        area.setWidgetResizable(True)
        content = QWidget()
        layout = QVBoxLayout(content)
        layout.setSpacing(12)
        self.notice = QLabel("Synthetic demo: no camera, model, shared memory, or controller output." if self.demo else
                             "Starts paused; calibration and Test send no inputs. Select the camera source below.")
        self.notice.setWordWrap(True)
        layout.addWidget(self.notice)
        actions = QHBoxLayout()
        self.start_button = self._button("Start", self._toggle_live)
        self.test_button = self._button("Test (no output)", self._toggle_test)
        self.calibrate_button = self._button("Calibrate", self._open_calibration)
        for button in (self.start_button, self.test_button, self.calibrate_button):
            actions.addWidget(button)
        layout.addLayout(actions)
        self.mode_status = QLabel("Paused")
        self.mode_status.setWordWrap(True)
        layout.addWidget(self.mode_status)
        self.action_status = QLabel("")
        self.action_status.setWordWrap(True)
        layout.addWidget(self.action_status)

        toggle = QGroupBox("Start / Pause live output shortcut")
        toggle_form = QFormLayout(toggle)
        self.toggle_keys = ChordPicker([(key, key) for key in KEY_CHOICES], self.settings.live_toggle_keys)
        self.toggle_buttons = ChordPicker([(OUTPUT_LABELS[key], key) for key in XBOX_BUTTONS], self.settings.live_toggle_buttons)
        toggle_form.addRow("Keyboard key(s)", self.toggle_keys)
        toggle_form.addRow("Simplecontroller Xbox button(s)", self.toggle_buttons)
        self.toggle_error = QLabel("")
        self.toggle_error.setWordWrap(True)
        toggle_form.addRow(self.toggle_error)
        toggle_help = QLabel("Either chord toggles Start / Pause; leave both empty to disable. Add each component separately. One key or button is enough. Hold toggles once; release before reuse. Works from the tray. Start uses the normal readiness checks; calibration cannot start live output. Xbox uses phone-origin buttons for the selected Player, not external hardware. Shortcuts also reach the game. Avoid using the same chord for re-center or game actions you don't want together. Save settings to keep your choice.")
        toggle_help.setWordWrap(True)
        toggle_form.addRow(toggle_help)
        self.toggle_keys.changed.connect(self._toggle_shortcut_changed)
        self.toggle_buttons.changed.connect(self._toggle_shortcut_changed)
        layout.addWidget(toggle)

        camera = QGroupBox("Camera source")
        camera_form = QFormLayout(camera)
        self.camera_source = PointerComboBox()
        self.camera_source.addItem("Tyler Safety Monitor — shared frames", "shared")
        self.camera_source.addItem("Standalone webcam — monitor not required", "webcam")
        self.camera_source.setCurrentIndex(self.camera_source.findData(self.settings.camera_source))
        self.camera_index = PointerComboBox()
        for index in range(21):
            self.camera_index.addItem(f"Camera {index}", index)
        self.camera_index.setCurrentIndex(self.camera_index.findData(self.settings.camera_index))
        self.camera_resolution = PointerComboBox()
        for value in ("640x480", "1280x720", "1920x1080"):
            self.camera_resolution.addItem(value.replace("x", " × "), value)
        self.camera_resolution.setCurrentIndex(self.camera_resolution.findData(self.settings.camera_resolution))
        self.camera_apply = self._button("Apply camera / retry", self._apply_camera)
        self.camera_source.currentIndexChanged.connect(self._camera_selection_changed)
        self.camera_index.currentIndexChanged.connect(self._camera_selection_changed)
        self.camera_resolution.currentIndexChanged.connect(self._camera_selection_changed)
        camera_form.addRow("Source", self.camera_source)
        camera_form.addRow("Webcam number", self.camera_index)
        camera_form.addRow("Capture detail requested", self.camera_resolution)
        camera_form.addRow(self.camera_apply)
        region_controls = QHBoxLayout()
        self.region_select_button = self._button("Select face region", self._select_region)
        self.region_clear_button = self._button("Clear face region", self._clear_region)
        region_controls.addWidget(self.region_select_button)
        region_controls.addWidget(self.region_clear_button)
        camera_form.addRow(region_controls)
        self.region_status = QLabel()
        self.region_status.setWordWrap(True)
        camera_form.addRow(self.region_status)
        self.camera_help = QLabel("Shared mode never opens a webcam. Standalone can crop your face before downscaling. Higher capture detail can cost more CPU; the driver may negotiate different values. Use an unused camera; sharing a device with the safety monitor may disrupt capture. This app never closes the monitor. Selection pauses output; Apply opens the selected source. Save settings preserves it.")
        self.camera_help.setWordWrap(True)
        camera_form.addRow(self.camera_help)
        self.camera_index.setEnabled(self.settings.camera_source == "webcam")
        self.camera_resolution.setEnabled(self.settings.camera_source == "webcam")
        layout.addWidget(camera)

        binding = QGroupBox("Expression inputs")
        form = QFormLayout(binding)
        self.expression = self._combo([("Mouth opening", "mouth"), ("Eyebrow raising", "brows")], self.settings.expression)
        self.player = self._combo([("Player 1", "player1"), ("Player 2", "player2")], self.settings.player)
        self.fps = self._combo([(f"{n} frames/second", n) for n in (10, 15, 20, 30)], self.settings.fps)
        self.simultaneous_check = QCheckBox("Enable both expressions")
        self.simultaneous_check.setMinimumHeight(44)
        self.simultaneous_check.setChecked(self.settings.simultaneous)
        self.simultaneous_check.toggled.connect(self._simultaneous_changed)
        form.addRow(self.simultaneous_check)
        self.wink_check = config_check("Enable left-eye wink (button output only)", self.settings.wink_enabled, self._wink_changed)
        form.addRow(self.wink_check)
        for label, widget in (("Primary expression", self.expression), ("Controller", self.player), ("Processing limit", self.fps)):
            form.addRow(label, widget)
        self.binding_tabs = QTabWidget()
        self.binding_tabs.setTabBar(PointerTabBar())
        self.binding_tabs.tabBar().setExpanding(True)
        self.binding_tabs.setUsesScrollButtons(False)
        self.editors = {expression: BindingEditor(self, expression) for expression in ("mouth", "brows", "wink")}
        self.binding_tabs.currentChanged.connect(self._editor_tab_changed)
        self._show_binding_tabs()
        form.addRow(self.binding_tabs)
        self.binding_errors = QLabel("")
        self.binding_errors.setWordWrap(True)
        form.addRow(self.binding_errors)
        for expression in self.editors:
            self._refresh_profiles(expression=expression)
        layout.addWidget(binding)
        self.motion_button = self._button("Show optional face-position movement controls", self._toggle_motion_controls)
        layout.addWidget(self.motion_button)
        self.motion_editor = MotionEditor(self)
        self.motion_editor.setVisible(self.settings.movement.enabled)
        layout.addWidget(self.motion_editor)
        for source in ("motion", "mouth", "brows"):
            self._refresh_movement_profiles(source)
        resources = QGroupBox("Independent resource test — no cameras or game inputs")
        resource_form = QFormLayout(resources)
        self.resource_label = config_combo([("Monitor/game baseline", "baseline"), ("Monitor + facial input", "facial"), ("Custom comparison", "custom")], "baseline", lambda: None)
        self.resource_game = QLineEdit()
        self.resource_game.setPlaceholderText("Optional process name; exact game.exe for PresentMon")
        self.resource_presentmon = QLineEdit()
        self.resource_presentmon.setPlaceholderText("Optional existing PresentMon console executable")
        resource_form.addRow("Condition label", self.resource_label)
        resource_form.addRow("Game process", self.resource_game)
        resource_form.addRow("Frame-time provider", self.resource_presentmon)
        resource_form.addRow(self._button("Choose PresentMon…", self._choose_presentmon))
        resource_form.addRow(self._button("Queue independent resource test", self._launch_resources))
        self.resource_status = QLabel("Starts in 30 seconds, warms up for 60 seconds, then samples for 5 minutes. The collector continues if Facial Input closes. It does not start/stop apps or change monitor settings; use the same game scene for comparisons. Includes Codex and collector memory.")
        self.resource_status.setWordWrap(True)
        resource_form.addRow(self.resource_status)
        layout.addWidget(resources)
        overlap = QLabel("Phone and face holds share each button. Mouth and eyebrow holds on the same button also merge. An overlapping hold can mask taps or turbo press edges. LT/RT use the higher value. Switching editor tabs does not pause output; changing settings does.")
        overlap.setWordWrap(True)
        layout.addWidget(overlap)

        self.calibration_box = QGroupBox("Compare comfortable expressions — no controller output")
        cal_layout = QVBoxLayout(self.calibration_box)
        instructions = QLabel("First collect your relaxed neutral face. Then collect several gentle mouth openings and eyebrow raises separately, with neutral breaks. Stop whenever uncomfortable; do not sustain or strain an expression. Test during ordinary play to check speech, blinking, posture, and lighting.")
        instructions.setWordWrap(True)
        cal_layout.addWidget(instructions)
        stages = QGridLayout()
        self.neutral_button = self._button("1. Sample neutral", lambda: self._begin_stage("neutral"))
        self.mouth_button = self._button("2. Sample mouth", lambda: self._begin_stage("mouth"))
        self.brows_button = self._button("3. Sample eyebrows", lambda: self._begin_stage("brows"))
        self.review_button = self._button("Stop / review calibration", self._finish_calibration)
        stages.addWidget(self.neutral_button, 0, 0)
        stages.addWidget(self.mouth_button, 0, 1)
        stages.addWidget(self.brows_button, 1, 0)
        stages.addWidget(self.review_button, 1, 1)
        cal_layout.addLayout(stages)
        self.calibration_status = QLabel("Collect neutral first.")
        self.calibration_status.setWordWrap(True)
        cal_layout.addWidget(self.calibration_status)
        self.calibration_feedback = QLabel("")
        self.calibration_feedback.setWordWrap(True)
        cal_layout.addWidget(self.calibration_feedback)
        self.calibration_box.hide()
        layout.addWidget(self.calibration_box)

        comparison = QGroupBox("Live expression scores")
        score_layout = QFormLayout(comparison)
        self.score_bars = {}
        for expression, title in (("mouth", "Mouth opening"), ("brows", "Eyebrow raising"), ("wink", "Left wink")):
            bar = QProgressBar()
            bar.setRange(0, 1000)
            bar.setFormat("0.000")
            bar.setMinimumHeight(28)
            self.score_bars[expression] = bar
            score_layout.addRow(title, bar)
        self.events = QLabel("Candidate activations: 0")
        score_layout.addRow(self.events)
        self.expression_events = QLabel("")
        self.expression_events.setWordWrap(True)
        score_layout.addRow(self.expression_events)
        self.movement_status = QLabel("")
        self.movement_status.setWordWrap(True)
        score_layout.addRow(self.movement_status)
        self.output_feedback = QLabel("Live output: 0 queued; 0 sent over TCP")
        self.output_feedback.setWordWrap(True)
        score_layout.addRow(self.output_feedback)
        layout.addWidget(comparison)
        self.health = QLabel("")
        self.health.setWordWrap(True)
        layout.addWidget(self.health)
        self.error_status = QLabel("")
        self.error_status.setWordWrap(True)
        layout.addWidget(self.error_status)
        self.preview_check = QCheckBox("Show camera preview")
        self.preview_check.setMinimumHeight(44)
        self.preview_check.toggled.connect(self._preview_toggled)
        layout.addWidget(self.preview_check)
        self.preview = PreviewLabel()
        self.preview.corner_selected.connect(self._region_corner)
        self.preview.hide()
        layout.addWidget(self.preview)
        self.region_instruction = QLabel("")
        self.region_instruction.setWordWrap(True)
        self.region_instruction.hide()
        layout.addWidget(self.region_instruction)
        self.region_cancel_button = self._button("Cancel face selection", self._cancel_region)
        self.region_cancel_button.hide()
        layout.addWidget(self.region_cancel_button)
        footer = QHBoxLayout()
        self.model_button = self._button("Choose model…", self._choose_model)
        self.save_button = self._button("Save settings", self._save)
        self.tray_button = self._button("Minimize to tray", self.minimize_to_tray)
        for button in (self.model_button, self.save_button, self.tray_button):
            footer.addWidget(button)
        layout.addLayout(footer)
        self.model_status = QLabel(f"Model: {self.model_path or 'choose a local .task model to enable inference'}")
        self.model_status.setWordWrap(True)
        layout.addWidget(self.model_status)
        self.save_status = QLabel("Changes pause output. Settings are saved only when you press Save settings.")
        self.save_status.setWordWrap(True)
        layout.addWidget(self.save_status)
        # Frequently watched feedback stays near the primary actions, above setup.
        layout.removeWidget(comparison)
        layout.removeWidget(self.health)
        layout.removeWidget(self.error_status)
        layout.insertWidget(4, self.health)
        layout.insertWidget(5, self.error_status)
        layout.insertWidget(6, comparison)
        area.setWidget(content)
        self.setCentralWidget(area)

    def _build_tray(self):
        self.tray = None
        if QSystemTrayIcon.isSystemTrayAvailable():
            self.tray = QSystemTrayIcon(self.style().standardIcon(QStyle.StandardPixmap.SP_ComputerIcon), self)
            self.tray.setToolTip("Facial Input — paused")
            menu = QMenu(self)
            for title, action in (("Open controls", self.show_controls), ("Pause", self.pause), ("Quit", self.quit)):
                item = QAction(title, self)
                item.triggered.connect(action)
                menu.addAction(item)
            self.tray.setContextMenu(menu)
            self.tray.activated.connect(lambda reason: self.show_controls() if reason == QSystemTrayIcon.ActivationReason.Trigger else None)
            self.tray.show()

    def _editor_tab_changed(self, *_):
        editor = self.binding_tabs.currentWidget()
        if editor is None:
            return
        self.editing_expression = editor.expression
        # Existing callers can still reach the currently displayed controls.
        for name in ("output_mode", "button", "preset", "on_threshold", "off_threshold", "neutral_threshold",
                     "threshold_error", "profile_choice", "profile_name", "profile_load_button", "profile_save_button",
                     "profile_status", "profile_directory_status"):
            setattr(self, name, getattr(editor, name))

    def _show_binding_tabs(self):
        visible = [self.settings.expression]
        if self.settings.simultaneous:
            visible.append("brows" if self.settings.expression == "mouth" else "mouth")
        if self.settings.wink_enabled:
            visible.append("wink")
        selected = self.editing_expression if self.editing_expression in visible else self.settings.expression
        blocked = self.binding_tabs.blockSignals(True)
        while self.binding_tabs.count():
            self.binding_tabs.removeTab(0)
        for expression in visible:
            self.binding_tabs.addTab(self.editors[expression], {"mouth": "Mouth opening", "brows": "Eyebrows", "wink": "Left wink"}[expression])
        self.binding_tabs.setCurrentWidget(self.editors[selected])
        self.binding_tabs.blockSignals(blocked)
        self.expression.setEnabled(not self.settings.simultaneous)
        self._editor_tab_changed()

    def _binding_changed(self, expression):
        if self._building:
            return
        editor = self.editors[expression]
        previous = self.settings
        output = editor.button.currentData()
        changes = dict(mode=editor.output_mode.currentData(), preset=editor.preset.currentData())
        if output in BUTTONS:
            changes["button"] = output
        self.settings = self.settings.with_binding(expression, **changes)
        if expression != "wink":
            configs = dict(self.settings.expression_movements)
            configs[expression] = replace(configs[expression], enabled=output not in BUTTONS,
                output="wasd" if output == "WASD_MOVEMENT" else "stick" if output == "STICK_MOVEMENT" else configs[expression].output)
            self.settings = replace(self.settings, expression_movements=configs)
            editor.movement_editor.setVisible(configs[expression].enabled)
            editor.output_mode.setEnabled(not configs[expression].enabled)
            if output not in BUTTONS:
                self._movement_config_changed(expression)
                if editor.movement_editor.error.text():
                    self.settings = previous
                    self._refresh_binding_errors()
                    return
            else:
                # Leaving movement preserves its last valid configuration; an
                # invalid unsaved movement draft cannot leave that route active.
                editor.movement_editor.error.clear()
                self._refresh_binding_errors()
        self.runtime.set_settings(self.settings)
        self.save_status.setText("Unsaved binding changes. All output paused. Press Save settings to persist.")
        self.refresh()

    def _simultaneous_changed(self, enabled):
        if self._building:
            return
        self.settings = replace(self.settings, simultaneous=enabled)
        self.runtime.set_settings(self.settings)
        self._show_binding_tabs()
        self._refresh_binding_errors()
        self.save_status.setText("Unsaved expression selection. All output paused. Press Save settings to persist.")
        self.refresh()

    def _wink_changed(self):
        if self._building:
            return
        self.settings = replace(self.settings, wink_enabled=self.wink_check.isChecked())
        self.runtime.set_settings(self.settings)
        self._show_binding_tabs()
        self._refresh_binding_errors()
        self.save_status.setText("Unsaved wink selection. Output paused.")
        self.refresh()

    def _expression_enabled_changed(self, expression):
        if self._building:
            return
        disabled = set(self.settings.disabled_expressions)
        if self.editors[expression].enabled.isChecked():
            disabled.discard(expression)
        else:
            disabled.add(expression)
        self.settings = replace(self.settings, disabled_expressions=tuple(sorted(disabled)))
        self.runtime.set_settings(self.settings)
        self._refresh_binding_errors()
        self.save_status.setText("Unsaved expression enable choice. Output paused.")
        self.refresh()

    def _toggle_motion_controls(self):
        shown = self.motion_editor.isHidden()
        self.motion_editor.setVisible(shown)
        self.motion_button.setText(("Hide" if shown else "Show") + " optional face-position movement controls")

    def _launch_resources(self):
        project = Path(__file__).resolve().parents[2]
        output = project / "private" / "resource-tests"
        command = [sys.executable, "-B", str(project / "scripts" / "collect_resources.py"),
                   "--delay", "30", "--warmup", "60", "--duration", "300", "--facial-pid", str(os.getpid()),
                   "--label", self.resource_label.currentData(), "--output-root", str(output)]
        if self.resource_game.text().strip():
            command.extend(("--game", self.resource_game.text().strip()))
        if self.resource_presentmon.text().strip():
            command.extend(("--presentmon", self.resource_presentmon.text().strip()))
        try:
            subprocess.Popen(command, cwd=project, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             close_fds=True)
            self.resource_status.setText(f"Queued independent collector: 30-second delay, 60-second warmup, 300-second sample. New run files under {output}. Keep comparison conditions stable; no app or camera is started or stopped.")
        except OSError as exc:
            self.resource_status.setText("Collector could not start: " + str(exc))

    def _choose_presentmon(self):
        path, _ = QFileDialog.getOpenFileName(self, "Select existing PresentMon console executable", "", "Windows executable (*.exe)")
        if path:
            self.resource_presentmon.setText(path)

    def _movement_controls(self, source):
        return self.motion_editor if source == "motion" else self.editors[source].movement_editor

    def _movement_config_changed(self, source):
        if self._building:
            return
        editor = self._movement_controls(source)
        try:
            config = editor.config()
            if source == "motion":
                proposed = replace(self.settings, movement=config)
            else:
                selected = self.editors[source].button.currentData()
                config = replace(config, enabled=selected not in BUTTONS,
                                 output="wasd" if selected == "WASD_MOVEMENT" else "stick" if selected == "STICK_MOVEMENT" else config.output)
                configs = dict(self.settings.expression_movements)
                configs[source] = config
                proposed = replace(self.settings, expression_movements=configs)
                editor.update_controls()
            proposed.validate()
        except ValueError as exc:
            self.pause()
            editor.error.setText(f"Invalid movement choices: {exc}. Output paused.")
            self._refresh_binding_errors()
            return
        editor.error.clear()
        self.settings = proposed
        self.runtime.set_settings(self.settings)
        self._refresh_binding_errors()
        self.save_status.setText("Unsaved movement choices. All output paused. Press Save settings to persist.")
        self.refresh()

    def _recenter(self, target):
        self.runtime.recenter(target)
        self.action_status.setText("Re-center requested: " + target + ". Keep a comfortable stable neutral pose; readiness appears in Test.")
        self.refresh()

    def _refresh_movement_profiles(self, source, selected=None):
        controls = self._movement_controls(source).profiles
        controls.choice.clear()
        try:
            paths = list_movement_profiles(source, self.movement_profiles_directory)
            for path in paths:
                profile = load_movement_profile(path)
                controls.choice.addItem(profile.name + " — " + profile.created_at, str(path))
            if not paths:
                controls.choice.addItem("No movement profiles for this source", None)
            if selected is not None:
                controls.choice.setCurrentIndex(controls.choice.findData(str(selected)))
        except (ValueError, OSError) as exc:
            controls.status.setText("Profiles unavailable: " + str(exc))

    def _save_movement_profile(self, source):
        editor = self._movement_controls(source)
        if editor.error.text():
            editor.profiles.status.setText("Correct movement choices before saving a profile.")
            return
        self.pause()
        try:
            path = save_movement_profile(self.settings, editor.profiles.name.text(), source, self.movement_profiles_directory)
            self._refresh_movement_profiles(source, path)
            editor.profiles.status.setText(f"Saved new {source} movement profile: {path}. Center and Test before Start.")
        except (OSError, ValueError) as exc:
            editor.profiles.status.setText("Profile not saved: " + str(exc))

    def _load_movement_profile(self, source):
        old = self._movement_controls(source)
        path = old.profiles.choice.currentData()
        if path is None:
            return
        self.pause()
        try:
            profile = load_movement_profile(Path(path))
            if profile.source != source:
                raise ValueError("Choose a profile for this movement source.")
            self.settings = apply_movement_profile(self.settings, profile)
            self.runtime.set_settings(self.settings)
            self._building = True
            if source == "motion":
                fresh = MotionEditor(self)
                old.parentWidget().layout().replaceWidget(old, fresh)
                self.motion_editor = fresh
            else:
                binding = self.editors[source]
                fresh = ExpressionMovementEditor(self, source)
                binding.layout().replaceWidget(old, fresh)
                binding.movement_editor = fresh
                config = self.settings.expression_movements[source]
                output = "WASD_MOVEMENT" if config.output == "wasd" else "STICK_MOVEMENT"
                binding.button.setCurrentIndex(binding.button.findData(output if config.enabled else self.settings.binding_for(source).button))
                binding.output_mode.setEnabled(not config.enabled)
                fresh.setVisible(config.enabled)
            old.hide()
            old.deleteLater()
            for form in fresh.findChildren(QFormLayout):
                form.setRowWrapPolicy(QFormLayout.RowWrapPolicy.WrapLongRows)
                form.setFieldGrowthPolicy(QFormLayout.FieldGrowthPolicy.AllNonFixedFieldsGrow)
            self._building = False
            self._refresh_movement_profiles(source, path)
            fresh.profiles.name.setText(profile.name)
            fresh.profiles.status.setText("Loaded movement choices. Current center is not restored; Center and Test before Start.")
            self._refresh_binding_errors()
            self.save_status.setText("Unsaved movement profile. Press Save settings to persist.")
        except (OSError, ValueError) as exc:
            self._building = False
            old.profiles.status.setText("Profile not loaded: " + str(exc))
        self.refresh()

    def _refresh_binding_errors(self):
        errors = [f"{expression.capitalize()}: {self.editors[expression].threshold_error.text()}"
                  for expression in self.settings.active_expressions if self.editors[expression].threshold_error.text()]
        if hasattr(self, "toggle_error") and self.toggle_error.text():
            errors.append("Start / Pause shortcut: " + self.toggle_error.text())
        for expression in self.settings.active_expressions:
            movement = self.editors[expression].movement_editor
            if movement and self.editors[expression].button.currentData() not in BUTTONS and movement.error.text():
                errors.append(f"{expression}: {movement.error.text()}")
        if hasattr(self, "motion_editor") and self.motion_editor.error.text():
            errors.append("Motion: " + self.motion_editor.error.text())
        self.binding_errors.setText("\n".join(errors))
        return bool(errors)

    def _sync_threshold_widgets(self, expression=None):
        expression = expression or self.editing_expression
        editor = self.editors[expression]
        thresholds = self.settings.wink_thresholds if expression == "wink" else self.settings.thresholds[expression]
        for widget, key in ((editor.on_threshold, "on"), (editor.off_threshold, "off"), (editor.neutral_threshold, "neutral")):
            blocked = widget.blockSignals(True)
            widget.setValue(thresholds.get(key, thresholds["off"]))
            widget.blockSignals(blocked)

    def _settings_changed(self, *_):
        if self._building:
            return
        expression_changed = self.expression.currentData() != self.settings.expression
        if expression_changed:
            self.settings = self.settings.choose_primary(self.expression.currentData())
        self.settings = replace(self.settings, player=self.player.currentData(), fps=self.fps.currentData())
        if expression_changed:
            self._show_binding_tabs()
            self._refresh_binding_errors()
        self.runtime.set_settings(self.settings)
        self.save_status.setText("Unsaved changes. Output paused. Press Save settings to persist.")
        self.refresh()

    def _camera_selection_changed(self, *_):
        if self._building:
            return
        self._cancel_region()
        self.pause()
        self.camera_index.setEnabled(self.camera_source.currentData() == "webcam")
        self.camera_resolution.setEnabled(self.camera_source.currentData() == "webcam")
        self.action_status.setText("Camera selection pending. Press Apply camera / retry to use it.")

    def _pending_camera(self):
        return (self.camera_source.currentData(), self.camera_index.currentData(), self.camera_resolution.currentData()) != (
            self.settings.camera_source, self.settings.camera_index, self.settings.camera_resolution)

    def _camera_key(self):
        return (self.settings.camera_source, self.settings.camera_index,
                self.settings.camera_resolution, self.settings.camera_roi)

    def _apply_camera(self):
        self._cancel_region()
        self.pause()
        previous = self._camera_key()
        geometry_changed = (self.settings.camera_source, self.settings.camera_index) != (
            self.camera_source.currentData(), self.camera_index.currentData())
        self.settings = replace(self.settings, camera_source=self.camera_source.currentData(),
                                camera_index=self.camera_index.currentData(),
                                camera_resolution=self.camera_resolution.currentData(),
                                camera_roi=None if geometry_changed else self.settings.camera_roi)
        # The runtime performs capture shutdown/replacement off the UI thread.
        self.runtime.set_settings(self.settings)
        if previous == self._camera_key():
            self.runtime.restart_camera()
        self._stage = None
        self._calibration_completed.clear()
        self.save_status.setText("Camera applied; output paused. Review the face region and use Test before Start. Press Save settings to persist.")
        self.refresh()

    def _select_region(self):
        if self.settings.camera_source != "webcam" or self._pending_camera():
            self.action_status.setText("Apply a standalone webcam selection before selecting its face region.")
            return
        if self._selecting_region:
            return
        self.pause()
        self._region_before_selection = self.settings.camera_roi
        self._preview_before_selection = self.preview_check.isChecked()
        previous = self._camera_key()
        self.settings = replace(self.settings, camera_roi=None)
        self.runtime.set_settings(self.settings)
        if previous == self._camera_key():
            self.runtime.restart_camera()
        # The restarted full-frame source must replace any cached cropped view.
        self.preview.clear()
        self._selecting_region = self.preview.selection_enabled = True
        self.preview.setMaximumHeight(500)
        self.preview.setMinimumHeight(320)
        self.preview.first_corner = None
        self._first_region_geometry = None
        self.region_instruction.setText("Wait for the full camera view. Tap one corner around your face, then the opposite corner. No dragging; output remains paused.")
        self.region_instruction.show()
        self.region_cancel_button.show()
        self.preview_check.setChecked(True)
        self.refresh()
        QTimer.singleShot(0, self._show_region_selection)

    def _show_region_selection(self):
        if not self._selecting_region:
            return
        self.preview.setMaximumHeight(max(320, min(500, self._scroll.viewport().height() - 190)))
        self._scroll.ensureWidgetVisible(self.region_cancel_button, 0, 8)
        self._scroll.ensureWidgetVisible(self.preview, 0, 8)

    def _finish_region_ui(self):
        self._selecting_region = self.preview.selection_enabled = False
        self.preview.first_corner = None
        self._first_region_geometry = None
        self.preview.update()
        self.preview.setMinimumHeight(160)
        self.preview.setMaximumHeight(280)
        self.region_cancel_button.hide()
        self.region_instruction.hide()

    def _cancel_region(self):
        if not self._selecting_region:
            return
        self._finish_region_ui()
        self.settings = replace(self.settings, camera_roi=self._region_before_selection)
        self.runtime.set_settings(self.settings)
        self.preview.clear()
        self.preview_check.setChecked(self._preview_before_selection)
        self.save_status.setText("Face selection cancelled; previous region restored. Output paused.")
        self.refresh()

    def _region_corner(self, point):
        if not self._selecting_region:
            return
        if self.preview.first_corner is None:
            self.preview.first_corner = point
            self._first_region_geometry = self._selection_geometry(self._last_snapshot)
            self.preview.update()
            self.region_instruction.setText("First corner selected. Tap the opposite corner around your face, or Cancel. Output remains paused.")
            return
        first = self.preview.first_corner
        left, top = min(first[0], point[0]), min(first[1], point[1])
        width, height = abs(point[0] - first[0]), abs(point[1] - first[1])
        if width < .02 or height < .02:
            self.preview.first_corner = None
            self._first_region_geometry = None
            self.preview.update()
            self.region_instruction.setText("Region too small. Tap two opposite corners farther apart around your face, or Cancel.")
            return
        self._finish_region_ui()
        self.settings = replace(self.settings, camera_roi=(left, top, width, height))
        self.runtime.set_settings(self.settings)
        self.preview.clear()
        self.save_status.setText("Face region applied; output paused. Check the cropped preview and Test. Press Save settings to persist.")
        self.refresh()

    @staticmethod
    def _selection_geometry(state):
        capture = state.get("capture_info") or {}
        width, height = capture.get("source_width"), capture.get("source_height")
        if width and height:
            return width, height
        frame = state.get("frame")
        return tuple(frame.shape[:2]) if frame is not None else None

    def _clear_region(self):
        if self.settings.camera_source != "webcam" or self._pending_camera():
            self.action_status.setText("Apply a standalone webcam selection before clearing its face region.")
            return
        self._cancel_region()
        self.pause()
        self.settings = replace(self.settings, camera_roi=None)
        self.runtime.set_settings(self.settings)
        self.preview.clear()
        self.save_status.setText("Full camera view selected; output paused. Use Test and explicitly Save settings to persist.")
        self.refresh()

    def _refresh_profiles(self, selected=None, expression=None):
        expression = expression or self.editing_expression
        editor = self.editors[expression]
        editor.profile_choice.clear()
        try:
            paths = list_profiles(expression, self.profiles_directory)
            for path in paths:
                profile = load_profile(path)
                saved_at = datetime.fromisoformat(profile.created_at).astimezone().strftime("%m/%d/%Y %I:%M:%S %p")
                editor.profile_choice.addItem(f"{profile.name} — {saved_at}", str(path))
            if selected is not None:
                editor.profile_choice.setCurrentIndex(editor.profile_choice.findData(str(selected)))
            if not paths:
                editor.profile_choice.addItem("No profiles for this expression", None)
            editor.profile_load_button.setEnabled(bool(paths))
        except (OSError, ValueError) as exc:
            editor.profile_load_button.setEnabled(False)
            editor.profile_status.setText(f"Profiles unavailable: {exc}")

    def _save_profile(self, expression=None):
        expression = expression or self.editing_expression
        editor = self.editors[expression]
        if editor.threshold_error.text():
            editor.profile_status.setText("Correct the thresholds before saving a profile.")
            return
        self.pause()
        try:
            # This explicit save accepts only the selected expression. Commit
            # acceptance to running settings only after its new file is saved.
            reviewed = (replace(self.settings, wink_calibrated=True) if expression == "wink" else
                        replace(self.settings, calibrated=tuple(dict.fromkeys((*self.settings.calibrated, expression)))))
            # The file contains only thresholds; this temporary expression choice
            # must never become the running primary controller binding.
            path = (save_profile(reviewed, editor.profile_name.text(), self.profiles_directory, expression="wink")
                    if expression == "wink" else save_profile(replace(reviewed, expression=expression), editor.profile_name.text(), self.profiles_directory))
            self.settings = reviewed
            self.runtime.set_settings(self.settings)
            self._refresh_profiles(path, expression)
            editor.profile_status.setText(f"Saved {expression} calibration: {path}. Thresholds accepted; all output paused. Use Test before Start.")
            self.save_status.setText("Accepted thresholds. Press Save settings to restore this choice next launch.")
        except (OSError, ValueError) as exc:
            editor.profile_status.setText(f"Profile not saved: {exc}")
        self.refresh()

    def _load_profile(self, expression=None):
        expression = expression or self.editing_expression
        editor = self.editors[expression]
        path = editor.profile_choice.currentData()
        if path is None:
            return
        self.pause()
        try:
            profile = load_profile(Path(path))
            if profile.expression != expression:
                raise ValueError("Choose a profile for the selected expression.")
            self.settings = apply_profile(self.settings, profile)
            self.runtime.set_settings(self.settings)
            self._sync_threshold_widgets(expression)
            editor.threshold_error.clear()
            self._refresh_binding_errors()
            editor.profile_name.setText(profile.name)
            editor.profile_status.setText(f"Loaded {profile.name}. All output paused. Use Test before Start.")
            self.save_status.setText("Unsaved loaded calibration. Press Save settings to restore it next launch.")
        except (OSError, ValueError) as exc:
            editor.profile_status.setText(f"Profile not loaded: {exc}")
        self.refresh()

    def _threshold_changed(self, expression=None):
        if self._building:
            return
        expression = expression or self.editing_expression
        editor = self.editors[expression]
        on, off = editor.on_threshold.value(), editor.off_threshold.value()
        neutral = editor.neutral_threshold.value()
        if off >= on:
            self.pause()
            editor.threshold_error.setText("Release threshold must be lower than activation threshold. Output remains paused.")
            self._refresh_binding_errors()
            return
        if neutral >= on:
            self.pause()
            editor.threshold_error.setText("Neutral threshold must be lower than activation threshold. Output remains paused.")
            self._refresh_binding_errors()
            return
        previous = self.settings
        values = {"on": on, "off": off, "neutral": neutral}
        if expression == "wink":
            self.settings = replace(self.settings, wink_thresholds=values)
        else:
            thresholds = {key: dict(value) for key, value in self.settings.thresholds.items()}
            thresholds[expression] = values
            self.settings = replace(self.settings, thresholds=thresholds)
        try:
            self.settings.validate()
        except ValueError as exc:
            self.settings = previous
            self.pause()
            editor.threshold_error.setText("Invalid thresholds: " + str(exc) + ". Output paused.")
            self._refresh_binding_errors()
            return
        editor.threshold_error.clear()
        self._refresh_binding_errors()
        self.runtime.set_settings(self.settings)
        self.save_status.setText("Unsaved thresholds. Output paused.")
        self.refresh()

    def _toggle_shortcut_changed(self):
        if self._building:
            return
        self.runtime.set_mode("paused")
        try:
            settings = replace(self.settings, live_toggle_keys=self.toggle_keys.values,
                               live_toggle_buttons=self.toggle_buttons.values).validate()
        except ValueError as exc:
            self.toggle_error.setText(str(exc))
            self.refresh()
            return
        self.toggle_error.clear()
        self.settings = settings
        self.runtime.set_settings(settings)
        self.save_status.setText("Unsaved Start / Pause shortcut. All output paused. Press Save settings to persist.")
        self.refresh()

    def _toggle_live(self):
        if self._selecting_region:
            self.action_status.setText("Finish or cancel face selection before starting controller output.")
            return
        if self._last_snapshot.get("mode") == "live":
            self.pause()
            return
        if self._refresh_binding_errors():
            self.action_status.setText("Correct the thresholds before starting.")
            return
        missing = [expression for expression in self.settings.active_expressions
                   if not (self.settings.wink_calibrated if expression == "wink" else expression in self.settings.calibrated)]
        if missing:
            if any(expression != "wink" for expression in missing):
                self._open_calibration()
            self.action_status.setText("Calibrate " + ", ".join(missing) + ", or test manual thresholds and press Save current calibration in each expression's tab before Start.")
            return
        if not self.settings.active_expressions and not self.settings.movement.enabled:
            self.action_status.setText("Enable at least one expression or movement axis before Start.")
            return
        if not self.runtime.set_mode("live"):
            self.action_status.setText("Start unavailable. Check calibration, model, camera, and receiver status.")
        else:
            self.action_status.clear()
        self.refresh()

    def _toggle_test(self):
        if self._selecting_region:
            self.action_status.setText("Finish or cancel face selection before Test.")
            return
        self.action_status.clear()
        self.runtime.set_mode("paused" if self._last_snapshot.get("mode") == "test" else "test")
        self.refresh()

    def pause(self):
        self.action_status.clear()
        self.runtime.set_mode("paused")
        self._stage = None
        self.refresh()

    def _open_calibration(self):
        if self._selecting_region:
            self.action_status.setText("Finish or cancel face selection before calibration.")
            return
        self.pause()
        self._calibration_completed.clear()
        self.calibration_box.show()
        self.calibration_status.setText("Select Sample neutral, then collect comfortable mouth and eyebrow repetitions. No inputs are sent.")

    def _begin_stage(self, stage):
        if self._selecting_region:
            self.calibration_status.setText("Finish or cancel face selection before collecting samples.")
            return
        if stage != "neutral" and "neutral" not in self._calibration_completed and self._stage != "neutral":
            self.calibration_status.setText("Collect a neutral sample first.")
            return
        if self._stage:
            self._calibration_completed.add(self._stage)
        if stage == "neutral":
            self._calibration_completed.clear()
        self.runtime.begin_calibration(stage)
        self._stage = stage
        self.calibration_status.setText(f"Sampling {stage}. Use comfortable repetitions with neutral breaks; select the next stage when ready.")
        self.refresh()

    def _finish_calibration(self):
        if self._stage:
            self._calibration_completed.add(self._stage)
        self._stage = None
        try:
            result = self.runtime.finish_calibration()
            # Only expressions sampled in this collection may replace calibration.
            result = {key: value for key, value in result.items() if key in self._calibration_completed}
            if not result:
                raise ValueError("Collect fresh mouth or eyebrow samples with clear separation before review.")
            thresholds = {key: dict(value) for key, value in self.settings.thresholds.items()}
            thresholds.update(result)
            calibrated = tuple(sorted(set(self.settings.calibrated) | set(result)))
            self.settings = replace(self.settings, thresholds=thresholds, calibrated=calibrated)
            self.runtime.set_settings(self.settings)
            for expression in result:
                self._sync_threshold_widgets(expression)
                self.editors[expression].threshold_error.clear()
            self._refresh_binding_errors()
            self.calibration_status.setText("Calibration ready: " + ", ".join(result) + ". Use Test to compare reliability. Press Save settings to persist.")
            self.save_status.setText("Unsaved calibration. Output paused.")
        except ValueError as exc:
            self.runtime.set_mode("paused")
            self.calibration_status.setText(str(exc))
        self.refresh()

    def _preview_toggled(self, checked):
        if not checked and self._selecting_region:
            self._preview_before_selection = False
            self._cancel_region()
        self.runtime.set_preview(checked)
        self.preview.setVisible(checked)
        if not checked:
            self.preview.clear()
        self.refresh()

    def _choose_model(self):
        self._cancel_region()
        self.pause()
        path, _ = QFileDialog.getOpenFileName(self, "Select local Face Landmarker model", str(self.model_path.parent) if self.model_path else "", "MediaPipe model (*.task);;All files (*)")
        if not path:
            return
        self.runtime.stop()
        self.model_path = Path(path)
        self.runtime = self._runtime_factory(self.settings, self.model_path, feed_name=self.feed_name, demo=self.demo)
        self.runtime.set_preview(self.preview_check.isChecked())
        self.runtime.start_workers()
        self.model_status.setText(f"Model: {self.model_path}")
        self.save_status.setText("Unsaved model selection. Output paused. Press Save settings to persist.")
        self.refresh()

    def _save(self):
        if self._selecting_region:
            self.save_status.setText("Finish or cancel face selection before saving settings.")
            return
        if self._refresh_binding_errors():
            self.save_status.setText("Correct the thresholds before saving.")
            return
        if self._pending_camera():
            self.save_status.setText("Apply the pending camera selection before saving settings.")
            return
        try:
            self.settings = replace(self.settings, model_path=str(self.model_path) if self.model_path else None)
            saved_path = save_settings(self.settings, self.settings_path)
            self.save_status.setText(f"Saved settings: {saved_path or self.settings_path}")
        except (OSError, ValueError) as exc:
            self.save_status.setText(f"Settings not saved: {exc}")

    def refresh(self):
        state = self.runtime.snapshot()
        self._last_snapshot = state
        if getattr(self.runtime, "consume_live_toggle_start", lambda: False)():
            # Reuse the exact button action: invalid UI drafts and face-region
            # selection must not be bypassed by the background shortcut.
            self._toggle_live()
            return
        if self._selecting_region and self.preview.first_corner is not None:
            geometry = self._selection_geometry(state)
            if geometry is not None and self._first_region_geometry is not None and geometry != self._first_region_geometry:
                self.preview.first_corner = None
                self._first_region_geometry = None
                self.preview.update()
                self.region_instruction.setText("The camera dimensions changed. Tap two corners again on the full camera view, or Cancel. Output remains paused.")
        mode = state.get("mode", "paused")
        for control in (self.start_button, self.test_button, self.calibrate_button):
            control.setEnabled(not self._selecting_region)
        standalone = self.settings.camera_source == "webcam" and not self._pending_camera()
        self.region_select_button.setEnabled(standalone and not self._selecting_region)
        self.region_clear_button.setEnabled(standalone)
        if self._selecting_region:
            self.region_status.setText("Selecting on the full camera view; output paused. Complete two taps below or Cancel.")
        elif self.settings.camera_source == "shared":
            self.region_status.setText("Shared face region is selected in Tyler Safety Monitor.")
        elif self.settings.camera_roi is None:
            self.region_status.setText("Face region: full camera view")
        else:
            x, y, width, height = self.settings.camera_roi
            self.region_status.setText(f"Face region: left {x:.1%}, top {y:.1%}, width {width:.1%}, height {height:.1%}")
        self.start_button.setText("Pause" if mode == "live" else "Start")
        self.test_button.setText("Stop Test" if mode == "test" else "Test (no output)")
        if mode == "paused":
            self.mode_status.setText("Paused — no controller output")
        elif mode == "calibrate":
            self.mode_status.setText("Calibrate — no controller output")
        else:
            expression_states = state.get("expression_states", {})
            if (self.settings.simultaneous or self.settings.wink_enabled) and expression_states:
                summaries = []
                for expression in self.settings.active_expressions:
                    individual = expression_states.get(expression, {})
                    status = "active" if individual.get("activated") else "ready" if individual.get("armed") else "waiting for neutral"
                    summaries.append(f"{expression.capitalize()}: {status}")
                self.mode_status.setText(f"{mode.capitalize()} — " + "; ".join(summaries))
            else:
                self.mode_status.setText(f"{mode.capitalize()} — " + ("expression active" if state.get("activated") else
                                        "ready" if state.get("armed") else "waiting for fresh neutral"))
        for expression, bar in self.score_bars.items():
            score = state.get("scores", {}).get(expression)
            if score is None:
                bar.setValue(0)
                bar.setFormat("No result")
            else:
                bar.setValue(round(float(score) * 1000))
                bar.setFormat(f"{float(score):.3f}")
        self.events.setText(f"Candidate activations: {state.get('event_count', 0)}")
        expression_states = state.get("expression_states", {})
        self.expression_events.setVisible(self.settings.simultaneous or self.settings.wink_enabled)
        if self.settings.simultaneous or self.settings.wink_enabled:
            self.expression_events.setText("; ".join(f"{expression.capitalize()}: {expression_states.get(expression, {}).get('event_count', 0)} candidates"
                                                     for expression in self.settings.active_expressions))
        movement = state.get("movement", {}) or {}
        raw_displacement = movement.get("raw", {}) or {}
        self.motion_editor.boosts.refresh_units()
        owner = movement.get("owner")
        route = self.settings.expression_movements[owner].output if owner in self.settings.expression_movements else self.settings.movement.output
        self.motion_editor.horizontal.show_live_displacement("x", raw_displacement, movement, state["mode"], route)
        self.motion_editor.vertical.show_live_displacement("y", raw_displacement, movement, state["mode"], route,
                                                         boost_y_units=self.motion_editor.boosts.threshold_units()[0],
                                                         output_axis="x" if self.settings.movement.vertical_to_horizontal else "y")
        boosts = movement.get("boosts", {}) or {}
        self.movement_status.setText("Movement: " + str(movement.get("status", "disabled")) +
            "; owner: " + str(movement.get("owner", "none")) +
            "; face displacement (reference-face units): " + str(movement.get("raw", {})) +
            "; filtered vector: " + str(movement.get("vector", (0, 0))) +
            "; ready axes: " + str(movement.get("ready_axes", ())) +
            "; neutral axes: " + str(movement.get("neutral_axes", ())) +
            "; boost Holds: " + str(movement.get("boost_holds", boosts.get("holds", ()))) +
            "; boost Taps: " + str(movement.get("boost_taps", boosts.get("taps", ()))) +
            "; boost tap candidates: " + str(movement.get("boost_tap_count", 0)))
        queued, sent = state.get("output_queued_count", 0), state.get("output_sent_count", 0)
        last_output = state.get("last_output_sent", "")
        self.output_feedback.setText(f"Live presses: {queued} queued; {sent} sent over TCP" +
                                     (f". Last: {last_output}" if last_output else "") +
                                     ". TCP send does not confirm game recognition; the receiver console does not print each facial press.")
        def number(value, suffix):
            return "—" if value is None else f"{float(value):.1f}{suffix}"
        self.health.setText(f"Camera: {state.get('camera_status', 'unknown')}    Receiver: {state.get('receiver_status', 'unknown')}\n"
                            f"Inference: {state.get('worker_status', 'unknown')}    Rate: {number(state.get('fps'), ' fps')}\n"
                            f"Result age: {number(state.get('result_age_ms'), ' ms')}    Inference time: {number(state.get('inference_ms'), ' ms')}")
        if state.get("shortcut_status"):
            self.health.setText(self.health.text() + "\nInput shortcuts: " + str(state['shortcut_status']))
        capture = state.get("capture_info")
        if isinstance(capture, dict) and capture:
            self.health.setText(self.health.text() + "\n" +
                f"Capture requested: {capture.get('requested_resolution', self.settings.camera_resolution)}; " +
                f"delivered: {capture.get('source_width', '—')} × {capture.get('source_height', '—')}; " +
                f"driver FPS: {number(capture.get('source_fps'), ' fps')}; " +
                f"format: {capture.get('source_fourcc') or 'unavailable'}")
        self.error_status.setText(str(state.get("last_error") or ""))
        self.calibration_feedback.setText(str(state.get("calibration_status") or ""))
        if self.preview_check.isChecked():
            self.preview.show_frame(state.get("frame"))
        if self.tray:
            self.tray.setToolTip(f"Facial Input — {mode}")

    def show_controls(self):
        self.showNormal()
        self.raise_()
        self.activateWindow()

    def minimize_to_tray(self):
        if self.tray:
            self.hide()
        else:
            self.showMinimized()

    def closeEvent(self, event):
        self.quit()
        event.accept()

    def shutdown(self):
        if self._quitting:
            return
        self._quitting = True
        self.timer.stop()
        self.runtime.stop()
        if self.tray:
            self.tray.hide()
        self.stopped.emit()

    def quit(self):
        self.shutdown()
        QApplication.instance().quit()
