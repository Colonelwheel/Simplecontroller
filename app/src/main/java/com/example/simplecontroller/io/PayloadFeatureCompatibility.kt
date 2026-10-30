package com.example.simplecontroller.io

import com.example.simplecontroller.model.PayloadModifiers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private val actionFields = setOf(
    "payload", "alternatePayload", "buttonAimAlternatePayload", "thresholdPayload", "superThresholdPayload",
    "touchLowPayload", "touchMediumPayload", "touchHighPayload", "touchAimAimPayload", "touchAimShootPayload",
    "upCommand", "downCommand", "leftCommand", "rightCommand", "upBoostCommand", "downBoostCommand",
    "leftBoostCommand", "rightBoostCommand", "upSuperBoostCommand", "downSuperBoostCommand",
    "leftSuperBoostCommand", "rightSuperBoostCommand", "firstPayload", "secondPayload", "lowPayload",
    "mediumPayload", "highPayload", "aimPayload", "shootPayload", "adsPayload", "firePayload",
    "up", "down", "left", "right"
)
private val stageSensitivityFields = setOf(
    "touchAimAimOnlySensitivity", "touchLowSensitivity", "touchMediumSensitivity", "touchHighSensitivity",
    "aimOnlySensitivity", "lowSensitivity", "mediumSensitivity", "highSensitivity"
)
private val stageConfirmationFields = setOf(
    "touchAimAimOnlyEnterConfirmationMs", "touchLowEnterConfirmationMs", "touchMediumEnterConfirmationMs",
    "touchHighEnterConfirmationMs", "aimOnlyEnterConfirmationMs", "lowEnterConfirmationMs",
    "mediumEnterConfirmationMs", "highEnterConfirmationMs"
)

/** Inspect raw JSON first: typed decode may otherwise discard an unsupported future setting. */
internal fun JsonElement.hasPayloadModifierConfiguration(): Boolean = when (this) {
    is JsonArray -> any { it.hasPayloadModifierConfiguration() }
    is JsonObject -> any { (key, value) ->
        (key in actionFields && (value as? JsonPrimitive)?.contentOrNull?.let(PayloadModifiers::hasModifiers) == true) ||
            value.hasPayloadModifierConfiguration()
    }
    else -> false
}

internal fun JsonElement.hasRepeatPayloadConfiguration(): Boolean = when (this) {
    is JsonArray -> any { it.hasRepeatPayloadConfiguration() }
    is JsonObject -> any { (key, value) ->
        (key in actionFields && (value as? JsonPrimitive)?.contentOrNull?.let(PayloadModifiers::hasRepeatModifiers) == true) ||
            value.hasRepeatPayloadConfiguration()
    }
    else -> false
}

internal fun JsonElement.hasStageTouchAimConfiguration(): Boolean = when (this) {
    is JsonArray -> any { it.hasStageTouchAimConfiguration() }
    is JsonObject -> any { (key, value) ->
        (key in stageSensitivityFields && value != JsonNull) ||
            (key in stageConfirmationFields && (value as? JsonPrimitive)?.longOrNull != 0L) ||
            value.hasStageTouchAimConfiguration()
    }
    else -> false
}

internal fun requirePayloadFeatureSchema(
    value: JsonObject,
    label: String,
    maxVersion: Int,
    featureVersion: Int,
    legacyVersion: Int = 1,
    stageTuning: Boolean = false,
    repeatVersion: Int? = null
) {
    val version = (value["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: legacyVersion
    require(version in 1..maxVersion) { "$label version $version is newer than supported version $maxVersion or invalid." }
    require(repeatVersion == null || version >= repeatVersion || !value.hasRepeatPayloadConfiguration()) {
        "The saved repeat payload modifier requires $label version $repeatVersion."
    }
    require(version >= featureVersion ||
        (!value.hasPayloadModifierConfiguration() && (!stageTuning || !value.hasStageTouchAimConfiguration()))) {
        "The saved payload modifiers or stage tuning require $label version $featureVersion."
    }
}
