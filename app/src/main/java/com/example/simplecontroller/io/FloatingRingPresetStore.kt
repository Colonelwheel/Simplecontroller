package com.example.simplecontroller.io

import android.content.Context
import com.example.simplecontroller.model.FloatingRingPreset
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val ringPresetJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun requireSupportedFloatingRingPresetSchema(value: JsonObject) {
    val version = value["schemaVersion"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("The ring preset version is missing or invalid.")
    require(version in 1..FloatingRingPreset.CURRENT_SCHEMA_VERSION) {
        "Ring preset version $version is newer than supported version ${FloatingRingPreset.CURRENT_SCHEMA_VERSION} or invalid."
    }
    requireFloatingRingPresetOptionsSchema(value, version)
}

internal fun requireFloatingRingPresetOptionsSchema(value: JsonObject, version: Int) {
    val zone = value["zone"] as? JsonObject
    val required = when {
        zone != null && floatingZoneHasRoleBoostOptions(zone) -> FloatingRingPreset.CURRENT_SCHEMA_VERSION
        zone != null && floatingZoneHasExtendedOptions(zone) -> FloatingRingPreset.RING_OPTIONS_SCHEMA_VERSION
        else -> FloatingRingPreset.LEGACY_SCHEMA_VERSION
    }
    require(version >= required) {
        "The saved ring options require ring preset version $required."
    }
}

fun encodeFloatingRingPreset(preset: FloatingRingPreset): String {
    preset.validationError()?.let { throw IllegalArgumentException(it) }
    return ringPresetJson.encodeToString(preset)
}

fun decodeFloatingRingPreset(text: String): FloatingRingPreset {
    val normalized = text.removePrefix("\uFEFF")
    val value = ringPresetJson.parseToJsonElement(normalized) as? JsonObject
        ?: throw IllegalArgumentException("This is not a ring preset.")
    requireSupportedFloatingRingPresetSchema(value)
    return ringPresetJson.decodeFromString<FloatingRingPreset>(normalized).also {
        it.validationError()?.let { error -> throw IllegalArgumentException(error) }
    }
}

object FloatingRingPresetStore {
    private const val PREFIX = "floating_ring_preset_"
    private val store = AtomicNamedProfileStore(
        tag = "FloatingRingPreset",
        prefix = PREFIX,
        serializer = FloatingRingPreset.serializer(),
        idOf = FloatingRingPreset::id,
        nameOf = FloatingRingPreset::name,
        updatedAtOf = FloatingRingPreset::updatedAtEpochMs,
        withName = { preset, name, updated -> preset.copy(name = name, updatedAtEpochMs = updated) },
        validationError = FloatingRingPreset::validationError,
        preflight = ::requireSupportedFloatingRingPresetSchema
    )

    fun list(context: Context): List<FloatingRingPreset> = store.list(context)
    fun backupSnapshot(context: Context) = store.backupSnapshot(context)

    /** Save adds a new snapshot. Only explicit rename may rewrite an existing preset. */
    @Synchronized
    fun save(context: Context, preset: FloatingRingPreset): SettingsProfileOperationResult<FloatingRingPreset> {
        preset.validationError()?.let { return SettingsProfileOperationResult(error = it) }
        val file = File(context.filesDir, "$PREFIX${preset.id}.json")
        if (listOf(file, File(file.path + ".bak"), File(file.path + ".new")).any(File::exists)) {
            return SettingsProfileOperationResult(error = "That ring preset already exists. Save a new preset or rename the existing one.")
        }
        val snapshot = decodeFloatingRingPreset(encodeFloatingRingPreset(preset))
        return store.save(context, snapshot)
    }

    @Synchronized
    fun rename(context: Context, preset: FloatingRingPreset, name: String, nowMs: Long): SettingsProfileOperationResult<FloatingRingPreset> {
        val current = list(context).firstOrNull { it.id == preset.id }
            ?: return SettingsProfileOperationResult(error = "The saved ring preset could not be read; it was left unchanged.")
        return store.rename(context, current, name, nowMs)
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean = store.delete(context, id)
}
