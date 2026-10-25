package com.example.simplecontroller.io

import android.content.Context
import com.example.simplecontroller.model.ButtonAimProfile
import com.example.simplecontroller.model.FloatingBoostProfile
import com.example.simplecontroller.model.FloatingRingPreset
import com.example.simplecontroller.model.MAX_SUPPORTED_CONTROLLER_PROFILE_FORMAT_VERSION
import com.example.simplecontroller.model.ControllerPage
import com.example.simplecontroller.model.ControllerProfile
import com.example.simplecontroller.model.StickDirectionalProfile
import com.example.simplecontroller.model.TouchAimCalibrationProfile
import com.example.simplecontroller.model.TouchAimManualProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.UUID

const val CONTROLLER_PROFILES_BACKUP_FILE_TYPE = "simplecontroller-profiles-backup"
const val LEGACY_CONTROLLER_PROFILES_BACKUP_VERSION = 1
const val FLOATING_BOOST_PROFILES_BACKUP_VERSION = 2
const val CONTROLLER_PROFILES_BACKUP_VERSION = 3
const val CONTROLLER_PROFILES_BACKUP_EXTENSION = ".simplecontroller-profiles-backup.json"

@Serializable
data class NamedControllerProfile(
    val name: String,
    val profile: ControllerProfile
)

@Serializable
data class ControllerProfilesBackup(
    val fileType: String = CONTROLLER_PROFILES_BACKUP_FILE_TYPE,
    val backupVersion: Int = LEGACY_CONTROLLER_PROFILES_BACKUP_VERSION,
    val activeControllerProfileName: String,
    val controllerProfiles: List<NamedControllerProfile>,
    val touchAimCalibrations: List<TouchAimCalibrationProfile> = emptyList(),
    val touchAimManualProfiles: List<TouchAimManualProfile> = emptyList(),
    val buttonAimProfiles: List<ButtonAimProfile> = emptyList(),
    val stickDirectionalProfiles: List<StickDirectionalProfile> = emptyList(),
    val floatingBoostProfiles: List<FloatingBoostProfile> = emptyList(),
    val floatingRingPresets: List<FloatingRingPreset> = emptyList()
)

/** New libraries need an envelope gate even when every nested controller uses legacy settings. */
fun ControllerProfilesBackup.requiredBackupVersion(): Int = when {
    floatingRingPresets.isNotEmpty() -> CONTROLLER_PROFILES_BACKUP_VERSION
    floatingBoostProfiles.isNotEmpty() -> FLOATING_BOOST_PROFILES_BACKUP_VERSION
    else -> LEGACY_CONTROLLER_PROFILES_BACKUP_VERSION
}

data class ControllerProfilesBackupLoadResult(
    val backup: ControllerProfilesBackup,
    val warnings: List<String>
)

sealed interface ProfileTransferDocument {
    data class Single(val imported: ImportedControllerProfile) : ProfileTransferDocument
    data class AllProfiles(val loaded: ControllerProfilesBackupLoadResult) : ProfileTransferDocument
}

data class ControllerProfilesBackupImportResult(
    val activeProfileName: String,
    val activeProfile: ControllerProfileLoadResult,
    val controllerProfileCount: Int,
    val touchAimCalibrationCount: Int,
    val touchAimManualProfileCount: Int,
    val buttonAimProfileCount: Int,
    val stickDirectionalProfileCount: Int,
    val warnings: List<String>,
    val floatingBoostProfileCount: Int = 0,
    val floatingRingPresetCount: Int = 0
)

private val backupJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun createControllerProfilesBackup(
    context: Context,
    activeProfileName: String,
    activeProfile: ControllerProfile
): Result<ControllerProfilesBackup> = runCatching {
    val storedNames = listLayouts(context).sortedWith(String.CASE_INSENSITIVE_ORDER)
    val profiles = mutableListOf<NamedControllerProfile>()
    var activeIncluded = false
    storedNames.forEach { storedName ->
        if (storedName.equals(activeProfileName, ignoreCase = true)) {
            profiles += NamedControllerProfile(activeProfileName, activeProfile)
            activeIncluded = true
        } else {
            val loaded = when (val stored = readControllerProfile(context, storedName)) {
                is StoredControllerProfileResult.Loaded -> stored.result.profile
                StoredControllerProfileResult.NotFound ->
                    error("Saved profile '$storedName' disappeared while the backup was being prepared.")
                is StoredControllerProfileResult.Error ->
                    error("Saved profile '$storedName' could not be read; no incomplete backup was created.")
            }
            profiles += NamedControllerProfile(storedName, loaded)
        }
    }
    if (!activeIncluded) profiles += NamedControllerProfile(activeProfileName, activeProfile)

    val calibrations = TouchAimCalibrationStore.backupSnapshot(context).requireFullyReadable(
        "TouchAim calibration"
    )
    val manualProfiles = TouchAimManualProfileStore.backupSnapshot(context).requireFullyReadable(
        "manual TouchAim"
    )
    val buttonProfiles = ButtonAimProfileStore.backupSnapshot(context).requireFullyReadable(
        "Button Aim"
    )
    val stickProfiles = StickDirectionalProfileStore.backupSnapshot(context).requireFullyReadable(
        "Stick directional"
    )
    val boostProfiles = FloatingBoostProfileStore.backupSnapshot(context).requireFullyReadable("Floating boost")
    val ringPresets = FloatingRingPresetStore.backupSnapshot(context).requireFullyReadable("Floating ring")

    val backup = ControllerProfilesBackup(
        backupVersion = when {
            ringPresets.isNotEmpty() -> CONTROLLER_PROFILES_BACKUP_VERSION
            boostProfiles.isNotEmpty() -> FLOATING_BOOST_PROFILES_BACKUP_VERSION
            else -> LEGACY_CONTROLLER_PROFILES_BACKUP_VERSION
        },
        activeControllerProfileName = activeProfileName,
        controllerProfiles = profiles,
        touchAimCalibrations = calibrations,
        touchAimManualProfiles = manualProfiles,
        buttonAimProfiles = buttonProfiles,
        stickDirectionalProfiles = stickProfiles,
        floatingBoostProfiles = boostProfiles,
        floatingRingPresets = ringPresets
    )
    validateControllerProfilesBackup(backup).backup
}

private fun <T> ProfileStoreBackupSnapshot<T>.requireFullyReadable(label: String): List<T> {
    require(unreadableFileCount == 0) {
        "$unreadableFileCount saved $label profile file(s) could not be read; no incomplete backup was created."
    }
    return profiles
}

fun encodeControllerProfilesBackup(backup: ControllerProfilesBackup): String {
    val validated = validateControllerProfilesBackup(backup.copy(backupVersion = backup.requiredBackupVersion())).backup
    return backupJson.encodeToString(
        validated.copy(
            fileType = CONTROLLER_PROFILES_BACKUP_FILE_TYPE,
            backupVersion = validated.requiredBackupVersion()
        )
    )
}

fun decodeProfileTransferDocument(
    text: String,
    fallbackProfileName: String
): ProfileTransferDocument {
    val normalizedText = text.removePrefix("\uFEFF")
    val element = backupJson.parseToJsonElement(normalizedText)
    if (element is JsonArray) {
        return ProfileTransferDocument.Single(
            decodeControllerProfileTransfer(normalizedText, fallbackProfileName)
        )
    }
    val objectValue = element as? JsonObject
        ?: throw IllegalArgumentException("This is not a SimpleController profile file.")
    val fileType = objectValue["fileType"]?.jsonPrimitive?.contentOrNull
    return if (fileType == CONTROLLER_PROFILES_BACKUP_FILE_TYPE) {
        ProfileTransferDocument.AllProfiles(decodeControllerProfilesBackup(normalizedText))
    } else {
        ProfileTransferDocument.Single(
            decodeControllerProfileTransfer(normalizedText, fallbackProfileName)
        )
    }
}

fun decodeControllerProfilesBackup(text: String): ControllerProfilesBackupLoadResult {
    val normalizedText = text.removePrefix("\uFEFF")
    val element = backupJson.parseToJsonElement(normalizedText)
    val objectValue = element as? JsonObject
        ?: throw IllegalArgumentException("This is not a SimpleController all-profiles backup.")
    val fileType = objectValue["fileType"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("The backup file type is missing or invalid.")
    require(fileType == CONTROLLER_PROFILES_BACKUP_FILE_TYPE) {
        "Unsupported profile backup file type '$fileType'."
    }
    val backupVersion = objectValue["backupVersion"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("The profile backup version is missing or invalid.")
    require(backupVersion >= 1) { "Profile backup version $backupVersion is invalid." }
    require(backupVersion <= CONTROLLER_PROFILES_BACKUP_VERSION) {
        "Profile backup version $backupVersion is newer than supported version " +
            CONTROLLER_PROFILES_BACKUP_VERSION
    }
    require(backupVersion >= 2 || (objectValue["floatingBoostProfiles"] as? JsonArray).orEmpty().isEmpty()) {
        "Floating boost profiles require backup version 2."
    }
    require(backupVersion >= 3 || (objectValue["floatingRingPresets"] as? JsonArray).orEmpty().isEmpty()) {
        "Floating ring presets require backup version 3."
    }

    requireNestedVersions(objectValue)
    val decoded = backupJson.decodeFromString<ControllerProfilesBackup>(normalizedText)
    val rawProfiles = objectValue["controllerProfiles"] as? JsonArray ?: JsonArray(emptyList())
    val normalizedProfiles = decoded.controllerProfiles.mapIndexed { index, named ->
        val rawProfile = (rawProfiles[index] as JsonObject)["profile"] as JsonObject
        val version = rawProfile["formatVersion"]?.jsonPrimitive?.intOrNull ?: 2
        named.copy(profile = named.profile.copy(formatVersion = version))
    }
    return validateControllerProfilesBackup(
        decoded.copy(controllerProfiles = normalizedProfiles)
    )
}

private fun requireNestedVersions(objectValue: JsonObject) {
    fun entries(key: String): JsonArray = objectValue[key] as? JsonArray ?: JsonArray(emptyList())
    entries("controllerProfiles").forEachIndexed { index, item ->
        val profile = (item as? JsonObject)?.get("profile") as? JsonObject
            ?: throw IllegalArgumentException("Controller profile ${index + 1} is missing its data.")
        requireSupportedVersion(
            "Controller profile ${index + 1}",
            profile["formatVersion"]?.jsonPrimitive?.intOrNull ?: 2,
            MAX_SUPPORTED_CONTROLLER_PROFILE_FORMAT_VERSION
        )
        requireFloatingOptionsFormat(profile, profile["formatVersion"]?.jsonPrimitive?.intOrNull ?: 2)
    }
    listOf(
        Triple("touchAimCalibrations", "TouchAim calibration", TouchAimCalibrationProfile.CURRENT_SCHEMA_VERSION),
        Triple("touchAimManualProfiles", "Manual TouchAim profile", TouchAimManualProfile.CURRENT_SCHEMA_VERSION),
        Triple("buttonAimProfiles", "Button Aim profile", ButtonAimProfile.CURRENT_SCHEMA_VERSION),
        Triple("stickDirectionalProfiles", "Stick directional profile", StickDirectionalProfile.CURRENT_SCHEMA_VERSION),
        Triple("floatingBoostProfiles", "Floating boost profile", FloatingBoostProfile.CURRENT_SCHEMA_VERSION),
        Triple("floatingRingPresets", "Floating ring preset", FloatingRingPreset.CURRENT_SCHEMA_VERSION)
    ).forEach { (key, label, current) ->
        entries(key).forEachIndexed { index, item ->
            val itemObject = item as? JsonObject
                ?: throw IllegalArgumentException("$label ${index + 1} is invalid.")
            requireSupportedVersion(
                "$label ${index + 1}",
                itemObject["schemaVersion"]?.jsonPrimitive?.intOrNull ?: current,
                current
            )
            when (key) {
                "touchAimCalibrations" -> requirePayloadFeatureSchema(itemObject, label, current, current)
                "touchAimManualProfiles" -> requirePayloadFeatureSchema(itemObject, label, current, current, stageTuning = true)
                "buttonAimProfiles" -> requirePayloadFeatureSchema(itemObject, label, current, current,
                    legacyVersion = ButtonAimProfile.LEGACY_SCHEMA_VERSION)
                "stickDirectionalProfiles" -> requirePayloadFeatureSchema(itemObject, label, current, current,
                    legacyVersion = StickDirectionalProfile.LEGACY_SCHEMA_VERSION)
            }
            if (key == "floatingRingPresets") {
                requireFloatingRingPresetOptionsSchema(
                    itemObject,
                    itemObject["schemaVersion"]?.jsonPrimitive?.intOrNull ?: FloatingRingPreset.LEGACY_SCHEMA_VERSION
                )
            } else if (key == "floatingBoostProfiles") {
                requireSupportedFloatingBoostSchema(itemObject)
            }
        }
    }
}

private fun requireSupportedVersion(label: String, version: Int, current: Int) {
    require(version >= 1) { "$label version $version is invalid." }
    require(version <= current) { "$label version $version is newer than supported version $current." }
}

fun validateControllerProfilesBackup(
    decoded: ControllerProfilesBackup
): ControllerProfilesBackupLoadResult {
    require(decoded.fileType == CONTROLLER_PROFILES_BACKUP_FILE_TYPE) {
        "Unsupported profile backup file type '${decoded.fileType}'."
    }
    require(decoded.backupVersion in 1..CONTROLLER_PROFILES_BACKUP_VERSION) {
        "Profile backup version ${decoded.backupVersion} is not supported."
    }
    require(decoded.floatingBoostProfiles.isEmpty() || decoded.backupVersion >= 2) {
        "Floating boost profiles require backup version 2."
    }
    require(decoded.floatingRingPresets.isEmpty() || decoded.backupVersion >= 3) {
        "Floating ring presets require backup version 3."
    }
    require(decoded.controllerProfiles.isNotEmpty()) {
        "An all-profiles backup must contain at least one controller profile."
    }
    requireUniqueNames(decoded.controllerProfiles.map { it.name }, "controller profile")
    require(decoded.controllerProfiles.any {
        it.name.equals(decoded.activeControllerProfileName, ignoreCase = true)
    }) { "The backup's active controller profile is missing." }

    val warnings = mutableListOf<String>()
    val controllerProfiles = decoded.controllerProfiles.map { named ->
        validateImportedProfileName(named.name)?.let { throw IllegalArgumentException(it) }
        val validated = validateControllerProfile(named.profile, named.name)
        warnings += validated.warnings.map { "${named.name}: $it" }
        NamedControllerProfile(named.name.trim(), validated.profile)
    }

    validateReusableProfiles(decoded.touchAimCalibrations, "TouchAim calibration") {
        it.versionValidationError()?.let { error -> throw IllegalArgumentException(error) }
        require(it.name.trim().isNotEmpty() && it.name.trim().length <= 80) {
            "Choose a calibration name from 1 to 80 characters."
        }
    }
    validateReusableProfiles(decoded.touchAimManualProfiles, "manual TouchAim profile") {
        it.validationError()?.let { error -> throw IllegalArgumentException(error) }
    }
    validateReusableProfiles(decoded.buttonAimProfiles, "Button Aim profile") {
        it.validationError()?.let { error -> throw IllegalArgumentException(error) }
    }
    validateReusableProfiles(decoded.stickDirectionalProfiles, "Stick directional profile") {
        it.validationError()?.let { error -> throw IllegalArgumentException(error) }
    }
    validateReusableProfiles(decoded.floatingBoostProfiles, "Floating boost profile") {
        it.validationError()?.let { error -> throw IllegalArgumentException(error) }
    }
    validateReusableProfiles(decoded.floatingRingPresets, "Floating ring preset") {
        it.validationError()?.let { error -> throw IllegalArgumentException(error) }
    }

    return ControllerProfilesBackupLoadResult(
        decoded.copy(
            controllerProfiles = controllerProfiles,
            touchAimCalibrations = decoded.touchAimCalibrations.map { it.copy(schemaVersion = it.requiredSchemaVersion()) },
            touchAimManualProfiles = decoded.touchAimManualProfiles.map { it.copy(schemaVersion = it.requiredSchemaVersion()) },
            stickDirectionalProfiles = decoded.stickDirectionalProfiles.map { it.copy(schemaVersion = it.requiredSchemaVersion()) },
            buttonAimProfiles = decoded.buttonAimProfiles.map {
                it.copy(schemaVersion = it.requiredSchemaVersion())
            }
        ),
        warnings.distinct()
    )
}

private inline fun <T> validateReusableProfiles(
    profiles: List<T>,
    label: String,
    validate: (T) -> Unit
) where T : Any {
    val ids = profiles.map { profileId(it) }
    require(ids.distinct().size == ids.size) { "The backup contains duplicate $label IDs." }
    ids.forEach { id ->
        require(runCatching { UUID.fromString(id) }.getOrNull()?.toString() == id.lowercase(Locale.ROOT)) {
            "The backup contains an invalid $label ID."
        }
    }
    requireUniqueNames(profiles.map { profileName(it) }, label)
    profiles.forEach(validate)
}

private fun profileId(profile: Any): String = when (profile) {
    is TouchAimCalibrationProfile -> profile.id
    is TouchAimManualProfile -> profile.id
    is ButtonAimProfile -> profile.id
    is StickDirectionalProfile -> profile.id
    is FloatingBoostProfile -> profile.id
    is FloatingRingPreset -> profile.id
    else -> error("Unsupported reusable profile type.")
}

private fun profileName(profile: Any): String = when (profile) {
    is TouchAimCalibrationProfile -> profile.name
    is TouchAimManualProfile -> profile.name
    is ButtonAimProfile -> profile.name
    is StickDirectionalProfile -> profile.name
    is FloatingBoostProfile -> profile.name
    is FloatingRingPreset -> profile.name
    else -> error("Unsupported reusable profile type.")
}

private fun requireUniqueNames(names: List<String>, label: String) {
    val normalized = names.map { it.trim().lowercase(Locale.ROOT) }
    require(normalized.none(String::isBlank) && normalized.distinct().size == normalized.size) {
        "The backup contains blank or duplicate $label names."
    }
}

fun controllerProfilesBackupFileName(): String =
    "SimpleController Profiles Backup$CONTROLLER_PROFILES_BACKUP_EXTENSION"

fun importControllerProfilesBackup(
    context: Context,
    loaded: ControllerProfilesBackupLoadResult
): Result<ControllerProfilesBackupImportResult> = runCatching {
    val backup = loaded.backup
    val existingControllerNames = listLayouts(context).toMutableList()
    val existingCalibrations = TouchAimCalibrationStore.list(context)
    val existingManual = TouchAimManualProfileStore.list(context)
    val existingButton = ButtonAimProfileStore.list(context)
    val existingStick = StickDirectionalProfileStore.list(context)
    val existingBoost = FloatingBoostProfileStore.list(context)
    val existingRings = FloatingRingPresetStore.list(context)

    fun freshId(reserved: MutableSet<String>): String {
        while (true) {
            val candidate = UUID.randomUUID().toString()
            if (reserved.add(candidate)) return candidate
        }
    }

    val calibrationNames = existingCalibrations.mapTo(mutableListOf()) { it.name }
    val calibrationIds = existingCalibrations.mapTo(mutableSetOf()) { it.id.lowercase(Locale.ROOT) }
    val calibrationIdMap = mutableMapOf<String, Pair<String, String>>()
    val calibrations = backup.touchAimCalibrations.map { source ->
        val name = uniqueImportedProfileName(source.name, calibrationNames)
        calibrationNames += name
        val id = freshId(calibrationIds)
        calibrationIdMap[source.id] = id to name
        source.copy(id = id, name = name)
    }

    val importedControllerNames = mutableMapOf<String, String>()
    val controllers = backup.controllerProfiles.map { source ->
        val name = uniqueImportedProfileName(source.name, existingControllerNames)
        existingControllerNames += name
        importedControllerNames[source.name.lowercase(Locale.ROOT)] = name
        NamedControllerProfile(name, source.profile.remapAppliedCalibrations(calibrationIdMap))
    }

    val manualNames = existingManual.mapTo(mutableListOf()) { it.name }
    val manualIds = existingManual.mapTo(mutableSetOf()) { it.id.lowercase(Locale.ROOT) }
    val manualProfiles = backup.touchAimManualProfiles.map { source ->
        val name = uniqueImportedProfileName(source.name, manualNames)
        manualNames += name
        source.copy(id = freshId(manualIds), name = name)
    }
    val buttonNames = existingButton.mapTo(mutableListOf()) { it.name }
    val buttonIds = existingButton.mapTo(mutableSetOf()) { it.id.lowercase(Locale.ROOT) }
    val buttonProfiles = backup.buttonAimProfiles.map { source ->
        val name = uniqueImportedProfileName(source.name, buttonNames)
        buttonNames += name
        source.copy(id = freshId(buttonIds), name = name)
    }
    val stickNames = existingStick.mapTo(mutableListOf()) { it.name }
    val stickIds = existingStick.mapTo(mutableSetOf()) { it.id.lowercase(Locale.ROOT) }
    val stickProfiles = backup.stickDirectionalProfiles.map { source ->
        val name = uniqueImportedProfileName(source.name, stickNames)
        stickNames += name
        source.copy(id = freshId(stickIds), name = name)
    }
    val boostNames = existingBoost.mapTo(mutableListOf()) { it.name }
    val boostIds = existingBoost.mapTo(mutableSetOf()) { it.id.lowercase(Locale.ROOT) }
    val boostProfiles = backup.floatingBoostProfiles.map { source ->
        val name = uniqueImportedProfileName(source.name, boostNames)
        boostNames += name
        source.copy(id = freshId(boostIds), name = name)
    }
    val ringNames = existingRings.mapTo(mutableListOf()) { it.name }
    val ringIds = existingRings.mapTo(mutableSetOf()) { it.id.lowercase(Locale.ROOT) }
    val ringPresets = backup.floatingRingPresets.map { source ->
        val name = uniqueImportedProfileName(source.name, ringNames)
        ringNames += name
        source.copy(id = freshId(ringIds), name = name)
    }

    val createdControllerNames = mutableListOf<String>()
    val createdCalibrationIds = mutableListOf<String>()
    val createdManualIds = mutableListOf<String>()
    val createdButtonIds = mutableListOf<String>()
    val createdStickIds = mutableListOf<String>()
    val createdBoostIds = mutableListOf<String>()
    val createdRingIds = mutableListOf<String>()
    try {
        calibrations.forEach { profile ->
            require(TouchAimCalibrationStore.save(context, profile).succeeded) {
                "Could not save imported TouchAim calibration '${profile.name}'."
            }
            createdCalibrationIds += profile.id
        }
        manualProfiles.forEach { profile ->
            require(TouchAimManualProfileStore.save(context, profile).succeeded) {
                "Could not save imported manual TouchAim profile '${profile.name}'."
            }
            createdManualIds += profile.id
        }
        buttonProfiles.forEach { profile ->
            require(ButtonAimProfileStore.save(context, profile).succeeded) {
                "Could not save imported Button Aim profile '${profile.name}'."
            }
            createdButtonIds += profile.id
        }
        stickProfiles.forEach { profile ->
            require(StickDirectionalProfileStore.save(context, profile).succeeded) {
                "Could not save imported Stick profile '${profile.name}'."
            }
            createdStickIds += profile.id
        }
        boostProfiles.forEach { profile ->
            require(FloatingBoostProfileStore.save(context, profile).succeeded) {
                "Could not save imported floating boost profile '${profile.name}'."
            }
            createdBoostIds += profile.id
        }
        ringPresets.forEach { preset ->
            require(FloatingRingPresetStore.save(context, preset).succeeded) {
                "Could not save imported floating ring preset '${preset.name}'."
            }
            createdRingIds += preset.id
        }
        controllers.forEach { named ->
            saveControllerProfile(context, named.name, named.profile).getOrThrow()
            createdControllerNames += named.name
            val verified = (readControllerProfile(
                context,
                named.name
            ) as? StoredControllerProfileResult.Loaded)?.result?.profile
            require(verified == named.profile) {
                "Imported controller profile '${named.name}' could not be verified."
            }
        }

        require(calibrations.all { expected ->
            TouchAimCalibrationStore.get(context, expected.id) == expected
        }) { "An imported TouchAim calibration could not be verified." }
        require(manualProfiles.all { expected ->
            TouchAimManualProfileStore.list(context).any { it == expected }
        }) { "An imported manual TouchAim profile could not be verified." }
        require(buttonProfiles.all { expected ->
            ButtonAimProfileStore.list(context).any { it == expected }
        }) { "An imported Button Aim profile could not be verified." }
        require(stickProfiles.all { expected ->
            StickDirectionalProfileStore.list(context).any { it == expected }
        }) { "An imported Stick profile could not be verified." }
        require(boostProfiles.all { expected ->
            FloatingBoostProfileStore.list(context).any { it == expected }
        }) { "An imported floating boost profile could not be verified." }
        require(ringPresets.all { expected ->
            FloatingRingPresetStore.list(context).any { it == expected }
        }) { "An imported floating ring preset could not be verified." }

        val activeName = requireNotNull(
            importedControllerNames[backup.activeControllerProfileName.lowercase(Locale.ROOT)]
        )
        val active = (readControllerProfile(
            context,
            activeName
        ) as? StoredControllerProfileResult.Loaded)?.result
            ?: error("The imported active controller profile could not be reopened.")
        ControllerProfilesBackupImportResult(
            activeProfileName = activeName,
            activeProfile = active,
            controllerProfileCount = controllers.size,
            touchAimCalibrationCount = calibrations.size,
            touchAimManualProfileCount = manualProfiles.size,
            buttonAimProfileCount = buttonProfiles.size,
            stickDirectionalProfileCount = stickProfiles.size,
            warnings = loaded.warnings,
            floatingBoostProfileCount = boostProfiles.size,
            floatingRingPresetCount = ringPresets.size
        )
    } catch (failure: Throwable) {
        var rollbackComplete = true
        createdControllerNames.asReversed().forEach {
            rollbackComplete = deleteControllerProfile(context, it) && rollbackComplete
        }
        createdCalibrationIds.asReversed().forEach {
            rollbackComplete = TouchAimCalibrationStore.delete(context, it) && rollbackComplete
        }
        createdManualIds.asReversed().forEach {
            rollbackComplete = TouchAimManualProfileStore.delete(context, it) && rollbackComplete
        }
        createdButtonIds.asReversed().forEach {
            rollbackComplete = ButtonAimProfileStore.delete(context, it) && rollbackComplete
        }
        createdStickIds.asReversed().forEach {
            rollbackComplete = StickDirectionalProfileStore.delete(context, it) && rollbackComplete
        }
        createdBoostIds.asReversed().forEach {
            rollbackComplete = FloatingBoostProfileStore.delete(context, it) && rollbackComplete
        }
        createdRingIds.asReversed().forEach {
            rollbackComplete = FloatingRingPresetStore.delete(context, it) && rollbackComplete
        }
        if (!rollbackComplete) {
            throw IllegalStateException(
                "${failure.message.orEmpty()} Some newly imported profiles could not be rolled back.",
                failure
            )
        }
        throw failure
    }
}

private fun ControllerProfile.remapAppliedCalibrations(
    calibrationIdMap: Map<String, Pair<String, String>>
): ControllerProfile = copy(
    pages = pages.map { page: ControllerPage ->
        page.copy(
            controls = page.controls.map { control ->
                val replacement = calibrationIdMap[control.touchAimAppliedCalibrationId]
                if (replacement == null) control else control.copy(
                    touchAimAppliedCalibrationId = replacement.first,
                    touchAimAppliedCalibrationName = replacement.second
                )
            }
        )
    }
)
