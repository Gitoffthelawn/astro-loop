package com.astroloop.game.hangar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.PilotDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tap that lands while a frame is part-drawn must not close the open panel.
 *
 * The owner's second device report, 2026-09-20: the panels "still close unexpectedly when
 * tapping", after the dead-space fix had demonstrably closed the geometric hole. This is the rest
 * of it, and it is a RACE rather than a geometry error.
 *
 * `HangarRenderer.render` clears everything the tap path hit-tests against — the cards, the
 * buttons, the box — at the TOP of every frame, and only republishes them in `drawPanelLayer`,
 * which runs after the starfield, the whole room, the walkway and the walker. Touches are
 * dispatched on the UI thread and the frame is drawn on the render thread, with no lock between
 * them (the concurrent collection types exist precisely because that boundary is crossed). So for
 * the majority of every frame the renderer says "nothing is tappable" while `HangarState` says
 * "a panel is open" — and the tap path resolves that disagreement by running off the end of its
 * checks into the backdrop rule, whose only outcome is to close.
 *
 * A correctly aimed tap on a pilot therefore shut the roster whenever it landed in that window,
 * which is exactly the intermittency the owner described.
 *
 * These tests SAMPLE the window rather than racing it. A [ProbeCanvas] runs the tap from inside
 * the draw pass, at a point provably after the clear and before the panel layer — single
 * threaded, no sleeps, no repeats, and it asserts it actually caught the inconsistent moment
 * rather than assuming it did. (The same discipline as the panel-blink guard at 704514f1, which
 * replaced a flaky concurrent sampler.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PanelMidFrameTapTest {

    private lateinit var persistence: PersistenceManager

    /** A rotated Pixel 9 Pro — the owner's device. */
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

    /**
     * A canvas that runs [onFirstCircle] once, the first time the frame draws a circle.
     *
     * The first circle of a BROWSING frame comes from `drawStars`, which runs immediately after
     * the clear at the top of `render` and long before `drawPanelLayer` — so the callback fires
     * inside the window under test. [firedWith] records what the tap path could see at that
     * instant, so a test can prove the window was real instead of trusting this comment.
     */
    private class ProbeCanvas(bitmap: Bitmap, val onFirstCircle: () -> Unit) : Canvas(bitmap) {
        var fired = false
        var firedWith: String? = null

        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
            if (!fired) {
                fired = true
                onFirstCircle()
            }
            super.drawCircle(cx, cy, radius, paint)
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
        // One complete frame first, so the panel is genuinely up and its rects are known.
        view.renderer.render(newCanvas(), view.state)
        return view
    }

    private fun newBitmap() = Bitmap.createBitmap(
        land.width.toInt(), land.height.toInt(), Bitmap.Config.ARGB_8888
    )

    private fun newCanvas() = Canvas(newBitmap())

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

    /**
     * The owner's report, reproduced: a tap dead-centre on a pilot card, landing mid-frame.
     *
     * RED before the fix — the panel closes — and the probe's own recording proves why: at the
     * moment the tap ran, the renderer had published no box and no cards, though the state still
     * held the panel open.
     */
    @Test
    fun `a tap on a pilot card mid-frame selects the pilot and keeps the roster open`() {
        val view = view(page = 0, panel = HangarPanels.Panel.CREW)
        val card = view.renderer.panelCardRects[3]
        val target = card.centerX to card.centerY

        val probe = ProbeCanvas(newBitmap()) { }
        val probeCanvas = ProbeCanvas(newBitmap()) {
            probe.firedWith = "box=${view.renderer.panelBoxRect} cards=${view.renderer.panelCardRects.size}"
            tap(view, target.first, target.second)
        }
        view.renderer.render(probeCanvas, view.state)

        assertTrue("the probe must actually have fired inside the frame", probeCanvas.fired)
        assertEquals(
            "the roster must survive a tap that lands mid-frame (saw ${probe.firedWith})",
            HangarPanels.Panel.CREW, view.state.openPanel
        )
        assertNull("and must not even be starting to close", view.state.closingPanel)
        assertEquals("the pilot is still selected", 3, view.state.selectedPilotIndex)
    }

    /** The same for the shop, which the owner reported alongside it. */
    @Test
    fun `a tap on a shop tile mid-frame keeps the shop open`() {
        val view = view(page = 2, panel = HangarPanels.Panel.SHOP)
        val tile = view.renderer.panelCardRects[0]
        val target = tile.centerX to tile.centerY

        val probeCanvas = ProbeCanvas(newBitmap()) { tap(view, target.first, target.second) }
        view.renderer.render(probeCanvas, view.state)

        assertTrue(probeCanvas.fired)
        assertEquals("the shop must survive it too", HangarPanels.Panel.SHOP, view.state.openPanel)
        assertNull(view.state.closingPanel)
    }

    /**
     * The window is real, and this states it as a fact about the renderer rather than about a
     * tap: partway through a frame, the published view of the panel must still be the one a
     * finger can act on. Before the fix the assertion below fails outright — box null, no cards —
     * which is the whole defect in one line.
     */
    @Test
    fun `the published panel stays readable while a frame is part drawn`() {
        val view = view(page = 0, panel = HangarPanels.Panel.CREW)
        val expectedCards = view.renderer.panelCardRects.size
        val expectedBox = view.renderer.panelBoxRect
        var midFrameBox: com.astroloop.game.core.LayoutRect? = null
        var midFrameCards = -1
        var midFrameButtons = -1

        val probeCanvas = ProbeCanvas(newBitmap()) {
            midFrameBox = view.renderer.panelBoxRect
            midFrameCards = view.renderer.panelCardRects.size
            midFrameButtons = view.renderer.panelButtonRects.size
        }
        view.renderer.render(probeCanvas, view.state)

        assertTrue(probeCanvas.fired)
        assertEquals("the box must not blink out mid-frame", expectedBox, midFrameBox)
        assertEquals("nor the cards", expectedCards, midFrameCards)
        assertEquals("nor the button", 1, midFrameButtons)
    }

    /**
     * ...and the flip side, which is what the clear-at-the-top was there to guarantee in the
     * first place: once a frame has drawn no panel, nothing of it may still be hit-testable.
     * Making publication atomic must not resurrect the stale-rects bug it was preventing.
     */
    @Test
    fun `a frame that draws no panel leaves nothing behind to hit`() {
        val view = view(page = 0, panel = HangarPanels.Panel.CREW)
        assertTrue("precondition: the panel published something", view.renderer.panelCardRects.isNotEmpty())

        view.state.openPanel = null
        view.state.closingPanel = null
        view.state.panelFade = 0f
        view.renderer.render(newCanvas(), view.state)

        assertNull("the box is gone", view.renderer.panelBoxRect)
        assertTrue("the cards are gone", view.renderer.panelCardRects.isEmpty())
        assertEquals("the button remains, since it is still drawn", 1, view.renderer.panelButtonRects.size)
    }

    /** The same, for a phase that never reaches the panel layer at all. */
    @Test
    fun `a launching frame clears the panel layer entirely`() {
        val view = view(page = 0, panel = HangarPanels.Panel.CREW)
        assertTrue(view.renderer.panelCardRects.isNotEmpty())

        view.state.phase = HangarPhase.LAUNCHING
        view.renderer.render(newCanvas(), view.state)

        assertNull(view.renderer.panelBoxRect)
        assertTrue(view.renderer.panelCardRects.isEmpty())
        assertTrue(view.renderer.panelButtonRects.isEmpty())
    }
}
