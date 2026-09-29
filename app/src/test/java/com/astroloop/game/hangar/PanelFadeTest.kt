package com.astroloop.game.hangar

import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.LayoutRect
import com.astroloop.game.core.SoundManager
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * The landscape panel layer's open/close fade — owner-approved, for the
 * house rule against instant disappearance: a panel used to appear and vanish between frames.
 *
 * `HangarState.togglePanel`/`advancePanelFade` are a small pure state machine (see their doc
 * comments), so the bulk of this is driven directly against `HangarState` with hand-computed
 * expected values — no Canvas, no Robolectric surface needed for those. The two integration
 * tests at the bottom drive `HangarSurfaceView` for real (real `onTouchEvent`/`surfaceChanged`
 * dispatch, seeded rects standing in for a live Canvas pass — the same seam
 * `TouchGestureAbandonTest`/`StoreHoldSuppressesFlipTest` already use) because the input gate and
 * the rotation clear both live in `HangarSurfaceView`, and a re-derivation against `HangarState`
 * alone cannot catch a call site that stops calling into it.
 *
 * `fadeDuration` reads the production duration rather than hardcoding 0.15f: these tests exist to pin
 * the RATE FORMULA and the state transitions (rising/falling, clamped, reversible, latch-free),
 * not the specific duration the owner picked, so a later tweak to `PANEL_FADE_SECONDS` should not
 * need every expected value in this file recomputed by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PanelFadeTest {

    /**
     * A stand-in for the box a real frame would have published around the seeded card.
     *
     * It has to exist, and it has to CONTAIN the card: since the 2026-09-20 race fix,
     * `handlePanelTap` treats a visible panel with no published box as one that has not been
     * drawn yet and refuses the tap rather than closing. Seeding cards without a box would
     * therefore describe a state no frame produces. Generous enough to hold the 100..200 card and
     * still leave the 900..1000 button and the nav band outside it.
     */
    private val PANEL_BOX = LayoutRect(50f, 50f, 400f, 400f)

    private lateinit var persistence: PersistenceManager
    private val eps = 0.0005f
    private val fadeDuration = HangarSurfaceView.PANEL_FADE_SECONDS

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
    }

    // =====================================================================================
    // The pure state machine
    // =====================================================================================

    @Test
    fun `opening rises toward fully open and clamps there without overshooting`() {
        val state = HangarState(persistence)

        state.togglePanel(HangarPanels.Panel.CREW)
        assertEquals(HangarPanels.Panel.CREW, state.openPanel)
        assertNull(state.closingPanel)
        assertEquals(0f, state.panelFade, eps)

        state.advancePanelFade(fadeDuration / 2f)
        assertEquals(0.5f, state.panelFade, eps)
        assertEquals(HangarPanels.Panel.CREW, state.openPanel)

        state.advancePanelFade(fadeDuration / 2f)
        assertEquals(1f, state.panelFade, eps)

        // A tick well past full must clamp, not overshoot — the coerceAtMost is what this
        // catches; a bare `panelFade += rate` would read 6f here instead of 1f.
        state.advancePanelFade(fadeDuration * 5f)
        assertEquals(1f, state.panelFade, eps)
        assertEquals("reaching fully open must not itself close the panel",
            HangarPanels.Panel.CREW, state.openPanel)
    }

    @Test
    fun `closing keeps the panel drawable while it fades, and clears only once it reaches zero`() {
        val state = HangarState(persistence)
        state.togglePanel(HangarPanels.Panel.CREW)
        state.advancePanelFade(fadeDuration) // fully open

        state.togglePanel(HangarPanels.Panel.CREW) // press again: begin closing
        assertNull("openPanel must move aside the instant a close begins", state.openPanel)
        assertEquals("the panel's identity must survive into closingPanel, not disappear",
            HangarPanels.Panel.CREW, state.closingPanel)
        assertEquals("the toggle itself must not touch the fade", 1f, state.panelFade, eps)

        state.advancePanelFade(fadeDuration / 2f)
        assertEquals(0.5f, state.panelFade, eps)
        assertEquals("still mid-fade: the panel must stay drawable",
            HangarPanels.Panel.CREW, state.closingPanel)

        state.advancePanelFade(fadeDuration / 2f)
        assertEquals(0f, state.panelFade, eps)
        assertNull("closingPanel clears exactly when the fade reaches zero, not before",
            state.closingPanel)

        // Nothing left to advance: further ticks must not go negative or resurrect a panel.
        state.advancePanelFade(fadeDuration * 5f)
        assertEquals(0f, state.panelFade, eps)
        assertNull(state.openPanel)
        assertNull(state.closingPanel)
    }

    @Test
    fun `reopening the same panel mid-close resumes the fade instead of restarting or overshooting`() {
        val state = HangarState(persistence)
        state.togglePanel(HangarPanels.Panel.CREW)
        state.advancePanelFade(fadeDuration) // fully open, fade = 1

        state.togglePanel(HangarPanels.Panel.CREW) // begin closing
        state.advancePanelFade(fadeDuration * 0.25f)
        assertEquals(0.75f, state.panelFade, eps)

        state.togglePanel(HangarPanels.Panel.CREW) // reverse before it finishes
        assertEquals("the reversal must hand the identity straight back",
            HangarPanels.Panel.CREW, state.openPanel)
        assertNull(state.closingPanel)
        assertEquals("resuming must not restart from zero or jump to one",
            0.75f, state.panelFade, eps)

        // From here it must climb back to fully open in exactly the remaining quarter-fade —
        // if the reversal had instead reset panelFade to 0f, this same tick would only reach
        // 0.25f, not 1f.
        state.advancePanelFade(fadeDuration * 0.25f)
        assertEquals(1f, state.panelFade, eps)

        // And still must not overshoot from here either.
        state.advancePanelFade(fadeDuration * 5f)
        assertEquals(1f, state.panelFade, eps)
    }

    @Test
    fun `toggling rapidly with no time between presses never drifts the fade or latches both fields`() {
        val state = HangarState(persistence)
        state.togglePanel(HangarPanels.Panel.CREW)
        state.advancePanelFade(fadeDuration * 0.4f)
        assertEquals(0.4f, state.panelFade, eps)

        // Three close/reopen pairs, no ticking between any of them.
        repeat(3) {
            state.togglePanel(HangarPanels.Panel.CREW) // close
            assertNull(state.openPanel)
            assertEquals(HangarPanels.Panel.CREW, state.closingPanel)
            assertEquals("a press with no elapsed time must not move the fade",
                0.4f, state.panelFade, eps)

            state.togglePanel(HangarPanels.Panel.CREW) // reopen
            assertEquals(HangarPanels.Panel.CREW, state.openPanel)
            assertNull("the pair must not leave both fields set",
                state.closingPanel)
            assertEquals(0.4f, state.panelFade, eps)
        }
    }

    @Test
    fun `switching to a panel on a different page abandons whatever was closing, and resets the fade`() {
        // Was documented as an open quirk (the fade carried the old value forward, so SHOP
        // opened already partway visible) — settled by one code review, THEN
        // corrected by the next: the rule is not "any identity change other than
        // reversing the same panel" (that reset a live SHOP<->SLOT swap too, popping the shared
        // scrim — see the test below) but "a change that also leaves the old panel's PAGE".
        // CREW (page 0) -> SHOP (page 2) crosses pages, so it still resets.
        val state = HangarState(persistence)
        state.togglePanel(HangarPanels.Panel.CREW)
        state.advancePanelFade(fadeDuration) // fully open
        state.togglePanel(HangarPanels.Panel.CREW) // begin closing CREW
        state.advancePanelFade(fadeDuration * 0.5f)
        assertEquals(0.5f, state.panelFade, eps)

        state.togglePanel(HangarPanels.Panel.SHOP) // a different button, a different page

        assertEquals(HangarPanels.Panel.SHOP, state.openPanel)
        assertNull("CREW's close is abandoned outright, not finished or resumed",
            state.closingPanel)
        assertEquals("SHOP opens from scratch rather than inheriting CREW's half-finished fade",
            0f, state.panelFade, eps)
    }

    /**
     * A code review: an earlier instruction ("switching panels mid-close
     * resets the fade") was wrong, and this is the case it broke. SHOP and SLOT both live on
     * page 2 — pressing SLOT while SHOP is open (or mid-close) keeps the SAME box and the SAME
     * shared scrim on screen; only the CONTENT changes. Resetting the fade there made a
     * partially-faded box and its scrim vanish in one frame the instant the second button was
     * pressed, the exact instant disappearance the fade exists to prevent. RED against the
     * fix2-era rule (which reset unconditionally in the third `when` branch of
     * `HangarState.togglePanel`): this asserts the fade carries the 0.5 forward, which that rule
     * would have zeroed.
     */
    @Test
    fun `switching to a different panel on the SAME page carries the fade forward, not resets it`() {
        val state = HangarState(persistence)
        state.togglePanel(HangarPanels.Panel.SHOP)
        state.advancePanelFade(fadeDuration) // fully open
        state.togglePanel(HangarPanels.Panel.SHOP) // begin closing SHOP
        state.advancePanelFade(fadeDuration * 0.5f)
        assertEquals(0.5f, state.panelFade, eps)

        state.togglePanel(HangarPanels.Panel.SLOT) // SLOT's own button, still page 2

        assertEquals(HangarPanels.Panel.SLOT, state.openPanel)
        assertNull("SHOP's close is abandoned outright, not finished or resumed",
            state.closingPanel)
        assertEquals("the shared box/scrim must not pop — SLOT inherits SHOP's half-finished fade",
            0.5f, state.panelFade, eps)
    }

    // =====================================================================================
    // Orientation: HangarSurfaceView.applyScreenDimensions, driven via the real surfaceChanged
    // =====================================================================================

    private fun newView(): HangarSurfaceView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        persistence.resetAllProgress()
        return HangarSurfaceView(context) { _, _ -> }
    }

    @Test
    fun `an orientation change leaves no panel and no residual fade while opening`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2844, 1280) // landscape
        view.state.openPanel = HangarPanels.Panel.CREW
        view.state.panelFade = 0.6f

        view.surfaceChanged(view.holder, 0, 1280, 2844) // rotate to portrait

        assertNull(view.state.openPanel)
        assertNull(view.state.closingPanel)
        assertEquals(0f, view.state.panelFade, 0f)
    }

    @Test
    fun `an orientation change leaves no panel and no residual fade while closing`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2844, 1280) // landscape
        view.state.closingPanel = HangarPanels.Panel.CREW
        view.state.panelFade = 0.3f

        view.surfaceChanged(view.holder, 0, 1280, 2844) // rotate to portrait

        assertNull(view.state.openPanel)
        assertNull(view.state.closingPanel)
        assertEquals(0f, view.state.panelFade, 0f)
    }

    // =====================================================================================
    // Input gating: HangarSurfaceView.handlePanelTap, driven via real onTouchEvent dispatch
    // =====================================================================================

    private fun dispatch(view: HangarSurfaceView, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /**
     * A tap on a seeded card rect, exactly as `handlePanelTap` reads
     * `renderer.panelCardRects`/`panelButtonRects` — the same seam `StoreHoldSuppressesFlipTest`
     * uses in place of a live Canvas pass (Robolectric's `lockCanvas()` returns null, so
     * `HangarRenderer.render` never actually runs). The button rect is placed well away from the
     * card so the two can't be confused by accident.
     *
     * Physical size 2142x960 — exactly `GameConfig.DESIGN_HEIGHT`x`DESIGN_WIDTH` transposed for
     * landscape — is chosen so `renderScale` lands on exactly 1f (`min(2142/2142, 960/960)`) and
     * `onTouchEvent`'s `event.x / renderScale` is the identity, so the seeded rects (in design
     * space, same as the real renderer publishes) line up with the raw dispatched coordinates
     * without a second scale factor to get wrong. `StoreHoldSuppressesFlipTest`'s tests get this
     * for free by never calling `surfaceChanged` at all; this one has to call it, to get
     * `landscape` true, so it has to earn the identity deliberately instead.
     */
    @Test
    fun `taps are refused once the fade-out has begun, but the button still reverses it`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2142, 960) // handlePanelTap requires landscape
        view.state.currentPage = 0 // the bar page owns CREW
        view.state.phase = HangarPhase.BROWSING

        val cardRect = LayoutRect(100f, 100f, 200f, 200f)
        // Well clear of both the card and the nav-label tap band (screenHeight * 0.95 ± ~30..15).
        view.renderer.publishPanelLayer(
            HangarRenderer.PanelLayer(
                box = PANEL_BOX,
                cards = listOf(cardRect),
                buttons = mapOf(HangarPanels.Panel.CREW to LayoutRect(900f, 700f, 1000f, 800f))
            )
        )

        // Fully open: a tap on the card acts — pilot 0 is selected by default, so this is a
        // flip (togglePilotFlip), observable on pilotFlipIndex.
        view.state.openPanel = HangarPanels.Panel.CREW
        view.state.pilotFlipIndex = -1
        dispatch(view, MotionEvent.ACTION_DOWN, cardRect.centerX, cardRect.centerY)
        dispatch(view, MotionEvent.ACTION_UP, cardRect.centerX, cardRect.centerY)
        assertEquals("a tap on an OPEN panel's card must act", 0, view.state.pilotFlipIndex)

        // Now closing: the identical tap on the identical rect must be refused.
        view.state.pilotFlipIndex = -1
        view.state.openPanel = null
        view.state.closingPanel = HangarPanels.Panel.CREW
        dispatch(view, MotionEvent.ACTION_DOWN, cardRect.centerX, cardRect.centerY)
        dispatch(view, MotionEvent.ACTION_UP, cardRect.centerX, cardRect.centerY)
        assertEquals("a tap on a CLOSING panel's card must be refused",
            -1, view.state.pilotFlipIndex)
        assertEquals("the tap must not itself disturb the close in progress",
            HangarPanels.Panel.CREW, view.state.closingPanel)

        // But the button itself — still published as the tab, see HangarRenderer.drawPanelLayer
        // — must still reverse the close rather than being swallowed by the same gate.
        val buttonRect = view.renderer.panelButtonRects.getValue(HangarPanels.Panel.CREW)
        dispatch(view, MotionEvent.ACTION_DOWN, buttonRect.centerX, buttonRect.centerY)
        dispatch(view, MotionEvent.ACTION_UP, buttonRect.centerX, buttonRect.centerY)
        assertEquals("pressing the button again must reopen it",
            HangarPanels.Panel.CREW, view.state.openPanel)
        assertNull("...and must not leave it also marked as closing",
            view.state.closingPanel)
    }

    // =====================================================================================
    // A review finding: the lit nav row must be reachable while a panel is open or mid-close
    // =====================================================================================

    /**
     * `HangarSurfaceView.handleTap` used to resolve `handlePanelTap` BEFORE the nav row, so any
     * tap that missed a button and missed a card — which a nav label tap does, since the panel's
     * box is deliberately kept clear of the nav band (`CrewPanelTest`) — fell into the
     * backdrop-closes catch-all and was swallowed instead of reaching `navigateToPage`. Worse,
     * with the panel already mid-close the SAME tap hit `handlePanelTap`'s
     * `state.closingPanel != null -> return true` refusal gate and was swallowed a second time.
     * Both are fixed by resolving the nav row first; this pins that against real touch dispatch,
     * not just the geometry `CrewPanelTest` already pins.
     */
    @Test
    fun `a nav label is reachable while a panel is open, and while one is closing`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2142, 960) // landscape, renderScale == 1f
        view.state.phase = HangarPhase.BROWSING
        view.state.currentPage = 0 // the bar page owns CREW

        // Seed the panel's rects exactly as a real render() would have published them — the same
        // seam PanelFadeTest's other integration test above uses, since Robolectric's
        // lockCanvas() returns null and HangarRenderer.render never actually runs here. Without
        // this, handlePanelTap's OWN "nothing drawn means nothing to hit" early return would make
        // it a no-op regardless of tap order, and the test would pass by accident rather than by
        // the fix.
        val cardRect = LayoutRect(100f, 100f, 200f, 200f)
        view.renderer.publishPanelLayer(
            HangarRenderer.PanelLayer(
                box = PANEL_BOX,
                cards = listOf(cardRect),
                buttons = mapOf(HangarPanels.Panel.CREW to LayoutRect(900f, 700f, 1000f, 800f))
            )
        )

        // Same tap-zone arithmetic as HangarSurfaceView.handleTap: labelY = screenHeight*0.95f,
        // spacing = content.width*0.25f, label i's centre = screenWidth/2 + (i-1)*spacing.
        // i=2 is SHOP/LAUNCH-adjacent — any label other than the current page (i=0) will do; SHOP
        // doubles as proof the tap reaches all the way to a page CREW does not even share a panel
        // set with. Well clear of both the seeded card and button rects above.
        val labelY = view.layout.height * 0.95f
        val spacing = view.layout.content.width * 0.25f
        val shopLabelX = view.layout.width / 2f + spacing

        // Fully open: the lit SHOP label must still navigate, not be swallowed by the backdrop.
        view.state.openPanel = HangarPanels.Panel.CREW
        view.state.closingPanel = null
        dispatch(view, MotionEvent.ACTION_DOWN, shopLabelX, labelY)
        dispatch(view, MotionEvent.ACTION_UP, shopLabelX, labelY)
        assertEquals("a nav tap must reach the page while a panel is open",
            2, view.state.currentPage)

        // Mid-close: the same tap must still work, not be swallowed by handlePanelTap's
        // closingPanel refusal gate.
        view.state.currentPage = 0
        view.state.openPanel = null
        view.state.closingPanel = HangarPanels.Panel.CREW
        dispatch(view, MotionEvent.ACTION_DOWN, shopLabelX, labelY)
        dispatch(view, MotionEvent.ACTION_UP, shopLabelX, labelY)
        assertEquals("a nav tap must reach the page while a panel is closing",
            2, view.state.currentPage)
    }

    // =====================================================================================
    // A review finding: panel state must not outlive a page change, a launch or a run
    // =====================================================================================

    @Test
    fun `a page change starts closing the panel it leaves, rather than clearing it instantly`() {
        val state = HangarState(persistence)
        state.togglePanel(HangarPanels.Panel.CREW)
        state.advancePanelFade(fadeDuration) // fully open, fade = 1

        state.setPageTarget(2) // CREW belongs to page 0, not page 2

        assertNull("the open identity must not simply vanish", state.openPanel)
        assertEquals("it starts closing instead of clearing outright",
            HangarPanels.Panel.CREW, state.closingPanel)
        assertEquals("the fade is preserved, not reset, so it decays smoothly from here rather " +
            "than cutting instantly", 1f, state.panelFade, eps)
    }

    /**
     * DOCUMENTATION, not a guard (these two cannot fail). `setPageTarget`'s
     * only panel logic is `openPanel?.let { if (it !in panelsOn(page)) togglePanel(it) }` — on
     * the panel's own page `it !in panelsOn(page)` is false, so NOTHING runs. This assertion
     * would pass identically even with that whole `openPanel?.let {}` line deleted, since nothing
     * here would then touch openPanel/closingPanel/panelFade either. It records the intended
     * no-op rather than proving the ownership check fired — the check firing is what `a page
     * change starts closing the panel it leaves...` above actually exercises, on a page the
     * panel does NOT belong to.
     */
    @Test
    fun `setPageTarget on a panel's own page is a documented no-op`() {
        val state = HangarState(persistence)
        state.togglePanel(HangarPanels.Panel.CREW)
        state.advancePanelFade(fadeDuration * 0.5f)

        state.setPageTarget(0) // CREW's own page — nothing should change, including on re-entry

        assertEquals(HangarPanels.Panel.CREW, state.openPanel)
        assertNull(state.closingPanel)
        assertEquals(0.5f, state.panelFade, eps)
    }

    /**
     * DOCUMENTATION, not a guard (these two cannot fail). `setPageTarget`
     * reads `openPanel` only — a panel already in [HangarState.closingPanel] (this scenario) is
     * invisible to it, so this would hold even if `setPageTarget` were reduced to just
     * `currentPage = page`. The "keeps closing" behaviour actually comes from
     * [HangarState.advancePanelFade] running every frame regardless of page — pinned above by
     * the pure fade-machine tests — this only records that `setPageTarget` does not additionally
     * interfere with it.
     */
    @Test
    fun `setPageTarget does not see a closing panel at all`() {
        val state = HangarState(persistence)
        state.togglePanel(HangarPanels.Panel.CREW)
        state.advancePanelFade(fadeDuration) // fully open
        state.togglePanel(HangarPanels.Panel.CREW) // begin closing
        state.advancePanelFade(fadeDuration * 0.25f)

        state.setPageTarget(2) // wander off while it fades

        assertNull(state.openPanel)
        assertEquals(HangarPanels.Panel.CREW, state.closingPanel)
        assertEquals(0.75f, state.panelFade, eps)
    }

    @Test
    fun `resetForReturn clears an open panel outright, since a run has intervened`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2142, 960)
        view.state.openPanel = HangarPanels.Panel.CREW
        view.state.panelFade = 1f

        view.resetForReturn()

        assertNull(view.state.openPanel)
        assertNull(view.state.closingPanel)
        assertEquals(0f, view.state.panelFade, 0f)
    }

    @Test
    fun `resetForReturn clears a closing panel outright too`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2142, 960)
        view.state.closingPanel = HangarPanels.Panel.CREW
        view.state.panelFade = 0.4f

        view.resetForReturn()

        assertNull(view.state.openPanel)
        assertNull(view.state.closingPanel)
        assertEquals(0f, view.state.panelFade, 0f)
    }

    // =====================================================================================
    // A code review: ACTION_DOWN must consult the panel layer too — a hold or a
    // press started on the room BENEATH an open (or still-closing) SHOP/SLOT box used to buy a
    // store upgrade, or arm a 100¥ spin, that the player could not even see under the box.
    // When this was written `drawPanelContents` drew nothing for SHOP/SLOT, so the box was
    // blank — the reachability is the bug, not what fills the box.
    // =====================================================================================

    /**
     * An earlier change filled the box, which changed what this test can mean — and the test with it.
     *
     * `upgradeRects` is now "the tiles as DRAWN". In landscape the room draws no board at all
     * (`StorePageRenderer.draw` skips it and retracts its rects — `ShopPanelTest` pins that), so a
     * rect published there while the SHOP panel is up IS a panel tile, and a press on one is the
     * panel's own hold-to-buy. Buying from the board is the point of the panel; refusing that press
     * would leave landscape able to read the board and never buy from it.
     *
     * A code review: this used to also assert a press at (900, 700) started
     * nothing, with `upgradeRects` left empty — vacuous, since an empty list matches no point in
     * either space regardless of whether any room-covering gate exists at all. Dropped rather than
     * reworked: `ShopPanelTest`'s `a press on the blank SHOP box does not arm the spin button
     * beneath it` already covers the same invariant non-vacuously, against a REAL seeded rect.
     */
    @Test
    fun `a press on the panel's own tile starts its fill`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2142, 960) // landscape, renderScale == 1f
        view.state.phase = HangarPhase.BROWSING
        view.state.currentPage = 2 // the shop page owns SHOP/SLOT
        view.state.openPanel = HangarPanels.Panel.SHOP
        persistence.setYen(100_000)
        view.state.actualYen = 100_000

        // Seeded exactly as StoreHoldSuppressesFlipTest/TouchGestureAbandonTest seed
        // upgradeRects in place of a live Canvas pass — Robolectric's lockCanvas() returns null.
        val tileRect = RectF(50f, 50f, 150f, 150f)
        view.renderer.upgradeRects.add(tileRect)
        dispatch(view, MotionEvent.ACTION_DOWN, tileRect.centerX(), tileRect.centerY())

        assertEquals("a press on the panel's own tile must start its fill", 0, view.heldUpgradeIndex)
        assertTrue(view.storeHold.isActive)
    }

    @Test
    fun `a press on the blank SHOP box does not arm the spin button beneath it`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2142, 960)
        view.state.phase = HangarPhase.BROWSING
        view.state.currentPage = 2
        view.state.openPanel = HangarPanels.Panel.SHOP
        view.renderer.storePageRenderer.spinButtonRect = RectF(50f, 50f, 150f, 150f)

        dispatch(view, MotionEvent.ACTION_DOWN, 100f, 100f)

        assertFalse("a press on the blank SHOP box must not arm a 100¥ spin beneath it",
            view.spinButtonHeld)
    }

    @Test
    fun `a hold on the blank SHOP box is refused while it is still fading closed too`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2142, 960)
        view.state.phase = HangarPhase.BROWSING
        view.state.currentPage = 2
        view.state.openPanel = null
        view.state.closingPanel = HangarPanels.Panel.SHOP // mid fade-out, still on screen
        view.state.panelFade = 0.5f
        persistence.setYen(100_000)
        view.state.actualYen = 100_000

        val tileRect = RectF(50f, 50f, 150f, 150f)
        view.renderer.upgradeRects.add(tileRect)

        dispatch(view, MotionEvent.ACTION_DOWN, tileRect.centerX(), tileRect.centerY())

        assertEquals("a box still fading out is still covering the room — a hold started under " +
            "it must be refused exactly like one under a fully open box", -1, view.heldUpgradeIndex)
    }

    // =====================================================================================
    // A code review: a closing panel drawn over a page that owns no panel of its
    // own (the launchpad, panelsOn(1) == emptyList()) used to leave the room beneath it fully
    // interactive, because the input gate asked "does this page publish buttons?" instead of
    // "what is HangarRenderer.drawPanelLayer actually drawing?" — reachable by opening CREW on
    // the bar page, tapping LAUNCH, then tapping (or dragging) the ship again within the ~150ms
    // fade.
    // =====================================================================================

    @Test
    fun `a closing CREW panel blocks a shipyard tap from reaching the ship carousel beneath it`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2142, 960)
        view.state.phase = HangarPhase.BROWSING
        view.state.currentPage = 1 // the launchpad — panelsOn(1) is empty, it owns no panel
        view.state.selectedShipIndex = 2

        val shipY = view.state.shipRestingY
        val centerX = 1071f // screenWidth/2 at this identity-scale physical size — see class doc
        val leftPeekX = centerX - HangarSurfaceView.SHIP_HIT_SIZE - 50f

        // Control, no panel in play: the tap must reach the carousel, or the coordinates below
        // prove nothing.
        dispatch(view, MotionEvent.ACTION_DOWN, leftPeekX, shipY)
        dispatch(view, MotionEvent.ACTION_UP, leftPeekX, shipY)
        assertEquals("control: an ordinary tap must swap the peek ship",
            1, view.state.selectedShipIndex)

        // CREW opened on the bar page, then the player navigated here — panelsOn(1) is empty, so
        // the (nonexistent) button loop can't consume this, but the scrim is still on screen.
        view.state.selectedShipIndex = 2
        view.state.openPanel = null
        view.state.closingPanel = HangarPanels.Panel.CREW
        view.state.panelFade = 0.5f
        dispatch(view, MotionEvent.ACTION_DOWN, leftPeekX, shipY)
        dispatch(view, MotionEvent.ACTION_UP, leftPeekX, shipY)
        assertEquals("a closing panel's scrim must block the ship carousel beneath it, even on " +
            "a page that owns no panel of its own", 2, view.state.selectedShipIndex)
    }

    @Test
    fun `a closing CREW panel over the launchpad does not arm a ship drag either`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2142, 960)
        view.state.phase = HangarPhase.BROWSING
        view.state.currentPage = 1
        view.state.openPanel = null
        view.state.closingPanel = HangarPanels.Panel.CREW
        view.state.panelFade = 0.5f

        val y = view.state.shipRestingY // below the walkway — the ship-drag zone
        dispatch(view, MotionEvent.ACTION_DOWN, 1071f, y)
        dispatch(view, MotionEvent.ACTION_MOVE, 1071f, y + 60f) // vertical, well past touch slop

        assertFalse("a drag started under a closing panel's scrim must not grab the ship " +
            "underneath it", view.state.isDraggingShip)
    }

    // =====================================================================================
    // The swipe release's own page write — the gesture, not setPageTarget
    // =====================================================================================

    /**
     * A horizontal drag off a panel's page must FADE the panel out — never blink it off and back
     * on first. Spec decision makes this drag the primary non-modal way to leave a page with a
     * panel open, and the house rule against instant disappearance applies to it like everything
     * else.
     *
     * Driven as a real gesture rather than a `setPageTarget` call, because the defect lived in
     * the swipe release's own page write and nowhere else: every other panel-transition test in
     * this file calls `setPageTarget` directly, which is precisely the path the bug routed
     * around. `navigateToPage` had the same window removed (see its own doc); this one was wider,
     * because the sound block sat inside it.
     *
     * **Why the probe, and not an end-state assertion.** The four assertions at the bottom pass
     * against the broken code too — measured, not assumed: the old order (`currentPage++`, sounds,
     * `setPageTarget`) lands on the identical end state, because `setPageTarget` still started the
     * close, just later. The defect was only ever visible to a reader INSIDE the gesture: between
     * the bare `currentPage++` and the later `setPageTarget` the state is self-inconsistent — the
     * open panel belongs to the page just left, so [HangarState.visiblePanel] answers null,
     * `drawPanelLayer` returns early, and a render frame landing there cuts the scrim outright.
     *
     * So the test reads the state from inside that window, and does it deterministically rather
     * than racing a second thread for it (the first version of this test sampled from a concurrent
     * reader and flaked ~9% of runs). [ShadowSoundManagerProbe] is the seam: the page-change sound
     * block is the one place the release path calls out to code a test can stand in for, and in
     * the broken ordering it sat squarely INSIDE the window — that is exactly why the window was
     * the wider of the two on a device, `SoundManager.playAmbient` building a `MediaPlayer`
     * synchronously. Sampling `visiblePanel()` there is single-threaded, has no timing component,
     * and observes the precise instant the old code was wrong at: RED reports
     * `[sfx_ui_swipe -> null, bgm_normal_hangar -> null]` against the pre-fix ordering, GREEN
     * reports CREW at both.
     *
     * What it therefore does NOT cover: the window is observed at the sound calls, not
     * continuously. A future regression that re-opened a gap somewhere the release path makes no
     * outward call — a direct `currentPage` write placed AFTER the sound block but before
     * `setPageTarget`, say — would slip past it, as would one on a swipe that plays no sound (a
     * swipe that does not change page has no window to open). The `samples.isNotEmpty()` assertion
     * is the guard against the quieter failure of the two: if the sound block stops running, the
     * probe stops observing anything, and this test must say so rather than pass vacuously.
     */
    @Test
    @Config(
        shadows = [ShadowSoundManagerProbe::class],
        // Narrow on purpose: prefix-matched, so only SoundManager itself is instrumented, and only
        // for this one test method's sandbox. Nothing else in the class changes classloaders.
        instrumentedPackages = ["com.astroloop.game.core.SoundManager"]
    )
    fun `a swipe off a panel's page fades it out instead of blanking it mid-gesture`() {
        val view = newView()
        view.surfaceChanged(view.holder, 0, 2142, 960) // landscape, renderScale == 1f
        view.state.phase = HangarPhase.BROWSING
        view.state.currentPage = 0            // the bar page owns CREW
        view.state.openPanel = HangarPanels.Panel.CREW
        view.state.closingPanel = null
        view.state.panelFade = 1f
        view.state.pageScrollOffset = 0f
        view.state.pageVelocity = 0f

        val samples = mutableListOf<Pair<String, HangarPanels.Panel?>>()
        ShadowSoundManagerProbe.onSound = { call -> samples.add(call to view.state.visiblePanel()) }
        try {
            // 800 design units leftward in one move: past the platform's touch slop, and past the
            // quarter-stride the release needs to commit (stride is the 2142-wide screen here).
            dispatch(view, MotionEvent.ACTION_DOWN, 1071f, 480f)
            dispatch(view, MotionEvent.ACTION_MOVE, 271f, 480f)
            dispatch(view, MotionEvent.ACTION_UP, 271f, 480f)
        } finally {
            ShadowSoundManagerProbe.onSound = null
        }

        assertEquals("the swipe must actually leave the page, or this proves nothing",
            1, view.state.currentPage)
        assertTrue("the page-change sound block never ran, so nothing was sampled inside the " +
            "window — the probe is observing nothing and this test can no longer fail",
            samples.isNotEmpty())
        assertTrue(
            "the panel must never read as absent while the page changes under it: a render frame " +
                "landing there draws no scrim at all, which is the panel blinking out before the " +
                "fade it exists to have. Sampled $samples",
            samples.none { it.second == null }
        )

        // End state. Correct, and worth pinning — but see the doc above: these four cannot
        // distinguish the fix from the defect on their own.
        assertNull("the panel must not still be open on a page that does not own it",
            view.state.openPanel)
        assertEquals("it leaves by fading, so its identity moves to closingPanel",
            HangarPanels.Panel.CREW, view.state.closingPanel)
        assertEquals("the fade starts from where it was, not from zero",
            1f, view.state.panelFade, eps)
    }
}

/**
 * Stands in for [SoundManager] for the swipe test above, and nothing else — see that test's doc
 * for why the sound block is the seam worth standing in for. While [onSound] is set, every
 * page-change sound call hands the test its own label instead of playing anything; while it is
 * null (every other test, and this one outside the gesture) the calls are silent no-ops, which is
 * what the real `SoundManager` does under Robolectric anyway — it never gets an `appContext` or a
 * `SoundPool`.
 *
 * All four of the release path's sound entry points are covered, not just the two the non-intro
 * branch uses, so the probe still samples if the gesture ever runs during the intro cinematic.
 */
@Implements(SoundManager::class)
class ShadowSoundManagerProbe {
    companion object {
        /** Set by a test to observe each sound call; left null the rest of the time. */
        @JvmStatic
        var onSound: ((String) -> Unit)? = null
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun playSFX(eventId: String, volume: Float, rate: Float, isSoundboard: Boolean) {
        onSound?.invoke(eventId)
    }

    @Implementation
    fun playAmbient(ambientId: String) {
        onSound?.invoke(ambientId)
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun playIntroSwell(context: Context) {
        onSound?.invoke("introSwell")
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun stopAmbient(fadeOutMillis: Long?) {
        onSound?.invoke("stopAmbient")
    }
}
