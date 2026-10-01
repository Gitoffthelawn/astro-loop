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
import kotlin.random.Random
import com.astroloop.game.tuning.KnobsWeapons

class FlakBarrage : Weapon(
    id = "flak_barrage",
    name = "Flak Barrage"
) {
    override val knobs = KnobsWeapons.flakBarrage

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

        for (i in 0 until count) {
            // Wide spread with slight random jitter (matches FlakCannon pattern)
            val spreadAngle = (i - (count - 1) / 2f) * KnobsWeapons.barrageSpread.value
            val angle = firer.rotation + spreadAngle + (Random.nextFloat() - 0.5f) * KnobsWeapons.barrageJitter.value
            val direction = Vector2.fromAngle(angle)

            val projectile = projectilePool.obtain()
            projectile.initialize(
                x = firer.position.x + direction.x * firer.radius,
                y = firer.position.y + direction.y * firer.radius,
                vx = direction.x * speed,
                vy = direction.y * speed,
                projectileType = ProjectileType.FLAK,
                projectileDamage = damage,
                projectileLifetime = KnobsWeapons.barrageFuse.value
            )
            projectile.isEnemyProjectile = firer.isEnemyFirer
            projectile.weaponId = id
            projectile.radius = KnobsWeapons.barrageShellRadius.value
            projectile.explodeOnDeath = true
            projectile.explosionRadius = KnobsWeapons.barrageExplosionRadius.value * areaOf(state)
            projectile.explosionDamage = damage * KnobsWeapons.barrageExplosionFraction.value
            projectile.proximityFuse = true
            projectile.color = ShipDefinitions.getEvolutionColor("flak_cannon", state.isCorruptionRun)
        }

        cooldownTimer = getCooldown(state)
    }
}
