package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ControlGeometry
import org.junit.Assert.*
import org.junit.Test

class FloatingPlacementTest {
    @Test fun clampedBirthInFinishWaitsForExitThenEntry() {
        val guard=FinishEntryGuard(initiallyInside=true)
        assertFalse(guard.observe(true));assertFalse(guard.observe(true))
        assertFalse(guard.observe(false));assertTrue(guard.observe(true))
    }
    @Test fun edgeFitPreservesLogicalGrabAndSize() {
        val p = placeFloatingBounds(100f, 60f, 50f, 30f, 5f, 6f, ControlGeometry(0f,0f,300f,200f))!!
        assertEquals(0f,p.x,0f); assertEquals(-45f,p.grabOffsetX,0f)
        assertEquals(-24f,p.grabOffsetY,0f)
        assertEquals(5f,p.x + p.grabOffsetX + 50f,0f)
    }
    @Test fun oversizeRejectsInsteadOfShrinking() {
        assertNull(placeFloatingBounds(301f,60f,50f,30f,5f,6f,ControlGeometry(0f,0f,300f,200f)))
    }
    @Test fun insetsAndSpacingRemainIntact() {
        val bounds=floatingBounds(listOf(ControlGeometry(10f,20f,40f,40f),ControlGeometry(70f,20f,40f,40f)))!!
        val p=placeFloatingBounds(bounds.w,bounds.h,20f,20f,280f,180f,ControlGeometry(15f,10f,270f,170f))!!
        assertEquals(185f,p.x,0f); assertEquals(140f,p.y,0f)
        assertEquals(100f,bounds.w,0f)
    }
}
