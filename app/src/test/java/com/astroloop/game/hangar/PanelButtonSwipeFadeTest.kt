package com.astroloop.game.hangar

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.ScreenLayout
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The CREW / SHOP / SLOT buttons fade out as their page is swiped away, instead of staying lit
 * through the swipe and vanishing when it commits.
 *
 * The owner, 2026-09-20: "the buttons stay visible, until you let go of the screen to swipe over,
 * and then buttons (seem to) instantly disappear. I need the buttons to fade out once you start
 * your swipe, similar as how icons disappear when you start the launch swipe on the launch
 * screen." The buttons are screen chrome that belongs to a page, so nothing moved them when the
 * page moved, and `drawPanelLayer` drew the CURRENT page's buttons only — so the moment
 * `currentPage` flipped they were simply not drawn. An instant disappearance of exactly the kind
 * the project forbids.
 *
 * Two halves, tested separately because they fail separately:
 *  - [HangarGestures.pageSwipeFade], the curve, including the thing that makes it a fade rather
 *    than a fade plus a jump: it is CONTINUOUS across the commit;
 *  - the renderer, which must keep DRAWING a departing page's buttons while they fade while
 *    publishing only the current page's as targets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PanelButtonSwipeFadeTest {

    private lateinit var persistence: PersistenceManager

    /** A rotated Pixel 9 Pro. In landscape the stride is one screen. */
    private val land = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f)
    private val stride get() = land.width

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
    }

    // =====================================================================================
    // The curve
    // =====================================================================================

    /**
     * An arbitrary travel distance for the curve's own tests. The REAL one is measured from the
     * room's distance to the button — see the renderer half below, which is where that is pinned.
     */
    private val travel = 600f

    @Test
    fun `a settled page is fully lit and its neighbours are not drawn at all`() {
        assertEquals(1f, HangarGestures.pageSwipeFade(0, 0, 0f, stride, travel), 0.0001f)
        assertEquals(0f, HangarGestures.pageSwipeFade(2, 0, 0f, stride, travel), 0.0001f)
        assertEquals(1f, HangarGestures.pageSwipeFade(2, 2, 0f, stride, travel), 0.0001f)
        assertEquals(0f, HangarGestures.pageSwipeFade(0, 2, 0f, stride, travel), 0.0001f)
    }

    @Test
    fun `the fade completes exactly at the travel it is given`() {
        assertEquals(0.5f, HangarGestures.pageSwipeFade(0, 0, travel * 0.5f, stride, travel), 0.0001f)
        assertEquals(0f, HangarGestures.pageSwipeFade(0, 0, travel, stride, travel), 0.0001f)
        assertEquals(0f, HangarGestures.pageSwipeFade(0, 0, travel * 2f, stride, travel), 0.0001f)
    }

    @Test
    fun `it fades the same swiping either way`() {
        assertEquals(
            HangarGestures.pageSwipeFade(2, 2, travel * 0.5f, stride, travel),
            HangarGestures.pageSwipeFade(2, 2, -travel * 0.5f, stride, travel),
            0.0001f
        )
        assertEquals(0.5f, HangarGestures.pageSwipeFade(2, 2, -travel * 0.5f, stride, travel), 0.0001f)
    }

    /**
     * The one that makes it a fade rather than a fade plus a jump.
     *
     * On release `HangarSurfaceView` moves the page index and the scroll offset together —
     * `pageScrollOffset -= stride` alongside `currentPage + 1` — so the page does not leap. The
     * fade must be written from both terms to inherit that. A fade computed from
     * `abs(pageScrollOffset)` alone would read part faded the instant before the commit and
     * fully lit the instant after, snapping the buttons back as the page left.
     */
    @Test
    fun `the fade does not jump at the moment the swipe commits`() {
        // A release early enough that the fade has not finished — the interesting case.
        val atRelease = travel * 0.6f
        val before = HangarGestures.pageSwipeFade(0, 0, atRelease, stride, travel)
        // Exactly what the release does: index up one, offset back one stride.
        val after = HangarGestures.pageSwipeFade(0, 1, atRelease - stride, stride, travel)
        assertEquals("the fade must be continuous across the commit", before, after, 0.0001f)
        assertTrue("and mid-fade at that point, not already gone", before > 0f && before < 1f)
    }

    @Test
    fun `it keeps fading to nothing as the page settles after the commit`() {
        val midSettle = HangarGestures.pageSwipeFade(0, 1, -stride + travel * 0.5f, stride, travel)
        val settled = HangarGestures.pageSwipeFade(0, 1, 0f, stride, travel)
        assertTrue("still visible mid-settle", midSettle > 0f)
        assertEquals("and gone once settled", 0f, settled, 0.0001f)
    }

    @Test
    fun `a zero stride or travel cannot produce a NaN`() {
        assertEquals(1f, HangarGestures.pageSwipeFade(0, 0, 0f, 0f, travel), 0f)
        assertEquals(0f, HangarGestures.pageSwipeFade(0, 1, 0f, 0f, travel), 0f)
        assertEquals(1f, HangarGestures.pageSwipeFade(0, 0, 0f, stride, 0f), 0f)
        assertEquals(0f, HangarGestures.pageSwipeFade(0, 1, 0f, stride, 0f), 0f)
    }

    // =====================================================================================
    // The renderer
    // =====================================================================================

    private fun roomWidthFor(layout: ScreenLayout, swDp: Int = 427): Float {
        val p = DesignSpace.portraitShaped(layout)
        return HangarMetrics.roomWidth(p.width, p.content.width, swDp)
    }

    private fun render(
        layout: ScreenLayout, page: Int, offset: Float
    ): HangarRenderer {
        val renderer = HangarRenderer(persistence)
        renderer.initialize(layout, roomWidthFor(layout))
        val state = HangarState(persistence)
        state.pilotScreenWidth = layout.width
        state.pilotScreenHeight = layout.height
        state.roomWidth = roomWidthFor(layout)
        state.currentPage = page
        state.pageScrollOffset = offset
        state.phase = HangarPhase.BROWSING
        val bitmap = Bitmap.createBitmap(
            layout.width.toInt().coerceAtLeast(1), layout.height.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        renderer.render(Canvas(bitmap), state)
        return renderer
    }

    @Test
    fun `sitting on the crew page draws its button fully lit and tappable`() {
        val r = render(land.layout, page = 0, offset = 0f)
        assertEquals(1f, r.drawnButtonAlphas.getValue(HangarPanels.Panel.CREW), 0.0001f)
        assertTrue(
            "and it is a target", r.panelButtonRects.containsKey(HangarPanels.Panel.CREW)
        )
    }

    @Test
    fun `starting to swipe away dims the button before anything has committed`() {
        val r = render(land.layout, page = 0, offset = crewGap() * 0.5f)
        assertEquals(
            "halfway to the room is half faded",
            0.5f, r.drawnButtonAlphas.getValue(HangarPanels.Panel.CREW), 0.0001f
        )
        assertTrue(
            "still the current page, so still pressable",
            r.panelButtonRects.containsKey(HangarPanels.Panel.CREW)
        )
    }

    // =====================================================================================
    // Gone by the time the room arrives (owner, 2026-09-20)
    // =====================================================================================

    /**
     * The clear space between the crew button and the bar room at rest, straight from the
     * production helpers the renderer uses — so this test says "when the room reaches the
     * button", not "at some number I typed in".
     */
    private fun crewGap(): Float {
        val (cardW, cardH) = panelCardSize()
        val rw = roomWidthFor(land.layout)
        val anchor = RoomAnchor.anchorX(0, land.width, rw, true)
        val pw = RoomAnchor.pageWidth(0, land.width, rw, true)
        return HangarPanels.buttonRoomGap(
            HangarPanels.Panel.CREW, anchor, anchor + pw,
            HangarPanels.buttonHome(HangarPanels.Panel.CREW, land.layout.safe, cardW, cardH)
        )
    }

    private fun panelCardSize(): Pair<Float, Float> {
        val p = DesignSpace.portraitShaped(land.layout)
        return GridGeometry.pilotCardSize(GridGeometry.pilotGridBounds(p.content, p.width, p.width))
    }

    /**
     * The owner's actual ask: "completely gone once the bar hits the button".
     *
     * Asserted as the geometric coincidence rather than as an alpha alone — at the offset where
     * the fade reaches zero, the bar room's drawn left edge is exactly the button's right edge.
     * That is what ties the fade to the thing the player sees arriving, and it is what a fade
     * measured in strides could never satisfy on more than one profile.
     */
    @Test
    fun `the crew button is gone at the exact offset the bar reaches it`() {
        val gap = crewGap()
        val rw = roomWidthFor(land.layout)
        val anchor = RoomAnchor.anchorX(0, land.width, rw, true)
        val (cardW, cardH) = panelCardSize()
        val button = HangarPanels.buttonHome(HangarPanels.Panel.CREW, land.layout.safe, cardW, cardH)

        // Where the room's left edge sits once the page has been dragged `gap` out.
        val roomLeftAtContact = anchor - gap
        assertEquals(
            "the fade's endpoint IS the moment the room meets the button",
            button.right, roomLeftAtContact, 0.01f
        )

        val atContact = render(land.layout, page = 0, offset = gap)
        assertFalse(
            "the button must be gone by then, not merely dim: ${atContact.drawnButtonAlphas}",
            atContact.drawnButtonAlphas.containsKey(HangarPanels.Panel.CREW)
        )

        // ...and still there just before, so this is a fade finishing and not an early cut.
        val justBefore = render(land.layout, page = 0, offset = gap * 0.9f)
        val alpha = justBefore.drawnButtonAlphas.getValue(HangarPanels.Panel.CREW)
        assertTrue("still faintly there just before contact, was $alpha", alpha > 0f && alpha < 0.2f)
    }

    /** The same on the other side, where the shop room closes on the stack from the left. */
    @Test
    fun `the shop buttons are gone at the exact offset the shop reaches them`() {
        val (cardW, cardH) = panelCardSize()
        val rw = roomWidthFor(land.layout)
        val anchor = RoomAnchor.anchorX(2, land.width, rw, true)
        val pw = RoomAnchor.pageWidth(2, land.width, rw, true)
        val button = HangarPanels.buttonHome(HangarPanels.Panel.SHOP, land.layout.safe, cardW, cardH)
        val gap = HangarPanels.buttonRoomGap(HangarPanels.Panel.SHOP, anchor, anchor + pw, button)

        // Swiping off the shop page runs the offset NEGATIVE, carrying the room right.
        assertEquals(
            "the fade's endpoint IS the moment the room meets the buttons",
            button.left, anchor + pw + gap, 0.01f
        )

        val atContact = render(land.layout, page = 2, offset = -gap)
        assertFalse(
            "SHOP must be gone: ${atContact.drawnButtonAlphas}",
            atContact.drawnButtonAlphas.containsKey(HangarPanels.Panel.SHOP)
        )
        assertFalse(
            "and SLOT with it", atContact.drawnButtonAlphas.containsKey(HangarPanels.Panel.SLOT)
        )
    }

    /**
     * The measured gaps on the profiles this feature ships against, as literals — so a change to
     * the room width, the button inset or the card size shows up here as a number rather than as
     * a button that quietly starts surviving under the bar again.
     *
     * The 16:9 phone is the tight one: its room is wider (1204.9 against 964.1), so it leaves the
     * button 494.9 of travel rather than 735.7. All of them are far above the divide-by-zero
     * floor, which is the other thing this pins.
     */
    @Test
    fun `every shipping profile leaves the button real room to fade in`() {
        val profiles = listOf(
            Triple(2844f, 1280f, 427) to 735.75f,   // Pixel 9 Pro, no cutout
            Triple(1920f, 1080f, 360) to 494.93f,   // 16:9 phone / 1080p TV
            Triple(2560f, 1600f, 800) to 739.80f,   // tablet
            Triple(3840f, 2160f, 960) to 739.80f    // 4K TV
        )
        for ((profile, expected) in profiles) {
            val (w, h, swDp) = profile
            val m = DesignSpace.metricsFor(w, h)
            val p = DesignSpace.portraitShaped(m.layout)
            val rw = HangarMetrics.roomWidth(p.width, p.content.width, swDp)
            val (cardW, cardH) = GridGeometry.pilotCardSize(
                GridGeometry.pilotGridBounds(p.content, p.width, p.width)
            )
            val anchor = RoomAnchor.anchorX(0, m.width, rw, true)
            val pw = RoomAnchor.pageWidth(0, m.width, rw, true)
            val gap = HangarPanels.buttonRoomGap(
                HangarPanels.Panel.CREW, anchor, anchor + pw,
                HangarPanels.buttonHome(HangarPanels.Panel.CREW, m.layout.safe, cardW, cardH)
            )
            assertEquals("${w.toInt()}x${h.toInt()}", expected, gap, 0.01f)
            assertTrue(
                "${w.toInt()}x${h.toInt()}: $gap must clear the floor by a wide margin",
                gap > HangarPanels.MIN_ROOM_GAP * 4f
            )
        }
    }

    /**
     * The owner's actual report. Post-commit the page index has already flipped to the launchpad
     * while the scroll offset settles — and the crew button must still be on screen, fading,
     * rather than gone. Before the fix `drawPanelLayer` drew `panelsOn(currentPage)` only, so
     * page 1 drew nothing and the button disappeared on that frame.
     */
    @Test
    fun `after the swipe commits the button is still drawn, fading, on the launchpad`() {
        val r = render(land.layout, page = 1, offset = -stride * 0.7f)
        val alpha = r.drawnButtonAlphas[HangarPanels.Panel.CREW]
        assertTrue("the crew button must still be drawn while it fades, was $alpha", alpha != null)
        assertTrue("and part faded, not full", alpha!! > 0f && alpha < 1f)
        assertFalse(
            "but it is no longer a target — the player is on another page",
            r.panelButtonRects.containsKey(HangarPanels.Panel.CREW)
        )
    }

    @Test
    fun `once the launchpad has settled no panel button is drawn at all`() {
        val r = render(land.layout, page = 1, offset = 0f)
        assertTrue("nothing drawn: ${r.drawnButtonAlphas}", r.drawnButtonAlphas.isEmpty())
        assertTrue("and nothing published", r.panelButtonRects.isEmpty())
    }

    /**
     * Swiping toward the shop brings its pair up the same curve, before the page commits.
     *
     * Worth stating plainly, because it caught a wrong fixture while this was being written:
     * "arriving" and "leaving" are the SAME state. Page 2 sitting 0.25 of a stride off screen
     * looks identical whether the player is swiping toward it or away from it, and the fade is a
     * function of where the page is, not of how it got there. There is nothing to test twice.
     */
    @Test
    fun `the arriving page's buttons fade in as it comes`() {
        val r = render(land.layout, page = 1, offset = stride * 0.75f)
        val shop = r.drawnButtonAlphas[HangarPanels.Panel.SHOP]
        val slot = r.drawnButtonAlphas[HangarPanels.Panel.SLOT]
        assertTrue("SHOP must be coming up, was $shop", shop != null && shop > 0f && shop < 1f)
        assertEquals("the pair arrives together", shop, slot)
        assertFalse(
            "not pressable until the page is actually current",
            r.panelButtonRects.containsKey(HangarPanels.Panel.SHOP)
        )
    }

    @Test
    fun `the shop page fades out on its own swipe just as the crew page does`() {
        val settled = render(land.layout, page = 2, offset = 0f)
        assertEquals(1f, settled.drawnButtonAlphas.getValue(HangarPanels.Panel.SHOP), 0.0001f)
        assertEquals(1f, settled.drawnButtonAlphas.getValue(HangarPanels.Panel.SLOT), 0.0001f)

        // Still ON the shop page, dragging back toward the launchpad: the pre-commit half of
        // the swipe, which is the moment the owner described ("fade out once you START").
        val leaving = render(land.layout, page = 2, offset = -stride * 0.3f)
        val shop = leaving.drawnButtonAlphas.getValue(HangarPanels.Panel.SHOP)
        assertTrue("SHOP fades rather than cutting, was $shop", shop > 0f && shop < 1f)
        assertEquals(
            "and SLOT with it", shop, leaving.drawnButtonAlphas.getValue(HangarPanels.Panel.SLOT)
        )
    }

    /** Portrait has no panel layer at all, so none of this may put one there. */
    @Test
    fun `portrait draws no buttons at any scroll offset`() {
        val portrait = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f).layout
        for (page in listOf(0, 1, 2)) {
            for (offset in listOf(0f, 200f, -200f)) {
                val r = render(portrait, page, offset)
                assertTrue(
                    "portrait must stay unchanged (page=$page offset=$offset)",
                    r.drawnButtonAlphas.isEmpty() && r.panelButtonRects.isEmpty()
                )
            }
        }
    }
}
