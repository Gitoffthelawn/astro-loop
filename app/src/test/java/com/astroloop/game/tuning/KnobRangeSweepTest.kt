package com.astroloop.game.tuning

import com.astroloop.game.core.GameState
import com.astroloop.game.entity.Asteroid
import com.astroloop.game.entity.AsteroidSize
import com.astroloop.game.entity.AsteroidType
import com.astroloop.game.system.DifficultySystem
import com.astroloop.game.system.SpawnSystem
import com.astroloop.game.util.Vector2
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every knob at its minimum and at its maximum must leave the game running. GameThread swallows
 * exceptions from update(), so a tuning that throws would freeze a run with no trace — this is
 * the guard.
 */
class KnobRangeSweepTest {

    @After
    fun reset() = Tunables.resetAll()

    private val knobs get() = Tunables.all.filterNot { it.group.startsWith("Test/") }

    private fun finite(label: String, v: Float) = assertTrue("$label = $v", v.isFinite())

    private fun exerciseEverything(context: String) {
        for (id in GoldenSnapshot.WEAPON_IDS) for (level in listOf(1, 5)) {
            val projectiles = GoldenSnapshot.exerciseWeapon(id, level)
            for (p in projectiles) {
                finite("$context $id L$level damage", p.damage)
                finite("$context $id L$level radius", p.radius)
                finite("$context $id L$level explosionRadius", p.explosionRadius)
            }
        }
        for (size in AsteroidSize.values()) for (type in AsteroidType.values()) {
            val a = Asteroid().also { it.initialize(0f, 0f, size, type, Vector2(1f, 0f)) }
            repeat(5) { a.getSplitCount() }
            finite("$context $size $type hp", a.maxHealth)
            finite("$context $size $type trail", a.getTrailDamage())
            finite("$context $size $type pull", a.getMagneticPullStrength())
        }
        val difficulty = DifficultySystem()
        for (minute in 0..30) {
            val t = minute * 60f
            val s = GameState().also { it.reset(); it.astroLoopMode = true; it.survivalTime = t }
            difficulty.update(0f, s)
            finite("$context difficulty@$minute", s.difficultyMultiplier)
            finite("$context speed@$minute", SpawnSystem.asteroidSpeedFactor(t, s.difficultyMultiplier))
            finite("$context hp@$minute", SpawnSystem.asteroidHealthFactor(t, true))
            finite("$context drop@$minute", s.getAsteroidDropChance())
        }
        val s = GameState().also { it.reset() }
        for (id in listOf("nano_repair", "duplicator_core", "magnet_field", "phoenix_core", "momentum_drive",
                "cryo_field", "lucky_star", "revenge_protocol", "vampiric_core", "glass_cannon")) {
            s.passiveStacks[id] = 5
        }
        s.recalculateStats()
        finite("$context cryo radius", s.getCryoRadius())
    }

    private fun sweep(label: String, apply: () -> Unit) {
        Tunables.resetAll()
        apply()
        try {
            exerciseEverything(label)
        } catch (t: Throwable) {
            fail("$label threw ${t::class.simpleName}: ${t.message}")
        }
    }

    @Test
    fun `every knob alone at its minimum and maximum`() {
        for (k in knobs) {
            sweep("${k.id}=min") { Tunables.set(k, k.minRaw) }
            sweep("${k.id}=max") { Tunables.set(k, k.maxRaw) }
        }
    }

    @Test
    fun `every knob at its minimum at once, and at its maximum at once`() {
        sweep("all=min") { for (k in knobs) Tunables.set(k, k.minRaw) }
        sweep("all=max") { for (k in knobs) Tunables.set(k, k.maxRaw) }
    }
}
