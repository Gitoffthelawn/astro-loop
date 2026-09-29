package com.astroloop.game.weapon.weapons

import com.astroloop.game.core.GameState
import com.astroloop.game.entity.Entity
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.Projectile
import com.astroloop.game.entity.ProjectileType
import com.astroloop.game.entity.Firer
import com.astroloop.game.util.Vector2
import com.astroloop.game.data.ShipDefinitions
import com.astroloop.game.weapon.Weapon
import kotlin.math.PI
import kotlin.random.Random

class LeechBurst : Weapon(
    id = "leech_burst",
    name = "Leech Burst"
) {
    companion object {
        /**
         * The same cone Scatter Shot throws, re-exported rather than redeclared.
         *
         * An evolution that scattered wider than the weapon it replaced would be a downgrade, and
         * the two used to be kept in step by a comment alone.
         */
        val SPREAD_CONE_RADIANS = ScatterShot.SPREAD_CONE_RADIANS
    }

    override val baseDamage = 12f
    override val baseCooldown = 0.5f
    override val baseProjectileSpeed = 550f
    override val baseProjectileCount = 13

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

        // Read from ScatterShot rather than copied, so this cannot drift wider than the weapon it
        // evolves from — an evolution that scattered worse than its base would be a downgrade.
        val spreadAngle = SPREAD_CONE_RADIANS * state.areaMultiplier

        for (i in 0 until count) {
            val angle = firer.rotation + (Random.nextFloat() - 0.5f) * spreadAngle
            val direction = Vector2.fromAngle(angle)

            // Slight speed variation for organic scatter feel
            val projectileSpeed = speed * (0.9f + Random.nextFloat() * 0.2f)

            val projectile = projectilePool.obtain()
            projectile.initialize(
                x = firer.position.x + direction.x * firer.radius,
                y = firer.position.y + direction.y * firer.radius,
                vx = direction.x * projectileSpeed,
                vy = direction.y * projectileSpeed,
                projectileType = ProjectileType.BULLET,
                projectileDamage = damage,
                projectileLifetime = 1.5f
            )
            projectile.isEnemyProjectile = firer.isEnemyFirer
            // weaponId set to "leech_burst" so the hit system can detect and apply healing (2 HP per hit)
            projectile.weaponId = "leech_burst"
            projectile.radius = 3f
            projectile.color = ShipDefinitions.getEvolutionColor("scatter_shot", state.isCorruptionRun)
        }

        cooldownTimer = getCooldown(state)
    }
}
