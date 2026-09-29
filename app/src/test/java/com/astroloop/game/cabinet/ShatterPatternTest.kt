package com.astroloop.game.cabinet

import com.astroloop.game.cabinet.patterns.ShatterPattern
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

class ShatterPatternTest : ReckoningPatternContract() {

    override fun pattern(): ReckoningPattern = ShatterPattern()

    @Test fun itIsCalledShatter() {
        assertEquals("SHATTER", ShatterPattern().name)
    }

    @Test fun clustersArriveAtAnUnbrokenCadence() {
        // A design decision, and this is the biggest single change it makes: SHATTER's identity
        // used to be "a short burst then a long silence" — a 4/12 duty cycle, the longest
        // rest of the five at 4.95s. That silence is gone and the clusters are continuous.
        // Inverted rather than deleted so a re-added silence fails here.
        val e = emissions(1f)
        val gaps = (1 until e.size).map { e[it].first - e[it - 1].first }
        assertTrue(
            "every gap must be one cluster interval; longest was ${gaps.max()}s",
            gaps.all { abs(it - ShatterPattern.CLUSTER_INTERVAL) < 0.05f }
        )
    }

    @Test fun clustersAreEvenlySpreadEvenThoughTheySeemChaotic() {
        // The chaos is WHERE each cluster lands, not how it is shaped. An unevenly
        // shaped cluster could put two bullets close enough to close the lane, and
        // nothing would catch it - the harness only measures the radial schedule.
        val cluster = ShatterPattern().emit(0f, 1f / 120f, 1f, m, shipX, shipY)
        assertEquals(ShatterPattern.COUNT, cluster.size)
        val angles = cluster.map { atan2(it.vy, it.vx) }.sorted()
        val step = 2f * PI.toFloat() / ShatterPattern.COUNT
        for (i in 1 until angles.size) {
            assertEquals(step, angles[i] - angles[i - 1], 0.01f)
        }
    }

    @Test fun aClusterPutsABulletOnTheShipsBearing() {
        // REPLACES "successive clusters land differently". A device pass made SHATTER aim:
        // "it's easy to just camp and easily damage the crystal."
        //
        // A cluster is COUNT bullets evenly spread over the whole circle, so it is rotation
        // INVARIANT — the golden angle this used to assert could only make it look
        // different, never play differently. What matters now is that one of them is always
        // pointed at you.
        val p = ShatterPattern()
        val bs = p.emit(0f, 1f / 120f, 1f, m, shipX, shipY)
        val aim = atan2(shipY - m.height / 2f, shipX - m.width / 2f)
        val closest = bs.minOf { b ->
            var d = abs(atan2(b.vy, b.vx) - aim)
            if (d > PI) d = 2f * PI.toFloat() - d
            d
        }
        assertEquals("a bullet must be on the ship's bearing", 0f, closest, 0.02f)
    }

    @Test fun theChaosIsDeterministic() {
        // Derived from the ship's bearing, not Random. Every fairness test and the
        // RECKONING_PHASE_N jump both depend on the same dt sequence being the same fight.
        val dt = 1f / 120f
        val a = ShatterPattern().emit(2.2f, dt, 1f, m, shipX, shipY)
        val b = ShatterPattern().emit(2.2f, dt, 1f, m, shipX, shipY)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i].vx, b[i].vx, 0.0001f)
    }

    @Test fun theClusterFollowsTheShipRatherThanARotation() {
        // REPLACES "no two clusters in a phase land in the same place", which asserted the
        // golden angle's variety. Against a STATIONARY ship the clusters are now identical
        // by design — the pattern is only predictable to someone who has stopped, and
        // stopping is exactly what it punishes. So the property worth pinning is that it
        // tracks: move, and the cluster moves with you.
        val p = ShatterPattern()
        val dt = 1f / 120f
        val a = p.emit(0f, dt, 1f, m, shipX, shipY).first()
        val b = p.emit(0f, dt, 1f, m, m.width * 0.2f, m.height * 0.25f).first()
        assertNotEquals(
            "the cluster ignored where the ship was",
            atan2(a.vy, a.vx), atan2(b.vy, b.vx), 0.05f
        )
    }
}
