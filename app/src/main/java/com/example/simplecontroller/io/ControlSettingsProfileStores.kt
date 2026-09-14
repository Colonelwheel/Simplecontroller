package com.example.simplecontroller.io

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.example.simplecontroller.model.ButtonAimProfile
import com.example.simplecontroller.model.TouchAimManualProfile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class SettingsProfileOperationResult<T>(
    val profile: T? = null,
    val error: String? = null
) {
    val succeeded: Boolean get() = error == null
}

private class AtomicNamedProfileStore<T>(
    private val tag: String,
    private val prefix: String,
    private val serializer: KSerializer<T>,
    private val idOf: (T) -> String,
    private val nameOf: (T) -> String,
    private val updatedAtOf: (T) -> Long,
    private val withName: (T, String, Long) -> T,
    private val validationError: (T) -> String?
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun list(context: Context): List<T> = context.filesDir.listFiles()
        .orEmpty()
        .filter { it.name.startsWith(prefix) && it.name.endsWith(SUFFIX) }
        .mapNotNull(::readFile)
        .sortedWith(compareByDescending<T> { updatedAtOf(it) }.thenBy { idOf(it) })

    fun save(context: Context, profile: T): SettingsProfileOperationResult<T> {
        validationError(profile)?.let { return SettingsProfileOperationResult(error = it) }
        val id = idOf(profile)
        val cleanName = nameOf(profile).trim()
        if (!isCanonicalUuid(id)) return SettingsProfileOperationResult(error = "Invalid profile identifier.")
        if (list(context).any { idOf(it) != id && nameOf(it).equals(cleanName, ignoreCase = true) }) {
            return SettingsProfileOperationResult(error = "A profile with that name already exists.")
        }
        val normalized = withName(profile, cleanName, updatedAtOf(profile))
        return runCatching {
            val atomic = AtomicFile(File(context.filesDir, "$prefix$id$SUFFIX"))
            val output = atomic.startWrite()
            try {
                output.write(json.encodeToString(serializer, normalized).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
            SettingsProfileOperationResult(profile = normalized)
        }.onFailure { Log.e(tag, "Unable to save profile", it) }
            .getOrElse { SettingsProfileOperationResult(error = "Could not save the profile.") }
    }

    fun rename(
        context: Context,
        profile: T,
        newName: String,
        nowMs: Long
    ): SettingsProfileOperationResult<T> {
        val clean = newName.trim()
        if (clean.isEmpty()) return SettingsProfileOperationResult(error = "Profile name is required.")
        return save(context, withName(profile, clean, nowMs))
    }

    fun delete(context: Context, id: String): Boolean {
        if (!isCanonicalUuid(id)) return false
        val file = File(context.filesDir, "$prefix$id$SUFFIX")
        return !file.exists() || file.delete()
    }

    private fun readFile(file: File): T? = runCatching {
        val fileId = file.name.removePrefix(prefix).removeSuffix(SUFFIX)
        if (!isCanonicalUuid(fileId)) return@runCatching null
        json.decodeFromString(serializer, file.readText()).takeIf {
            idOf(it) == fileId && isCanonicalUuid(idOf(it)) && validationError(it) == null
        }
    }.onFailure { Log.w(tag, "Skipping unreadable profile ${file.name}", it) }.getOrNull()

    private companion object {
        const val SUFFIX = ".json"
        val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        )

        fun isCanonicalUuid(id: String): Boolean = UUID_PATTERN.matches(id)
    }
}

object TouchAimManualProfileStore {
    private val store = AtomicNamedProfileStore(
        tag = "TouchAimManualProfile",
        prefix = "touch_aim_manual_profile_",
        serializer = TouchAimManualProfile.serializer(),
        idOf = TouchAimManualProfile::id,
        nameOf = TouchAimManualProfile::name,
        updatedAtOf = TouchAimManualProfile::updatedAtEpochMs,
        withName = { profile, name, updated ->
            profile.copy(name = name, updatedAtEpochMs = updated)
        },
        validationError = TouchAimManualProfile::validationError
    )

    fun list(context: Context): List<TouchAimManualProfile> = store.list(context)
    fun save(context: Context, profile: TouchAimManualProfile) = store.save(context, profile)
    fun rename(context: Context, profile: TouchAimManualProfile, name: String, nowMs: Long) =
        store.rename(context, profile, name, nowMs)
    fun delete(context: Context, id: String): Boolean = store.delete(context, id)
}

object ButtonAimProfileStore {
    private val store = AtomicNamedProfileStore(
        tag = "ButtonAimProfile",
        prefix = "button_aim_profile_",
        serializer = ButtonAimProfile.serializer(),
        idOf = ButtonAimProfile::id,
        nameOf = ButtonAimProfile::name,
        updatedAtOf = ButtonAimProfile::updatedAtEpochMs,
        withName = { profile, name, updated ->
            profile.copy(name = name, updatedAtEpochMs = updated)
        },
        validationError = ButtonAimProfile::validationError
    )

    fun list(context: Context): List<ButtonAimProfile> = store.list(context)
    fun save(context: Context, profile: ButtonAimProfile) = store.save(context, profile)
    fun rename(context: Context, profile: ButtonAimProfile, name: String, nowMs: Long) =
        store.rename(context, profile, name, nowMs)
    fun delete(context: Context, id: String): Boolean = store.delete(context, id)
}
