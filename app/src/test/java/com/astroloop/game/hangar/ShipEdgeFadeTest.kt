package com.astroloop.game.hangar

import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.ScreenLayout
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A ship near the launchpad page's clip edge fades out instead of being sliced in half.
 *
 * The owner, 2026-09-20, from the device: "if you select one of the ships in the middle and you
 * swipe pages, you see the ships on the edges being clipped... it breaks the illusion of it being
 * one connected walkway." The page clips to its own width, so a ship whose glyph or label crosses
 * that boundary used to be drawn cut in half — the seam between pages made visible by the one
 * thing that is supposed to read as continuous.
 *
 * Two properties, and the second matters as much as the first:
 *  - in LANDSCAPE, a ship is fully transparent by the time any part of it would cross;
 *  - in PORTRAIT, nothing changes at all. Portrait draws the same edge slivers it always has
 *    (its third ship out sits about 68 units off the page edge at half alpha), and the owner's
 *    standing rule for this whole layer is that portrait does not move. That is a deliberate
 *    asymmetry, not an oversight, and it is one line in `shipEdgeFade` to lift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShipEdgeFadeTest {

    private lateinit var persistence: PersistenceManager

    private val land = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f)
    private val port = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f)

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
    }

    private fun roomWidthFor(layout: ScreenLayout, swDp: Int = 427): Float {
        val p = DesignSpace.portraitShaped(layout)
        return HangarMetrics.roomWidth(p.width, p.content.width, swDp)
    }

    private fun renderer(layout: ScreenLayout): HangarRenderer =
        HangarRenderer(persistence).also { it.initialize(layout, roomWidthFor(layout)) }

    /**
     * The extent the fade is measured in. `HangarRenderer.measureShipHalfExtent` floors at
     * [HangarRenderer.MIN_SHIP_EXTENT] because Robolectric's `measureText` returns 0 — so this is
     * the number both this test and a real device use, rather than a test-only fiction.
     */
    private val halfExtent = HangarRenderer.MIN_SHIP_EXTENT / 2f // 90

    // =====================================================================================
    // Landscape: gone before it is cut
    // =====================================================================================

    @Test
    fun `a ship is fully transparent by the moment it would start to clip`() {
        val r = renderer(land.layout)
        val w = land.width

        // Its leading edge exactly on the page's left clip: nothing of it may be drawn.
        assertEquals(0f, r.shipEdgeFade(halfExtent, w), 0.001f)
        // And past it, still nothing — not a negative alpha, not a resurrection.
        assertEquals(0f, r.shipEdgeFade(halfExtent - 50f, w), 0.001f)
        assertEquals(0f, r.shipEdgeFade(0f, w), 0.001f)
        // The same on the right-hand edge.
        assertEquals(0f, r.shipEdgeFade(w - halfExtent, w), 0.001f)
        assertEquals(0f, r.shipEdgeFade(w, w), 0.001f)
    }

    @Test
    fun `a ship in the middle of the page is untouched`() {
        val r = renderer(land.layout)
        assertEquals(1f, r.shipEdgeFade(land.width / 2f, land.width), 0.001f)
    }

    /**
     * The fade is a ramp, not a switch — the owner chose "fade out over the last stretch" over a
     * hard hide precisely so a ship is never seen to pop. A hardcoded `if (clipping) 0 else 1`
     * passes the two tests above and fails this one.
     */
    @Test
    fun `the fade is gradual across one ship width, not a switch`() {
        val r = renderer(land.layout)
        val w = land.width
        // Clearance is measured from the ship's leading edge: at halfExtent + span it is fully
        // opaque, at halfExtent it is gone, and halfway between it is halfway faded.
        val span = halfExtent * 2f
        assertEquals(1f, r.shipEdgeFade(halfExtent + span, w), 0.001f)
        assertEquals(0.5f, r.shipEdgeFade(halfExtent + span / 2f, w), 0.001f)
        assertEquals(0.25f, r.shipEdgeFade(halfExtent + span / 4f, w), 0.001f)

        // Monotonic on the way in, so nothing flickers as the page slides.
        var previous = -1f
        for (x in 0..400 step 20) {
            val alpha = r.shipEdgeFade(x.toFloat(), w)
            assertTrue("alpha must not decrease as a ship moves inward (at x=$x)", alpha >= previous)
            previous = alpha
        }
    }

    @Test
    fun `the fade never overshoots its own range`() {
        val r = renderer(land.layout)
        for (x in -500..2600 step 25) {
            val alpha = r.shipEdgeFade(x.toFloat(), land.width)
            assertTrue("alpha $alpha out of range at x=$x", alpha in 0f..1f)
        }
    }

    // =====================================================================================
    // Portrait: provably nothing
    // =====================================================================================

    /**
     * Asserted at BOTH inset values, per this layer's standing rule — the 2026-09-14 revert
     * happened because a suite of portrait-identity tests used zero-inset fixtures only.
     */
    @Test
    fun `portrait fades nothing, at either inset`() {
        for (cutout in listOf(0f, 128f)) {
            val layout = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = cutout).layout
            val r = renderer(layout)
            val w = layout.width
            for (x in listOf(-99f, 0f, 22f, halfExtent, w / 2f, w - 22f, w, w + 99f)) {
                assertEquals(
                    "portrait must draw exactly what it always has (cutout=$cutout, x=$x)",
                    1f, r.shipEdgeFade(x, w), 0.0f
                )
            }
        }
    }

    /**
     * The specific sliver the owner's portrait build shows today: with 12 ships and the portrait
     * carousel's own spacing, the third ship out sits just inside the old centre-based cull and
     * is drawn clipped. This pins that it is still drawn — if someone lifts the landscape gate
     * without saying so, this is the test that notices.
     */
    @Test
    fun `the portrait carousel still draws its clipped edge ship`() {
        val r = renderer(port.layout)
        val spacing = r.shipSpacing
        val centre = port.width / 2f
        val thirdShipOut = centre - 2f * spacing
        assertTrue(
            "the fixture must actually place a ship off the page edge (at $thirdShipOut)",
            thirdShipOut < 0f
        )
        assertTrue(
            "and near enough that the old centre-based cull keeps it",
            thirdShipOut > -100f
        )
        assertEquals(
            "portrait still draws it, clipped, exactly as before",
            1f, r.shipEdgeFade(thirdShipOut, port.width), 0.0f
        )
    }

    // =====================================================================================
    // The extent the fade is measured in
    // =====================================================================================

    /**
     * The fade has to clear the widest thing drawn at a ship's x, which is a LABEL, not the ship.
     * A fade sized to the 25-unit glyph alone would still let "HOMING MISSILES" hang over the
     * edge — the exact bug, just narrower.
     */
    @Test
    fun `the extent covers the labels, not just the ship glyph`() {
        val r = renderer(land.layout)
        val glyphHalf = com.astroloop.game.core.GameConfig.SHIP_BASE_SIZE // 25
        assertTrue(
            "the extent ($halfExtent) must be well past the glyph's own ($glyphHalf)",
            halfExtent > glyphHalf * 3f
        )
        // And the renderer really is using it: a ship one glyph-width off the edge is already gone.
        assertEquals(0f, r.shipEdgeFade(glyphHalf * 2f, land.width), 0.001f)
    }
}
