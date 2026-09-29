package com.astroloop.game.cabinet

import com.astroloop.game.cabinet.patterns.PulsePattern
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class PulsePatternTest : ReckoningPatternContract() {

    override fun pattern(): ReckoningPattern = PulsePattern()

    @Test fun itIsCalledPulse() {
        assertEquals("PULSE", PulsePattern().name)
    }

    @Test fun ringsArriveAtAnUnbrokenCadence() {
        // A design decision retired fairness rule 2. This used to assert the OPPOSITE — a rest
        // of about 2.4s between bursts — on the grounds that without it "there is never a
        // moment to turn and shoot". A device pass disproved the fear it was written
        // against: the owner flew the fight and won on a first attempt, and asked for the
        // pauses to go. Inverted rather than deleted, so re-adding a rest gate fails here.
        val e = emissions(1f)
        val gaps = (1 until e.size).map { e[it].first - e[it - 1].first }
        assertTrue(
            "every gap must be one ring interval; longest was ${gaps.max()}s",
            gaps.all { abs(it - PulsePattern.RING_INTERVAL) < 0.05f }
        )
    }

    @Test fun everyRingIsUnbrokenAndEvenlySpaced() {
        // INVERTED at a device pass. The ring used to carry a rotating three-slot door and
        // this asserted its existence and its rotation. Owner: "the bullet patterns shot by
        // the boss also don't need a gap in them, it's easy enough right now to dodge even
        // without the gap." So the door is gone and the way through is every arc equally.
        //
        // Kept inverted rather than deleted so re-adding a door fails here.
        val rings = emissions(1f).map { it.second }
        assertTrue(rings.size >= 3)
        for (r in rings) {
            assertEquals("a ring fires every slot", PulsePattern.COUNT, r.size)
        }
        val angles = rings[0].map { atan2(it.vy, it.vx) }.sorted()
        val slot = 2f * PI.toFloat() / PulsePattern.COUNT
        for (i in 1 until angles.size) {
            assertEquals("evenly spaced", slot, angles[i] - angles[i - 1], 0.01f)
        }
    }

    @Test fun theArcBetweenNeighboursIsWideEnoughToFlyThrough() {
        // Rule 1's opening for PULSE is now the arc between two bullets, because that is
        // the only way through there is. It must still clear the floor, or the ring has
        // become a wall with no door AND no seams.
        val r = ReckoningFairness.engagementRange(m)
        val arc = 2f * PI.toFloat() * r / PulsePattern.COUNT
        assertTrue(
            "arc ${arc}px must clear the ${ReckoningFairness.spacingFloor(m)}px floor",
            arc >= ReckoningFairness.spacingFloor(m)
        )
    }

    @Test fun theBindingOpeningOnLapOneIsTheRadialOne() {
        // 259.2px between rings against a 678.6px hole. It matters WHICH term binds:
        // the hole is geometry and never tightens, the radial gap is an interval and
        // does — so only the radial term can carry fairness rule 5.
        val p = PulsePattern()
        val radial = PulsePattern.SPEED_FRAC * m.minEdge * PulsePattern.RING_INTERVAL
        assertEquals(radial, p.minSpacing(m, 1f), 0.5f)
        assertEquals(259.2f, p.minSpacing(m, 1f), 0.5f)
    }

    @Test fun ringsExpandOutwardFromTheCrystal() {
        val cx = m.width / 2f
        val cy = m.height / 2f
        for ((_, bs) in emissions(1f)) {
            for (b in bs) {
                // Velocity points away from centre, and the bullet starts on the
                // crystal's surface rather than inside it.
                val r = hypot(b.x - cx, b.y - cy)
                assertEquals(CabinetCrystal.RADIUS_FRAC * m.minEdge, r, 1f)
                val dot = (b.x - cx) * b.vx + (b.y - cy) * b.vy
                assertTrue("rings expand; this one is heading inward", dot > 0f)
            }
        }
    }

    @Test fun tighteningBringsTheRingsCloserTogether() {
        val p = PulsePattern()
        val lap1 = p.minSpacing(m, 1f)
        val lap4 = p.minSpacing(m, 0.421875f)
        assertTrue("lap 4 must be tighter than lap 1", lap4 < lap1)
    }
}
