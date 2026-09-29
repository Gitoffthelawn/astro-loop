package com.astroloop.game.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputModeStateTest {

    @Before
    fun setup() = InputModeState.reset()

    @Test
    fun `defaults to touch`() {
        assertEquals(InputMode.TOUCH, InputModeState.mode)
        assertTrue(InputModeState.isTouch)
        assertFalse(InputModeState.isDirectional)
    }

    @Test
    fun `a directional event switches mode`() {
        InputModeState.markDirectional()

        assertTrue(InputModeState.isDirectional)
        assertFalse(InputModeState.isTouch)
    }

    @Test
    fun `a touch switches straight back`() {
        InputModeState.markDirectional()
        InputModeState.markTouch()

        assertTrue(InputModeState.isTouch)
        assertFalse(InputModeState.isDirectional)
    }

    @Test
    fun `directional input records the device that spoke`() {
        InputModeState.markDirectional(7)

        assertEquals(7, InputModeState.activeDeviceId)
    }

    @Test
    fun `a touch does not forget which pad was last used`() {
        // The rumble target and the disconnect-pause rule both read this; a touch changes the mode,
        // not which controller the player picked up.
        InputModeState.markDirectional(7)
        InputModeState.markTouch()

        assertEquals(7, InputModeState.activeDeviceId)
    }
}
