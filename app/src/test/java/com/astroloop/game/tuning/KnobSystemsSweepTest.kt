package com.astroloop.game.tuning

import com.astroloop.game.core.GameState
import com.astroloop.game.core.SoundManager
import com.astroloop.game.entity.Asteroid
import com.astroloop.game.entity.AsteroidSize
import com.astroloop.game.entity.AsteroidType
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.EntityPools
import com.astroloop.game.entity.Projectile
import com.astroloop.game.entity.Ship
import com.astroloop.game.entity.VisualEffectManager
import com.astroloop.game.system.BeamDamageSystem
import com.astroloop.game.system.CombatDroneSystem
import com.astroloop.game.system.SawDamageSystem
import com.astroloop.game.system.SpawnSystem
import com.astroloop.game.system.VampiricLeecherSystem
import com.astroloop.game.system.WeaponSystem
import com.astroloop.game.util.Vector2
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The systems that run weapons, drones, leeching and spawning, driven for a few seconds with
 * every knob at each extreme. KnobRangeSweepTest covers the weapons in isolation; this covers
 * the loops around them, where a tuned value reaches an index, a divisor or a timer.
 */
class KnobSystemsSweepTest {

    @After
    fun reset() = Tunables.resetAll()

    private val knobs get() = Tunables.all.filterNot { it.group.startsWith("Test/") }

    /** A crowd hugging the ship (for the leech), then a spread further out (for saw and beam reach). */
    private fun field(): MutableList<Asteroid> {
        val list = mutableListOf<Asteroid>()
        for (i in 0 until 12) list += Asteroid().also { placeNear(it, i) }
        for (i in 0 until 12) list += Asteroid().also {
            val size = AsteroidSize.values()[i % 3]
            val type = AsteroidType.values()[i % 6]
            it.initialize(120f + i * 40f, (i % 4) * 60f - 90f, size, type, Vector2(-1f, 0f))
        }
        return list
    }

    /** Small or medium rock on a ring of radius 50-80 round the ship, so its edge is well inside leech range. */
    private fun placeNear(a: Asteroid, i: Int) {
        val ang = i * (2f * Math.PI.toFloat() / 12f)
        val r = 50f + (i % 4) * 10f
        val size = if (i % 2 == 0) AsteroidSize.SMALL else AsteroidSize.MEDIUM
        a.initialize(cos(ang) * r, sin(ang) * r, size, AsteroidType.ROCK, Vector2(0f, 0f))
    }

    class Stats {
        var damage = 0f
        var sawDamage = 0f
        var beamDamage = 0f
        var leechParticlesPeak = 0
        var leechHealthDrained = 0f
        var droneProjectiles = 0
        var spawned = 0
        fun add(o: Stats) {
            damage += o.damage; sawDamage += o.sawDamage; beamDamage += o.beamDamage
            leechParticlesPeak = maxOf(leechParticlesPeak, o.leechParticlesPeak)
            leechHealthDrained += o.leechHealthDrained; droneProjectiles += o.droneProjectiles; spawned += o.spawned
        }
        override fun toString() = "damage=$damage saw=$sawDamage beam=$beamDamage leechPeak=$leechParticlesPeak " +
            "leechDrained=$leechHealthDrained droneShots=$droneProjectiles spawned=$spawned"
    }

    private fun runSystems(seconds: Float, evolved: Boolean): Stats {
        val stats = Stats()
        val dt = 1f / 60f
        val state = GameState().also {
            it.reset(); it.astroLoopMode = true; it.survivalTime = 600f
            it.screenWidth = 1080f; it.screenHeight = 1920f
            for (p in listOf("vampiric_core", "cryo_field", "tb26", "momentum_drive")) it.passiveStacks[p] = 5
            it.recalculateStats()
            if (evolved) { it.droneEvolved = true; it.revengeActive = true }
        }
        val ship = Ship()
        val asteroids = field()
        val pool = EntityPool({ Projectile() }, 64)
        val weapons = WeaponSystem(pool)
        var fakeNow = 0L
        weapons.nowMs = { fakeNow }
        SoundManager.beatClock.start(0L)
        for (id in GoldenSnapshot.WEAPON_IDS) {
            weapons.addWeapon(id, state)
            repeat(4) { weapons.addWeapon(id, state) }
        }
        val effects = VisualEffectManager()
        val saws = SawDamageSystem(state, ship, weapons, effects, { it to false }, {}, {}, {})
        val beam = BeamDamageSystem(state, ship, weapons, { _, _, _, _, _ -> }, { _, _, _, _ -> }, { it to false }, {}, {}, {})
        val leech = VampiricLeecherSystem { it.isActive = false }
        val drones = CombatDroneSystem(state, ship)
        val spawner = SpawnSystem(EntityPool({ Asteroid() }, 32)).also { it.initialize(1080f, 1920f) }
        var t = 0f
        var respawns = 0
        while (t < seconds) {
            fakeNow += 16L
            weapons.update(dt, ship, state, asteroids, asteroids)
            saws.update(dt, emptyList(), asteroids)
            beam.update(dt, emptyList(), asteroids)
            ship.health = ship.maxHealth * 0.5f // the leech only ticks while hurt
            val healthBefore = asteroids.sumOf { if (it.isActive) it.health.toDouble() else 0.0 }.toFloat()
            leech.update(ship, asteroids, state, dt)
            val healthAfter = asteroids.sumOf { if (it.isActive) it.health.toDouble() else 0.0 }.toFloat()
            stats.leechHealthDrained += (healthBefore - healthAfter).coerceAtLeast(0f)
            stats.leechParticlesPeak = maxOf(stats.leechParticlesPeak, leech.particles.size)
            val shotsBefore = EntityPools.projectiles.getAllInUse().size
            drones.update(dt, asteroids, emptyList())
            stats.droneProjectiles += (EntityPools.projectiles.getAllInUse().size - shotsBefore).coerceAtLeast(0)
            stats.spawned += spawner.update(dt, state, ship).size
            // Re-place dead rocks alternately near the ship and further out so the crowd persists.
            for ((i, a) in asteroids.withIndex()) if (!a.isActive) {
                if (respawns++ % 2 == 0) placeNear(a, i)
                else a.initialize(300f, 0f, AsteroidSize.LARGE, AsteroidType.ROCK, Vector2(-1f, 0f))
            }
            t += dt
        }
        stats.damage = state.telemetryTotalDamageDealt
        stats.sawDamage = (state.telemetryDamageByWeapon["energy_saw"] ?: 0f) + (state.telemetryDamageByWeapon["warp_saw"] ?: 0f)
        stats.beamDamage = state.telemetryDamageByWeapon["oblivion_beam"] ?: 0f
        return stats
    }

    private fun sweep(label: String, seconds: Float = 1f, apply: () -> Unit) {
        Tunables.resetAll()
        apply()
        for (evolved in listOf(false, true)) {
            try {
                runSystems(seconds / 2f, evolved)
            } catch (t: Throwable) {
                fail("$label (evolved=$evolved) threw ${t::class.simpleName}: ${t.message}")
            }
        }
    }

    @Test
    fun `baseline run actually exercises every system`() {
        Tunables.resetAll()
        val total = Stats()
        for (evolved in listOf(false, true)) {
            val s = runSystems(2f, evolved)
            println("baseline evolved=$evolved: $s")
            total.add(s)
            if (evolved) assertTrue("evolved run: drones fired: $s", s.droneProjectiles > 0)
        }
        assertTrue("weapon damage: $total", total.damage > 0f)
        assertTrue("saw damage: $total", total.sawDamage > 0f)
        assertTrue("beam damage: $total", total.beamDamage > 0f)
        assertTrue("leech drained: $total", total.leechHealthDrained > 0f && total.leechParticlesPeak > 0)
        assertTrue("drones fired: $total", total.droneProjectiles > 0)
        assertTrue("spawner spawned: $total", total.spawned > 0)
    }

    @Test
    fun `systems survive every knob alone at its extremes`() {
        for (k in knobs) {
            sweep("${k.id}=min") { Tunables.set(k, k.minRaw) }
            sweep("${k.id}=max") { Tunables.set(k, k.maxRaw) }
        }
    }

    @Test
    fun `systems survive all knobs at min, and all at max`() {
        sweep("all=min", seconds = 10f) { for (k in knobs) Tunables.set(k, k.minRaw) }
        sweep("all=max", seconds = 10f) { for (k in knobs) Tunables.set(k, k.maxRaw) }
    }
}
