package com.astroloop.game.tuning

import com.astroloop.game.core.GameConfig
import com.astroloop.game.entity.AsteroidSize
import com.astroloop.game.entity.AsteroidType

/** Asteroid knobs by size and by type. Values are read when an asteroid spawns. */
object KnobsAsteroids {

    class SizeKnobs internal constructor(
        size: String, hp: Float, radius: Float, speedFactor: Float,
        trailDamage: Float, trailWidth: Float, trailLifetime: Float, magneticPullFactor: Float,
    ) {
        private val g = KnobGroup("Asteroids/Sizes/${size.replaceFirstChar { it.uppercase() }}", "asteroid.$size")
        val hp = g.float("hp", "Health", hp, applies = Applies.NEW_SPAWNS)
        val radius = g.float("radius", "Radius", radius, min = radius / 4f, max = radius * 4f, applies = Applies.NEW_SPAWNS)
        val speedFactor = g.float("speed_factor", "Speed ×", speedFactor, applies = Applies.NEW_SPAWNS)
        val trailDamage = g.float("trail_damage", "Trail wake damage", trailDamage)
        val trailWidth = g.float("trail_width", "Trail wake width", trailWidth)
        val trailLifetime = g.float("trail_lifetime", "Trail wake lifetime (s)", trailLifetime)
        val magneticPullFactor = g.float("magnetic_pull_factor", "Magnetic pull ×", magneticPullFactor)
    }

    class TypeKnobs internal constructor(
        type: String, hpMultiplier: Float, speedMultiplier: Float, unlockSeconds: Float?, spawnWeight: Int,
    ) {
        private val g = KnobGroup("Asteroids/Types/${type.replaceFirstChar { it.uppercase() }}", "asteroid.$type")
        val hpMultiplier = g.float("hp_mult", "Health ×", hpMultiplier, applies = Applies.NEW_SPAWNS)
        val speedMultiplier = g.float("speed_mult", "Speed ×", speedMultiplier, applies = Applies.NEW_SPAWNS)
        /** Survival seconds before this type starts spawning; null for rock, which always spawns. */
        val unlockSeconds = unlockSeconds?.let { g.float("unlock_seconds", "Starts spawning at (s)", it, min = 0f, max = 1200f) }
        val spawnWeight = g.int("spawn_weight", "Spawn weight", spawnWeight, min = 0, max = 20)
    }

    private val large = SizeKnobs("large", 50f, GameConfig.ASTEROID_LARGE_SIZE, 0.8f, 15f, 12f, 4f, 1.5f)
    private val medium = SizeKnobs("medium", 25f, GameConfig.ASTEROID_MEDIUM_SIZE, 1.2f, 8f, 6f, 2.5f, 1f)
    private val small = SizeKnobs("small", 10f, GameConfig.ASTEROID_SMALL_SIZE, 1.6f, 3f, 3f, 1.5f, 0.5f)

    fun size(s: AsteroidSize): SizeKnobs = when (s) {
        AsteroidSize.LARGE -> large
        AsteroidSize.MEDIUM -> medium
        AsteroidSize.SMALL -> small
    }

    private val rock = TypeKnobs("rock", 1f, 1f, null, 5)
    private val ice = TypeKnobs("ice", 1f, 1.5f, GameConfig.UNLOCK_ICE_ASTEROIDS, 3)
    private val metal = TypeKnobs("metal", 2f, 0.6f, GameConfig.UNLOCK_METAL_ASTEROIDS, 2)
    private val volatile = TypeKnobs("volatile", 1f, 1f, GameConfig.UNLOCK_VOLATILE_ASTEROIDS, 2)
    private val magnetic = TypeKnobs("magnetic", 1f, 0.4f, GameConfig.UNLOCK_MAGNETIC_ASTEROIDS, 1)
    private val trail = TypeKnobs("trail", 1f, 1f, GameConfig.UNLOCK_TRAIL_ASTEROIDS, 2)

    fun type(t: AsteroidType): TypeKnobs = when (t) {
        AsteroidType.ROCK -> rock
        AsteroidType.ICE -> ice
        AsteroidType.METAL -> metal
        AsteroidType.VOLATILE -> volatile
        AsteroidType.MAGNETIC -> magnetic
        AsteroidType.TRAIL -> trail
    }

    private val splits = KnobGroup("Asteroids/Types/Splitting", "asteroid")
    val splitCount = splits.int("split_count", "Pieces (non-ice)", 2, min = 0, max = 8)
    val iceSplitMin = splits.int("ice.split_min", "Ice pieces, fewest", 3, min = 0, max = 12)
    val iceSplitMax = splits.int("ice.split_max", "Ice pieces, most", 4, min = 0, max = 12)

    private val volatileSpecial = KnobGroup("Asteroids/Types/Volatile", "asteroid.volatile")
    val volatileBlastRadiusFactor = volatileSpecial.float("blast_radius_factor", "Blast radius × own radius", 3f)
    val volatileBlastDamageFactor = volatileSpecial.float("blast_damage_factor", "Blast damage × contact damage", 1.5f)

    private val magneticSpecial = KnobGroup("Asteroids/Types/Magnetic", "asteroid.magnetic")
    val magneticPullStrength = magneticSpecial.float("pull_strength", "Pull strength", 250f)
    val magneticPullRange = magneticSpecial.float("pull_range", "Pull range", 600f)
}
