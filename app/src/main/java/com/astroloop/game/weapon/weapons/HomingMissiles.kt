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
import com.astroloop.game.tuning.KnobsWeapons

class HomingMissiles : Weapon(
    id = "homing_missiles",
    name = "Homing Missiles"
) {
    override val knobs = KnobsWeapons.homingMissiles
    override val beatPhaseOffsetMs: Long = 250L

    override fun getDamage(state: GameState): Float = baseDamage * state.damageMultiplier

    override fun getProjectileCount(state: GameState): Int = baseProjectileCount + (level - 1) + state.extraProjectiles

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

        // Sort targets by distance
        val sortedTargets = targets
            .filter { it.isActive }
            .sortedBy { firer.position.distanceSquared(it.position) }

        for (i in 0 until count) {
            val offsetAngle = (i - (count - 1) / 2f) * KnobsWeapons.homingFan.value
            val angle = firer.rotation + offsetAngle
            val direction = Vector2.fromAngle(angle)

            val projectile = projectilePool.obtain()
            projectile.initialize(
                x = firer.position.x + direction.x * firer.radius,
                y = firer.position.y + direction.y * firer.radius,
                vx = direction.x * speed,
                vy = direction.y * speed,
                projectileType = ProjectileType.MISSILE,
                projectileDamage = damage,
                projectileLifetime = KnobsWeapons.homingLifetime.value
            )
            projectile.isEnemyProjectile = firer.isEnemyFirer
            projectile.weaponId = id
            projectile.homingStrength = KnobsWeapons.homingStrength.value
            projectile.target = sortedTargets.getOrNull(i % sortedTargets.size.coerceAtLeast(1))
            projectile.color = ShipDefinitions.getWeaponColor("homing_missiles", state.isCorruptionRun)
        }

        cooldownTimer = getCooldown(state)
    }

    override fun canEvolve(state: GameState): Boolean {
        return level >= GameConfig.WEAPON_MAX_LEVEL &&
               state.getPassiveStacks("tb26") > 0
    }

    override fun getEvolutionId(): String = "autonomous_ace"
    override fun getRequiredPassive(): String = "tb26"
}
