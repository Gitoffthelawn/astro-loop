package com.astroloop.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.astroloop.game.input.InputModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Robolectric: the renderer builds its Paints at construction, and a bare JVM Paint is not mocked.
// NATIVE graphics because the colour tests below read the pixels back — the legacy shadow canvas
// records nothing, so every drawn pixel would come back transparent and the assertions would be
// vacuous rather than wrong.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class StickReadoutRendererTest {

    private lateinit var readout: StickReadoutRenderer

    @Before
    fun setup() {
        InputModeState.reset()
        readout = StickReadoutRenderer()
    }

    @Test
    fun `starts invisible`() {
        assertEquals(0f, readout.alpha, 0.0001f)
        assertFalse(readout.shouldDraw())
    }

    @Test
    fun `fades in while a finger is down`() {
        readout.update(active = true, deltaTime = 0.1f)

        assertTrue(readout.alpha > 0f)
        assertTrue(readout.shouldDraw())
    }

    @Test
    fun `reaches full opacity and stops there`() {
        repeat(30) { readout.update(active = true, deltaTime = 0.1f) }

        assertEquals(1f, readout.alpha, 0.0001f)
    }

    @Test
    fun `fades out rather than cutting when the finger lifts`() {
        repeat(30) { readout.update(active = true, deltaTime = 0.1f) }
        readout.update(active = false, deltaTime = 0.05f)

        // No instant disappearance: still visible on the frame after release.
        assertTrue("must not cut to zero", readout.alpha > 0f)
        assertTrue("but must be on the way out", readout.alpha < 1f)
    }

    @Test
    fun `never draws in directional mode`() {
        repeat(30) { readout.update(active = true, deltaTime = 0.1f) }
        InputModeState.markDirectional()

        assertFalse("a gamepad player has a real stick already", readout.shouldDraw())
    }

    @Test
    fun `a released readout reaches zero and stays there`() {
        // The no-instant-disappearance rule cuts both ways: it must finish leaving, not park
        // at a sliver forever, and must not go negative and come back.
        repeat(30) { readout.update(active = true, deltaTime = 0.1f) }
        repeat(30) { readout.update(active = false, deltaTime = 0.1f) }

        assertEquals(0f, readout.alpha, 0.0001f)
        assertFalse(readout.shouldDraw())
    }

    @Test
    fun `switching to a pad mid-fade leaves nothing drawn`() {
        // Mode can flip on any event, including while the readout is still fading out. It must
        // not keep drawing a touch affordance over a controller player's screen.
        repeat(30) { readout.update(active = true, deltaTime = 0.1f) }
        readout.update(active = false, deltaTime = 0.05f)
        InputModeState.markDirectional()

        assertFalse(readout.shouldDraw())
    }

    // --- the readout wears the colour of the thing you are flying -----------------------------

    private fun renderOnto(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        repeat(30) { readout.update(active = true, deltaTime = 0.1f) }
        // Knob at the origin, so the filled dot lands on a known pixel.
        readout.render(canvas, 200f, 200f, 200f, 200f, 20f, 120f, color)
        return bmp
    }

    @Test
    fun `a blue ship leaves a blue readout`() {
        val px = renderOnto(0xFF3388FF.toInt()).getPixel(200, 200)

        assertTrue("blue ship must not draw a white knob", Color.blue(px) > Color.red(px))
    }

    @Test
    fun `the desert tank's red leaves a red readout`() {
        // The flashback is the case that proves this is a parameter and not a ship-only lookup.
        val px = renderOnto(0xFFDD3333.toInt()).getPixel(200, 200)

        assertTrue("the tank must not draw a blue knob", Color.red(px) > Color.blue(px))
    }

    @Test
    fun `the outer ring takes the colour too, not just the knob`() {
        // Origin at (200,200), radius 120, so the ring crosses x=320 on the horizontal.
        val bmp = renderOnto(0xFFDD3333.toInt())
        var found = false
        for (x in 316..324) {
            val px = bmp.getPixel(x, 200)
            if (Color.alpha(px) > 0 && Color.red(px) > Color.blue(px)) found = true
        }

        assertTrue("the ring must be drawn in the given colour", found)
    }
}
