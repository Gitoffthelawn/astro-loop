package com.astroloop.game.hangar

import android.graphics.RectF
import com.astroloop.game.input.FocusRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BarFocusParityTest {

    private lateinit var focus: FocusRegistry
    private val tapped = mutableListOf<Pair<Float, Float>>()

    @Before
    fun setup() {
        focus = FocusRegistry()
        tapped.clear()
    }

    private fun publish(rects: List<RectF>, originX: Float = 0f) {
        focus.begin()
        BarPageRenderer.publishPilotTargets(focus, rects, originX) { x, y -> tapped.add(x to y) }
        focus.commit()
    }

    @Test
    fun `one target per published card rect`() {
        publish(listOf(RectF(0f, 0f, 100f, 140f), RectF(120f, 0f, 220f, 140f)))

        assertEquals(listOf("pilot:0", "pilot:1"), focus.targets().map { it.id })
    }

    @Test
    fun `activating a card taps its own centre`() {
        val rect = RectF(120f, 0f, 220f, 140f)
        publish(listOf(RectF(0f, 0f, 100f, 140f), rect))
        focus.focusedId = "pilot:1"

        assertTrue(focus.activate())
        assertEquals(listOf(rect.centerX() to rect.centerY()), tapped)
    }

    @Test
    fun `no cards means no targets and nothing to activate`() {
        publish(emptyList())

        assertTrue(focus.isEmpty())
    }

    // --- the room-local / screen-space seam ---------------------------------------------------

    @Test
    fun `the published rect is in screen space, not room space`() {
        // pilotCardRects are recorded inside the page's translate. FocusNavigator compares them
        // against the nav row, which is screen space, so a raw room rect would sit a room-offset
        // to the left of the card it stands for.
        publish(listOf(RectF(100f, 200f, 200f, 340f)), originX = 640f)

        val rect = focus.byId("pilot:0")!!.rect
        assertEquals(740f, rect.left, 0.01f)
        assertEquals(840f, rect.right, 0.01f)
        assertEquals(200f, rect.top, 0.01f)
    }

    @Test
    fun `the tap point is screen space too, because handleBarTap converts it back`() {
        // handleBarTap's first act is roomX(x). Handing it a room-local x would convert twice and
        // land a room-offset away — on the wrong card, or on no card at all.
        publish(listOf(RectF(100f, 200f, 200f, 340f)), originX = 640f)
        focus.focusedId = "pilot:0"

        focus.activate()

        assertEquals(listOf(790f to 270f), tapped)
    }

    @Test
    fun `a zero origin is the identity, which is the phone case`() {
        val rect = RectF(100f, 200f, 200f, 340f)
        publish(listOf(rect), originX = 0f)

        assertEquals(rect, focus.byId("pilot:0")!!.rect)
    }
}
