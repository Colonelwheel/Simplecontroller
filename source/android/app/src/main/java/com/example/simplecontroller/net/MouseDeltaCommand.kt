package com.example.simplecontroller.net

import java.util.Locale

/** The opt-in PC path never leaks an unsupported GAME_DELTA command to ConsoleBridge. */
internal fun mouseDeltaCommand(dx: Float, dy: Float, gameMouse: Boolean, useCbv0: Boolean): String =
    if (gameMouse && !useCbv0) {
        "GAME_DELTA:${"%.3f".format(Locale.US, dx)},${"%.3f".format(Locale.US, dy)}"
    } else {
        // Preserve the existing DELTA representation for all legacy and Pico callers.
        "DELTA:${"%.3f".format(dx)},${"%.3f".format(dy)}"
    }
