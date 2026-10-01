package com.astroloop.game.tuning

import com.astroloop.game.core.GameConfig

/**
 * Astro Loop's upgrade and evolution drops. Astro Loop has no enemy upgrade drops and no elite
 * diamonds (regular enemies do not spawn in it), so asteroids are the only source.
 */
object KnobsDrops {
    private val g = KnobGroup("Drops", "drops")
    val initialChance = g.float("initial_chance", "Upgrade drop chance at start", GameConfig.ASTRO_LOOP_UPGRADE_DROP_INITIAL, min = 0f, max = 1f)
    val baselineChance = g.float("baseline_chance", "Upgrade drop chance, floor", GameConfig.ASTRO_LOOP_UPGRADE_DROP_BASELINE, min = 0f, max = 1f)
    val decayPerPickup = g.float("decay_per_pickup", "Chance lost per upgrade dropped", GameConfig.ASTEROID_UPGRADE_DROP_DECREASE, min = 0f, max = 0.2f)
    val cooldown = g.float("cooldown", "Seconds between drops", GameConfig.ASTRO_LOOP_UPGRADE_DROP_COOLDOWN, min = 0f, max = 120f)
    val earlyCooldown = g.float("early_cooldown", "Seconds between drops, early game", GameConfig.ASTRO_LOOP_UPGRADE_EARLY_COOLDOWN, min = 0f, max = 120f)
    val diamondChance = g.float("diamond_chance", "Evolution diamond chance per asteroid", 0.05f, min = 0f, max = 1f)
}
