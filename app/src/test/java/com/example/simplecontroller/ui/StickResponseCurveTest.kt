package com.example.simplecontroller.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StickResponseCurveTest {
    @Test
    fun sensitivityOne_isLinear() {
        assertEquals(0.5f, StickResponseCurve.apply(0.5f, 1f), 0.0001f)
        assertEquals(-0.5f, StickResponseCurve.apply(-0.5f, 1f), 0.0001f)
    }

    @Test
    fun lowerSensitivity_softensCenterButPreservesFullTilt() {
        assertEquals(0.25f, StickResponseCurve.apply(0.5f, 0.5f), 0.0001f)
        assertEquals(1f, StickResponseCurve.apply(1f, 0.5f), 0.0001f)
    }

    @Test
    fun higherSensitivityBoostsCenterButPreservesFullTilt() {
        assertEquals(0.7071f, StickResponseCurve.apply(0.5f, 2f), 0.0001f)
        assertEquals(-1f, StickResponseCurve.apply(-1f, 2f), 0.0001f)
    }

    @Test
    fun zeroSensitivity_disablesOutput() {
        assertEquals(0f, StickResponseCurve.apply(1f, 0f), 0f)
    }
}
