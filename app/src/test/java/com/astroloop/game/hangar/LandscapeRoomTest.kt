package com.astroloop.game.hangar

import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The room keeps its portrait width and leans toward the shipyard.
 *
 * Every portrait assertion runs WITH a cutout as well as without. The 2026-09-14 revert happened
 * because a whole suite of portrait-identity tests used zero insets and so tested a configuration
 * the owner's device does not have.
 *
 * Robolectric only because [HangarState] needs a [PersistenceManager]; every number below is plain
 * Kotlin layout maths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LandscapeRoomTest {

    private lateinit var persistence: PersistenceManager

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
    }

    /**
     * Exactly what `HangarSurfaceView.applyScreenDimensions` computes: the design space for this
     * physical screen, shaped back to portrait, then `HangarMetrics.roomWidth` over that.
     */
    private fun roomWidthFor(physW: Float, physH: Float, swDp: Int, cutoutPx: Float): Float {
        val m = if (physW > physH) DesignSpace.metricsFor(physW, physH, insetLeftPx = cutoutPx)
        else DesignSpace.metricsFor(physW, physH, insetTopPx = cutoutPx)
        val portrait = DesignSpace.portraitShaped(m.layout)
        return HangarMetrics.roomWidth(portrait.width, portrait.content.width, swDp)
    }

    @Test
    fun `a rotated phone's room is its portrait room, cutout and all`() {
        for (cutout in listOf(0f, 128f)) {
            val portrait = roomWidthFor(1280f, 2844f, swDp = 427, cutoutPx = cutout)
            val landscape = roomWidthFor(2844f, 1280f, swDp = 427, cutoutPx = cutout)
            assertEquals("cutout=$cutout", portrait, landscape, 0.01f)
        }
    }

    @Test
    fun `a rotated tablet's room is its portrait content column, not the screen`() {
        val portrait = roomWidthFor(1600f, 2560f, swDp = 800, cutoutPx = 0f)
        val landscape = roomWidthFor(2560f, 1600f, swDp = 800, cutoutPx = 0f)
        assertEquals(portrait, landscape, 0.01f)
        assertTrue("the room must be narrower than the rotated screen", landscape < 2560f)
    }

    @Test
    fun `a rotated room is narrower than the screen it is drawn on`() {
        // The whole point of the task, stated as a number: before this, roomWidth was the
        // landscape screen width and the room stretched to fill it.
        val m = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f)
        val roomW = roomWidthFor(2844f, 1280f, swDp = 427, cutoutPx = 128f)
        assertTrue("room $roomW must be narrower than screen ${m.width}", roomW < m.width - 100f)
        // ...while the stride stays a full screen, so neighbours stay off-screen.
        assertEquals(m.width, RoomAnchor.stride(m.width, roomW, landscape = true), 0.01f)
    }

    @Test
    fun `the crew room's right edge is the screen's right edge`() {
        val m = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f)
        val roomW = roomWidthFor(2844f, 1280f, swDp = 427, cutoutPx = 128f)
        val originX = RoomAnchor.pageOriginX(0, 0, 0f, m.width, roomW, landscape = true)
        assertEquals(m.width, originX + roomW, 0.01f)
    }

    @Test
    fun `the shop room's left edge is the screen's left edge`() {
        val m = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f)
        val roomW = roomWidthFor(2844f, 1280f, swDp = 427, cutoutPx = 128f)
        assertEquals(0f, RoomAnchor.pageOriginX(2, 2, 0f, m.width, roomW, landscape = true), 0.01f)
    }

    @Test
    fun `the rooms lean toward the shipyard, so neither leaves a gap beside it`() {
        // Crew's right edge and the shipyard's left edge are the same world point, and likewise
        // shop's left with the shipyard's right — so the walker never crosses starfield.
        val m = DesignSpace.metricsFor(2844f, 1280f)
        val roomW = roomWidthFor(2844f, 1280f, swDp = 427, cutoutPx = 0f)
        val crewRight = RoomAnchor.pageOriginX(0, 1, 0f, m.width, roomW, true) + roomW
        val shipyardLeft = RoomAnchor.pageOriginX(1, 1, 0f, m.width, roomW, true)
        assertEquals(shipyardLeft, crewRight, 0.01f)
        val shipyardRight = shipyardLeft + m.width
        val shopLeft = RoomAnchor.pageOriginX(2, 1, 0f, m.width, roomW, true)
        assertEquals(shipyardRight, shopLeft, 0.01f)
    }

    @Test
    fun `the walker's target lands inside its own room on every page`() {
        val state = HangarState(persistence)
        val m = DesignSpace.metricsFor(2844f, 1280f)
        state.pilotScreenWidth = m.width
        state.pilotScreenHeight = m.height
        state.roomWidth = roomWidthFor(2844f, 1280f, swDp = 427, cutoutPx = 0f)
        assertTrue("fixture must actually be landscape", state.landscape)
        for (page in 0..2) {
            val world = state.getPilotWorldTarget(page)
            val stride = RoomAnchor.stride(m.width, state.roomWidth, landscape = true)
            val left = page * stride + RoomAnchor.anchorX(page, m.width, state.roomWidth, true)
            val width = RoomAnchor.pageWidth(page, m.width, state.roomWidth, true)
            assertTrue("page $page target $world before room at $left", world >= left)
            assertTrue("page $page target $world past room end ${left + width}", world <= left + width)
        }
    }

    @Test
    fun `the slot machine's world point is the shop page's own target`() {
        // The crystal reveal parks Astro here; the store page draws the machine at the room-local
        // twin of it. One derivation, so the two cannot drift.
        val state = HangarState(persistence)
        val m = DesignSpace.metricsFor(2844f, 1280f)
        state.pilotScreenWidth = m.width
        state.pilotScreenHeight = m.height
        state.roomWidth = roomWidthFor(2844f, 1280f, swDp = 427, cutoutPx = 0f)
        assertEquals(state.getPilotWorldTarget(2), state.slotMachineWorldX(), 0.01f)

        // The room-local twin StorePageRenderer draws at, put back on the world by page 2's origin.
        val roomLocal = state.roomWidth * 0.1f + 0.1f * (state.roomWidth * 0.8f)
        val page2Left = 2f * RoomAnchor.stride(m.width, state.roomWidth, true) +
            RoomAnchor.anchorX(2, m.width, state.roomWidth, true)
        assertEquals(page2Left + roomLocal, state.slotMachineWorldX(), 0.01f)
    }

    @Test
    fun `portrait walker targets are unchanged by the anchor`() {
        val state = HangarState(persistence)
        val m = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f)
        state.pilotScreenWidth = m.width
        state.pilotScreenHeight = m.height
        state.roomWidth = m.width
        assertTrue("fixture must actually be portrait", !state.landscape)
        // The shipped arithmetic: page * stride + margin + fraction * walkable, no anchor term.
        val margin = m.width * 0.1f
        val walkable = m.width * 0.8f
        assertEquals(0 * m.width + margin + 0.9f * walkable, state.getPilotWorldTarget(0), 0.01f)
        assertEquals(1 * m.width + margin + 0.5f * walkable, state.getPilotWorldTarget(1), 0.01f)
        assertEquals(2 * m.width + margin + 0.1f * walkable, state.getPilotWorldTarget(2), 0.01f)
    }

    @Test
    fun `portrait draws the walker exactly where it always did, cutout and all`() {
        // Above the gate the walker's WORLD number rebases by the block-centring term, because
        // RoomAnchor.viewportX shed that same term (HangarMetrics.viewportX carried it). What must
        // not move is where he is DRAWN, which is `pilotX - viewportX`, so that is what this pins —
        // against the shipped arithmetic written out, on the tablet branch AND the phone branch,
        // with a cutout and without.
        //
        //     shipped screen X = (page - current) * roomW + (screenW - roomW) / 2 - scroll
        //                        + roomW * 0.1 + fraction * roomW * 0.8
        val fractions = mapOf(0 to 0.9f, 1 to 0.5f, 2 to 0.1f)
        for ((physW, physH) in listOf(1280f to 2844f, 1600f to 2560f)) {
            for (cutout in listOf(0f, 128f)) {
                val m = DesignSpace.metricsFor(physW, physH, insetTopPx = cutout)
                for (swDp in listOf(427, 800)) {
                    val roomW = HangarMetrics.roomWidth(m.width, m.layout.content.width, swDp)
                    val state = HangarState(persistence)
                    state.pilotScreenWidth = m.width
                    state.pilotScreenHeight = m.height
                    state.roomWidth = roomW
                    for (current in 0..2) {
                        for (scroll in listOf(-120f, 0f, 47.5f)) {
                            val viewportX = RoomAnchor.viewportX(
                                current, scroll, m.width, roomW, landscape = false
                            )
                            for (page in 0..2) {
                                val shipped = (page - current) * roomW + (m.width - roomW) / 2f -
                                    scroll + roomW * 0.1f + fractions.getValue(page) * roomW * 0.8f
                                assertEquals(
                                    "$physW x $physH cutout=$cutout sw=$swDp page=$page " +
                                        "current=$current scroll=$scroll",
                                    shipped,
                                    state.getPilotWorldTarget(page) - viewportX,
                                    0.01f
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the shipyard's drawn width matches the screen-centre hit test, below the sw600 gate`() {
        // Pixel 9 Pro rotated, no cutout: below the sw600 gate, so the room narrows to the
        // portrait room width while the launchpad is still meant to stay full-screen.
        val m = DesignSpace.metricsFor(2844f, 1280f)
        val roomW = roomWidthFor(2844f, 1280f, swDp = 427, cutoutPx = 0f)
        assertTrue(
            "fixture must actually have a narrower room than the screen, or this proves nothing",
            roomW < m.width - 100f
        )

        // Hand derivation — not by calling RoomAnchor.pageWidth or HangarRenderer.shipyardPageWidth,
        // the functions under test: the launchpad is the one page landscape leaves full-screen
        // (stated in RoomAnchor's own doc comment and unchanged by this fix), so its drawing width
        // IS the screen width, full stop, and the ship's drawn centre is screenWidth / 2 — the exact
        // literal HangarSurfaceView.handleShipyardTap ("val centerX = screenWidth / 2") and
        // publishShipyardFocus ("centerX = screenWidth / 2f") both use.
        val expectedDrawWidth = 2844f / DesignSpace.renderScale(2844f, 1280f)
        val expectedShipCenterX = expectedDrawWidth / 2f
        // Sanity-check the hand derivation against the fixture's own screen width before using it.
        assertEquals(expectedDrawWidth, m.width, 0.01f)

        val renderer = HangarRenderer(persistence)
        renderer.initialize(m.layout, roomW)

        assertEquals(
            "the shipyard must draw at the FULL-screen width in landscape, not the narrower room",
            expectedDrawWidth, renderer.shipyardPageWidth(), 0.01f
        )
        assertEquals(
            "the ship's drawn centre must be the same point the touch/focus hit tests use",
            expectedShipCenterX, renderer.shipyardPageWidth() / 2f, 0.01f
        )
    }

    @Test
    fun `the launchpad's drawn centre is the screen-centre hit test, in every orientation and gate branch`() {
        // A guard-gap review found: `shipyardPageWidth() == 2142` alone does
        // not pin the WIRING — a call site (drawShips' `rw`, drawLaunchRail's `centerX`, …) could
        // drift back onto `HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth)` and that one
        // number would be unaffected (its own halved twin passes too — half of anything passes).
        // The property that actually matters is that the point HangarRenderer draws page 1's
        // ship/rail/halo at and the point HangarSurfaceView's hit tests fire at are the same
        // point:
        //
        //     pageOriginX(1, currentPage = 1, scroll = 0) + shipyardCenterX() == screenWidth / 2
        //
        // Every expected number below is derived BY HAND from RoomAnchor's own written-out
        // arithmetic (its doc comments and body, read together) — never by calling
        // RoomAnchor.pageOriginX/pageWidth/anchorX or HangarRenderer.shipyardPageWidth/
        // shipyardCenterX, the functions under test:
        //
        //   viewportX(1, 0, ...)   = 1 * stride + 0 = stride
        //   pageOriginX(1,1,0,…)   = 1*stride - stride + anchorX(1,…) = anchorX(1,…)
        //   anchorX(1,…)           = (screenWidth - pageWidth(1,…)) / 2
        //                            — page 1 is neither 0 nor 2, so it always falls through
        //                            anchorX's landscape `else` branch to this same formula;
        //                            that one fact is why the invariant holds in BOTH
        //                            orientations, for two different reasons:
        //   pageWidth(1,…)         = screenWidth in landscape (the launchpad alone stays
        //                            full-screen) — so origin = 0 and centre = screenWidth / 2
        //                            directly;
        //                          = roomWidth in portrait — so origin = (sw - rw) / 2 and
        //                            centre = rw / 2, which sum to sw / 2 by cancellation, not
        //                            because either term is individually screenWidth / 2.
        data class Fixture(
            val label: String, val physW: Float, val physH: Float, val swDp: Int, val cutout: Float
        )
        val fixtures = listOf(
            Fixture("phone portrait, no cutout", 1280f, 2844f, 427, 0f),
            Fixture("phone portrait, cutout", 1280f, 2844f, 427, 128f),
            Fixture("phone landscape, no cutout", 2844f, 1280f, 427, 0f),
            Fixture("phone landscape, cutout", 2844f, 1280f, 427, 128f),
            Fixture("tablet portrait, no cutout", 1600f, 2560f, 800, 0f),
            Fixture("tablet portrait, cutout", 1600f, 2560f, 800, 128f),
            Fixture("tablet landscape, no cutout", 2560f, 1600f, 800, 0f),
            Fixture("tablet landscape, cutout", 2560f, 1600f, 800, 128f)
        )

        for (f in fixtures) {
            val landscape = f.physW > f.physH
            val m = if (landscape) DesignSpace.metricsFor(f.physW, f.physH, insetLeftPx = f.cutout)
                    else DesignSpace.metricsFor(f.physW, f.physH, insetTopPx = f.cutout)
            val roomW = roomWidthFor(f.physW, f.physH, swDp = f.swDp, cutoutPx = f.cutout)

            // Hand-derived expectation, per the branch worked out above.
            val expectedPageWidth = if (landscape) m.width else roomW
            val expectedOrigin = (m.width - expectedPageWidth) / 2f
            val expectedCenter = expectedPageWidth / 2f
            assertEquals(
                "${f.label}: the hand derivation itself must sum to the screen centre, or it is " +
                    "not the invariant being tested",
                m.width / 2f, expectedOrigin + expectedCenter, 0.01f
            )

            val renderer = HangarRenderer(persistence)
            renderer.initialize(m.layout, roomW)
            val actualOrigin = RoomAnchor.pageOriginX(1, 1, 0f, m.width, roomW, landscape)
            val actualCenter = renderer.shipyardCenterX()

            assertEquals("${f.label}: page-1 origin", expectedOrigin, actualOrigin, 0.01f)
            assertEquals("${f.label}: shipyard centre", expectedCenter, actualCenter, 0.01f)

            // The actual invariant: the launchpad's drawn centre lands exactly on the
            // screen-centre hit test every page-1 draw site is supposed to share with
            // HangarSurfaceView's `handleShipyardTap`/`publishShipyardFocus` (both literally
            // `screenWidth / 2`).
            assertEquals(
                "${f.label}: launchpad drawn centre must equal the screen-centre hit test",
                m.width / 2f, actualOrigin + actualCenter, 0.01f
            )
        }
    }

    @Test
    fun `a state that knows its width but not its height is treated as portrait`() {
        // Every frame between the two assignments looks like this, and so do several older test
        // fixtures. isLandscape alone would answer `width > 0` — LANDSCAPE — and anchor the walker
        // on a portrait phone.
        val state = HangarState(persistence)
        state.pilotScreenWidth = 1000f
        assertTrue("an unknown height must answer portrait", !state.landscape)
        state.pilotScreenHeight = 2000f
        assertTrue(!state.landscape)
        state.pilotScreenHeight = 500f
        assertTrue(state.landscape)
    }

    @Test
    fun `the tablet fixture above really exercises the narrow-room branch`() {
        // Or the test above would be three copies of the below-gate case.
        val m = DesignSpace.metricsFor(1600f, 2560f, insetTopPx = 128f)
        val roomW = HangarMetrics.roomWidth(m.width, m.layout.content.width, smallestScreenWidthDp = 800)
        assertTrue("the tablet room must really be narrower than its screen", roomW < m.width - 100f)
    }

    @Test
    fun `portrait page origins keep the shipped centred-block transform, cutout and all`() {
        // The draw side, pinned against the arithmetic HangarMetrics.viewportX/roomOriginX
        // performed before they were deleted, written out here so the pin survives them:
        //     roomOriginX = (screenW - roomW) / 2 - scroll
        //     viewportX   = currentPage * roomW - roomOriginX
        //     pageOrigin  = page * roomW - viewportX
        for ((physW, physH) in listOf(1280f to 2844f, 1600f to 2560f)) {
            for (cutout in listOf(0f, 128f)) {
                val m = DesignSpace.metricsFor(physW, physH, insetTopPx = cutout)
                for (swDp in listOf(427, 800)) {
                    val roomW = HangarMetrics.roomWidth(m.width, m.layout.content.width, swDp)
                    for (current in 0..2) {
                        for (scroll in listOf(-120f, 0f, 47.5f)) {
                            val roomOrigin = (m.width - roomW) / 2f - scroll
                            for (page in 0..2) {
                                val shipped = page * roomW - (current * roomW - roomOrigin)
                                assertEquals(
                                    "$physW x $physH cutout=$cutout sw=$swDp page=$page " +
                                        "current=$current scroll=$scroll",
                                    shipped,
                                    RoomAnchor.pageOriginX(
                                        page, current, scroll, m.width, roomW, landscape = false
                                    ),
                                    0.01f
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
