package com.astroloop.game.weapon.weapons

import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.GameState
import com.astroloop.game.entity.Entity
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.Projectile
import com.astroloop.game.entity.ProjectileType
import com.astroloop.game.entity.Firer
import com.astroloop.game.util.Vector2
import com.astroloop.game.data.ShipDefinitions
import com.astroloop.game.weapon.Weapon
import kotlin.random.Random
import com.astroloop.game.tuning.KnobsWeapons

class ScatterShot : Weapon(
    id = "scatter_shot",
    name = "Scatter Shot"
) {
    companion object {
        /**
         * How wide the pellets throw, before `areaMultiplier`.
         *
         * **Defaults to 30 degrees, halved from 60 on 2026-08-11.** Reported independently by two
         * players: the weapon's damage stat is mid-table, but a 60-degree throw put most of the
         * pellets nowhere near the target, so the shortfall was accuracy rather than damage.
         * Narrowing the cone raises effective damage without touching the damage number, which is
         * also why the level bonus ("+2 pellets") starts being felt.
         *
         * Still deliberately wide enough to read as a shotgun — the identity is the spread, and a
         * cone this weapon could not miss with would just be a machine gun.
         *
         * [LeechBurst][com.astroloop.game.weapon.weapons.LeechBurst] reads this rather than
         * declaring its own, so the evolution can never end up throwing wider than the weapon it
         * replaced. It previously carried a copy and a comment saying they matched.
         */
        val SPREAD_CONE_RADIANS: Float get() = KnobsWeapons.scatterCone.value
    }

    override val knobs = KnobsWeapons.scatterShot
    override val beatPhaseOffsetMs: Long = 500L

    override fun getDamage(state: GameState): Float = baseDamage * state.damageMultiplier

    override fun getProjectileCount(state: GameState): Int =
        baseProjectileCount + (level - 1) * KnobsWeapons.scatterCountGrowth.value + state.extraProjectiles

    override fun getCooldown(state: GameState): Float {
        return baseCooldown * state.cooldownMultiplier
    }

    override fun fire(
        firer: Firer,
        state: GameState,
        projectilePool: EntityPool<Projectile>,
        targets: List<Entity>
    ) {
        if (!canFire()) return

        val damage = getDamage(state)
        val speed = getProjectileSpeed(state)
        val count = getProjectileCount(state)
        val spreadAngle = SPREAD_CONE_RADIANS * areaOf(state)

        for (i in 0 until count) {
            // Random spread within cone
            val angle = firer.rotation + (Random.nextFloat() - 0.5f) * spreadAngle
            val direction = Vector2.fromAngle(angle)

            // Slight speed variation
            val projectileSpeed = speed * (0.9f + Random.nextFloat() * 0.2f)

            val projectile = projectilePool.obtain()
            projectile.initialize(
                x = firer.position.x + direction.x * firer.radius,
                y = firer.position.y + direction.y * firer.radius,
                vx = direction.x * projectileSpeed,
                vy = direction.y * projectileSpeed,
                projectileType = ProjectileType.BULLET,
                projectileDamage = damage,
                projectileLifetime = KnobsWeapons.scatterLifetime.value
            )
            projectile.isEnemyProjectile = firer.isEnemyFirer
            projectile.weaponId = id
            projectile.radius = KnobsWeapons.scatterPelletRadius.value
            projectile.color = ShipDefinitions.getWeaponColor("scatter_shot", state.isCorruptionRun)
        }

        cooldownTimer = getCooldown(state)
    }

    override fun canEvolve(state: GameState): Boolean {
        return level >= GameConfig.WEAPON_MAX_LEVEL &&
               state.getPassiveStacks("vampiric_core") > 0
    }

    override fun getEvolutionId(): String = "leech_burst"
    override fun getRequiredPassive(): String = "vampiric_core"
}
