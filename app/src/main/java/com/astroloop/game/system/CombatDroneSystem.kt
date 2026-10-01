package com.astroloop.game.system

import com.astroloop.game.core.GameState
import com.astroloop.game.core.SoundManager
import com.astroloop.game.data.PassiveDefinitions
import com.astroloop.game.tuning.KnobsPassives
import com.astroloop.game.entity.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class CombatDroneSystem(
    private val state: GameState,
    private val ship: Ship
) {
    val drones = mutableListOf<Drone>()

    fun reset() {
        drones.clear()
    }

    fun update(deltaTime: Float, asteroids: List<Asteroid>, enemies: List<EnemyShip>) {
        syncDroneCount()

        for (drone in drones) {
            if (!drone.isActive) continue

            if (state.droneEvolved && !drone.evolved) {
                drone.applyEvolution()
            }

            val peers = drones.filter { it != drone && it.isActive }
            drone.updateAI(deltaTime, asteroids, enemies, peers)

            if (drone.canFire()) {
                fireDrone(drone)
            }
        }
    }

    fun checkCollisions(asteroids: List<Asteroid>, projectiles: List<Projectile>) {
        for (drone in drones) {
            if (!drone.isActive) continue

            for (asteroid in asteroids) {
                if (!asteroid.isActive) continue
                if (drone.collidesWith(asteroid)) {
                    drone.onHit()
                    break
                }
            }

            for (projectile in projectiles) {
                if (!projectile.isActive || !projectile.isEnemyProjectile) continue
                if (drone.collidesWith(projectile)) {
                    drone.onHit()
                    projectile.isActive = false
                    break
                }
            }
        }
    }

    private fun syncDroneCount() {
        val target = state.droneCount

        while (drones.size < target) {
            val index = drones.size
            val n = target.coerceAtLeast(1)
            val startAngle = index * (2f * Math.PI.toFloat() / n)
            val drone = Drone()
            drone.initialize(ship, startAngle)
            drone.themeColor = droneColorFor(index)
            drones.add(drone)
        }

        while (drones.size > target) {
            drones.removeAt(drones.size - 1)
        }
    }

    private fun droneColorFor(index: Int): Int {
        val hasTb26 = state.getPassiveStacks("tb26") > 0
        return if (index == 0 && hasTb26)
            PassiveDefinitions.getDroneColor("tb26", state.activePilotId, state.astroLoopMode)
        else
            PassiveDefinitions.DRONE_COLOR_COMBAT
    }

    private fun fireDrone(drone: Drone) {
        val direction = drone.getFireDirection() ?: return
        val baseAngle = atan2(direction.y, direction.x)

        if (state.droneEvolved) {
            drone.fireRate = KnobsPassives.droneEvolvedFireRate.value
            val projectile = EntityPools.projectiles.obtain()
            projectile.initialize(
                x = drone.position.x,
                y = drone.position.y,
                vx = cos(baseAngle) * KnobsPassives.droneEvolvedSpeed.value,
                vy = sin(baseAngle) * KnobsPassives.droneEvolvedSpeed.value,
                projectileType = ProjectileType.MISSILE,
                projectileDamage = KnobsPassives.droneEvolvedDamage.value,
                projectileLifetime = 4f
            )
            projectile.weaponId = "autonomous_ace"
            projectile.color = drone.themeColor
            projectile.homingStrength = KnobsPassives.droneEvolvedHoming.value
            projectile.target = drone.currentTarget
        } else {
            drone.fireRate = KnobsPassives.droneFireRate.value
            val isTb26 = drone.themeColor == PassiveDefinitions.DRONE_COLOR_TB26
            val spreadAngle = KnobsPassives.droneSpread.value
            val offsets = if (isTb26) intArrayOf(-2, -1, 0, 1, 2) else intArrayOf(-1, 0, 1)
            for (offset in offsets) {
                val angle = baseAngle + offset * spreadAngle
                val projectile = EntityPools.projectiles.obtain()
                projectile.initialize(
                    x = drone.position.x,
                    y = drone.position.y,
                    vx = cos(angle) * KnobsPassives.droneSpeed.value,
                    vy = sin(angle) * KnobsPassives.droneSpeed.value,
                    projectileType = ProjectileType.BULLET,
                    projectileDamage = KnobsPassives.droneDamage.value,
                    projectileLifetime = 2f
                )
                projectile.weaponId = "tb26"
                projectile.color = drone.themeColor
            }
        }

        drone.fire()
        SoundManager.playSFX("sfx_weapon_drone", 0.3f)
    }
}
