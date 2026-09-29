package com.astroloop.game.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionalInputTest {

    private val input = DirectionalInput()

    @Test
    fun `starts inactive with zero magnitude`() {
        assertFalse(input.isActive)
        assertEquals(0f, input.magnitude, 0.0001f)
    }

    @Test
    fun `a single digital direction is full magnitude`() {
        input.setDigital(left = false, right = true, up = false, down = false)

        assertTrue(input.isActive)
        assertEquals(1f, input.magnitude, 0.0001f)
        assertEquals(1f, input.direction.x, 0.0001f)
        assertEquals(0f, input.direction.y, 0.0001f)
    }

    @Test
    fun `a digital diagonal is normalised, not 1_41`() {
        input.setDigital(left = false, right = true, up = true, down = false)

        assertEquals("magnitude must not exceed 1", 1f, input.magnitude, 0.0001f)
        assertEquals("direction must be a unit vector", 1f, input.direction.length(), 0.0001f)
        assertEquals(0.7071f, input.direction.x, 0.001f)
        assertEquals(-0.7071f, input.direction.y, 0.001f)
    }

    @Test
    fun `opposing digital directions cancel`() {
        input.setDigital(left = true, right = true, up = true, down = true)

        assertFalse(input.isActive)
        assertEquals(0f, input.magnitude, 0.0001f)
    }

    @Test
    fun `stick inside the flat zone reads as zero`() {
        input.setStick(axisX = 0.05f, axisY = 0.05f, flat = 0.2f)

        assertFalse(input.isActive)
        assertEquals(0f, input.magnitude, 0.0001f)
    }

    @Test
    fun `stick magnitude rescales from the flat edge, not from zero`() {
        // Half way between the flat edge (0.2) and full deflection (1.0).
        input.setStick(axisX = 0.6f, axisY = 0f, flat = 0.2f)

        assertEquals(0.5f, input.magnitude, 0.001f)
        assertEquals(1f, input.direction.x, 0.0001f)
    }

    @Test
    fun `stick magnitude is clamped to one past full deflection`() {
        input.setStick(axisX = 1.4f, axisY = 0f, flat = 0.2f)

        assertEquals(1f, input.magnitude, 0.0001f)
    }

    @Test
    fun `clear returns to rest`() {
        input.setDigital(left = false, right = true, up = false, down = false)
        input.clear()

        assertFalse(input.isActive)
        assertEquals(0f, input.magnitude, 0.0001f)
        assertEquals(0f, input.direction.x, 0.0001f)
        assertEquals(0f, input.direction.y, 0.0001f)
    }
}
