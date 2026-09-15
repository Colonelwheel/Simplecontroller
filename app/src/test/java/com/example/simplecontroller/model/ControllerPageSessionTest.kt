package com.example.simplecontroller.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerPageSessionTest {
    @Test
    fun goToActivePage_isNoOpWithoutCleanupOrRender() {
        val fixture = Fixture()
        val result = fixture.session.perform(PageAction.GO_TO, "base", fixture::cleanup, fixture::render)

        assertFalse(result.changed)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun goToPage_cleansBeforeRenderingAndTracksPrevious() {
        val fixture = Fixture()
        val result = fixture.session.perform(PageAction.GO_TO, "alternate", fixture::cleanup, fixture::render)

        assertTrue(result.changed)
        assertEquals(listOf("cleanup", "render:alternate"), fixture.events)
        assertEquals("base", fixture.session.previousPageId)
        assertEquals("alternate", fixture.session.activePageId)
    }

    @Test
    fun copiedToggle_switchesOutAndBackUsingOnePreviousValue() {
        val fixture = Fixture()
        fixture.session.perform(PageAction.TOGGLE, "alternate", fixture::cleanup, fixture::render)
        fixture.session.perform(PageAction.TOGGLE, "alternate", fixture::cleanup, fixture::render)

        assertEquals("base", fixture.session.activePageId)
        assertEquals("alternate", fixture.session.previousPageId)
    }

    @Test
    fun returnWithoutPrevious_fallsBackToHomeAsSafeNoOp() {
        val fixture = Fixture()
        val result = fixture.session.perform(PageAction.RETURN, "", fixture::cleanup, fixture::render)

        assertFalse(result.changed)
        assertEquals("base", fixture.session.activePageId)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun missingPrevious_afterDeletion_fallsBackToHome() {
        val fixture = Fixture()
        fixture.session.perform(PageAction.GO_TO, "alternate", fixture::cleanup, fixture::render)
        fixture.session.updateProfile("base", listOf("base", "alternate"))
        fixture.session.perform(PageAction.GO_TO, "base", fixture::cleanup, fixture::render)
        fixture.session.updateProfile("base", listOf("base"))

        val result = fixture.session.perform(PageAction.RETURN, "", fixture::cleanup, fixture::render)
        assertFalse(result.changed)
        assertEquals("base", fixture.session.activePageId)
    }

    @Test
    fun missingTarget_warnsAndDoesNotCleanupOrSelectAnotherPage() {
        val fixture = Fixture()
        val result = fixture.session.perform(PageAction.GO_TO, "missing", fixture::cleanup, fixture::render)

        assertFalse(result.changed)
        assertEquals("base", result.toPageId)
        assertTrue(result.warning!!.contains("missing"))
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun safetyReset_returnsHomeAndClearsPrevious() {
        val fixture = Fixture()
        fixture.session.perform(PageAction.GO_TO, "alternate", fixture::cleanup, fixture::render)
        fixture.session.resetToHome()

        assertEquals("base", fixture.session.activePageId)
        assertNull(fixture.session.previousPageId)
    }

    private class Fixture {
        val session = ControllerPageSession("base", listOf("base", "alternate"))
        val events = mutableListOf<String>()
        fun cleanup() { events += "cleanup" }
        fun render(pageId: String) { events += "render:$pageId" }
    }
}
