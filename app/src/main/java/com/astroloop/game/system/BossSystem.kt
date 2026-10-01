package com.astroloop.game.system

import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.GameState
import com.astroloop.game.core.SoundManager
import com.astroloop.game.tuning.KnobsPassives
import com.astroloop.game.entity.*
import com.astroloop.game.entity.VisualEffectManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class BossSystem(
    private val state: GameState,
    private val boss: Boss,
    private val ship: Ship,
    private val weaponSystem: WeaponSystem,
    private val visualEffects: VisualEffectManager,
    private val onPlayerDeath: () -> Unit
) {

    fun spawnBoss(
        shipId: String,
        pilotId: String,
        asteroids: List<Asteroid>,
        enemies: List<EnemyShip>,
        powerUps: List<PowerUp>
    ) {
        state.bossSpawned = true
        state.bossActive = true

        // Destroy all asteroids with explosion effects
        for (asteroid in asteroids) {
            visualEffects.addExplosion(
                asteroid.position.x,
                asteroid.position.y,
                asteroid.radius * 1.5f,
                0xFFFF4400.toInt()  // Dark red/orange explosions
            )
            asteroid.isActive = false
        }

        // Destroy all enemies
        for (enemy in enemies) {
            visualEffects.addExplosion(
                enemy.position.x,
                enemy.position.y,
                enemy.radius * 2f,
                0xFFFF4400.toInt()
            )
            enemy.isActive = false
        }

        // Clear all existing power-ups from battlefield
        for (powerUp in powerUps) {
            powerUp.isActive = false
        }

        // Strip upgrades but keep starting weapon + starting passive
        val startingWeaponId = com.astroloop.game.data.ShipDefinitions.getShip(shipId)?.startingWeaponId
        val startingPassiveId = com.astroloop.game.data.PilotDefinitions.getPilot(pilotId)?.startingPassiveId

        // Instant-max passives get L5, others get L1
        val defaultPassiveLevel = if (startingPassiveId != null && GameState.INSTANT_MAX_PASSIVES.contains(startingPassiveId))
            GameConfig.PASSIVE_MAX_STACKS else 1

        weaponSystem.reset()
        state.weaponLevels.clear()
        state.passiveStacks.clear()
        state.evolvedWeapons.clear()
        state.phoenixUsed = false
        state.totalUpgradesCollected = 0

        // Restore starting weapon at L1
        if (startingWeaponId != null) {
            state.weaponLevels[startingWeaponId] = 1
            weaponSystem.addWeapon(startingWeaponId, state)
        }

        // Restore starting passive at default level (skip for crystal Astro — TB-26 is gone)
        if (startingPassiveId != null && !state.hasCrystalPowers) {
            state.passiveStacks[startingPassiveId] = defaultPassiveLevel
        }

        state.recalculateStats()

        // Reset ship to base stats and full health (including permanent upgrades)
        val baseHealth = GameConfig.SHIP_BASE_HEALTH + state.getPermanentHealthBonus()
        ship.maxHealth = baseHealth
        ship.health = baseHealth
        ship.makeInvulnerable(3f)  // Brief invulnerability

        // Add dramatic flash effect
        visualEffects.addExplosion(ship.position.x, ship.position.y, 500f, 0xFFFFFFFF.toInt())

        // Spawn boss at edge of screen, facing player
        val spawnDist = 800f  // Larger spawn distance for bigger boss
        val angle = kotlin.random.Random.nextFloat() * 2 * PI.toFloat()
        val spawnX = ship.position.x + cos(angle) * spawnDist
        val spawnY = ship.position.y + sin(angle) * spawnDist

        boss.initialize(spawnX, spawnY, ship)
    }

    fun update(deltaTime: Float, projectiles: List<Projectile>, enemies: List<EnemyShip>, asteroids: List<Asteroid>) {
        boss.update(deltaTime)

        // Fire railgun when ready
        if (boss.wantsToFire) {
            fireBossRailgun()
            boss.railCooldown = Boss.RAIL_COOLDOWN
        }

        // Update recall shot behavior (pause + retarget toward player)
        updateRecallShots(state, deltaTime, projectiles, enemies, asteroids)

        // Check boss collision with player
        if (!ship.isInvulnerable && boss.collidesWith(ship)) {
            ship.takeDamage(50f)  // Contact damage (smaller boss = less devastating)

            // Revenge Protocol: trigger on ship damage
            val revengeStacks = state.passiveStacks["revenge_protocol"] ?: 0
            if (revengeStacks > 0) {
                state.revengeTimer = revengeStacks * KnobsPassives.revengeSecondsPerStack.value
                state.revengeActive = true
            }

            if (ship.health <= 0) {
                onPlayerDeath()
            }
        }
    }

    private fun updateRecallShots(state: GameState, deltaTime: Float, projectiles: List<Projectile>, enemies: List<EnemyShip>, asteroids: List<Asteroid>) {
        for (p in projectiles) {
            if (p.type != ProjectileType.RECALL_SHOT) continue
            if (p.shouldFadeOut) continue

            if (!p.isRecalling) {
                // Trigger recall at screen edge
                val margin = 20f
                val halfW = Boss.recallHalfWidth(state.screenWidth)
                val halfH = state.screenHeight / 2f
                val atEdge = p.position.x < ship.position.x - halfW + margin ||
                    p.position.x > ship.position.x + halfW - margin ||
                    p.position.y < ship.position.y - halfH + margin ||
                    p.position.y > ship.position.y + halfH - margin
                if (atEdge) {
                    p.isRecalling = true
                    p.recallPauseTimer = Boss.RECALL_PAUSE_TIME
                    p.velocity.set(0f, 0f)
                    p.ricochetCount++
                    visualEffects.addHitFlash(p.position.x, p.position.y, 15f, p.color)
                }
            } else {
                p.recallPauseTimer -= deltaTime
                if (p.recallPauseTimer <= 0f) {
                    // Find nearest target based on projectile ownership
                    var targetX = 0f
                    var targetY = 0f
                    var closestDist = Float.MAX_VALUE
                    var found = false

                    if (p.isEnemyProjectile) {
                        // Boss projectile: retarget the player
                        targetX = ship.position.x
                        targetY = ship.position.y
                        found = true
                    } else {
                        // Player projectile: prefer enemies, then boss, then asteroids
                        for (enemy in enemies) {
                            if (!enemy.isActive) continue
                            val dx = enemy.position.x - p.position.x
                            val dy = enemy.position.y - p.position.y
                            val dist = sqrt(dx * dx + dy * dy)
                            if (dist < closestDist) {
                                closestDist = dist
                                targetX = enemy.position.x
                                targetY = enemy.position.y
                                found = true
                            }
                        }
                        // Also consider the boss as a target
                        if (boss.isActive) {
                            val dx = boss.position.x - p.position.x
                            val dy = boss.position.y - p.position.y
                            val dist = sqrt(dx * dx + dy * dy)
                            if (dist < closestDist) {
                                closestDist = dist
                                targetX = boss.position.x
                                targetY = boss.position.y
                                found = true
                            }
                        }
                        // Fallback to asteroids
                        if (!found) {
                            for (asteroid in asteroids) {
                                if (!asteroid.isActive) continue
                                val dx = asteroid.position.x - p.position.x
                                val dy = asteroid.position.y - p.position.y
                                val dist = sqrt(dx * dx + dy * dy)
                                if (dist < closestDist) {
                                    closestDist = dist
                                    targetX = asteroid.position.x
                                    targetY = asteroid.position.y
                                    found = true
                                }
                            }
                        }
                    }

                    if (found) {
                        val dx = targetX - p.position.x
                        val dy = targetY - p.position.y
                        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                        p.velocity.set(dx / dist * Boss.RAIL_SPEED, dy / dist * Boss.RAIL_SPEED)
                    } else {
                        // No target found — deactivate
                        p.isActive = false
                    }
                    p.isRecalling = false
                    p.recallRetargeted = false
                    p.age = 0f  // Reset age so it doesn't expire
                }
            }
        }
    }

    private fun fireBossRailgun() {
        val aimDir = boss.getAimDirection()
        // One report per volley, slightly louder than a regular enemy (they fire at 0.4f)
        SoundManager.playSFX("sfx_weapon_railgun", 0.5f)
        // Two shots of RAIL_DAMAGE, so a volley lands the same 80 the player's Railgun lands in
        // one. That total is the only thing the two weapons share — this is not a tuned-down
        // Railgun, it is a different weapon:
        //   - half the speed (RAIL_SPEED 800 against the Railgun's 2000)
        //   - no piercing set, so Projectile.onHit deactivates it on first contact, where the
        //     player's passes through maxPierces = 10
        //   - RECALL_SHOT, which Projectile.update exempts from lifetime expiry: instead of
        //     leaving the field it stops at the screen edge, waits RECALL_PAUSE_TIME and flies
        //     back, and nothing ever reads ricochetCount, so it returns until it connects.
        // The player does get that boomerang, but on the *corruption* run rather than in Astro
        // Loop — hasCrystalPowers is isCorrupted() — and it is bolted onto ordinary railgun
        // projectiles rather than living in the weapon. See "Crystal power: player railgun recall
        // shots" in GameSurfaceView, which reuses Boss.RECALL_PAUSE_TIME and calls the same
        // Boss.recallHalfWidth this does — the edge test used to be copied into both, and the
        // boss mirrors Astro's powers, so a copy was one edit away from drifting them apart.
        val shotsInBurst = 2
        for (burst in 0 until shotsInBurst) {
            val burstOffset = burst * 15f
            val spawnX = boss.position.x + aimDir.x * (boss.radius + burstOffset)
            val spawnY = boss.position.y + aimDir.y * (boss.radius + burstOffset)
            val projectile = EntityPools.projectiles.obtain()
            projectile.initialize(
                x = spawnX,
                y = spawnY,
                vx = aimDir.x * Boss.RAIL_SPEED,
                vy = aimDir.y * Boss.RAIL_SPEED,
                projectileType = ProjectileType.RECALL_SHOT,
                projectileDamage = Boss.RAIL_DAMAGE,
                projectileLifetime = 4f
            )
            projectile.weaponId = "boss_rail"
            projectile.isEnemyProjectile = true
            projectile.color = Boss.CORRUPTION_COLOR
            projectile.radius = 5f
            projectile.ignoresSpawnShield = true  // railgun always pierces spawn shields

        }
    }
}
