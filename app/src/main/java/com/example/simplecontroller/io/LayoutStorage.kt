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
import com.example.simplecontroller.model.capturePageGeometry
import com.example.simplecontroller.model.newPageId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.FileNotFoundException
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.Locale

private const val TAG = "LayoutStorage"
private const val EXT = ".json"
private const val PENDING_PROFILE_EXPORT_FILE = "pending_controller_profile_export.json"
const val CONTROLLER_PROFILE_TRANSFER_FILE_TYPE = "simplecontroller-profile"
const val CONTROLLER_PROFILE_TRANSFER_VERSION = 1
const val CONTROLLER_PROFILE_TRANSFER_EXTENSION = ".simplecontroller-profile.json"
const val MAX_CONTROLLER_PROFILE_TRANSFER_BYTES = 25 * 1024 * 1024

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

@kotlinx.serialization.Serializable
private data class ControllerProfileTransferFile(
    val fileType: String = CONTROLLER_PROFILE_TRANSFER_FILE_TYPE,
    val transferVersion: Int = CONTROLLER_PROFILE_TRANSFER_VERSION,
    val profileName: String,
    val profile: ControllerProfile
)

data class ImportedControllerProfile(
    val suggestedName: String,
    val result: ControllerProfileLoadResult
)

private fun fileFor(ctx: Context, name: String) =
    ctx.getFileStreamPath("layout_${name.lowercase(Locale.ROOT)}$EXT")

/** Return saved profile names without the historical layout_ prefix. */
fun listLayouts(ctx: Context): List<String> =
    ctx.filesDir.listFiles()
        ?.map { it.name.removeSuffix(".bak").removeSuffix(".new") }
        ?.filter { it.startsWith("layout_") && it.endsWith(EXT) }
        ?.map { it.removePrefix("layout_").removeSuffix(EXT) }
        ?.distinct()
        .orEmpty()

fun stableLegacyBasePageId(profileName: String): String = UUID.nameUUIDFromBytes(
    "simplecontroller-profile:${profileName.trim().lowercase(Locale.ROOT)}:base"
        .toByteArray(StandardCharsets.UTF_8)
).toString()

fun decodeControllerProfile(text: String, profileName: String): ControllerProfileLoadResult {
    val normalizedText = text.removePrefix("\uFEFF")
    if (normalizedText.trimStart().startsWith("[")) {
        val controls = json.decodeFromString<List<Control>>(normalizedText)
        val pageId = stableLegacyBasePageId(profileName)
        return validateControllerProfile(
            decoded = ControllerProfile(
                formatVersion = 2,
                homePageId = pageId,
                pages = listOf(ControllerPage(pageId, "Base", controls))
            ),
            profileName = profileName
        ).copy(migratedFromSinglePage = true)
    }
    val element = json.parseToJsonElement(normalizedText)
    val encodedVersion = (element as? JsonObject)
        ?.get("formatVersion")
        ?.jsonPrimitive
        ?.intOrNull
        ?: 2
    require(encodedVersion >= 1) { "Profile format $encodedVersion is invalid." }
    require(encodedVersion <= CONTROLLER_PROFILE_FORMAT_VERSION) {
        "Profile format $encodedVersion is newer than supported format " +
            CONTROLLER_PROFILE_FORMAT_VERSION
    }
    val decoded = json.decodeFromJsonElement<ControllerProfile>(element)
        .copy(formatVersion = encodedVersion)
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

/**
 * Portable, user-owned profile file. This wrapper is deliberately independent from Android's
 * application ID, signing certificate, and target SDK so it can cross debug/release installs.
 */
fun encodeControllerProfileTransfer(profileName: String, profile: ControllerProfile): String {
    validateControllerProfileForSave(profile)?.let { throw IllegalArgumentException(it) }
    return json.encodeToString(
        ControllerProfileTransferFile(
            profileName = profileName.trim(),
            profile = profile.copy(formatVersion = CONTROLLER_PROFILE_FORMAT_VERSION)
        )
    )
}

/**
 * Reads the transfer wrapper plus raw current-profile JSON and historical top-level control arrays.
 * The last two forms keep files created before the transfer wrapper importable.
 */
fun decodeControllerProfileTransfer(
    text: String,
    fallbackProfileName: String
): ImportedControllerProfile {
    val fallbackName = sanitizeSuggestedProfileName(fallbackProfileName)
    val normalizedText = text.removePrefix("\uFEFF")
    val element = json.parseToJsonElement(normalizedText)
    if (element is JsonArray) {
        return ImportedControllerProfile(
            fallbackName,
            decodeControllerProfile(normalizedText, fallbackName)
        )
    }

    val objectValue = element as? JsonObject
        ?: throw IllegalArgumentException("This is not a SimpleController profile file.")
    if (!objectValue.containsKey("fileType")) {
        require(objectValue.containsKey("pages")) {
            "This is not a SimpleController profile file."
        }
        return ImportedControllerProfile(
            fallbackName,
            decodeControllerProfile(normalizedText, fallbackName)
        )
    }

    val fileType = objectValue["fileType"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("The profile file type is missing or invalid.")
    require(fileType == CONTROLLER_PROFILE_TRANSFER_FILE_TYPE) {
        "Unsupported profile file type '$fileType'."
    }
    val transferVersion = objectValue["transferVersion"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("The profile transfer version is missing or invalid.")
    require(transferVersion >= 1) { "Profile transfer version $transferVersion is invalid." }
    require(transferVersion <= CONTROLLER_PROFILE_TRANSFER_VERSION) {
        "Profile transfer version $transferVersion is newer than supported version " +
            CONTROLLER_PROFILE_TRANSFER_VERSION
    }
    val encodedProfile = objectValue["profile"] as? JsonObject
        ?: throw IllegalArgumentException("The profile data is missing or invalid.")
    val profileFormatVersion = encodedProfile["formatVersion"]?.jsonPrimitive?.intOrNull
        ?: 2
    require(profileFormatVersion >= 1) {
        "Profile format $profileFormatVersion is invalid."
    }
    require(profileFormatVersion <= CONTROLLER_PROFILE_FORMAT_VERSION) {
        "Profile format $profileFormatVersion is newer than supported format " +
            CONTROLLER_PROFILE_FORMAT_VERSION
    }

    val transfer = json.decodeFromJsonElement<ControllerProfileTransferFile>(element)

    val suggestedName = sanitizeSuggestedProfileName(transfer.profileName.ifBlank { fallbackName })
    return ImportedControllerProfile(
        suggestedName,
        validateControllerProfile(
            transfer.profile.copy(formatVersion = profileFormatVersion),
            suggestedName
        )
    )
}

fun controllerProfileTransferFileName(profileName: String): String =
    sanitizeSuggestedProfileName(profileName) + CONTROLLER_PROFILE_TRANSFER_EXTENSION

fun sanitizeSuggestedProfileName(value: String): String {
    val withoutTransferExtension = value.replace(
        Regex("(?i)\\.simplecontroller-profile\\.json$"),
        ""
    ).replace(Regex("(?i)\\.json$"), "")
    return withoutTransferExtension
        .replace(Regex("[\\\\/\\u0000-\\u001F\\u007F]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.')
        .take(80)
        .ifBlank { "Imported Profile" }
}

fun validateImportedProfileName(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Enter a profile name."
    if (trimmed.length > 80) return "Profile names must be 80 characters or fewer."
    if (Regex("[\\\\/\\u0000-\\u001F\\u007F]").containsMatchIn(trimmed)) {
        return "Profile names cannot contain slashes or control characters."
    }
    return null
}

fun uniqueImportedProfileName(suggestedName: String, existingNames: Collection<String>): String {
    val normalizedExisting = existingNames.mapTo(mutableSetOf()) {
        it.trim().lowercase(Locale.ROOT)
    }
    val base = sanitizeSuggestedProfileName(suggestedName)
    if (base.lowercase(Locale.ROOT) !in normalizedExisting) return base

    fun withSuffix(suffix: String): String {
        val available = (80 - suffix.length).coerceAtLeast(1)
        return base.take(available).trimEnd() + suffix
    }

    var candidate = withSuffix(" (Imported)")
    if (candidate.lowercase(Locale.ROOT) !in normalizedExisting) return candidate
    var index = 2
    while (true) {
        candidate = withSuffix(" (Imported $index)")
        if (candidate.lowercase(Locale.ROOT) !in normalizedExisting) return candidate
        index++
    }
}

/** Prevent a malformed or hostile document provider from allocating an unbounded import. */
fun readControllerProfileTransferText(
    input: InputStream,
    maxBytes: Int = MAX_CONTROLLER_PROFILE_TRANSFER_BYTES
): String {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            val singleByte = input.read()
            if (singleByte < 0) break
            total++
            require(total <= maxBytes) {
                "Profile file is larger than ${maxBytes / (1024 * 1024)} MB."
            }
            output.write(singleByte)
            continue
        }
        total += count
        require(total <= maxBytes) {
            "Profile file is larger than ${maxBytes / (1024 * 1024)} MB."
        }
        output.write(buffer, 0, count)
    }
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output.toByteArray()))
        .toString()
        .removePrefix("\uFEFF")
}

/**
 * Stage the exact export snapshot before opening the system picker. App-private staging lets the
 * Activity Result callback finish safely even if Android recreates the Activity while the picker
 * is open. It is never offered as a saved controller profile.
 */
fun stageControllerProfileTransfer(
    ctx: Context,
    profileName: String,
    profile: ControllerProfile
): Result<Unit> = stageProfileTransferDocument(
    ctx,
    encodeControllerProfileTransfer(profileName, profile)
)

/** Stage either the one-profile document or the all-profiles backup document. */
fun stageProfileTransferDocument(ctx: Context, text: String): Result<Unit> = runCatching {
    val atomic = AtomicFile(File(ctx.filesDir, PENDING_PROFILE_EXPORT_FILE))
    atomic.delete()
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= MAX_CONTROLLER_PROFILE_TRANSFER_BYTES) {
        "Profile export is larger than ${MAX_CONTROLLER_PROFILE_TRANSFER_BYTES / (1024 * 1024)} MB."
    }
    val stream = atomic.startWrite()
    try {
        stream.write(bytes)
        atomic.finishWrite(stream)
    } catch (error: Throwable) {
        atomic.failWrite(stream)
        throw error
    }
}

fun readStagedControllerProfileTransfer(ctx: Context): Result<String> = runCatching {
    AtomicFile(File(ctx.filesDir, PENDING_PROFILE_EXPORT_FILE)).openRead().use {
        readControllerProfileTransferText(it)
    }
}

fun clearStagedControllerProfileTransfer(ctx: Context) {
    AtomicFile(File(ctx.filesDir, PENDING_PROFILE_EXPORT_FILE)).delete()
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
        validateOrientationGeometry(source.portraitGeometry, "$name Portrait")
        validateOrientationGeometry(source.landscapeGeometry, "$name Landscape")
        val usedControlIds = mutableSetOf<String>()
        val controls = deepCopyControls(source.controls).mapIndexed { controlIndex, original ->
            var control = original
            if (control.id.isBlank() || !usedControlIds.add(control.id)) {
                require(
                    decoded.formatVersion < CONTROLLER_PROFILE_FORMAT_VERSION &&
                        source.portraitGeometry == null && source.landscapeGeometry == null
                ) {
                    "Page '$name' contains blank or duplicate control IDs; its orientation geometry is ambiguous."
                }
                val replacement = stableRecoveredControlId(
                    profileName,
                    id,
                    controlIndex,
                    usedControlIds
                )
                usedControlIds += replacement
                control = control.copy(id = replacement)
                warnings += "A blank or duplicate control ID on '$name' was assigned a stable replacement."
            }
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
        val migratedPortraitGeometry = if (
            decoded.formatVersion < CONTROLLER_PROFILE_FORMAT_VERSION &&
            source.portraitGeometry == null && source.landscapeGeometry == null
        ) {
            capturePageGeometry(controls, 0f, 0f)
        } else source.portraitGeometry
        source.copy(
            id = id,
            name = name,
            controls = controls,
            portraitGeometry = migratedPortraitGeometry
        )
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
        val controlIds = page.controls.map { it.id }
        if (controlIds.any(String::isBlank)) return "Every control must have a nonblank stable ID."
        if (controlIds.distinct().size != controlIds.size) {
            return "Control IDs must be unique within each page."
        }
        runCatching {
            validateOrientationGeometry(page.portraitGeometry, "${page.name} Portrait")
            validateOrientationGeometry(page.landscapeGeometry, "${page.name} Landscape")
        }.exceptionOrNull()?.let { return it.message ?: "Orientation geometry is invalid." }
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

private fun stableRecoveredControlId(
    profileName: String,
    pageId: String,
    index: Int,
    usedIds: Set<String>
): String {
    var attempt = 0
    while (true) {
        val value = "control_" + UUID.nameUUIDFromBytes(
            "simplecontroller-control:${profileName.lowercase(Locale.ROOT)}:$pageId:$index:$attempt"
                .toByteArray(StandardCharsets.UTF_8)
        )
        if (value !in usedIds) return value
        attempt++
    }
}

private fun validateOrientationGeometry(
    geometry: com.example.simplecontroller.model.PageOrientationGeometry?,
    label: String
) {
    if (geometry == null) return
    require(geometry.canvasWidth.isFinite() && geometry.canvasHeight.isFinite()) {
        "$label canvas dimensions are invalid."
    }
    geometry.controls.forEach { (controlId, value) ->
        require(controlId.isNotBlank()) { "$label contains a blank control ID." }
        require(value.x.isFinite() && value.y.isFinite() && value.w.isFinite() && value.h.isFinite()) {
            "$label contains non-finite control geometry."
        }
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
