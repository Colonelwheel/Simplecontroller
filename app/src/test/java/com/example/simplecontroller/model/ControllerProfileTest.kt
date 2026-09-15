package com.example.simplecontroller.model

import com.example.simplecontroller.io.decodeControllerProfile
import com.example.simplecontroller.io.deepCopyControls
import com.example.simplecontroller.io.encodeControllerProfile
import com.example.simplecontroller.io.validateControllerProfileForSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerProfileTest {
    @Test
    fun legacyControlArray_loadsAsStableBasePageWithoutLosingSettings() {
        val legacy = """
            [{
              "id":"fire","type":"BUTTON","x":12.0,"y":34.0,"w":140.0,"h":140.0,
              "payload":"RT:1.0","name":"Fire","holdToggle":true,"holdDurationMs":350
            }]
        """.trimIndent()

        val first = decodeControllerProfile(legacy, "My Profile")
        val second = decodeControllerProfile(legacy, "My Profile")

        assertTrue(first.migratedFromSinglePage)
        assertEquals(1, first.profile.pages.size)
        assertEquals("Base", first.profile.pages.single().name)
        assertEquals(first.profile.pages.single().id, second.profile.pages.single().id)
        assertEquals(first.profile.homePageId, first.profile.pages.single().id)
        assertEquals("RT:1.0", first.profile.pages.single().controls.single().payload)
        assertTrue(first.profile.pages.single().controls.single().holdToggle)
        assertEquals(350L, first.profile.pages.single().controls.single().holdDurationMs)
        assertEquals(PageAction.NONE, first.profile.pages.single().controls.single().pageAction)
    }

    @Test
    fun multipageProfile_roundTripsHomeOrderIdsAndCompleteControls() {
        val base = ControllerPage(
            "base-id",
            "Base",
            listOf(button("base-button", "X360A").copy(autoTapEnabled = true))
        )
        val alternate = ControllerPage(
            "alt-id",
            "Alternate",
            listOf(
                button("toggle", "unused").copy(
                    pageAction = PageAction.TOGGLE,
                    pageTargetId = "alt-id"
                )
            )
        )
        val profile = ControllerProfile(homePageId = "alt-id", pages = listOf(base, alternate))

        val decoded = decodeControllerProfile(encodeControllerProfile(profile), "roundtrip").profile

        assertEquals("alt-id", decoded.homePageId)
        assertEquals(listOf("base-id", "alt-id"), decoded.pages.map { it.id })
        assertTrue(decoded.pages.first().controls.single().autoTapEnabled)
        assertEquals(PageAction.TOGGLE, decoded.pages.last().controls.single().pageAction)
        assertEquals("alt-id", decoded.pages.last().controls.single().pageTargetId)
    }

    @Test
    fun duplicateAndImportCopies_haveNoSharedMutableControlState() {
        val source = listOf(button("source", "X360A").copy(
            name = "Original",
            touchAimSensorTransforms = listOf(
                TouchAimSensorTransform(TouchAimSensor.SIZE, 0.5f, 2f)
            )
        ))
        val duplicate = deepCopyControls(source, regenerateIds = true)

        duplicate.single().name = "Changed"
        duplicate.single().payload = "X360B"
        duplicate.single().touchAimSensorTransforms = emptyList()

        assertEquals("Original", source.single().name)
        assertEquals("X360A", source.single().payload)
        assertEquals(1, source.single().touchAimSensorTransforms.size)
        assertNotEquals(source.single().id, duplicate.single().id)
    }

    @Test
    fun renamePreservesIdAndReferencesResolveByStableId() {
        val target = ControllerPage("target-id", "Alternate", emptyList())
        val source = ControllerPage(
            "base-id",
            "Base",
            listOf(button("go", "unused").copy(
                pageAction = PageAction.GO_TO,
                pageTargetId = target.id
            ))
        )
        val renamed = ControllerProfile(homePageId = source.id, pages = listOf(source, target)).copy(
            pages = listOf(source, target.copy(name = "Driving"))
        )

        assertEquals("target-id", renamed.pages.first().controls.single().pageTargetId)
        assertEquals("Driving", renamed.pageName("target-id"))
        assertEquals(1, renamed.referenceCount("target-id"))
    }

    @Test
    fun missingTarget_isRetainedAndWarnedWithoutArbitraryRetargeting() {
        val base = ControllerPage(
            "base-id",
            "Base",
            listOf(button("broken", "unused").copy(
                pageAction = PageAction.GO_TO,
                pageTargetId = "deleted-id"
            ))
        )
        val loaded = decodeControllerProfile(
            encodeControllerProfile(ControllerProfile(homePageId = base.id, pages = listOf(base))),
            "broken"
        )

        assertEquals("deleted-id", loaded.profile.pages.single().controls.single().pageTargetId)
        assertTrue(loaded.warnings.any { it.contains("missing page", ignoreCase = true) })
    }

    @Test
    fun pageTransportCommands_areBlockedButPageUpKeyboardKeyIsNot() {
        assertTrue(isPageTransportCommand("PAGE_RETURN"))
        assertTrue(isPageTransportCommand("player1:OUT:session:2:PAGE_SET:any"))
        assertTrue(isPageTransportCommand("KEY_DOWN:PAGE_TOGGLE"))
        assertTrue(isPageTransportCommand("X360A,PAGE_HOME"))
        assertTrue(isPageTransportCommand("STICK,PAGE_GO_TO:0.1,0.2"))
        assertFalse(isPageTransportCommand("PAGEUP"))
        assertFalse(isPageTransportCommand("KEY_DOWN:PAGEUP"))
    }

    @Test
    fun emptyLegacyArray_isIdentifiedForDefaultTemplateMigration() {
        val loaded = decodeControllerProfile("[]", "empty")

        assertTrue(loaded.migratedFromSinglePage)
        assertTrue(loaded.profile.pages.single().controls.isEmpty())
    }

    @Test
    fun futureFormat_isRejectedInsteadOfSilentlyDowngraded() {
        val future = """{"formatVersion":999,"homePageId":"base","pages":[{"id":"base","name":"Base"}]}"""

        val error = runCatching { decodeControllerProfile(future, "future") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("newer"))
    }

    @Test
    fun duplicatePageIds_areRejectedBecauseTargetsAreAmbiguous() {
        val duplicate = """{"formatVersion":2,"homePageId":"same","pages":[{"id":"same","name":"Base"},{"id":"same","name":"Other"}]}"""

        val error = runCatching { decodeControllerProfile(duplicate, "duplicate") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("duplicate page ID"))
    }

    @Test
    fun saveValidationRejectsInvalidHomeDuplicateNamesAndMalformedActions() {
        val base = ControllerPage("base", "Base", emptyList())
        assertTrue(validateControllerProfileForSave(
            ControllerProfile(homePageId = "missing", pages = listOf(base))
        )!!.contains("Home"))
        assertTrue(validateControllerProfileForSave(
            ControllerProfile(homePageId = "base", pages = listOf(base, ControllerPage("two", " base ")))
        )!!.contains("names"))
        assertTrue(validateControllerProfileForSave(
            ControllerProfile(
                homePageId = "base",
                pages = listOf(base.copy(controls = listOf(button("go", "unused").copy(
                    pageAction = PageAction.GO_TO,
                    pageTargetId = ""
                ))))
            )
        )!!.contains("target ID"))
    }

    @Test
    fun malformedLoadedActions_areNormalizedToASavableProfileWithWarnings() {
        val malformed = """
            {"formatVersion":2,"homePageId":"base","pages":[
              {"id":"base","name":"Base","controls":[
                {"id":"stick","type":"STICK","x":0.0,"y":0.0,"w":100.0,"h":100.0,"payload":"STICK_L","pageAction":"HOME"},
                {"id":"go","type":"BUTTON","x":0.0,"y":0.0,"w":100.0,"h":100.0,"payload":"unused","pageAction":"GO_TO","pageTargetId":""}
              ]}
            ]}
        """.trimIndent()

        val loaded = decodeControllerProfile(malformed, "malformed")

        assertEquals(listOf(PageAction.NONE, PageAction.NONE),
            loaded.profile.pages.single().controls.map { it.pageAction })
        assertEquals(null, validateControllerProfileForSave(loaded.profile))
        assertTrue(loaded.warnings.any { it.contains("disabled") })
    }

    @Test
    fun singleBlankPageId_repairsHomeAndTargetReferencesTogether() {
        val recoverable = """
            {"formatVersion":2,"homePageId":"","pages":[
              {"id":"base","name":"Base","controls":[
                {"id":"toggle","type":"BUTTON","x":0.0,"y":0.0,"w":100.0,"h":100.0,"payload":"unused","pageAction":"TOGGLE","pageTargetId":""}
              ]},
              {"id":"","name":"Alternate","controls":[]}
            ]}
        """.trimIndent()

        val loaded = decodeControllerProfile(recoverable, "recoverable").profile
        val recoveredId = loaded.pages.last().id

        assertEquals(recoveredId, loaded.homePageId)
        assertEquals(recoveredId, loaded.pages.first().controls.single().pageTargetId)
        assertEquals(null, validateControllerProfileForSave(loaded))
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
