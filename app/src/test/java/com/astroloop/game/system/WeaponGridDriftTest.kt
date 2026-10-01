package com.astroloop.game.system

import com.astroloop.game.core.BeatClock
import com.astroloop.game.core.GameState
import com.astroloop.game.core.SoundManager
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.Projectile
import com.astroloop.game.entity.Ship
import com.astroloop.game.tuning.KnobsWeapons
import com.astroloop.game.tuning.Tunables
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives WeaponSystem with a fake clock at 120 fps and records when each shot fires. Every shot
 * must land within one frame after a grid tick, however long the run: lateness may not build up.
 */
class WeaponGridDriftTest {

    @After
    fun reset() = Tunables.resetAll()

    private val frameMs = 1000.0 / 120.0

    /** Fire times (ms) of [weaponId] over [seconds] of play, with Revenge on if [revenge]. */
    private fun fireTimes(weaponId: String, seconds: Int, revenge: Boolean = false): List<Double> {
        val pool = EntityPool({ Projectile() }, 64)
        val system = WeaponSystem(pool)
        val state = GameState().also { it.reset(); it.revengeActive = revenge }
        val ship = Ship()
        var nowMs = 0.0
        system.nowMs = { nowMs.toLong() }
        SoundManager.beatClock.start(0L)
        system.addWeapon(weaponId, state)
        val times = mutableListOf<Double>()
        var lastInUse = 0
        val frames = (seconds * 120)
        repeat(frames) {
            nowMs += frameMs
            system.update((frameMs / 1000.0).toFloat(), ship, state, emptyList())
            val inUse = pool.getAllInUse().size
            if (inUse > lastInUse) times += nowMs
            lastInUse = inUse
        }
        return times
    }

    private fun assertOnGrid(label: String, times: List<Double>, subdivisionMs: Double, phaseMs: Double) {
        assertTrue("$label never fired", times.size > 3)
        for (t in times) {
            val sincePhase = t - phaseMs
            val late = ((sincePhase % subdivisionMs) + subdivisionMs) % subdivisionMs
            assertTrue("$label shot at $t ms is ${"%.2f".format(late)} ms past its tick", late <= frameMs + 1.5)  // the fake clock truncates to whole ms
        }
    }

    /** Shots over the run must match the grid's tick count: skipping ticks is as wrong as drifting. */
    private fun assertRate(label: String, times: List<Double>, subdivisionMs: Double, seconds: Int) {
        val expected = seconds * 1000.0 / subdivisionMs
        assertTrue("$label fired ${times.size} times in ${seconds}s, expected about ${expected.toInt()}",
            kotlin.math.abs(times.size - expected) <= 2.0)
    }

    @Test
    fun `pulse cannon stays on its half-beat grid for a minute`() {
        val t = fireTimes("pulse_cannon", 60)
        assertOnGrid("pulse", t, 500.0, 0.0)
        assertRate("pulse", t, 500.0, 60)
    }

    @Test
    fun `railgun on its dotted grid for a minute`() {
        val t = fireTimes("railgun", 60)
        assertOnGrid("railgun", t, 1500.0, 0.0)
        assertRate("railgun", t, 1500.0, 60)
    }

    @Test
    fun `a tuned odd sixteenth stays on grid`() {
        Tunables.set(KnobsWeapons.pulseCannon.cooldown!!, 3f)          // 375 ms
        val t = fireTimes("pulse_cannon", 30)
        assertOnGrid("pulse@3/16", t, 375.0, 0.0)
        assertRate("pulse@3/16", t, 375.0, 30)
    }

    @Test
    fun `revenge halves the interval and stays on the halved grid`() {
        val t = fireTimes("pulse_cannon", 30, revenge = true)
        assertOnGrid("pulse revenge", t, 250.0, 0.0)
        assertRate("pulse revenge", t, 250.0, 30)
    }

    @Test
    fun `needle gun keeps its grid (regression)`() {
        val t = fireTimes("needle_gun", 30)
        assertOnGrid("needle", t, 250.0, 0.0)
        assertRate("needle", t, 250.0, 30)
    }
}
