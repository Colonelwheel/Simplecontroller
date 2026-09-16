package com.example.simplecontroller.model

import com.example.simplecontroller.io.ControllerProfilesBackup
import com.example.simplecontroller.io.NamedControllerProfile
import com.example.simplecontroller.io.decodeControllerProfilesBackup
import com.example.simplecontroller.io.encodeControllerProfilesBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compatibility guard for the debug-to-release all-profile backup contract. */
class ProfileBackupFormatTest {
    @Test
    fun versionOne_roundTripsEveryProfileFamily() {
        val control = Control(
            id = "fire",
            type = ControlType.BUTTON,
            x = 10f,
            y = 20f,
            w = 100f,
            h = 100f,
            payload = "X360A"
        )
        val page = ControllerPage(
            id = "base",
            name = "Base",
            controls = listOf(control),
            portraitGeometry = capturePageGeometry(listOf(control), 1000f, 2000f),
            landscapeGeometry = capturePageGeometry(
                listOf(control.copy(x = 700f, y = 300f)), 2000f, 1000f
            )
        )
        val backup = ControllerProfilesBackup(
            activeControllerProfileName = "Controller",
            controllerProfiles = listOf(
                NamedControllerProfile(
                    "Controller",
                    ControllerProfile(homePageId = page.id, pages = listOf(page))
                )
            ),
            touchAimCalibrations = listOf(calibration()),
            touchAimManualProfiles = listOf(manualProfile()),
            buttonAimProfiles = listOf(buttonAimProfile()),
            stickDirectionalProfiles = listOf(stickProfile())
        )

        val decoded = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup)).backup

        assertEquals(backup, decoded)
        assertEquals(1, decoded.backupVersion)
        assertEquals(
            700f,
            decoded.controllerProfiles.single().profile.pages.single()
                .landscapeGeometry!!.controls.getValue("fire").x,
            0.001f
        )
    }

    @Test
    fun futureBackupVersion_isRejectedBeforeNestedProfilesAreDecoded() {
        val future = """
            {
              "fileType":"simplecontroller-profiles-backup",
              "backupVersion":999,
              "activeControllerProfileName":"Future",
              "controllerProfiles":[{"name":"Future","profile":{"futureShape":true}}]
            }
        """.trimIndent()

        assertTrue(
            runCatching { decodeControllerProfilesBackup(future) }
                .exceptionOrNull()?.message.orEmpty().contains("newer")
        )
    }

    @Test
    fun versionOneBackup_withMissingNestedFormat_migratesAsFormatTwo() {
        val legacy = """
            {
              "fileType":"simplecontroller-profiles-backup",
              "backupVersion":1,
              "activeControllerProfileName":"Legacy",
              "controllerProfiles":[{
                "name":"Legacy",
                "profile":{
                  "homePageId":"base",
                  "pages":[{"id":"base","name":"Base","controls":[{
                    "id":"a","type":"BUTTON","x":3.0,"y":4.0,
                    "w":100.0,"h":100.0,"payload":"X360A"
                  }]}]
                }
              }]
            }
        """.trimIndent()

        val profile = decodeControllerProfilesBackup(legacy).backup
            .controllerProfiles.single().profile

        assertEquals(3, profile.formatVersion)
        assertEquals(3f, profile.pages.single().portraitGeometry!!
            .controls.getValue("a").x, 0.001f)
    }

    private fun calibration() = TouchAimCalibrationProfile(
        id = "00000000-0000-0000-0000-000000000001",
        name = "Calibration",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        sensors = emptyList(),
        smoothing = .25f,
        shootOnThreshold = 1f,
        shootOffThreshold = .5f,
        enterShootConfirmationMs = 64L,
        returnToAimConfirmationMs = 64L,
        quality = TouchAimCalibrationQuality.UNRELIABLE,
        estimatedFalseActivationRate = 0f,
        estimatedMissedActivationRate = 0f,
        movementReducedReliability = false,
        consistencyScore = 0f,
        passSummaries = emptyList(),
        aimOutput = TouchAimOutput.MOUSE,
        aimPayload = "",
        shootPayload = "RT:1.0",
        keepAimPayloadWhileShooting = false,
        shootBehavior = TouchAimShootBehavior.HOLD_WHILE_ABOVE
    )

    private fun manualProfile() = TouchAimManualProfile(
        id = "00000000-0000-0000-0000-000000000002",
        name = "Manual",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        mode = TouchAimMode.MANUAL_TWO_STATE,
        aimOutput = TouchAimOutput.MOUSE,
        aimSensitivity = 1f,
        useSize = true,
        useMajor = false,
        useMinor = false,
        sizeScale = 1f,
        stickFullSpeed = 220f,
        invertY = false,
        useResponseCurve = false,
        twoState = TouchAimManualTwoStateSettings(
            shootOnThreshold = 1f,
            shootOffThreshold = .5f,
            smoothing = .25f,
            enterShootConfirmationMs = 64L,
            returnToAimConfirmationMs = 64L,
            aimPayload = "",
            shootPayload = "RT:1.0",
            keepAimPayloadWhileShooting = false,
            shootSensitivity = 1f,
            shootBehavior = TouchAimShootBehavior.HOLD_WHILE_ABOVE
        )
    )

    private fun tuning() = ButtonAimTuning(
        output = TouchAimOutput.MOUSE,
        sensitivity = 1f,
        invertY = false,
        stickProfile = ButtonAimStickProfile.LINEAR,
        mouseProfile = ButtonAimMouseProfile.SMOOTHED_NONLINEAR,
        stickFullDisplacementPx = 220f,
        stickDeadzonePx = 8f,
        stickUsesTouchPosition = false,
        haptics = true
    )

    private fun buttonAimProfile() = ButtonAimProfile(
        id = "00000000-0000-0000-0000-000000000003",
        name = "Button Aim",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        sourceControlWidthPx = 100f,
        sourceControlHeightPx = 100f,
        base = tuning(),
        alternate = tuning(),
        actions = ButtonAimActionSettings(
            payload = "X360A",
            holdToggle = false,
            holdDurationMs = 500L,
            payloadTiming = ButtonAimPayloadTiming.IMMEDIATE,
            releaseDelayMs = 0L,
            oneShotAlternateEnabled = false,
            alternatePayload = "",
            alternatePayloadTiming = ButtonAimPayloadTiming.IMMEDIATE,
            alternateResetHoldDurationMs = 2000L,
            alternateBaseUnlatchDelayMs = 0L,
            alternateDisplayName = ""
        )
    )

    private fun stickProfile() = StickDirectionalProfile(
        id = "00000000-0000-0000-0000-000000000004",
        name = "Stick",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        mode = StickDirectionalProfileMode.STICK_PLUS,
        settings = StickDirectionalSettings(
            upCommand = "", downCommand = "", leftCommand = "", rightCommand = "",
            boostThreshold = .5f,
            upBoostCommand = "", downBoostCommand = "", leftBoostCommand = "", rightBoostCommand = "",
            superBoostThreshold = .75f,
            upSuperBoostCommand = "", downSuperBoostCommand = "",
            leftSuperBoostCommand = "", rightSuperBoostCommand = ""
        )
    )
}
