package com.astroloop.game.hangar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.LayoutRect
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.PilotDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tap that misses a card INSIDE an open panel does nothing; only the button, a tap outside the
 * panel, or a swipe closes it.
 *
 * The owner reported this on 2026-09-20, from the device, against both panels: "when I open the
 * roster, pressing a pilot selects it. However, sometimes it closes the roster instead." The cause
 * was that `handlePanelTap` hit-tested the CARDS and let everything else fall to a
 * backdrop-closes catch-all — but a panel box is strictly bigger than the grid inside it, by
 * `PAD_H`/`PAD_V` all round, by a gutter between every pair of cards, and by the empty half-row a
 * reflowed board leaves. Every one of those points sits under the scrim, looks like part of the
 * panel, and used to dismiss it.
 *
 * These drive the real view with real MotionEvents through the real renderer's published rects,
 * so they fail if the box stops being published, if the gate moves above the card resolution, or
 * if the catch-all comes back — not merely if some geometry helper changes its mind.
 *
 * Every test names the point it taps in terms of the drawn geometry rather than a literal, since
 * the interesting points (a gutter, the padding, the short row's gap) only exist relative to the
 * cards actually laid out on that profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PanelDeadSpaceTest {

    private lateinit var persistence: PersistenceManager

    /** A rotated Pixel 9 Pro — the owner's device, with the 128px cutout it actually has. */
    private val land = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f)

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
        persistence.setYen(1_000_000)
        for (i in 0 until PilotDefinitions.getPilotCount()) {
            PilotDefinitions.getPilotByIndex(i)?.let { persistence.unlockPilot(it.id) }
        }
    }

    private fun view(page: Int, panel: HangarPanels.Panel): HangarSurfaceView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = HangarSurfaceView(context) { _, _ -> }
        view.applyInsets(128f, 0f, 0f, 0f)
        view.surfaceChanged(view.holder, 0, 2844, 1280)
        view.state.actualYen = persistence.getYen()
        view.state.currentPage = page
        view.state.phase = HangarPhase.BROWSING
        view.state.openPanel = panel
        view.state.panelFade = 1f
        frame(view)
        return view
    }

    private fun frame(view: HangarSurfaceView) {
        val bitmap = Bitmap.createBitmap(
            land.width.toInt(), land.height.toInt(), Bitmap.Config.ARGB_8888
        )
        view.renderer.render(Canvas(bitmap), view.state)
    }

    private fun tap(view: HangarSurfaceView, x: Float, y: Float) {
        val scale = land.renderScale
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0L, 0L, action, x * scale, y * scale, 0)
            try {
                view.onTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun cards(view: HangarSurfaceView): List<LayoutRect> = view.renderer.panelCardRects.toList()

    // =====================================================================================
    // The reported bug
    // =====================================================================================

    /**
     * The exact miss the owner hit: the gutter between two pilot cards in the same row.
     *
     * RED against the old catch-all — verified by deleting the `panelBoxRect` guard, which leaves
     * `openPanel` null and `closingPanel` CREW here.
     */
    @Test
    fun `a tap in the gutter between two pilot cards leaves the roster open`() {
        val view = view(page = 0, panel = HangarPanels.Panel.CREW)
        val rects = cards(view)
        assertEquals("the crew panel publishes all twelve pilots", 12, rects.size)

        // Between card 0 and card 1, vertically mid-card: inside the box, on no card at all.
        val gutterX = (rects[0].right + rects[1].left) / 2f
        assertTrue("the two cards really are separated", rects[1].left > rects[0].right)
        assertNull("the point must belong to no card", PanelGrid.indexAt(rects, gutterX, rects[0].centerY))

        tap(view, gutterX, rects[0].centerY)

        assertEquals("the roster stays open", HangarPanels.Panel.CREW, view.state.openPanel)
        assertNull("and is not even beginning to close", view.state.closingPanel)
    }

    /** The same miss in the other axis: the panel's own padding, above the first row of cards. */
    @Test
    fun `a tap in the panel's padding leaves the roster open`() {
        val view = view(page = 0, panel = HangarPanels.Panel.CREW)
        val box = view.renderer.panelBoxRect
        assertNotNull("the box must be published for the tap path to read", box)
        val rects = cards(view)

        val padY = (box!!.top + rects[0].top) / 2f
        assertTrue("there really is padding above the cards", rects[0].top > box.top)
        assertNull("the point must belong to no card", PanelGrid.indexAt(rects, rects[0].centerX, padY))

        tap(view, rects[0].centerX, padY)

        assertEquals("the roster stays open", HangarPanels.Panel.CREW, view.state.openPanel)
        assertNull(view.state.closingPanel)
    }

    /**
     * The shop board reflows to 5+4 on this profile, so its second row is centred and leaves a
     * half-tile of empty box at each end — the widest dead space anywhere in the feature, and the
     * owner reported the same symptom here ("the SHOP button has the same issue").
     */
    @Test
    fun `a tap in the short row's empty end leaves the shop open`() {
        val view = view(page = 2, panel = HangarPanels.Panel.SHOP)
        val box = view.renderer.panelBoxRect!!
        val rects = cards(view)
        assertEquals("all nine tiles are published", 9, rects.size)

        // The last row holds tiles 5..8 under a row of five, so it is inset from the box's left.
        val lastRowLeft = rects[5].left
        val emptyX = (box.left + lastRowLeft) / 2f
        assertTrue("the short row really is inset", lastRowLeft > box.left + HangarPanels.PAD_H)
        assertNull("the point must belong to no tile", PanelGrid.indexAt(rects, emptyX, rects[5].centerY))

        tap(view, emptyX, rects[5].centerY)

        assertEquals("the shop stays open", HangarPanels.Panel.SHOP, view.state.openPanel)
        assertNull(view.state.closingPanel)
    }

    // =====================================================================================
    // ...without breaking the three things that DO close it
    // =====================================================================================

    /**
     * The owner's rule has three doors, and widening the dead space must not have shut any of
     * them. This is the one most at risk: the box now swallows taps, so a point just OUTSIDE it
     * has to still reach the backdrop rule.
     */
    @Test
    fun `a tap just outside the box still closes the panel`() {
        val view = view(page = 0, panel = HangarPanels.Panel.CREW)
        val box = view.renderer.panelBoxRect!!
        val buttons = view.renderer.panelButtonRects.values.toList()

        // One unit outside the box's left edge, clear of the button, level with its middle.
        val x = box.left - 1f
        assertTrue("the probe must be outside the box", !box.contains(x, box.centerY))
        assertTrue(
            "and must not be on the button, which has its own branch",
            buttons.none { it.contains(x, box.centerY) }
        )

        tap(view, x, box.centerY)

        assertNull("the backdrop still closes", view.state.openPanel)
        assertEquals(HangarPanels.Panel.CREW, view.state.closingPanel)
    }

    /** Door two: the button itself, which is outside the box and has its own branch above. */
    @Test
    fun `the button still closes the panel it opened`() {
        val view = view(page = 0, panel = HangarPanels.Panel.CREW)
        val button = view.renderer.panelButtonRects.getValue(HangarPanels.Panel.CREW)

        tap(view, button.centerX, button.centerY)

        assertNull(view.state.openPanel)
        assertEquals(HangarPanels.Panel.CREW, view.state.closingPanel)
    }

    /** And a card still does what it is there for, rather than being swallowed with the gutters. */
    @Test
    fun `a tap on a pilot card still selects that pilot`() {
        val view = view(page = 0, panel = HangarPanels.Panel.CREW)
        val rects = cards(view)
        val target = 3

        tap(view, rects[target].centerX, rects[target].centerY)

        assertEquals("the panel stays open", HangarPanels.Panel.CREW, view.state.openPanel)
        assertEquals("and the pilot is selected", target, view.state.selectedPilotIndex)
    }

    /**
     * The dead-space rule must not outlive the panel: once it is closing, `handlePanelTap`'s
     * earlier gate already refuses everything, and the box must not start leaking taps to the
     * room underneath just because the point is inside it.
     */
    @Test
    fun `a tap in the dead space of a closing panel changes nothing`() {
        val view = view(page = 0, panel = HangarPanels.Panel.CREW)
        val box = view.renderer.panelBoxRect!!
        val rects = cards(view)
        val before = view.state.selectedPilotIndex

        view.state.openPanel = null
        view.state.closingPanel = HangarPanels.Panel.CREW
        view.state.panelFade = 0.5f
        frame(view)

        tap(view, (rects[0].right + rects[1].left) / 2f, rects[0].centerY)

        assertEquals("still closing, not reopened", HangarPanels.Panel.CREW, view.state.closingPanel)
        assertNull(view.state.openPanel)
        assertEquals("and nothing underneath was touched", before, view.state.selectedPilotIndex)
        assertTrue("the box is still published while it fades", view.renderer.panelBoxRect != null)
    }

    /**
     * Portrait publishes no panel at all, so the new guard must be inert there — a null box, and
     * every tap going to the room exactly as it always has.
     */
    @Test
    fun `portrait publishes no panel box, so nothing about its taps changes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = HangarSurfaceView(context) { _, _ -> }
        view.applyInsets(0f, 128f, 0f, 0f)
        view.surfaceChanged(view.holder, 0, 1280, 2844)
        view.state.currentPage = 0
        view.state.phase = HangarPhase.BROWSING
        // Even with a panel somehow set, the layer's own gate refuses to draw one in portrait.
        view.state.openPanel = HangarPanels.Panel.CREW
        view.state.panelFade = 1f

        val portrait = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f)
        val bitmap = Bitmap.createBitmap(
            portrait.width.toInt(), portrait.height.toInt(), Bitmap.Config.ARGB_8888
        )
        view.renderer.render(Canvas(bitmap), view.state)

        assertNull("portrait draws no panel, so it publishes no box", view.renderer.panelBoxRect)
        assertTrue("and no panel cards", view.renderer.panelCardRects.isEmpty())
    }
}
