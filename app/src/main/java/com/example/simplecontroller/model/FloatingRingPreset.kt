package com.example.simplecontroller.model

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A complete saved ring zone. Live placement, pointers and output sessions are not model data. */
@Serializable
data class FloatingRingPreset(
    val schemaVersion: Int = LEGACY_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val zone: FloatingZone
) {
    fun validationError(): String? {
        if (schemaVersion !in LEGACY_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) return "This ring preset version is not supported."
        if (schemaVersion < requiredSchemaVersion()) return "The saved ring options require ring preset version ${requiredSchemaVersion()}."
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) return "Invalid ring preset identifier."
        if (name.trim().length !in 1..80) return "Choose a ring preset name from 1 to 80 characters."
        if (createdAtEpochMs < 0L || updatedAtEpochMs < createdAtEpochMs) return "Invalid ring preset timestamps."
        return zone.ringPresetValidationError()
    }

    /** Add a new zone; callers append it to their chosen page without replacing an existing zone. */
    fun instantiate(
        newZoneId: String = UUID.randomUUID().toString(),
        zoneName: String? = null
    ): FloatingZone? {
        if (validationError() != null || newZoneId.isBlank() || newZoneId == zone.id) return null
        return detachedFloatingRingZone(zone).copy(id = newZoneId, name = zoneName ?: zone.name)
    }

    fun requiredSchemaVersion(): Int = zone.requiredRingPresetSchemaVersion()

    companion object {
        const val LEGACY_SCHEMA_VERSION = 1
        const val RING_OPTIONS_SCHEMA_VERSION = 2
        const val ROLE_BOOST_SCHEMA_VERSION = 3
        const val DIRECT_TOUCH_DELAY_SCHEMA_VERSION = 4
        const val CURRENT_SCHEMA_VERSION = DIRECT_TOUCH_DELAY_SCHEMA_VERSION
    }
}

/** Capture configuration, including inactive switches and both saved orientation geometries. */
fun FloatingZone.captureRingPreset(id: String, name: String, nowMs: Long): FloatingRingPreset? {
    if (ringPresetValidationError() != null) return null
    return FloatingRingPreset(
        schemaVersion = requiredRingPresetSchemaVersion(),
        id = id,
        name = name.trim(),
        createdAtEpochMs = nowMs,
        updatedAtEpochMs = nowMs,
        zone = detachedFloatingRingZone(this)
    ).takeIf { it.validationError() == null }
}

private fun FloatingZone.requiredRingPresetSchemaVersion(): Int = when {
    hasDirectTouchBoostDelayConfiguration() -> FloatingRingPreset.DIRECT_TOUCH_DELAY_SCHEMA_VERSION
    hasRoleBoostConfiguration() -> FloatingRingPreset.ROLE_BOOST_SCHEMA_VERSION
    hasExtendedFloatingConfiguration() -> FloatingRingPreset.RING_OPTIONS_SCHEMA_VERSION
    else -> FloatingRingPreset.LEGACY_SCHEMA_VERSION
}

private fun FloatingZone.ringPresetValidationError(): String? {
    if (kind != FloatingZoneKind.RING || ring == null) return "Only complete ring zones can be saved as ring presets."
    if (memberIds.isNotEmpty() || startMemberId.isNotEmpty() || groupSwipe ||
        portraitMemberGeometry.isNotEmpty() || landscapeMemberGeometry.isNotEmpty() ||
        portraitRestorationGeometry.isNotEmpty() || landscapeRestorationGeometry.isNotEmpty()) {
        return "A ring preset cannot contain Button or group settings."
    }
    return ControllerPage(id = "ring-preset", floatingZones = listOf(this)).floatingValidationError()
}

private val ringSnapshotJson = Json { encodeDefaults = true }

/** Serialization also detaches mutable collections nested inside otherwise immutable settings. */
internal fun detachedFloatingRingZone(zone: FloatingZone): FloatingZone =
    ringSnapshotJson.decodeFromString(ringSnapshotJson.encodeToString(zone))
