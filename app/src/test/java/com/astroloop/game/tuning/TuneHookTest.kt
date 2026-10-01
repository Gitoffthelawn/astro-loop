package com.astroloop.game.tuning

import com.astroloop.game.core.GameState
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.Projectile
import com.astroloop.game.entity.Ship
import com.astroloop.game.render.PauseTuneButton
import com.astroloop.game.system.WeaponSystem
import com.astroloop.game.weapon.weapons.IonOrbiters
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TuneHookTest {

    @After
    fun reset() = Tunables.resetAll()

    @Test
    fun `the default listener offers no tuning`() {
        assertFalse(object : RunListener {}.tuneAvailable())
    }

    @Test
    fun `the tune button sits in the lower half and is hit only inside`() {
        val r = PauseTuneButton.rect(960f, 2142f)
        assertTrue(r.top > 2142f / 2f)
        assertTrue(PauseTuneButton.hit(r.centerX(), r.centerY(), 960f, 2142f))
        assertFalse(PauseTuneButton.hit(480f, 300f, 960f, 2142f))
    }

    @Test
    fun `knob changes re-anchor every weapon's timer`() {
        val state = GameState().also { it.reset() }
        val weapons = WeaponSystem(EntityPool({ Projectile() }, 32))
        weapons.addWeapon("pulse_cannon", state)
        val pulse = weapons.getWeapon("pulse_cannon")!!
        pulse.beatSynced = true
        pulse.cooldownTimer = 1.4f
        weapons.onKnobsChanged()
        assertFalse(pulse.beatSynced)
        assertEquals(0f, pulse.cooldownTimer)
    }

    @Test
    fun `orbiters fade out and respawn at the new count`() {
        val state = GameState().also { it.reset() }
        val pool = EntityPool({ Projectile() }, 32)
        val weapons = WeaponSystem(pool)
        weapons.addWeapon("ion_orbiters", state)
        val ion = weapons.getWeapon("ion_orbiters") as IonOrbiters
        ion.fire(Ship(), state, pool, emptyList())
        val before = pool.getAllInUse().filter { it.isActive && it.lifetime > 100f }
        assertEquals(2, before.size)
        Tunables.set(KnobsWeapons.ionOrbiters.count!!, 4f)
        weapons.onKnobsChanged()
        // the old orbiters are fading (short lifetime), not removed
        assertTrue(before.all { it.isActive && it.lifetime < 100f })
        assertEquals(0f, ion.cooldownTimer)
        ion.fire(Ship(), state, pool, emptyList())
        assertEquals(4, pool.getAllInUse().count { it.isActive && it.lifetime > 100f })
    }

    @Test
    fun `the default listener offers no quit`() {
        assertFalse(object : RunListener {}.quitAvailable())
    }

    @Test
    fun `with quit offered, QUIT sits left and TUNE right on one row, neither overlapping`() {
        val q = PauseTuneButton.quitRect(960f, 2142f)
        val t = PauseTuneButton.tuneRect(960f, 2142f, withQuit = true)
        assertEquals(q.top, t.top, 0f)
        assertTrue(q.right < t.left)
        assertTrue(q.left >= 0f && t.right <= 960f)
        assertTrue(PauseTuneButton.hitQuit(q.centerX(), q.centerY(), 960f, 2142f))
        assertFalse(PauseTuneButton.hitTune(q.centerX(), q.centerY(), 960f, 2142f, withQuit = true))
        assertEquals(PauseTuneButton.rect(960f, 2142f), PauseTuneButton.tuneRect(960f, 2142f, withQuit = false))
    }
}
