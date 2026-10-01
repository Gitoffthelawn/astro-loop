package com.astroloop.game.lab

import com.astroloop.game.tuning.KnobsPassives
import com.astroloop.game.tuning.KnobsWeapons
import com.astroloop.game.tuning.Tunables
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class KnobFormatTest {
    @After
    fun reset() = Tunables.resetAll()

    @Test
    fun `musical names`() {
        assertEquals("¼ beat", KnobFormat.musical(1))
        assertEquals("½ beat", KnobFormat.musical(2))
        assertEquals("¾ beat", KnobFormat.musical(3))
        assertEquals("1 beat", KnobFormat.musical(4))
        assertEquals("1½ beats", KnobFormat.musical(6))
        assertEquals("3 beats", KnobFormat.musical(12))
        assertEquals("1 bar", KnobFormat.musical(16))
        assertEquals("2 bars", KnobFormat.musical(32))
    }

    @Test
    fun `cooldown shows name and ms`() {
        assertEquals("3 beats · 1500 ms", KnobFormat.value(KnobsWeapons.railgun.cooldown!!))
    }

    @Test
    fun `evolution rate reads against its base`() {
        assertEquals("½× Pulse Cannon", KnobFormat.value(KnobsWeapons.stormCannon.rate!!))
        assertEquals("1× Homing Missiles", KnobFormat.value(KnobsWeapons.autonomousAce.rate!!))
    }

    @Test
    fun `floats print canonically, ints plainly`() {
        assertEquals("0.4", KnobFormat.value(KnobsPassives.nanoRegenPerStack))
        assertEquals("10", KnobFormat.value(KnobsWeapons.railgunPierce))
    }

    @Test
    fun `steps stay in range and on the lattice`() {
        val cd = KnobsWeapons.railgun.cooldown!!
        assertEquals(13f, KnobFormat.stepUp(cd))
        assertEquals(11f, KnobFormat.stepDown(cd))
        Tunables.set(cd, 32f)
        assertEquals(32f, KnobFormat.stepUp(cd))
        val dmg = KnobsWeapons.railgun.damage
        assertEquals(85f, KnobFormat.stepUp(dmg), 0f)
    }

    @Test
    fun `float steps are the largest 1-2-5 value within a tenth of the default`() {
        fun s(default: Float) = KnobFormat.stepFor(default, 0f, default * 10f)
        assertEquals(1f, s(15f), 0f)
        assertEquals(5f, s(80f), 0f)
        assertEquals(5f, s(90f), 0f)
        assertEquals(0.5f, s(5f), 0f)
        assertEquals(50f, s(600f), 0f)
        assertEquals(20f, s(350f), 0f)
        assertEquals(200f, s(2000f), 0f)
        assertEquals(0.2f, s(2f), 1e-7f)
        assertEquals(0.1f, s(1.5f), 1e-7f)
        assertEquals(2f, s(20f), 0f)
        assertEquals(0.02f, s(0.2618f), 1e-8f)
        assertEquals(0.005f, s(0.08f), 1e-9f)
        assertEquals(0.1f, s(1f), 1e-7f)
        assertEquals(0.05f, s(0.7f), 1e-8f)
        assertEquals(0.005f, s(0.05f), 1e-9f)
        // A zero default steps by the same rule over a tenth of the range.
        assertEquals(0.1f, KnobFormat.stepFor(0f, 0f, 10f), 1e-7f)
    }

    @Test
    fun `stepping up then down lands exactly on the default`() {
        val dmg = KnobsWeapons.pulseCannon.damage
        Tunables.set(dmg, KnobFormat.stepUp(dmg))
        assertEquals(16f, dmg.value, 0f)
        Tunables.set(dmg, KnobFormat.stepDown(dmg))
        assertEquals(dmg.defaultRaw, dmg.raw, 0f)
        assertTrue(dmg.isDefault)

        val spread = KnobsWeapons.pulseSpread
        Tunables.set(spread, KnobFormat.stepUp(spread))
        Tunables.set(spread, KnobFormat.stepUp(spread))
        Tunables.set(spread, KnobFormat.stepDown(spread))
        Tunables.set(spread, KnobFormat.stepDown(spread))
        assertEquals(spread.defaultRaw, spread.raw, 0f)
        assertTrue(spread.isDefault)
    }

    @Test
    fun `an off-lattice value snaps to the next lattice point in the pressed direction`() {
        val dmg = KnobsWeapons.pulseCannon.damage
        Tunables.set(dmg, 15.37f)
        assertEquals(16f, KnobFormat.stepUp(dmg), 0f)
        assertEquals(15f, KnobFormat.stepDown(dmg), 0f)
    }

    @Test
    fun `lattice values carry no float noise`() {
        val life = KnobsWeapons.pulseLifetime                       // default 2, step 0.2
        repeat(7) { Tunables.set(life, KnobFormat.stepUp(life)) }
        assertEquals("3.4", KnobFormat.value(life))
    }

    @Test
    fun `steps stop at the range edge and come back onto the lattice`() {
        val dmg = KnobsWeapons.pulseCannon.damage                    // range 1.5 .. 150
        Tunables.set(dmg, 1.5f)
        assertEquals(1.5f, KnobFormat.stepDown(dmg), 0f)
        assertEquals(2f, KnobFormat.stepUp(dmg), 0f)
    }

    @Test
    fun `a quarter-beat base interval warns`() {
        assertNull(KnobFormat.fastWarning(KnobsWeapons.pulseCannon.cooldown!!))
        Tunables.set(KnobsWeapons.pulseCannon.cooldown!!, 1f)
        assertNotNull(KnobFormat.fastWarning(KnobsWeapons.pulseCannon.cooldown!!))
        assertNotNull(KnobFormat.fastWarning(KnobsWeapons.stormCannon.rate!!))
    }
}
