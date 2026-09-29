package com.astroloop.game.cabinet

import com.astroloop.game.cabinet.patterns.VolleyPattern
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class VolleyPatternTest : ReckoningPatternContract() {

    override fun pattern(): ReckoningPattern = VolleyPattern()

    @Test fun itIsCalledVolley() {
        assertEquals("VOLLEY", VolleyPattern().name)
    }

    @Test fun theFanIsAimedAtWhereTheShipIs() {
        // The pattern's whole question. A volley aimed at the centre of the field would
        // ask nothing at all.
        val p = VolleyPattern()
        val cx = crystalX(m)
        val cy = crystalY(m)
        val target = atan2(shipY - cy, shipX - cx)
        val fan = p.emit(0f, 1f / 120f, 1f, m, shipX, shipY)
        assertEquals(VolleyPattern.FAN_COUNT, fan.size)
        val mean = fan.map { atan2(it.vy, it.vx) }.average().toFloat()
        assertEquals("the fan must straddle the bearing to the ship", target, mean, 0.05f)
    }

    @Test fun aimingTakesTheShortWayAcrossTheSeam() {
        // The ship wraps; the crystal does not move. Aiming with a raw delta would fire
        // at a ship a whole field away when it is in fact adjacent across the seam.
        val p = VolleyPattern()
        val nearLeft = 12f
        val fan = p.emit(0f, 1f / 120f, 1f, m, nearLeft, crystalY(m))
        // The crystal is at x = 540; the short way to x = 12 is leftward (vx < 0).
        assertTrue("must fire toward the near side", fan.all { it.vx < 0f })
    }

    @Test fun subVolleysArriveAtAnUnbrokenCadence() {
        // A design decision: the rest between bursts is gone. This used to require a gap over
        // 2s; it now requires that no such gap exists. Inverted rather than deleted so a
        // re-added rest gate fails here.
        val e = emissions(1f)
        val gaps = (1 until e.size).map { e[it].first - e[it - 1].first }
        assertTrue(
            "every gap must be one sub-interval; longest was ${gaps.max()}s",
            gaps.all { abs(it - VolleyPattern.SUB_INTERVAL) < 0.05f }
        )
    }

    @Test fun theFanGapClearsTheFloorAtEngagementRange() {
        // The ANGULAR half of rule 1, which the shared contract does not see.
        val spread = VolleyPattern.FAN_SPREAD * ReckoningFairness.engagementRange(m)
        val gap = spread / (VolleyPattern.FAN_COUNT - 1)
        assertTrue(
            "fan gap ${gap}px must clear ${ReckoningFairness.spacingFloor(m)}px",
            gap >= ReckoningFairness.spacingFloor(m)
        )
    }

    @Test fun theBindingTermSwitchesFromGeometryToScheduleAsItTightens() {
        // Lap 1 is limited by the fan's own width (geometry, never tightens); lap 4 by
        // the sub-volley interval (schedule, tightens). Only the second can carry rule 5,
        // so a pattern whose binding term stayed geometric could never terminate.
        val p = VolleyPattern()
        val fanGap = VolleyPattern.FAN_SPREAD * ReckoningFairness.engagementRange(m) /
            (VolleyPattern.FAN_COUNT - 1)
        assertEquals(fanGap, p.minSpacing(m, 1f), 0.5f)
        assertTrue(p.minSpacing(m, 0.421875f) < fanGap)
    }
}
