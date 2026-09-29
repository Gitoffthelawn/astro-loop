package com.astroloop.game.cabinet

import org.junit.Assert.assertEquals
import org.junit.Test

class CabinetInputReadoutTest {

    private val input = CabinetInput(deadZone = 20f, maxRadius = 120f)

    @Test
    fun `the origin is where the finger landed`() {
        input.down(300f, 400f)

        assertEquals(300f, input.originX, 0.0001f)
        assertEquals(400f, input.originY, 0.0001f)
    }

    @Test
    fun `the current point follows the finger`() {
        input.down(300f, 400f)
        input.move(350f, 400f)

        assertEquals(350f, input.currentX, 0.0001f)
    }

    @Test
    fun `the metrics the readout draws are readable`() {
        // The cabinet's stick is sized from CabinetMetrics, not GameConfig, so the readout must
        // be told rather than assume the combat constants.
        assertEquals(20f, input.deadZoneRadius, 0.0001f)
        assertEquals(120f, input.stickRadius, 0.0001f)
    }
}
