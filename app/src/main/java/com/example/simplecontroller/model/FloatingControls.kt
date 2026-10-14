package com.example.simplecontroller.model

import kotlinx.serialization.Serializable
import com.example.simplecontroller.net.PhoneOutputCoordinator
import kotlin.math.hypot

/** Saved templates only. Pointer state, live placement, claims and timers belong to runtime sessions. */
@Serializable
enum class FloatingZoneKind { SINGLE_BUTTON, BUTTON_GROUP, RING }

/** Android-local scoped actions; never encode these as receiver commands. */
@Serializable
enum class FloatingLocalAction { NONE, FINISH, STOP_FIRE, RELEASE_ADS, END_SUPPRESSION }

@Serializable
enum class FloatingStartRole { MOVEMENT, AIM, BOOST_ONE, BOOST_TWO }

@Serializable
enum class RingBoostStage { ONE, TWO }

@Serializable
enum class RingAction { TAP, HOLD, REPEAT }

@Serializable
enum class RingFireMode { NONE, HOLD, TAP, RELEASE_SHOT, REPEAT }

@Serializable
data class FloatingZoneGeometry(
    val canvasWidth: Float = 0f,
    val canvasHeight: Float = 0f,
    val zone: ControlGeometry = ControlGeometry(0f, 0f, 240f, 240f)
)

@Serializable
data class FloatingZone(
    val id: String,
    val name: String = "",
    val kind: FloatingZoneKind,
    val enabled: Boolean = true,
    val memberIds: List<String> = emptyList(),
    /** Blank explicitly selects reveal-only for a group. */
    val startMemberId: String = "",
    val portraitGeometry: FloatingZoneGeometry? = null,
    val landscapeGeometry: FloatingZoneGeometry? = null,
    val portraitMemberGeometry: Map<String, ControlGeometry> = emptyMap(),
    val landscapeMemberGeometry: Map<String, ControlGeometry> = emptyMap(),
    val portraitRestorationGeometry: Map<String, ControlGeometry> = emptyMap(),
    val landscapeRestorationGeometry: Map<String, ControlGeometry> = emptyMap(),
    val groupSwipe: Boolean = false,
    val ring: FloatingRingConfig? = null
) {
    fun geometryFor(orientation: LayoutOrientation): FloatingZoneGeometry? = when (orientation) {
        LayoutOrientation.PORTRAIT -> portraitGeometry
        LayoutOrientation.LANDSCAPE -> landscapeGeometry
    }

    fun memberGeometryFor(orientation: LayoutOrientation): Map<String, ControlGeometry> = when (orientation) {
        LayoutOrientation.PORTRAIT -> portraitMemberGeometry
        LayoutOrientation.LANDSCAPE -> landscapeMemberGeometry
    }

    fun zoneGeometryFor(orientation: LayoutOrientation): ControlGeometry? = geometryFor(orientation)?.zone

    fun restorationGeometryFor(orientation: LayoutOrientation): Map<String, ControlGeometry> = when (orientation) {
        LayoutOrientation.PORTRAIT -> portraitRestorationGeometry
        LayoutOrientation.LANDSCAPE -> landscapeRestorationGeometry
    }

    fun withGeometry(orientation: LayoutOrientation, value: FloatingZoneGeometry): FloatingZone =
        when (orientation) {
            LayoutOrientation.PORTRAIT -> copy(portraitGeometry = value)
            LayoutOrientation.LANDSCAPE -> copy(landscapeGeometry = value)
        }
}

@Serializable
data class RingMovementSettings(
    val enabled: Boolean = true,
    val output: TouchAimOutput = TouchAimOutput.LEFT_STICK,
    val sensitivity: Float = 1f,
    val profile: ButtonAimStickProfile = ButtonAimStickProfile.LINEAR,
    val autoCenter: Boolean = true,
    val excludeFromCameraFollow: Boolean = false,
    /** Explicit normalized summon vector; edge fitting never changes this value. */
    val initialX: Float = 0f,
    val initialY: Float = 0f,
    val participateInBoosts: Boolean = true
)

fun defaultRingAimTuning() = ButtonAimTuning(
    output = ButtonAimOutput.MOUSE,
    sensitivity = 1f,
    invertY = false,
    stickProfile = ButtonAimStickProfile.LINEAR,
    mouseProfile = ButtonAimMouseProfile.LINEAR_RELATIVE,
    stickFullDisplacementPx = 220f,
    stickDeadzonePx = 8f,
    stickUsesTouchPosition = false,
    autoCenter = true,
    haptics = true
)

@Serializable
data class RingFineAimSettings(
    val enabled: Boolean = false,
    val sensitivity: Float = 0.4f,
    val qualifyingStages: Set<RingBoostStage> = setOf(RingBoostStage.ONE),
    val persistWhileAdsLatched: Boolean = true
)

@Serializable
data class RingAimSettings(
    val enabled: Boolean = true,
    val tuning: ButtonAimTuning = defaultRingAimTuning(),
    val continueOutside: Boolean = true,
    val participateInBoosts: Boolean = false,
    val suppressCameraFollow: Boolean = false,
    val fine: RingFineAimSettings = RingFineAimSettings()
)

@Serializable
data class RingReturnFireSettings(
    val enabled: Boolean = false,
    val qualifyingStages: Set<RingBoostStage> = setOf(RingBoostStage.ONE),
    val delayMs: Long = 0L,
    val cancelOnExit: Boolean = true
)

@Serializable
data class RingFireSettings(
    val mode: RingFireMode = RingFireMode.NONE,
    val adsPayload: String = "",
    val firePayload: String = "RT:1.0",
    val pulsePressMs: Long = 35L,
    val pulseGapMs: Long = 35L,
    val retainAdsOnLift: Boolean = false,
    val retainFireOnLift: Boolean = false,
    val continueAdsOutside: Boolean = true,
    val continueFireOutside: Boolean = true,
    val cancelReleaseShotOnBoundaryExit: Boolean = false,
    val rearmReleaseShotOnBoundaryReturn: Boolean = false,
    val returnFire: RingReturnFireSettings = RingReturnFireSettings()
)

@Serializable
data class RingAimKnob(
    val id: String = "aim_1",
    val name: String = "Aim",
    val enabled: Boolean = true,
    /** Saved position in inner-radius units, independent of summoned placement. */
    val x: Float = 0f,
    val y: Float = 0f,
    val targetDiameterDp: Float = 48f,
    val fire: RingFireSettings = RingFireSettings()
)

@Serializable
data class RingBoostSettings(
    val enabled: Boolean = false,
    /** Circular raw physical distance in dp, not legacy per-axis or first-contact pixels. */
    val distanceDp: Float = 120f,
    val directionalPayloads: DirectionalCommands = DirectionalCommands(),
    val payload: String = "",
    val action: RingAction = RingAction.HOLD,
    val pulsePressMs: Long = 35L,
    val pulseGapMs: Long = 35L,
    val latchOnExit: Boolean = false,
    val repeatAfterExit: Boolean = false,
    val retainOnLift: Boolean = false,
    val activationDelayMs: Long = 0L,
    val cancelDelayOnExit: Boolean = true,
    val hysteresisDp: Float = 2f,
    val directTapEnabled: Boolean = false,
    val directInnerDistanceDp: Float = 120f,
    val directOuterDistanceDp: Float = 144f
) {
    fun payloadFor(direction: StickDirection): String =
        directionalPayloads.forDirection(direction).ifBlank { payload }
}

@Serializable
data class FloatingRingConfig(
    val innerRadiusDp: Float = 48f,
    val movementRadiusDp: Float = 88f,
    val movement: RingMovementSettings = RingMovementSettings(),
    val aim: RingAimSettings = RingAimSettings(),
    val knobs: List<RingAimKnob> = listOf(RingAimKnob()),
    val boost1: RingBoostSettings = RingBoostSettings(),
    val boost2: RingBoostSettings = RingBoostSettings(
        distanceDp = 160f, directInnerDistanceDp = 160f, directOuterDistanceDp = 184f
    ),
    val startRole: FloatingStartRole = FloatingStartRole.MOVEMENT,
    val startKnobId: String = "aim_1",
    val directBoostToAim: Boolean = false,
    val directBoostAimKnobId: String = "aim_1"
) {
    fun boost(stage: RingBoostStage): RingBoostSettings = when (stage) {
        RingBoostStage.ONE -> boost1
        RingBoostStage.TWO -> boost2
    }

    fun knob(id: String): RingAimKnob? = knobs.firstOrNull { it.id == id }

    val isActive: Boolean get() = movement.enabled || aim.enabled || boost1.enabled || boost2.enabled

    fun validationError(): String? {
        if (!innerRadiusDp.isFinite() || innerRadiusDp <= 0f ||
            !movementRadiusDp.isFinite() || movementRadiusDp <= innerRadiusDp) {
            return "The movement radius must exceed the positive inner aim radius."
        }
        if (movement.output == TouchAimOutput.MOUSE || !movement.sensitivity.validSensitivity() ||
            !movement.initialX.isFinite() || !movement.initialY.isFinite() ||
            hypot(movement.initialX, movement.initialY) > 1.00001f) {
            return "Ring movement needs a valid stick output, sensitivity and summon vector."
        }
        if (aim.tuning.output !in RING_AIM_OUTPUTS) return "Ring aim supports Mouse, Game Mouse or Right Stick."
        aim.tuning.validationError()?.let { return it }
        if (!aim.fine.sensitivity.validSensitivity() || aim.fine.qualifyingStages.isEmpty()) {
            return "Fine aim needs a valid sensitivity and at least one qualifying boost."
        }
        if (knobs.size !in 1..3 || knobs.any { it.id.isBlank() } ||
            knobs.map { it.id }.distinct().size != knobs.size) return "A ring needs one to three uniquely identified aim knobs."
        if (aim.enabled && knobs.none { it.enabled }) return "Enable an aiming knob or explicitly disable the aiming layer."
        if (knob(startKnobId) == null || knob(directBoostAimKnobId) == null) {
            return "Saved starting and direct-boost destinations must reference existing aim knobs."
        }
        knobs.forEach { knob ->
            if (!knob.x.isFinite() || !knob.y.isFinite() || hypot(knob.x, knob.y) > 1f ||
                !knob.targetDiameterDp.isFinite() || knob.targetDiameterDp <= 0f ||
                hypot(knob.x, knob.y) * innerRadiusDp + knob.targetDiameterDp / 2f > innerRadiusDp) {
                return "Aim knob touch targets must fit inside the configured inner area."
            }
            knob.fire.validationError()?.let { return it }
        }
        boost1.validationError()?.let { return it }
        boost2.validationError()?.let { return it }
        if (boost1.distanceDp <= movementRadiusDp || boost2.distanceDp <= boost1.distanceDp ||
            boost1.directOuterDistanceDp > boost2.directInnerDistanceDp) {
            return "Boost distances and finite direct areas must be ordered beyond movement."
        }
        if (boost1.activationDelayMs != 0L) return "Only Boost 2 supports an activation delay."
        // An entirely disabled template remains savable, including its configured starting role.
        if (isActive) {
            val validStart = when (startRole) {
                FloatingStartRole.MOVEMENT -> movement.enabled
                FloatingStartRole.AIM -> aim.enabled && knob(startKnobId)?.enabled == true
                FloatingStartRole.BOOST_ONE -> boost1.enabled && boost1.directTapEnabled
                FloatingStartRole.BOOST_TWO -> boost2.enabled && boost2.directTapEnabled
            }
            if (!validStart) return "The starting role is disabled; select an enabled role explicitly."
        }
        if (isActive && directBoostToAim && (!aim.enabled || knob(directBoostAimKnobId)?.enabled != true)) {
            return "Direct boost aiming needs an enabled destination aim knob."
        }
        outputConflictError()?.let { return it }
        return null
    }

    private fun outputConflictError(): String? {
        fun RingBoostSettings.keys(): Set<String> =
            (directionalPayloads.values() + payload).flatMap { PhoneOutputCoordinator.payloadLogicalKeys(it).orEmpty() }.toSet()
        val stages = listOf(boost1, boost2).filter { it.enabled }
        if (boost1.enabled && boost2.enabled &&
            (boost1.action != RingAction.HOLD || boost2.action != RingAction.HOLD) &&
            boost1.keys().intersect(boost2.keys()).isNotEmpty()) {
            return "Boost stages sharing an output cannot mask each other's Tap or Repeat edges."
        }
        if (aim.enabled) knobs.filter { it.enabled }.forEach { knob ->
            val fire = knob.fire
            val adsKeys = PhoneOutputCoordinator.payloadLogicalKeys(fire.adsPayload).orEmpty()
            val fireKeys = if (fire.mode == RingFireMode.NONE) emptySet() else
                PhoneOutputCoordinator.payloadLogicalKeys(fire.firePayload).orEmpty()
            if (adsKeys.intersect(fireKeys).isNotEmpty()) return "ADS and firing must use independent logical outputs."
            if (fire.returnFire.enabled && boost2.enabled && boost2.activationDelayMs > 0L &&
                fireKeys.intersect(boost2.keys()).isNotEmpty()) {
                return "Choose one delayed firing source for each logical output: Boost 2 or return to aim."
            }
            stages.forEach { stage ->
                if (stage.action != RingAction.HOLD && stage.keys().intersect(adsKeys).isNotEmpty()) {
                    return "A boost Tap or Repeat cannot share a held ADS output."
                }
                if (fire.mode != RingFireMode.NONE &&
                    (stage.action != RingAction.HOLD || fire.mode != RingFireMode.HOLD) &&
                    stage.keys().intersect(fireKeys).isNotEmpty()) {
                    return "Boost and inner firing outputs cannot mask each other's finite or Repeat edges."
                }
            }
        }
        return null
    }

    companion object {
        val RING_AIM_OUTPUTS = setOf(ButtonAimOutput.MOUSE, ButtonAimOutput.GAME_MOUSE, ButtonAimOutput.RIGHT_STICK)
    }
}

private fun Float.validSensitivity(): Boolean = isFinite() && this in 0f..5f

fun RingFireSettings.validationError(): String? {
    validateRingPayloads(listOf(adsPayload, firePayload))?.let { return it }
    if (pulsePressMs !in 1L..60_000L || pulseGapMs !in 1L..60_000L) return "Fire press and release gap must be positive and at most 60000 ms."
    if (returnFire.delayMs !in 0L..60_000L || returnFire.qualifyingStages.isEmpty()) return "Return fire needs a valid delay and qualifying boost."
    return null
}

fun RingBoostSettings.validationError(): String? {
    if (!distanceDp.isFinite() || distanceDp <= 0f || !hysteresisDp.isFinite() ||
        hysteresisDp < 0f || hysteresisDp >= distanceDp || !directInnerDistanceDp.isFinite() ||
        !directOuterDistanceDp.isFinite() || directInnerDistanceDp < distanceDp ||
        directOuterDistanceDp <= directInnerDistanceDp) return "Boost distance and finite direct hit area are invalid."
    validateRingPayloads(directionalPayloads.values() + payload)?.let { return it }
    if (pulsePressMs !in 1L..60_000L || pulseGapMs !in 1L..60_000L || activationDelayMs !in 0L..60_000L) {
        return "Boost timing is invalid."
    }
    // Inactive fields remain stored so switching action never erases a prior setup.
    if (activationDelayMs > 0L && !cancelDelayOnExit &&
        ((action == RingAction.HOLD && !latchOnExit) || (action == RingAction.REPEAT && !repeatAfterExit))) {
        return "A delay continuing after exit requires Hold latch or Repeat continuation."
    }
    return null
}

private fun validateRingPayloads(values: List<String>): String? {
    values.forEach { value ->
        if (value.length > 1000) return "A ring payload exceeds 1000 characters."
        if (value.uppercase().split(Regex("[,\\s]+")).any {
            (it.startsWith("LT:") || it.startsWith("RT:")) && it.contains('P')
        }) return "Use an ordinary trigger binding; the ring action controls press duration and release gap."
        ButtonAimPayloadPolicy.stateValidationError(value)?.let { return it }
        if (PhoneOutputCoordinator.payloadLogicalKeys(value) == null) {
            return "Ring bindings need supported reversible outputs; configure pulse timing with the action controls."
        }
    }
    return null
}

/** One optional preset in the boost library avoids a second library; boost-only apply ignores it. */
@Serializable
data class RingAimActionPreset(
    val aim: RingAimSettings = RingAimSettings(),
    val knobActions: List<RingFireSettings> = listOf(RingFireSettings())
) {
    fun validationError(): String? {
        if (aim.tuning.output !in FloatingRingConfig.RING_AIM_OUTPUTS) return "Unsupported ring aim output."
        aim.tuning.validationError()?.let { return it }
        if (!aim.fine.sensitivity.validSensitivity() || aim.fine.qualifyingStages.isEmpty()) return "Invalid fine aim settings."
        if (knobActions.size !in 1..3) return "The preset needs one to three knob action slots."
        return knobActions.firstNotNullOfOrNull { it.validationError() }
    }
}

@Serializable
data class FloatingBoostProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val boost1: RingBoostSettings,
    val boost2: RingBoostSettings,
    val ringAimActions: RingAimActionPreset? = null
) {
    fun validationError(): String? {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) return "This boost profile version is not supported."
        if (name.trim().length !in 1..80) return "Choose a boost profile name from 1 to 80 characters."
        boost1.validationError()?.let { return it }
        boost2.validationError()?.let { return it }
        if (boost1.activationDelayMs != 0L || boost2.distanceDp <= boost1.distanceDp ||
            boost1.directOuterDistanceDp > boost2.directInnerDistanceDp) return "The saved boost stages are not ordered."
        return ringAimActions?.validationError()
    }

    /** A detached snapshot; target enable choices and all non-boost tuning are preserved. */
    fun applyBoostSettingsOnlyTo(target: FloatingRingConfig): FloatingRingConfig? {
        if (validationError() != null) return null
        return target.copy(
            boost1 = boost1.copy(enabled = target.boost1.enabled),
            boost2 = boost2.copy(enabled = target.boost2.enabled)
        ).takeIf { it.validationError() == null }
    }

    /** Slot mapping preserves target knob identities, placements and enable switches atomically. */
    fun applyRingAimActionsTo(target: FloatingRingConfig): FloatingRingConfig? {
        if (validationError() != null) return null
        val preset = ringAimActions ?: return null
        if (preset.knobActions.size != target.knobs.size) return null
        return target.copy(
            aim = preset.aim.copy(enabled = target.aim.enabled),
            knobs = target.knobs.mapIndexed { index, knob -> knob.copy(fire = preset.knobActions[index]) }
        ).takeIf { it.validationError() == null }
    }

    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}

fun FloatingRingConfig.captureBoostProfile(
    id: String, name: String, nowMs: Long, includeRingAimActions: Boolean = false
): FloatingBoostProfile? = FloatingBoostProfile(
    id = id, name = name.trim(), createdAtEpochMs = nowMs, updatedAtEpochMs = nowMs,
    boost1 = boost1, boost2 = boost2,
    ringAimActions = if (includeRingAimActions) RingAimActionPreset(aim, knobs.map { it.fire }) else null
).takeIf { it.validationError() == null }

fun ControllerPage.floatingValidationError(): String? {
    val controlById = controls.associateBy { it.id }
    if (floatingZones.any { it.id.isBlank() } || floatingZones.map { it.id }.distinct().size != floatingZones.size) {
        return "Floating zone IDs must be nonblank and unique within a page."
    }
    if (floatingZones.any { it.id in controlById }) return "Floating zones and controls must have distinct IDs."
    val usedMembers = mutableSetOf<String>()
    floatingZones.forEach { zone ->
        if (zone.kind == FloatingZoneKind.RING) {
            if (zone.memberIds.isNotEmpty() || zone.startMemberId.isNotEmpty()) return "A ring cannot contain Button members."
            val ring = zone.ring ?: return "The ring configuration is missing."
            ring.validationError()?.let { return it }
        } else {
            if (zone.ring != null) return "Only ring zones may contain ring configuration."
            if (zone.memberIds.isEmpty() || zone.memberIds.distinct().size != zone.memberIds.size ||
                (zone.kind == FloatingZoneKind.SINGLE_BUTTON && zone.memberIds.size != 1)) return "Floating Button membership is invalid."
            zone.memberIds.forEach { id ->
                if (controlById[id]?.type != ControlType.BUTTON) return "A floating member must reference a Button on the same page."
                if (!usedMembers.add(id)) return "A Button cannot belong to multiple floating zones."
            }
            if (zone.startMemberId.isNotEmpty() && zone.startMemberId !in zone.memberIds) return "The starting Button is not a group member."
            if (zone.kind == FloatingZoneKind.SINGLE_BUTTON && zone.startMemberId != zone.memberIds.single()) return "A floating Button must start on its member."
            if (zone.kind == FloatingZoneKind.BUTTON_GROUP) {
                val portraitDefined = zone.portraitGeometry != null
                val landscapeDefined = zone.landscapeGeometry != null
                if ((portraitDefined && (zone.portraitMemberGeometry.keys != zone.memberIds.toSet() ||
                        zone.portraitRestorationGeometry.keys != zone.memberIds.toSet())) ||
                    (landscapeDefined && (zone.landscapeMemberGeometry.keys != zone.memberIds.toSet() ||
                        zone.landscapeRestorationGeometry.keys != zone.memberIds.toSet()))) {
                    return "Every group orientation needs all member placements and restoration geometry."
                }
            }
        }
        listOf(zone.portraitGeometry, zone.landscapeGeometry).filterNotNull().forEach {
            if (!it.canvasWidth.isFinite() || !it.canvasHeight.isFinite() || it.canvasWidth < 0f || it.canvasHeight < 0f ||
                !it.zone.validGeometry()) return "Floating zone geometry is invalid."
        }
        if (zone.portraitGeometry == null && zone.landscapeGeometry == null) return "A floating zone needs saved activation geometry."
        listOf(zone.portraitMemberGeometry, zone.landscapeMemberGeometry,
            zone.portraitRestorationGeometry, zone.landscapeRestorationGeometry).forEach { geometries ->
            if (geometries.keys.any { it !in zone.memberIds } || geometries.values.any { !it.validGeometry() }) return "Floating member geometry is invalid."
        }
    }
    return null
}

private fun ControlGeometry.validGeometry(): Boolean =
    x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite() && w > 0f && h > 0f
