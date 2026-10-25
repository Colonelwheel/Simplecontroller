package com.example.simplecontroller.model

/** Include inactive selections so portable profiles never silently lose saved behavior. */
fun Control.actionPayloads(): List<String> = listOf(
    payload, buttonAimAlternatePayload, thresholdPayload, superThresholdPayload,
    touchLowPayload, touchMediumPayload, touchHighPayload, touchAimAimPayload, touchAimShootPayload,
    upCommand, downCommand, leftCommand, rightCommand,
    upBoostCommand, downBoostCommand, leftBoostCommand, rightBoostCommand,
    upSuperBoostCommand, downSuperBoostCommand, leftSuperBoostCommand, rightSuperBoostCommand
) + extendedBoost.outerCommands.values() +
    listOf(buttonDirectional.up, buttonDirectional.left, buttonDirectional.right, buttonDirectional.down)
        .flatMap { listOf(it.firstPayload, it.secondPayload) }

fun RingBoostSettings.actionPayloads(): List<String> = directionalPayloads.values() + payload

fun Iterable<String>.payloadModifiersValidationError(): String? =
    firstNotNullOfOrNull { value ->
        if (PayloadModifiers.hasModifiers(value)) PayloadModifiers.parse(value).error else null
    }

fun FloatingRingConfig.actionPayloads(): List<String> =
    knobs.flatMap { listOf(it.fire.adsPayload, it.fire.firePayload) } +
        listOfNotNull(boost1, boost2, boostBanks?.movement?.boost1, boostBanks?.movement?.boost2,
            boostBanks?.aim?.boost1, boostBanks?.aim?.boost2).flatMap { it.actionPayloads() }

fun Control.hasStageTouchAimTuning(): Boolean =
    listOf(touchAimAimOnlySensitivity, touchLowSensitivity, touchMediumSensitivity, touchHighSensitivity)
        .any { it != null } ||
        listOf(touchAimAimOnlyEnterConfirmationMs, touchLowEnterConfirmationMs,
            touchMediumEnterConfirmationMs, touchHighEnterConfirmationMs).any { it != 0L }

fun Control.stageTouchAimTuningValidationError(): String? {
    if (listOf(touchAimAimOnlySensitivity, touchLowSensitivity, touchMediumSensitivity, touchHighSensitivity)
            .filterNotNull().any { !it.isFinite() || it !in 0f..5f }) {
        return "TouchAim stage sensitivity must be between 0 and 5."
    }
    if (listOf(touchAimAimOnlyEnterConfirmationMs, touchLowEnterConfirmationMs,
            touchMediumEnterConfirmationMs, touchHighEnterConfirmationMs).any { it !in 0L..600_000L }) {
        return "TouchAim stage confirmation must be between 0 and 600000 ms."
    }
    return null
}
