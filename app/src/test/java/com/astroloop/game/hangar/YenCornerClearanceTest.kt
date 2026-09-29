package com.astroloop.game.hangar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
 * The yen counter clears the display's rounded corner.
 *
 * The owner, 2026-09-20, on a Pixel 9 Pro: "the yen counter in the top right is not fully visible
 * due to the rounded corners of my device. That's not a problem on portrait as it's printed below
 * the camera cutout, but it becomes a problem on any other orientation" — including upside-down
 * portrait, which also leaves `safe.top` at 0.
 *
 * A rounded corner is not an inset. `safe` accounts for the cutout and the system bars; the corner
 * radius arrives separately (`WindowInsets.getRoundedCorner`, API 31+) and was not being read at
 * all. Right-way-up portrait got away with it because the cutout pushes the counter down past the
 * arc; every other orientation puts it at y=50 in the bare corner.
 *
 * The rule under test is deliberately one-directional: the counter moves INWARD, and only as far
 * as the arc actually demands. So on a square-cornered display, below API 31, and in right-way-up
 * portrait on the owner's device, it does not move at all — which is what keeps portrait still.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YenCornerClearanceTest {

    private lateinit var persistence: PersistenceManager

    /**
     * A plausible Pixel 9 Pro corner, in physical pixels. The exact figure is the device's to
     * report — the behaviour under test is "clear whatever radius arrives", so the tests below
     * sweep a range rather than resting on one guess.
     */
    private val radiusPx = 150f

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
        persistence.setYen(123_456)
    }

    private fun roomWidthFor(layout: ScreenLayout, swDp: Int = 427): Float {
        val p = DesignSpace.portraitShaped(layout)
        return HangarMetrics.roomWidth(p.width, p.content.width, swDp)
    }

    /** Where the counter was actually drawn, read back from the renderer's own seam. */
    private fun drawnYenRight(layout: ScreenLayout): Float {
        val renderer = HangarRenderer(persistence)
        renderer.initialize(layout, roomWidthFor(layout))
        val state = HangarState(persistence)
        state.pilotScreenWidth = layout.width
        state.pilotScreenHeight = layout.height
        state.roomWidth = roomWidthFor(layout)
        state.currentPage = 1
        state.phase = HangarPhase.BROWSING
        val bitmap = Bitmap.createBitmap(
            layout.width.toInt().coerceAtLeast(1), layout.height.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        renderer.render(Canvas(bitmap), state)
        return renderer.drawnYenRight
    }

    // =====================================================================================
    // The arc itself
    // =====================================================================================

    @Test
    fun `a square-cornered display constrains nothing`() {
        val l = ScreenLayout.compute(2142f, 964f)
        assertEquals(0f, l.cornerRadius, 0f)
        assertEquals(2142f, l.cornerSafeRight(0f), 0f)
        assertEquals(2142f, l.cornerSafeRight(500f), 0f)
    }

    @Test
    fun `below the arc, the full width is available again`() {
        val l = ScreenLayout.compute(2142f, 964f, cornerRadius = 100f)
        // At exactly the radius the arc has met the straight edge.
        assertEquals(2142f, l.cornerSafeRight(100f), 0.01f)
        assertEquals(2142f, l.cornerSafeRight(900f), 0.01f)
    }

    @Test
    fun `inside the arc, the usable width follows the circle`() {
        val l = ScreenLayout.compute(2142f, 964f, cornerRadius = 100f)
        // Centre at (2042, 100). At y=100-60=40 the boundary is 2042 + sqrt(100^2 - 60^2) = 2122.
        assertEquals(2122f, l.cornerSafeRight(40f), 0.01f)
        // At the very top edge only the straight run at x <= 2042 exists.
        assertEquals(2042f, l.cornerSafeRight(0f), 0.01f)
        // Monotonic: the lower you go, the more room there is, and never more than the full width.
        var previous = 0f
        for (y in 0..120 step 5) {
            val x = l.cornerSafeRight(y.toFloat())
            assertTrue("must not narrow as y grows (at y=$y)", x >= previous)
            assertTrue("must never exceed the display", x <= 2142f)
            previous = x
        }
    }

    @Test
    fun `a point above the top edge is clamped rather than producing a NaN`() {
        val l = ScreenLayout.compute(2142f, 964f, cornerRadius = 100f)
        val x = l.cornerSafeRight(-40f)
        assertTrue("must be a real number, was $x", !x.isNaN())
        assertEquals("treated as the top edge", 2042f, x, 0.01f)
    }

    // =====================================================================================
    // The counter, drawn
    // =====================================================================================

    @Test
    fun `landscape pulls the counter clear of the corner`() {
        val bare = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f).layout
        val rounded = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f, cornerRadiusPx = radiusPx).layout

        val without = drawnYenRight(bare)
        val with = drawnYenRight(rounded)

        assertEquals("the unrounded case is the old behaviour", bare.safe.right - 20f, without, 0.01f)
        assertTrue("a rounded corner must pull it in, was $with vs $without", with < without)
        // It must clear the arc at the glyphs' own height, with the 20-unit margin intact.
        assertTrue(
            "the counter still ends inside the display's own boundary",
            with <= rounded.cornerSafeRight(50f) - 20f + 0.01f
        )
    }

    /**
     * Upside-down portrait: the cutout moves to the BOTTOM, so `safe.top` is 0 and the counter
     * sits in a bare corner exactly as it does in landscape. The owner called this one out
     * specifically.
     */
    @Test
    fun `upside-down portrait pulls the counter clear too`() {
        val flipped = DesignSpace.metricsFor(
            1280f, 2844f, insetBottomPx = 128f, cornerRadiusPx = radiusPx
        ).layout
        assertEquals("the fixture really does leave the top bare", 0f, flipped.safe.top, 0f)

        val with = drawnYenRight(flipped)
        assertTrue("must be pulled in from the edge", with < flipped.safe.right - 20f)
    }

    /**
     * The constraint this whole branch lives under. Right-way-up portrait's cutout already puts
     * the glyphs below a Pixel 9 Pro's arc, so the clearance computes to nothing and the counter
     * is drawn at the identical x — asserted at ZERO tolerance, and with the cutout as well as
     * without, since zero-inset-only portrait fixtures are what got the first attempt reverted.
     */
    @Test
    fun `right-way-up portrait does not move, with a cutout or without`() {
        for (cutout in listOf(0f, 128f)) {
            val bare = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = cutout).layout
            val rounded = DesignSpace.metricsFor(
                1280f, 2844f, insetTopPx = cutout, cornerRadiusPx = radiusPx
            ).layout
            val without = drawnYenRight(bare)
            val with = drawnYenRight(rounded)
            if (cutout > 0f) {
                assertEquals(
                    "portrait with a cutout must be bit-identical (radius ${radiusPx}px)",
                    without, with, 0f
                )
            } else {
                // With no cutout the counter sits in the bare corner in portrait too, so it is
                // genuinely clipped there and moving it is a fix, not a regression. Pinned as a
                // direction rather than an identity so the asymmetry is on the record.
                assertTrue("a bare portrait corner clips, so it may move inward", with <= without)
            }
        }
    }

    /**
     * How far portrait could ever move on a device whose arc DOES reach the glyphs: a fraction of
     * a unit, and only because the counter is genuinely being clipped by that much today. Swept
     * rather than asserted at one radius, because the owner's real corner radius is the device's
     * to report and this is the property that has to hold for all of them.
     */
    @Test
    fun `any portrait movement is sub-unit and only ever inward`() {
        val bare = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f).layout
        val without = drawnYenRight(bare)
        for (r in listOf(0f, 50f, 100f, 150f, 175f, 200f)) {
            val rounded = DesignSpace.metricsFor(
                1280f, 2844f, insetTopPx = 128f, cornerRadiusPx = r
            ).layout
            val with = drawnYenRight(rounded)
            assertTrue("radius $r must never push the counter OUTWARD", with <= without)
            assertTrue(
                "radius $r moved portrait by ${without - with}, which is more than a rounding",
                without - with < 4f
            )
        }
    }

    // =====================================================================================
    // The wiring
    // =====================================================================================

    /**
     * The radius has to survive the trip from `applyInsets` into the layout the renderer reads,
     * or the arc maths above is correct and never consulted.
     *
     * Insets are applied BEFORE `surfaceChanged`, which is this suite's standing convention (see
     * `PanelFocusParityTest.pad`) and is forced by Robolectric rather than chosen: `applyInsets`
     * only recomputes when the VIEW has been laid out, and a Robolectric view never is, so it
     * stores the values and the following `surfaceChanged` picks them up. On a device the layout
     * pass has happened by the time the window delivers insets, so the recompute runs there.
     */
    @Test
    fun `the view carries a radius from applyInsets into its layout`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = HangarSurfaceView(context) { _, _ -> }
        assertEquals("nothing is assumed before one arrives", 0f, view.layout.cornerRadius, 0f)

        view.applyInsets(128f, 0f, 0f, 0f, radiusPx)
        view.surfaceChanged(view.holder, 0, 2844, 1280)

        val scale = DesignSpace.metricsFor(2844f, 1280f).renderScale
        assertEquals(
            "the radius is converted to design units like every other inset",
            radiusPx / scale, view.layout.cornerRadius, 0.01f
        )
        assertEquals(
            "and the side cutout still arrives with it",
            128f / scale, view.layout.safe.left, 0.01f
        )
    }

    /**
     * The same delivery the other way round — a radius arriving at a view that is already sized,
     * which is the order a rotation produces. `applyInsets`'s early-out compares the radius as
     * well as the four insets, so a radius that changes on its own still forces the recompute;
     * without that it would be dropped whenever the insets happened to be unchanged.
     */
    @Test
    fun `a radius arriving after the surface is sized still reaches the layout`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = HangarSurfaceView(context) { _, _ -> }
        view.applyInsets(128f, 0f, 0f, 0f, 0f)
        view.surfaceChanged(view.holder, 0, 2844, 1280)
        assertEquals(0f, view.layout.cornerRadius, 0f)

        // Same insets, new radius: the early-out must not swallow this.
        view.applyInsets(128f, 0f, 0f, 0f, radiusPx)
        view.surfaceChanged(view.holder, 0, 2844, 1280)

        val scale = DesignSpace.metricsFor(2844f, 1280f).renderScale
        assertEquals(radiusPx / scale, view.layout.cornerRadius, 0.01f)
    }

    /** A rotation must not lose it — `portraitShaped` is a transposition, not a reset. */
    @Test
    fun `the portrait-shaped layout keeps the radius`() {
        val land = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f, cornerRadiusPx = radiusPx).layout
        assertEquals(land.cornerRadius, DesignSpace.portraitShaped(land).cornerRadius, 0f)
    }
}
