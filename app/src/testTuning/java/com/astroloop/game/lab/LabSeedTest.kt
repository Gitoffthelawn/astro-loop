package com.astroloop.game.lab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.StoryStateManager
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LabSeedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `seedIfNeeded initializes a complete Astro Loop save`() {
        val p = PersistenceManager(context)

        LabSeed.seedIfNeeded(p)

        assertTrue("isAstroLoop should be true", StoryStateManager.isAstroLoop(p))
        assertTrue("hasDesertGoodEnding should be true", p.hasDesertGoodEnding())
        assertTrue("isIntroDone should be true", p.isIntroDone())
        assertEquals("damage upgrade should be 5", 5, p.getUpgradeLevel("damage"))
        assertTrue("isLabSeeded should be true", p.isLabSeeded())
    }

    @Test
    fun `seedIfNeeded does not override manually changed values on second call`() {
        val p = PersistenceManager(context)

        // First seed
        LabSeed.seedIfNeeded(p)

        // Manually change a value
        p.setUpgradeLevel("damage", 2)
        assertEquals("damage should be 2 after manual change", 2, p.getUpgradeLevel("damage"))

        // Second seed should not change it
        LabSeed.seedIfNeeded(p)

        assertEquals("damage should still be 2 after second seed", 2, p.getUpgradeLevel("damage"))
    }

    @Test
    fun `the seed survives the game's story migration and boot heal`() {
        val p = PersistenceManager(context)

        LabSeed.seedIfNeeded(p)
        p.migrateStoryState()
        p.healDesertGoodEnding()

        assertTrue(StoryStateManager.isAstroLoop(p))
    }

    @Test
    fun `a save seeded the old way is repaired on the next start`() {
        val p = PersistenceManager(context)
        p.setLabSeeded()
        p.setStoryStageCode(com.astroloop.game.core.StoryStage.NORMAL.code)

        LabSeed.seedIfNeeded(p)
        LabSeed.ensureAstroLoop(p)
        p.migrateStoryState()
        p.healDesertGoodEnding()

        assertTrue(StoryStateManager.isAstroLoop(p))
        assertTrue(p.hasDesertGoodEnding())
    }
}
