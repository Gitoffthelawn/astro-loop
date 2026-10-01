package com.astroloop.game.tuning

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

/** Pins shipped values the golden snapshot cannot observe (angles, homing, heal-on-hit). */
class KnobDefaultsTest {
    @Test
    fun `projectile weapon specials default to the shipped values`() {
        with(KnobsWeapons) {
            assertEquals(PI.toFloat() / 12f, pulseSpread.value)
            assertEquals(PI.toFloat() / 6f, stormSpiralStep.value)
            assertEquals(0.08f, needleSpread.value)
            assertEquals(0.08f, siphonSpread.value)
            assertEquals(0.1f, siphonHealPerHit.value)
            assertEquals(PI.toFloat() / 6f, scatterCone.value)
            assertEquals(3.5f, homingStrength.value)
            assertEquals(0.3f, homingFan.value)
            assertEquals(2.5f, aceHoming.value)
            assertEquals(0.2f, clusterFan.value)
            assertEquals(3.5f, hunterHoming.value)
            assertEquals(0.15f, flakSpread.value)
            assertEquals(0.1f, flakJitter.value)
            assertEquals(0.15f, barrageSpread.value)
            assertEquals(0.1f, barrageJitter.value)
        }
    }

    @Test
    fun `area and persistent weapon specials default to the shipped values`() {
        with(KnobsWeapons) {
            assertEquals(1f, sawDamagePerLevel.value)
            assertEquals(600f, warpLeash.value)
            assertEquals(450f, warpRoamSpeed.value)
            assertEquals(0.3f, warpDuration.value)
            assertEquals(1600f, beamLength.value)
            assertEquals(10f, beamHalfWidth.value)
            assertEquals(3f, ionOrbitSpeed.value)
            assertEquals(0.3f, ionOrbitSpeedPerLevel.value)
            assertEquals(3.5f, frostInnerSpeed.value)
            assertEquals(2.5f, frostOuterSpeed.value)
            assertEquals(0.2f, minesSpawnDelay.value)
        }
    }
}
