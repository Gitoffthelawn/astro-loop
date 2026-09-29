package com.astroloop.game.input

import com.astroloop.game.util.Vector2
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The blend rule, extracted so it can be tested without standing up a SurfaceView and a
 * render thread: whichever source produced input most recently wins, and a resting source
 * never fights an active one.
 */
class CombatMovementSourceTest {

    private fun blend(
        touch: Vector2, touchMagnitude: Float,
        pad: Vector2, padMagnitude: Float,
        mode: InputMode
    ): Pair<Vector2, Float> =
        if (mode == InputMode.DIRECTIONAL) pad to padMagnitude else touch to touchMagnitude

    @Test
    fun `touch mode uses the touch stick`() {
        val (dir, mag) = blend(Vector2(1f, 0f), 1f, Vector2(-1f, 0f), 1f, InputMode.TOUCH)

        assertEquals(1f, dir.x, 0.0001f)
        assertEquals(1f, mag, 0.0001f)
    }

    @Test
    fun `directional mode uses the pad`() {
        val (dir, mag) = blend(Vector2(1f, 0f), 1f, Vector2(-1f, 0f), 1f, InputMode.DIRECTIONAL)

        assertEquals(-1f, dir.x, 0.0001f)
        assertEquals(1f, mag, 0.0001f)
    }

    @Test
    fun `a resting pad in directional mode holds the ship still`() {
        val (_, mag) = blend(Vector2(1f, 0f), 1f, Vector2(), 0f, InputMode.DIRECTIONAL)

        assertEquals(0f, mag, 0.0001f)
    }

    @Test
    fun `copyFrom snapshots rather than aliasing`() {
        // The router owns one DirectionalInput and mutates it in place, so a surface that keeps
        // a reference would see it change underneath a frame.
        val source = DirectionalInput()
        source.setDigital(left = false, right = true, up = false, down = false)

        val snapshot = DirectionalInput()
        snapshot.copyFrom(source)
        source.clear()

        assertEquals("the snapshot must not follow the source", 1f, snapshot.magnitude, 0.0001f)
        assertEquals(1f, snapshot.direction.x, 0.0001f)
    }
}
