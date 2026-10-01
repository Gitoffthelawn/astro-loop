package com.astroloop.game.tuning

import com.astroloop.game.core.GameState
import org.junit.Assert.*
import org.junit.Test

class RunSummaryTest {

    private fun state() = GameState().also { it.reset() }

    @Test
    fun `run totals survive the per-minute reset`() {
        val s = state()
        s.telemetryDamageByWeapon["railgun"] = 100f
        s.telemetryDamageTakenBy["asteroid.rock"] = 5f
        s.resetTelemetryMinuteCounters()
        s.telemetryDamageByWeapon["railgun"] = 40f
        assertEquals(140f, s.runDamageByWeapon().getValue("railgun"))
        assertEquals(5f, s.runDamageTakenBy().getValue("asteroid.rock"))
    }

    @Test
    fun `a full reset clears run totals`() {
        val s = state()
        s.telemetryDamageByWeapon["railgun"] = 100f
        s.resetTelemetryMinuteCounters()
        s.resetTelemetry()
        assertTrue(s.runDamageByWeapon().isEmpty())
    }

    @Test
    fun `summary reads state`() {
        val s = state()
        s.survivalTime = 702f
        s.lastDamageSource = "asteroid.volatile"
        s.weaponLevels["railgun"] = 5
        s.passiveStacks["glass_cannon"] = 5
        s.telemetryDamageByWeapon["railgun"] = 48210f
        s.telemetryAsteroidsDestroyed = 1843
        s.frameCount = 84_000
        s.runStartNanos = 0L
        s.firstTunedAtSeconds = 192f
        val lo = RunLoadout("ship_blue", "pilot_astro", listOf("railgun" to 1), emptyList(), 0)
        val sum = RunSummary.from(s, "ship_blue", "pilot_astro", startMinute = 0, nowNanos = 700_000_000_000L, loadout = lo)
        assertEquals(702f, sum.survivedSeconds)
        assertEquals("asteroid.volatile", sum.cause)
        assertEquals(mapOf("railgun" to 5), sum.finalWeapons)
        assertEquals(48210f, sum.damageByWeapon.getValue("railgun"))
        assertEquals(1843, sum.asteroidsDestroyed)
        assertEquals(120f, sum.avgFps, 0.01f)
        assertEquals(192f, sum.tunedMidRunAtSeconds)
        assertSame(lo, sum.loadout)
    }

    @Test
    fun `no listener is installed by default`() {
        assertNull(RunHooks.listener)
    }
}
