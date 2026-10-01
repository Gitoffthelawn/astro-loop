package com.astroloop.game.tuning

import com.astroloop.game.core.GameState
import com.astroloop.game.entity.Asteroid
import com.astroloop.game.entity.AsteroidSize
import com.astroloop.game.entity.AsteroidType
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.Firer
import com.astroloop.game.entity.Projectile
import com.astroloop.game.system.BeamDamageSystem
import com.astroloop.game.system.DifficultySystem
import com.astroloop.game.system.SpawnSystem
import com.astroloop.game.system.VampiricLeecherSystem
import com.astroloop.game.util.Vector2
import com.astroloop.game.weapon.Weapon
import com.astroloop.game.weapon.WeaponFactory
import com.astroloop.game.weapon.weapons.*

/**
 * A text rendering of every balance number the game computes, used to prove a refactor changed
 * nothing. Floats print with Kotlin's shortest round-trip form, so equal text means equal bits.
 *
 * Deliberately left out: anything random (scatter angles, pellet speed jitter, mine offsets) and
 * anything positional. Those constants are pinned by KnobDefaultsTest instead.
 */
object GoldenSnapshot {

    val WEAPON_IDS: List<String> = WeaponFactory.getBaseWeaponIds() + WeaponFactory.getEvolutionIds()

    private fun firer() = object : Firer {
        override val position = Vector2(0f, 0f)
        override val velocity = Vector2(0f, 0f)
        override val rotation = 0f
        override val radius = 12f
        override val isEnemyFirer = false
    }

    private fun freshState() = GameState().also {
        it.reset()
        it.screenWidth = 1080f
        it.screenHeight = 1920f
    }

    /** Three LARGE rocks in front of the firer, on screen, targetable. */
    private fun targets(): List<Asteroid> = listOf(200f, 300f, 400f).map { x ->
        Asteroid().also { it.initialize(x, 0f, AsteroidSize.LARGE, AsteroidType.ROCK, Vector2(1f, 0f)) }
    }

    /** Fires [id] at [level] once and runs its delayed spawns to completion; returns everything it spawned. */
    fun exerciseWeapon(id: String, level: Int, state: GameState = freshState()): List<Projectile> {
        val weapon = WeaponFactory.createWeapon(id)!!.also { it.level = level }
        val pool = EntityPool({ Projectile() }, 64)
        weapon.fire(firer(), state, pool, targets())
        repeat(100) { tick(weapon, pool, state, 0.05f) }
        return pool.getAllInUse()
    }

    private fun tick(w: Weapon, pool: EntityPool<Projectile>, state: GameState, dt: Float) {
        when (w) {
            is SpaceMines -> w.updatePendingMines(dt, pool, state)
            is LingeringNova -> w.updatePending(dt, pool, state)
            is IonOrbiters -> w.updateOrbiters(Vector2(0f, 0f), dt, state)
            is FrostRing -> w.updateOrbiters(Vector2(0f, 0f), dt)
            else -> Unit
        }
    }

    private fun projectileLine(p: Projectile): String =
        "proj ${p.weaponId} type=${p.type} dmg=${p.damage} life=${p.lifetime} r=${p.radius} " +
            "pierce=${p.piercing}/${p.maxPierces} len=${p.length} w=${p.width} " +
            "boom=${p.explodeOnDeath} exR=${p.explosionRadius} exD=${p.explosionDamage} " +
            "bomblets=${p.bombletCount} bD=${p.bombletDamage} bR=${p.bombletExplosionRadius} " +
            "frag=${p.hasFragments}/${p.fragmentDamage} homing=${p.homingStrength} " +
            "orbitR=${p.orbitRadius} visualOnly=${p.isVisualOnly} fuse=${p.proximityFuse}"

    private fun extras(w: Weapon, state: GameState): String? = when (w) {
        is EnergySaw -> "saw disc=${w.discRadius} reach=${w.reach} tick=${w.getTickRate()}"
        is WarpSaw -> "warp disc=${w.discRadius} reach=${w.reach} leash=${w.leashRange} roam=${w.roamSpeed} warp=${WarpSaw.WARP_DURATION}"
        is Railgun -> "rail pierce=${w.getPierceCount()} r=${w.getShotRadius()} w=${w.getShotWidth()}"
        is ClusterBomb -> "cluster bomblets=${w.getBombletCount()}"
        is NovaBlast -> "nova radius=${NovaBlast.blastRadiusFor(w.level, state.areaMultiplier)}"
        is LingeringNova -> "lingering radius=${LingeringNova.blastRadiusFor(state.areaMultiplier)}"
        else -> null
    }

    fun weapons(): String = buildString {
        for (id in WEAPON_IDS) for (level in 1..5) {
            val state = freshState()
            val w = WeaponFactory.createWeapon(id)!!.also { it.level = level }
            appendLine("[$id L$level]")
            appendLine(
                "damage=${w.getDamage(state)} cooldown=${w.getCooldown(state)} " +
                    "count=${w.getProjectileCount(state)} speed=${w.getProjectileSpeed(state)} " +
                    "area=${w.getArea(state)} phase=${w.beatPhaseOffsetMs}"
            )
            extras(w, state)?.let { appendLine(it) }
            exerciseWeapon(id, level).map(::projectileLine).sorted().forEach { appendLine(it) }
        }
    }

    fun world(): String = buildString {
        // Asteroids: every size × type, fired straight along +x so speed is deterministic.
        for (size in AsteroidSize.values()) for (type in AsteroidType.values()) {
            val a = Asteroid().also { it.initialize(0f, 0f, size, type, Vector2(1f, 0f)) }
            val splits = (1..400).map { a.getSplitCount() }
            appendLine(
                "asteroid $size $type r=${a.radius} hp=${a.maxHealth} speed=${a.velocity.length()} " +
                    "contact=${a.getContactDamage()} scale=${a.getDamageScale()} " +
                    "trail=${a.getTrailDamage()}/${a.getTrailWidth()}/${a.getTrailLifetime()} " +
                    "boom=${a.getExplosionRadius()}/${a.getExplosionDamage()} pull=${a.getMagneticPullStrength()} " +
                    "split=${a.shouldSplit()} ${splits.min()}..${splits.max()}"
            )
        }
        // Field curves, sampled every half minute to 20 minutes.
        val difficulty = DifficultySystem()
        for (half in 0..40) {
            val minutes = half / 2f
            val t = minutes * 60f
            for (astro in listOf(false, true)) {
                val s = GameState().also { it.reset(); it.astroLoopMode = astro; it.survivalTime = t }
                difficulty.update(0f, s)
                val m = s.difficultyMultiplier
                appendLine(
                    "field m=$minutes astro=$astro difficulty=$m count=${SpawnSystem.asteroidCount(m)} " +
                        "speed=${SpawnSystem.asteroidSpeedFactor(t, m)} hp=${SpawnSystem.asteroidHealthFactor(t, astro)} " +
                        "dmg=${SpawnSystem.asteroidDamageBonus(t, astro)}"
                )
            }
        }
        // Passives, every stack count.
        val passiveIds = listOf(
            "nano_repair", "duplicator_core", "magnet_field", "phoenix_core", "extra_weapon_slot",
            "tb26", "combat_drone", "momentum_drive", "cryo_field", "lucky_star",
            "revenge_protocol", "vampiric_core", "glass_cannon"
        )
        for (id in passiveIds) for (stacks in 1..5) {
            val s = GameState().also { it.reset(); it.passiveStacks[id] = stacks; it.recalculateStats() }
            appendLine(
                "passive $id x$stacks dmg=${s.damageMultiplier} regen=${s.healthRegen} pickup=${s.pickupRangeMultiplier} " +
                    "extraProj=${s.extraProjectiles} lives=${s.extraLives} drones=${s.droneCount} " +
                    "momentum=${s.momentumDamageBonus} cryo=${s.cryoSlowPercent}/${s.cryoRadiusMultiplier}/${s.getCryoRadius()} " +
                    "dropRate=${s.dropRateMultiplier} shieldCap=${s.maxShieldCap} shieldRegenOff=${s.shieldRegenDisabled}"
            )
        }
        // Drop chance as upgrades are collected, both modes.
        for (astro in listOf(false, true)) for (collected in 0..12) {
            val s = GameState().also { it.reset(); it.astroLoopMode = astro; it.asteroidUpgradesCollected = collected }
            appendLine("drops astro=$astro collected=$collected chance=${s.getAsteroidDropChance()} early=${s.isEarlyGameDropRate()}")
        }
        // System constants read by code this plan moves behind knobs.
        appendLine("beam length=${BeamDamageSystem.BEAM_LENGTH} halfWidth=${BeamDamageSystem.BEAM_HALF_WIDTH} tick=${BeamDamageSystem.TICK_INTERVAL}")
        appendLine(
            "vampiric tick=${VampiricLeecherSystem.TICK_INTERVAL} range=${VampiricLeecherSystem.LEECH_RANGE} " +
                "perStack=${VampiricLeecherSystem.LEECH_PER_STACK} maxTargets=${VampiricLeecherSystem.MAX_TARGETS}"
        )
    }
}
