package com.example.simplecontroller.ui

import com.example.simplecontroller.model.*
import org.junit.Assert.*
import org.junit.Test

class FloatingBoostBandGeometryTest {
    private fun compact(start: FloatingStartRole = FloatingStartRole.AIM) = FloatingRingConfig(
        innerRadiusDp = 24f, movementRadiusDp = 32f, startRole = start,
        compactAimBoosts = true, adjacentMovementBoosts = true,
        boost1 = RingBoostSettings(enabled = true, directTapEnabled = true),
        boost2 = RingBoostSettings(enabled = true, distanceDp = 160f,
            directInnerDistanceDp = 160f, directOuterDistanceDp = 184f, directTapEnabled = true)
    )

    @Test fun compactDirectBandsFollowMovementEdgeAndSavedStartingRole() {
        val aim = FloatingRingGeometry(compact())
        assertEquals(RingBand(32f, 40f), aim.directBand(RingBoostStage.ONE))
        assertEquals(RingBand(40f, 64f), aim.directBand(RingBoostStage.TWO))
        val move = FloatingRingGeometry(compact(FloatingStartRole.MOVEMENT))
        assertEquals(RingBand(32f, 72f), move.directBand(RingBoostStage.ONE))
        assertEquals(RingBand(72f, 96f), move.directBand(RingBoostStage.TWO))
        assertEquals(24f, aim.boostDistance(RingBoostStage.ONE, bank = RingBoostBank.AIM), 0f)
        assertEquals(32f, aim.boostDistance(RingBoostStage.TWO, bank = RingBoostBank.AIM), 0f)
    }

    @Test fun compactDirectHitTestingKeepsAimAndMovementTouchPriority() {
        val g = FloatingRingGeometry(compact())
        assertEquals(FloatingStartRole.AIM, g.roleAt(g.center.x, g.center.y))
        assertEquals(FloatingStartRole.MOVEMENT, g.roleAt(g.center.x + 32f, g.center.y))
        assertEquals(FloatingStartRole.BOOST_ONE, g.roleAt(g.center.x + 32.1f, g.center.y))
        assertEquals(FloatingStartRole.BOOST_TWO, g.roleAt(g.center.x + 40f, g.center.y))
        assertNull(g.roleAt(g.center.x + 65f, g.center.y))
    }

    @Test fun automaticDirectFixAlsoWorksWhenOnlyMovementCompactionIsEnabled() {
        val base = compact().copy(compactAimBoosts = false)
        val g = FloatingRingGeometry(base)
        assertEquals(RingBand(32f, 72f), g.directBand(RingBoostStage.ONE))
        assertEquals(RingBand(72f, 96f), g.directBand(RingBoostStage.TWO))
        assertEquals(120f, g.boostDistance(RingBoostStage.ONE, bank = RingBoostBank.AIM), 0f)
    }

    @Test fun noncompactLegacyDirectBandsAndVisibleFitRemainUnchanged() {
        val legacy = compact().copy(compactAimBoosts = false, adjacentMovementBoosts = false)
        val normal = FloatingRingGeometry(legacy)
        assertEquals(RingBand(120f, 144f), normal.directBand(RingBoostStage.ONE))
        assertEquals(RingBand(160f, 184f), normal.directBand(RingBoostStage.TWO))
        val compact = FloatingRingGeometry(compact())
        assertEquals(normal.width, compact.width, 0f)
        assertEquals(normal.height, compact.height, 0f)
        assertEquals(normal.center, compact.center)
        assertEquals(normal.finish, compact.finish)
    }

    @Test fun customBankGeometryWorksIndependentlyOfSeparateActionsAndPreservesOtherBank() {
        val source = compact()
        val seeded = source.seedBoostBanks()
        val c = source.copy(boostBanks = seeded.copy(enabled = false,
            aim = seeded.aim.copy(geometry = RingBoostBandLayout(enabled = true,
                boost1DistanceDp = 26f, boost1ThicknessDp = 6f,
                boost2ThicknessDp = 9f, directStartDistanceDp = 34f))))
        assertNull(c.validationError())
        val g = FloatingRingGeometry(c, 2f)
        assertEquals(52f, g.boostDistance(RingBoostStage.ONE, bank = RingBoostBank.AIM), 0f)
        assertEquals(64f, g.boostDistance(RingBoostStage.TWO, bank = RingBoostBank.AIM), 0f)
        assertEquals(RingBand(68f, 80f), g.directBand(RingBoostStage.ONE))
        assertEquals(RingBand(80f, 98f), g.directBand(RingBoostStage.TWO))
        assertEquals(64f, g.boostDistance(RingBoostStage.ONE, bank = RingBoostBank.MOVEMENT), 0f)
        assertEquals(144f, g.boostDistance(RingBoostStage.TWO, bank = RingBoostBank.MOVEMENT), 0f)
        assertEquals(source.boost1, c.boost(RingBoostStage.ONE, RingBoostBank.AIM))
        assertEquals(120f, c.boost1.distanceDp, 0f)
    }

    @Test fun directAnchorsDensityAndReachabilityUseTheSameChosenBand() {
        val source = compact()
        val seeded = source.seedBoostBanks()
        val c = source.copy(boostBanks = seeded.copy(
            aim = seeded.aim.copy(geometry = RingBoostBandLayout(enabled = true,
                boost1DistanceDp = 26f, boost1ThicknessDp = 6f,
                boost2ThicknessDp = 9f, directStartDistanceDp = 34f))))
        val g = FloatingRingGeometry(c, 2f)
        listOf(FloatingStartRole.BOOST_ONE, FloatingStartRole.BOOST_TWO).forEach { role ->
            val stage = if (role == FloatingStartRole.BOOST_ONE) RingBoostStage.ONE else RingBoostStage.TWO
            val anchor = g.startAnchor(role)
            assertTrue(g.directHit(stage, anchor.x, anchor.y))
            val band = g.directBand(stage)
            assertEquals((band.innerRadius + band.outerRadius) / 2f, g.distance(anchor.x, anchor.y), .00001f)
        }
        val canvas = RingRect(g.center.x - 60f, g.center.y - 60f, g.center.x + 60f, g.center.y + 60f)
        assertTrue(g.unreachableDirections(RingBoostStage.ONE, RingPoint(0f, 0f), canvas,
            bank = RingBoostBank.AIM).isEmpty())
        assertEquals(setOf("Up", "Down", "Left", "Right"),
            g.unreachableDirections(RingBoostStage.TWO, RingPoint(0f, 0f), canvas, bank = RingBoostBank.AIM))
    }

    @Test fun largeCustomHysteresisCannotCrossThePrecedingBand() {
        val source = compact()
        val seeded = source.seedBoostBanks()
        val c = source.copy(boostBanks = seeded.copy(enabled = true,
            aim = seeded.aim.copy(boost1 = source.boost1.copy(hysteresisDp = 100f),
                boost2 = source.boost2.copy(hysteresisDp = 150f),
                geometry = RingBoostBandLayout(enabled = true, boost1DistanceDp = 26f,
                    boost1ThicknessDp = 6f, boost2ThicknessDp = 9f, directStartDistanceDp = 34f))))
        assertNull(c.validationError())
        val g = FloatingRingGeometry(c)
        assertEquals(3f, g.boostHysteresis(RingBoostStage.ONE, bank = RingBoostBank.AIM), 0f)
        assertEquals(3f, g.boostHysteresis(RingBoostStage.TWO, bank = RingBoostBank.AIM), 0f)
    }
}
