package com.astroloop.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerPauseGuardTest {

    @Test
    fun `playing can pause`() {
        assertTrue(canPauseFromInput(GamePhase.PLAYING, isPaused = false, debugMenuOpen = false))
    }

    @Test
    fun `the desert can pause`() {
        // The desert scene takes the same pause path as PLAYING in onTouchEvent.
        assertTrue(canPauseFromInput(GamePhase.DESERT, isPaused = false, debugMenuOpen = false))
    }

    @Test
    fun `already paused does not pause again`() {
        assertFalse(canPauseFromInput(GamePhase.PLAYING, isPaused = true, debugMenuOpen = false))
    }

    @Test
    fun `the debug menu blocks pause`() {
        assertFalse(canPauseFromInput(GamePhase.PLAYING, isPaused = false, debugMenuOpen = true))
    }

    @Test
    fun `death and game over do not pause`() {
        for (phase in listOf(
            GamePhase.DEATH_PLAY_OUT, GamePhase.CRYSTAL_DEATH,
            GamePhase.GAME_OVER, GamePhase.HEART_TO_HEART
        )) {
            assertFalse(
                "$phase must not be pausable",
                canPauseFromInput(phase, isPaused = false, debugMenuOpen = false)
            )
        }
    }

    @Test
    fun `cutscene phases deafen the controller exactly as they deafen touch`() {
        for (phase in listOf(
            GamePhase.DEATH_PLAY_OUT, GamePhase.CRYSTAL_DEATH,
            GamePhase.GAME_OVER, GamePhase.HEART_TO_HEART
        )) {
            assertTrue("$phase must block controller input", controllerInputBlocked(phase, isPaused = false))
        }
    }

    @Test
    fun `the pause screen still accepts the press that resumes`() {
        assertFalse(controllerInputBlocked(GamePhase.PLAYING, isPaused = true))
    }

    @Test
    fun `the upgrade overlay is a focus context while it has cards`() {
        assertTrue(upgradeFocusActive(hasPendingOptions = true, luckyStarAnimating = false))
    }

    @Test
    fun `no cards means no focus context`() {
        assertFalse(upgradeFocusActive(hasPendingOptions = false, luckyStarAnimating = false))
    }

    @Test
    fun `Lucky Star deafens the controller as it deafens touch`() {
        // updateUpgradeSelection returns before its tap check while the reel spins, so a finger
        // cannot pick. Neither may a pad — the reel is choosing for the player.
        assertFalse(upgradeFocusActive(hasPendingOptions = true, luckyStarAnimating = true))
    }

    @Test
    fun `the pause button pauses a run`() {
        assertEquals(PauseVerb.PAUSE, pauseVerb(GamePhase.PLAYING, isPaused = false, debugMenuOpen = false))
    }

    @Test
    fun `the pause button resumes while paused`() {
        // A dedicated pause button that cannot unpause is a button that lies. Start was one-way
        // when it was the only pause button; X and Y are not.
        assertEquals(PauseVerb.RESUME, pauseVerb(GamePhase.PLAYING, isPaused = true, debugMenuOpen = false))
    }

    @Test
    fun `the pause button is deaf during a cutscene`() {
        assertEquals(PauseVerb.NOTHING, pauseVerb(GamePhase.HEART_TO_HEART, isPaused = false, debugMenuOpen = false))
    }

    @Test
    fun `the pause button is deaf while the debug menu is open`() {
        assertEquals(PauseVerb.NOTHING, pauseVerb(GamePhase.PLAYING, isPaused = false, debugMenuOpen = true))
    }
}
