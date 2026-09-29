package com.astroloop.game.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub #35: a pickup on the frame the ship dies must not open the upgrade screen over death. */
class UpgradeAfterDeathGuardTest {

    @Test
    fun `a live ship in play opens the upgrade screen`() {
        assertTrue(canOpenUpgradeSelection(GamePhase.PLAYING, shipActive = true))
    }

    @Test
    fun `the frame the ship dies, the pickup does not override the death`() {
        // gameOver() has already run: ship inactive, death play-out (or GAME_OVER on a corruption
        // run, which skips the crystal).
        assertFalse(canOpenUpgradeSelection(GamePhase.DEATH_PLAY_OUT, shipActive = false))
        assertFalse(canOpenUpgradeSelection(GamePhase.GAME_OVER, shipActive = false))
    }

    @Test
    fun `an inactive ship never gets the screen, whatever the phase says`() {
        // The corruption boss's heart-to-heart transition deactivates the ship inside PLAYING.
        assertFalse(canOpenUpgradeSelection(GamePhase.PLAYING, shipActive = false))
    }

    @Test
    fun `a second pickup cannot reopen a screen already open`() {
        assertFalse(canOpenUpgradeSelection(GamePhase.UPGRADE_SELECTION, shipActive = true))
    }
}
