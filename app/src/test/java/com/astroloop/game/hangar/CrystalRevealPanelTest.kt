package com.astroloop.game.hangar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.LayoutRect
import com.astroloop.game.core.ScreenLayout
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.render.CrystalOrbPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The crystal reveal's destination tile lives inside the shop panel in landscape, and the reveal
 * fires itself when the store page settles. The gate must open that panel, or a player is left
 * holding a blocked launch — no Astro, no liftoff — with the payoff behind a closed door.
 *
 * Two halves. First the pure rule ([HangarState.armCrystalRevealPanel]), then a real
 * [HangarRenderer.render] pass reading back where the orb actually left from and where it was
 * actually headed. The second half is the one that matters: "the reveal completes" passes whether
 * or not a single pixel of it was visible, and before this task the landscape orb corkscrewed to
 * the room's top-left corner and burst at (0,0) while every phase test stayed green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrystalRevealPanelTest {

    private lateinit var persistence: PersistenceManager

    // A rotated Pixel 9 Pro: 2844x1280 physical, a 128px cutout on the (landscape) left edge —
    // the same profile ShopPanelTest pins the board against.
    private val land = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f)

    private val tileCount = GridGeometry.STORE_COLS * GridGeometry.STORE_ROWS

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
    }

    // =====================================================================================
    // The rule: which panel the gate opens
    // =====================================================================================

    private fun armed(layout: ScreenLayout = land.layout): HangarState =
        HangarState(persistence).apply {
            awaitingCrystalReveal = true
            currentPage = 2
            pageScrollOffset = 0f
            pageVelocity = 0f
            crystalRevealPhase = HangarState.CrystalRevealPhase.GLOW
            pilotScreenWidth = layout.width
            pilotScreenHeight = layout.height
            roomWidth = roomWidthFor(layout)
        }

    @Test
    fun `arming the reveal in landscape opens the shop panel`() {
        val state = armed()
        HangarState.armCrystalRevealPanel(state)
        assertEquals(HangarPanels.Panel.SHOP, state.openPanel)
    }

    /** Portrait keeps the board in the room, so there is nothing to open and nothing to change. */
    @Test
    fun `in portrait it opens nothing`() {
        val state = armed(DesignSpace.metricsFor(1280f, 2844f).layout)
        HangarState.armCrystalRevealPanel(state)
        assertNull(state.openPanel)
    }

    @Test
    fun `a panel already open on the slot machine is switched to the shop`() {
        val state = armed().apply { openPanel = HangarPanels.Panel.SLOT }
        HangarState.armCrystalRevealPanel(state)
        assertEquals(HangarPanels.Panel.SHOP, state.openPanel)
    }

    /**
     * Catches the obvious implementation — an unconditional `togglePanel(SHOP)` — which CLOSES the
     * panel a player already had open on the board, leaving the orb flying into a shutting door.
     */
    @Test
    fun `arming with the shop already open leaves it open`() {
        val state = armed().apply { openPanel = HangarPanels.Panel.SHOP; panelFade = 1f }
        HangarState.armCrystalRevealPanel(state)
        assertEquals(HangarPanels.Panel.SHOP, state.openPanel)
        assertNull("nothing may start closing", state.closingPanel)
        assertEquals("an open panel's fade may not restart", 1f, state.panelFade, 0f)
    }

    /** A shop panel caught mid-close is reopened, not abandoned to finish closing. */
    @Test
    fun `arming while the shop panel is closing reopens it`() {
        val state = armed().apply { closingPanel = HangarPanels.Panel.SHOP; panelFade = 0.5f }
        HangarState.armCrystalRevealPanel(state)
        assertEquals(HangarPanels.Panel.SHOP, state.openPanel)
        assertNull(state.closingPanel)
    }

    // =====================================================================================
    // The draw pass: where the orb actually leaves from, and where it is actually headed
    // =====================================================================================

    private fun roomWidthFor(layout: ScreenLayout, swDp: Int = 427): Float {
        val p = DesignSpace.portraitShaped(layout)
        return HangarMetrics.roomWidth(p.width, p.content.width, swDp)
    }

    private var lastRenderState: HangarState? = null

    private fun renderOnce(
        layout: ScreenLayout, swDp: Int = 427, configure: (HangarState) -> Unit
    ): HangarRenderer {
        val roomW = roomWidthFor(layout, swDp)
        val renderer = HangarRenderer(persistence)
        renderer.initialize(layout, roomW)
        val state = HangarState(persistence).apply {
            pilotScreenWidth = layout.width
            pilotScreenHeight = layout.height
            roomWidth = roomW
            currentPage = 2
            phase = HangarPhase.BROWSING
            astroAtSlotMachine = true
        }
        configure(state)
        val bitmap = Bitmap.createBitmap(
            layout.width.toInt().coerceAtLeast(1), layout.height.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        renderer.render(Canvas(bitmap), state)
        lastRenderState = state
        return renderer
    }

    /**
     * Screen X of the store walkway's slot machine, composed from the pure page transform rather
     * than read back off anything the renderer published — `margin + 0.1 * walkable` against the
     * room, exactly as `StorePageRenderer.draw` places the mini machine Astro stands at.
     */
    private fun machineScreenX(layout: ScreenLayout, swDp: Int = 427): Float {
        val roomW = roomWidthFor(layout, swDp)
        val ls = RoomAnchor.isLandscape(layout.width, layout.height)
        val origin = RoomAnchor.pageOriginX(2, 2, 0f, layout.width, roomW, ls)
        return origin + RoomAnchor.pageWidth(2, layout.width, roomW, ls) * 0.18f
    }

    /** The dot is drawn at `walkwayY - 8f` less another 6 — the point the orb leaves from. */
    private fun astroDotScreenY(layout: ScreenLayout): Float = layout.height * 0.60f - 14f

    private fun navBandTop(layout: ScreenLayout): Float = layout.height * 0.95f - 30f

    private fun panelAvailableHeight(layout: ScreenLayout): Float {
        val s = layout.safe
        val navClearance = 2f * (navBandTop(layout) - s.centerY) - HangarPanels.PAD_V * 2f
        return minOf(navClearance, s.height - HangarPanels.PAD_V * 2f)
    }

    /** The SHOP panel's grid, composed exactly as `HangarRenderer.shopGridArrangement` does. */
    private fun shopArrangement(layout: ScreenLayout): PanelGrid.Arrangement {
        val p = DesignSpace.portraitShaped(layout)
        val t = GridGeometry.storeTileSize(p.content, p.height * 0.60f)
        val s = layout.safe
        return PanelGrid.arrange(
            tileCount, t, t, GridGeometry.STORE_GAP, GridGeometry.STORE_COLS,
            s.width - HangarPanels.SCREEN_EDGE * 2f - HangarPanels.PAD_H * 2f,
            panelAvailableHeight(layout)
        )
    }

    /** The ninth tile of the SHOP panel — the crystal tile — composed the same way. */
    private fun panelCrystalTile(layout: ScreenLayout): LayoutRect {
        val p = DesignSpace.portraitShaped(layout)
        val t = GridGeometry.storeTileSize(p.content, p.height * 0.60f)
        val a = shopArrangement(layout)
        val box = HangarPanels.panelBox(HangarPanels.Panel.SHOP, a.width, a.height, layout.safe)
        return PanelGrid.rects(
            a, tileCount, box.left + HangarPanels.PAD_H, box.top + HangarPanels.PAD_V,
            t, t, GridGeometry.STORE_GAP
        )[tileCount - 1]
    }

    /**
     * The load-bearing one. Before this task the landscape room zeroed `crystalTileRect` and the
     * orb flew to (0,0); an assertion that only watched the phases advance never noticed.
     */
    @Test
    fun `in landscape the orb leaves Astro on the walkway and lands on the panel's crystal tile`() {
        val renderer = renderOnce(land.layout) {
            it.openPanel = HangarPanels.Panel.SHOP
            it.panelFade = 1f
            it.crystalRevealPhase = HangarState.CrystalRevealPhase.ORB_TRAVEL
            it.crystalRevealTimer = CrystalOrbPath.TRAVEL_DURATION * 0.5f
        }

        assertEquals(
            "the orb leaves the walkway machine, not the panel",
            machineScreenX(land.layout), renderer.drawnRevealSrcX, 0.01f
        )
        assertEquals(
            "the orb leaves Astro's dot", astroDotScreenY(land.layout), renderer.drawnRevealSrcY, 0.01f
        )

        // The destination, composed from the panel's own pure pieces — not read back off the
        // `crystalTileRect` the reveal itself consults, which would agree with anything.
        // Deliberately NOT paired with a separate "and it is not (0,0)": the shipped bug puts NaN
        // or the room's corner here and this equality catches both, so a second assertion could
        // only fail where this one already had (verified — the mutation that draws the reveal
        // before the panel layer fails on this line).
        val tile = panelCrystalTile(land.layout)
        assertEquals("destination X — the panel's crystal tile", tile.centerX, renderer.drawnRevealDstX, 0.01f)
        assertEquals("destination Y — the panel's crystal tile", tile.centerY, renderer.drawnRevealDstY, 0.01f)
    }

    /**
     * A code review. No panel is opened here, so the SHOP panel has published no
     * `crystalTileRect` this frame — the same state a pad user reaches by pressing a nav label
     * mid-flight (nav targets are published with no reveal check, a disclosed pre-existing hole
     * outside this fix's scope) and closing the panel out from under the orb.
     *
     * Before this fix `drawCrystalReveal` returned before drawing anything at all once the tile
     * came back empty, so the room — already stood down for the whole flight, keyed on the same
     * `crystalRevealInFlight()` — and the reveal agreed on nothing: Astro simply blinked out.
     * The dot's own seams must survive even though the orb has nowhere to fly.
     */
    @Test
    fun `Astro's dot still draws when the panel has published no destination tile`() {
        val flying = renderOnce(land.layout) {
            // openPanel left null: StorePageRenderer.draw resets crystalTileRect to an empty
            // RectF in landscape and nothing publishes a new one.
            it.crystalRevealPhase = HangarState.CrystalRevealPhase.ORB_TRAVEL
        }

        assertFalse(
            "Astro's dot must still draw even with nowhere published to fly to yet",
            flying.drawnRevealSrcX.isNaN()
        )
        assertFalse(
            "Astro's dot must still draw even with nowhere published to fly to yet",
            flying.drawnRevealSrcY.isNaN()
        )
        assertEquals(
            "the dot draws at the walkway machine regardless",
            machineScreenX(land.layout), flying.drawnRevealSrcX, 0.01f
        )

        // There really is nowhere to fly: no orb, no burst, and the destination seams say so.
        assertTrue("no destination was published, so none is drawn", flying.drawnRevealDstX.isNaN())
        assertTrue("no destination was published, so none is drawn", flying.drawnRevealDstY.isNaN())

        // ...and the room must still not double-draw its own copy meanwhile.
        assertTrue(
            "the room must not draw the dot a second time under the scrim",
            flying.storePageRenderer.drawnAstroDotX.isNaN()
        )
    }

    /**
     * The ordering. The panel publishes the crystal tile's rect when it DRAWS, so opening it and
     * launching the orb in the same tick would aim [CrystalOrbPath] at a rect that does not exist
     * yet. The gate holds the reveal in GLOW for exactly that one frame.
     *
     * The portrait half is the one that matters most: the expression must short-circuit before it
     * looks at any draw state at all, or portrait gains a frame of delay it never had.
     */
    @Test
    fun `the reveal waits for the panel to publish its tile, and portrait never waits at all`() {
        val land = viewOn(2844f, 1280f)
        assertFalse(
            "landscape: a shut panel has published no tile, so there is nowhere to fly",
            land.view.revealDestinationReady()
        )
        HangarState.armCrystalRevealPanel(land.view.state)
        assertEquals(HangarPanels.Panel.SHOP, land.view.state.openPanel)
        assertFalse(
            "opening the panel is not the same as having drawn it",
            land.view.revealDestinationReady()
        )
        land.frame()
        assertTrue(
            "one frame later the panel layer has published the tile",
            land.view.revealDestinationReady()
        )

        // Portrait: the board is in the room, so the answer is yes before anything has drawn at
        // all — the reveal fires on exactly the frame it always has.
        val port = viewOn(1280f, 2844f, drawFirstFrame = false)
        assertTrue("portrait may never wait", port.view.revealDestinationReady())
        assertTrue("no panel opens in portrait", port.view.state.openPanel == null)
    }

    /** A real view on a real device profile, with the store page showing. */
    private class Probe(val view: HangarSurfaceView, private val canvas: Canvas) {
        fun frame() = view.renderer.render(canvas, view.state)
    }

    private fun viewOn(physW: Float, physH: Float, drawFirstFrame: Boolean = true): Probe {
        val landscape = physW > physH
        val m = if (landscape) DesignSpace.metricsFor(physW, physH, insetLeftPx = 128f)
        else DesignSpace.metricsFor(physW, physH, insetTopPx = 128f)
        val view = HangarSurfaceView(ApplicationProvider.getApplicationContext<Context>()) { _, _ -> }
        if (landscape) view.applyInsets(128f, 0f, 0f, 0f) else view.applyInsets(0f, 128f, 0f, 0f)
        view.surfaceChanged(view.holder, 0, physW.toInt(), physH.toInt())
        view.state.currentPage = 2
        view.state.phase = HangarPhase.BROWSING
        view.state.astroAtSlotMachine = true
        view.state.awaitingCrystalReveal = true
        view.state.crystalRevealPhase = HangarState.CrystalRevealPhase.GLOW
        val bitmap = Bitmap.createBitmap(
            m.width.toInt().coerceAtLeast(1), m.height.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        return Probe(view, Canvas(bitmap)).also { if (drawFirstFrame) it.frame() }
    }

    /**
     * The dot comes up with the orb so the flight reads as leaving *him* — and the room must stop
     * drawing its own, or it is on screen twice at two different alphas (once under the panel's
     * scrim, once over it).
     */
    @Test
    fun `the room hands its Astro dot to the reveal for the flight, and takes it back after`() {
        for (phase in listOf(
            HangarState.CrystalRevealPhase.ORB_TRAVEL, HangarState.CrystalRevealPhase.FLASH
        )) {
            val flying = renderOnce(land.layout) {
                it.openPanel = HangarPanels.Panel.SHOP
                it.panelFade = 1f
                it.crystalRevealPhase = phase
            }
            assertTrue(
                "$phase: the room must not draw the dot a second time under the scrim",
                flying.storePageRenderer.drawnAstroDotX.isNaN()
            )
            assertFalse(
                "$phase: the reveal must draw the dot itself", flying.drawnRevealSrcX.isNaN()
            )
        }

        // ...and outside the flight the room owns it exactly as it always has.
        val glowing = renderOnce(land.layout) {
            it.crystalRevealPhase = HangarState.CrystalRevealPhase.GLOW
        }
        assertFalse("the room still draws the dot during GLOW", glowing.storePageRenderer.drawnAstroDotX.isNaN())
        assertTrue("nothing in flight, nothing to draw", glowing.drawnRevealSrcX.isNaN())
    }

    // =====================================================================================
    // Portrait cannot change
    // =====================================================================================

    /**
     * The handover must be pixel-identical in portrait: the point the reveal draws Astro at is the
     * very point the room drew him at a frame earlier, and the tile the orb aims for is the
     * in-room board's own.
     *
     * Runs on a tablet as well as a phone, because that is where the two spaces come apart:
     * `crystalTileRect` is published ROOM-LOCAL by the in-room board and SCREEN-SPACE by the
     * panel, and above the sw600 gate a portrait room is narrower than the screen, so reading the
     * room's rect as though it were screen space puts the burst half a gutter off the tile. Below
     * the gate that offset is zero and the mistake is invisible.
     */
    @Test
    fun `portrait aims at the in-room board, phone and tablet, cutout and all`() {
        val profiles = listOf(
            Triple(DesignSpace.metricsFor(1280f, 2844f).layout, 427, "phone"),
            Triple(DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f).layout, 427, "phone+cutout"),
            Triple(DesignSpace.metricsFor(1600f, 2560f).layout, 800, "tablet")
        )
        for ((layout, swDp, name) in profiles) {
            val roomW = roomWidthFor(layout, swDp)
            val origin = RoomAnchor.pageOriginX(2, 2, 0f, layout.width, roomW, false)

            // Where the room itself draws Astro, from its own frame.
            val glowing = renderOnce(layout, swDp) {
                it.crystalRevealPhase = HangarState.CrystalRevealPhase.GLOW
            }
            val roomDotX = glowing.storePageRenderer.drawnAstroDotX
            val roomDotY = glowing.storePageRenderer.drawnAstroDotY
            assertFalse("$name: the room must draw Astro during GLOW", roomDotX.isNaN())

            // The in-room board's own crystal tile, room-local as the room publishes it.
            val bounds = GridGeometry.storeGridBounds(
                layout.content, HangarMetrics.contentXInRoom(layout.content.left, roomW, layout.width),
                layout.height * 0.60f
            )
            val roomTile = GridGeometry.storeTileRects(bounds, tileCount)[tileCount - 1]

            val flying = renderOnce(layout, swDp) {
                it.crystalRevealPhase = HangarState.CrystalRevealPhase.ORB_TRAVEL
                it.crystalRevealTimer = CrystalOrbPath.TRAVEL_DURATION * 0.5f
            }
            assertEquals(
                "$name: source X — the orb leaves exactly where the room drew Astro",
                roomDotX + origin, flying.drawnRevealSrcX, 0.01f
            )
            assertEquals(
                "$name: source Y — the orb leaves exactly where the room drew Astro",
                roomDotY, flying.drawnRevealSrcY, 0.01f
            )
            assertEquals(
                "$name: destination X — the orb aims at the in-room crystal tile",
                roomTile.centerX + origin, flying.drawnRevealDstX, 0.01f
            )
            assertEquals(
                "$name: destination Y — the orb aims at the in-room crystal tile",
                roomTile.centerY, flying.drawnRevealDstY, 0.01f
            )
        }
    }

    /**
     * The source composed above is also the number the walker band uses, so the two cannot drift:
     * Astro stands ON the mini machine, and the walker's own world target is that point less 35.
     */
    @Test
    fun `the orb's source is the slot machine the walker band already knows about`() {
        for (layout in listOf(land.layout, DesignSpace.metricsFor(1280f, 2844f).layout)) {
            val state = armed(layout)
            val ls = RoomAnchor.isLandscape(layout.width, layout.height)
            val viewport = RoomAnchor.viewportX(2, 0f, layout.width, roomWidthFor(layout), ls)
            assertEquals(
                "screen ${layout.width}x${layout.height}",
                machineScreenX(layout), state.slotMachineWorldX() - viewport, 0.01f
            )
        }
    }
}
