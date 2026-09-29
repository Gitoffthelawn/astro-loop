package com.astroloop.game.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectPolicyTest {

    @Test
    fun `losing the device being played on pauses`() {
        assertTrue(shouldPauseOnDisconnect(wasLastSource = true, mode = InputMode.DIRECTIONAL))
    }

    @Test
    fun `losing an idle second controller does not pause`() {
        assertFalse(shouldPauseOnDisconnect(wasLastSource = false, mode = InputMode.DIRECTIONAL))
    }

    @Test
    fun `a touch player is never interrupted by a disconnect`() {
        // Someone unplugging a controller they were not using must not stop the game.
        assertFalse(shouldPauseOnDisconnect(wasLastSource = true, mode = InputMode.TOUCH))
    }
}
