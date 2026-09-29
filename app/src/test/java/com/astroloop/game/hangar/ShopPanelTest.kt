package com.astroloop.game.hangar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.LayoutRect
import com.astroloop.game.core.ScreenLayout
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.StoreUpgradeDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shop page's two landscape panels: the upgrade board (SHOP — 3x3 where the nav band leaves
 * room for it, 5+4 where it does not) and the machine (SLOT).
 *
 * Three halves, in the order a defect would appear in them. First the geometry, composed from the
 * pure pieces exactly as the renderer composes them. Then a real [HangarRenderer.render] pass over
 * a Robolectric [Canvas], reading back the rects it published — a re-derivation cannot catch a call
 * site that stopped calling the seam, which is the gap the code review found. Then real
 * [MotionEvent] dispatch through [HangarSurfaceView.onTouchEvent] against those very rects, because
 * the store's tap paths are the ones that spend money: a hold must fill the tile it landed on and
 * no other.
 *
 * Every portrait assertion runs WITH a cutout as well as without — the 2026-09-14 revert happened
 * because a suite of portrait-identity tests used zero insets, a configuration the owner's device
 * does not have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShopPanelTest {

    private lateinit var persistence: PersistenceManager

    // A rotated Pixel 9 Pro: 2844x1280 physical, a 128px cutout on the (landscape) left edge.
    private val land = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f)
    private val portrait = DesignSpace.portraitShaped(land.layout)
    private val safe = land.layout.safe
    private val walkwayY = portrait.height * 0.60f

    private val tileCount = GridGeometry.STORE_COLS * GridGeometry.STORE_ROWS

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

    /** One store tile, at its portrait size — what the panel must give it, whatever the screen. */
    private fun tileSize(layout: ScreenLayout = land.layout): Float {
        val p = DesignSpace.portraitShaped(layout)
        return GridGeometry.storeTileSize(p.content, p.height * 0.60f)
    }

    /** `HangarSurfaceView.handleTap`'s own nav-row tap band top, for the given layout's screen. */
    private fun navBandTop(layout: ScreenLayout = land.layout): Float = layout.height * 0.95f - 30f

    /**
     * The vertical budget a panel's content may use, composed the way
     * `HangarRenderer.panelAvailableHeight` does: capped so the outer
     * box — always centred on `safe.centerY` — cannot reach into the nav row's tap band, and
     * floored by the safe area's own height as before.
     */
    private fun panelAvailableHeight(layout: ScreenLayout = land.layout): Float {
        val s = layout.safe
        val navClearance = 2f * (navBandTop(layout) - s.centerY) - HangarPanels.PAD_V * 2f
        val safeClearance = s.height - HangarPanels.PAD_V * 2f
        return minOf(navClearance, safeClearance)
    }

    /** The SHOP panel's arrangement, composed the way `HangarRenderer.panelContentSize` does. */
    private fun shopArrangement(layout: ScreenLayout = land.layout): PanelGrid.Arrangement {
        val s = layout.safe
        val t = tileSize(layout)
        return PanelGrid.arrange(
            tileCount, t, t, GridGeometry.STORE_GAP, GridGeometry.STORE_COLS,
            s.width - HangarPanels.SCREEN_EDGE * 2f - HangarPanels.PAD_H * 2f,
            panelAvailableHeight(layout)
        )
    }

    /** The machine at its portrait size — the SLOT panel's single object. */
    private fun machineFrame(layout: ScreenLayout = land.layout): LayoutRect {
        val p = DesignSpace.portraitShaped(layout)
        val wy = p.height * 0.60f
        return GridGeometry.machineFrame(
            GridGeometry.storeGridBounds(p.content, 0f, wy), wy, p.height
        )
    }

    private fun panelCardSize(layout: ScreenLayout = land.layout): Pair<Float, Float> {
        val p = DesignSpace.portraitShaped(layout)
        return GridGeometry.pilotCardSize(GridGeometry.pilotGridBounds(p.content, p.width, p.width))
    }

    // =====================================================================================
    // The geometry, composed from the pure pieces
    // =====================================================================================

    @Test
    fun `panel tiles are the size they are in portrait, cutout and all`() {
        for (cutout in listOf(0f, 128f)) {
            val portraitOnly = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = cutout).layout
            val landscapeOnly = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = cutout).layout
            assertEquals(
                "cutout=$cutout",
                GridGeometry.storeTileSize(portraitOnly.content, portraitOnly.height * 0.60f),
                tileSize(landscapeOnly),
                0.01f
            )
        }
    }

    /**
     * A code review: the previous version of this test called `PanelGrid.arrange`
     * directly with a hand-picked height, never touching `HangarRenderer.shopGridArrangement`,
     * `panelContentSize` or `drawPanelContents` — a literal `Arrangement(3, 3, …)` hardcoded
     * inside `shopGridArrangement` would have passed every assertion the old file had, and its
     * closing assertion compared a value (`tileSize()`) to itself.
     *
     * This drives the real seam instead — `renderer.shopGridArrangement()`, the exact function
     * the review's fix changed — across two real device profiles rather than one contrived height,
     * so it can tell "reflowed because the nav-band rule fired" apart from "3x3 never fits
     * anywhere" (a review finding's own follow-up note): a 16:9 TV has room above the nav band for 3x3,
     * and a rotated Pixel 9 Pro does not. A hardcoded `Arrangement(3, 3, …)` passes the TV
     * assertion but fails the phone one; a version that reflowed unconditionally would fail the TV
     * one. Only the real rule — reflow exactly when the budgeted height forces it — passes both.
     */
    @Test
    fun `the renderer reflows the board on the profile the nav band pinches, not on one it does not`() {
        val tv = DesignSpace.metricsFor(1920f, 1080f).layout
        val tvRenderer = HangarRenderer(persistence)
        tvRenderer.initialize(tv, roomWidthFor(tv))
        val tvArrangement = tvRenderer.shopGridArrangement()
        assertEquals("a 16:9 TV has room for 3x3 even with the nav band budgeted", 3, tvArrangement.cols)
        assertEquals(3, tvArrangement.rows)
        assertTrue(
            "the 3x3 board must actually fit the budget it was tested against",
            tvArrangement.height <= panelAvailableHeight(tv)
        )

        val renderer = HangarRenderer(persistence)
        renderer.initialize(land.layout, roomWidthFor(land.layout))
        val phoneArrangement = renderer.shopGridArrangement()
        assertEquals("a rotated Pixel 9 Pro's nav band forces the board to reflow", 5, phoneArrangement.cols)
        assertEquals(2, phoneArrangement.rows)

        // The reflowed tile, back-solved from the arrangement's own aggregate width, must still be
        // the portrait tile — derived independently here, from a REAL un-rotated portrait phone,
        // rather than compared to the rotated fixture's own `tileSize()` (i.e. to itself).
        val truePortrait = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f).layout
        val independentPortraitTile =
            GridGeometry.storeTileSize(truePortrait.content, truePortrait.height * 0.60f)
        val tileFromReflow =
            (phoneArrangement.width - (phoneArrangement.cols - 1) * GridGeometry.STORE_GAP) /
                phoneArrangement.cols
        assertEquals(
            "a reflowed tile is still a portrait tile", independentPortraitTile, tileFromReflow, 0.01f
        )
    }

    @Test
    fun `the machine is its portrait size and fits the panel`() {
        val frame = machineFrame()
        assertEquals(portrait.height * 0.92f - (walkwayY + 15f), frame.height, 0.01f)
        assertTrue(
            "machine must fit the safe height",
            frame.height + HangarPanels.PAD_V * 2f <= safe.height
        )
        assertTrue(
            "machine must fit the safe width",
            frame.width + HangarPanels.PAD_H * 2f + HangarPanels.SCREEN_EDGE * 2f <= safe.width
        )
    }

    @Test
    fun `the renderer sizes both shop panels from the same pieces`() {
        val renderer = HangarRenderer(persistence)
        renderer.initialize(land.layout, roomWidthFor(land.layout))

        val a = shopArrangement()
        val (shopW, shopH) = renderer.panelContentSize(HangarPanels.Panel.SHOP)
        assertEquals(a.width, shopW, 0.01f)
        assertEquals(a.height, shopH, 0.01f)

        val frame = machineFrame()
        val (slotW, slotH) = renderer.panelContentSize(HangarPanels.Panel.SLOT)
        assertEquals(frame.width, slotW, 0.01f)
        assertEquals(frame.height, slotH, 0.01f)
    }

    /**
     * The machine never reflows or scales, so its own box never reaches the button stack on any
     * profile this suite exercises — unlike SHOP's board, which now can (a review finding's own
     * consequence, verified separately below).
     */
    @Test
    fun `the machine never reaches the buttons, so the stack stays home while only it is open`() {
        val (cardW, cardH) = panelCardSize()
        val content = machineFrame().width to machineFrame().height
        val box = HangarPanels.panelBox(HangarPanels.Panel.SLOT, content.first, content.second, safe)
        for (p in listOf(HangarPanels.Panel.SHOP, HangarPanels.Panel.SLOT)) {
            val home = HangarPanels.buttonHome(p, safe, cardW, cardH)
            assertTrue("$p: button must clear the machine's panel", home.left > box.right)
            assertEquals("$p: tab stays home", home.left, HangarPanels.tabRect(p, home, box).left, 0.01f)
        }
        assertTrue("SLOT: panel stays inside safe", box.left >= safe.left && box.right <= safe.right)
    }

    /**
     * The owner's 2026-09-20 move, on the shop side: the stack sits a tenth of the safe width in
     * from the RIGHT, which puts it clear of even the widened 5+4 board, so opening either panel
     * leaves both buttons exactly where they were.
     *
     * This test used to assert the opposite, back when the
     * stack sat two thirds across at 1319.4 and the 5+4 board's box.right of 1645.7 shoved it
     * 356.3 units right. Moving the stack to 1720.2 makes the clamp a no-op here. Keeping the old
     * assertions would have been worse than useless: `shopRect.left >= box.right + TAB_GAP` is
     * still TRUE of a button that never moved, so the test would have gone on passing while
     * testing nothing — the fifth instance of that shape in this plan if it had been left.
     *
     * So it asserts what is now load-bearing (the stack does not move, and by how much it clears)
     * and `HangarPanelsTest` drives the clamp itself with a box wide enough to fire it.
     */
    @Test
    fun `the widened shop board stops short of the stack, so neither button moves`() {
        val shut = renderOnce(land.layout) { }
        val shutShop = shut.panelButtonRects.getValue(HangarPanels.Panel.SHOP)
        val shutSlot = shut.panelButtonRects.getValue(HangarPanels.Panel.SLOT)

        val renderer = renderOnce(land.layout) { it.openPanel = HangarPanels.Panel.SHOP }
        val a = shopArrangement()
        assertEquals("this profile is the one the nav band actually reflows", 5, a.cols)

        val box = HangarPanels.panelBox(HangarPanels.Panel.SHOP, a.width, a.height, safe)
        val shopRect = renderer.panelButtonRects.getValue(HangarPanels.Panel.SHOP)
        val slotRect = renderer.panelButtonRects.getValue(HangarPanels.Panel.SLOT)

        // The owner's actual requirement: the button he pressed is still under his thumb.
        assertEquals("SHOP does not move when its own panel opens", shutShop.left, shopRect.left, 0.01f)
        assertEquals("SLOT does not move either", shutSlot.left, slotRect.left, 0.01f)
        assertEquals("nor vertically", shutShop.top, shopRect.top, 0.01f)

        // And the margin it clears by, so a board that grows toward the stack shows up here as a
        // shrinking number before it starts shoving buttons again. Measured: box.right 1645.73,
        // + TAB_GAP 30 = 1675.73, against a stack left edge of 1720.24.
        assertEquals(1645.73f, box.right, 0.01f)
        assertEquals(1720.24f, shopRect.left, 0.01f)
        assertEquals(44.51f, shopRect.left - (box.right + HangarPanels.TAB_GAP), 0.01f)

        assertEquals(
            "the stack still shares one X", shopRect.left, slotRect.left, 0.01f
        )
        assertTrue("SHOP's tab stays on screen", shopRect.right <= land.width)
        assertTrue("SLOT's button stays on screen", slotRect.right <= land.width)
        assertEquals(
            "the stack keeps its own vertical gap", HangarPanels.STACK_GAP, slotRect.top - shopRect.bottom, 0.01f
        )
    }

    /**
     * The nav row is drawn OVER the panel and resolved BEFORE it, so the two must not compete for
     * the same point — `CrewPanelTest` pins the same invariant for the crew panel.
     *
     * A code review: before the fix, `panelContentSize`'s height budget ignored the nav
     * band entirely, so the unreflowed 3x3 board's box (936.42 bottom on a rotated Pixel 9 Pro)
     * landed 50.57 units into the band (885.85 top) — this exact assertion is RED against that
     * code (verified by temporarily reverting `panelAvailableHeight` to `safe.height - PAD_V*2f`).
     * `panelAvailableHeight` reflowing the board to 5+4 there is what clears it now.
     */
    @Test
    fun `the shop panel and the machine both clear the nav row's tap band`() {
        val navTop = navBandTop()

        val a = shopArrangement()
        val shopBox = HangarPanels.panelBox(HangarPanels.Panel.SHOP, a.width, a.height, safe)
        assertTrue(
            "shop board bottom ${shopBox.bottom} must clear the nav band at $navTop", shopBox.bottom < navTop
        )

        val slotBox = HangarPanels.panelBox(
            HangarPanels.Panel.SLOT, machineFrame().width, machineFrame().height, safe
        )
        assertTrue("the machine clears the nav band", slotBox.bottom < navTop)
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
        state.currentPage = 2
        state.phase = HangarPhase.BROWSING
        configure(state)
        val bitmap = Bitmap.createBitmap(
            layout.width.toInt().coerceAtLeast(1), layout.height.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        renderer.render(Canvas(bitmap), state)
        lastRenderState = state
        return renderer
    }

    /** The state [renderOnce] just drew, for the rects the store publishes onto it. */
    private var lastRenderState: HangarState? = null

    /**
     * The room's hit tests have to go where the room's furniture went. Nothing drawn means nothing
     * hit-testable: a board and a machine left in `upgradeRects`/`spinButtonRect` after the room
     * stopped drawing them would buy an upgrade, or spend 100¥ on a spin, that nobody can see —
     * the very bug a code review found behind the panel scrim, in a new place.
     */
    @Test
    fun `landscape takes the board and the machine out of the room`() {
        val renderer = renderOnce(land.layout) { }
        val state = lastRenderState!!

        assertTrue("no tiles in the room", renderer.upgradeRects.isEmpty())
        assertTrue("no crystal tile in the room", renderer.crystalTileRect.isEmpty)
        assertTrue("no spin button in the room", renderer.spinButtonRect.isEmpty)
        assertTrue("no machine buttons in the room", renderer.storeButtonRects.isEmpty())
        assertNull("no hatch in the room", state.hatchRect)
        assertNull("no paper in the room", state.paperRect)
        assertNull("no audio toggle in the room", state.audioMuteButtonRect)
        assertNull("no vibration toggle in the room", state.vibrationMuteButtonRect)
        assertNull("no INSERT COIN in the room", state.insertCoinRect)
    }

    @Test
    fun `the open shop panel draws nine tiles at portrait size, in screen space`() {
        val renderer = renderOnce(land.layout) { it.openPanel = HangarPanels.Panel.SHOP }
        val a = shopArrangement()
        val box = HangarPanels.panelBox(HangarPanels.Panel.SHOP, a.width, a.height, safe)
        val t = tileSize()

        assertEquals(tileCount, renderer.panelCardRects.size)
        for (r in renderer.panelCardRects) {
            assertEquals("a panel tile must be a portrait tile", t, r.width, 0.01f)
            assertEquals("a panel tile must be a portrait tile", t, r.height, 0.01f)
            assertTrue("tile left ${r.left} outside the panel", r.left >= box.left)
            assertTrue("tile right ${r.right} outside the panel", r.right <= box.right)
            assertTrue("tile top ${r.top} outside the panel", r.top >= box.top)
            assertTrue("tile bottom ${r.bottom} outside the panel", r.bottom <= box.bottom)
        }

        // The eight purchasable tiles are published as tap targets in the space they were drawn
        // in, and the ninth — the crystal/shield tile — separately, as it always was.
        assertEquals(StoreUpgradeDefinitions.purchasableIds.size, renderer.upgradeRects.size)
        for ((i, r) in renderer.upgradeRects.withIndex()) {
            val panel = renderer.panelCardRects[i]
            assertEquals("tile $i left", panel.left, r.left, 0.01f)
            assertEquals("tile $i top", panel.top, r.top, 0.01f)
        }
        val crystal = renderer.crystalTileRect
        assertEquals(renderer.panelCardRects[tileCount - 1].left, crystal.left, 0.01f)
        assertEquals(renderer.panelCardRects[tileCount - 1].top, crystal.top, 0.01f)
    }

    @Test
    fun `the open slot panel draws the machine at its portrait size, in screen space`() {
        val renderer = renderOnce(land.layout) { it.openPanel = HangarPanels.Panel.SLOT }
        val state = lastRenderState!!
        val frame = machineFrame()

        // The SLOT panel holds one object, not a grid — and that object is what a tap on the
        // panel's contents resolves to, so it is published like any other panel item.
        assertEquals(1, renderer.panelCardRects.size)
        val drawn = renderer.panelCardRects[0]
        assertEquals("the machine is its portrait width", frame.width, drawn.width, 0.01f)
        assertEquals("the machine is its portrait height", frame.height, drawn.height, 0.01f)

        val spin = renderer.spinButtonRect
        assertFalse("the machine must have drawn its spin button", spin.isEmpty)
        assertTrue(
            "spin button ${spin.left}..${spin.right} must sit inside the panel's machine",
            spin.left >= drawn.left && spin.right <= drawn.right &&
                spin.top >= drawn.top && spin.bottom <= drawn.bottom
        )
        val hatch = state.hatchRect
        assertNotNull("the machine must have drawn its hatch", hatch)
        assertTrue(
            "hatch must sit inside the panel's machine",
            hatch!!.left >= drawn.left && hatch.right <= drawn.right
        )
    }

    /** One panel at a time: opening SLOT must not leave SHOP's tiles hit-testable. */
    @Test
    fun `only the open panel publishes its own contents`() {
        val slot = renderOnce(land.layout) { it.openPanel = HangarPanels.Panel.SLOT }
        assertTrue("the slot panel publishes no upgrade tiles", slot.upgradeRects.isEmpty())

        val shop = renderOnce(land.layout) { it.openPanel = HangarPanels.Panel.SHOP }
        assertTrue("the shop panel publishes no spin button", shop.spinButtonRect.isEmpty)
    }

    // =====================================================================================
    // Portrait cannot change
    // =====================================================================================

    @Test
    fun `portrait keeps its in-room board and machine, and draws no panel, cutout and all`() {
        for (cutout in listOf(0f, 128f)) {
            val layout = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = cutout).layout
            val renderer = renderOnce(layout) { it.openPanel = HangarPanels.Panel.SHOP }
            val state = lastRenderState!!

            assertTrue("cutout=$cutout: no panel in portrait", renderer.panelCardRects.isEmpty())
            assertTrue("cutout=$cutout: no buttons in portrait", renderer.panelButtonRects.isEmpty())

            // The shipped arithmetic, written out: the room's own content column, its own walkway.
            val roomW = roomWidthFor(layout)
            val wy = layout.height * 0.60f
            val bounds = GridGeometry.storeGridBounds(
                layout.content, HangarMetrics.contentXInRoom(layout.content.left, roomW, layout.width), wy
            )
            val expected = GridGeometry.storeTileRects(bounds, tileCount)
            assertEquals(
                "cutout=$cutout", StoreUpgradeDefinitions.purchasableIds.size, renderer.upgradeRects.size
            )
            for ((i, r) in renderer.upgradeRects.withIndex()) {
                assertEquals("cutout=$cutout tile $i left", expected[i].left, r.left, 0.01f)
                assertEquals("cutout=$cutout tile $i top", expected[i].top, r.top, 0.01f)
                assertEquals("cutout=$cutout tile $i right", expected[i].right, r.right, 0.01f)
                assertEquals("cutout=$cutout tile $i bottom", expected[i].bottom, r.bottom, 0.01f)
            }
            assertEquals(
                "cutout=$cutout crystal tile", expected[tileCount - 1].left,
                renderer.crystalTileRect.left, 0.01f
            )

            // The machine stays under the walkway in the room, at the grid's own width.
            val frame = GridGeometry.machineFrame(bounds, wy, layout.height)
            val spin = renderer.spinButtonRect
            assertFalse("cutout=$cutout: the room must still draw its machine", spin.isEmpty)
            assertTrue(
                "cutout=$cutout: spin button must sit on the in-room machine",
                spin.left >= frame.left && spin.right <= frame.right &&
                    spin.top >= frame.top && spin.bottom <= frame.bottom
            )
            assertNotNull("cutout=$cutout: the hatch is still in the room", state.hatchRect)
        }
    }

    // =====================================================================================
    // The tap paths that spend money
    // =====================================================================================

    private fun landscapeView(): HangarSurfaceView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = HangarSurfaceView(context) { _, _ -> }
        view.surfaceChanged(view.holder, 0, 2844, 1280)
        persistence.setYen(100_000)
        view.state.actualYen = persistence.getYen()
        view.state.currentPage = 2
        view.state.phase = HangarPhase.BROWSING
        return view
    }

    /** The renderer the VIEW hit-tests against, driven over a throwaway canvas. */
    private fun renderInto(view: HangarSurfaceView) {
        val bitmap = Bitmap.createBitmap(
            land.width.toInt(), land.height.toInt(), Bitmap.Config.ARGB_8888
        )
        view.renderer.render(Canvas(bitmap), view.state)
    }

    private fun dispatch(view: HangarSurfaceView, action: Int, x: Float, y: Float, at: Long = 0L) {
        // onTouchEvent divides by renderScale — the test speaks design units like the layout does.
        val scale = DesignSpace.metricsFor(2844f, 1280f).renderScale
        val event = MotionEvent.obtain(0L, at, action, x * scale, y * scale, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `a press on a panel tile holds that tile and no other`() {
        val view = landscapeView()
        view.state.openPanel = HangarPanels.Panel.SHOP
        view.state.panelFade = 1f
        renderInto(view)

        val rects = view.renderer.upgradeRects.toList()
        // Not vacuous: an empty list would satisfy the loop below on its own.
        assertEquals(StoreUpgradeDefinitions.purchasableIds.size, rects.size)
        for ((index, rect) in rects.withIndex()) {
            dispatch(view, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY())
            assertEquals(
                "a press on panel tile $index must hold tile $index", index, view.heldUpgradeIndex
            )
            dispatch(view, MotionEvent.ACTION_CANCEL, rect.centerX(), rect.centerY())
        }
    }

    @Test
    fun `a tap on a panel tile turns that tile over`() {
        val view = landscapeView()
        view.state.openPanel = HangarPanels.Panel.SHOP
        view.state.panelFade = 1f
        renderInto(view)

        val rect = view.renderer.upgradeRects.getOrNull(3)
        assertNotNull("the panel must have drawn tile 3", rect)
        dispatch(view, MotionEvent.ACTION_DOWN, rect!!.centerX(), rect.centerY())
        dispatch(view, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY())

        assertTrue("the tapped tile must turn over", view.state.isStoreCardFlipped(3))
        assertFalse("no other tile may turn over", view.state.isStoreCardFlipped(0))
        assertEquals("a tap must never spend yen", 100_000, persistence.getYen())
    }

    @Test
    fun `a tap on the machine in the slot panel presses the machine, not the backdrop`() {
        val view = landscapeView()
        view.state.openPanel = HangarPanels.Panel.SLOT
        view.state.panelFade = 1f
        renderInto(view)

        val spin = view.renderer.spinButtonRect
        assertFalse("the panel must have drawn the machine", spin.isEmpty)
        dispatch(view, MotionEvent.ACTION_DOWN, spin.centerX(), spin.centerY())
        dispatch(view, MotionEvent.ACTION_UP, spin.centerX(), spin.centerY())

        assertTrue("the spin button must have spun", view.state.isSpinning)
        assertEquals(
            "the panel must stay open — a press on its contents is not a backdrop tap",
            HangarPanels.Panel.SLOT, view.state.openPanel
        )
    }

    @Test
    fun `a tap on the backdrop still closes the panel`() {
        val view = landscapeView()
        view.state.openPanel = HangarPanels.Panel.SHOP
        view.state.panelFade = 1f
        renderInto(view)

        val a = shopArrangement()
        val box = HangarPanels.panelBox(HangarPanels.Panel.SHOP, a.width, a.height, safe)
        // Well right of the panel and of its two buttons' stack, and above the nav row.
        dispatch(view, MotionEvent.ACTION_DOWN, land.width - 30f, box.centerY)
        dispatch(view, MotionEvent.ACTION_UP, land.width - 30f, box.centerY)

        assertNull("the backdrop closes the panel", view.state.openPanel)
        assertEquals(HangarPanels.Panel.SHOP, view.state.closingPanel)
    }

    // `a press on the room behind an open panel still does nothing` was dropped here.
    // With a real renderInto() pass, `upgradeRects`/`spinButtonRect` hold
    // only the panel's own published tiles — the room draws no board of its own in landscape at
    // all — so a point chosen "well right of the panel" hits nothing regardless of whether any
    // room-covering gate exists: it could not fail either way, gate present or absent. The
    // invariant it stood in for — a press on the blank box does not reach a control drawn under
    // it — is covered non-vacuously by `PanelFadeTest`'s "a press on the blank SHOP box does not
    // arm the spin button beneath it", which seeds a REAL spinButtonRect at the press point and
    // still proves it is not armed.

    /**
     * A code review: the vibration toggle's own hit rect pokes ~15 units left of
     * the machine frame `panelCardRects` publishes as SLOT's one hit-testable item
     * (`StorePageRenderer.drawSlotMachineAt`'s `muteButtonRadius` math) — `PanelGrid.indexAt`
     * misses that sliver, so a press there used to fall straight to the backdrop catch-all and
     * close the panel instead of muting. Picks a point just inside the toggle's own rect but
     * outside the published frame's, so this is RED against the un-fixed `handlePanelTap`.
     */
    @Test
    fun `a press on the mute toggle's outer sliver mutes rather than closing the panel`() {
        val view = landscapeView()
        view.state.openPanel = HangarPanels.Panel.SLOT
        view.state.panelFade = 1f
        renderInto(view)

        val frame = view.renderer.panelCardRects.single()
        val vibRect = view.state.vibrationMuteButtonRect
        assertNotNull("the machine must have drawn its vibration toggle", vibRect)
        val x = vibRect!!.left + 2f
        val y = vibRect.centerY()
        assertTrue(
            "the probe point must actually sit in the sliver outside the published frame",
            x < frame.left
        )

        val wasMuted = view.state.vibrationMuted
        dispatch(view, MotionEvent.ACTION_DOWN, x, y)
        dispatch(view, MotionEvent.ACTION_UP, x, y)

        assertEquals(
            "the panel must stay open — the sliver belongs to the toggle, not the backdrop",
            HangarPanels.Panel.SLOT, view.state.openPanel
        )
        assertEquals("the tap must toggle vibration mute", !wasMuted, view.state.vibrationMuted)
    }

    /**
     * One decision about which space a store hit test is in, consulted by every path — DOWN, MOVE,
     * UP and the pad. The two spaces coincide on the shop page at rest (the page's own origin is 0
     * in landscape), so this drives them apart with a page-scroll offset: whatever the room
     * transform says, a panel's rects are screen space and a room's are room-local.
     */
    @Test
    fun `the store's hit space follows where its rects were drawn`() {
        val view = landscapeView()
        view.state.pageScrollOffset = 120f

        assertEquals("landscape: the panel drew in screen space", 500f, view.storeHitPoint(500f, 40f).first, 0.01f)
        assertEquals("y never crosses rooms", 40f, view.storeHitPoint(500f, 40f).second, 0.01f)

        view.surfaceChanged(view.holder, 0, 1280, 2844)
        view.state.currentPage = 2
        view.state.pageScrollOffset = 120f
        val roomLocal = RoomAnchor.toRoomX(
            500f, 2, 120f, DesignSpace.metricsFor(1280f, 2844f).width, roomWidthFor(
                DesignSpace.metricsFor(1280f, 2844f).layout
            ), false
        )
        assertTrue("the fixture must actually differ from screen space", roomLocal != 500f)
        assertEquals("portrait: the room drew in room space", roomLocal, view.storeHitPoint(500f, 40f).first, 0.01f)
    }
}
