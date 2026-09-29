package com.astroloop.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the run-only classification, which is the pure part and the property the per-row dimming depends on.
 *
 * `handle`'s persistence branches are not covered here: `PersistenceManager` needs a `Context` and
 * this project has no Robolectric. That is the same boundary an earlier change drew, and its coverage
 * gap is written up in the ledger rather than papered over with a mock.
 */
class DebugActionDispatchTest {

    /** Need a live run permanently. */
    private val runOnly = listOf(
        "WEAPON_TOGGLE", "PASSIVE_TOGGLE", "BOSS_NOW", "INSTANT_DEATH",
        "PLAY_DESERT", "PLAY_DESERT_P2", "DESERT_CRYSTAL"
    )

    private val persistenceBacked = listOf(
        "ARCADE_CLEAR_ALL", "ARCADE_CLEAR_PILOT", "ARCADE_CREDITS", "ARCADE_RESET",
        "ARCADE_OPEN", "ARCADE_PLAY", "GRANT_BANDANAS", "CLEAR_BANDANAS",
        "BUY_CRYSTAL", "RESET_STORY", "SET_LOOP_1", "SET_LOOP_2",
        "SET_LOOP_3", "SET_CORRUPT", "TOGGLE_ASTRO_LOOP", "CLR_DESERT",
        "SET_DESERT_FLAGS", "RESET_SMALL", "RESET_BIG",
        "KILL_PILOT", "KILL_ALL"
    )

    @Test
    fun everyActionNeedingAViewIsClassifiedAsRunOnly() {
        for (action in runOnly) {
            assertTrue("$action needs a live run", DebugActionDispatch.isRunOnly(action))
        }
    }

    @Test
    fun everyOtherActionWorksFromAStandingStart() {
        for (action in persistenceBacked) {
            assertFalse(
                "$action is persistence-backed and must not be gated on a run",
                DebugActionDispatch.isRunOnly(action)
            )
        }
    }

    @Test
    fun theRunOnlySurfaceIsExactlySevenActions() {
        // The tripwire for the per-row dimming: every action in this set needs a dimmed row
        // in the hangar, and one added here without a matching row is a live button that no-ops.
        val all = runOnly + persistenceBacked
        assertEquals(7, all.count { DebugActionDispatch.isRunOnly(it) })
    }

    @Test
    fun anUnknownActionIsNotRunOnly() {
        assertFalse(DebugActionDispatch.isRunOnly("NOT_AN_ACTION"))
    }
}
