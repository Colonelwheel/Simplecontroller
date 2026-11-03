package com.example.simplecontroller.net

import com.example.simplecontroller.CbProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MouseDeltaCommandTest {
    @Test
    fun legacyMouse_retainsOriginalCommand() {
        assertEquals(
            "DELTA:${"%.3f".format(1.25f)},${"%.3f".format(-2.5f)}",
            mouseDeltaCommand(1.25f, -2.5f, gameMouse = false, useCbv0 = false)
        )
    }

    @Test
    fun gameMouse_directPc_usesDistinctRelativeMovementCommand() {
        assertEquals("GAME_DELTA:1.250,-2.500", mouseDeltaCommand(1.25f, -2.5f, true, false))
    }

    @Test
    fun gameMouse_consoleBridge_retainsExistingHidCommandAndFrame() {
        val legacy = mouseDeltaCommand(1.25f, -2.5f, false, true)
        val game = mouseDeltaCommand(1.25f, -2.5f, true, true)
        assertEquals(legacy, game)
        // CBv0 sequence bytes and CRC change with each encoding; compare its type and mouse payload.
        val legacyFrame = requireNotNull(CbProtocol.encode(legacy))
        val gameFrame = requireNotNull(CbProtocol.encode(game))
        assertArrayEquals(legacyFrame.copyOfRange(4, legacyFrame.lastIndex), gameFrame.copyOfRange(4, gameFrame.lastIndex))
    }

    @Test
    fun gameMouse_decimalCommaLocale_keepsUnambiguousPcCoordinates() {
        val prior = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("GAME_DELTA:1.250,-2.500", mouseDeltaCommand(1.25f, -2.5f, true, false))
        } finally {
            Locale.setDefault(prior)
        }
    }
}
