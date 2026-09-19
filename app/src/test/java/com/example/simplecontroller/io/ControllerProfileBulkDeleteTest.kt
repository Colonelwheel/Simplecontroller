package com.example.simplecontroller.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerProfileBulkDeleteTest {
    @Test
    fun candidates_excludeActiveProfileIgnoringCase() {
        assertEquals(
            listOf("Favorite", "Experimental"),
            controllerProfileDeleteCandidates(
                savedNames = listOf("Favorite", "CURRENT", "Experimental"),
                activeName = "current"
            )
        )
    }

    @Test
    fun candidates_removeCaseInsensitiveDuplicatesWithoutReordering() {
        assertEquals(
            listOf("First", "Second"),
            controllerProfileDeleteCandidates(
                savedNames = listOf("First", "first", "Second"),
                activeName = null
            )
        )
    }

    @Test
    fun deleteSelected_deletesOnlyTheExplicitSelection() {
        val calls = mutableListOf<String>()

        val result = deleteSelectedControllerProfiles(
            selectedNames = listOf("Old 1", "Old 2"),
            activeName = "Favorite",
            deleteOne = { name -> true.also { calls += name } }
        )

        assertEquals(listOf("Old 1", "Old 2"), calls)
        assertEquals(listOf("Old 1", "Old 2"), result.deletedNames)
        assertTrue(result.failedNames.isEmpty())
        assertTrue(result.protectedNames.isEmpty())
    }

    @Test
    fun deleteSelected_rechecksAndProtectsActiveProfile() {
        val calls = mutableListOf<String>()

        val result = deleteSelectedControllerProfiles(
            selectedNames = listOf("Keep", "Delete"),
            activeName = "keep",
            deleteOne = { name -> true.also { calls += name } }
        )

        assertEquals(listOf("Delete"), calls)
        assertEquals(listOf("Delete"), result.deletedNames)
        assertEquals(listOf("Keep"), result.protectedNames)
    }

    @Test
    fun deleteSelected_reportsPartialFailuresAndContinues() {
        val result = deleteSelectedControllerProfiles(
            selectedNames = listOf("One", "Two", "Three"),
            activeName = null,
            deleteOne = { it != "Two" }
        )

        assertEquals(listOf("One", "Three"), result.deletedNames)
        assertEquals(listOf("Two"), result.failedNames)
    }

    @Test
    fun deleteSelected_emptySelectionDoesNotInvokeStorage() {
        var called = false

        val result = deleteSelectedControllerProfiles(emptyList(), null) {
            called = true
            true
        }

        assertTrue(result.deletedNames.isEmpty())
        assertTrue(result.failedNames.isEmpty())
        assertTrue(result.protectedNames.isEmpty())
        assertEquals(false, called)
    }
}
