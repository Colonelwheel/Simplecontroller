package com.example.simplecontroller.model

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A complete saved ring zone. Live placement, pointers and output sessions are not model data. */
@Serializable
data class FloatingRingPreset(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val zone: FloatingZone
) {
    fun validationError(): String? {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) return "This ring preset version is not supported."
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

    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}

/** Capture configuration, including inactive switches and both saved orientation geometries. */
fun FloatingZone.captureRingPreset(id: String, name: String, nowMs: Long): FloatingRingPreset? {
    if (ringPresetValidationError() != null) return null
    return FloatingRingPreset(
        id = id,
        name = name.trim(),
        createdAtEpochMs = nowMs,
        updatedAtEpochMs = nowMs,
        zone = detachedFloatingRingZone(this)
    ).takeIf { it.validationError() == null }
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
