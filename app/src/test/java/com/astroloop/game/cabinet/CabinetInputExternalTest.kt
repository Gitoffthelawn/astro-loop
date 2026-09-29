package com.astroloop.game.cabinet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinetInputExternalTest {

    private val input = CabinetInput(deadZone = 20f, maxRadius = 120f)

    @Test
    fun `an external direction steers without a finger`() {
        input.setExternal(1f, 0f, 1f)

        assertTrue(input.steering)
        assertEquals(1f, input.x, 0.0001f)
        assertEquals(0f, input.y, 0.0001f)
    }

    @Test
    fun `magnitude scales the output`() {
        input.setExternal(1f, 0f, 0.5f)

        assertEquals(0.5f, input.x, 0.0001f)
    }

    @Test
    fun `clearing returns to rest`() {
        input.setExternal(1f, 0f, 1f)
        input.clearExternal()

        assertFalse(input.steering)
        assertEquals(0f, input.x, 0.0001f)
    }

    @Test
    fun `a touch drag still works after an external clear`() {
        input.setExternal(1f, 0f, 1f)
        input.clearExternal()
        input.down(100f, 100f)
        input.move(200f, 100f)

        assertTrue(input.steering)
        assertTrue("the finger must drive it again", input.x > 0f)
    }

    @Test
    fun `a resting pad does not hold the stick over`() {
        // The pad reports every frame, including at rest. If rest left `active` set, the ship
        // would keep its last heading with nothing touching the controller.
        input.setExternal(1f, 0f, 1f)
        input.setExternal(0f, 0f, 0f)

        assertFalse(input.steering)
        assertEquals(0f, input.x, 0.0001f)
    }

    @Test
    fun `an external clear does not leave a phantom drag origin`() {
        // down() sets the origin, so a stale one from before the clear would make the first
        // move() read as a huge deflection.
        input.down(100f, 100f)
        input.move(300f, 300f)
        input.clearExternal()
        input.down(100f, 100f)

        assertEquals(0f, input.x, 0.0001f)
        assertEquals(0f, input.y, 0.0001f)
    }
}
