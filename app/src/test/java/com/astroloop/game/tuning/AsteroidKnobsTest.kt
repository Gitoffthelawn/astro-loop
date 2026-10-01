package com.astroloop.game.tuning

import com.astroloop.game.entity.Asteroid
import com.astroloop.game.entity.AsteroidSize
import com.astroloop.game.entity.AsteroidType
import com.astroloop.game.util.Vector2
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AsteroidKnobsTest {
    @After
    fun reset() = Tunables.resetAll()

    @Test
    fun `defaults the golden cannot see`() {
        assertEquals(5, KnobsAsteroids.type(AsteroidType.ROCK).spawnWeight.value)
        assertEquals(1, KnobsAsteroids.type(AsteroidType.MAGNETIC).spawnWeight.value)
        assertNull(KnobsAsteroids.type(AsteroidType.ROCK).unlockSeconds)
        assertEquals(300f, KnobsAsteroids.type(AsteroidType.TRAIL).unlockSeconds!!.value)
        assertEquals(600f, KnobsAsteroids.magneticPullRange.value)
    }

    @Test
    fun `a size knob reaches a new asteroid`() {
        Tunables.set(KnobsAsteroids.size(AsteroidSize.LARGE).hp, 80f)
        val a = Asteroid().also { it.initialize(0f, 0f, AsteroidSize.LARGE, AsteroidType.METAL, Vector2(1f, 0f)) }
        assertEquals(160f, a.maxHealth)
    }

    @Test
    fun `ice split bounds cannot cross`() {
        Tunables.set(KnobsAsteroids.iceSplitMin, 8f)   // above the default max of 4
        val ice = Asteroid().also { it.initialize(0f, 0f, AsteroidSize.LARGE, AsteroidType.ICE, Vector2(1f, 0f)) }
        repeat(50) { assertTrue(ice.getSplitCount() in 4..8) }
    }
}
