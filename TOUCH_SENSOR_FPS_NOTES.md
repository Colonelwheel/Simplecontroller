# Touch Sensor FPS Prototype Notes

## Scope

This document records the Samsung Galaxy S22 touch diagnostic results, the longer-term three-stage FPS concept, and the implemented reusable two-state Aim/Shoot calibration wizard. The two-state mode can drive releasable trigger, mouse-button, keyboard, Xbox-button, and stick-macro payloads while preserving the original manual three-stage mode.

## Long-term control goal

Use one continuous finger contact for all of the following:

- X/Y movement controls aim through mouse movement or controller right-stick output.
- Light contact aims without holding a trigger.
- A medium contact level holds LT for controller ADS or right mouse for mouse ADS.
- The highest contact level also holds RT for controller fire or left mouse for mouse fire.
- Aim continues without interruption while ADS or fire is held.
- Dropping from the highest level to the medium level releases fire but keeps ADS held.
- Dropping to the light level releases both fire and ADS.
- Lifting the finger always releases all held outputs and centers the virtual stick when applicable.

The natural high-level state progression is:

```text
AIM_ONLY -> ADS -> ADS_AND_FIRE
```

For the highest level, the initial expected behavior is to keep ADS held and add fire. A later configuration could optionally make the highest level fire without ADS.

## Diagnostic setup

- Device: Samsung Galaxy S22
- Signals: MotionEvent pressure, size, touch major, and touch minor
- Recorded states: normal, flattened, and pressed
- Still and moving stages were performed for each state.
- Each stage used a 0.5 second settling period followed by 2.0 seconds of recording.
- Total recorded samples: 1811
- Total movement path: 3120.733 px
- The 0.5 second settling period was reported as too short to change gestures comfortably.

## Recorded results

The ranges include both still and moving samples.

| State | Signal | Average | Minimum | Maximum | Samples |
|---|---|---:|---:|---:|---:|
| Normal | Pressure | 1.000 | 1.000 | 1.000 | 501 |
| Normal | Size | 0.028 | 0.018 | 0.045 | 501 |
| Normal | Touch major | 3.576 px | 2.087 px | 6.262 px | 501 |
| Normal | Touch minor | 2.411 px | 1.670 px | 3.757 px | 501 |
| Flattened | Pressure | 1.000 | 1.000 | 1.000 | 737 |
| Flattened | Size | 0.053 | 0.031 | 0.090 | 737 |
| Flattened | Touch major | 7.078 px | 3.757 px | 12.942 px | 737 |
| Flattened | Touch minor | 4.195 px | 2.505 px | 6.262 px | 737 |
| Pressed | Pressure | 1.000 | 1.000 | 1.000 | 573 |
| Pressed | Size | 0.045 | 0.023 | 0.074 | 573 |
| Pressed | Touch major | 5.769 px | 3.340 px | 8.350 px | 573 |
| Pressed | Touch minor | 3.716 px | 2.087 px | 5.427 px | 573 |

Moving-average change relative to the still average:

| State | Pressure | Size | Touch major | Touch minor |
|---|---:|---:|---:|---:|
| Normal | 0.000 | -0.009 | -0.906 px | -0.481 px |
| Flattened | 0.000 | -0.021 | -2.764 px | -1.260 px |
| Pressed | 0.000 | -0.020 | -2.766 px | -1.106 px |

## Interpretation

### Pressure

Finger pressure is effectively unavailable on this device through MotionEvent. It remained 1.000 for normal, flattened, and harder presses. It must not be used for an FPS threshold based on this run.

The diagnostic incorrectly rated pressure separation as Good because equal, zero-width ranges were handled as if they had no overlap. The suggested pressure ON and OFF values of 1.000 are invalid and must be ignored. That scoring edge case was subsequently fixed in the diagnostic.

### Contact geometry

Size, touch major, and touch minor all responded to increased finger contact. Their averages were consistently ordered:

```text
normal < pressed < flattened
```

Relative to normal touch:

- Flattened Size increased by about 89 percent.
- Flattened TouchMajor increased by about 98 percent.
- Flattened TouchMinor increased by about 74 percent.
- Pressed Size increased by about 61 percent.
- Pressed TouchMajor increased by about 61 percent.
- Pressed TouchMinor increased by about 54 percent.

TouchMajor currently shows the strongest individual flattening response. The diagnostic named Size as the best signal only because Size, TouchMajor, and TouchMinor all received the same Poor overlap rank and Size appeared first in the tie. That label is not evidence that Size is actually best.

### Motion

All contact-geometry signals dropped during movement, particularly in the flattened and pressed states. This is the primary risk for an aiming surface. A fixed threshold could release ADS or fire merely because the finger started moving, even though the intended finger state did not change.

The full ranges overlap substantially:

- Normal and pressed overlap for all three geometry signals.
- Pressed and flattened overlap heavily.
- Normal and flattened also overlap, especially while moving or changing posture.

Hysteresis will reduce chatter near a threshold, but it cannot make physically overlapping states unambiguous by itself.

## Proposed two-threshold model

The software model is feasible and is similar to ordered stick-boost thresholds, with two trigger boundaries instead of one:

```text
Light contact:
    AIM_ONLY

Contact score crosses ADS_ON:
    Hold LT or right mouse

Contact score crosses FIRE_ON:
    Keep ADS held and also hold RT or left mouse

Contact score falls below FIRE_OFF:
    Release RT or left mouse; remain in ADS

Contact score falls below ADS_OFF:
    Release LT or right mouse; remain in aim-only mode

ACTION_UP or ACTION_CANCEL:
    Release both outputs and center stick output
```

Required threshold ordering:

```text
ADS_OFF < ADS_ON < FIRE_OFF < FIRE_ON
```

Each boundary needs hysteresis. A short median or low-pass filter and a small confirmation time may also be needed, but fire must remain responsive. Touch ownership must stay with one aiming surface so changing states never interrupts X/Y aiming.

## Signal-combination candidates

Using one raw value may not be reliable enough. Candidates for a contact score include:

- A calibrated weighted combination of Size, TouchMajor, and TouchMinor.
- A robust median of normalized versions of the three signals.
- TouchMajor multiplied by TouchMinor as an approximate contact-area measure.
- TouchMajor divided by TouchMinor as a shape measure. Ratios of the reported averages were approximately 1.48 normal, 1.69 flattened, and 1.55 pressed.
- Velocity compensation or separately calibrated moving thresholds, because movement reduces the measured contact area.

These must be evaluated from per-sample data rather than averages alone. The current report does not include distributions, correlations, or synchronized raw samples.

## Feasibility assessment

- Implementing the three states and safe output transitions is straightforward.
- Continuous aiming while either trigger state is held is straightforward if the aiming view retains the pointer.
- One geometry-driven secondary input looks plausible.
- Two reliable geometry levels for separate ADS and fire are promising from the ordered averages, but are not proven because the ranges overlap heavily.
- The design should not use the S22 pressure value.
- No production thresholds should be selected from this single run.

## Next diagnostic work

Before integrating controller or mouse outputs, collect a stronger dataset with:

- A 1.5 to 2.0 second settling period.
- Longer recording windows for each state.
- Multiple repeated low, medium, and high cycles rather than one pass.
- Separate still and moving statistics instead of only combined ranges.
- Median, standard deviation, and percentile ranges such as P10/P50/P90.
- Raw synchronized Size, TouchMajor, TouchMinor, X/Y, and movement-speed samples.
- Contact-score candidates and the percentage of each state that would be misclassified at proposed thresholds.
- Correct handling of constant signals such as Pressure.

The next decision is whether moving low, medium, and high contact levels remain sufficiently ordered to support both ADS and fire without unacceptable accidental transitions.

## Configurable Touch Aim control prototype

A new `TOUCH_AIM` control prototype was added after the initial diagnostic. It is visually based on a touchpad because continuous aiming is its primary interaction, but it has a distinct reticle, contact-level border color, and live score/level display.

Editable control properties include:

- Mouse or right-stick aim output.
- Aim sensitivity and optional Y inversion.
- Right-stick finger speed corresponding to full deflection.
- Inclusion of Size, TouchMajor, and TouchMinor in the contact score.
- Size multiplier and score smoothing.
- Low, medium, and high ON thresholds.
- Shared downward hysteresis.
- A comma-separated command payload for each level.
- Press-once or hold behavior for each level.
- Whether lower-level holds remain active at higher levels.

The initial combined score is the average of the enabled inputs:

```text
Size * SizeMultiplier
TouchMajor
TouchMinor
```

SizeMultiplier defaults to 100 so the S22 Size measurement is in approximately the same numeric range as TouchMajor and TouchMinor. Default thresholds are 2.0, 4.0, and 5.2 based only on the first diagnostic averages; they are starting points for manual tuning, not validated production values.

The levels are cumulative when `Keep lower holds at higher levels` is enabled. With the default medium payload `LT:1.0` and high payload `RT:1.0`, entering high keeps LT held and adds RT. When the option is disabled, entering high releases the medium hold and activates only the high mapping.

Press-once actions fire only on upward threshold crossings. Hold actions receive explicit releases when their level is no longer active, on finger lift/cancel, when edit mode opens, when the activity pauses, or when the control is removed.

## Hysteresis behavior

Hysteresis gives each level separate effective ON and OFF boundaries. A level activates when its score rises to its configured ON threshold, but it remains active until the score falls below the ON threshold minus Hysteresis.

With the initial Medium threshold and Hysteresis defaults:

```text
Medium ON:  4.00
Hysteresis: 0.25
Medium OFF: 4.00 - 0.25 = 3.75
```

With the initial High threshold:

```text
High ON:  5.20
High OFF: 5.20 - 0.25 = 4.95
```

This gap prevents repeated transitions when the contact score fluctuates near a threshold. Increasing Hysteresis makes a level more stable but also makes it remain active longer while the finger relaxes. Setting Hysteresis to zero makes ON and OFF identical and can cause chatter.

When falling from High, the handler evaluates the lower OFF boundaries and moves to the appropriate lower state. Held outputs are reconciled during that transition. For example, with lower-level carryover enabled, falling from High to Medium releases the High hold while preserving the Medium hold.

## Contact-score checkboxes

The `Use Size`, `Use TouchMajor`, and `Use TouchMinor` checkboxes determine which measurements participate in the contact score. An unchecked measurement has no effect on the score.

With all three enabled and the default SizeMultiplier of 100:

```text
score = (Size * 100 + TouchMajor + TouchMinor) / 3
```

Using the first recorded normal-touch averages:

```text
(0.028 * 100 + 3.576 + 2.411) / 3 = approximately 2.93
```

If TouchMajor is disabled:

```text
score = (Size * 100 + TouchMinor) / 2
```

If only TouchMajor is enabled:

```text
score = TouchMajor
```

The score is always the average of the enabled, scaled inputs. Disabling an input therefore changes the score's numeric range, so Low, Medium, High, and possibly Hysteresis should be retuned after changing the checkbox combination.

Possible reasons to disable an input include excessive noise, large movement-related changes, or poor separation between intended contact levels. Removing a noisy input may stabilize level detection; removing a strongly separating input may make detection worse. The live score and level shown on the Touch Aim surface are intended to support this tuning while stationary and while aiming.

Mouse or right-stick aiming is independent of these checkboxes and continues as long as the finger owns the Touch Aim surface. If all three signals are disabled, the score remains zero. With positive thresholds, Low, Medium, and High will not activate, while aim continues normally.

## Reusable two-state calibration wizard

TouchAim now also has explicit, opt-in **Manual two-state** and **Calibrated two-state** modes.
Existing and newly decoded older layouts remain on the original manual three-stage mode unless the
user deliberately switches that individual control. Manual two-state uses the selected raw Size,
TouchMajor, and TouchMinor inputs with the same AIM/SHOOT state machine as calibrated mode, but it
stores separate raw-score ON/OFF thresholds so switching modes cannot reuse normalized calibration
thresholds.

The wizard records only two intended postures, twice each:

```text
AIM -> the finger naturally aims
SHOOT -> the same finger continues aiming while using the intended shooting posture
```

Each pass begins only after a large Start button and an adjustable preparation countdown. The
first half records a steady natural touch; a visual/vibration cue asks the user to move naturally
during the second half. Movement matters because the S22 diagnostic showed that contact geometry
can fall while the finger moves.

Pressure is not collected or evaluated. The analyzer evaluates Size, TouchMajor, TouchMinor, and
normalized combinations. Repeated passes stay separate during evaluation. A result is labeled
Good, Borderline, or Unreliable and reports estimated accidental Shoot activations, missed Shoot
activations, pass consistency, and whether movement reduced reliability. If an Unreliable result
still has a meaningful sensor direction, the wizard exposes its thresholds as an explicitly
experimental, editable best guess after live validation. If no meaningful direction exists, it
still exposes no applicable threshold rather than inventing one.

Two-state runtime settings include independent Shoot-on/Shoot-off thresholds, smoothing, entry and
return confirmation times, an optional Aim payload, a Shoot payload, and whether the Aim payload
stays active during Shoot. Aim output can still be Mouse, Right stick, or Left stick. **Shoot aim
sensitivity** is separate from normal Aim sensitivity so a confirmed Shoot state can use finer aim
control without changing the lower Aim state.

Shoot activation choices are:

- **Hold while above threshold:** hold Shoot until the contact returns to Aim.
- **Press when entering Shoot:** emit one finite press after the upward transition is confirmed.
- **Press when returning to Aim:** arm after a confirmed upward transition, then emit one finite
  press only after the finger deliberately relaxes below Shoot-off and remains there for the return
  confirmation time. Lifting the finger does not count as returning to Aim.

Calibration and live validation cover the actual TouchAim rectangle at its current screen position
and suppress all normal controller output. Entry and exit run the centralized release path. Finger
lift, cancellation, app pause, edit mode, control removal, layout change, connection loss, and
Release All cancel any armed return action without firing it.
