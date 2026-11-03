package com.example.simplecontroller.ui

import com.example.simplecontroller.model.FloatingRingConfig
import com.example.simplecontroller.model.RingBoostStage
import org.junit.Assert.*
import org.junit.Test

class FloatingRingGeometryTest {
    @Test fun compactAimDistancesUseSavedAimAndMovementRadiiWithoutChangingPlacementOrDirectBands() {
        val normal = FloatingRingGeometry(FloatingRingConfig(), 2f)
        val compact = FloatingRingGeometry(normal.config.copy(compactAimBoosts = true), 2f)
        assertEquals(96f, compact.boostDistance(RingBoostStage.ONE, true), 0f)
        assertEquals(176f, compact.boostDistance(RingBoostStage.TWO, true), 0f)
        assertEquals(240f, compact.boostDistance(RingBoostStage.ONE), 0f)
        assertEquals(320f, compact.boostDistance(RingBoostStage.TWO), 0f)
        assertEquals(240f, normal.boostDistance(RingBoostStage.ONE, true), 0f)
        assertEquals(normal.width, compact.width, 0f)
        assertEquals(normal.height, compact.height, 0f)
        assertEquals(normal.center, compact.center)
        assertEquals(normal.finish, compact.finish)
        assertEquals(normal.config.boost1, compact.config.boost1)
        assertEquals(normal.config.boost2, compact.config.boost2)
    }

    @Test fun compactReachabilityMatchesEffectiveThresholdAndHysteresisRemainsFinite() {
        val base = FloatingRingConfig(compactAimBoosts = true)
        val geometry = FloatingRingGeometry(base.copy(
            boost1 = base.boost1.copy(enabled = true, hysteresisDp = 110f),
            boost2 = base.boost2.copy(enabled = true, hysteresisDp = 150f)))
        val canvas = RingRect(geometry.center.x - 90f, geometry.center.y - 90f,
            geometry.center.x + 90f, geometry.center.y + 90f)
        assertEquals(setOf("Up", "Down", "Left", "Right"),
            geometry.unreachableDirections(RingBoostStage.TWO, RingPoint(0f, 0f), canvas))
        assertTrue(geometry.unreachableDirections(RingBoostStage.TWO, RingPoint(0f, 0f), canvas, true).isEmpty())
        assertEquals(24f, geometry.boostHysteresis(RingBoostStage.ONE, true), 0f)
        assertEquals(20f, geometry.boostHysteresis(RingBoostStage.TWO, true), 0f)
        assertEquals(110f, geometry.boostHysteresis(RingBoostStage.ONE), 0f)
    }

    @Test fun adjacentMovementUsesMovementEdgeAndPreservesSavedBoostBandAtAnyDensity() {
        val config = FloatingRingConfig(adjacentMovementBoosts = true, compactAimBoosts = true)
        val geometry = FloatingRingGeometry(config, 2f)
        assertEquals(176f, geometry.boostDistance(RingBoostStage.ONE, adjacentMovement = true), 0f)
        assertEquals(256f, geometry.boostDistance(RingBoostStage.TWO, adjacentMovement = true), 0f)
        assertEquals(96f, geometry.boostDistance(RingBoostStage.ONE, compactAim = true), 0f)
        assertEquals(176f, geometry.boostDistance(RingBoostStage.TWO, compactAim = true), 0f)
        assertEquals(320f, geometry.boostDistance(RingBoostStage.TWO), 0f)
        assertEquals(4f, geometry.boostHysteresis(RingBoostStage.ONE, adjacentMovement = true), 0f)
    }
}
