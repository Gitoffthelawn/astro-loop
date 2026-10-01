package com.astroloop.game.lab

import com.astroloop.game.tuning.KnobsPassives
import com.astroloop.game.tuning.KnobsWeapons
import com.astroloop.game.tuning.Tunables
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectiveLineTest {
    @After
    fun reset() = Tunables.resetAll()

    @Test
    fun `labels say what the count knob is`() {
        assertEquals("Projectiles at L1", KnobsWeapons.pulseCannon.count!!.label)
        assertEquals("Targets at L1", KnobsWeapons.solarStorm.count!!.label)
        assertEquals("Projectiles (base)", KnobsWeapons.stormCannon.count!!.label)
        assertEquals("Projectiles per ring", KnobsWeapons.phoenixFlare.count!!.label)
    }

    @Test
    fun `base weapon line lists every level and the duplicator bonus`() {
        val line = EffectiveLine.forKnob(KnobsWeapons.pulseCannon.count!!, runLevel = null)!!
        assertEquals("Fires per level: 1 · 2 · 3 · 4 · 5 (+1 with Duplicator)", line.text)
        assertNull(line.highlight)
    }

    @Test
    fun `the run's level is highlighted`() {
        val line = EffectiveLine.forKnob(KnobsWeapons.scatterShot.count!!, runLevel = 3)!!
        assertEquals("Fires per level: 5 · 7 · 9 · 11 · 13 (+1 with Duplicator)", line.text)
        assertEquals("9", line.text.substring(line.highlight!!))
    }

    @Test
    fun `evolution line shows the real count and the damage bonus`() {
        assertEquals(
            "Fires 6 (7 with Duplicator) · hits for 27.9 (×1.55 evolution bonus)",
            EffectiveLine.forKnob(KnobsWeapons.stormCannon.count!!, null)!!.text,
        )
        assertEquals(
            "Fires up to 24 · hits for 77.5 (×1.55 evolution bonus)",
            EffectiveLine.forKnob(KnobsWeapons.phoenixFlare.count!!, null)!!.text,
        )
        assertEquals(
            "Fires 1 (2 with Duplicator) · 7 bomblets",
            EffectiveLine.forKnob(KnobsWeapons.hunterKiller.count!!, null)!!.text,
        )
    }

    @Test
    fun `target-limited, bomblet and count-less weapons`() {
        assertEquals("Strikes per level, up to: 1 · 2 · 3 · 4 · 5",
            EffectiveLine.forKnob(KnobsWeapons.solarStorm.count!!, null)!!.text)
        assertEquals("Fires per level: 1 · 1 · 1 · 1 · 1 (+1 with Duplicator) · bomblets 2 · 3 · 4 · 5 · 6",
            EffectiveLine.forKnob(KnobsWeapons.clusterBomb.count!!, null)!!.text)
        assertEquals("Fires per level: 8 · 8 · 8 · 8 · 8",
            EffectiveLine.forKnob(KnobsWeapons.novaBlast.damage, null)!!.text)
        assertEquals("Fires 16 · hits for 46.5 (×1.55 evolution bonus)",
            EffectiveLine.forKnob(KnobsWeapons.lingeringNova.damage, null)!!.text)
        assertEquals("Damage per tick by level: 8 · 9 · 10 · 11 · 12",
            EffectiveLine.forKnob(KnobsWeapons.energySaw.damage, null)!!.text)
    }

    @Test
    fun `only the carrier knob gets a line`() {
        assertNull(EffectiveLine.forKnob(KnobsWeapons.pulseCannon.damage, null))
        assertNull(EffectiveLine.forKnob(KnobsWeapons.pulseLifetime, null))
        assertNull(EffectiveLine.forKnob(KnobsWeapons.warpSaw.damage, null))
        assertNull(EffectiveLine.forKnob(KnobsWeapons.oblivionBeam.damage, null))
        assertNull(EffectiveLine.forKnob(KnobsPassives.nanoRegenPerStack, null))
    }

    @Test
    fun `the line follows edits`() {
        Tunables.set(KnobsPassives.duplicatorExtraProjectiles, 2f)
        assertEquals("Fires per level: 1 · 2 · 3 · 4 · 5 (+2 with Duplicator)",
            EffectiveLine.forKnob(KnobsWeapons.pulseCannon.count!!, null)!!.text)
        Tunables.set(KnobsWeapons.stormCannon.damage, 20f)
        assertEquals("Fires 6 (8 with Duplicator) · hits for 31 (×1.55 evolution bonus)",
            EffectiveLine.forKnob(KnobsWeapons.stormCannon.count!!, null)!!.text)
    }

    @Test
    fun `weaponOf finds the group by id prefix`() {
        assertEquals("pulse_cannon", EffectiveLine.weaponOf(KnobsWeapons.pulseLifetime)!!.weaponId)
        assertNull(EffectiveLine.weaponOf(KnobsPassives.nanoRegenPerStack))
    }
}
