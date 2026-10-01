package com.astroloop.game.tuning

import com.astroloop.game.core.GameState
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.Projectile
import com.astroloop.game.system.WeaponSystem
import org.junit.Assert.*
import org.junit.Test

class RunLoadoutTest {

    private val sample = RunLoadout(
        shipId = "ship_blue",
        pilotId = "pilot_astro",
        weapons = listOf("railgun" to 5, "oblivion_beam" to 5, "pulse_cannon" to 2),
        passives = listOf("glass_cannon" to 5, "tb26" to 3),
        startMinute = 8,
    )

    @Test
    fun `encode and decode round-trip`() {
        assertEquals(sample, RunLoadout.decode(sample.encode()))
    }

    @Test
    fun `decode rejects garbage and clamps levels`() {
        assertNull(RunLoadout.decode("nonsense"))
        val d = RunLoadout.decode("ship=ship_blue;pilot=pilot_astro;w=railgun:9;p=tb26:0;m=-3")!!
        assertEquals(listOf("railgun" to 5), d.weapons)
        assertEquals(listOf("tb26" to 1), d.passives)
        assertEquals(0, d.startMinute)
    }

    @Test
    fun `applier sets levels, evolutions, stacks and the clock`() {
        val state = GameState().also { it.reset(); it.astroLoopMode = true }
        val weapons = WeaponSystem(EntityPool({ Projectile() }, 16))
        weapons.addWeapon("pulse_cannon", state)          // the ship's starting weapon, to be replaced
        LoadoutApplier.apply(sample, state, weapons)
        assertEquals(linkedMapOf("railgun" to 5, "oblivion_beam" to 5, "pulse_cannon" to 2), state.weaponLevels)
        assertEquals(5, weapons.getWeapon("oblivion_beam")!!.level)
        assertTrue(state.hasEvolution("oblivion_beam"))
        assertTrue(state.astroLoopEvolutionUsed)
        assertEquals(5, state.getPassiveStacks("glass_cannon"))
        assertEquals(3, state.getPassiveStacks("tb26"))
        assertEquals(480f, state.survivalTime)
        assertEquals(0f, state.evolutionTimeGateSeconds)
    }

    @Test
    fun `applier drops weapons beyond the slot limit and unknown ids`() {
        val state = GameState().also { it.reset() }
        val weapons = WeaponSystem(EntityPool({ Projectile() }, 16))
        val many = sample.copy(
            weapons = listOf("railgun" to 1, "needle_gun" to 1, "flak_cannon" to 1, "scatter_shot" to 1, "nova_blast" to 1, "nope" to 1),
            passives = emptyList(),
        )
        LoadoutApplier.apply(many, state, weapons)
        assertEquals(state.getMaxWeaponSlots(), state.weaponLevels.size)
        assertFalse(state.weaponLevels.containsKey("nope"))
    }

    private fun applied(loadout: RunLoadout): GameState {
        val state = GameState().also { it.reset(); it.activePilotId = loadout.pilotId }
        LoadoutApplier.apply(loadout, state, WeaponSystem(EntityPool({ Projectile() }, 16)))
        return state
    }

    @Test
    fun `instant-max passives always end at full stacks`() {
        val s = applied(sample.copy(passives = listOf("glass_cannon" to 1)))
        assertEquals(5, s.getPassiveStacks("glass_cannon"))
    }

    @Test
    fun `unknown passive ids are skipped`() {
        val s = applied(sample.copy(passives = listOf("nope" to 3, "tb26" to 2)))
        assertFalse(s.passiveStacks.containsKey("nope"))
        assertEquals(2, s.getPassiveStacks("tb26"))
    }

    @Test
    fun `combat_drone becomes tb26 for astro`() {
        val s = applied(sample.copy(passives = listOf("combat_drone" to 2)))
        assertFalse(s.passiveStacks.containsKey("combat_drone"))
        assertEquals(2, s.getPassiveStacks("tb26"))
    }

    @Test
    fun `totalUpgradesCollected counts passive pickups like normal play`() {
        // glass_cannon is one pickup (instant max), tb26:3 is three; weapons and evolutions add none.
        assertEquals(4, applied(sample).totalUpgradesCollected)
    }
}
