package com.example.simplecontroller.io

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.example.simplecontroller.model.CONTROLLER_PROFILE_FORMAT_VERSION
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControllerPage
import com.example.simplecontroller.model.ControllerProfile
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.model.PageAction
import com.example.simplecontroller.model.newPageId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.io.FileNotFoundException
import java.io.File
import java.util.UUID
import java.util.Locale

private const val TAG = "LayoutStorage"
private const val EXT = ".json"

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

data class ControllerProfileLoadResult(
    val profile: ControllerProfile,
    val warnings: List<String>,
    val migratedFromSinglePage: Boolean
)

sealed interface StoredControllerProfileResult {
    data class Loaded(val result: ControllerProfileLoadResult) : StoredControllerProfileResult
    data object NotFound : StoredControllerProfileResult
    data class Error(val message: String, val cause: Throwable) : StoredControllerProfileResult
}

private fun fileFor(ctx: Context, name: String) =
    ctx.getFileStreamPath("layout_${name.lowercase(Locale.ROOT)}$EXT")

/** Return saved profile names without the historical layout_ prefix. */
fun listLayouts(ctx: Context): List<String> =
    ctx.filesDir.listFiles()
        ?.filter { it.name.startsWith("layout_") && it.name.endsWith(EXT) }
        ?.map { it.name.removePrefix("layout_").removeSuffix(EXT) }
        .orEmpty()

fun stableLegacyBasePageId(profileName: String): String = UUID.nameUUIDFromBytes(
    "simplecontroller-profile:${profileName.trim().lowercase(Locale.ROOT)}:base"
        .toByteArray(StandardCharsets.UTF_8)
).toString()

fun decodeControllerProfile(text: String, profileName: String): ControllerProfileLoadResult {
    if (text.trimStart().startsWith("[")) {
        val controls = json.decodeFromString<List<Control>>(text)
        val pageId = stableLegacyBasePageId(profileName)
        return ControllerProfileLoadResult(
            profile = ControllerProfile(
                homePageId = pageId,
                pages = listOf(ControllerPage(pageId, "Base", controls))
            ),
            warnings = emptyList(),
            migratedFromSinglePage = true
        )
    }
    val element = json.parseToJsonElement(text)
    val decoded = json.decodeFromJsonElement<ControllerProfile>(element)
    require(decoded.formatVersion <= CONTROLLER_PROFILE_FORMAT_VERSION) {
        "Profile format ${decoded.formatVersion} is newer than supported format " +
            CONTROLLER_PROFILE_FORMAT_VERSION
    }
    val result = validateControllerProfile(decoded, profileName)
    val missingFieldWarnings = element.jsonObject["pages"]?.jsonArray.orEmpty()
        .mapIndexedNotNull { index, pageElement ->
            if (!pageElement.jsonObject.containsKey("controls")) {
                "Page ${index + 1} had no controls field and was restored as blank."
            } else null
        }
    return result.copy(warnings = (result.warnings + missingFieldWarnings).distinct())
}

fun encodeControllerProfile(profile: ControllerProfile): String {
    validateControllerProfileForSave(profile)?.let { throw IllegalArgumentException(it) }
    return json.encodeToString(profile.copy(formatVersion = CONTROLLER_PROFILE_FORMAT_VERSION))
}

/** Serialization round-trip guarantees detached nested lists now and as Control evolves. */
fun deepCopyControls(controls: List<Control>, regenerateIds: Boolean = false): List<Control> {
    val copied: List<Control> = json.decodeFromString(json.encodeToString(controls))
    return if (regenerateIds) copied.map { it.copy(id = "control_${newPageId()}") } else copied
}

fun validateControllerProfile(
    decoded: ControllerProfile,
    profileName: String
): ControllerProfileLoadResult {
    val warnings = mutableListOf<String>()
    val sourcePages = decoded.pages.ifEmpty {
        warnings += "The profile contained no pages, so an empty Base page was restored."
        val id = stableLegacyBasePageId(profileName)
        listOf(ControllerPage(id, "Base", emptyList()))
    }

    val usedIds = mutableSetOf<String>()
    val usedNames = mutableSetOf<String>()
    val explicitIds = sourcePages.map { it.id }.filterNot(String::isBlank)
    require(explicitIds.distinct().size == explicitIds.size) {
        "Profile contains duplicate page IDs; its navigation targets are ambiguous."
    }
    val missingIdIndexes = sourcePages.indices.filter { sourcePages[it].id.isBlank() }
    require(missingIdIndexes.size <= 1) {
        "Profile contains multiple pages without IDs; their references are ambiguous."
    }
    val recoveredMissingId = missingIdIndexes.singleOrNull()?.let { index ->
        stableRecoveredPageId(profileName, sourcePages[index].name, index, explicitIds.toSet())
    }
    val pages = sourcePages.mapIndexed { index, source ->
        var id = source.id
        if (id.isBlank()) {
            id = requireNotNull(recoveredMissingId)
            usedIds += id
            warnings += "Page ${index + 1} had no ID and was assigned a stable replacement."
        } else {
            require(usedIds.add(id)) {
                "Profile contains the duplicate page ID '$id'; its navigation targets are ambiguous."
            }
        }

        var name = source.name.trim()
        if (name.isEmpty()) {
            name = "Page ${index + 1}"
            warnings += "Page ${index + 1} had no name and was renamed to '$name'."
        }
        if (name.lowercase(Locale.ROOT) in usedNames) {
            val base = name
            var suffix = 2
            while ("${base.lowercase(Locale.ROOT)} $suffix" in usedNames) suffix++
            name = "$base $suffix"
            warnings += "Duplicate page name '$base' was renamed to '$name'."
        }
        usedNames += name.lowercase(Locale.ROOT)
        val controls = deepCopyControls(source.controls).map { original ->
            var control = original
            if ((control.pageAction == PageAction.GO_TO || control.pageAction == PageAction.TOGGLE) &&
                control.pageTargetId.isBlank() && recoveredMissingId != null
            ) {
                control = control.copy(pageTargetId = recoveredMissingId)
                warnings += "A blank page target on '${name}' was restored to the recovered page ID."
            }
            if (control.pageAction != PageAction.NONE && control.type != ControlType.BUTTON) {
                warnings += "A local page action on non-Button '${control.name.ifBlank { control.id }}' was disabled."
                control = control.copy(pageAction = PageAction.NONE, pageTargetId = "")
            } else if ((control.pageAction == PageAction.GO_TO || control.pageAction == PageAction.TOGGLE) &&
                control.pageTargetId.isBlank()
            ) {
                warnings += "A page action without a target on '${control.name.ifBlank { control.id }}' was disabled."
                control = control.copy(pageAction = PageAction.NONE, pageTargetId = "")
            }
            if (control.pageAction != PageAction.NONE) {
                if (control.holdToggle || control.autoTapEnabled || control.buttonAimEnabled ||
                    control.buttonAimOneShotAlternateEnabled ||
                    control.buttonAimPayloadTiming != com.example.simplecontroller.model.ButtonAimPayloadTiming.IMMEDIATE
                ) {
                    warnings += "Incompatible Hold, Auto Tap, or Button Aim settings on '${control.name.ifBlank { control.id }}' were disabled for its local page action."
                }
                control = control.copy(
                    holdToggle = false,
                    autoTapEnabled = false,
                    buttonAimEnabled = false,
                    buttonAimOneShotAlternateEnabled = false,
                    buttonAimPayloadTiming = com.example.simplecontroller.model.ButtonAimPayloadTiming.IMMEDIATE
                )
            }
            control
        }
        ControllerPage(id, name, controls)
    }

    val validIds = pages.mapTo(mutableSetOf()) { it.id }
    val requestedHomeId = if (decoded.homePageId.isBlank() && recoveredMissingId != null) {
        recoveredMissingId
    } else decoded.homePageId
    val homeId = requestedHomeId.takeIf(validIds::contains) ?: pages.first().id.also {
        warnings += "The Home page was missing or invalid, so '${pages.first().name}' became Home."
    }

    pages.forEach { page ->
        page.controls.forEach { control ->
            if ((control.pageAction == PageAction.GO_TO || control.pageAction == PageAction.TOGGLE) &&
                control.pageTargetId !in validIds
            ) {
                warnings += "'${control.name.ifBlank { control.id }}' on '${page.name}' targets a missing page."
            }
        }
    }

    return ControllerProfileLoadResult(
        ControllerProfile(CONTROLLER_PROFILE_FORMAT_VERSION, homeId, pages),
        warnings.distinct(),
        migratedFromSinglePage = false
    )
}

/** Strict structural checks used before persistence. Missing targets remain valid and visible. */
fun validateControllerProfileForSave(profile: ControllerProfile): String? {
    if (profile.pages.isEmpty()) return "A controller profile must contain at least one page."
    val ids = profile.pages.map { it.id }
    if (ids.any(String::isBlank)) return "Every page must have a nonblank stable ID."
    if (ids.distinct().size != ids.size) return "Page IDs must be unique."
    if (profile.homePageId !in ids) return "The Home page must reference an existing page."

    val names = profile.pages.map { it.name.trim().lowercase(Locale.ROOT) }
    if (names.any(String::isBlank)) return "Every page must have a nonblank name."
    if (names.distinct().size != names.size) return "Page names must be unique."

    profile.pages.forEach { page ->
        page.controls.forEach { control ->
            if (control.pageAction != PageAction.NONE && control.type != ControlType.BUTTON) {
                return "Only button controls can have local page actions."
            }
            if ((control.pageAction == PageAction.GO_TO || control.pageAction == PageAction.TOGGLE) &&
                control.pageTargetId.isBlank()
            ) {
                return "Go To and Toggle actions must keep a target ID, even when that page is missing."
            }
            if (control.pageAction != PageAction.NONE &&
                (control.holdToggle || control.autoTapEnabled || control.buttonAimEnabled ||
                    control.buttonAimOneShotAlternateEnabled ||
                    control.buttonAimPayloadTiming != com.example.simplecontroller.model.ButtonAimPayloadTiming.IMMEDIATE)
            ) {
                return "Local page actions cannot keep Hold, Auto Tap, Button Aim, or delayed timing."
            }
        }
    }
    return null
}

private fun stableRecoveredPageId(
    profileName: String,
    pageName: String,
    index: Int,
    usedIds: Set<String>
): String {
    var attempt = 0
    while (true) {
        val value = UUID.nameUUIDFromBytes(
            "simplecontroller-profile:${profileName.lowercase(Locale.ROOT)}:$index:${pageName.lowercase(Locale.ROOT)}:$attempt"
                .toByteArray(StandardCharsets.UTF_8)
        ).toString()
        if (value !in usedIds) return value
        attempt++
    }
}

fun readControllerProfile(ctx: Context, name: String): StoredControllerProfileResult {
    val atomic = AtomicFile(fileFor(ctx, name))
    return try {
        val text = atomic.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        StoredControllerProfileResult.Loaded(decodeControllerProfile(text, name))
    } catch (_: FileNotFoundException) {
        StoredControllerProfileResult.NotFound
    } catch (error: Throwable) {
        Log.w(TAG, "load $name failed", error)
        StoredControllerProfileResult.Error("Could not read '$name'.", error)
    }
}

fun loadControllerProfile(ctx: Context, name: String): ControllerProfileLoadResult? =
    (readControllerProfile(ctx, name) as? StoredControllerProfileResult.Loaded)?.result

fun saveControllerProfile(ctx: Context, name: String, profile: ControllerProfile): Result<Unit> =
    runCatching {
        val atomic = AtomicFile(fileFor(ctx, name))
        val bytes = encodeControllerProfile(profile).toByteArray(StandardCharsets.UTF_8)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        Log.i(TAG, "saved ${profile.pages.size} pages -> $name")
        Unit
    }.onFailure { Log.e(TAG, "save $name failed", it) }

fun deleteControllerProfile(ctx: Context, name: String): Boolean = runCatching {
    val base = fileFor(ctx, name)
    AtomicFile(base).delete()
    listOf(base, File(base.path + ".bak"), File(base.path + ".new")).none(File::exists)
}.onFailure { Log.e(TAG, "delete $name failed", it) }.getOrDefault(false)

/** Backward-compatible API for dormant legacy callers: returns only the Home page controls. */
fun loadControls(ctx: Context, name: String): List<Control>? =
    loadControllerProfile(ctx, name)?.profile?.let { profile ->
        profile.pages.firstOrNull { it.id == profile.homePageId }?.controls
            ?: profile.pages.firstOrNull()?.controls
    }

/** Backward-compatible saver that creates a one-page profile. */
fun saveControls(ctx: Context, name: String, controls: List<Control>): Result<Unit> {
    val id = stableLegacyBasePageId(name)
    return saveControllerProfile(
        ctx,
        name,
        ControllerProfile(
            homePageId = id,
            pages = listOf(ControllerPage(id, "Base", deepCopyControls(controls)))
        )
    )
}
