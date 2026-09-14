package com.example.simplecontroller.io

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.example.simplecontroller.model.TouchAimCalibrationProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

data class TouchAimCalibrationOperationResult(
    val profile: TouchAimCalibrationProfile? = null,
    val error: String? = null
) {
    val succeeded: Boolean get() = error == null
}

/** Pure list operations used by the Android store and JVM tests. */
object TouchAimCalibrationCatalog {
    fun upsert(
        profiles: List<TouchAimCalibrationProfile>,
        profile: TouchAimCalibrationProfile
    ): List<TouchAimCalibrationProfile> =
        (profiles.filterNot { it.id == profile.id } + profile)
            .sortedByDescending { it.updatedAtEpochMs }

    fun rename(
        profiles: List<TouchAimCalibrationProfile>,
        id: String,
        newName: String,
        nowMs: Long
    ): List<TouchAimCalibrationProfile>? {
        val clean = newName.trim()
        if (clean.isEmpty() || profiles.none { it.id == id }) return null
        if (profiles.any { it.id != id && it.name.equals(clean, ignoreCase = true) }) return null
        return profiles.map {
            if (it.id == id) it.copy(name = clean, updatedAtEpochMs = nowMs) else it
        }.sortedByDescending { it.updatedAtEpochMs }
    }

    fun duplicate(
        profiles: List<TouchAimCalibrationProfile>,
        id: String,
        newId: String,
        newName: String,
        nowMs: Long
    ): List<TouchAimCalibrationProfile>? {
        val source = profiles.firstOrNull { it.id == id } ?: return null
        val clean = newName.trim()
        if (clean.isEmpty() || profiles.any { it.name.equals(clean, ignoreCase = true) }) return null
        return upsert(
            profiles,
            source.copy(
                id = newId,
                name = clean,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            )
        )
    }

    fun delete(profiles: List<TouchAimCalibrationProfile>, id: String): List<TouchAimCalibrationProfile> =
        profiles.filterNot { it.id == id }

    fun migrated(profile: TouchAimCalibrationProfile): TouchAimCalibrationProfile? =
        profile.takeIf { it.schemaVersion in 1..TouchAimCalibrationProfile.CURRENT_SCHEMA_VERSION }
}

/** App-private, UUID-addressed profile files. A corrupt profile cannot hide the other profiles. */
object TouchAimCalibrationStore {
    private const val TAG = "TouchAimCalibration"
    private const val PREFIX = "touch_aim_calibration_"
    private const val SUFFIX = ".json"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun list(context: Context): List<TouchAimCalibrationProfile> =
        context.filesDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
            .mapNotNull(::readFile)
            .sortedByDescending { it.updatedAtEpochMs }

    fun get(context: Context, id: String): TouchAimCalibrationProfile? =
        validatedFile(context, id)?.takeIf(File::exists)?.let(::readFile)

    fun save(context: Context, profile: TouchAimCalibrationProfile): TouchAimCalibrationOperationResult {
        val cleanName = profile.name.trim()
        if (cleanName.isEmpty()) return TouchAimCalibrationOperationResult(error = "Name is required.")
        if (profile.schemaVersion !in 1..TouchAimCalibrationProfile.CURRENT_SCHEMA_VERSION) {
            return TouchAimCalibrationOperationResult(error = "This calibration version is not supported.")
        }
        if (list(context).any { it.id != profile.id && it.name.equals(cleanName, true) }) {
            return TouchAimCalibrationOperationResult(error = "A calibration with that name already exists.")
        }
        val file = validatedFile(context, profile.id)
            ?: return TouchAimCalibrationOperationResult(error = "Invalid calibration identifier.")
        val normalized = profile.copy(name = cleanName)
        return runCatching {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(json.encodeToString(normalized).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
            TouchAimCalibrationOperationResult(profile = normalized)
        }.onFailure { Log.e(TAG, "Unable to save calibration", it) }
            .getOrElse { TouchAimCalibrationOperationResult(error = "Could not save the calibration.") }
    }

    fun rename(context: Context, id: String, name: String, nowMs: Long): TouchAimCalibrationOperationResult {
        val current = list(context)
        val updated = TouchAimCalibrationCatalog.rename(current, id, name, nowMs)
            ?: return TouchAimCalibrationOperationResult(error = "Choose a unique non-empty name.")
        return save(context, updated.first { it.id == id })
    }

    fun duplicate(
        context: Context,
        id: String,
        name: String,
        nowMs: Long
    ): TouchAimCalibrationOperationResult {
        val newId = UUID.randomUUID().toString()
        val updated = TouchAimCalibrationCatalog.duplicate(list(context), id, newId, name, nowMs)
            ?: return TouchAimCalibrationOperationResult(error = "Choose a unique non-empty name.")
        return save(context, updated.first { it.id == newId })
    }

    fun delete(context: Context, id: String): Boolean {
        val file = validatedFile(context, id) ?: return false
        return !file.exists() || file.delete()
    }

    private fun readFile(file: File): TouchAimCalibrationProfile? = runCatching {
        val decoded = json.decodeFromString<TouchAimCalibrationProfile>(file.readText())
        TouchAimCalibrationCatalog.migrated(decoded)
    }.onFailure { Log.w(TAG, "Skipping unreadable profile ${file.name}", it) }.getOrNull()

    private fun validatedFile(context: Context, id: String): File? {
        if (!UUID_PATTERN.matches(id)) return null
        return File(context.filesDir, "$PREFIX$id$SUFFIX")
    }

    private val UUID_PATTERN = Regex("^[0-9a-fA-F-]{36}$")
}
