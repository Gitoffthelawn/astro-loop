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
import kotlin.math.PI
import kotlin.random.Random
import com.astroloop.game.tuning.KnobsWeapons
import com.astroloop.game.tuning.forLevel

class SpaceMines : Weapon(
    id = "space_mines",
    name = "Space Mines"
) {
    override val knobs = KnobsWeapons.spaceMines
    override val beatPhaseOffsetMs: Long = 1500L

    // Stagger mine drops
    private var pendingMineCount: Int = 0
    private var mineSpawnTimer: Float = 0f
    private val mineSpawnDelay: Float get() = KnobsWeapons.minesSpawnDelay.value  // delay between mines
    private var pendingMineData: MineSpawnData? = null

    private data class MineSpawnData(
        val damage: Float,
        val explosionRadius: Float,
        val shipPosition: Vector2,
        val shipVelocity: Vector2,
        val shipRotation: Float,
        val shipRadius: Float,
        val isCorruption: Boolean = false,
        val isEnemy: Boolean = false
    )

    override fun getDamage(state: GameState): Float = baseDamage * state.damageMultiplier

    override fun getProjectileCount(state: GameState): Int {
        return baseProjectileCount + ((level.coerceAtMost(5) - 1) * KnobsWeapons.minesCountGrowth.value).toInt() + state.extraProjectiles
    }

    private fun getExplosionRadius(state: GameState): Float {
        val base = KnobsWeapons.minesExplosionRadius.forLevel(level)
        return base * areaOf(state)
    }

    override fun fire(
        firer: Firer,
        state: GameState,
        projectilePool: EntityPool<Projectile>,
        targets: List<Entity>
    ) {
        if (!canFire()) return

        val damage = getDamage(state)
        val count = getProjectileCount(state)
        val explosionRadius = getExplosionRadius(state)

        // Spawn first mine immediately
        spawnMine(firer, projectilePool, damage, explosionRadius, 0, state.isCorruptionRun)

        // Queue remaining mines if any
        if (count > 1) {
            pendingMineCount = count - 1
            pendingMineData = MineSpawnData(
                damage,
                explosionRadius,
                Vector2(firer.position.x, firer.position.y),
                Vector2(firer.velocity.x, firer.velocity.y),
                firer.rotation,
                firer.radius,
                state.isCorruptionRun,
                firer.isEnemyFirer
            )
            mineSpawnTimer = mineSpawnDelay
        }

        cooldownTimer = getCooldown(state)
    }

    private fun spawnMine(firer: Firer, pool: EntityPool<Projectile>, damage: Float, explosionRadius: Float, index: Int, isCorruption: Boolean = false) {
        val behindAngle = firer.rotation + PI.toFloat()
        val offsetAngle = behindAngle + (Random.nextFloat() - 0.5f) * 0.5f
        val offset = Vector2.fromAngle(offsetAngle, firer.radius + 10f + index * 15f)

        val projectile = pool.obtain()
        projectile.initialize(
            x = firer.position.x + offset.x,
            y = firer.position.y + offset.y,
            vx = -firer.velocity.x * 0.3f,
            vy = -firer.velocity.y * 0.3f,
            projectileType = ProjectileType.MINE,
            projectileDamage = damage,
            projectileLifetime = KnobsWeapons.minesLifetime.value
        )
        projectile.isEnemyProjectile = firer.isEnemyFirer
        projectile.weaponId = id
        projectile.radius = KnobsWeapons.minesRadius.value
        projectile.explodeOnDeath = true
        projectile.explosionRadius = explosionRadius
        projectile.explosionDamage = damage
        projectile.color = ShipDefinitions.getWeaponColor("space_mines", isCorruption)
    }

    private fun spawnMineFromData(pool: EntityPool<Projectile>, data: MineSpawnData, index: Int) {
        val behindAngle = data.shipRotation + PI.toFloat()
        val offsetAngle = behindAngle + (Random.nextFloat() - 0.5f) * 0.5f
        val offset = Vector2.fromAngle(offsetAngle, data.shipRadius + 10f + index * 15f)

        val projectile = pool.obtain()
        projectile.initialize(
            x = data.shipPosition.x + offset.x,
            y = data.shipPosition.y + offset.y,
            vx = -data.shipVelocity.x * 0.3f,
            vy = -data.shipVelocity.y * 0.3f,
            projectileType = ProjectileType.MINE,
            projectileDamage = data.damage,
            projectileLifetime = KnobsWeapons.minesLifetime.value
        )
        projectile.isEnemyProjectile = data.isEnemy
        projectile.weaponId = id
        projectile.radius = KnobsWeapons.minesRadius.value
        projectile.explodeOnDeath = true
        projectile.explosionRadius = data.explosionRadius
        projectile.explosionDamage = data.damage
        projectile.color = ShipDefinitions.getWeaponColor("space_mines", data.isCorruption)
    }

    fun updatePendingMines(deltaTime: Float, projectilePool: EntityPool<Projectile>, state: GameState) {
        if (pendingMineCount > 0 && pendingMineData != null) {
            mineSpawnTimer -= deltaTime
            if (mineSpawnTimer <= 0f) {
                val data = pendingMineData!!
                spawnMineFromData(projectilePool, data, getProjectileCount(state) - pendingMineCount)
                pendingMineCount--
                mineSpawnTimer = mineSpawnDelay
                if (pendingMineCount <= 0) {
                    pendingMineData = null
                }
            }
        }
    }

    override fun canEvolve(state: GameState): Boolean {
        return level >= GameConfig.WEAPON_MAX_LEVEL &&
               state.getPassiveStacks("lucky_star") > 0
    }

    override fun getEvolutionId(): String = "jackpot_mines"
    override fun getRequiredPassive(): String = "lucky_star"
}
