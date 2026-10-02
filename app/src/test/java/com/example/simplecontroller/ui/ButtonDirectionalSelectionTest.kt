package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonDirectionStages
import com.example.simplecontroller.model.ButtonDirectionalSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonDirectionalSelectionTest {
    private val settings = ButtonDirectionalSettings(
        enabled = true,
        up = ButtonDirectionStages(80f, "X360Y", 160f, "X360RB"),
        right = ButtonDirectionStages(40f, "X360A", 90f, "X360B")
    )

    @Test
    fun physicalTravel_entersCumulativeStagesAndReleasesOnRetreat() {
        val first = selectButtonDirection(45f, 0f, settings)
        val second = selectButtonDirection(100f, 0f, settings, first)
        val retreat = selectButtonDirection(60f, 0f, settings, second)
        val center = selectButtonDirection(20f, 0f, settings, retreat)

        assertEquals(ButtonDirectionalSelection(ButtonDirection.RIGHT, 1), first)
        assertEquals(ButtonDirectionalSelection(ButtonDirection.RIGHT, 2), second)
        assertEquals(first, retreat)
        assertEquals(ButtonDirectionalSelection(), center)
    }

    @Test
    fun directionChange_waitsForDominantAxisAndUsesItsOwnDistances() {
        val right = selectButtonDirection(45f, 0f, settings)
        assertEquals(right, selectButtonDirection(50f, -52f, settings, right))
        assertEquals(
            ButtonDirectionalSelection(ButtonDirection.UP, 1),
            selectButtonDirection(40f, -85f, settings, right)
        )
    }
}
