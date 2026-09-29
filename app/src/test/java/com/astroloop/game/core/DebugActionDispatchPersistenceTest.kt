package com.astroloop.game.core

import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.cabinet.CabinetDebugIntent
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.PilotDefinitions
import com.astroloop.game.data.ShipDefinitions
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the persistence-backed branches of [DebugActionDispatch.handle].
 *
 * [DebugActionDispatchTest] covers only the pure [DebugActionDispatch.isRunOnly] classification —
 * its own doc comment says the remaining branches were left uncovered because "`PersistenceManager`
 * needs a `Context` and this project has no Robolectric." That premise was false: this project has
 * had Robolectric (`org.robolectric:robolectric:4.16`) all along, and 40+ other test files already
 * use it, including the house pattern this class follows
 * ([com.astroloop.game.data.BandanaPersistenceTest]).
 *
 * Every assertion here reads back through a *real* [PersistenceManager] backed by Robolectric's
 * SharedPreferences — not a fake recording that a method was called. [FakeHost] exists only to
 * stand in for [DebugActionHost] (the run-only / view-owned half of `handle`'s contract), and its
 * call counts are asserted alongside the persistence effect wherever a branch touches both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugActionDispatchPersistenceTest {

    private lateinit var p: PersistenceManager
    private lateinit var state: GameState
    private lateinit var host: FakeHost

    @Before
    fun setup() {
        p = PersistenceManager(ApplicationProvider.getApplicationContext())
        p.resetAllProgress()
        state = GameState()
        host = FakeHost()
    }

    @After
    fun tearDown() {
        // CabinetDebugIntent is a plain Kotlin object, not reset by Robolectric between tests
        // (CabinetDebugIntentTest uses the same teardown for the same reason) — ARCADE_OPEN and
        // ARCADE_PLAY both leave a pending request behind if a test forgets to consume it.
        CabinetDebugIntent.clear()
    }

    /** Hand-rolled fake — no mocking library, per the F-Droid reproducible-build constraint. */
    private class FakeHost(private val inRun: Boolean = false) : DebugActionHost {
        var returnToHangarCalls = 0
        var closeMenuCalls = 0
        var clearTelemetryCalls = 0
        val toggleLoadoutCalls = mutableListOf<String>()
        val runOnlyActionCalls = mutableListOf<String>()

        override fun isInRun(): Boolean = inRun
        override fun returnToHangar() { returnToHangarCalls++ }
        override fun closeMenu() { closeMenuCalls++ }
        override fun toggleLoadout(action: String) { toggleLoadoutCalls.add(action) }
        override fun runOnlyAction(action: String) { runOnlyActionCalls.add(action) }
        override fun clearTelemetry() { clearTelemetryCalls++ }
    }

    // --- RESET_SMALL / RESET_BIG ------------------------------------------------------------

    @Test
    fun resetSmallWipesProgressAndBanksFiftyYen() {
        p.setYen(999_999)
        p.addBandana("pilot_dash")
        p.unlockShip("ship_red")

        assertTrue(DebugActionDispatch.handle("RESET_SMALL", state, p, host))

        // resetAllProgress() itself never touches yen — if the explicit setYen(50) call were
        // dropped, this would still read 999_999.
        assertEquals(50, p.getYen())
        assertEquals(0, p.getBandanaCount())
        assertEquals(setOf("ship_blue"), p.getUnlockedShips())
        assertEquals(1, host.clearTelemetryCalls)
        assertEquals(1, host.closeMenuCalls)
        assertEquals(1, host.returnToHangarCalls)
    }

    @Test
    fun resetBigUnlocksEverythingMaxesUpgradesAndBanksTenMillion() {
        assertTrue(DebugActionDispatch.handle("RESET_BIG", state, p, host))

        assertEquals(10_000_000, p.getYen())
        assertFalse(p.isFirstLaunch())
        assertTrue(p.isIntroDone())
        assertEquals(ShipDefinitions.ships.map { it.id }.toSet(), p.getUnlockedShips())
        assertEquals(PilotDefinitions.pilots.map { it.id }.toSet(), p.getUnlockedPilots())
        for (id in listOf("health", "shields", "speed", "damage", "crit", "yen_bonus", "salvage", "magnet")) {
            assertEquals("$id should be maxed", 5, p.getUpgradeLevel(id))
        }
        assertEquals(1, host.clearTelemetryCalls)
        assertEquals(1, host.closeMenuCalls)
        assertEquals(1, host.returnToHangarCalls)
    }

    // --- SET_CORRUPT / KILL_PILOT / KILL_ALL / BUY_CRYSTAL / RESET_STORY -------------------

    @Test
    fun setCorruptSetsTheStoryStageAndReturnsToHangar() {
        assertTrue(DebugActionDispatch.handle("SET_CORRUPT", state, p, host))

        assertEquals(StoryStage.CORRUPTION.code, p.getStoryStageCode())
        assertEquals(1, host.closeMenuCalls)
        assertEquals(1, host.returnToHangarCalls)
    }

    @Test
    fun killPilotKillsTheFirstAlivePilotAndItsShip() {
        assertTrue(DebugActionDispatch.handle("KILL_PILOT", state, p, host))

        assertEquals(StoryStage.CORRUPTION.code, p.getStoryStageCode())
        // pilot_medic is first in PilotDefinitions.pilots (excluding pilot_astro), and
        // ship_blue is its fleet ship per StoryStateManager.FLEET_MAPPING.
        assertEquals(setOf("pilot_medic"), p.getDeadPilots())
        assertEquals(setOf("ship_blue"), p.getDeadShips())
    }

    @Test
    fun killPilotProgressesToTheNextAlivePilotOnASecondCall() {
        DebugActionDispatch.handle("KILL_PILOT", state, p, host)
        DebugActionDispatch.handle("KILL_PILOT", state, p, host)

        assertEquals(setOf("pilot_medic", "pilot_rascal"), p.getDeadPilots())
        assertEquals(setOf("ship_blue", "ship_green"), p.getDeadShips())
    }

    @Test
    fun killAllKillsEveryCrewmateButAstroAndUnlocksTheCrystal() {
        assertTrue(DebugActionDispatch.handle("KILL_ALL", state, p, host))

        val expectedDead = PilotDefinitions.pilots.map { it.id }.filter { it != "pilot_astro" }.toSet()
        assertEquals(expectedDead, p.getDeadPilots())
        assertFalse(p.getDeadPilots().contains("pilot_astro"))
        assertEquals(11, p.getDeadShips().size)
        assertFalse("Astro's ship must survive KILL_ALL", p.getDeadShips().contains("ship_white"))
        assertEquals(StoryStage.CORRUPTION.code, p.getStoryStageCode())
        // All 11 crewmates dead and the crystal not yet purchased is exactly
        // StoryStateManager.shouldUnlockCrystal's condition.
        assertTrue(p.isCrystalUnlocked())
    }

    @Test
    fun buyCrystalUnlocksAndMarksItPurchased() {
        assertTrue(DebugActionDispatch.handle("BUY_CRYSTAL", state, p, host))

        assertEquals(StoryStage.CORRUPTION.code, p.getStoryStageCode())
        assertTrue(p.isCrystalUnlocked())
        assertTrue(p.getCrystalPurchased())
    }

    @Test
    fun resetStoryWipesStoryStateBackToLoopOneNormal() {
        p.setStoryStageCode(StoryStage.ASTRO_LOOP.code)
        p.setStoryLoop(3)
        p.setCrystalUnlocked(true)
        p.setCrystalPurchased(true)
        p.addDeadPilot("pilot_medic")
        p.addDeadShip("ship_blue")

        assertTrue(DebugActionDispatch.handle("RESET_STORY", state, p, host))

        assertEquals(StoryStage.NORMAL.code, p.getStoryStageCode())
        assertEquals(1, p.getStoryLoop())
        assertFalse(p.isCrystalUnlocked())
        assertFalse(p.getCrystalPurchased())
        assertTrue(p.getDeadPilots().isEmpty())
        assertTrue(p.getDeadShips().isEmpty())
    }

    // --- GRANT_BANDANAS / CLEAR_BANDANAS ----------------------------------------------------

    @Test
    fun grantBandanasSetsAllTwelve() {
        assertTrue(DebugActionDispatch.handle("GRANT_BANDANAS", state, p, host))

        for (pilot in PilotDefinitions.pilots) {
            assertTrue("${pilot.id} should have a bandana", p.hasBandana(pilot.id))
        }
        assertEquals(12, p.getBandanaCount())
    }

    @Test
    fun clearBandanasWipesBandanaState() {
        for (pilot in PilotDefinitions.pilots) p.addBandana(pilot.id)
        p.setPendingBandanaPilot("pilot_dash")
        p.setCrystalReleased(true)

        assertTrue(DebugActionDispatch.handle("CLEAR_BANDANAS", state, p, host))

        assertEquals(0, p.getBandanaCount())
        assertNull(p.getPendingBandanaPilot())
        assertFalse(p.isCrystalReleased())
    }

    // --- ARCADE_* ----------------------------------------------------------------------------

    @Test
    fun arcadeOpenRequestsTheCabinetWithoutSpendingACredit() {
        assertTrue(DebugActionDispatch.handle("ARCADE_OPEN", state, p, host))

        // The twin branch, ARCADE_PLAY, banks a credit; ARCADE_OPEN must not.
        assertEquals(0, p.getCabinetCredits())
        val request = CabinetDebugIntent.consume()
        assertNotNull(request)
        assertEquals(CabinetDebugIntent.Action.OPEN, request!!.action)
        assertEquals(1, host.closeMenuCalls)
        assertEquals(1, host.returnToHangarCalls)
    }

    @Test
    fun arcadePlayBanksACreditAndRequestsPlay() {
        assertTrue(DebugActionDispatch.handle("ARCADE_PLAY", state, p, host))

        assertEquals(1, p.getCabinetCredits())
        val request = CabinetDebugIntent.consume()
        assertNotNull(request)
        assertEquals(CabinetDebugIntent.Action.PLAY, request!!.action)
        assertEquals(1, host.closeMenuCalls)
        assertEquals(1, host.returnToHangarCalls)
    }

    @Test
    fun arcadeCreditsAddsTen() {
        assertEquals(0, p.getCabinetCredits())
        assertTrue(DebugActionDispatch.handle("ARCADE_CREDITS", state, p, host))
        assertEquals(10, p.getCabinetCredits())
    }

    @Test
    fun arcadeClearPilotClearsOnlyTheSelectedPilot() {
        p.setSelectedPilotId("pilot_dash")

        assertTrue(DebugActionDispatch.handle("ARCADE_CLEAR_PILOT", state, p, host))

        assertTrue(p.isPilotCleared("pilot_dash"))
        assertEquals(PersistenceManager.ARCADE_CLEAR_SCORE, p.getArcadeScore("pilot_dash"))
        assertFalse(p.isPilotCleared("pilot_havoc"))
        assertFalse(p.allPilotsCleared())
    }

    @Test
    fun arcadeClearAllClearsEveryPilot() {
        assertTrue(DebugActionDispatch.handle("ARCADE_CLEAR_ALL", state, p, host))
        assertTrue(p.allPilotsCleared())
    }

    @Test
    fun arcadeResetWipesScoresAndCredits() {
        for (pilot in PilotDefinitions.pilots) {
            p.setArcadeScoreIfBetter(pilot.id, PersistenceManager.ARCADE_CLEAR_SCORE)
        }
        p.addCabinetCredit()

        assertTrue(DebugActionDispatch.handle("ARCADE_RESET", state, p, host))

        assertFalse(p.allPilotsCleared())
        for (pilot in PilotDefinitions.pilots) assertEquals(0, p.getArcadeScore(pilot.id))
        assertEquals(0, p.getCabinetCredits())
    }

    // --- TOGGLE_ASTRO_LOOP ----------------------------------------------------------------------

    @Test
    fun toggleAstroLoopRoundTrips() {
        assertEquals(StoryStage.NORMAL.code, p.getStoryStageCode())

        assertTrue(DebugActionDispatch.handle("TOGGLE_ASTRO_LOOP", state, p, host))
        assertEquals(StoryStage.ASTRO_LOOP.code, p.getStoryStageCode())
        assertTrue(state.astroLoopMode)

        assertTrue(DebugActionDispatch.handle("TOGGLE_ASTRO_LOOP", state, p, host))
        assertEquals(StoryStage.NORMAL.code, p.getStoryStageCode())
        assertFalse(state.astroLoopMode)
    }

    // --- SET_DESERT_FLAGS / CLR_DESERT -----------------------------------------------------------

    @Test
    fun setDesertFlagsMarksCompletedAndGoodEnding() {
        assertTrue(DebugActionDispatch.handle("SET_DESERT_FLAGS", state, p, host))
        assertTrue(p.isDesertCompleted())
        assertTrue(p.hasDesertGoodEnding())
    }

    @Test
    fun clrDesertClearsCompletedAndGoodEnding() {
        p.setDesertCompleted()
        p.setDesertGoodEnding()

        assertTrue(DebugActionDispatch.handle("CLR_DESERT", state, p, host))

        assertFalse(p.isDesertCompleted())
        assertFalse(p.hasDesertGoodEnding())
    }

    // --- SET_LOOP_1 / SET_LOOP_2 / SET_LOOP_3 -----------------------------------------------------

    @Test
    fun setLoopActionsWriteTheirLoopNumberToPersistenceAndState() {
        val cases = listOf("SET_LOOP_1" to 1, "SET_LOOP_2" to 2, "SET_LOOP_3" to 3)
        for ((action, loop) in cases) {
            // Start from a different value each time so a no-op branch would be caught.
            val other = if (loop == 1) 3 else 1
            p.setStoryLoop(other)
            state.storyLoop = other

            assertTrue(DebugActionDispatch.handle(action, state, p, host))

            assertEquals("$action should set persisted loop to $loop", loop, p.getStoryLoop())
            assertEquals("$action should set state.storyLoop to $loop", loop, state.storyLoop)
        }
    }
}
