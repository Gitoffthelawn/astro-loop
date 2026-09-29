package com.astroloop.game.cabinet

import com.astroloop.game.cabinet.patterns.WindmillPattern
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2

class WindmillPatternTest : ReckoningPatternContract() {

    override fun pattern(): ReckoningPattern = WindmillPattern()

    @Test fun itIsCalledWindmill() {
        assertEquals("WINDMILL", WindmillPattern().name)
    }

    @Test fun threeArmsEvenlySpaced() {
        val arms = WindmillPattern().emit(0f, 1f / 120f, 1f, m, shipX, shipY)
        assertEquals(WindmillPattern.ARMS, arms.size)
        val angles = arms.map { atan2(it.vy, it.vx) }.sorted()
        val step = 2f * PI.toFloat() / WindmillPattern.ARMS
        for (i in 1 until angles.size) {
            assertEquals("arms must be evenly spaced", step, angles[i] - angles[i - 1], 0.01f)
        }
    }

    @Test fun theArmsSweep() {
        // Without rotation this is three static streams, and the pattern's question —
        // can you time a dive through a rotating gap — stops being asked.
        val rings = emissions(1f).map { it.second }
        assertTrue("need at least two emissions to see rotation", rings.size >= 2)
        val a = atan2(rings.first().first().vy, rings.first().first().vx)
        val b = atan2(rings.last().first().vy, rings.last().first().vx)
        assertNotEquals("the arms must be somewhere else by the end of the phase", a, b, 0.05f)
    }

    @Test fun theArmsAreSlowEnoughToBeReadable() {
        // The old fight could not have this pattern. CrystalFightSystem carried a hard
        // floor - "Speeds NEVER drop below 487.5 px/s or the fight becomes winnable by
        // flying away". On a torus you cannot fly away, so slow is finally usable.
        val speed = WindmillPattern.SPEED_FRAC * m.minEdge
        assertTrue("these arms must be slower than the old floor", speed < 487.5f)
        assertTrue("but faster than the player can simply outpace", speed > m.topSpeed * 0.9f)
    }

    @Test fun theSectorBetweenArmsClearsTheFloorAtEngagementRange() {
        val sector = 2f * PI.toFloat() * ReckoningFairness.engagementRange(m) / WindmillPattern.ARMS
        assertTrue(
            "sector ${sector}px must clear ${ReckoningFairness.spacingFloor(m)}px",
            sector >= ReckoningFairness.spacingFloor(m)
        )
    }

    @Test fun tighteningSpinsTheArmsFasterAndShortensTheSector() {
        // Was asserted through lullLength, which decision deleted. WINDMILL never had
        // a rest to remove — its gap between arm passes is geometry, not silence — so the
        // property survives; only the instrument changed. More arms alive at once is the
        // same fact the old lull figure reported from the other side.
        val p = WindmillPattern()
        assertTrue(p.peakDensity(m, 0.75f) > p.peakDensity(m, 1f))
    }
}
