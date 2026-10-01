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

    @Test
    fun radialCurve_preservesDiagonalDirectionAtLowSensitivity() {
        val (x, y) = StickResponseCurve.applyRadial(0.4f, 0.3f, 0.2f)
        assertEquals(0.025f, x, 0.0001f)
        assertEquals(0.01875f, y, 0.0001f)
        assertEquals(4f / 3f, x / y, 0.0001f)
    }

    @Test
    fun radialCurve_clampsCornerToFullCircularTilt() {
        val (x, y) = StickResponseCurve.applyRadial(1f, 1f, 0.2f)
        assertEquals(0.7071f, x, 0.0001f)
        assertEquals(0.7071f, y, 0.0001f)
    }

    @Test
    fun radialCurve_keepsLinearSensitivityAndZeroOutput() {
        val (linearX, linearY) = StickResponseCurve.applyRadial(-0.4f, 0.3f, 1f)
        assertEquals(-0.4f, linearX, 0.0001f)
        assertEquals(0.3f, linearY, 0.0001f)
        assertEquals(0f to 0f, StickResponseCurve.applyRadial(1f, 0f, 0f))
    }
}
