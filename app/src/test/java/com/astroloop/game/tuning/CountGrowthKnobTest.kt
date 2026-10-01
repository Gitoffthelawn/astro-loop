package com.astroloop.game.tuning

import com.astroloop.game.core.GameState
import com.astroloop.game.weapon.WeaponFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CountGrowthKnobTest {

    @After
    fun reset() = Tunables.resetAll()

    private fun state() = GameState().also { it.reset() }

    private fun counts(id: String, levels: IntRange): List<Int> {
        val s = state()
        return levels.map { l -> WeaponFactory.createWeapon(id)!!.also { it.level = l }.getProjectileCount(s) }
    }

    @Test
    fun `growth knobs default to the shipped values`() {
        assertEquals(1, KnobsWeapons.needleCountGrowth.value)
        assertEquals(1, KnobsWeapons.ionCountGrowth.value)
        assertEquals(0.5f, KnobsWeapons.minesCountGrowth.value)
        assertEquals(listOf(1, 1, 2, 2, 3), counts("space_mines", 1..5))
    }

    @Test
    fun `needle growth scales per level`() {
        Tunables.set(KnobsWeapons.needleCountGrowth, 2f)
        assertEquals(7, counts("needle_gun", 3..3)[0])
    }

    @Test
    fun `mines growth scales per level`() {
        Tunables.set(KnobsWeapons.minesCountGrowth, 1f)
        assertEquals(5, counts("space_mines", 5..5)[0])
    }

    @Test
    fun `ion growth of zero holds the count flat`() {
        Tunables.set(KnobsWeapons.ionCountGrowth, 0f)
        assertEquals(2, counts("ion_orbiters", 5..5)[0])
    }
}
