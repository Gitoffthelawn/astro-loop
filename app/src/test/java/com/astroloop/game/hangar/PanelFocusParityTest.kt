package com.astroloop.game.hangar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.PilotDefinitions
import com.astroloop.game.input.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Everything a finger can reach in the landscape panel layer, a controller must reach at the
 * same rect — and everything that blocks the finger must block the pad.**
 *
 * Driven through the REAL VIEW, not through hand-made rects fed to a static publisher. Every
 * target asserted below was published by `HangarSurfaceView.publishFocusPass` — the production
 * pass `render()` itself calls — from the rects a real `HangarRenderer.render` put on screen one
 * line earlier. A parity test built the other way (rects invented by the test, handed to the
 * publisher) proves the publisher works and says nothing about whether the view publishes the
 * right rects, which is precisely the defect this task exists to fix: `publishBarFocus` added a
 * room origin to rects the CREW panel had already published in screen space, putting the ring
 * ~1178 units right of the cards, and then activated them through `handleBarTap`, whose
 * `getPilotGridIndex` returns null in landscape.
 *
 * Robolectric, sdk 28, matching the other hangar tests. `HangarSurfaceView` drives in-process
 * under it — an earlier change proved that with real `MotionEvent` dispatch — so the pad verbs
 * (`onActivateDown`, `onDirectionalPress`, `onCancel`) are called on the real view too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PanelFocusParityTest {

    private lateinit var persistence: PersistenceManager

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
    }

    // =====================================================================================
    // The probe: a real view, a real draw pass, the real focus pass
    // =====================================================================================

    private class Pad(val view: HangarSurfaceView, private val canvas: Canvas) {

        /** One production frame: draw, then publish from what the draw put on screen. */
        fun frame() {
            view.renderer.render(canvas, view.state)
            view.publishFocusPass()
        }

        val ids: List<String> get() = view.hangarFocus.targets().map { it.id }

        fun rect(id: String): RectF = view.hangarFocus.byId(id)!!.rect

        fun focus(id: String) {
            view.hangarFocus.focusedId = id
        }

        val focused: String? get() = view.hangarFocus.focusedId

        /** OK on the pad, through the same verb `InputRouter` calls. */
        fun press(): Boolean = view.onActivateDown()
    }

    /**
     * A view sized to a real device, one frame in.
     *
     * A cutout goes on the LEFT edge in landscape and the TOP edge in portrait — the same physical
     * notch, rotated with the device, exactly as `GoldenRuleTest` poses it.
     */
    private fun pad(
        physW: Float,
        physH: Float,
        page: Int,
        panel: HangarPanels.Panel? = null,
        closing: HangarPanels.Panel? = null,
        cutoutPx: Float = 0f
    ): Pad {
        val landscape = physW > physH
        val m = if (landscape) DesignSpace.metricsFor(physW, physH, insetLeftPx = cutoutPx)
        else DesignSpace.metricsFor(physW, physH, insetTopPx = cutoutPx)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = HangarSurfaceView(context) { _, _ -> }
        if (cutoutPx > 0f) {
            if (landscape) view.applyInsets(cutoutPx, 0f, 0f, 0f)
            else view.applyInsets(0f, cutoutPx, 0f, 0f)
        }
        view.surfaceChanged(view.holder, 0, physW.toInt(), physH.toInt())
        view.state.currentPage = page
        view.state.phase = HangarPhase.BROWSING
        if (panel != null) {
            view.state.openPanel = panel
            view.state.panelFade = 1f
        }
        if (closing != null) {
            view.state.closingPanel = closing
            view.state.panelFade = 0.5f
        }

        val bitmap = Bitmap.createBitmap(
            m.width.toInt().coerceAtLeast(1), m.height.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        return Pad(view, Canvas(bitmap)).also { it.frame() }
    }

    /** A rotated Pixel 9 Pro, the owner's device, with the 128px cutout it actually has. */
    private fun landscapePad(
        page: Int,
        panel: HangarPanels.Panel? = null,
        closing: HangarPanels.Panel? = null
    ) = pad(2844f, 1280f, page, panel, closing, cutoutPx = 128f)

    private fun unlockEveryPilot() {
        for (i in 0 until PilotDefinitions.getPilotCount()) {
            PilotDefinitions.getPilotByIndex(i)?.let { persistence.unlockPilot(it.id) }
        }
    }

    // =====================================================================================
    // 1. The way in: a shut panel must still offer its button
    // =====================================================================================

    /**
     * The live defect, half one: in landscape the crew room draws no grid, so `pilotCardRects` is
     * empty and page 0 published NOTHING but the nav row — a pad user had no pilot targets and no
     * way to open the panel that holds them. Catches a publisher that only ever publishes the
     * room's own content.
     */
    @Test
    fun `a shut crew panel still publishes its button, which is the only way in`() {
        val p = landscapePad(page = 0)

        assertTrue("the CREW button must be a focus target: ${p.ids}",
            p.ids.contains("panelbtn:CREW"))
        assertTrue("a shut panel has no cards on screen, so it may publish none",
            p.ids.none { it.startsWith("panel:") })
    }

    /** The shop page's two buttons, same rule. */
    @Test
    fun `a shut shop page publishes both of its buttons and no room content`() {
        val p = landscapePad(page = 2)

        assertTrue("SHOP button missing: ${p.ids}", p.ids.contains("panelbtn:SHOP"))
        assertTrue("SLOT button missing: ${p.ids}", p.ids.contains("panelbtn:SLOT"))
        assertTrue("the shop room draws no board in landscape, so nothing may be published for it",
            p.ids.none { it.startsWith("store:") })
    }

    /**
     * A published button that does nothing is not a way in. Catches a target published with the
     * wrong action (or none), and a button rect that the panel layer's own tap dispatch misses.
     */
    @Test
    fun `pressing the button on the pad opens the panel`() {
        val p = landscapePad(page = 0)
        p.focus("panelbtn:CREW")

        assertTrue("the press must be consumed", p.press())
        assertEquals(HangarPanels.Panel.CREW, p.view.state.openPanel)
    }

    /** And presses it again to close — through the fade, not by clearing it. */
    @Test
    fun `pressing the tab again starts the panel closing`() {
        val p = landscapePad(page = 0, panel = HangarPanels.Panel.CREW)
        p.focus("panelbtn:CREW")

        assertTrue(p.press())
        assertNull("the panel must leave openPanel", p.view.state.openPanel)
        assertEquals("...and fade out rather than vanish",
            HangarPanels.Panel.CREW, p.view.state.closingPanel)
    }

    // =====================================================================================
    // 2. The panel's contents, at the rects they were drawn at
    // =====================================================================================

    /**
     * The live defect, half two: the panel's rects are SCREEN space and `publishBarFocus` added
     * `roomOriginX()` to them, which on this device is 1178 — the ring drew a room's width right
     * of the cards. Asserted against the rects the draw actually published, so a target that has
     * been translated by anything at all fails.
     */
    @Test
    fun `an open crew panel publishes one target per drawn card, untranslated`() {
        val p = landscapePad(page = 0, panel = HangarPanels.Panel.CREW)
        val drawn = p.view.renderer.panelCardRects.toList()

        assertEquals(PilotDefinitions.getPilotCount(), drawn.size)
        for ((i, card) in drawn.withIndex()) {
            val r = p.rect("panel:$i")
            assertEquals("card $i left", card.left, r.left, 0.01f)
            assertEquals("card $i top", card.top, r.top, 0.01f)
            assertEquals("card $i right", card.right, r.right, 0.01f)
            assertEquals("card $i bottom", card.bottom, r.bottom, 0.01f)
        }
    }

    /**
     * And the target actually does what a finger does there.
     *
     * Catches the second half of the shipped defect: activation routed through `handleBarTap`,
     * whose `roomX` + `getPilotGridIndex` pair returns null in landscape — a click and nothing
     * else. Three cards across the grid, none of them the already-selected one (a second press on
     * the selected card flips it instead of selecting, which would prove nothing).
     */
    @Test
    fun `pressing a panel card selects that pilot, exactly as a tap on it does`() {
        unlockEveryPilot()
        val p = landscapePad(page = 0, panel = HangarPanels.Panel.CREW)

        for (index in listOf(2, 7, 11)) {
            assertNotEquals("pick a card that is not already selected",
                index, p.view.state.selectedPilotIndex)
            p.focus("panel:$index")
            assertTrue(p.press())
            assertEquals("pressing panel card $index must select pilot $index",
                index, p.view.state.selectedPilotIndex)
        }
    }

    /**
     * The shop's panels hold the STORE's own controls, published by the store's own publisher in
     * screen space — so they keep their `store:` ids, and the pad's hold-to-buy (which reads
     * `store:upgrade:` off the focused id) still works inside the panel.
     *
     * Catches republishing the tiles under a second `panel:N` id: the ring would land on a tile
     * whose press buys nothing, and two overlapping targets would sit on every tile.
     */
    @Test
    fun `an open shop panel keeps its tiles as store targets, at the drawn rects`() {
        val p = landscapePad(page = 2, panel = HangarPanels.Panel.SHOP)
        val drawn = p.view.renderer.upgradeRects.toList()

        assertTrue("the panel must have drawn its board", drawn.isNotEmpty())
        assertTrue("tiles must not be republished as panel cards: ${p.ids}",
            p.ids.none { it.startsWith("panel:") })
        for ((i, tile) in drawn.withIndex()) {
            val r = p.rect("store:upgrade:$i")
            assertEquals("tile $i left", tile.left, r.left, 0.01f)
            assertEquals("tile $i top", tile.top, r.top, 0.01f)
        }
    }

    /** The press half: a pad press on a panel tile starts the same fill a finger starts. */
    @Test
    fun `pressing a panel tile starts its hold-to-buy fill`() {
        persistence.setYen(100_000)
        val p = landscapePad(page = 2, panel = HangarPanels.Panel.SHOP)
        p.view.state.actualYen = 100_000
        // The existence check is load-bearing: `onActivateDown` reads the hold branch off the
        // focused id STRING, so the press below starts a fill whether or not anything published
        // that id — without this the test would pass on a panel that publishes no tiles at all.
        assertTrue("the tile must be reachable before it can be held: ${p.ids}",
            p.ids.contains("store:upgrade:0"))
        p.focus("store:upgrade:0")

        assertTrue(p.press())
        assertEquals(0, p.view.heldUpgradeIndex)
        assertTrue("the fill must be running", p.view.storeHold.isActive)
    }

    /** The SLOT panel's one control is the machine's spin button, and it is reachable. */
    @Test
    fun `an open slot panel publishes the machine's own buttons`() {
        val p = landscapePad(page = 2, panel = HangarPanels.Panel.SLOT)

        assertTrue("the spin button must be a focus target: ${p.ids}",
            p.ids.contains("store:button:0"))
        assertEquals("...at the rect the machine drew it",
            p.view.renderer.spinButtonRect.left, p.rect("store:button:0").left, 0.01f)
    }

    // =====================================================================================
    // 3. Every gate that stops a finger stops the pad
    // =====================================================================================

    /**
     * A panel's scrim covers the room for a finger (`HangarSurfaceView`'s ACTION_DOWN gate)
     * — including on the launchpad, which owns no panel of its own and can
     * still have one fading shut over it for ~0.15s.
     *
     * Catches a publisher that keeps publishing the room's own targets under a scrim: with the
     * ship published, a pad press would buy or LAUNCH a ship through a panel the player is
     * watching close. `PanelFadeTest` pins the touch half of this exact case.
     */
    @Test
    fun `a closing panel over the launchpad leaves the ship unreachable to the pad`() {
        val control = landscapePad(page = 1)
        assertTrue("control: the launchpad must publish its ship when nothing covers it",
            control.ids.contains("ship"))

        val p = landscapePad(page = 1, closing = HangarPanels.Panel.CREW)
        assertFalse("a closing panel's scrim must hide the ship from the pad too: ${p.ids}",
            p.ids.contains("ship"))
    }

    /**
     * The tap path refuses a closing panel's cards and its backdrop, while the BUTTON stays live
     * (pressing it reverses the close — `PanelFadeTest` pins that for touch). The pad gets the
     * same shape: no cards, button still there.
     */
    @Test
    fun `a closing panel publishes its button but none of its cards`() {
        val p = landscapePad(page = 0, closing = HangarPanels.Panel.CREW)

        assertTrue("the panel is still drawn, so its cards exist",
            p.view.renderer.panelCardRects.isNotEmpty())
        assertTrue("...but nothing on its way out is a target: ${p.ids}",
            p.ids.none { it.startsWith("panel:") })
        assertTrue("the button reverses the close, so it stays reachable",
            p.ids.contains("panelbtn:CREW"))
    }

    /**
     * The crystal reveal's flight refuses every touch outright (the ACTION_DOWN gate, and
     * `handlePanelTap`'s own copy of it). A pad must not be able to open, close or press a panel
     * through it.
     *
     * Published-but-disabled rather than omitted, which is this codebase's idiom for a control
     * that is on screen and unavailable (`FocusTarget.enabled`'s own doc) — the buttons really are
     * still drawn during the reveal.
     */
    @Test
    fun `the crystal reveal's flight disables the panel buttons for the pad`() {
        val p = landscapePad(page = 0)
        p.view.state.crystalRevealPhase = HangarState.CrystalRevealPhase.ORB_TRAVEL
        p.frame()

        assertFalse("the button must be published disabled",
            p.view.hangarFocus.byId("panelbtn:CREW")!!.enabled)
        p.focus("panelbtn:CREW")
        assertFalse("a disabled target fires nothing", p.press())
        assertNull("no panel may open during the reveal", p.view.state.openPanel)
    }

    /** And the panel's own contents go with it. */
    @Test
    fun `the crystal reveal's flight publishes no panel contents either`() {
        val p = landscapePad(page = 0, panel = HangarPanels.Panel.CREW)
        p.view.state.crystalRevealPhase = HangarState.CrystalRevealPhase.FLASH
        p.frame()

        assertTrue("nothing inside the panel may be pressed during the reveal: ${p.ids}",
            p.ids.none { it.startsWith("panel:") })
    }

    /**
     * The codex secret is entered with a swipe sequence on the shop page — and a panel's scrim
     * eats swipes, so a finger cannot walk the sequence while one is open. The pad's copy lives in
     * `onDirectionalPress`, where every press that moves the ring also steps the sequence: without
     * a gate, a player simply navigating the SHOP panel's 3x3 board would trip the hatch open.
     *
     * The control half is what makes this a guard rather than decoration: the same eight presses
     * with the panel SHUT must still open the hatch, so landscape keeps its way in.
     */
    @Test
    fun `a panel covering the shop blocks the codex sequence, which is still walkable without one`() {
        val control = landscapePad(page = 2)
        for (d in HangarGestures.CODEX_SEQUENCE) control.view.onDirectionalPress(d)
        assertTrue("control: with no panel up the sequence must still open the hatch",
            control.view.state.hatchOpen)

        val p = landscapePad(page = 2, panel = HangarPanels.Panel.SHOP)
        for (d in HangarGestures.CODEX_SEQUENCE) p.view.onDirectionalPress(d)
        assertFalse("a scrim eats the swipe, so it must eat the press too",
            p.view.state.hatchOpen)
        assertEquals("and leave no half-walked sequence behind",
            0, p.view.state.codexSequenceProgress)
    }

    // =====================================================================================
    // 4. Back
    // =====================================================================================

    /**
     * Back — the system button, and the pad's Cancel (`InputRouter` maps SELECT/Escape there; B is
     * confirm on this pad layout) — closes an open panel instead of leaving the hangar.
     *
     * Catches a Back that falls through to `MainActivity`'s default callback, which finishes the
     * Activity: pressing Back with a panel open would quit the game.
     */
    @Test
    fun `Back closes an open panel rather than leaving the hangar`() {
        val p = landscapePad(page = 0, panel = HangarPanels.Panel.CREW)

        assertTrue("Back must be consumed while a panel is open", p.view.onCancel())
        assertNull(p.view.state.openPanel)
        assertEquals(HangarPanels.Panel.CREW, p.view.state.closingPanel)
    }

    /**
     * And is NOT consumed with no panel open — otherwise Back in the landscape hangar would stop
     * working entirely, which is a worse bug than the one above.
     */
    @Test
    fun `Back with no panel open is left alone`() {
        assertFalse(landscapePad(page = 0).view.onCancel())
        assertFalse("nor is a panel already fading shut something Back can close",
            landscapePad(page = 0, closing = HangarPanels.Panel.CREW).view.onCancel())
    }

    /**
     * The cabinet overlay is modal: while it is up it owns the screen and the hangar behind it is
     * neither drawn nor ticked. Back never reaches here in that case (MainActivity hands it to the
     * shell first) but the pad's own cancel button goes straight to `onCancel`, so the gate has to
     * live here too.
     */
    @Test
    fun `cancel does not reach past the cabinet overlay to close a panel behind it`() {
        val p = landscapePad(page = 0, panel = HangarPanels.Panel.CREW)
        p.view.state.cabinetOpen = true

        assertFalse(p.view.onCancel())
        assertEquals("the panel behind the overlay must be left exactly as it was",
            HangarPanels.Panel.CREW, p.view.state.openPanel)
    }

    // =====================================================================================
    // 5. Where the ring goes
    // =====================================================================================

    /**
     * Opening puts the ring inside the panel; closing hands it back to the button that opened it.
     *
     * The second half rides `FocusRegistry.commit`'s fallback — the focused card id vanishes when
     * the panel starts closing — so it needs a default to fall back TO. Catches a publisher that
     * names no default: the ring would simply go out, leaving a pad player with nothing selected.
     */
    @Test
    fun `opening moves the ring into the panel and closing returns it to the button`() {
        val p = landscapePad(page = 0)
        p.focus("panelbtn:CREW")
        p.press()
        p.frame()
        assertEquals("the ring must land on the panel's first card", "panel:0", p.focused)

        // Closed with Back, deliberately, so the ring is still on a CARD when the close begins —
        // which is what makes the fallback do any work at all. Pressing the tab would mean moving
        // the ring onto the button first, and the assertion below would then hold whether or not
        // anything ever fell back. (Verified: with `setDefault` deleted, this fails; with the tab
        // press it did not.)
        p.focus("panel:5")
        assertTrue(p.view.onCancel())
        p.frame()
        assertEquals("the ring must come back to the button", "panelbtn:CREW", p.focused)
    }

    /**
     * A panel fades shut wherever the player has gone, so the page it is closing over need not be
     * the page it belongs to. The fallback must name a button THAT page actually publishes.
     *
     * Catches naming the closing panel's own button: `panelbtn:CREW` is published nowhere on the
     * shop page, `commit()` would find nothing to fall back to, and the ring would go out.
     */
    @Test
    fun `a panel closing over another page falls back to that page's own button`() {
        val p = landscapePad(page = 2, closing = HangarPanels.Panel.CREW)
        p.focus("panel:3")  // a CREW card, from before the player left the bar page
        p.frame()

        assertEquals("panelbtn:SHOP", p.focused)
    }

    /** The ring does not get yanked back into the panel on every frame it is open. */
    @Test
    fun `the ring stays where the player put it while the panel stays open`() {
        val p = landscapePad(page = 0, panel = HangarPanels.Panel.CREW)
        p.focus("panel:4")
        p.frame()
        p.frame()

        assertEquals("panel:4", p.focused)
    }

    /**
     * The launchpad (page 1) owns no panel button of its own — `HangarPanels.panelsOn(1)` is
     * empty — so a panel fading shut over it (the only way this layer ever runs there at all)
     * used to leave `setDefault(null)`: with the ship also unreachable under the scrim (see "a
     * closing panel over the launchpad leaves the ship unreachable to the pad" above), `commit()`
     * had nothing to fall back to and the ring simply went out for the ~0.15s the fade takes.
     *
     * The fix: fall back one level further, to the current page's own nav label — published every
     * frame by `publishNavTargets` regardless of what the panel layer is doing, so it is always
     * there to land on.
     */
    @Test
    fun `a panel fading shut over the launchpad lands the ring on the nav label, not nowhere`() {
        val p = landscapePad(page = 1, closing = HangarPanels.Panel.CREW)
        assertFalse("control: the ship must actually be unreachable here, or this proves nothing",
            p.ids.contains("ship"))
        p.focus("ship")

        p.frame()

        assertEquals("the ring must land on this page's own nav label rather than go out",
            "nav:1", p.focused)
    }

    // =====================================================================================
    // 6. Portrait cannot change
    // =====================================================================================

    /**
     * Portrait has no panel layer at all, and its focus behaviour may not move for any reason.
     * Run with the cutout as well as without — a portrait claim tested only at zero insets is what
     * caused the September revert.
     *
     * Catches a landscape-only branch that leaks: a room origin dropped from `publishBarFocus`
     * would move every portrait pilot target a room's width, and a panel gate placed too high
     * would stop portrait publishing its room at all.
     */
    @Test
    fun `portrait publishes its in-room pilot grid and no panel anything, cutout and all`() {
        unlockEveryPilot()
        for (cutout in listOf(0f, 128f)) {
            val p = pad(1280f, 2844f, page = 0, cutoutPx = cutout)
            val drawn = p.view.renderer.pilotCardRects.toList()

            assertEquals("cutout=$cutout: the room draws the grid in portrait",
                PilotDefinitions.getPilotCount(), drawn.size)
            assertTrue("cutout=$cutout: no panel exists in portrait: ${p.ids}",
                p.ids.none { it.startsWith("panel") })
            for ((i, card) in drawn.withIndex()) {
                val r = p.rect("pilot:$i")
                assertEquals("cutout=$cutout: pilot $i left", card.left, r.left, 0.01f)
                assertEquals("cutout=$cutout: pilot $i top", card.top, r.top, 0.01f)
            }

            assertNotEquals(3, p.view.state.selectedPilotIndex)
            p.focus("pilot:3")
            assertTrue(p.press())
            assertEquals("cutout=$cutout: pressing a portrait card still selects its pilot",
                3, p.view.state.selectedPilotIndex)
        }
    }

    /**
     * The portrait test above cannot see a lost room origin: on a phone the room IS the screen, so
     * `roomOriginX()` is zero and the conversion is the identity. This is the case that separates
     * them — a portrait TABLET, above the sw600 gate, where the room is the content column and
     * genuinely narrower than the screen.
     *
     * The expected origin is hand-derived, `(screenWidth - roomWidth) / 2`, not
     * `RoomAnchor.pageOriginX` — which is half of what is under test. Catches `publishBarFocus`
     * losing its origin term, which is exactly the shape of the change this task makes next door
     * to it, and which nothing else in the tree would notice: `GoldenRuleTest` pins the TAP path
     * here, not the focus path.
     */
    @Test
    @Config(qualifiers = "sw800dp")
    fun `portrait pilot targets carry the room origin, on a screen whose room is not the screen`() {
        val p = pad(1600f, 2560f, page = 0)
        val screenWidth = p.view.layout.width
        val roomWidth = HangarMetrics.roomWidth(
            screenWidth, p.view.layout.content.width,
            ApplicationProvider.getApplicationContext<Context>()
                .resources.configuration.smallestScreenWidthDp
        )
        assertTrue("fixture must put the room inside the screen: $roomWidth vs $screenWidth",
            roomWidth < screenWidth - 1f)
        val originX = (screenWidth - roomWidth) / 2f

        val drawn = p.view.renderer.pilotCardRects.toList()
        assertEquals(PilotDefinitions.getPilotCount(), drawn.size)
        for ((i, card) in drawn.withIndex()) {
            assertEquals("pilot $i must be published at its drawn X plus the room origin",
                card.left + originX, p.rect("pilot:$i").left, 0.01f)
        }
    }

    /** Portrait's shop page keeps its in-room board, published room-local -> screen. */
    @Test
    fun `portrait publishes its in-room store board, cutout and all`() {
        for (cutout in listOf(0f, 128f)) {
            val p = pad(1280f, 2844f, page = 2, cutoutPx = cutout)
            assertTrue("cutout=$cutout: the room draws its board in portrait",
                p.view.renderer.upgradeRects.isNotEmpty())
            assertTrue("cutout=$cutout: store targets must survive: ${p.ids}",
                p.ids.contains("store:upgrade:0"))
            assertTrue("cutout=$cutout: no panel buttons in portrait",
                p.ids.none { it.startsWith("panelbtn:") })
        }
    }

    /** And portrait's Back is untouched: nothing there to close, nothing consumed. */
    @Test
    fun `portrait never consumes Back`() {
        val p = pad(1280f, 2844f, page = 0)
        // Even with the field set (it cannot be, in portrait — applyScreenDimensions clears it —
        // but the gate must be the orientation, not the field).
        p.view.state.openPanel = HangarPanels.Panel.CREW

        assertFalse(p.view.onCancel())
    }

    // =====================================================================================
    // 7. The nav row keeps working, panel or no panel
    // =====================================================================================

    /**
     * Leaving the hangar must not require closing a panel first — the nav row is drawn ABOVE the
     * scrim and `handleTap` resolves it before the panel layer, so the pad must keep it too.
     * Catches a "publish nothing under a scrim" rule applied one level too high.
     */
    @Test
    fun `the nav row stays reachable with a panel open`() {
        val p = landscapePad(page = 0, panel = HangarPanels.Panel.CREW)

        assertTrue("nav must survive an open panel: ${p.ids}", p.ids.contains("nav:1"))
        p.focus("nav:1")
        assertTrue(p.press())
        assertEquals(1, p.view.state.currentPage)
    }

    /** A direction still moves the ring while a panel is open — the panel is navigable at all. */
    @Test
    fun `a direction moves the ring between the panel's own cards`() {
        val p = landscapePad(page = 0, panel = HangarPanels.Panel.CREW)
        p.focus("panel:0")

        assertTrue(p.view.onDirectionalPress(Direction.RIGHT))
        assertEquals("right from the first card must land on the second", "panel:1", p.focused)
    }

    /**
     * Every button test above focuses `panelbtn:CREW` directly and presses it — none of them
     * arrive there by direction, so nothing has ever asserted that a pad actually CAN reach the
     * button from a cold start (no prior focus at all, the state a player is in the moment they
     * pick up the controller on the bar page).
     *
     * With no focused id, `FocusNavigator.next` takes its no-current-focus branch and lands on
     * whatever is first in reading order (top, then left) among everything published — which is
     * why this holds for all four directions alike rather than by each one's own geometry: with
     * nothing focused yet, a shut bar page publishes only the nav row (near the bottom of the
     * screen) and the CREW button (vertically centred) — so the button is topmost regardless of
     * which direction is pressed first.
     */
    @Test
    fun `from a cold start, any direction reaches the shut CREW button`() {
        for (d in Direction.values()) {
            val p = landscapePad(page = 0)
            assertNull("control: must truly be cold, nothing focused yet", p.focused)

            assertTrue(p.view.onDirectionalPress(d))
            p.frame()

            assertEquals("$d from a cold start must reach the CREW button: ${p.ids}",
                "panelbtn:CREW", p.focused)
        }
    }

    /**
     * The report's other unasserted walkthrough claim: LEFT off the panel's leftmost card reaches
     * the tab. `panelBox` anchors CREW to the right edge and `tabRect` slides the button clear of
     * the box's LEFT edge — so the tab sits to the left of every card in the grid, and pressing
     * LEFT from the leftmost column should reach it before any other target does.
     *
     * `panel:0` as "the leftmost card" rests on `a direction moves the ring between the panel's
     * own cards` above, which already established RIGHT from `panel:0` lands on `panel:1` — i.e.
     * `panel:0` has a card to its right in the same row, which is what "leftmost" means here.
     */
    @Test
    fun `LEFT off the leftmost card reaches the tab`() {
        val p = landscapePad(page = 0, panel = HangarPanels.Panel.CREW)
        p.focus("panel:0")

        assertTrue(p.view.onDirectionalPress(Direction.LEFT))
        assertEquals("left from the leftmost card must reach the CREW tab", "panelbtn:CREW", p.focused)
    }
}
