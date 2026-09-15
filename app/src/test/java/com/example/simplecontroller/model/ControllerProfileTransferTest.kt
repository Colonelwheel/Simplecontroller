package com.example.simplecontroller.model

import com.example.simplecontroller.io.CONTROLLER_PROFILE_TRANSFER_FILE_TYPE
import com.example.simplecontroller.io.CONTROLLER_PROFILE_TRANSFER_VERSION
import com.example.simplecontroller.io.decodeControllerProfileTransfer
import com.example.simplecontroller.io.encodeControllerProfile
import com.example.simplecontroller.io.encodeControllerProfileTransfer
import com.example.simplecontroller.io.readControllerProfileTransferText
import com.example.simplecontroller.io.uniqueImportedProfileName
import com.example.simplecontroller.io.validateImportedProfileName
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerProfileTransferTest {
    @Test
    fun transferEnvelope_roundTripsCompleteMultipageProfileWithoutChangingIdsOrTargets() {
        val base = ControllerPage(
            id = "base-id",
            name = "Base",
            controls = listOf(
                button("to-alt", "unused").copy(
                    pageAction = PageAction.GO_TO,
                    pageTargetId = "alternate-id"
                ),
                button("fire", "RT:1.0").copy(
                    name = "Fire",
                    autoTapEnabled = true,
                    autoTapIntervalMs = 72L
                )
            )
        )
        val alternate = ControllerPage(
            id = "alternate-id",
            name = "Alternate",
            controls = listOf(
                button("to-base", "unused").copy(
                    pageAction = PageAction.TOGGLE,
                    pageTargetId = "base-id"
                )
            )
        )
        val profile = ControllerProfile(
            homePageId = base.id,
            pages = listOf(base, alternate)
        )

        val imported = decodeControllerProfileTransfer(
            encodeControllerProfileTransfer("My Profile", profile),
            "fallback"
        )

        assertEquals("My Profile", imported.suggestedName)
        assertEquals(profile, imported.result.profile)
        assertEquals(listOf("base-id", "alternate-id"), imported.result.profile.pages.map { it.id })
        assertEquals(
            "alternate-id",
            imported.result.profile.pages.first().controls.first().pageTargetId
        )
        assertEquals("to-alt", imported.result.profile.pages.first().controls.first().id)
    }

    @Test
    fun import_acceptsBareCurrentProfileAndLegacyControlArray() {
        val page = ControllerPage("base", "Base", listOf(button("a", "X360A")))
        val profile = ControllerProfile(homePageId = page.id, pages = listOf(page))

        val current = decodeControllerProfileTransfer(
            encodeControllerProfile(profile),
            "Current File.json"
        )
        val legacy = decodeControllerProfileTransfer(
            """[{"id":"old","type":"BUTTON","x":1.0,"y":2.0,"w":3.0,"h":4.0,"payload":"X360B"}]""",
            "Legacy File.json"
        )

        assertEquals(profile, current.result.profile)
        assertEquals("Current File", current.suggestedName)
        assertTrue(legacy.result.migratedFromSinglePage)
        assertEquals("Base", legacy.result.profile.pages.single().name)
        assertEquals("X360B", legacy.result.profile.pages.single().controls.single().payload)
    }

    @Test
    fun import_acceptsUtf8BomAndUnknownOptionalEnvelopeFields() {
        val page = ControllerPage("base", "Base", emptyList())
        val profile = ControllerProfile(homePageId = page.id, pages = listOf(page))
        val encoded = encodeControllerProfileTransfer("BOM", profile)
            .replaceFirst("{", "{\n  \"futureOptionalNote\": \"ignored\",")

        val imported = decodeControllerProfileTransfer("\uFEFF$encoded", "fallback")

        assertEquals(profile, imported.result.profile)
    }

    @Test
    fun futureVersions_areRejectedBeforeUnknownControlShapesAreDecoded() {
        val futureTransfer = """
            {
              "fileType":"$CONTROLLER_PROFILE_TRANSFER_FILE_TYPE",
              "transferVersion":${CONTROLLER_PROFILE_TRANSFER_VERSION + 1},
              "profile":{"notYetKnown":true}
            }
        """.trimIndent()
        val futureProfile = """
            {
              "fileType":"$CONTROLLER_PROFILE_TRANSFER_FILE_TYPE",
              "transferVersion":$CONTROLLER_PROFILE_TRANSFER_VERSION,
              "profileName":"Future",
              "profile":{
                "formatVersion":999,
                "homePageId":"base",
                "pages":[{"id":"base","name":"Base","controls":[{
                  "id":"future","type":"FUTURE_CONTROL","x":0.0,"y":0.0,
                  "w":1.0,"h":1.0,"payload":"unused"
                }]}]
              }
            }
        """.trimIndent()

        val transferError = runCatching {
            decodeControllerProfileTransfer(futureTransfer, "future")
        }.exceptionOrNull()
        val profileError = runCatching {
            decodeControllerProfileTransfer(futureProfile, "future")
        }.exceptionOrNull()

        assertTrue(transferError?.message.orEmpty().contains("newer"))
        assertTrue(profileError?.message.orEmpty().contains("newer"))
    }

    @Test
    fun wrongFileTypeAndMalformedObject_areRejected() {
        val wrongType = """{"fileType":"another-app","transferVersion":1,"profile":{}}"""
        val unrelated = """{"hello":"world"}"""

        assertTrue(runCatching {
            decodeControllerProfileTransfer(wrongType, "wrong")
        }.exceptionOrNull()?.message.orEmpty().contains("Unsupported"))
        assertTrue(runCatching {
            decodeControllerProfileTransfer(unrelated, "wrong")
        }.exceptionOrNull()?.message.orEmpty().contains("not a SimpleController"))
    }

    @Test
    fun boundedUtf8Reader_acceptsLimitRejectsOverflowAndRejectsInvalidUtf8() {
        assertEquals(
            "1234",
            readControllerProfileTransferText(ByteArrayInputStream("1234".toByteArray()), 4)
        )
        assertTrue(runCatching {
            readControllerProfileTransferText(ByteArrayInputStream("12345".toByteArray()), 4)
        }.isFailure)
        assertTrue(
            runCatching {
                readControllerProfileTransferText(
                    ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)),
                    4
                )
            }.exceptionOrNull() is CharacterCodingException
        )
    }

    @Test
    fun boundedReader_handlesAZeroLengthBulkReadWithoutLooping() {
        val delegate = ByteArrayInputStream("ok".toByteArray(StandardCharsets.UTF_8))
        val stream = object : InputStream() {
            var firstBulkRead = true
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (firstBulkRead) {
                    firstBulkRead = false
                    return 0
                }
                return delegate.read(buffer, offset, length)
            }
        }

        assertEquals("ok", readControllerProfileTransferText(stream, 4))
    }

    @Test
    fun importedNames_areSanitizedUniqueAndNeverSilentlyOverwrite() {
        assertEquals(
            "Action (Imported 2)",
            uniqueImportedProfileName(
                "Action.simplecontroller-profile.json",
                listOf("action", "ACTION (IMPORTED)")
            )
        )
        assertTrue(validateImportedProfileName("bad/name") != null)
        assertTrue(validateImportedProfileName(" ") != null)
        assertEquals(null, validateImportedProfileName("Accessible Profile"))
    }

    @Test
    fun goldenDebugExport_remainsReadableByFutureReleaseDecoder() {
        val text = requireNotNull(
            javaClass.getResourceAsStream("/golden/debug-profile-transfer-v1.json")
        ).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        val imported = decodeControllerProfileTransfer(text, "fallback")

        assertEquals("Debug Migration Golden", imported.suggestedName)
        assertEquals("golden-base", imported.result.profile.homePageId)
        assertEquals(listOf("golden-base", "golden-alternate"), imported.result.profile.pages.map { it.id })
        assertEquals(
            "golden-alternate",
            imported.result.profile.pages.first().controls.single().pageTargetId
        )
    }

    private fun button(id: String, payload: String) = Control(
        id = id,
        type = ControlType.BUTTON,
        x = 0f,
        y = 0f,
        w = 100f,
        h = 100f,
        payload = payload
    )
}
