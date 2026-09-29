package com.astroloop.game.hangar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.ScreenLayout
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.PilotDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **An object's size never depends on which way the device is held.**
 *
 * The owner's governing rule for the landscape feature, made executable — and made executable
 * through the PRODUCTION DRAW PATH rather than through a second derivation that could agree with a
 * broken renderer. Every number asserted below was published by a real [HangarRenderer.render] pass
 * onto a Robolectric [Canvas]: the rects the draw put on screen, not the rects a test thinks it
 * ought to have. Two previous landscape attempts were reverted for breaking this rule, and the
 * second of them (2026-09-14) survived a full green suite because every guard in it re-derived the
 * geometry instead of reading what was drawn.
 *
 * The suite already contains the re-derived form of this rule in two places — `CrewPanelTest`'s
 * "a portrait card is the size the panel gives it" and `ShopPanelTest`'s "panel tiles are the size
 * they are in portrait" both call [GridGeometry] directly and never touch a renderer. They are
 * worth keeping (they pin the pure pieces) but they cannot catch a call site that stops calling the
 * seam. This file is the other half.
 *
 * Four groups, in the order a defect shows up in them:
 *
 *  1. **The rule itself**, orientation against orientation, drawn against drawn.
 *  2. **Portrait cannot change**, pinned to literal shipped numbers rather than to an expression
 *     that would move with the code it is guarding — with a cutout as well as without, because a
 *     portrait claim tested only at zero insets is what caused the September revert.
 *  3. **The draw/inverse pair**, exercised through real `MotionEvent` dispatch into
 *     `HangarSurfaceView`, on a screen where the room is NOT the screen so the inverse has real
 *     work to do. `RoomAnchorTest`'s round-trip is tautological by its own admission and nothing
 *     else in the suite reaches `HangarSurfaceView.roomX` at all.
 *  4. **The reflow rules**, at the two draw sites whose regressions leave the suite green: page 1's
 *     ship centre (via [HangarRenderer.drawnSelectedShipX], added for this) and CREW's share of the
 *     nav-band height budget.
 *
 * The spec caveat, unchanged from the spec: a cutout is physical and transposes exactly, while a system
 * bar may change edge and thickness on rotation. Every case here holds the inset SET fixed and
 * rotates it, which is what makes the identity checkable at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenRuleTest {

    private lateinit var persistence: PersistenceManager

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
    }

    // =====================================================================================
    // The probe: a real draw pass, read back
    // =====================================================================================

    private class Probe(
        val renderer: HangarRenderer,
        val layout: ScreenLayout,
        val roomWidth: Float
    )

    /**
     * Everything `HangarSurfaceView` does between a surface size and a frame, then one frame.
     *
     * [swDp] defaults to 427 — a phone, below the sw600 gate — matching `CrewPanelTest` and
     * `ShopPanelTest`. A cutout goes on the TOP edge in portrait and the LEFT edge in landscape:
     * the same physical notch, rotated with the device, which is the whole point of the comparison.
     */
    private fun probe(
        physW: Float,
        physH: Float,
        cutoutPx: Float = 0f,
        page: Int,
        openPanel: HangarPanels.Panel? = null,
        swDp: Int = 427,
        thenPage: Int? = null
    ): Probe {
        val m = if (physW > physH) DesignSpace.metricsFor(physW, physH, insetLeftPx = cutoutPx)
        else DesignSpace.metricsFor(physW, physH, insetTopPx = cutoutPx)
        val shaped = DesignSpace.portraitShaped(m.layout)
        val roomWidth = HangarMetrics.roomWidth(shaped.width, shaped.content.width, swDp)

        val renderer = HangarRenderer(persistence)
        renderer.initialize(m.layout, roomWidth)

        val state = HangarState(persistence)
        state.pilotScreenWidth = m.width
        state.pilotScreenHeight = m.height
        state.roomWidth = roomWidth
        state.currentPage = page
        state.phase = HangarPhase.BROWSING
        // What HangarSurfaceView.initShipPositions sets; drawShips reads it for the ship's Y.
        val walkwayY = m.height * 0.60f
        state.shipRestingY = walkwayY + (m.height - walkwayY) * 0.4f
        state.shipDragY = state.shipRestingY
        if (openPanel != null) {
            state.openPanel = openPanel
            state.panelFade = 1f
        }

        val bitmap = Bitmap.createBitmap(
            m.width.toInt().coerceAtLeast(1), m.height.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        renderer.render(canvas, state)
        // A SECOND frame on the SAME renderer, for the "what did this frame draw" seams: a field
        // that is only ever written, never cleared, looks identical to a correct one on a
        // freshly-built renderer, so the only way to test the clearing is to reuse one.
        if (thenPage != null) {
            state.currentPage = thenPage
            renderer.render(canvas, state)
        }
        return Probe(renderer, m.layout, roomWidth)
    }

    /** A device, named by its portrait physical size. Landscape is the transpose. */
    private data class Device(val name: String, val portW: Float, val portH: Float)

    private val devices = listOf(
        Device("Pixel 9 Pro", 1280f, 2844f),
        Device("16:9", 1080f, 1920f)
    )

    // =====================================================================================
    // 1. The rule itself: drawn against drawn
    // =====================================================================================

    /**
     * Catches: the CREW panel sizing its cards from the landscape [ScreenLayout] instead of
     * [HangarRenderer.portraitLayout] — i.e. `panelCardSize()` reading `layout` rather than
     * `portraitLayout`, which is the exact shape of the 2026-09-14 revert — and any attempt to
     * scale cards to fit a short screen. Verified RED by making that substitution.
     *
     * Both sides are read off a draw pass: the portrait number is the in-room grid
     * `BarPageRenderer` actually drew, the landscape number is what the panel actually drew.
     */
    @Test
    fun `a pilot card is drawn at the same size in both orientations, cutout and all`() {
        for (d in devices) for (cutout in listOf(0f, 128f)) {
            val label = "${d.name} cutout=$cutout"
            val portrait = probe(d.portW, d.portH, cutout, page = 0)
            val landscape =
                probe(d.portH, d.portW, cutout, page = 0, openPanel = HangarPanels.Panel.CREW)

            val count = PilotDefinitions.getPilotCount()
            assertEquals("$label: portrait draws its grid in the room", count,
                portrait.renderer.pilotCardRects.size)
            assertTrue("$label: portrait opens no panel", portrait.renderer.panelCardRects.isEmpty())
            assertEquals("$label: the panel draws every pilot, none dropped", count,
                landscape.renderer.panelCardRects.size)

            val inRoom = portrait.renderer.pilotCardRects[0]
            for (r in landscape.renderer.panelCardRects) {
                assertEquals("$label: card width", inRoom.width(), r.width, 0.001f)
                assertEquals("$label: card height", inRoom.height(), r.height, 0.001f)
            }
        }
    }

    /**
     * Catches: `panelTileSize()` measuring the store tile against the landscape layout or the
     * landscape walkway, and a board that shrinks its tiles to keep 3x3 on a short screen. Verified
     * RED by feeding `layout.content` to `panelTileSize()`.
     *
     * `upgradeRects` holds only the purchasable tiles, on both sides, so the counts match by
     * construction and the sizes are the thing under test.
     */
    @Test
    fun `a store tile is drawn at the same size in both orientations, cutout and all`() {
        for (d in devices) for (cutout in listOf(0f, 128f)) {
            val label = "${d.name} cutout=$cutout"
            val portrait = probe(d.portW, d.portH, cutout, page = 2)
            val landscape =
                probe(d.portH, d.portW, cutout, page = 2, openPanel = HangarPanels.Panel.SHOP)

            assertTrue("$label: the room must draw its board in portrait",
                portrait.renderer.upgradeRects.isNotEmpty())
            assertEquals("$label: the panel draws every tile the room does",
                portrait.renderer.upgradeRects.size, landscape.renderer.upgradeRects.size)

            val inRoom = portrait.renderer.upgradeRects[0]
            for (r in landscape.renderer.upgradeRects) {
                assertEquals("$label: tile width", inRoom.width(), r.width(), 0.001f)
                assertEquals("$label: tile height", inRoom.height(), r.height(), 0.001f)
            }
        }
    }

    /**
     * The machine, which neither reflows nor scales — measured at its spin button, because that is
     * the one part of it the draw publishes and its size is proportional to the cabinet
     * (`buttonWidth = reelArea * 0.80`, `boxHeight = machineHeight * 0.12`), not a constant.
     *
     * Catches: `panelMachineFrame()` measured against the landscape layout or the landscape
     * walkway, and a SLOT panel that stretches the cabinet to fill its box. Verified RED by
     * measuring `panelMachineFrame()` against `layout` instead of `portraitLayout`.
     */
    @Test
    fun `the slot machine is drawn at the same size in both orientations, cutout and all`() {
        for (d in devices) for (cutout in listOf(0f, 128f)) {
            val label = "${d.name} cutout=$cutout"
            val portrait = probe(d.portW, d.portH, cutout, page = 2)
            val landscape =
                probe(d.portH, d.portW, cutout, page = 2, openPanel = HangarPanels.Panel.SLOT)

            val inRoom = portrait.renderer.spinButtonRect
            val inPanel = landscape.renderer.spinButtonRect
            assertFalse("$label: the room must draw its machine", inRoom.isEmpty)
            assertFalse("$label: the panel must draw its machine", inPanel.isEmpty)
            assertEquals("$label: spin button width", inRoom.width(), inPanel.width(), 0.001f)
            assertEquals("$label: spin button height", inRoom.height(), inPanel.height(), 0.001f)
        }
    }

    /**
     * The counter is the same counter either way round, measured at the far end of it: the codex
     * book sits at `barRight - 55`, and `barRight` is `effectiveRoomWidth - 10`.
     *
     * Catches: any bar-room dressing that reaches for `screenWidth` where it should use the room's
     * own (portrait) width — on a rotated phone those differ by 1178 design units, so the book ends
     * up out in the starfield past the archway. Verified RED by pointing `barRight` at
     * `screenWidth`. Vertical is deliberately NOT compared: rooms tile horizontally, so the room's
     * height is the screen's height and the counter rides the walkway in both orientations. Nor is
     * the book's SIZE — it is a literal 22x15 with no orientation input, so an equality on it could
     * not be made to fail and would only look like a guard.
     */
    @Test
    fun `the counter ends at the same room-local X in both orientations, cutout and all`() {
        for (d in devices) for (cutout in listOf(0f, 128f)) {
            val label = "${d.name} cutout=$cutout"
            val portrait = probe(d.portW, d.portH, cutout, page = 0)
            val landscape = probe(d.portH, d.portW, cutout, page = 0)

            val p = portrait.renderer.codexBookRect
            val l = landscape.renderer.codexBookRect
            assertFalse("$label: portrait must draw the book", p.isEmpty)
            assertFalse("$label: landscape must draw the book", l.isEmpty)
            assertEquals("$label: book right edge, room-local", p.right, l.right, 0.001f)
            // And it really is inside the room rather than merely equal to a wrong constant.
            assertTrue("$label: book must sit inside the room", l.right <= landscape.roomWidth)
        }
    }

    // =====================================================================================
    // 2. Portrait cannot change
    // =====================================================================================

    /**
     * The shipped portrait numbers, as literals.
     *
     * Every other portrait guard in the suite compares the draw against an expression built from
     * the same production helpers the draw uses — which pins the wiring but moves with the
     * geometry. These are the geometry. Nothing about portrait may move for any reason, so a
     * literal is the honest expected value here: if one of these changes, either portrait changed
     * or the design did, and both need a human.
     *
     * Reference device is the owner's: a Pixel-9-Pro-shaped 1280x2844 panel, run with the 128px
     * cutout it actually has as well as without — the September revert shipped a suite of portrait
     * identities every one of which used zero insets.
     */
    @Test
    fun `portrait's drawn geometry is pinned to its shipped numbers, cutout and all`() {
        // --- No cutout ---
        var bar = probe(1280f, 2844f, 0f, page = 0)
        var store = probe(1280f, 2844f, 0f, page = 2)
        assertEquals("room width", 964.0506f, bar.roomWidth, 0.001f)
        var card = bar.renderer.pilotCardRects[0]
        assertEquals("card width", 228.0f, card.width(), 0.001f)
        assertEquals("card height", 342.61334f, card.height(), 0.001f)
        assertEquals("card 0 left", 14.025316f, card.left, 0.001f)
        assertEquals("card 0 top", 70.0f, card.top, 0.001f)
        assertEquals("store tile", 302.66667f, store.renderer.upgradeRects[0].width(), 0.001f)

        // --- 128px cutout on the top edge ---
        bar = probe(1280f, 2844f, 128f, page = 0)
        store = probe(1280f, 2844f, 128f, page = 2)
        assertEquals("room width, cutout", 964.0506f, bar.roomWidth, 0.001f)
        card = bar.renderer.pilotCardRects[0]
        assertEquals("card width, cutout", 217.19832f, card.width(), 0.001f)
        assertEquals("card height, cutout", 325.90312f, card.height(), 0.001f)
        assertEquals("card 0 left, cutout", 35.62869f, card.left, 0.001f)
        assertEquals("card 0 top, cutout", 166.40506f, card.top, 0.001f)
        assertEquals("store tile, cutout", 288.26443f, store.renderer.upgradeRects[0].width(), 0.001f)
    }

    // =====================================================================================
    // 3. The draw/inverse pair, through real touch dispatch
    // =====================================================================================

    /**
     * The one test in the tree that reaches `HangarSurfaceView.roomX`.
     *
     * `RoomAnchorTest`'s round-trip says so itself: `toRoomX` is defined as
     * `screenX - pageOriginX(current, current, …)`, so `roomLocal + X - X == roomLocal` holds for
     * any `pageOriginX` whatsoever, broken or not. And nothing in `LandscapeRoomTest` calls
     * `HangarSurfaceView.roomX`/`roomOriginX` at all — the draw/inverse pairing has been correct
     * only by construction and by review.
     *
     * So: a real `MotionEvent` through `onTouchEvent`, on a TABLET in portrait, where the room is
     * the content column and therefore genuinely narrower than the screen. The card's room-local
     * rect comes from the draw; the screen point is that rect's centre plus a HAND-derived room
     * origin `(screenWidth - roomWidth) / 2` — not `RoomAnchor.pageOriginX`, which is half of what
     * is under test. Production then has to invert it and land on the same card.
     *
     * Catches: `roomX` losing its anchor term (the identity), `roomOriginX` measuring the wrong
     * page, and the draw anchor and the inverse anchor disagreeing by any amount wider than the
     * 8px gutter between cards. Verified RED by making `roomX` the identity: the tap lands a
     * column and a half away and selects the wrong pilot.
     */
    @Test
    @Config(qualifiers = "sw800dp")
    fun `a real tap where a card was drawn selects that card, on a screen whose room is not the screen`() {
        for (i in 0 until PilotDefinitions.getPilotCount()) {
            PilotDefinitions.getPilotByIndex(i)?.let { persistence.unlockPilot(it.id) }
        }

        val physW = 1600f
        val physH = 2560f
        val m = DesignSpace.metricsFor(physW, physH)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = HangarSurfaceView(context) { _, _ -> }
        view.surfaceChanged(view.holder, 0, physW.toInt(), physH.toInt())
        view.state.currentPage = 0
        view.state.phase = HangarPhase.BROWSING

        val bitmap = Bitmap.createBitmap(
            m.width.toInt(), m.height.toInt(), Bitmap.Config.ARGB_8888
        )
        view.renderer.render(Canvas(bitmap), view.state)

        // The fixture has to actually exercise the narrow-room branch, or the inverse has nothing
        // to undo and this test degenerates into the identity it exists to rule out.
        val roomWidth = HangarMetrics.roomWidth(
            m.width, m.layout.content.width, context.resources.configuration.smallestScreenWidthDp
        )
        assertTrue(
            "fixture must be above the sw600 gate: room $roomWidth vs screen ${m.width}",
            roomWidth < m.width - 1f
        )
        val handDerivedOrigin = (m.width - roomWidth) / 2f
        assertTrue("the hand-derived room origin must be non-zero", handDerivedOrigin > 1f)

        val drawn = view.renderer.pilotCardRects.toList()
        assertEquals(PilotDefinitions.getPilotCount(), drawn.size)

        // Three cards spread across the grid, none of them the one already selected (a second tap
        // on the selected card flips it instead of selecting, which would prove nothing).
        for (index in listOf(3, 6, 9)) {
            assertNotEquals("pick a card that is not already selected",
                index, view.state.selectedPilotIndex)
            val rect = drawn[index]
            tap(view, m.renderScale, rect.centerX() + handDerivedOrigin, rect.centerY())
            assertEquals(
                "a tap at drawn card $index must select pilot $index",
                index, view.state.selectedPilotIndex
            )
        }
    }

    /**
     * The landscape half of the same pairing: the CREW panel publishes in SCREEN space and
     * `handlePanelTap` hit-tests it without going through `roomX` at all. Dispatching at the drawn
     * centre proves the panel's own draw/tap pair agrees, through `onTouchEvent`.
     *
     * Catches: a transform applied in `drawPanelContents` but not reflected in what it publishes
     * (or vice versa), and a `handlePanelTap` that converts the point into room space the way the
     * room's handlers do — which would shift every panel tap by the crew room's right-hug anchor,
     * 1178 units on this device. Verified RED by routing `handlePanelTap`'s point through `roomX`.
     */
    @Test
    fun `a real tap where a panel card was drawn selects that pilot`() {
        for (i in 0 until PilotDefinitions.getPilotCount()) {
            PilotDefinitions.getPilotByIndex(i)?.let { persistence.unlockPilot(it.id) }
        }

        val m = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = HangarSurfaceView(context) { _, _ -> }
        view.applyInsets(128f, 0f, 0f, 0f)
        view.surfaceChanged(view.holder, 0, 2844, 1280)
        view.state.currentPage = 0
        view.state.phase = HangarPhase.BROWSING
        view.state.openPanel = HangarPanels.Panel.CREW
        view.state.panelFade = 1f

        val bitmap = Bitmap.createBitmap(
            m.width.toInt(), m.height.toInt(), Bitmap.Config.ARGB_8888
        )
        view.renderer.render(Canvas(bitmap), view.state)

        val drawn = view.renderer.panelCardRects.toList()
        assertEquals(PilotDefinitions.getPilotCount(), drawn.size)
        for (index in listOf(2, 7, 11)) {
            assertNotEquals(index, view.state.selectedPilotIndex)
            val rect = drawn[index]
            tap(view, m.renderScale, rect.centerX, rect.centerY)
            assertEquals(
                "a tap at drawn panel card $index must select pilot $index",
                index, view.state.selectedPilotIndex
            )
        }
    }

    /** DOWN then UP at the same point — a tap, in design units, scaled back to device pixels. */
    private fun tap(view: HangarSurfaceView, renderScale: Float, x: Float, y: Float) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0L, 0L, action, x * renderScale, y * renderScale, 0)
            try {
                view.onTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    // =====================================================================================
    // 4. The two draw sites whose regressions leave the suite green
    // =====================================================================================

    /**
     * Page 1's ship centre, read off the draw.
     *
     * An earlier change unified every page-1 centre onto [HangarRenderer.shipyardCenterX], and
     * `LandscapeRoomTest` pins that accessor at zero tolerance — but reverting `drawShips`' own
     * `val centerX` to re-derive `HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth) / 2f`
     * leaves the accessor untouched and every existing test green (verified experimentally in the
     * code review). [HangarRenderer.drawnSelectedShipX] exists to close exactly that: it is the
     * screen X the ship was actually drawn at.
     *
     * The expected value is the literal `screenWidth / 2f` that `HangarSurfaceView.
     * handleShipyardTap` and `publishShipyardFocus` both fire at — not a call back into
     * `shipyardCenterX()` or `RoomAnchor.pageWidth`, which are the functions under test.
     *
     * The discriminating profiles are the two where the room is not the page: a rotated phone
     * (room 964, page 2142) and a portrait tablet above the sw600 gate. A portrait phone below the
     * gate cannot discriminate — the room IS the screen there — and is asserted anyway, because
     * portrait not moving is the point of the whole feature. Verified RED on the rotated phone by
     * making that exact substitution in `drawShips`.
     */
    @Test
    fun `the drawn ship lands on the screen centre its hit test fires at, cutout and all`() {
        data class Case(val label: String, val w: Float, val h: Float, val swDp: Int)
        val cases = listOf(
            Case("phone portrait", 1280f, 2844f, 427),
            Case("phone landscape", 2844f, 1280f, 427),
            Case("tablet portrait", 1600f, 2560f, 800),
            Case("tablet landscape", 2560f, 1600f, 800)
        )
        for (c in cases) for (cutout in listOf(0f, 128f)) {
            val p = probe(c.w, c.h, cutout, page = 1, swDp = c.swDp)
            assertEquals(
                "${c.label} cutout=$cutout: the drawn ship must sit on screenWidth / 2",
                p.layout.width / 2f, p.renderer.drawnSelectedShipX, 0.001f
            )
        }
    }

    /**
     * The seam above reports THIS frame, not the last frame that happened to draw a ship — without
     * which the test above would keep passing off a stale value after page 1 left the screen, and
     * the guard would quietly stop guarding.
     *
     * Two frames on ONE renderer, deliberately: on a renderer built fresh per frame an
     * only-ever-written field is indistinguishable from a correctly cleared one, and the first
     * version of this test made exactly that mistake and could not be made to fail. Verified RED by
     * deleting `render()`'s `drawnSelectedShipX = Float.NaN` line.
     */
    @Test
    fun `the ship seam reports nothing on a frame that drew no shipyard`() {
        val p = probe(2844f, 1280f, 128f, page = 1, thenPage = 0)
        assertTrue(
            "page 1 is off screen on the second frame, so nothing may be reported as drawn",
            p.renderer.drawnSelectedShipX.isNaN()
        )
    }

    /**
     * CREW's share of the nav-band height budget.
     *
     * A code review was explicit that the budget (`panelAvailableHeight`, which caps a panel's
     * content so its box — always centred on `safe.centerY` — cannot grow into the nav row's tap
     * band) applies to every panel that reflows, CREW included. That is code-verified and, until
     * now, test-unverified: on a rotated Pixel 9 Pro the crew board reflows to 6x2 under BOTH the
     * budgeted rule and the old unbudgeted `safe.height - PAD_V * 2`, so reverting it changes
     * nothing anywhere in the suite.
     *
     * A 16:9 landscape screen is the profile that separates them. The crew board at 4x3 is 1043.84
     * tall on every landscape profile (the portrait content rect is 2142 tall whatever the screen,
     * being aspect-contained), the nav budget there is 1000.39 and the safe-area budget is 1180.88
     * — so the budgeted rule reflows to 6x2 and the unbudgeted one keeps 4x3, drawing a board whose
     * bottom rows sit under the lit [CREW]/[LAUNCH] labels.
     *
     * The second profile is the other half of the guard: a 3:2 landscape screen has room above its
     * nav band for the full 4x3 (budget 1201.2 > 1043.84), so a panel that reflowed
     * unconditionally — or one that had shrunk the cards to fit — fails there. Only the real rule
     * passes both. Verified RED in both directions: substituting `safe.height - PAD_V * 2f` back
     * into `crewGridArrangement` fails the 16:9 case, and hardcoding a 2-row arrangement fails the
     * 3:2 case.
     */
    @Test
    fun `the crew panel is budgeted against the nav band too, not only the shop's board`() {
        val pinched = probe(1920f, 1080f, 0f, page = 0, openPanel = HangarPanels.Panel.CREW)
        val roomy = probe(2160f, 1440f, 0f, page = 0, openPanel = HangarPanels.Panel.CREW)

        assertEquals(
            "16:9: the nav band forces the crew board off three rows",
            2, pinched.renderer.panelCardRects.map { it.top }.distinct().size
        )
        assertEquals(
            "3:2: three rows fit above the nav band, so nothing may reflow",
            3, roomy.renderer.panelCardRects.map { it.top }.distinct().size
        )

        // Reflowed, never scaled: both boards keep the portrait card, whatever shape they took.
        val portraitCard = probe(1080f, 1920f, 0f, page = 0).renderer.pilotCardRects[0]
        for (r in pinched.renderer.panelCardRects) {
            assertEquals("16:9 reflow must not resize", portraitCard.width(), r.width, 0.001f)
            assertEquals("16:9 reflow must not resize", portraitCard.height(), r.height, 0.001f)
        }

        // And the budget it is being held to is real: the box clears the nav band's tap zone,
        // whose top HangarSurfaceView.handleTap writes as `screenHeight * 0.95f - 30f`.
        for (p in listOf(pinched, roomy)) {
            val navTop = p.layout.height * 0.95f - 30f
            val bottom = p.renderer.panelCardRects.maxOf { it.bottom } + HangarPanels.PAD_V
            assertTrue("panel bottom $bottom must clear the nav band at $navTop", bottom < navTop)
        }
    }
}
