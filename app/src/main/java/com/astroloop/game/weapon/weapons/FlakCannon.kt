package com.astroloop.game.weapon.weapons

import com.astroloop.game.core.GameState
import com.astroloop.game.entity.Entity
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.Projectile
import com.astroloop.game.entity.ProjectileType
import com.astroloop.game.entity.Firer
import com.astroloop.game.util.Vector2
import com.astroloop.game.core.GameConfig
import com.astroloop.game.data.ShipDefinitions
import com.astroloop.game.weapon.Weapon
import kotlin.random.Random
import com.astroloop.game.tuning.KnobsWeapons

class FlakCannon : Weapon(
    id = "flak_cannon",
    name = "Flak Cannon"
) {
    override val knobs = KnobsWeapons.flakCannon

    override fun getDamage(state: GameState): Float = baseDamage * state.damageMultiplier

    override fun getProjectileCount(state: GameState): Int = baseProjectileCount + (level - 1) + state.extraProjectiles

    private fun getExplosionRadius(state: GameState): Float = KnobsWeapons.flakExplosionRadius.value * areaOf(state)

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
        val explosionRadius = getExplosionRadius(state)

        for (i in 0 until count) {
            val spreadAngle = (i - (count - 1) / 2f) * KnobsWeapons.flakSpread.value
            val angle = firer.rotation + spreadAngle + (Random.nextFloat() - 0.5f) * KnobsWeapons.flakJitter.value
            val direction = Vector2.fromAngle(angle)

            val projectile = projectilePool.obtain()
            projectile.initialize(
                x = firer.position.x + direction.x * firer.radius,
                y = firer.position.y + direction.y * firer.radius,
                vx = direction.x * speed,
                vy = direction.y * speed,
                projectileType = ProjectileType.FLAK,
                projectileDamage = damage,
                projectileLifetime = KnobsWeapons.flakFuse.value // Explodes after traveling a bit
            )
            projectile.isEnemyProjectile = firer.isEnemyFirer
            projectile.weaponId = id
            projectile.radius = KnobsWeapons.flakShellRadius.value
            projectile.explodeOnDeath = true
            projectile.explosionRadius = explosionRadius
            projectile.explosionDamage = damage * KnobsWeapons.flakExplosionFraction.value
            projectile.proximityFuse = true
            projectile.color = ShipDefinitions.getWeaponColor("flak_cannon", state.isCorruptionRun)
        }

        cooldownTimer = getCooldown(state)
    }

    override fun canEvolve(state: GameState): Boolean {
        return level >= GameConfig.WEAPON_MAX_LEVEL &&
               state.getPassiveStacks("extra_weapon_slot") > 0
    }

    override fun getEvolutionId(): String = "flak_barrage"
    override fun getRequiredPassive(): String = "extra_weapon_slot"
}
