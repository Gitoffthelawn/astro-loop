package com.astroloop.game.tuning

import com.astroloop.game.core.GameState
import com.astroloop.game.weapon.WeaponFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WeaponKnobsTest {

    @After
    fun reset() = Tunables.resetAll()

    private val ids = WeaponFactory.getBaseWeaponIds() + WeaponFactory.getEvolutionIds()

    @Test
    fun `every weapon is wired to its own knobs`() {
        for (id in ids) {
            val w = WeaponFactory.createWeapon(id)!!
            assertSame(id, KnobsWeapons.forWeapon(id), w.knobs)
            assertEquals(id, w.knobs.weaponId)
        }
        assertEquals(24, KnobsWeapons.all.size)
    }

    @Test
    fun `base weapons have a sixteenths cooldown unless they tick`() {
        for (id in WeaponFactory.getBaseWeaponIds()) {
            val k = KnobsWeapons.forWeapon(id)
            assertTrue(id, (k.cooldown != null) xor (k.tickMs != null))
            assertNull(id, k.rate)
        }
    }

    @Test
    fun `evolutions follow their base weapon at 1x or half`() {
        val ticking = setOf("warp_saw", "oblivion_beam")
        for (id in WeaponFactory.getEvolutionIds() - ticking) {
            val k = KnobsWeapons.forWeapon(id)
            val base = k.evolvesFrom!!
            assertNull(id, k.cooldown)
            assertEquals(id, base.cooldownSeconds * k.rate!!.factor, k.cooldownSeconds)
        }
    }

    @Test
    fun `tuning a base weapon's cooldown moves its evolution with it`() {
        Tunables.set(KnobsWeapons.pulseCannon.cooldown!!, 6f)   // 0.75 s
        assertEquals(0.75f, KnobsWeapons.pulseCannon.cooldownSeconds)
        assertEquals(0.375f, KnobsWeapons.stormCannon.cooldownSeconds)
    }

    @Test
    fun `a damage knob reaches the weapon's damage`() {
        val state = GameState().also { it.reset() }
        Tunables.set(KnobsWeapons.railgun.damage, 120f)
        assertEquals(120f, WeaponFactory.createWeapon("railgun")!!.getDamage(state))
    }

    @Test
    fun `an area knob scales the weapon's own radius`() {
        val state = GameState().also { it.reset() }
        Tunables.set(KnobsWeapons.flakCannon.area!!, 2f)
        val flak = GoldenSnapshot.exerciseWeapon("flak_cannon", 1, state)
        assertEquals(120f, flak.first().explosionRadius)
    }
}
