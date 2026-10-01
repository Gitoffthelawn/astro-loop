package com.astroloop.game.entity

import com.astroloop.game.core.GameConfig
import com.astroloop.game.tuning.KnobsAsteroids
import com.astroloop.game.tuning.KnobsField
import com.astroloop.game.util.Vector2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class AsteroidType {
    ROCK,       // Standard, splits on death
    ICE,        // Faster, shatters into many pieces
    METAL,      // Slower, takes 2 hits
    VOLATILE,   // Explodes on death, damages nearby
    MAGNETIC,   // Pulls toward player
    TRAIL       // Leaves damaging wake behind
}

enum class AsteroidSize {
    LARGE,
    MEDIUM,
    SMALL
}

class Asteroid : Entity() {

    companion object {
        /** Contact damage before the Astro Loop ramp. */
        const val BASE_CONTACT_DAMAGE = 20f
    }

    var type: AsteroidType = AsteroidType.ROCK
    var size: AsteroidSize = AsteroidSize.LARGE
    var shapePoints: FloatArray = FloatArray(0)
    var damage: Float = BASE_CONTACT_DAMAGE
    /** Astro Loop damage ramp, applied at spawn. See SpawnSystem.asteroidDamageBonus. */
    var damageBonus: Float = 0f
    var fragmentImmunityTimer: Float = 0f

    data class TrailPoint(val x: Float, val y: Float, val timestamp: Float)
    val trailPoints: MutableList<TrailPoint> = mutableListOf()
    private var lastTrailTime: Float = 0f

    fun updateTrail(gameTime: Float) {
        if (type != AsteroidType.TRAIL || !isActive) return
        if (gameTime - lastTrailTime >= 0.05f) {
            trailPoints.add(TrailPoint(position.x, position.y, gameTime))
            lastTrailTime = gameTime
        }
        val trailLifetime = getTrailLifetime()
        trailPoints.removeAll { gameTime - it.timestamp > trailLifetime }
    }

    fun getTrailLifetime(): Float = KnobsAsteroids.size(size).trailLifetime.value

    fun getTrailWidth(): Float = KnobsAsteroids.size(size).trailWidth.value

    /** Contact damage including the Astro Loop ramp — the number the ship actually takes. */
    fun getContactDamage(): Float = damage + damageBonus

    /**
     * How much harder this asteroid hits than a baseline one — 1.0 before the Astro Loop ramp.
     *
     * Trail wakes and volatile blasts scale by this rather than by [damageBonus] directly. A flat
     * bonus would add the same number to a SMALL fragment's 3-damage wake as to a LARGE's 15,
     * flattening the size ordering the player reads the field by — at minute 16 they would be 51
     * and 63, all but identical. Proportional keeps a small rock small.
     */
    fun getDamageScale(): Float = getContactDamage() / BASE_CONTACT_DAMAGE

    fun getTrailDamage(): Float = getDamageScale() * KnobsAsteroids.size(size).trailDamage.value

    fun initialize(
        x: Float,
        y: Float,
        asteroidSize: AsteroidSize,
        asteroidType: AsteroidType = AsteroidType.ROCK,
        direction: Vector2? = null
    ) {
        position.set(x, y)
        size = asteroidSize
        type = asteroidType

        val sizeKnobs = KnobsAsteroids.size(size)
        val typeKnobs = KnobsAsteroids.type(type)

        radius = sizeKnobs.radius.value
        val baseHealth = sizeKnobs.hp.value
        maxHealth = baseHealth * typeKnobs.hpMultiplier.value
        health = maxHealth

        val speedMultiplier = typeKnobs.speedMultiplier.value
        val speed = KnobsField.baseSpeed.value * speedMultiplier * sizeKnobs.speedFactor.value

        // Set velocity
        if (direction != null) {
            velocity.set(direction).normalize().mul(speed)
        } else {
            val angle = Random.nextFloat() * 2 * PI.toFloat()
            velocity.set(Vector2.fromAngle(angle, speed))
        }

        // Random rotation
        rotationSpeed = (Random.nextFloat() - 0.5f) * 3f

        // Generate random asteroid shape
        generateShape()

        isActive = true
    }

    private fun generateShape() {
        val numPoints = when (type) {
            AsteroidType.ICE -> Random.nextInt(4, 7)      // Crystalline - fewer, sharper points
            AsteroidType.METAL -> Random.nextInt(5, 8)    // Angular geometric
            else -> Random.nextInt(7, 12)                  // Irregular polygon
        }

        shapePoints = FloatArray(numPoints * 2)

        for (i in 0 until numPoints) {
            val angle = (i.toFloat() / numPoints) * 2 * PI.toFloat()
            val variance = when (type) {
                AsteroidType.ICE -> 0.15f   // Less variance for crystalline
                AsteroidType.METAL -> 0.1f  // Even less for geometric
                else -> 0.3f                 // More irregular for rock
            }
            val r = radius * (1f - variance + Random.nextFloat() * variance * 2)
            shapePoints[i * 2] = cos(angle) * r
            shapePoints[i * 2 + 1] = sin(angle) * r
        }
    }

    /**
     * Multiply this asteroid's health, preserving the size and type scaling already applied by
     * [initialize]. Call at spawn only: it lifts the ceiling and refills to it, so a scaled
     * asteroid enters the field undamaged rather than appearing to have taken a hit.
     */
    fun scaleHealth(factor: Float) {
        maxHealth *= factor
        health = maxHealth
    }

    /**
     * Claim this asteroid's destruction, once and only once.
     *
     * `CollisionSystem` gathers every hit for the frame before any damage is applied, and
     * `takeDamage` reports `health <= 0` rather than "died on this call" — so a piercing volley,
     * a saw's per-entity ticks, or two damage systems in the same frame all see the same rock as
     * freshly destroyed. Each redundant destruction used to run the full loot path: two more
     * fragments, a yen pickup, and a drop roll.
     *
     * @return true for the caller that actually destroyed it, false for everyone after.
     */
    fun claimDestruction(): Boolean {
        if (!isActive) return false
        isActive = false
        return true
    }

    fun shouldSplit(): Boolean {
        return size != AsteroidSize.SMALL && type != AsteroidType.VOLATILE
    }

    fun getSplitCount(): Int {
        return when (type) {
            AsteroidType.ICE -> {
                // Ordered here so a tuning that crosses the two bounds still splits.
                val a = KnobsAsteroids.iceSplitMin.value
                val b = KnobsAsteroids.iceSplitMax.value
                Random.nextInt(minOf(a, b), maxOf(a, b) + 1)  // shatters into many
            }
            else -> KnobsAsteroids.splitCount.value
        }
    }

    fun getNextSize(): AsteroidSize {
        return when (size) {
            AsteroidSize.LARGE -> AsteroidSize.MEDIUM
            AsteroidSize.MEDIUM -> AsteroidSize.SMALL
            AsteroidSize.SMALL -> AsteroidSize.SMALL // Won't be called
        }
    }

    fun getExplosionRadius(): Float {
        return if (type == AsteroidType.VOLATILE) {
            radius * KnobsAsteroids.volatileBlastRadiusFactor.value
        } else {
            0f
        }
    }

    fun getExplosionDamage(): Float {
        return if (type == AsteroidType.VOLATILE) {
            getContactDamage() * KnobsAsteroids.volatileBlastDamageFactor.value
        } else {
            0f
        }
    }

    fun getMagneticPullStrength(): Float {
        return if (type == AsteroidType.MAGNETIC) {
            KnobsAsteroids.magneticPullStrength.value * KnobsAsteroids.size(size).magneticPullFactor.value
        } else {
            0f
        }
    }

    fun getColor(): Int {
        return when (type) {
            AsteroidType.ROCK -> GameConfig.COLOR_ASTEROID
            AsteroidType.ICE -> GameConfig.COLOR_ASTEROID_ICE
            AsteroidType.METAL -> GameConfig.COLOR_ASTEROID_METAL
            AsteroidType.VOLATILE -> GameConfig.COLOR_ASTEROID_VOLATILE
            AsteroidType.MAGNETIC -> GameConfig.COLOR_ASTEROID_MAGNETIC
            AsteroidType.TRAIL -> GameConfig.COLOR_ASTEROID_TRAIL
        }
    }

    override fun reset() {
        super.reset()
        type = AsteroidType.ROCK
        size = AsteroidSize.LARGE
        shapePoints = FloatArray(0)
        trailPoints.clear()
        lastTrailTime = 0f
        fragmentImmunityTimer = 0f
        damage = KnobsField.contactDamage.value
        damageBonus = 0f
    }
}
