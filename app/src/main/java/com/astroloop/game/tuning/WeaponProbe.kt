package com.astroloop.game.tuning

import com.astroloop.game.core.GameState
import com.astroloop.game.entity.Asteroid
import com.astroloop.game.entity.AsteroidSize
import com.astroloop.game.entity.AsteroidType
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.Firer
import com.astroloop.game.entity.Projectile
import com.astroloop.game.util.Vector2
import com.astroloop.game.weapon.Weapon
import com.astroloop.game.weapon.WeaponFactory
import com.astroloop.game.weapon.weapons.ClusterBomb
import com.astroloop.game.weapon.weapons.FrostRing
import com.astroloop.game.weapon.weapons.IonOrbiters
import com.astroloop.game.weapon.weapons.LingeringNova
import com.astroloop.game.weapon.weapons.SpaceMines
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a weapon really does with the current knobs: it is built, fired once and allowed to finish
 * its delayed spawns, and what it spawned is counted. Nothing here touches a running game.
 *
 * Results are remembered until any knob changes ([Tunables.version] moves), since a probe run is
 * far too costly to repeat for every line of an editor list on every keystroke.
 */
object WeaponProbe {
    private const val TARGETS = 8
    private const val TARGET_DISTANCE = 300f
    private const val TICK = 0.05f
    /** Long enough for the slowest staggered drop or delayed burst any knob range allows. */
    private const val SETTLE_SECONDS = 60f

    private val lock = Any()
    private var cachedVersion = Int.MIN_VALUE
    private val firedCache = HashMap<Triple<String, Int, Boolean>, Int>()
    private val damageCache = HashMap<Pair<String, Int>, Float>()
    private val bombletCache = HashMap<Pair<String, Int>, Int?>()

    /** How many times [fired] has really simulated a shot rather than answering from memory. */
    @Volatile
    internal var probeRuns: Int = 0
        private set

    fun isEvolution(id: String): Boolean = id in WeaponFactory.getEvolutionIds()

    /** Damaging projectiles one shot spawns at [level], with Duplicator's extra projectiles if [duplicator]. */
    fun fired(id: String, level: Int, duplicator: Boolean = false): Int = synchronized(lock) {
        freshen()
        firedCache.getOrPut(Triple(id, level, duplicator)) { runFired(id, level, duplicator) }
    }

    /** Damage per hit at [level] with no passives. */
    fun damage(id: String, level: Int): Float = synchronized(lock) {
        freshen()
        damageCache.getOrPut(id to level) { build(id, level).getDamage(probeState()) }
    }

    /** Bomblets each bomb releases, for the weapons that carry them. */
    fun bomblets(id: String, level: Int): Int? = synchronized(lock) {
        freshen()
        val key = id to level
        if (key in bombletCache) bombletCache[key] else runBomblets(id, level).also { bombletCache[key] = it }
    }

    /** Forgets every remembered result once a knob has changed since they were measured. */
    private fun freshen() {
        val v = Tunables.version
        if (v != cachedVersion) {
            firedCache.clear()
            damageCache.clear()
            bombletCache.clear()
            cachedVersion = v
        }
    }

    private fun runFired(id: String, level: Int, duplicator: Boolean): Int {
        probeRuns++
        val state = probeState()
        if (duplicator) state.extraProjectiles = KnobsPassives.duplicatorExtraProjectiles.value
        val weapon = build(id, level)
        val pool = EntityPool({ Projectile() }, 16)
        weapon.fire(firer(), state, pool, targets())
        var t = 0f
        while (t < SETTLE_SECONDS) {
            settle(weapon, pool, state)
            t += TICK
        }
        return pool.getAllInUse().count { it.isActive && !it.isVisualOnly }
    }

    private fun runBomblets(id: String, level: Int): Int? = when (id) {
        "cluster_bomb" -> (build(id, level) as ClusterBomb).getBombletCount()
        "hunter_killer" -> KnobsWeapons.hunterBomblets.value
        else -> null
    }

    private fun build(id: String, level: Int): Weapon =
        WeaponFactory.createWeapon(id)!!.also { it.level = level }

    private fun settle(w: Weapon, pool: EntityPool<Projectile>, state: GameState) {
        when (w) {
            is SpaceMines -> w.updatePendingMines(TICK, pool, state)
            is LingeringNova -> w.updatePending(TICK, pool, state)
            is IonOrbiters -> w.updateOrbiters(Vector2(0f, 0f), TICK, state)
            is FrostRing -> w.updateOrbiters(Vector2(0f, 0f), TICK)
            else -> Unit
        }
    }

    private fun probeState() = GameState().also {
        it.reset()
        it.screenWidth = 1080f
        it.screenHeight = 1920f
    }

    private fun firer() = object : Firer {
        override val position = Vector2(0f, 0f)
        override val velocity = Vector2(0f, 0f)
        override val rotation = 0f
        override val radius = 12f
        override val isEnemyFirer = false
    }

    /** A ring of large rocks, all on screen, so target-limited weapons show their full count. */
    private fun targets(): List<Asteroid> = (0 until TARGETS).map { i ->
        val a = 2.0 * PI * i / TARGETS
        Asteroid().also {
            it.initialize((cos(a) * TARGET_DISTANCE).toFloat(), (sin(a) * TARGET_DISTANCE).toFloat(),
                AsteroidSize.LARGE, AsteroidType.ROCK, Vector2(1f, 0f))
        }
    }
}
