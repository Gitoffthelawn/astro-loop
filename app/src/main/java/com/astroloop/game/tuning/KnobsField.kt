package com.astroloop.game.tuning

import com.astroloop.game.core.GameConfig
import com.astroloop.game.entity.Asteroid
import com.astroloop.game.system.DifficultySystem
import com.astroloop.game.system.SpawnSystem

/**
 * The asteroid field as a whole: how often and how fast rocks come, and the Astro Loop curve and
 * ramps that make the late game lethal. The normal-mode curve is not a knob — the Lab only flies
 * Astro Loop.
 */
object KnobsField {
    private val spawn = KnobGroup("Asteroids/Field/Spawning", "field")
    val spawnInterval = spawn.float("spawn_interval", "Seconds between waves at start", GameConfig.ASTEROID_INITIAL_SPAWN_RATE)
    val spawnIntervalMin = spawn.float("spawn_interval_min", "Seconds between waves, floor", GameConfig.ASTEROID_MIN_SPAWN_RATE)
    val spawnIntervalDecay = spawn.float("spawn_interval_decay", "Interval shrink per minute", GameConfig.DIFFICULTY_SPAWN_RATE_INCREASE, min = 0f, max = 1f)
    val baseSpeed = spawn.float("base_speed", "Base speed", GameConfig.ASTEROID_BASE_SPEED, applies = Applies.NEW_SPAWNS)
    val speedGrowth = spawn.float("speed_growth", "Speed + per minute", GameConfig.DIFFICULTY_SPEED_INCREASE, min = 0f, max = 1f, applies = Applies.NEW_SPAWNS)
    val maxSpeedFactor = spawn.float("max_speed_factor", "Speed × ceiling", GameConfig.ASTEROID_MAX_SPEED_FACTOR, applies = Applies.NEW_SPAWNS)
    val contactDamage = spawn.float("contact_damage", "Contact damage", Asteroid.BASE_CONTACT_DAMAGE, applies = Applies.NEW_SPAWNS)

    private val curve = KnobGroup("Asteroids/Field/Astro Loop curve", "field")
    val peakMinute = curve.float("peak_minute", "Peak at minute", DifficultySystem.PEAK_MINUTES, min = 1f, max = 30f)
    val peakDifficulty = curve.float("peak_difficulty", "Peak difficulty", DifficultySystem.PEAK_DIFFICULTY, min = 1.1f, max = 20f)
    val postPeakSlope = curve.float("post_peak_slope", "Difficulty + per minute after peak", DifficultySystem.LINEAR_SLOPE, min = 0f, max = 4f)

    private val ramps = KnobGroup("Asteroids/Field/Late-game ramps", "field")
    val healthRampStart = ramps.float("health_ramp_start", "Health ramp starts (min)", SpawnSystem.HEALTH_RAMP_START_MINUTES, min = 0f, max = 60f, applies = Applies.NEW_SPAWNS)
    val healthRampDoubling = ramps.float("health_ramp_doubling", "Health doubles every (min)", SpawnSystem.HEALTH_RAMP_DOUBLING_MINUTES, applies = Applies.NEW_SPAWNS)
    val damageRampStart = ramps.float("damage_ramp_start", "Damage ramp starts (min)", SpawnSystem.DAMAGE_RAMP_START_MINUTES, min = 0f, max = 60f, applies = Applies.NEW_SPAWNS)
    val damageRampPerMinute = ramps.float("damage_ramp_per_minute", "Contact damage + per minute", SpawnSystem.DAMAGE_RAMP_PER_MINUTE, min = 0f, max = 60f, applies = Applies.NEW_SPAWNS)
    val damageRampMax = ramps.float("damage_ramp_max", "Contact damage + ceiling", SpawnSystem.DAMAGE_RAMP_MAX_BONUS, min = 0f, max = 1000f, applies = Applies.NEW_SPAWNS)
}
