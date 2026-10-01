package com.astroloop.game.tuning

import com.astroloop.game.core.GameState
import com.astroloop.game.system.DifficultySystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldKnobsTest {
    @After
    fun reset() = Tunables.resetAll()

    private fun astroDifficultyAt(minutes: Float): Float {
        val s = GameState().also { it.reset(); it.astroLoopMode = true; it.survivalTime = minutes * 60f }
        DifficultySystem().update(0f, s)
        return s.difficultyMultiplier
    }

    @Test
    fun `defaults the golden cannot see`() {
        assertEquals(2f, KnobsField.spawnInterval.value)
        assertEquals(0.3f, KnobsField.spawnIntervalMin.value)
        assertEquals(0.15f, KnobsField.spawnIntervalDecay.value)
        assertEquals(20f, KnobsField.contactDamage.value)
        assertEquals(0.05f, KnobsDrops.diamondChance.value)
        assertEquals(20f, KnobsDrops.cooldown.value)
        assertEquals(14f, KnobsDrops.earlyCooldown.value)
    }

    @Test
    fun `the curve peaks where the knobs say`() {
        Tunables.set(KnobsField.peakMinute, 10f)
        Tunables.set(KnobsField.peakDifficulty, 5f)
        assertEquals(5f, astroDifficultyAt(10f), 1e-5f)
        assertEquals(5f + 0.4f * 2f, astroDifficultyAt(12f), 1e-5f)
    }
}
