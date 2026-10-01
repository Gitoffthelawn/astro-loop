package com.astroloop.game.lab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.StoryStateManager
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.tuning.KnobsWeapons
import com.astroloop.game.tuning.RunHooks
import com.astroloop.game.tuning.RunSummary
import com.astroloop.game.tuning.Tunables
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LabApp::class)
class LabAppTest {

    @After
    fun tearDown() {
        Tunables.resetAll()
        RunHooks.listener = null
    }

    @Test
    fun `application start installs the listener and seeds the save`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(context is LabApp)
        assertSame(LabSession, RunHooks.listener)
        assertTrue(StoryStateManager.isAstroLoop(PersistenceManager(context)))
    }

    @Test
    fun `the save is still Astro Loop after the game activity's boot calls`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val p = PersistenceManager(context)

        p.migrateStoryState()
        p.healDesertGoodEnding()

        assertTrue(StoryStateManager.isAstroLoop(p))
    }

    @Test
    fun `a fresh process start re-applies the active preset`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preset = LabApp.presets.create(
            TuningPayload(LabBuild.BASE, "Strong", "", mapOf("pulse_cannon.damage" to 60f))
        )
        LabApp.presets.activeId = preset.id
        Tunables.resetAll()
        RunHooks.listener = null

        LabApp.initLab(context)

        assertEquals(60f, KnobsWeapons.pulseCannon.damage.value, 0f)
        assertSame(LabSession, RunHooks.listener)
    }

    @Test
    fun `a stored preset that was never normalised is stored normalised by the init sequence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preset = LabApp.presets.create(
            TuningPayload(
                "old", "Odd", "",
                mapOf(
                    "pulse_cannon.damage" to 15f,          // equals the default
                    "scatter_shot.damage" to 1_000_000f,   // out of range
                    "railgun.damage" to 40f,
                ),
            )
        )
        LabApp.presets.activeId = preset.id
        Tunables.resetAll()

        LabApp.initLab(context)
        LabSession.onRunEnded(
            RunSummary(
                shipId = "ship_blue", pilotId = "pilot_astro", startMinute = 0,
                survivedSeconds = 60f, cause = "x", finalWeapons = emptyMap(), finalPassives = emptyMap(),
                damageByWeapon = emptyMap(), damageTakenBy = emptyMap(), asteroidsDestroyed = 0,
                avgFps = 60f, tunedMidRunAtSeconds = null, loadout = null,
            )
        )

        val stored = LabApp.presets.get(preset.id)!!.payload
        assertEquals(LabBuild.BASE, stored.base)
        assertEquals(ShareCode.hash(stored), RunReport.parse(LabSession.lastReport!!)["code-hash"])
        assertEquals(ShareCode.hash(LabSession.activePayload!!), ShareCode.hash(stored))
    }
}
