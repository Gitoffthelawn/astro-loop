package com.astroloop.game.hangar

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.ScreenLayout
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
 * The crew panel: the geometry, and the pass that actually draws it.
 *
 * The first half composes the pure pieces the way the renderer composes them. The second half
 * drives a real [HangarRenderer.render] over a Robolectric [Canvas] and reads back the rects it
 * published — so the tap path is tested against what was drawn rather than against a second,
 * parallel derivation of it. A re-derivation cannot catch a call site that stops calling the seam,
 * which is exactly the gap the code review found.
 *
 * Every portrait assertion runs WITH a cutout as well as without: the 2026-09-14 revert happened
 * because a suite of portrait-identity tests used zero insets and so tested a configuration the
 * owner's device does not have.
 *
 * Robolectric because [HangarState] and [HangarRenderer] both need a [PersistenceManager]; the
 * numbers below are all plain Kotlin layout maths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrewPanelTest {

    private lateinit var persistence: PersistenceManager

    // A rotated Pixel 9 Pro: 2844x1280 physical, a 128px cutout on the (landscape) left edge.
    private val land = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f)
    private val portrait = DesignSpace.portraitShaped(land.layout)
    private val safe = land.layout.safe

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
    }

    /**
     * Exactly what `HangarSurfaceView.applyScreenDimensions` computes — the portrait-shaped design
     * space, then `HangarMetrics.roomWidth` over it. swDp 427 is a phone, below the sw600 gate.
     */
    private fun roomWidthFor(layout: ScreenLayout, swDp: Int = 427): Float {
        val p = DesignSpace.portraitShaped(layout)
        return HangarMetrics.roomWidth(p.width, p.content.width, swDp)
    }

    private fun cardSize(): Pair<Float, Float> {
        val bounds = GridGeometry.pilotGridBounds(portrait.content, portrait.width, portrait.width)
        return GridGeometry.pilotCardSize(bounds)
    }

    private fun crewArrangement(): PanelGrid.Arrangement {
        val (w, h) = cardSize()
        return PanelGrid.arrange(
            12, w, h, GridGeometry.PILOT_GAP, GridGeometry.PILOT_COLS,
            safe.width - HangarPanels.SCREEN_EDGE * 2f - HangarPanels.PAD_H * 2f,
            safe.height - HangarPanels.PAD_V * 2f
        )
    }

    // =====================================================================================
    // The geometry, composed from the pure pieces
    // =====================================================================================

    @Test
    fun `panel cards are the size they are in portrait`() {
        val portraitOnly = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f).layout
        val expected = GridGeometry.pilotCardSize(
            GridGeometry.pilotGridBounds(portraitOnly.content, portraitOnly.width, portraitOnly.width)
        )
        assertEquals(expected.first, cardSize().first, 0.01f)
        assertEquals(expected.second, cardSize().second, 0.01f)
    }

    @Test
    fun `a phone shows twelve pilots as six by two`() {
        val a = crewArrangement()
        assertEquals(6, a.cols)
        assertEquals(2, a.rows)
    }

    @Test
    fun `the renderer sizes the crew panel's content from the same arrangement`() {
        val renderer = HangarRenderer(persistence)
        renderer.initialize(land.layout, roomWidthFor(land.layout))
        val a = crewArrangement()
        val (w, h) = renderer.panelContentSize(HangarPanels.Panel.CREW)
        assertEquals(a.width, w, 0.01f)
        assertEquals(a.height, h, 0.01f)
    }

    @Test
    fun `the panel stays inside the screen and clear of its tab`() {
        val (w, h) = cardSize()
        val a = crewArrangement()
        val box = HangarPanels.panelBox(HangarPanels.Panel.CREW, a.width, a.height, safe)
        val home = HangarPanels.buttonHome(HangarPanels.Panel.CREW, safe, w, h)
        val tab = HangarPanels.tabRect(HangarPanels.Panel.CREW, home, box)
        assertTrue("panel right edge on screen", box.right <= land.width)
        assertTrue("panel left edge on screen", box.left >= 0f)
        assertTrue("tab on screen", tab.left >= 0f)
        assertTrue("tab clear of panel", tab.right <= box.left)
    }

    @Test
    fun `every pilot gets a rect and a tap in one resolves to that pilot`() {
        val (w, h) = cardSize()
        val a = crewArrangement()
        val box = HangarPanels.panelBox(HangarPanels.Panel.CREW, a.width, a.height, safe)
        val rects = PanelGrid.rects(
            a, 12, box.left + HangarPanels.PAD_H, box.top + HangarPanels.PAD_V,
            w, h, GridGeometry.PILOT_GAP
        )
        assertEquals(12, rects.size)
        for ((i, r) in rects.withIndex()) {
            assertEquals(i, PanelGrid.indexAt(rects, r.centerX, r.centerY))
        }
        assertNull(PanelGrid.indexAt(rects, box.left - 50f, box.centerY))
    }

    @Test
    fun `the state starts with no panel open and toggles shut again`() {
        val state = HangarState(persistence)
        assertNull(state.openPanel)
        state.openPanel = HangarPanels.Panel.CREW
        assertNotNull(state.openPanel)
        state.openPanel = null
        assertNull(state.openPanel)
    }

    /**
     * The panel is drawn UNDER the chrome — the yen counter and the nav labels stay above its
     * scrim, because you need to see your money while a panel is up — and `handleTap` resolves
     * the panel BEFORE the nav row. Those two orders only agree while the panel's box stays clear
     * of the nav row's tap band, so that clearance is an invariant, not an accident.
     */
    @Test
    fun `the panel and its button stay clear of the nav row's tap zone`() {
        val (w, h) = cardSize()
        val a = crewArrangement()
        val box = HangarPanels.panelBox(HangarPanels.Panel.CREW, a.width, a.height, safe)
        val home = HangarPanels.buttonHome(HangarPanels.Panel.CREW, safe, w, h)
        // HangarSurfaceView.handleTap: labelY = screenHeight * 0.95f, zone labelY-30 .. labelY+15.
        val navTop = land.height * 0.95f - 30f
        assertTrue("panel bottom ${box.bottom} must clear the nav band at $navTop", box.bottom < navTop)
        assertTrue("button bottom ${home.bottom} must clear the nav band at $navTop", home.bottom < navTop)
    }

    // =====================================================================================
    // The renderer's own pass
    // =====================================================================================

    private fun renderOnce(layout: ScreenLayout, configure: (HangarState) -> Unit): HangarRenderer {
        val renderer = HangarRenderer(persistence)
        renderer.initialize(layout, roomWidthFor(layout))
        val state = HangarState(persistence)
        state.pilotScreenWidth = layout.width
        state.pilotScreenHeight = layout.height
        state.roomWidth = roomWidthFor(layout)
        state.currentPage = 0
        state.phase = HangarPhase.BROWSING
        configure(state)
        val bitmap = Bitmap.createBitmap(
            layout.width.toInt().coerceAtLeast(1), layout.height.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        renderer.render(Canvas(bitmap), state)
        return renderer
    }

    @Test
    fun `the open panel draws its cards at portrait size, in screen space`() {
        val renderer = renderOnce(land.layout) { it.openPanel = HangarPanels.Panel.CREW }
        val (w, h) = cardSize()

        assertEquals(PilotDefinitions.getPilotCount(), renderer.panelCardRects.size)
        for (r in renderer.panelCardRects) {
            assertEquals("a panel card must be a portrait card", w, r.width, 0.01f)
            assertEquals("a panel card must be a portrait card", h, r.height, 0.01f)
            assertTrue("card left ${r.left} is off-screen", r.left >= 0f)
            assertTrue("card right ${r.right} is off-screen", r.right <= land.width)
            assertTrue("card top ${r.top} is off-screen", r.top >= 0f)
            assertTrue("card bottom ${r.bottom} is off-screen", r.bottom <= land.height)
        }
    }

    @Test
    fun `a tap on a drawn card resolves to that card`() {
        val renderer = renderOnce(land.layout) { it.openPanel = HangarPanels.Panel.CREW }

        val rects = renderer.panelCardRects
        // Not vacuous: an empty list would satisfy the loop below on its own.
        assertEquals(PilotDefinitions.getPilotCount(), rects.size)
        for ((i, r) in rects.withIndex()) {
            assertEquals("tap on card $i", i, PanelGrid.indexAt(rects, r.centerX, r.centerY))
        }
        assertNull("a tap on the backdrop belongs to no card", PanelGrid.indexAt(rects, 5f, 5f))
    }

    @Test
    fun `the button is published shut, and becomes the tab when open`() {
        val (w, h) = cardSize()
        val home = HangarPanels.buttonHome(HangarPanels.Panel.CREW, safe, w, h)

        val shut = renderOnce(land.layout) { }
        assertEquals(setOf(HangarPanels.Panel.CREW), shut.panelButtonRects.keys)
        assertEquals(home.left, shut.panelButtonRects[HangarPanels.Panel.CREW]!!.left, 0.01f)
        assertTrue("nothing is drawn as panel content while it is shut", shut.panelCardRects.isEmpty())

        val open = renderOnce(land.layout) { it.openPanel = HangarPanels.Panel.CREW }
        val box = HangarPanels.panelBox(
            HangarPanels.Panel.CREW, crewArrangement().width, crewArrangement().height, safe
        )
        val tab = HangarPanels.tabRect(HangarPanels.Panel.CREW, home, box)
        assertEquals(tab.left, open.panelButtonRects[HangarPanels.Panel.CREW]!!.left, 0.01f)
    }

    @Test
    fun `the launchpad shows no buttons, because it owns no panel`() {
        val renderer = renderOnce(land.layout) { it.currentPage = 1 }
        assertTrue(renderer.panelButtonRects.isEmpty())
        assertTrue(renderer.panelCardRects.isEmpty())
    }

    @Test
    fun `the intro cinematic publishes no buttons`() {
        val renderer = renderOnce(land.layout) {
            it.introCinematic = true
            it.openPanel = HangarPanels.Panel.CREW
        }
        assertTrue("the intro hides all chrome, buttons included", renderer.panelButtonRects.isEmpty())
        assertTrue(renderer.panelCardRects.isEmpty())
    }

    /**
     * The room's hit test has to go where the room's grid went. A grid that is not drawn must not
     * still be hit-testable: without this the empty upper half of the crew room would keep
     * selecting pilots nobody can see.
     */
    @Test
    fun `the room's grid hit test goes with the grid`() {
        val count = PilotDefinitions.getPilotCount()
        val roomW = roomWidthFor(land.layout)
        // The point the room's OWN grid arithmetic calls card 5 in landscape. Asserted first, so
        // the null below cannot pass merely by landing outside every rect.
        val staleBounds = GridGeometry.pilotGridBounds(land.layout.content, roomW, land.width)
        val stale = GridGeometry.pilotCardRects(staleBounds, count)[5]
        assertEquals(
            5, GridGeometry.pilotIndexAt(staleBounds, stale.centerX, stale.centerY, count)
        )
        assertNull(
            "the room keeps no hit test for a grid it does not draw",
            renderOnce(land.layout) { }.getPilotGridIndex(stale.centerX, stale.centerY)
        )

        // Portrait still hit-tests its own drawn card, cutout and all.
        for (cutout in listOf(0f, 128f)) {
            val portraitLayout = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = cutout).layout
            val card = GridGeometry.pilotCardRects(
                GridGeometry.pilotGridBounds(
                    portraitLayout.content, roomWidthFor(portraitLayout), portraitLayout.width
                ),
                count
            )[5]
            assertEquals(
                "cutout=$cutout", 5,
                renderOnce(portraitLayout) { }.getPilotGridIndex(card.centerX, card.centerY)
            )
        }
    }

    @Test
    fun `landscape moves the grid out of the room`() {
        val shut = renderOnce(land.layout) { }
        assertTrue("the room draws no pilot grid in landscape", shut.pilotCardRects.isEmpty())

        // With the panel up the cards ARE drawn, in the panel's screen space — the same rects the
        // panel publishes. pilotCardRects is "the cards as drawn", so in landscape it is screen
        // space; publishBarFocus leaves it alone in landscape and lets the panel publish it.
        val open = renderOnce(land.layout) { it.openPanel = HangarPanels.Panel.CREW }
        assertEquals(open.panelCardRects.size, open.pilotCardRects.size)
        for ((i, r) in open.panelCardRects.withIndex()) {
            assertEquals(r.left, open.pilotCardRects[i].left, 0.01f)
            assertEquals(r.top, open.pilotCardRects[i].top, 0.01f)
        }
    }

    // =====================================================================================
    // A code review: the draw path must be pinned to the fade, and a closing
    // panel must survive a page change
    // =====================================================================================

    /**
     * A review finding: nothing in the draw path was pinned to `?: state.closingPanel` — delete it from
     * `HangarRenderer.drawPanelLayer` and the panel vanishes in one frame on close (the exact bug
     * the fade commit exists to prevent) while every other test in the suite stayed green. This
     * drives the renderer with ONLY `closingPanel` set (no `openPanel`) and asserts its cards are
     * still published, which fails immediately if that fallback is removed or short-circuited.
     */
    @Test
    fun `a panel fading closed still draws its cards, not just one still open`() {
        val renderer = renderOnce(land.layout) {
            it.openPanel = null
            it.closingPanel = HangarPanels.Panel.CREW
            it.panelFade = 0.5f
        }
        assertEquals(
            "closingPanel alone must still publish the panel's cards",
            PilotDefinitions.getPilotCount(), renderer.panelCardRects.size
        )
        assertEquals(
            HangarPanels.Panel.CREW, renderer.panelButtonRects.keys.singleOrNull()
        )
    }

    /**
     * A code review: every test in this suite drives
     * `state.panelFade` through its state-machine transitions (`PanelFadeTest`), but until now
     * none of them inspected what the DRAW path actually did with it — a `saveLayerAlpha` region
     * isn't something a Robolectric software canvas can be probed for pixel-accurately without a
     * real compositing pass. Replacing the `HangarPanels.panelScrimAlpha`/`panelLayerAlpha`
     * calls in `drawPanelLayer` with hardcoded constants (the panel popping open/shut instead of
     * fading — the exact bug the fade commit exists to prevent) would leave every other test in
     * the suite green. `HangarRenderer.lastPanelScrimAlpha`/`lastPanelLayerAlpha` are the cheap
     * seam: the renderer's own record of the alpha it just used, read back here instead of off
     * a pixel.
     */
    @Test
    fun `the panel's drawn alpha is pinned to panelFade, not a hardcoded constant`() {
        val halfOpen = renderOnce(land.layout) {
            it.openPanel = HangarPanels.Panel.CREW
            it.panelFade = 0.4f
        }
        assertEquals(HangarPanels.panelScrimAlpha(0.4f), halfOpen.lastPanelScrimAlpha)
        assertEquals(HangarPanels.panelLayerAlpha(0.4f), halfOpen.lastPanelLayerAlpha)
        // Not vacuously equal to the fully-open case below — otherwise a constant could still
        // sneak this past.
        assertTrue(halfOpen.lastPanelScrimAlpha < HangarPanels.SCRIM_MAX)
        assertTrue(halfOpen.lastPanelLayerAlpha < 255)

        val fullyOpen = renderOnce(land.layout) {
            it.openPanel = HangarPanels.Panel.CREW
            it.panelFade = 1f
        }
        assertEquals(HangarPanels.SCRIM_MAX, fullyOpen.lastPanelScrimAlpha)
        assertEquals(255, fullyOpen.lastPanelLayerAlpha)

        val shut = renderOnce(land.layout) { }
        assertEquals("nothing drawn means nothing to pin", 0, shut.lastPanelScrimAlpha)
        assertEquals(0, shut.lastPanelLayerAlpha)
    }

    /**
     * A review finding: a page change used to leave `openPanel` pinned to a panel the new page does
     * not own, which `HangarRenderer.drawPanelLayer`'s `it in panels` filter then hid outright —
     * the panel and its full-screen scrim vanishing in a single frame, the exact instant
     * disappearance the fade exists to prevent. `HangarState.setPageTarget` now hands a departing
     * open panel to `closingPanel` instead (see `PanelFadeTest`'s state-level coverage of that),
     * and this is the render-path half: a closing panel must keep drawing through its fade even
     * once the CURRENT page no longer owns it, or the state fix alone would leave the screen
     * still cutting to nothing.
     */
    @Test
    fun `a panel closes visibly even after a page change carries it off its own page`() {
        val renderer = renderOnce(land.layout) {
            it.currentPage = 2 // the shop page — CREW is not one of its panels
            it.openPanel = null
            it.closingPanel = HangarPanels.Panel.CREW
            it.panelFade = 0.5f
        }
        assertEquals(
            "the panel must still be drawn while it fades, even off the page that owns it",
            PilotDefinitions.getPilotCount(), renderer.panelCardRects.size
        )
    }

    /**
     * A companion correctness check for the fix above: `BarPageRenderer.draw()` — and the clear
     * of its own `pilotCardRects` list — is skipped whenever the bar room is off-screen, which it
     * is once `currentPage` is 2. Drawing the closing CREW panel from that page must not simply
     * APPEND onto whatever `pilotCardRects` held from the prior frame; two consecutive render
     * passes must still land on exactly one set of cards, not a doubled one.
     */
    @Test
    fun `a panel fading over a different page does not leave duplicate card rects behind`() {
        val renderer = HangarRenderer(persistence)
        renderer.initialize(land.layout, roomWidthFor(land.layout))
        val state = HangarState(persistence)
        state.pilotScreenWidth = land.layout.width
        state.pilotScreenHeight = land.layout.height
        state.roomWidth = roomWidthFor(land.layout)
        state.phase = HangarPhase.BROWSING
        state.currentPage = 0
        state.openPanel = HangarPanels.Panel.CREW
        state.panelFade = 1f
        val bitmap = Bitmap.createBitmap(
            land.layout.width.toInt().coerceAtLeast(1), land.layout.height.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        renderer.render(canvas, state) // frame 1: CREW open, on its own page

        state.setPageTarget(2) // frame 2: carried into closingPanel, drawn over the shop page
        renderer.render(canvas, state)

        assertEquals(
            "no duplicate rects from a bar-page draw that was skipped this frame",
            PilotDefinitions.getPilotCount(), renderer.pilotCardRects.size
        )
    }

    // =====================================================================================
    // Portrait cannot change
    // =====================================================================================

    @Test
    fun `portrait keeps its in-room grid and draws no panel, cutout and all`() {
        for (cutout in listOf(0f, 128f)) {
            val layout = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = cutout).layout
            val renderer = renderOnce(layout) { it.openPanel = HangarPanels.Panel.CREW }

            assertTrue("cutout=$cutout: no panel in portrait", renderer.panelCardRects.isEmpty())
            assertTrue("cutout=$cutout: no buttons in portrait", renderer.panelButtonRects.isEmpty())

            val expected = GridGeometry.pilotCardRects(
                GridGeometry.pilotGridBounds(layout.content, roomWidthFor(layout), layout.width),
                PilotDefinitions.getPilotCount()
            )
            assertEquals("cutout=$cutout", expected.size, renderer.pilotCardRects.size)
            for ((i, e) in expected.withIndex()) {
                val got = renderer.pilotCardRects[i]
                assertEquals("cutout=$cutout card $i left", e.left, got.left, 0.01f)
                assertEquals("cutout=$cutout card $i top", e.top, got.top, 0.01f)
                assertEquals("cutout=$cutout card $i right", e.right, got.right, 0.01f)
                assertEquals("cutout=$cutout card $i bottom", e.bottom, got.bottom, 0.01f)
            }
        }
    }

    @Test
    fun `a portrait card is the size the panel gives it, cutout and all`() {
        for (cutout in listOf(0f, 128f)) {
            val landscapeLayout =
                DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = cutout).layout
            val portraitLayout = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = cutout).layout
            val shaped = DesignSpace.portraitShaped(landscapeLayout)

            val inPortrait = GridGeometry.pilotCardSize(
                GridGeometry.pilotGridBounds(
                    portraitLayout.content, roomWidthFor(portraitLayout), portraitLayout.width
                )
            )
            val inPanel = GridGeometry.pilotCardSize(
                GridGeometry.pilotGridBounds(shaped.content, shaped.width, shaped.width)
            )
            assertEquals("cutout=$cutout width", inPortrait.first, inPanel.first, 0.01f)
            assertEquals("cutout=$cutout height", inPortrait.second, inPanel.second, 0.01f)
        }
    }
}
