package com.example.simplecontroller.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlTuningInputsTest {
    @Test fun sensitivityKeepsThousandthsAndZero() {
        assertEquals(0.001f, parseSensitivityInput("0.001", 1f), 0f)
        assertEquals(0.137f, parseSensitivityInput("0.137", 1f), 0f)
        assertEquals(0f, parseSensitivityInput("0", 1f), 0f)
        assertEquals(5f, parseSensitivityInput("5", 1f), 0f)
    }

    @Test fun invalidSensitivityPreservesPreviousValue() {
        listOf("", "NaN", "Infinity", "1e40", "-0.1", "5.001", "words").forEach {
            assertEquals(0.123f, parseSensitivityInput(it, 0.123f), 0f)
        }
    }

    @Test fun blankStageSensitivityRestoresInheritance() {
        assertNull(parseOptionalSensitivityInput(" ", 0.123f))
        assertEquals(0.001f, parseOptionalSensitivityInput("0.001", null)!!, 0f)
        assertEquals(0.123f, parseOptionalSensitivityInput("NaN", 0.123f)!!, 0f)
    }

    @Test fun optionalConfirmationSupportsImmediateAndWholeMilliseconds() {
        assertEquals(0L, parseStageConfirmationInput("", 100L))
        assertEquals(0L, parseStageConfirmationInput("0", 100L))
        assertEquals(125L, parseStageConfirmationInput("125", 100L))
        assertEquals(600_000L, parseStageConfirmationInput("600000", 100L))
        listOf("-1", "1.5", "600001", "9223372036854775808", "words").forEach {
            assertEquals(100L, parseStageConfirmationInput(it, 100L))
        }
    }
}
