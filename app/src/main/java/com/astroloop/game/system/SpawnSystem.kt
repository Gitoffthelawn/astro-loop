package com.astroloop.game.system

import com.astroloop.game.core.Camera
import com.astroloop.game.core.GameConfig
import com.astroloop.game.tuning.KnobsField
import com.astroloop.game.core.GameState
import com.astroloop.game.entity.*
import com.astroloop.game.tuning.KnobsAsteroids
import com.astroloop.game.util.Vector2
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class SpawnSystem(
    private val asteroidPool: EntityPool<Asteroid>
) {
    private var spawnTimer: Float = 0f
    private var screenWidth: Float = 0f
    private var screenHeight: Float = 0f

    companion object {
        /** Minutes of survival before asteroid health starts compounding. */
        const val HEALTH_RAMP_START_MINUTES = 8f

        /** Minutes for asteroid health to double once the ramp has started. */
        const val HEALTH_RAMP_DOUBLING_MINUTES = 4.5f

        const val DAMAGE_RAMP_START_MINUTES = 8f
        const val DAMAGE_RAMP_PER_MINUTE = 6f
        const val DAMAGE_RAMP_MAX_BONUS = 100f

        fun asteroidCount(mult: Float): Int = mult.roundToInt().coerceIn(1, 3)

        fun asteroidSpeedFactor(survivalTime: Float, mult: Float): Float {
            val speedMod = 1f + (survivalTime / 60f) * KnobsField.speedGrowth.value
            return (speedMod * mult).coerceAtMost(KnobsField.maxSpeedFactor.value)
        }

        /**
         * Health multiplier applied to an asteroid at spawn — **Astro Loop mode only**.
         *
         * Astro Loop is the endless mode the player scores a best time in, so a run that cannot
         * end is a broken scoreboard. Every other difficulty lever clamps: count reaches its cap
         * of 3 around 2.5 minutes, speed hits ASTEROID_MAX_SPEED_FACTOR, and the spawn interval
         * floors at ASTEROID_MIN_SPAWN_RATE around 11.3 minutes. From there nothing about a run
         * changes, which is why a strong build could survive indefinitely.
         *
         * Health is the one unclamped lever, so it carries the late game alone and is
         * **deliberately uncapped**. It compounds rather than growing linearly: player damage is
         * effectively fixed once a build maxes out around 10 minutes, so a linear ramp would only
         * produce a long flat tail before the same inevitable loss.
         *
         * Not applied to normal runs or the corruption run, whose pacing is authored elsewhere.
         */
        fun asteroidHealthFactor(survivalTime: Float, astroLoopMode: Boolean): Float {
            if (!astroLoopMode) return 1f
            val minutes = survivalTime / 60f
            if (minutes <= KnobsField.healthRampStart.value) return 1f
            val doublings = (minutes - KnobsField.healthRampStart.value) / KnobsField.healthRampDoubling.value
            return 2f.pow(doublings)
        }

        /**
         * Flat contact-damage bonus applied to an asteroid at spawn — **Astro Loop mode only**.
         *
         * The companion to [asteroidHealthFactor]. Health alone could make a run unwinnable without
         * ever making it losable: a maxed build eventually cannot break anything, but a field of
         * 20-damage rocks cannot finish it either, so the run just stops resolving. Damage supplies
         * the other half of the ending.
         *
         * Additive and capped, where health is multiplicative and uncapped, and deliberately so:
         * health has to outrun a damage curve that flattens, while contact damage is measured
         * against a health pool that barely moves — 50 base plus 50 from Salvage Plate, with 50
         * more of shield behind it. An uncapped damage ramp would just move the wall.
         *
         * Starts where the health ramp starts, because that is where the arithmetic breaks. At
         * baseline 20 damage the invulnerability window allows 13.3 DPS through, while a maxed
         * Vampiric Core heals 15 HP/s — the player cannot lose, which is what every endless-run
         * report describes. The slope is set so a 16-minute run is genuinely hard: 68 damage a
         * touch, about five seconds of sustained contact to die through full healing. The cap lands
         * at minute 24.7, at 120 a touch — two touches kill a fully upgraded hull.
         *
         * Not applied to normal or corruption runs, which end at the ten-minute boss.
         */
        fun asteroidDamageBonus(survivalTime: Float, astroLoopMode: Boolean): Float {
            if (!astroLoopMode) return 0f
            val minutes = survivalTime / 60f
            if (minutes <= KnobsField.damageRampStart.value) return 0f
            return ((minutes - KnobsField.damageRampStart.value) * KnobsField.damageRampPerMinute.value)
                .coerceAtMost(KnobsField.damageRampMax.value)
        }
    }

    fun initialize(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
        spawnTimer = 0f
    }

    fun update(deltaTime: Float, state: GameState, ship: Ship, camera: Camera? = null): List<Asteroid> {
        spawnTimer -= deltaTime

        val spawnedAsteroids = mutableListOf<Asteroid>()

        if (spawnTimer <= 0f) {
            val spawnRate = getSpawnRate(state)
            spawnTimer = spawnRate

            // Spawn 1-3 asteroids based on difficulty
            val count = asteroidCount(state.difficultyMultiplier)
            repeat(count) {
                val asteroid = spawnAsteroid(state, ship, camera)
                if (asteroid != null) {
                    spawnedAsteroids.add(asteroid)
                }
            }
        }

        return spawnedAsteroids
    }

    private fun getSpawnRate(state: GameState): Float {
        val baseRate = KnobsField.spawnInterval.value
        val reduction = state.survivalTime / 60f * KnobsField.spawnIntervalDecay.value
        var rate = (baseRate - reduction).coerceAtLeast(KnobsField.spawnIntervalMin.value)
        // Halve asteroid spawns during corruption run — focus on crew encounters
        if (state.hasCrystalPowers) {
            rate *= 2f
        }
        return rate
    }

    private fun spawnAsteroid(state: GameState, ship: Ship, camera: Camera? = null): Asteroid? {
        val asteroid = asteroidPool.obtain()

        // Determine spawn position (off-screen, relative to camera)
        val spawnPos = getSpawnPosition(ship, camera)

        // Determine asteroid type based on time
        val type = getRandomAsteroidType(state.survivalTime)

        // Determine direction toward ship
        val direction = Vector2(ship.position.x - spawnPos.x, ship.position.y - spawnPos.y).normalize()

        asteroid.initialize(
            x = spawnPos.x,
            y = spawnPos.y,
            asteroidSize = AsteroidSize.LARGE,
            asteroidType = type,
            direction = direction
        )

        // Apply difficulty speed scaling (capped at ASTEROID_MAX_SPEED_FACTOR)
        asteroid.velocity.mul(asteroidSpeedFactor(state.survivalTime, state.difficultyMultiplier))

        // Apply the Astro Loop endless health ramp (uncapped, no-op in every other mode)
        asteroid.scaleHealth(asteroidHealthFactor(state.survivalTime, state.astroLoopMode))
        asteroid.damageBonus = asteroidDamageBonus(state.survivalTime, state.astroLoopMode)

        return asteroid
    }

    private fun getSpawnPosition(ship: Ship, camera: Camera? = null): Vector2 {
        val margin = GameConfig.ASTEROID_SPAWN_MARGIN

        // If we have a camera, spawn relative to camera view
        if (camera != null) {
            val left = camera.getVisibleLeft()
            val right = camera.getVisibleRight()
            val top = camera.getVisibleTop()
            val bottom = camera.getVisibleBottom()

            // Choose random edge of camera view
            return when (Random.nextInt(4)) {
                0 -> Vector2(left + Random.nextFloat() * screenWidth, top - margin) // Top
                1 -> Vector2(left + Random.nextFloat() * screenWidth, bottom + margin) // Bottom
                2 -> Vector2(left - margin, top + Random.nextFloat() * screenHeight) // Left
                else -> Vector2(right + margin, top + Random.nextFloat() * screenHeight) // Right
            }
        }

        // Fallback: spawn relative to screen (original behavior)
        return when (Random.nextInt(4)) {
            0 -> Vector2(Random.nextFloat() * screenWidth, -margin) // Top
            1 -> Vector2(Random.nextFloat() * screenWidth, screenHeight + margin) // Bottom
            2 -> Vector2(-margin, Random.nextFloat() * screenHeight) // Left
            else -> Vector2(screenWidth + margin, Random.nextFloat() * screenHeight) // Right
        }
    }

    private fun getRandomAsteroidType(survivalTime: Float): AsteroidType {
        val availableTypes = AsteroidType.values().filter { type ->
            val unlock = KnobsAsteroids.type(type).unlockSeconds
            unlock == null || survivalTime >= unlock.value
        }

        // Weight toward basic rock type
        val weights = availableTypes.map { KnobsAsteroids.type(it).spawnWeight.value }

        val totalWeight = weights.sum()
        if (totalWeight <= 0) return AsteroidType.ROCK
        var random = Random.nextInt(totalWeight)

        for ((index, weight) in weights.withIndex()) {
            random -= weight
            if (random < 0) {
                return availableTypes[index]
            }
        }

        return AsteroidType.ROCK
    }

    /**
     * The pieces a destroyed [parent] breaks into.
     *
     * [state] is here for the health ramp, which this path used to miss entirely: `scaleHealth`
     * was called only in `spawnAsteroid`, so at 25 minutes a LARGE carried 675 health and the two
     * MEDIUMs it produced carried 25. A maxed build spends most of its time shooting fragments, so
     * the Endless ramp was doing a fraction of the work its constant implies.
     *
     * The factor comes from the clock, exactly as it does for a fresh spawn, rather than being
     * inherited from the parent — one rule, and every asteroid on the field at minute T is worth
     * minute T. Inheriting would let a rock that survived a long time seed weak fragments into the
     * part of the run the ramp exists to make lethal.
     */
    fun spawnSplitAsteroids(parent: Asteroid, state: GameState): List<Asteroid> {
        if (!parent.shouldSplit()) return emptyList()

        val splitAsteroids = mutableListOf<Asteroid>()
        val count = parent.getSplitCount()
        val nextSize = parent.getNextSize()

        for (i in 0 until count) {
            val asteroid = asteroidPool.obtain()

            // Random direction away from parent center
            val angle = (2 * PI * i / count + Random.nextFloat() * 0.5f).toFloat()
            val direction = Vector2.fromAngle(angle)

            asteroid.initialize(
                x = parent.position.x + direction.x * parent.radius,
                y = parent.position.y + direction.y * parent.radius,
                asteroidSize = nextSize,
                asteroidType = parent.type,
                direction = direction
            )

            // Inherit some of parent velocity
            asteroid.velocity.add(parent.velocity.x * 0.3f, parent.velocity.y * 0.3f)

            // Same ramp a fresh spawn gets — see the note on this function.
            asteroid.scaleHealth(asteroidHealthFactor(state.survivalTime, state.astroLoopMode))
            asteroid.damageBonus = asteroidDamageBonus(state.survivalTime, state.astroLoopMode)

            // Brief immunity so clip weapons (SolarStorm, NovaBlast) don't instantly destroy children
            asteroid.fragmentImmunityTimer = 0.1f

            splitAsteroids.add(asteroid)
        }

        return splitAsteroids
    }

    fun reset() {
        spawnTimer = KnobsField.spawnInterval.value
    }
}
