package com.astroloop.game.lab

import com.astroloop.game.core.StoryStage
import com.astroloop.game.data.PersistenceManager

/**
 * The Lab's own save starts as a finished game: Astro Loop, every ship and pilot, every Black
 * Market upgrade at 5, and the good ending flag set. The game's normal boot code then reads a
 * valid Astro Loop save. Runs once.
 */
object LabSeed {
    private val UPGRADES = listOf("health", "shields", "speed", "damage", "crit", "yen_bonus", "salvage", "magnet")

    fun seedIfNeeded(persistence: PersistenceManager) {
        if (persistence.isLabSeeded()) return
        // Mark the save migrated first: the game's boot migration would otherwise recompute the
        // stage from legacy keys that do not exist and overwrite the one written below.
        persistence.migrateStoryState()
        persistence.setStoryStageCode(StoryStage.ASTRO_LOOP.code)
        persistence.setDesertGoodEnding()
        persistence.setIntroDone()
        persistence.unlockAllShipsAndPilots()
        for (id in UPGRADES) persistence.setUpgradeLevel(id, 5)
        persistence.setLabSeeded()
    }

    /** Repairs saves seeded before the migration was marked: runs on every start, after the seed. */
    fun ensureAstroLoop(persistence: PersistenceManager) {
        persistence.migrateStoryState()
        if (persistence.getStoryStageCode() != StoryStage.ASTRO_LOOP.code) {
            persistence.setStoryStageCode(StoryStage.ASTRO_LOOP.code)
            persistence.setDesertGoodEnding()
        }
    }
}
