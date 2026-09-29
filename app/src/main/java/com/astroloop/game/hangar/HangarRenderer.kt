package com.astroloop.game.hangar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.astroloop.game.cabinet.CabinetMarqueeDrift
import com.astroloop.game.cabinet.CabinetRenderer
import com.astroloop.game.cabinet.CabinetSim
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.LayoutRect
import com.astroloop.game.core.ScreenLayout
import java.util.concurrent.CopyOnWriteArrayList
import com.astroloop.game.data.BandanaDefinitions
import com.astroloop.game.data.PassiveDefinitions
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.PilotDefinitions
import com.astroloop.game.data.ShipDefinitions
import com.astroloop.game.data.WeaponDefinitions
import com.astroloop.game.render.CrystalOrbPath
import com.astroloop.game.render.CrystalPalette
import com.astroloop.game.render.FontManager
import com.astroloop.game.render.IconCache
import com.astroloop.game.render.IconRenderer
import com.astroloop.game.render.ShapeRenderer
import com.astroloop.game.render.ShipRenderer
import com.astroloop.game.core.StoryStateManager
import com.astroloop.game.entity.Boss
import com.astroloop.game.input.FocusRegistry
import com.astroloop.game.input.FocusTarget
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class HangarRenderer(private val persistence: PersistenceManager) {

    private val shapeRenderer = ShapeRenderer()
    private var screenWidth = 0f
    private var screenHeight = 0f
    private var roomWidth = 0f

    // --- Paints ---
    private val textPaint = Paint().apply {
        color = 0xFFCCCCCC.toInt()
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = FontManager.getRegular()
    }
    private val costPaint = Paint().apply {
        color = 0xFFFFAA00.toInt()
        textSize = 18f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = FontManager.getRegular()
    }

    // --- Layout ---
    private var layout: ScreenLayout = ScreenLayout.compute(GameConfig.DESIGN_WIDTH, GameConfig.DESIGN_HEIGHT)

    /**
     * The device's own PORTRAIT design space. Panels lay out against this, so a card is the size
     * it is in portrait whichever way the device is held. In portrait it IS [layout], so nothing
     * about the portrait page can move.
     */
    internal var portraitLayout: ScreenLayout =
        ScreenLayout.compute(GameConfig.DESIGN_WIDTH, GameConfig.DESIGN_HEIGHT)

    /** Whether the screen is rotated. The one input the page transform takes from orientation. */
    private var landscape = false

    var shipCenterY = 0f
    var shipSpacing = 0f
    private var walkwayY = 0f
    private var ceilingY = 0f

    // --- Page sub-renderers ---
    // internal, like storePageRenderer below: the test seam for rects that only a live Canvas
    // pass would otherwise populate.
    internal val barPageRenderer = BarPageRenderer(textPaint, costPaint, persistence)
    // internal (not private): test seam, same convention as HangarSurfaceView's `state`/
    // `renderer` — lets a test place spinButtonRect directly (it has no live Canvas draw pass to
    // populate it under Robolectric; see StoreHoldSuppressesFlipTest's doc comment) without
    // widening every individual sub-renderer property to a settable one.
    internal val storePageRenderer = StorePageRenderer(persistence, textPaint, costPaint)

    // --- Tap rects (delegated to sub-renderers) ---
    val upgradeRects get() = storePageRenderer.upgradeRects
    val storeButtonRects get() = storePageRenderer.storeButtonRects
    val spinButtonRect get() = storePageRenderer.spinButtonRect
    val crystalTileRect get() = storePageRenderer.crystalTileRect
    val codexBookRect get() = barPageRenderer.codexBookRect
    internal val pilotCardRects get() = barPageRenderer.pilotCardRects

    /** Delegates to [StorePageRenderer.isCrystalTileRevealed] — see there for the branch rules. */
    fun isCrystalTileRevealed(persistence: PersistenceManager, state: HangarState): Boolean =
        storePageRenderer.isCrystalTileRevealed(persistence, state)

    // --- Stars ---
    private data class HangarStar(val x: Float, val y: Float, val size: Float, val color: Int,
                                      val pulseSpeed: Float = 0f)  // 0 = static, >0 = pulsating
    @Volatile private var stars = listOf<HangarStar>()
    private val starPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    // Hyperspace phase reusable objects (avoid per-frame allocation)
    private val hyperBgPaint = Paint()
    private val hyperStarPaint = Paint().apply { style = Paint.Style.FILL }
    private val hyperStreakPaint = Paint().apply { strokeCap = Paint.Cap.ROUND }
    private val hyperStreakRandom = java.util.Random(42)
    private val hyperStarRandom = java.util.Random(123)
    private val glitchPaint = Paint()

    // ASTRO LOOP title (first-launch intro cinematic, launchpad page)
    private val introTitlePaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = FontManager.getDisplayBold()
        letterSpacing = 0.15f
    }

    fun initialize(layout: ScreenLayout, roomWidth: Float) {
        this.roomWidth = roomWidth
        this.layout = layout
        portraitLayout = DesignSpace.portraitShaped(layout)
        val width = layout.width
        val height = layout.height
        landscape = RoomAnchor.isLandscape(width, height)
        screenWidth = width
        screenHeight = height

        // Ship center position
        shipCenterY = height / 2f
        // Portrait-shaped, not `layout`: a ship sits the same ~289 from its neighbour in either
        // orientation, so a rotated screen reveals more of the fleet instead of spreading the
        // same three further apart (the golden rule — an object's size never depends on which
        // way the device is held).
        shipSpacing = portraitLayout.content.width * 0.30f
        shipHalfExtent = measureShipHalfExtent()

        // Walkway at ~60% screen height
        walkwayY = height * 0.60f
        ceilingY = walkwayY - 80f

        // Initialize sub-renderers with shared layout
        val roomFrameLambda = { canvas: Canvas, hasCeiling: Boolean, leftSolid: Boolean, rightSolid: Boolean ->
            drawRoomFrame(canvas, hasCeiling,
                if (leftSolid) RoomEdge.SOLID else RoomEdge.ARCHWAY,
                if (rightSolid) RoomEdge.SOLID else RoomEdge.ARCHWAY)
        }
        barPageRenderer.screenWidth = width
        barPageRenderer.roomWidth = roomWidth
        barPageRenderer.landscape = landscape
        barPageRenderer.screenHeight = height
        barPageRenderer.walkwayY = walkwayY
        barPageRenderer.ceilingY = ceilingY
        barPageRenderer.content = layout.content
        barPageRenderer.drawRoomFrame = roomFrameLambda
        barPageRenderer.drawNPCWalkers = ::drawNPCWalkers
        barPageRenderer.drawCharacter = { canvas, x, y, color, walking, arm -> drawCharacter(canvas, x, y, color, walking, arm) }
        barPageRenderer.corrupted = StoryStateManager.isCorrupted(persistence)
        barPageRenderer.astroLoop = StoryStateManager.isAstroLoop(persistence)
        barPageRenderer.dressing = com.astroloop.game.hangar.BarDressing.forStage(
            StoryStateManager.stage(persistence)
        )
        storePageRenderer.screenWidth = width
        storePageRenderer.roomWidth = roomWidth
        storePageRenderer.landscape = landscape
        storePageRenderer.screenHeight = height
        storePageRenderer.walkwayY = walkwayY
        storePageRenderer.ceilingY = ceilingY
        storePageRenderer.content = layout.content
        storePageRenderer.drawRoomFrame = roomFrameLambda

        // Generate static stars above walkway (built locally, then swapped atomically)
        val newStars = mutableListOf<HangarStar>()
        val starRandom = Random(42)
        repeat(40) {
            newStars.add(HangarStar(
                x = starRandom.nextFloat() * width,
                y = starRandom.nextFloat() * walkwayY,
                size = 1f + starRandom.nextFloat() * 0.5f,
                color = 0xFF444444.toInt()
            ))
        }
        repeat(25) {
            newStars.add(HangarStar(
                x = starRandom.nextFloat() * width,
                y = starRandom.nextFloat() * walkwayY,
                size = 1.5f + starRandom.nextFloat() * 0.5f,
                color = 0xFF888888.toInt()
            ))
        }
        repeat(10) {
            newStars.add(HangarStar(
                x = starRandom.nextFloat() * width,
                y = starRandom.nextFloat() * walkwayY,
                size = 2f + starRandom.nextFloat(),
                color = 0xFFCCCCCC.toInt()
            ))
        }
        // Pulsating/sparkling stars (faint, slow twinkle)
        repeat(8) {
            newStars.add(HangarStar(
                x = starRandom.nextFloat() * width,
                y = starRandom.nextFloat() * walkwayY,
                size = 1.5f + starRandom.nextFloat(),
                color = 0xFFAABBDD.toInt(),
                pulseSpeed = 600f + starRandom.nextFloat() * 800f  // Varied pulse periods
            ))
        }
        stars = newStars
    }

    // =======================================================================
    // Main render
    // =======================================================================

    /**
     * [bezelSim]/[bezelRenderer] are the store page's attract demo, owned and ticked by
     * HangarSurfaceView — see its bezelSim doc comment. [marqueeDrift] is the marquee
     * plate's ambient rock drift, owned the same way. Threaded through render() and
     * drawPageContent() down to StorePageRenderer.draw() rather than stored as fields
     * here, matching how [state] itself already crosses this same boundary.
     */
    fun render(
        canvas: Canvas, state: HangarState, bezelSim: CabinetSim? = null,
        bezelRenderer: CabinetRenderer? = null, marqueeDrift: CabinetMarqueeDrift? = null
    ) {
        // Background
        canvas.drawColor(0xFF000011.toInt())

        // Nothing is tappable until this frame draws it — but the panel layer is published ONCE,
        // at the END of this method, rather than cleared here and refilled halfway through. A
        // clear here leaves most of every frame saying "no panel to hit" while the state says one
        // is open, and a tap landing in that window closes it; see PanelLayer's own doc. The
        // local below is render-thread-only and carries what this frame drew to that publication.
        var layer = PanelLayer.NONE
        drawnYenRight = Float.NaN // seam — see this field's own doc
        drawnButtonAlphas = emptyMap() // seam — see this field's own doc
        lastPanelScrimAlpha = 0
        lastPanelLayerAlpha = 0
        drawnSelectedShipX = Float.NaN // seam — see this field's own doc
        drawnRevealSrcX = Float.NaN    // seam — see drawCrystalReveal's own doc
        drawnRevealSrcY = Float.NaN
        drawnRevealDstX = Float.NaN
        drawnRevealDstY = Float.NaN

        when (state.phase) {
            HangarPhase.BROWSING -> {
                drawStars(canvas)

                // Draw current page content with scroll offset for peeking
                drawPageContent(canvas, state, bezelSim, bezelRenderer, marqueeDrift)

                // Walkway and pilot walker (drawn over page content)
                drawWalkway(canvas, state)
                drawPilotWalker(canvas, state)

                // Landscape only: the page's buttons, and whichever panel is open over the room.
                layer = drawPanelLayer(canvas, state, bezelSim, bezelRenderer, marqueeDrift)

                // The crystal reveal, over the panel it flies into — see drawCrystalReveal.
                drawCrystalReveal(canvas, state)

                // The intro cinematic hides all HUD chrome (nav labels + yen counter)
                // and instead shows the ASTRO LOOP title on the launchpad.
                if (state.introCinematic) {
                    if (state.currentPage == 1) {
                        drawIntroTitle(canvas, state)
                    }
                } else {
                    // Page indicator dots
                    drawPageIndicator(canvas, state)

                    // Yen counter — fades with ship drag on shipyard page
                    val yenAlpha = if (state.currentPage == 1) shipDragFade(state) else 1f
                    drawYenCounter(canvas, state, yenAlpha)
                }
            }
            HangarPhase.LAUNCHING -> {
                drawStars(canvas)
                // Walkway stays visible until liftoff, then drops away
                // Phase 0-1: static walkway. Phase 2: drops (drawn inside sequence). Phase 3: gone.
                if (state.launchPhase < 2) {
                    drawWalkway(canvas, state)
                }
                drawLaunchSequence(canvas, state)
            }
            HangarPhase.CODEX -> {
                drawStars(canvas)
                barPageRenderer.draw(canvas, state, 0f)
                drawWalkway(canvas, state)
                drawCodex(canvas, state)
            }
        }

        // Glitch overlay (death return, fades over 1 second)
        drawGlitchOverlay(canvas, state)

        // Fade from black overlay (corruption death return)
        if (state.fadeFromBlackTimer > 0f) {
            val alpha = (state.fadeFromBlackTimer / 1.0f).coerceIn(0f, 1f)
            canvas.drawColor(android.graphics.Color.argb((alpha * 255).toInt(), 0, 0, 0))
        }

        // The frame's one publication of what a finger or a focus ring may act on. Every phase
        // reaches this line, so LAUNCHING and CODEX — which never draw a panel layer — publish
        // PanelLayer.NONE and cannot leave a stale target behind. See PanelLayer's own doc for
        // why this is a single write at the end rather than a clear at the top.
        publishPanelLayer(layer)
    }

    private fun drawGlitchOverlay(canvas: Canvas, state: HangarState) {
        val t = state.glitchTimer
        if (t <= 0f) return

        val seed = System.currentTimeMillis() / 50L
        val rng = java.util.Random(seed)
        val w = screenWidth
        val h = screenHeight

        // Pixel scramble: 10 colored rects at random positions
        repeat(10) {
            val rx = rng.nextFloat() * w
            val ry = rng.nextFloat() * h
            val rw = (20f + rng.nextFloat() * 60f)
            val rh = (8f + rng.nextFloat() * 20f)
            glitchPaint.color = when (rng.nextInt(3)) {
                0 -> android.graphics.Color.rgb(255, 0, 0)
                1 -> android.graphics.Color.rgb(0, 255, 0)
                else -> android.graphics.Color.rgb(0, 100, 255)
            }
            glitchPaint.alpha = (t * 128).toInt().coerceIn(0, 128)
            canvas.drawRect(rx, ry, rx + rw, ry + rh, glitchPaint)
        }

        // Screen tear: 4 horizontal bands
        repeat(4) {
            val ty = rng.nextFloat() * h
            val th = (3f + rng.nextFloat() * 8f)
            glitchPaint.color = if (rng.nextBoolean())
                android.graphics.Color.WHITE else android.graphics.Color.BLACK
            glitchPaint.alpha = (t * 100).toInt().coerceIn(0, 100)
            canvas.drawRect(0f, ty, w, ty + th, glitchPaint)
        }

        // Color fringe: 2 thin horizontal lines (red + cyan offset)
        val fringeY = rng.nextFloat() * h

        glitchPaint.color = android.graphics.Color.rgb(255, 50, 50)
        glitchPaint.alpha = (t * 80).toInt().coerceIn(0, 80)
        canvas.drawRect(0f, fringeY, w, fringeY + 2f, glitchPaint)

        glitchPaint.color = android.graphics.Color.rgb(0, 220, 220)
        glitchPaint.alpha = (t * 80).toInt().coerceIn(0, 80)
        canvas.drawRect(0f, fringeY + 4f, w, fringeY + 6f, glitchPaint)
    }

    // =======================================================================
    // Stars
    // =======================================================================

    private fun drawStars(canvas: Canvas) {
        val time = System.currentTimeMillis()
        for (star in stars) {
            if (star.pulseSpeed > 0f) {
                // Pulsating star — fades in and out
                val pulse = (0.3f + 0.7f * ((sin(time / star.pulseSpeed.toDouble()) + 1f) / 2f)).toFloat()
                starPaint.color = star.color
                starPaint.alpha = (pulse * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(star.x, star.y, star.size * (0.8f + 0.2f * pulse), starPaint)
                starPaint.alpha = 255
            } else {
                starPaint.color = star.color
                canvas.drawCircle(star.x, star.y, star.size, starPaint)
            }
        }
    }

    // =======================================================================
    // Page content dispatcher
    // =======================================================================

    private fun drawPageContent(
        canvas: Canvas, state: HangarState, bezelSim: CabinetSim?, bezelRenderer: CabinetRenderer?,
        marqueeDrift: CabinetMarqueeDrift? = null
    ) {
        // Pages tile one stride apart and each leans toward the shipyard inside its own slot.
        // In portrait the lean is the centring term the shipped code folded into its viewport and
        // the stride is the room width, so this is arithmetically identical to what shipped.
        val stride = RoomAnchor.stride(screenWidth, roomWidth, landscape)

        // A room is visible if any part of it falls inside the screen. The test is against
        // screenWidth, not stride: on wide screens several rooms are on screen at once.
        val barX = pageOriginX(0, state)
        if (barX > -stride && barX < screenWidth) {
            barPageRenderer.draw(canvas, state, -barX)
        }

        val shipyardX = pageOriginX(1, state)
        if (shipyardX > -stride && shipyardX < screenWidth) {
            drawShipyardPage(canvas, state, -shipyardX)
        }

        val storeX = pageOriginX(2, state)
        if (storeX > -stride && storeX < screenWidth) {
            storePageRenderer.draw(canvas, state, -storeX, bezelSim, bezelRenderer, marqueeDrift)
        }
    }

    /**
     * World X of the left screen edge, for this frame's page and scroll offset. One definition
     * shared by the page pass, the walkway and the pilot walker — three hand-written copies of
     * it drifting apart is what put content in the wrong room in the first place.
     */
    private fun viewportX(state: HangarState): Float =
        RoomAnchor.viewportX(state.currentPage, state.pageScrollOffset, screenWidth, roomWidth, landscape)

    /**
     * Screen X of page [page]'s left edge this frame. The draw half of the pair whose other half
     * is `HangarSurfaceView.roomX`; they change together or every tap lands off-target.
     */
    private fun pageOriginX(page: Int, state: HangarState): Float =
        RoomAnchor.pageOriginX(
            page, state.currentPage, state.pageScrollOffset, screenWidth, roomWidth, landscape
        )

    /**
     * Screen-space horizontal extent of the hangar building (its three rooms), for the walkway
     * and anything that must sit flush with it.
     *
     * Below the gate the building is exactly as wide as the screen (effectiveRoomWidth returns
     * screenWidth there), so it can never be narrower than the screen and the clip below must
     * never engage. HangarSurfaceView's rubber-band resistance damps pageScrollOffset toward zero
     * during edge overscroll but never clamps it to exactly zero, so feeding viewportX into the
     * clip in that branch would shave a sliver off the flush edge for the whole duration of the
     * drag. Bypass it entirely: the walkway always spans the full screen here, independent of
     * currentPage/pageScrollOffset.
     *
     * Landscape is excluded from that bypass even though its stride is also a full screen: the
     * crew and shop rooms are only portrait-wide there and hug the shipyard side of their slots,
     * so there IS starfield beside them and a full-screen walkway would hang out over it.
     */
    private fun buildingExtent(state: HangarState): Pair<Float, Float> {
        val stride = RoomAnchor.stride(screenWidth, roomWidth, landscape)
        if (!landscape && stride >= screenWidth) return 0f to screenWidth
        val left = pageOriginX(0, state)
        val right = pageOriginX(2, state) + RoomAnchor.pageWidth(2, screenWidth, roomWidth, landscape)
        return left.coerceAtLeast(0f) to right.coerceAtMost(screenWidth)
    }

    // =======================================================================
    // Landscape panel layer
    // =======================================================================

    /**
     * Everything the tap and focus paths hit-test an open panel against, as ONE value: the outer
     * box, the item rects inside it, and each visible button's rect — all in SCREEN space.
     *
     * **One value, and one assignment per frame, because the alternative was a live bug.** These
     * three used to be separate mutable fields, cleared at the top of [render] and refilled in
     * [drawPanelLayer] — which runs after the starfield, the whole room, the walkway and the
     * walker. Touches arrive on the UI thread while the frame is drawn on the render thread, with
     * no lock between them, so for the majority of every frame the renderer said "nothing here to
     * hit" while `HangarState` still said "a panel is open". `handlePanelTap` resolves that
     * disagreement by falling through to the backdrop rule, whose only outcome is to CLOSE — so a
     * correctly aimed tap on a pilot shut the roster whenever it landed in that window. The owner
     * reported it twice: once as the dead-space bug (a real but separate geometric hole, fixed by
     * the box below) and again, after that fix, as panels that "still close unexpectedly".
     *
     * Publishing an immutable snapshot in a single volatile write removes the window entirely: a
     * reader sees either the whole of last frame's panel or the whole of this one, never a
     * half-built frame and never a box without its cards. It also follows what the ROOM's own
     * rects (`upgradeRects`, `pilotCardRects`) have always done — overwritten in place, never
     * cleared first, which is why the room never had this bug.
     *
     * It keeps the property the clear was there for: a frame that draws no panel publishes
     * [NONE], so a launch, the codex, portrait or the intro cinematic cannot leave last frame's
     * rects behind as targets while nothing is on screen. [render] publishes exactly once, at the
     * end, for every phase.
     *
     * Readers take the snapshot into a local FIRST and then interrogate it — two reads of the
     * field can still straddle a frame, which is the same class of bug one level down.
     */
    internal data class PanelLayer(
        val box: LayoutRect?,
        val cards: List<LayoutRect>,
        val buttons: Map<HangarPanels.Panel, LayoutRect>
    ) {
        companion object {
            /** A frame that drew no panel layer at all. */
            val NONE = PanelLayer(null, emptyList(), emptyMap())
        }
    }

    @Volatile
    internal var panelLayer: PanelLayer = PanelLayer.NONE
        private set

    /**
     * The one writer. [render] calls this at the end of every frame; tests that cannot run a real
     * draw pass (Robolectric's `lockCanvas` returns null) call it to stand in for the frame that
     * would have published these.
     */
    internal fun publishPanelLayer(layer: PanelLayer) {
        panelLayer = layer
    }

    /**
     * The open panel's item rects, in SCREEN space. Published rather than recomputed, so the tap
     * path — and the focus path — reads exactly the rects that were drawn instead
     * of a second derivation that can drift from them.
     *
     * Empty whenever no panel is open, which is always in portrait.
     */
    internal val panelCardRects: List<LayoutRect> get() = panelLayer.cards

    /**
     * Each visible button's rect this frame, in SCREEN space: its home while its panel is shut,
     * its tab while it is open.
     *
     * Empty whenever the layer draws nothing, so everything that stops the buttons being drawn —
     * portrait, a page that owns no panel, the intro cinematic — stops them being tapped by the
     * same fact rather than by a second gate that could disagree with the first.
     */
    internal val panelButtonRects: Map<HangarPanels.Panel, LayoutRect> get() = panelLayer.buttons

    /**
     * The open panel's outer BOX this frame, in SCREEN space — `null` whenever the layer drew no
     * panel at all.
     *
     * Read by `HangarSurfaceView.handlePanelTap` to tell a tap that MISSED a card inside the panel
     * from one that landed outside it. A box is strictly bigger than the grid it holds —
     * [HangarPanels.PAD_H]/[HangarPanels.PAD_V] of padding all round, a gap between every pair of
     * cards, and the empty half-row a short final row leaves in a reflowed board — and every one
     * of those points used to fall through to the backdrop catch-all and CLOSE the panel. Reported
     * by the owner on 2026-09-20 as "pressing a pilot sometimes closes the roster instead": the
     * pilot grid's gutters are about 16 units wide, so it happened often enough to look random.
     */
    internal val panelBoxRect: LayoutRect? get() = panelLayer.box

    /**
     * The x the yen counter's right-aligned text was actually drawn at, `NaN` on a frame that
     * drew no counter.
     *
     * A seam, exactly like [drawnSelectedShipX] — and needed for the same reason. "The counter is
     * anchored to the safe area" was true and still left it clipped by the display's rounded
     * corner, and no assertion in the suite could see it, because a Robolectric canvas cannot be
     * asked where a string landed. Reverting the corner clearance to a plain `safe.right - 20f`
     * leaves everything green without this.
     */
    @Volatile
    internal var drawnYenRight: Float = Float.NaN
        private set

    /**
     * The alpha each panel button was actually DRAWN at this frame, empty for a frame that drew
     * none — a seam, like [drawnYenRight], and test-only: nothing on the UI thread reads it.
     *
     * A button that is fading out after its page has been swiped away is drawn but deliberately
     * not published in [PanelLayer], so it does not appear in `panelButtonRects` and there is
     * otherwise no way to tell "faded correctly on the way out" from the instant disappearance
     * the owner reported on 2026-09-20.
     */
    @Volatile
    internal var drawnButtonAlphas: Map<HangarPanels.Panel, Float> = emptyMap()
        private set

    /**
     * The scrim/box-layer alpha [drawPanelLayer] actually computed and used THIS frame, 0 when
     * nothing was drawn. Nothing reads these back — they exist purely as a seam, because
     * every test in the suite drives [HangarState.
     * panelFade] through its state-machine transitions, but none of them had inspected what the
     * draw path actually DID with it, so replacing the [HangarPanels.panelScrimAlpha]/
     * [HangarPanels.panelLayerAlpha] calls below with hardcoded constants — the panel popping
     * open/shut instead of fading, the exact bug the fade commit exists to prevent — left every
     * one of them green. A `saveLayerAlpha` region isn't something Robolectric's software canvas
     * can be probed for pixel-accurately without a real compositing pass, so exposing the
     * computed alpha is the cheap way to pin it instead.
     */
    internal var lastPanelScrimAlpha: Int = 0
        private set
    internal var lastPanelLayerAlpha: Int = 0
        private set

    private val panelBoxPaint = Paint().apply { style = Paint.Style.FILL; color = 0xF0101018.toInt() }
    private val panelBorderPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true; color = 0xFF3A3A40.toInt()
    }

    /**
     * The panel's content box, before padding — the grid for CREW/SHOP, the machine for SLOT.
     *
     * Every size here comes from [portraitLayout], never from the landscape [layout]: a card is
     * the size it is in portrait whichever way the device is held, and only the ARRANGEMENT is
     * allowed to change. `portraitLayout.width` is passed as both the room width and the screen
     * width, which makes `HangarMetrics.contentXInRoom` the identity — the panel lays out in its
     * own space, so there is no room to cross into.
     *
     * Internal: a test seam, like [portraitLayout] and [shipyardPageWidth].
     */
    internal fun panelContentSize(panel: HangarPanels.Panel): Pair<Float, Float> = when (panel) {
        HangarPanels.Panel.CREW -> crewGridArrangement().let { it.width to it.height }
        HangarPanels.Panel.SHOP -> shopGridArrangement().let { it.width to it.height }
        HangarPanels.Panel.SLOT -> panelMachineFrame().let { it.width to it.height }
    }

    /** The portrait walkway — what the store's board and machine are both measured against. */
    private val portraitWalkwayY: Float get() = portraitLayout.height * 0.60f

    /** One store tile, at its portrait size. Square, so one number. */
    private fun panelTileSize(): Float =
        GridGeometry.storeTileSize(portraitLayout.content, portraitWalkwayY)

    /**
     * Top of the nav row's tap band — the same [HangarMetrics.navBandTop] the input gate
     * (`HangarSurfaceView.handleTap`) and the focus pass (`publishNavTargets`) resolve taps
     * against. It used to be a hand-copy of that pair of numbers here, which was tolerable while
     * the band only decided tap ownership and stopped being so once a PANEL'S ARRANGEMENT started
     * depending on it — see [HangarMetrics.navBandTop]'s own doc.
     */
    private val navBandTop: Float get() = HangarMetrics.navBandTop(screenHeight)

    /**
     * The vertical space a panel's CONTENT may use, before padding.
     *
     * [HangarPanels.panelBox] always centres the outer box on `safe.centerY`, growing it in both
     * directions as content grows, so the binding constraint on how tall that content may get is
     * symmetric about `safe.centerY`, not the plain distance from `safe.top` down to the nav band:
     * a box that only grew downward would clear the band at twice this height. Before this fix the
     * SHOP panel's 3x3 board (908.79 tall on a rotated Pixel 9 Pro) ignored the band entirely, and
     * its own box (936.42 bottom) landed 50.57 units into it — visibly under the lit
     * [CREW]/[LAUNCH] labels `drawPageIndicator` paints on top of the panel layer.
     *
     * Also floored by the safe area's own height (unchanged from before this fix, and normally the
     * looser of the two): nothing here assumes the nav band will always be the tighter constraint.
     *
     * Applies to every panel that reflows — CREW included, not just SHOP, per the review — even
     * though CREW's own board (683.8 tall) clears either cap today with room to spare. SLOT is not
     * threaded through this: the machine is a fixed portrait object that never reflows, and its own
     * clearance is verified directly (`ShopPanelTest`'s `the machine clears the nav row's tap
     * band...`), not budgeted here.
     */
    private fun panelAvailableHeight(safe: LayoutRect): Float {
        val navClearance = 2f * (navBandTop - safe.centerY) - HangarPanels.PAD_V * 2f
        val safeClearance = safe.height - HangarPanels.PAD_V * 2f
        return minOf(navClearance, safeClearance)
    }

    /**
     * The SHOP panel's arrangement of the nine tiles — the store's twin of [crewGridArrangement],
     * and derived once per frame for the same reason: [panelContentSize] sizes the box from it and
     * [drawPanelContents] lays the tiles out from it, and two expressions that agree algebraically
     * can still disagree by an ulp and hand the second caller a wider grid than the box was sized
     * for, whose rects are published as tap targets even though the drawn grid is clipped.
     *
     * Three rows on most profiles in the spec — the board is the portrait content width less
     * 32, and a landscape screen's height is the portrait width, so the two are the same quantity
     * by construction and the fit lives entirely in the margins — but [panelAvailableHeight] caps
     * that height short of the nav row's tap band, and on a 2.22:1 phone (a rotated Pixel 9 Pro)
     * that cap is tighter than the 3x3 board, so it reflows to 5+4 there.
     * A 16:9 profile has enough room above the band for 3x3 to stand. The band is what decides
     * this, not `HangarPanels.PAD_V` — see that constant's own doc.
     *
     * internal: a test seam, like [panelContentSize] — `ShopPanelTest` reads `.cols`/`.rows` off
     * this directly, on real device profiles, so a hardcoded `Arrangement(3, 3, …)` here fails a
     * real test rather than merely a re-derivation that could hardcode the same mistake.
     */
    internal fun shopGridArrangement(): PanelGrid.Arrangement {
        val safe = layout.safe
        val tile = panelTileSize()
        return PanelGrid.arrange(
            GridGeometry.STORE_COLS * GridGeometry.STORE_ROWS, tile, tile, GridGeometry.STORE_GAP,
            GridGeometry.STORE_COLS,
            safe.width - HangarPanels.SCREEN_EDGE * 2f - HangarPanels.PAD_H * 2f,
            panelAvailableHeight(safe)
        )
    }

    /**
     * The machine at its PORTRAIT size — the SLOT panel's single object, which neither reflows nor
     * scales: it either fits the panel or it does not. Measured at the portrait grid's own width
     * and the portrait drop from the walkway, exactly as the room measures it, so the cabinet in
     * the panel is the cabinet in the room.
     */
    private fun panelMachineFrame(): LayoutRect = GridGeometry.machineFrame(
        GridGeometry.storeGridBounds(portraitLayout.content, 0f, portraitWalkwayY),
        portraitWalkwayY, portraitLayout.height
    )

    /**
     * The CREW panel's arrangement of all pilot cards, from the same inputs [panelContentSize]
     * used to size the box — computed once per frame in `drawPanelLayer` and threaded through to
     * [drawPanelContents] rather than re-derived there.
     *
     * A code review (the "arrangement derived twice" minor finding): before this, the box
     * was sized from [PanelGrid.arrange] against `safe.width - SCREEN_EDGE*2 - PAD_H*2`, and
     * [drawPanelContents] arranged AGAIN against `box.width - PAD_H*2` — algebraically the same
     * number (`box.width == contentW + PAD_H*2`), but reached by a different float expression, so
     * a single ulp of drift between the two could leave the second call arranging a wider grid
     * (say 4 columns instead of 3) than the box was actually sized for. [PanelGrid.arrange]
     * refuses a reduction that would overflow the available width, so that grid would be clipped
     * visually — but its rects, published as tap targets, would not be. Calling this once and
     * passing the SAME [PanelGrid.Arrangement] to both consumers makes that impossible: there is
     * only one derivation, not two that happen to agree.
     *
     * Height capped by [panelAvailableHeight] like [shopGridArrangement] — a code review
     * was explicit that the nav-band rule is not SHOP-specific, even though CREW's own board
     * (683.8 tall) clears either cap today with room to spare and so never actually reflows from
     * it.
     */
    private fun crewGridArrangement(): PanelGrid.Arrangement {
        val safe = layout.safe
        val (cardW, cardH) = panelCardSize()
        return PanelGrid.arrange(
            PilotDefinitions.getPilotCount(), cardW, cardH, GridGeometry.PILOT_GAP,
            GridGeometry.PILOT_COLS,
            safe.width - HangarPanels.SCREEN_EDGE * 2f - HangarPanels.PAD_H * 2f,
            panelAvailableHeight(safe)
        )
    }

    /**
     * One pilot card, at its portrait size. Also the size of a panel BUTTON — the buttons are
     * card-sized on purpose, so the crew page reads as a stack of cards either way.
     */
    private fun panelCardSize(): Pair<Float, Float> = GridGeometry.pilotCardSize(
        GridGeometry.pilotGridBounds(portraitLayout.content, portraitLayout.width, portraitLayout.width)
    )

    /**
     * The landscape panel layer: the buttons a page shows, and the panel one of them has opened.
     *
     * Drawn after the rooms, the walkway and the walker, and BEFORE the chrome — the yen counter
     * and the page indicator stay above the scrim, because you need to see your money while the
     * shop panel is up, and because leaving the hangar must not require closing a panel first.
     * The nav row is therefore drawn over the panel, and `HangarSurfaceView.handleTap` resolves
     * the nav row FIRST, to match — a lit nav label must always be reachable, panel or no panel
     * (found in review). The two only need to agree on tap ownership at all
     * because the panel's box stays clear of the nav row's tap band, which `CrewPanelTest` pins
     * as an invariant rather than an accident.
     *
     * A closing panel (see [HangarState.closingPanel]) is drawn regardless of whether the
     * CURRENT page owns it — only an OPEN one is filtered to the page it belongs to. A page
     * change hands an open panel to [HangarState.closingPanel] via `HangarState.setPageTarget`
     * rather than clearing it, precisely so this layer keeps drawing it through its fade instead
     * of cutting it in the one frame the page changes.
     *
     * The three cabinet parameters are threaded through exactly as [drawPageContent] threads
     * them: the SLOT panel draws the same machine the store page does, and it needs them.
     */
    private fun drawPanelLayer(
        canvas: Canvas, state: HangarState, bezelSim: CabinetSim?, bezelRenderer: CabinetRenderer?,
        marqueeDrift: CabinetMarqueeDrift?
    ): PanelLayer {
        // Returns what it drew rather than assigning it; render() publishes the result in one
        // write at the end of the frame. The intro cinematic hides all chrome, the buttons
        // included — and because the tap path reads only what this published, hiding them here is
        // what makes them untappable too.
        if (!landscape || state.introCinematic) return PanelLayer.NONE
        val panels = HangarPanels.panelsOn(state.currentPage)
        // How lit each panel-owning page's buttons are — 1 for the settled current page, easing
        // to 0 as it is swiped away and back up as one is swiped in. A page the swipe has carried
        // far enough out is not drawn at all. See HangarGestures.pageSwipeFade: the buttons are
        // screen chrome that belongs to a page, so nothing moved them when the page moved, and
        // they used to stay fully lit through the swipe and vanish on the frame it committed.
        val stride = RoomAnchor.stride(screenWidth, roomWidth, landscape)
        val (fadeCardW, fadeCardH) = panelCardSize()
        val pageFade = HangarPanels.PANEL_PAGES.associateWith { page ->
            HangarGestures.pageSwipeFade(
                page, state.currentPage, state.pageScrollOffset, stride,
                buttonFadeTravel(page, layout.safe, fadeCardW, fadeCardH)
            )
        }
        // A closing panel keeps this layer alive even on a page that owns no panel at all (the
        // launchpad, panelsOn(1) == emptyList()) — see the closing-panel paragraph above. So does
        // a page still fading out behind the one being swiped to.
        if (panels.isEmpty() && state.closingPanel == null && pageFade.values.all { it <= 0f }) {
            return PanelLayer.NONE
        }

        val safe = layout.safe
        val (cardW, cardH) = panelCardSize()
        // The same predicate HangarSurfaceView's input gate consults — see
        // HangarState.visiblePanel's own doc. An OPEN panel is
        // filtered to the page it belongs to; a CLOSING one is not, since HangarState.
        // advancePanelFade guarantees it clears itself within PANEL_FADE_SECONDS regardless of
        // whether anything ever draws it, so there is no risk of a stale panel surviving here.
        val visible = state.visiblePanel()

        val buttons = LinkedHashMap<HangarPanels.Panel, LayoutRect>(panels.size)
        val cards = ArrayList<LayoutRect>()

        if (visible != null) {
            // Computed once, not re-derived from the box below — see crewGridArrangement's doc
            // (a code review, the "arrangement derived twice" minor finding). SLOT holds one
            // object rather than a grid, so it has no arrangement and sizes straight off its frame.
            val arrangement = when (visible) {
                HangarPanels.Panel.CREW -> crewGridArrangement()
                HangarPanels.Panel.SHOP -> shopGridArrangement()
                HangarPanels.Panel.SLOT -> null
            }
            val (contentW, contentH) = arrangement?.let { it.width to it.height }
                ?: panelContentSize(visible)
            val box = HangarPanels.panelBox(visible, contentW, contentH, safe)
            val fade = state.panelFade
            // Scrim over the whole screen: the room stays visible and still animating behind it.
            // Its alpha follows the same fade the panel box does — 0 the instant a close begins
            // would cut the scrim the moment the tap lands, so it rides panelFade down instead
            // and "releases" over the fade like the panel itself.
            val scrimAlpha = HangarPanels.panelScrimAlpha(fade)
            lastPanelScrimAlpha = scrimAlpha // seam — see this field's own doc
            canvas.drawColor(android.graphics.Color.argb(scrimAlpha, 0x00, 0x00, 0x11))
            // The box and its contents fade as one unit via a canvas layer alpha, rather than
            // threading alpha through BarPageRenderer.drawPilotCards (and, later, the shop/slot
            // content it will sit beside) — the same approach HUDRenderer.render already leans
            // on for its own 120Hz fade. Rects are still published below at full geometry
            // regardless of fade: what's tappable is a discrete question (open vs. closing vs.
            // neither), answered in HangarSurfaceView.handlePanelTap, not a continuous one this
            // alpha should answer by accident.
            val layerAlpha = HangarPanels.panelLayerAlpha(fade)
            lastPanelLayerAlpha = layerAlpha // seam — see this field's own doc
            if (layerAlpha >= 255) {
                // Fully open is where the panel spends most of its life, so
                // skip the offscreen buffer entirely rather than pay for one
                // whose alpha is a no-op every frame it is up.
                drawPanelBox(canvas, box)
                drawPanelContents(canvas, state, visible, box, arrangement, cards, bezelSim, bezelRenderer, marqueeDrift)
            } else {
                canvas.saveLayerAlpha(box.left, box.top, box.right, box.bottom, layerAlpha)
                drawPanelBox(canvas, box)
                drawPanelContents(canvas, state, visible, box, arrangement, cards, bezelSim, bezelRenderer, marqueeDrift)
                canvas.restore()
            }
            // Every button belonging to the SAME page as the open/closing panel is clamped
            // against its box, not just the one panel that is actually open.
            // SHOP and SLOT share one X (buttonHome), so this was
            // invisible while neither panel's box ever reached the stack — but a 5+4 SHOP board is
            // wide enough to reach past SLOT's home, and clamping only the active button left its
            // sibling sitting at `home`, underneath the wider box, while the active one alone slid
            // clear. HangarPanels.tabRect degenerates to `home` unclamped whenever the box does
            // not reach a given button (its own doc: "left exactly where it was"), so applying it
            // to every button on the page is a no-op for CREW's lone button and for every profile
            // where neither shop panel's box was ever wide enough to matter — verified by
            // `ShopPanelTest`'s button-clamp test on the widened board.
            //
            // Guarded by `visible in panels`, not applied unconditionally: a CLOSING panel can be
            // drawn over a page that does not own it at all (the launchpad, or — inside this
            // `panels` list — a different page's own buttons), in which case `box` belongs to a
            // page these buttons are not part of and must not move them (`CrewPanelTest`'s "a
            // panel closes visibly even after a page change carries it off its own page").
            drawPanelButtons(canvas, state, safe, cardW, cardH, pageFade, visible, box, buttons)
            return PanelLayer(box, cards, buttons)
        }

        drawPanelButtons(canvas, state, safe, cardW, cardH, pageFade, null, null, buttons)
        // No panel, so no box and no cards — but the buttons are drawn and must be tappable.
        return PanelLayer(null, emptyList(), buttons)
    }

    /**
     * How far page [page] travels on a swipe before its own room arrives at its buttons — the
     * distance their fade has to finish in.
     *
     * Owner, 2026-09-20: the buttons must be "completely gone once the bar hits the button (or
     * the shop on the other side)". The arithmetic lives in [HangarPanels.buttonRoomGap]; this
     * supplies the room's settled edges from the same [RoomAnchor] the page pass draws with, so
     * the two cannot describe different rooms. A page settled and current has its origin at its
     * anchor, which is what makes the anchor the room's resting edge.
     */
    private fun buttonFadeTravel(page: Int, safe: LayoutRect, cardW: Float, cardH: Float): Float {
        val panel = HangarPanels.panelsOn(page).firstOrNull() ?: return 0f
        val pw = RoomAnchor.pageWidth(page, screenWidth, roomWidth, landscape)
        val roomLeft = RoomAnchor.anchorX(page, screenWidth, roomWidth, landscape)
        return HangarPanels.buttonRoomGap(
            panel, roomLeft, roomLeft + pw,
            HangarPanels.buttonHome(panel, safe, cardW, cardH)
        )
    }

    /**
     * Every panel-owning page's buttons that are still on screen, each at its own swipe alpha,
     * collecting the CURRENT page's into [buttons] as the frame's hit targets.
     *
     * **Drawn for any page, published for one.** A page that has been swiped away still draws its
     * buttons while they fade, but they are no longer something to press — the player is on
     * another page and pressing one would open a panel that page does not own. That split is not
     * new here: `drawPanelLayer` already draws a CLOSING panel through its fade while
     * `HangarSurfaceView.handlePanelTap` refuses its cards, for the same reason. What the rule
     * against draw and hit test diverging forbids is a target whose rect is not where it was
     * drawn; a fading-out thing that has stopped being a target is the ordinary way to leave.
     *
     * The tab clamp still applies only to the page that owns the open panel ([box] non-null): a
     * panel cannot push a button belonging to a page it is not on.
     */
    private fun drawPanelButtons(
        canvas: Canvas, state: HangarState, safe: LayoutRect, cardW: Float, cardH: Float,
        pageFade: Map<Int, Float>, visible: HangarPanels.Panel?, box: LayoutRect?,
        buttons: MutableMap<HangarPanels.Panel, LayoutRect>
    ) {
        val alphas = LinkedHashMap<HangarPanels.Panel, Float>()
        for (page in HangarPanels.PANEL_PAGES) {
            val alpha = pageFade[page] ?: 0f
            if (alpha <= 0f) continue
            val pagePanels = HangarPanels.panelsOn(page)
            // Guarded by "the open panel belongs to THIS page", which is what `visible in panels`
            // meant when there was only ever one page's worth of buttons to draw: a CLOSING panel
            // can be drawn over a page that does not own it at all, in which case its box must
            // not move that page's buttons (`CrewPanelTest`'s "a panel closes visibly even after
            // a page change carries it off its own page").
            val ownsVisible = visible != null && visible in pagePanels
            for (p in pagePanels) {
                val home = HangarPanels.buttonHome(p, safe, cardW, cardH)
                // Every button on the open panel's page is clamped against its box, not just the
                // one panel actually open: SHOP and SLOT
                // share one X, so clamping only the active one would leave its sibling underneath
                // a board wide enough to reach them. tabRect degenerates to `home` whenever the
                // box does not reach a given button, which since the owner's 2026-09-20 move is
                // every panel on every profile in the spec.
                val rect = if (ownsVisible && box != null) HangarPanels.tabRect(p, home, box) else home
                drawPanelButton(canvas, state, p, rect, active = p == visible, alpha = alpha)
                alphas[p] = alpha
                if (page == state.currentPage) buttons[p] = rect
            }
        }
        drawnButtonAlphas = alphas // seam — see this field's own doc
    }

    private fun drawPanelBox(canvas: Canvas, box: LayoutRect) {
        canvas.drawRect(box.left, box.top, box.right, box.bottom, panelBoxPaint)
        panelBorderPaint.color = 0xFF3A3A40.toInt()
        canvas.drawRect(box.left, box.top, box.right, box.bottom, panelBorderPaint)
    }

    /**
     * What an open panel puts inside its box.
     *
     * CREW hands the panel's rects straight to [BarPageRenderer.drawPilotCards], and SHOP does the
     * same with [StorePageRenderer.drawUpgradeTiles] — the same renderers the in-room grid and
     * board use, at the same sizes, in a different place. SLOT is the odd one: a single object, the
     * machine, which neither reflows nor scales.
     *
     * [arrangement] is the panel's [PanelGrid.Arrangement], computed once by the caller
     * ([drawPanelLayer]) from the exact same inputs it used to size [box] — never re-derived from
     * `box.width` here, which is the arrangement-derived-twice fix (see [crewGridArrangement]'s
     * doc). Non-null for CREW and SHOP; null for SLOT, which has no grid.
     *
     * Everything is collected into [cards] in the order it was drawn, SLOT's machine included:
     * the machine IS the panel's one item, and publishing it is what lets a tap on it reach the
     * store's own handler instead of falling through to the backdrop-closes rule. The caller owns
     * the list and hands it to [PanelLayer] in one write — see that type's doc.
     */
    private fun drawPanelContents(
        canvas: Canvas, state: HangarState, panel: HangarPanels.Panel, box: LayoutRect,
        arrangement: PanelGrid.Arrangement?, cards: MutableList<LayoutRect>,
        bezelSim: CabinetSim?, bezelRenderer: CabinetRenderer?, marqueeDrift: CabinetMarqueeDrift?
    ) {
        when (panel) {
            HangarPanels.Panel.CREW -> {
                val count = PilotDefinitions.getPilotCount()
                val (cardW, cardH) = panelCardSize()
                val a = arrangement ?: crewGridArrangement()
                val rects = PanelGrid.rects(
                    a, count, box.left + HangarPanels.PAD_H, box.top + HangarPanels.PAD_V,
                    cardW, cardH, GridGeometry.PILOT_GAP
                )
                cards.addAll(rects)
                barPageRenderer.drawPilotCards(canvas, state, rects)
            }
            HangarPanels.Panel.SHOP -> {
                val count = GridGeometry.STORE_COLS * GridGeometry.STORE_ROWS
                val tile = panelTileSize()
                val a = arrangement ?: shopGridArrangement()
                val rects = PanelGrid.rects(
                    a, count, box.left + HangarPanels.PAD_H, box.top + HangarPanels.PAD_V,
                    tile, tile, GridGeometry.STORE_GAP
                )
                cards.addAll(rects)
                storePageRenderer.drawUpgradeTiles(canvas, state, rects)
            }
            HangarPanels.Panel.SLOT -> {
                val frame = LayoutRect(
                    box.left + HangarPanels.PAD_H, box.top + HangarPanels.PAD_V,
                    box.right - HangarPanels.PAD_H, box.bottom - HangarPanels.PAD_V
                )
                cards.add(frame)
                storePageRenderer.drawSlotMachineAt(
                    canvas, state, frame, bezelSim, bezelRenderer, marqueeDrift
                )
            }
        }
    }

    /**
     * The button, and the tab it becomes. One icon, per the owner — no badge, no count, no word:
     * a player with a recruit waiting sees the same button as one with nothing to do.
     *
     * CREW is the selected pilot's roster card. SHOP and SLOT carry [panelButtonIcon]'s icon, a
     * square [PANEL_ICON_FRACTION] of the card's short side, centred. A button with nothing to
     * show (no pilot selected, an asset that failed to load) is the bare card it was before the
     * art arrived; the layout does not depend on it.
     */
    private fun drawPanelButton(
        canvas: Canvas, state: HangarState, panel: HangarPanels.Panel,
        rect: LayoutRect, active: Boolean, alpha: Float = 1f
    ) {
        // Through a layer rather than by scaling each paint's alpha: the fill carries its own
        // alpha (0xF0) so a multiply would need the base, the border switches colour with
        // `active`, and the icon gets the same treatment for free. Skipped entirely at full
        // alpha, which is where a button spends all its time when nothing is being swiped — the
        // same trade drawPanelLayer makes for the panel box itself.
        val faded = alpha < 1f
        if (faded) {
            canvas.saveLayerAlpha(
                rect.left, rect.top, rect.right, rect.bottom,
                (alpha * 255f).toInt().coerceIn(0, 255)
            )
        }
        if (panel == HangarPanels.Panel.CREW && crewButtonPilot(state) >= 0) {
            // The selected pilot's roster card, as the pop-up draws it (owner, 2026-09-28). The
            // button is already card-sized — panelCardSize() is the roster's own card — so it
            // is the same card at the same size, and its pilot-coloured border replaces the
            // grey one. Nothing else to draw.
            barPageRenderer.drawPilotCardFront(canvas, state, crewButtonPilot(state), rect)
            if (faded) canvas.restore()
            return
        }
        canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, panelBoxPaint)
        panelBorderPaint.color = if (active) 0xFF8899AA.toInt() else 0xFF3A3A40.toInt()
        canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, panelBorderPaint)
        val icon = panelButtonIcon(state, panel)
        if (icon != null) {
            val half = minOf(rect.width, rect.height) * PANEL_ICON_FRACTION / 2f
            panelIconRect.set(
                rect.centerX - half, rect.centerY - half, rect.centerX + half, rect.centerY + half
            )
            canvas.drawBitmap(icon, null, panelIconRect, panelIconPaint)
        }
        if (faded) canvas.restore()
    }

    /** The selected pilot's index if the crew button can show their card, else -1. */
    private fun crewButtonPilot(state: HangarState): Int {
        val index = state.selectedPilotIndex
        return if (PilotDefinitions.getPilotByIndex(index) != null && state.isPilotUnlocked(index)) index else -1
    }

    /**
     * The icon a SHOP or SLOT button shows: the upgrade art, and the slot machine — or, in Astro
     * Loop, where that machine is the BELT RUN cabinet, the arcade. CREW draws a whole card
     * instead (see [drawPanelButton]) and has no icon.
     */
    private fun panelButtonIcon(state: HangarState, panel: HangarPanels.Panel): Bitmap? =
        when (panel) {
            HangarPanels.Panel.CREW -> null
            HangarPanels.Panel.SHOP -> IconCache.getPanelIcon("upgrade")
            HangarPanels.Panel.SLOT -> IconCache.getPanelIcon(
                if (StoryStateManager.isAstroLoop(persistence)) "arcade" else "slot"
            )
        }

    private val panelIconRect = RectF()
    private val panelIconPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

    // =======================================================================
    // Crystal reveal (drawn over the panel layer)
    // =======================================================================

    /**
     * Where the reveal's orb actually left from and where it was actually headed this frame, in
     * SCREEN space — `NaN` on a frame that drew neither.
     *
     * A seam, exactly like [drawnSelectedShipX] and for the same reason. "The reveal completes"
     * is a proposition about `crystalRevealPhase` alone: every phase, sound and timing assertion
     * in the suite passed the whole time the landscape orb was corkscrewing to the room's
     * top-left corner and bursting at (0,0), because a Robolectric canvas cannot be asked where a
     * circle landed. Publishing the two endpoints is the cheap way to pin the one thing the beat
     * is actually about — that the player can see the crystal leave Astro and arrive on the tile.
     *
     * Src and dst are published independently, not as a pair: Astro's dot
     * draws — and [drawnRevealSrcX]/[drawnRevealSrcY] are set — whether or not a destination tile
     * exists yet, so that a frame with nowhere to fly to still shows him rather than nothing at
     * all. [drawnRevealDstX]/[drawnRevealDstY] stay `NaN` on such a frame, since no orb actually
     * flew.
     */
    internal var drawnRevealSrcX: Float = Float.NaN
        private set
    internal var drawnRevealSrcY: Float = Float.NaN
        private set
    internal var drawnRevealDstX: Float = Float.NaN
        private set
    internal var drawnRevealDstY: Float = Float.NaN
        private set

    private val revealDotPaint = Paint().apply { style = Paint.Style.FILL }
    private val revealOrbPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val revealFlashPaint = Paint().apply { style = Paint.Style.FILL }

    /**
     * The crystal reveal's flight, drawn over everything — the open shop panel included.
     *
     * Its source and destination live in different layers now. Astro stands on the store
     * walkway, inside the room; in landscape the crystal tile he throws to is inside the SHOP
     * panel, above the room and above its scrim. Neither layer can draw a line between them, so
     * this does, from above both, with both endpoints resolved into SCREEN space first: the
     * walkway point through the page transform ([viewportX], the same one [drawPilotWalker]
     * uses), and the tile from the rect the board published — wherever it drew itself.
     *
     * That published rect is in the space it was DRAWN in (see
     * [StorePageRenderer.drawUpgradeTiles]): room-local for the in-room board, screen space for
     * the panel. So the portrait rect crosses back through page 2's own origin, which is zero on
     * a phone and NOT zero on a portrait tablet, where the room is narrower than the screen.
     * Reading it raw would have put the burst half a gutter off the tile there and nowhere else.
     *
     * Astro's dot comes up with the orb rather than staying in the room: the corkscrew has to
     * read as leaving *him*, and a dot under the scrim starts the flight from something the
     * player cannot see. [StorePageRenderer.draw] stands its own copy down for the duration
     * ([HangarState.crystalRevealInFlight]) so it is never on screen twice at two alphas. The
     * mini machine and the shield aura stay behind the scrim — room props, not the moment.
     *
     * In portrait this draws exactly the pixels the store page used to, at exactly the same
     * moments: the panel layer is a no-op there, both endpoints resolve to the same points as
     * before, and nothing drawn between the room and here reaches them (the walkway is a 4-unit
     * strip at `walkwayY`, the player's own walker stands 35 units clear at the store's walk
     * target, and the chrome is drawn later still).
     */
    private fun drawCrystalReveal(canvas: Canvas, state: HangarState) {
        if (!state.crystalRevealInFlight()) return
        if (!state.astroAtSlotMachine) return

        // The walkway machine, in screen space — HangarState.slotMachineWorldX is the same point
        // StorePageRenderer.draw places the mini machine at, and the same one the walker band
        // measures its store target from, so Astro cannot drift off the machine he is standing at.
        val srcX = state.slotMachineWorldX() - viewportX(state)
        val srcY = walkwayY - 14f   // the dot: walkwayY - 8f, then the head's own -6f. Radius 5.

        // Darkened Astro dot (Astro is red 0xFFDD3333, corrupted = 50% brightness). Drawn before
        // the destination is even looked at: the room has already stood
        // its own copy down for the whole flight, keyed on the same crystalRevealInFlight() this
        // method's own early return above uses, so if the tile below turns out not to be
        // published this frame Astro must not simply blink out of a room that thinks someone else
        // is drawing him. His visibility never depends on whether there is anywhere to fly to.
        revealDotPaint.color = StoryStateManager.corruptColor(0xFFDD3333.toInt())
        canvas.drawCircle(srcX, srcY, 5f, revealDotPaint)
        drawnRevealSrcX = srcX   // seams — see their own doc
        drawnRevealSrcY = srcY

        // Room-local in portrait, screen space in landscape — see the doc above.
        val tile = storePageRenderer.crystalTileRect
        val tileOriginX = if (landscape) 0f else pageOriginX(2, state)
        // Nothing to aim at. Unreachable on the shipped path — `HangarSurfaceView`'s own gate
        // refuses to leave GLOW until the board has published a rect, and every input that could
        // shut the panel mid-flight is already refused while the reveal is flying — so this is
        // the belt to that braces: better a frame with no orb than a 30-unit white burst in the
        // corner of the screen. Astro's dot above has already drawn either way, so this early
        // return now only ever costs the orb and the burst, never the commander himself.
        if (tile.isEmpty) return
        val dstX = tile.centerX() + tileOriginX
        val dstY = tile.centerY()

        drawnRevealDstX = dstX   // seams — see their own doc
        drawnRevealDstY = dstY

        val time = System.currentTimeMillis()

        // Orb travel animation: corkscrew from Astro up to the crystal tile
        if (state.crystalRevealPhase == HangarState.CrystalRevealPhase.ORB_TRAVEL) {
            val t = (state.crystalRevealTimer / CrystalOrbPath.TRAVEL_DURATION).coerceIn(0f, 1f)
            val (orbX, orbY) = CrystalOrbPath.position(t, srcX, srcY, dstX, dstY)

            val orbPulse = 0.7f + 0.3f * sin(time / 200.0).toFloat()
            revealOrbPaint.color = CrystalPalette.MID   // icy cyan
            // Trail: fading circles along the corkscrew behind the orb
            for (i in 4 downTo 1) {
                val trailT = (t - i * 0.04f).coerceAtLeast(0f)
                val (trailX, trailY) = CrystalOrbPath.position(trailT, srcX, srcY, dstX, dstY)
                revealOrbPaint.alpha = ((1f - i / 5f) * 60).toInt()
                canvas.drawCircle(trailX, trailY, 5f - i * 0.8f, revealOrbPaint)
            }
            // Outer glow
            revealOrbPaint.alpha = (orbPulse * 100).toInt()
            canvas.drawCircle(orbX, orbY, 8f, revealOrbPaint)
            // Core
            revealOrbPaint.alpha = (orbPulse * 220).toInt()
            canvas.drawCircle(orbX, orbY, 3f, revealOrbPaint)
        }

        // Flash burst on crystal tile when orb arrives
        if (state.crystalRevealPhase == HangarState.CrystalRevealPhase.FLASH) {
            val ft = (state.crystalRevealTimer / CrystalOrbPath.FLASH_DURATION).coerceIn(0f, 1f)
            val flashRadius = 30f * ft
            revealFlashPaint.color = 0xFFFFFFFF.toInt()
            revealFlashPaint.alpha = ((1f - ft) * 255).toInt()
            canvas.drawCircle(dstX, dstY, flashRadius, revealFlashPaint)
        }
    }

    // =======================================================================
    // Shipyard page (fully functional)
    // =======================================================================

    /**
     * The width the shipyard page draws and clips page 1 against — the clip in
     * [drawShipyardPage], [drawShips], [drawLaunchRail], [drawWalkway]'s runway-light spacing,
     * and the page's own [drawRoomFrame] call all route through this one expression (or through
     * [shipyardCenterX], its halved twin), after [initialize].
     *
     * The launchpad is the one page landscape leaves full-screen, so this is
     * [RoomAnchor.pageWidth], matching the screen-centre hit tests in `HangarSurfaceView`
     * (`handleShipyardTap`, `publishShipyardFocus`, both literally `screenWidth / 2f`) rather
     * than the narrower portrait room width the bar and store pages correctly keep.
     *
     * Internal: a test seam, like [portraitLayout] above — `LandscapeRoomTest` pins it against
     * the hit-test centre without a live Canvas pass.
     */
    internal fun shipyardPageWidth(): Float = RoomAnchor.pageWidth(1, screenWidth, roomWidth, landscape)

    /**
     * Centre X every page-1 draw site centres on — the target halo, the launch rail and the
     * drawn ship centre all route through this rather than re-typing `shipyardPageWidth() / 2f`,
     * so a regression (e.g. one site quietly going back to `HangarMetrics.effectiveRoomWidth`)
     * requires editing this one accessor rather than silently diverging at a call site. Named and
     * placed after [shipyardPageWidth] the way [shipCenterY] is the existing precedent for a
     * published centre.
     *
     * `pageOriginX(1, currentPage = 1, scroll = 0) + shipyardCenterX() == screenWidth / 2` always
     * — see `LandscapeRoomTest` for the derivation, in both orientations and both gate branches.
     */
    internal fun shipyardCenterX(): Float = shipyardPageWidth() / 2f

    /**
     * SCREEN X of the selected ship as [drawShips] actually placed it this frame, or `NaN` if page
     * 1 drew no selected ship (a page off screen, the launch sequence, the codex).
     *
     * A seam, in the same spirit as [lastPanelScrimAlpha] and for the same reason: an earlier change unified
     * every page-1 centre onto [shipyardCenterX], and `LandscapeRoomTest` pins that ACCESSOR — but
     * reverting [drawShips]' own `val centerX` to re-derive a room width there leaves the accessor
     * untouched and the whole suite green (verified experimentally in the code review). Only the
     * drawn value can tell the two apart, and the drawn value lives inside a private draw method
     * behind a `canvas.translate`, which a software Canvas cannot be probed for.
     *
     * Published in SCREEN space rather than the page-local space it is computed in — the page's own
     * origin is added back — because the number it has to agree with is a screen-space one: both
     * `HangarSurfaceView.handleShipyardTap` and `publishShipyardFocus` fire at a literal
     * `screenWidth / 2f`. Publishing page-local would have been the same number at rest and would
     * have quietly stopped discriminating the moment a page origin went wrong.
     */
    internal var drawnSelectedShipX: Float = Float.NaN
        private set

    private fun drawShipyardPage(canvas: Canvas, state: HangarState, xOffset: Float) {
        canvas.save()
        canvas.translate(-xOffset, 0f)
        // Clip to page bounds so ships don't bleed into adjacent pages. The launchpad is the one
        // page landscape leaves full-screen, so it clips to its PAGE width, not the room width.
        val rw = shipyardPageWidth()
        canvas.clipRect(0f, 0f, rw, screenHeight)

        // Room frame: no ceiling (open to space), archways on both sides. The launchpad's own
        // PAGE width (rw, above), not the default room width — its right archway must land at
        // the screen edge, not mid-screen.
        drawRoomFrame(canvas, false, RoomEdge.ARCHWAY, RoomEdge.ARCHWAY, rw)

        val ship = ShipDefinitions.getShipByIndex(state.selectedShipIndex)
        val isSelectedLocked = !state.isShipUnlocked(state.selectedShipIndex)
        val shipColor = when {
            isSelectedLocked -> 0xFF555555.toInt()
            StoryStateManager.isCorrupted(persistence) -> StoryStateManager.corruptShipColor(ship?.color ?: 0xFF00AAFF.toInt())
            else -> ship?.color ?: 0xFF00AAFF.toInt()
        }

        // Target zone indicator (energy field at the launchpad's own centre — we're drawing in
        // this page's local space per the translate above, and the launchpad is the one page
        // landscape leaves full-screen, so shipyardCenterX() here is the page's centre, not the
        // room's; centring on anything narrower would drift off the screen-centre hit tests)
        // Brightness follows chevron logic: brightens on approach, pulses when in halo zone
        val dragProximity = if (state.isDraggingShip) {
            val dist = kotlin.math.abs(state.shipDragY - shipCenterY)
            val maxDist = state.shipRestingY - shipCenterY
            (1f - (dist / maxDist).coerceIn(0f, 1f))
        } else 0.2f
        val inHaloZone = state.isDraggingShip &&
            kotlin.math.abs(state.shipDragY - shipCenterY) < 60f
        drawEnergyField(canvas, shipColor, shipyardCenterX(), dragProximity, inHaloZone)

        // Launch rail chevrons between resting position and target zone
        drawLaunchRail(canvas, state, shipColor)

        // Ships below walkway
        drawShips(canvas, state)

        canvas.restore()
    }

    // centerX is supplied by the caller rather than derived here: this is drawn both inside
    // drawShipyardPage's room-local translate (needs the room centre) and directly from
    // drawLaunchSequence in plain screen space (needs the screen centre), and the function has
    // no way to tell those two contexts apart on its own.
    private fun drawEnergyField(canvas: Canvas, shipColor: Int, centerX: Float, intensity: Float = 1f, inHaloZone: Boolean = false) {
        val shipY = shipCenterY
        val time = System.currentTimeMillis()

        val outerAlpha: Float
        val innerAlpha: Float

        if (inHaloZone) {
            // In halo zone — pulse at full intensity, "ready to launch"
            outerAlpha = 0.5f + 0.3f * kotlin.math.sin(time / 600.0).toFloat()
            innerAlpha = 0.4f + 0.25f * kotlin.math.sin(time / 400.0 + 1.0).toFloat()
        } else {
            // Approaching or idle — steady brightness tracks drag progress, minimal pulse
            outerAlpha = 0.1f + 0.5f * intensity + 0.05f * kotlin.math.sin(time / 800.0).toFloat()
            innerAlpha = 0.08f + 0.4f * intensity + 0.04f * kotlin.math.sin(time / 500.0 + 1.0).toFloat()
        }

        // Outer ring
        val outerPaint = Paint().apply {
            color = shipColor
            alpha = (outerAlpha * 255).toInt().coerceIn(0, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        canvas.drawCircle(centerX, shipY, 55f, outerPaint)

        // Inner ring
        val innerPaint = Paint().apply {
            color = shipColor
            alpha = (innerAlpha * 255).toInt().coerceIn(0, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }
        canvas.drawCircle(centerX, shipY, 42f, innerPaint)
    }

    private fun drawLaunchRail(canvas: Canvas, state: HangarState, shipColor: Int) {
        // Called only from drawShipyardPage's room-local translate. The launchpad is the one page
        // landscape leaves full-screen, so centre on the PAGE centre, not the room centre —
        // matching the screen-centre hit tests in HangarSurfaceView.handleShipyardTap.
        val centerX = shipyardCenterX()
        val targetZoneY = shipCenterY
        val restingY = state.shipRestingY

        // Brightness tied to drag progress — faint at rest, bright approaching halo, dim in halo
        val dragFade = shipDragFade(state)
        val dragProgress = 1f - dragFade  // 0 at rest, 1 when text fully faded
        // Dim when ship enters halo zone
        val distToHalo = kotlin.math.abs(state.shipDragY - targetZoneY)
        val haloDim = if (distToHalo < 60f) (distToHalo / 60f).coerceIn(0f, 1f) else 1f
        val baseAlpha = (200f * dragProgress * haloDim).toInt().coerceIn(0, 220)

        val chevronPaint = Paint().apply {
            color = shipColor
            alpha = baseAlpha
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        val chevronCount = 6
        val totalDistance = restingY - targetZoneY - 60f
        val spacing = totalDistance / (chevronCount + 1)
        val chevronWidth = 12f

        for (i in 1..chevronCount) {
            val y = restingY - 30f - i * spacing
            canvas.drawLine(centerX - chevronWidth, y + 6f, centerX, y, chevronPaint)
            canvas.drawLine(centerX, y, centerX + chevronWidth, y + 6f, chevronPaint)
        }
    }

    /**
     * How opaque a ship at page-local [shipX] is, given the page's clip at 0 and [pageWidth]:
     * 1 well inside, easing to 0 at the moment any part of it would cross.
     *
     * Owner, 2026-09-20: a ship sliced in half by the page's clip edge during a swipe gives away
     * that the walkway is three pages rather than one room. Fading it out instead keeps the seam
     * invisible, and fading rather than cutting keeps the no-instant-disappearance rule — the
     * player watches it go rather than finding it gone.
     *
     * **Portrait returns 1 unconditionally**, so this cannot move a single pixel there. That is
     * not incidental: portrait shows the same sliver today (its third ship out sits about 68 units
     * off the page edge, drawn at half alpha, about 22 units of it visible), and the owner's
     * standing rule for this whole layer is that portrait does not change. The gate is the one
     * line to lift if the owner ever wants it there too.
     *
     * The fade spans one ship width — [shipHalfExtent] * 2 — and the ship is already invisible
     * when its leading edge reaches the clip, not when its centre does.
     */
    internal fun shipEdgeFade(shipX: Float, pageWidth: Float): Float {
        if (!landscape) return 1f
        val margin = shipHalfExtent
        val span = margin * 2f
        if (span <= 0f) return 1f
        val clearance = minOf(shipX - margin, pageWidth - margin - shipX)
        return (clearance / span).coerceIn(0f, 1f)
    }

    /**
     * Half the width of the widest thing the carousel draws at a ship's own x — measured, not
     * guessed, because the labels are wider than the ship and the widest of them decides when a
     * ship has to be gone by.
     *
     * Measured once in [initialize] rather than per frame: every input is fixed at build time —
     * the four text sizes are literals in [drawShips], and ship and weapon display names are
     * static table lookups (`ShipDefinitions.getShipName` reads a `val` list; nothing renames a
     * ship at runtime, corruption included, which recolours). The typeface is fixed too, since
     * `FontManager` initialises before any renderer is constructed.
     *
     * One extent for the whole carousel rather than one per ship: the ships then all vanish the
     * same distance from the edge, which is what reads as deliberate. The cost is that a ship with
     * a short name goes very slightly earlier than it strictly must.
     */
    private var shipHalfExtent = 0f

    private fun measureShipHalfExtent(): Float {
        // Floored, not purely measured, and the floor is what normally wins.
        //
        // Robolectric's Paint.measureText returns 0, so a purely measured extent collapses to the
        // ship glyph — 25 units, a tenth of the real thing — and every test of the fade would be
        // exercising a case no device ever runs. The floor covers the widest label the tables
        // actually hold: "HOMING MISSILES", 15 characters at the selected ship's 22px weapon size,
        // measures about 171 units in Exo 2 (~0.52em per character). 180 gives that a little room
        // and is the number both a device and a test see.
        //
        // The measurement below then only matters if a future ship or weapon name is LONGER than
        // anything shipping today, which is the case a hardcoded constant would get silently
        // wrong. `GameConfig.SHIP_BASE_SIZE * 2` is in the same max as a bound on the glyph — it
        // is smaller than the floor on every real profile, and is there so the expression is
        // honest about what it covers rather than assuming the text always wins.
        var widest = maxOf(MIN_SHIP_EXTENT, GameConfig.SHIP_BASE_SIZE * 2f)
        val sizes = floatArrayOf(24f, 16f) // selected, peek — see drawShips
        val weaponSizes = floatArrayOf(22f, 14f)
        for (i in 0 until ShipDefinitions.getShipCount()) {
            val ship = ShipDefinitions.getShipByIndex(i) ?: continue
            for (s in sizes) {
                textPaint.textSize = s
                widest = maxOf(widest, textPaint.measureText(ShipDefinitions.getShipName(i)))
            }
            for (s in weaponSizes) {
                textPaint.textSize = s
                widest = maxOf(
                    widest,
                    textPaint.measureText(WeaponDefinitions.getWeaponDisplayName(ship.startingWeaponId))
                )
            }
            widest = maxOf(widest, costPaint.measureText(GameConfig.formatYen(ship.cost)))
        }
        widest = maxOf(widest, costPaint.measureText("LOCKED"))
        textPaint.textSize = 24f // drawShips sets its own before every draw; restore the default
        return widest / 2f
    }

    private fun drawShips(canvas: Canvas, state: HangarState) {
        // Called only from drawShipyardPage's room-local translate. The launchpad is the one page
        // landscape leaves full-screen, so centre — and cull ship visibility below — against the
        // PAGE width, not the room width, or the drawn ship drifts off the screen-centre hit tests
        // in HangarSurfaceView.handleShipyardTap.
        val rw = shipyardPageWidth()
        val centerX = shipyardCenterX()

        val selectedPilot = PilotDefinitions.getPilotByIndex(state.selectedPilotIndex)

        // Build visible ship list — dead ships hidden in corruption
        val isCorrupted = StoryStateManager.isCorrupted(persistence)
        val visibleShipIndices = (0 until ShipDefinitions.getShipCount()).filter { i ->
            val s = ShipDefinitions.getShipByIndex(i) ?: return@filter false
            if (isCorrupted && StoryStateManager.isShipDead(persistence, s.id)) return@filter false
            // Intro cinematic: hide the carousel peek — only the starting ship (Scout) shows.
            if (state.introCinematic && i != state.selectedShipIndex) return@filter false
            true
        }

        // Find the visual position of the selected ship in the filtered list
        val selectedVisualPos = visibleShipIndices.indexOf(state.selectedShipIndex)
            .let { if (it < 0) 0 else it }

        // Global drag fade — text starts fading when selected ship starts moving,
        // fully gone halfway between resting position and walkway
        val dragFade = shipDragFade(state)

        for ((visualIndex, i) in visibleShipIndices.withIndex()) {
            val ship = ShipDefinitions.getShipByIndex(i) ?: continue

            val offsetFromSelected = visualIndex - selectedVisualPos
            val shipX = centerX + offsetFromSelected * shipSpacing + state.shipScrollOffset

            // Only draw if on screen (room-local space, so cull against the launchpad's own page
            // width, `rw` above, not the screen)
            if (shipX < -100f || shipX > rw + 100f) continue

            // ...and in landscape, fade a ship out before the page's clip edge can slice it.
            // Zero by the time any part of it would cross, so nothing is ever drawn half-cut.
            val edgeFade = shipEdgeFade(shipX, rw)
            if (edgeFade <= 0f) continue

            val isUnlocked = state.isShipUnlocked(i)
            val isSelected = (i == state.selectedShipIndex)
            // Seam — see drawnSelectedShipX's own doc. Page-local `shipX` back into screen space
            // via this page's own origin, which is exactly what drawShipyardPage's translate did.
            if (isSelected) drawnSelectedShipX = shipX + pageOriginX(1, state)

            // Selected ship follows drag Y; peek ships stay at resting Y
            val shipY = if (isSelected) state.shipDragY else state.shipRestingY

            // Dim non-selected ships (peek effect), and fade whatever is near the page edge
            val peekAlpha = (if (isSelected) 1f else 0.5f * dragFade) * edgeFade

            // Non-selected ships fade to grey as text fades; player ship keeps its color
            val greyColor = 0xFF555555.toInt()
            val rawShipColor = if (StoryStateManager.isCorrupted(persistence)) StoryStateManager.corruptShipColor(ship.color) else ship.color
            val baseShipColor = if (isUnlocked) rawShipColor else greyColor
            val displayColor = if (isSelected) baseShipColor
                else lerpColor(baseShipColor, greyColor, 1f - dragFade)

            drawShipAtPosition(canvas, shipX, shipY, ship, selectedPilot, isUnlocked, isSelected, peekAlpha, displayColor)

            // Ship name and weapon — all text fades together as selected ship is dragged
            if (dragFade > 0f) {
                // peekAlpha already carries edgeFade; the selected ship's own text needs it applied
                val combinedAlpha = if (isSelected) dragFade * edgeFade else peekAlpha

                textPaint.textSize = if (isSelected) 24f else 16f
                textPaint.color = if (isSelected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
                textPaint.alpha = (combinedAlpha * 255).toInt()
                val displayName = ShipDefinitions.getShipName(i)
                canvas.drawText(displayName, shipX, shipY + 85f, textPaint)

                // Show weapon name only for unlocked ships or the next unlockable one
                val showWeapon = isUnlocked || (state.canUnlockShip(i) && !isUnlocked)
                val weaponText = if (showWeapon) {
                    WeaponDefinitions.getWeaponDisplayName(ship.startingWeaponId)
                } else "???"
                textPaint.textSize = if (isSelected) 22f else 14f
                textPaint.color = if (isSelected) 0xFF88CCFF.toInt() else 0xFF666688.toInt()
                textPaint.alpha = (combinedAlpha * 255).toInt()
                canvas.drawText(weaponText, shipX, shipY + 107f, textPaint)
            }

            // Swipe-up hint chevrons below ship (only at resting position, unlocked)
            if (isSelected && isUnlocked && !state.isDraggingShip &&
                kotlin.math.abs(state.shipDragY - state.shipRestingY) < 2f) {
                val time = System.currentTimeMillis()
                val bob = (sin(time / 400.0) * 3f).toFloat()
                val hintAlpha = (0.3f + 0.2f * sin(time / 600.0)).toFloat() * edgeFade
                val hintPaint = Paint().apply {
                    color = 0xFFFFFFFF.toInt()
                    alpha = (hintAlpha * 255).toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
                val hintY = state.shipRestingY + 155f + bob
                val chevSize = 20f
                // Two upward chevrons
                canvas.drawLine(shipX - chevSize, hintY + 8f, shipX, hintY - 4f, hintPaint)
                canvas.drawLine(shipX, hintY - 4f, shipX + chevSize, hintY + 8f, hintPaint)
                canvas.drawLine(shipX - chevSize, hintY + 22f, shipX, hintY + 10f, hintPaint)
                canvas.drawLine(shipX, hintY + 10f, shipX + chevSize, hintY + 22f, hintPaint)
            }

            // Cost or "LOCKED" — also fades with drag
            if (!isUnlocked && dragFade > 0f) {
                if (state.canUnlockShip(i)) {
                    costPaint.color = 0xFFFFD700.toInt()
                    costPaint.alpha = (peekAlpha * 255).toInt()
                    canvas.drawText(GameConfig.formatYen(ship.cost), shipX, shipY - 50f, costPaint)
                } else {
                    costPaint.color = 0xFF666666.toInt()
                    costPaint.alpha = (peekAlpha * 255).toInt()
                    canvas.drawText("LOCKED", shipX, shipY - 50f, costPaint)
                }
            }
        }

        // Reset alpha
        textPaint.alpha = 255
        costPaint.alpha = 255
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val r = ((from shr 16 and 0xFF) + ((to shr 16 and 0xFF) - (from shr 16 and 0xFF)) * t).toInt()
        val g = ((from shr 8 and 0xFF) + ((to shr 8 and 0xFF) - (from shr 8 and 0xFF)) * t).toInt()
        val b = ((from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun drawShipAtPosition(
        canvas: Canvas,
        x: Float,
        y: Float,
        ship: com.astroloop.game.data.ShipDef,
        @Suppress("UNUSED_PARAMETER") pilot: com.astroloop.game.data.PilotDef?,
        unlocked: Boolean,
        @Suppress("UNUSED_PARAMETER") selected: Boolean,
        peekAlpha: Float = 1f,
        displayColor: Int = 0
    ) {
        val baseAlpha = if (unlocked) 1f else 0.7f
        val color = if (displayColor != 0) displayColor
            else if (unlocked) ship.color else 0xFF555555.toInt()
        ShipRenderer.drawShip(
            canvas = canvas,
            shapeRenderer = shapeRenderer,
            x = x,
            y = y,
            rotation = (-Math.PI / 2).toFloat(),
            size = GameConfig.SHIP_BASE_SIZE,
            shipColor = color,
            pilotColor = color,
            startingWeaponId = ship.startingWeaponId,
            alpha = baseAlpha * peekAlpha
        )
    }

    // =======================================================================
    // Room frame (ceiling, walls, archways)
    // =======================================================================

    private enum class RoomEdge { SOLID, ARCHWAY }

    private fun drawRoomFrame(
        canvas: Canvas, hasCeiling: Boolean, leftEdge: RoomEdge, rightEdge: RoomEdge,
        // Defaults to the ROOM width: the shared lambda handed to the bar and store page
        // renderers calls this with the 4-arg form, and those two rooms correctly keep their
        // portrait room width in landscape. Only drawShipyardPage passes a width explicitly —
        // the launchpad's own PAGE width, since it alone is full-screen in landscape.
        width: Float = HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth)
    ) {
        // Called from inside each page's translated space, so this draws THAT room's ceiling
        // and walls — it must span the room (or, for the launchpad, the page), not the screen,
        // or neighbouring rooms overlap.
        val rw = width

        val wallPaint = Paint().apply {
            color = 0xFF2A2A30.toInt()
            style = Paint.Style.FILL
        }
        val highlightPaint = Paint().apply {
            color = 0xFF3A3A40.toInt()
            style = Paint.Style.FILL
        }

        // Ceiling line
        if (hasCeiling) {
            canvas.drawRect(0f, ceilingY, rw, ceilingY + 3f, wallPaint)
            canvas.drawRect(0f, ceilingY, rw, ceilingY + 1f, highlightPaint)
        }

        val wallWidth = 5f
        val archOpening = 30f  // Height of archway opening from walkway upward

        // Left edge
        when (leftEdge) {
            RoomEdge.SOLID -> {
                canvas.drawRect(0f, ceilingY, wallWidth, walkwayY, wallPaint)
            }
            RoomEdge.ARCHWAY -> {
                if (hasCeiling) {
                    // Wall from ceiling down to archway opening
                    canvas.drawRect(0f, ceilingY, wallWidth, walkwayY - archOpening, wallPaint)
                }
                // Open-air rooms: no wall, archway is fully open
            }
        }

        // Right edge
        when (rightEdge) {
            RoomEdge.SOLID -> {
                canvas.drawRect(rw - wallWidth, ceilingY, rw, walkwayY, wallPaint)
            }
            RoomEdge.ARCHWAY -> {
                if (hasCeiling) {
                    canvas.drawRect(rw - wallWidth, ceilingY, rw, walkwayY - archOpening, wallPaint)
                }
            }
        }
    }

    // =======================================================================
    // Walkway and pilot walker
    // =======================================================================

    private fun drawWalkway(canvas: Canvas, state: HangarState) {
        // Global screen-space layer drawn over the page content. The hangar building is only
        // three rooms wide, so on a wide screen a full-width walkway would extend past the last
        // room and hang in open space. Clip the walkway (and its edge highlight, which must
        // match or it would float past the walkway itself) to the building.
        val (buildingLeft, buildingRight) = buildingExtent(state)

        if (buildingRight > buildingLeft) {
            val walkwayPaint = Paint().apply {
                color = 0xFF2A2A30.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawRect(buildingLeft, walkwayY, buildingRight, walkwayY + 4f, walkwayPaint)

            // Subtle edge highlight
            val highlightPaint = Paint().apply {
                color = 0xFF3A3A40.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawRect(buildingLeft, walkwayY, buildingRight, walkwayY + 1f, highlightPaint)
        }

        // Runway lights along walkway (shipyard page only)
        if (state.currentPage == 1 || state.phase == HangarPhase.LAUNCHING) {
            val lightPaint = Paint().apply { style = Paint.Style.FILL }
            val lightCount = 10
            // Lights belong to the launchpad room (page index 1), not the full screen — space
            // and position them across that room using the same page transform drawPageContent
            // uses to place the shipyard page itself.
            val launchpadLeft = pageOriginX(1, state)
            val spacing = shipyardPageWidth() / (lightCount + 1)
            val time = System.currentTimeMillis()

            // Check if ship is in the halo zone
            val shipInHalo = state.isDraggingShip &&
                    kotlin.math.abs(state.shipDragY - shipCenterY) < 60f
            val launchActive = state.phase == HangarPhase.LAUNCHING

            for (i in 1..lightCount) {
                val lx = launchpadLeft + spacing * i
                val ly = walkwayY + 2f

                if (shipInHalo || launchActive) {
                    // Blinking red
                    val pulse = (0.5f + 0.5f * sin(time / 120.0 + i * 0.5)).toFloat()
                    lightPaint.color = 0xFFFF2200.toInt()
                    lightPaint.alpha = (pulse * 200).toInt().coerceIn(0, 255)
                } else {
                    // Steady white/yellow
                    lightPaint.color = 0xFFCCAA44.toInt()
                    lightPaint.alpha = 80
                }
                canvas.drawCircle(lx, ly, 2f, lightPaint)
            }
        }
    }

    private fun drawCharacter(canvas: Canvas, x: Float, y: Float, color: Int, walking: Boolean, armRaiseTimer: Float = 0f, bandanaColor: Int? = null) {
        val dotPaint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val limbPaint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        // Body dot
        canvas.drawCircle(x, y - 6f, 5f, dotPaint)

        // Bandana band across the forehead (top of the 5px body dot) — same 2px weight
        // as the limbs, chord-fit to the head so it reads as a band, not a floating dash.
        if (bandanaColor != null) {
            val bandPaint = Paint().apply {
                this.color = bandanaColor
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawLine(x - 4.1f, y - 8.4f, x + 4.1f, y - 8.4f, bandPaint)
        }

        val time = System.currentTimeMillis()

        if (walking) {
            val legPhase = (time / 100) % 4
            val armPhase = (time / 100 + 2) % 4

            // Legs
            when (legPhase.toInt()) {
                0 -> {
                    canvas.drawLine(x - 2f, y, x - 4f, y + 5f, limbPaint)
                    canvas.drawLine(x + 2f, y, x + 4f, y + 5f, limbPaint)
                }
                1 -> {
                    canvas.drawLine(x - 2f, y, x - 5f, y + 4f, limbPaint)
                    canvas.drawLine(x + 2f, y, x + 2f, y + 5f, limbPaint)
                }
                2 -> {
                    canvas.drawLine(x - 2f, y, x - 2f, y + 5f, limbPaint)
                    canvas.drawLine(x + 2f, y, x + 5f, y + 4f, limbPaint)
                }
                3 -> {
                    canvas.drawLine(x - 2f, y, x - 4f, y + 5f, limbPaint)
                    canvas.drawLine(x + 2f, y, x + 4f, y + 5f, limbPaint)
                }
            }

            // Arms (opposite phase to legs)
            when (armPhase.toInt()) {
                0 -> {
                    canvas.drawLine(x - 4f, y - 5f, x - 7f, y - 1f, limbPaint)
                    canvas.drawLine(x + 4f, y - 5f, x + 7f, y - 1f, limbPaint)
                }
                1 -> {
                    canvas.drawLine(x - 4f, y - 5f, x - 8f, y - 3f, limbPaint)
                    canvas.drawLine(x + 4f, y - 5f, x + 5f, y - 1f, limbPaint)
                }
                2 -> {
                    canvas.drawLine(x - 4f, y - 5f, x - 5f, y - 1f, limbPaint)
                    canvas.drawLine(x + 4f, y - 5f, x + 8f, y - 3f, limbPaint)
                }
                3 -> {
                    canvas.drawLine(x - 4f, y - 5f, x - 7f, y - 1f, limbPaint)
                    canvas.drawLine(x + 4f, y - 5f, x + 7f, y - 1f, limbPaint)
                }
            }
        } else {
            // Standing still
            canvas.drawLine(x - 2f, y, x - 3f, y + 5f, limbPaint)  // left leg
            canvas.drawLine(x + 2f, y, x + 3f, y + 5f, limbPaint)  // right leg
            if (armRaiseTimer > 0f) {
                // One arm raised (grabbing beer)
                canvas.drawLine(x - 4f, y - 5f, x - 5f, y - 1f, limbPaint)  // left arm normal
                canvas.drawLine(x + 4f, y - 5f, x + 7f, y - 10f, limbPaint) // right arm raised
            } else {
                canvas.drawLine(x - 4f, y - 5f, x - 5f, y - 1f, limbPaint)  // left arm
                canvas.drawLine(x + 4f, y - 5f, x + 5f, y - 1f, limbPaint)  // right arm
            }
        }
    }

    private fun drawPilotWalker(canvas: Canvas, state: HangarState) {
        val pilot = state.getSelectedPilot() ?: return
        // Pilot has a world-space position; subtract viewport to get screen position.
        // Same viewport transform drawPageContent uses, so the walker lines up with the room.
        val walkerX = state.pilotX - viewportX(state)
        val walkerY = walkwayY - 8f
        // Only draw if on screen
        if (walkerX > -20f && walkerX < screenWidth + 20f) {
            val color = if (StoryStateManager.isCorrupted(persistence)) StoryStateManager.corruptColor(pilot.color) else pilot.color
            val bandana = if (persistence.hasBandana(pilot.id)) BandanaDefinitions.accentColor(pilot.id) else null
            drawCharacter(canvas, walkerX, walkerY, color, state.pilotWalking, 0f, bandana)
        }
    }

    private fun drawNPCWalkers(canvas: Canvas, state: HangarState) {
        // Called inside BarPageRenderer's room-local translated canvas, so this must map the
        // walker's normalized x into room width, not screen width, or roaming crew stray past
        // the counter on wide screens (the same 0.1/0.8 band stoolNormalizedX maps into).
        val rw = HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth)
        val margin = rw * 0.1f
        val walkableWidth = rw - 2 * margin
        val corrupted = StoryStateManager.isCorrupted(persistence)

        for (npc in state.npcWalkers) {
            if (npc.seated) continue   // drawn at the counter by BarPageRenderer
            val npcX = margin + npc.x * walkableWidth
            val npcY = walkwayY - 8f
            val color = if (corrupted) StoryStateManager.corruptColor(npc.color) else npc.color
            val npcPilot = PilotDefinitions.getPilotByIndex(npc.pilotIndex)
            val bandana = if (npcPilot != null && persistence.hasBandana(npcPilot.id))
                BandanaDefinitions.accentColor(npcPilot.id) else null
            drawCharacter(canvas, npcX, npcY, color, npc.walking, npc.armRaiseTimer, bandana)
        }
    }

    /**
     * Ship drag-fade: 1.0 at the resting position, fading to 0.0 as the ship is dragged up to
     * launch. Internal because the focus ring rides the same curve, and it is drawn from the view.
     */
    internal fun shipDragFade(state: HangarState): Float =
        if (state.shipRestingY == 0f) 1f
        else HangarGestures.shipDragFade(
            state.shipDragY, state.shipRestingY, (state.shipRestingY + walkwayY) / 2f
        )

    private fun drawIntroTitle(canvas: Canvas, state: HangarState) {
        // Fade in slowly (~3s) after arriving at the launchpad...
        val fadeIn = (state.introTitleTimer / 3.0f).coerceIn(0f, 1f)
        // ...then fade out with the ship drag — but half as fast as the yen counter, so the
        // title lingers. The yen-counter fade completes halfway to the walkway; doubling the
        // drag distance (fadeEnd = walkwayY) stretches the title fade over twice the range.
        val dragFade = if (state.shipRestingY == 0f) 1f
            else HangarGestures.shipDragFade(state.shipDragY, state.shipRestingY, walkwayY)

        val alpha = (fadeIn * dragFade * 255f).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        introTitlePaint.textSize = (screenWidth * 0.11f).coerceIn(48f, 96f)
        introTitlePaint.alpha = alpha
        canvas.drawText("ASTRO LOOP", screenWidth / 2f, screenHeight * 0.30f, introTitlePaint)
    }

    // =======================================================================
    // Page indicator dots
    // =======================================================================

    private fun drawPageIndicator(canvas: Canvas, state: HangarState) {
        val labels = listOf("CREW", "LAUNCH", "SHOP")
        val centerX = screenWidth / 2
        // The row the tap band in [HangarMetrics.navBandTop] is measured from — one definition,
        // so the labels and the band that catches them cannot drift apart.
        val labelY = HangarMetrics.navLabelY(screenHeight)
        val spacing = layout.content.width * 0.25f

        for (i in labels.indices) {
            val dx = (i - 1) * spacing
            textPaint.textSize = 20f
            textPaint.color = if (i == state.currentPage) 0xFFFFFFFF.toInt() else 0xFF444444.toInt()
            canvas.drawText("[${labels[i]}]", centerX + dx, labelY, textPaint)
        }
    }

    // =======================================================================
    // Yen counter (shared)
    // =======================================================================

    private fun drawYenCounter(canvas: Canvas, state: HangarState, alpha: Float = 1f) {
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.RIGHT
        val baseColor = 0xFFD700  // Gold RGB without alpha
        textPaint.color = ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or baseColor
        // Anchor to the inset-safe area (edge-to-edge devices push the display cutout
        // into the top-right corner where the yen lives — raw screenWidth/50f put it
        // behind the notch). safe == full on non-cutout devices, so this is a no-op there.
        //
        // The cutout is not the only thing in that corner. The display's own rounded corner is
        // reported separately from the insets and used to be ignored, which was invisible in
        // portrait — where the cutout pushes safe.top down past the arc — and clipped the
        // counter's right-hand end everywhere else: landscape, and upside-down portrait, both of
        // which put safe.top at 0 and the glyphs at y=24 in the bare corner (owner, 2026-09-20,
        // Pixel 9 Pro). `cornerSafeRight` binds on the glyphs' own top, not the baseline, and
        // returns full.right untouched wherever the arc does not reach them — so right-way-up
        // portrait keeps the exact x it has always had, and nothing moves on a square-cornered
        // display or below API 31.
        val baselineY = layout.safe.top + 50f
        val edge = minOf(layout.safe.right, layout.cornerSafeRight(baselineY + textPaint.ascent()))
        drawnYenRight = edge - 20f // seam — see this field's own doc
        canvas.drawText(GameConfig.formatYen(state.displayedYen), drawnYenRight, baselineY, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = 0xFFFFFFFF.toInt()
    }

    // =======================================================================
    // Ship tap rect
    // =======================================================================

    fun getShipTapRect(): RectF {
        val centerX = screenWidth / 2
        val centerY = shipCenterY
        val size = 60f
        return RectF(centerX - size, centerY - size, centerX + size, centerY + size)
    }

    // =======================================================================
    // Pilot grid index
    // =======================================================================

    /**
     * Index of the pilot card under a tap, or null.
     *
     * [roomX] is ROOM-local (see `RoomAnchor.toRoomX`) because the grid is drawn room-local by
     * BarPageRenderer; [y] is screen space, which room tiling never touches. Below the gate the
     * two spaces coincide and the caller passes the raw touch X, exactly as before.
     */
    fun getPilotGridIndex(roomX: Float, y: Float): Int? {
        // The room draws no grid in landscape — the CREW panel does, in screen space, and
        // HangarSurfaceView.handlePanelTap hit-tests the rects it published. Without this the
        // room would keep a hit test for cards that are not there: a tap on the empty upper half
        // of the crew room would select a pilot nobody can see.
        if (landscape) return null
        return GridGeometry.pilotIndexAt(
            GridGeometry.pilotGridBounds(layout.content, roomWidth, screenWidth),
            roomX, y, PilotDefinitions.getPilotCount()
        )
    }

    // =======================================================================
    // Launch sequence
    // =======================================================================

    private fun drawLaunchSequence(canvas: Canvas, state: HangarState) {
        val progress = state.launchProgress
        val centerX = screenWidth / 2
        val shipY = shipCenterY

        val selectedShip = ShipDefinitions.getShipByIndex(state.selectedShipIndex)
        val selectedPilot = PilotDefinitions.getPilotByIndex(state.selectedPilotIndex)
        val isCorruptedLaunch = StoryStateManager.isCorrupted(persistence)
        val launchColor = if (isCorruptedLaunch) Boss.CORRUPTION_COLOR else selectedShip?.color ?: 0xFF00AAFF.toInt()
        val launchAccent = if (isCorruptedLaunch) Boss.CORRUPTION_COLOR else selectedShip?.color ?: 0xFF00AAFF.toInt()
        // The pilot's own colour follows the same rule as the walker (drawPilotWalker) and the bar
        // crew (drawNPCWalkers): corruption darkens it to 50%, it does not go boss-red. The hull
        // does go boss-red, via launchColor above — pilot and ship corrupt differently, and this is
        // the one place the player sees both at once. Without it the figure that has been walking
        // the hangar in corrupted colour snaps back to full brightness the instant it jumps for the
        // cockpit, and the cockpit dot stays wrong for the rest of the launch.
        val launchPilotColor = selectedPilot?.color?.let {
            if (isCorruptedLaunch) StoryStateManager.corruptColor(it) else it
        } ?: 0xFFFFFFFF.toInt()

        when {
            // Phase 1: Pilot boards (0.0 - 0.20, ~0.8s)
            progress < 0.20f -> {
                val phase = progress / 0.20f

                // Energy field active
                drawEnergyField(canvas, launchColor, centerX)

                // Ship without pilot color
                if (selectedShip != null) {
                    ShipRenderer.drawShip(
                        canvas = canvas, shapeRenderer = shapeRenderer,
                        x = centerX, y = shipY,
                        rotation = (-Math.PI / 2).toFloat(),
                        size = GameConfig.SHIP_BASE_SIZE,
                        shipColor = launchColor,
                        pilotColor = launchColor,  // Hidden
                        startingWeaponId = selectedShip.startingWeaponId,
                        alpha = 1f
                    )
                }

                // Pilot jumping from walkway to cockpit
                if (selectedPilot != null) {
                    val startY = walkwayY - 8f
                    val endY = shipY
                    val limbPaint = Paint().apply {
                        color = launchPilotColor
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                    }
                    val pilotPaint = Paint().apply {
                        color = launchPilotColor
                        style = Paint.Style.FILL
                    }

                    val jumpY = if (phase < 0.5f) {
                        val t = phase / 0.5f
                        startY - t * (startY - endY + 60f)
                    } else {
                        val t = (phase - 0.5f) / 0.5f
                        (endY - 60f) + t * 60f
                    }

                    if (phase < 0.80f) {
                        // Fade out as pilot approaches cockpit
                        val pilotAlpha = if (phase > 0.60f) ((0.80f - phase) / 0.20f) else 1f
                        pilotPaint.alpha = (pilotAlpha * 255).toInt()
                        limbPaint.alpha = (pilotAlpha * 255).toInt()
                        canvas.drawCircle(centerX, jumpY - 6f, 5f, pilotPaint)
                        // Arms up!
                        canvas.drawLine(centerX - 4f, jumpY - 8f, centerX - 7f, jumpY - 16f, limbPaint)
                        canvas.drawLine(centerX + 4f, jumpY - 8f, centerX + 7f, jumpY - 16f, limbPaint)
                        // Legs tucked
                        canvas.drawLine(centerX - 2f, jumpY, centerX - 3f, jumpY + 3f, limbPaint)
                        canvas.drawLine(centerX + 2f, jumpY, centerX + 3f, jumpY + 3f, limbPaint)
                    }
                }
            }

            // Phase 2: Engine charge (0.20 - 0.375, ~0.7s)
            progress < 0.375f -> {
                val phase = (progress - 0.20f) / 0.175f

                // Energy field intensifying then fading
                val fieldIntensity = 1f + phase * 2f
                val fieldAlpha = 1f - phase
                drawEnergyField(canvas, launchColor, centerX, fieldIntensity * fieldAlpha)

                // Ship now has pilot color
                if (selectedShip != null) {
                    ShipRenderer.drawShip(
                        canvas = canvas, shapeRenderer = shapeRenderer,
                        x = centerX, y = shipY,
                        rotation = (-Math.PI / 2).toFloat(),
                        size = GameConfig.SHIP_BASE_SIZE,
                        shipColor = launchColor,
                        pilotColor = launchPilotColor,
                        startingWeaponId = selectedShip.startingWeaponId,
                        alpha = 1f
                    )
                }

                // Engine glow behind ship
                val glowPaint = Paint().apply {
                    color = if (isCorruptedLaunch) 0xFFCC3300.toInt() else 0xFFFFAA00.toInt()
                    alpha = (phase * 200).toInt()
                }
                canvas.drawCircle(centerX, shipY + 30f, 6f + phase * 15f, glowPaint)

                // Neon accent pulses
                val accentPaint = Paint().apply {
                    color = launchAccent
                    alpha = ((0.5f + 0.5f * sin(System.currentTimeMillis() / 80.0)) * 255).toInt()
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(centerX - 35f, shipY, 3f, accentPaint)
                canvas.drawCircle(centerX + 35f, shipY, 3f, accentPaint)
            }

            // Phase 3: Liftoff (0.375 - 0.55, ~0.7s) — world moves down, ship stays
            progress < 0.55f -> {
                val phase = (progress - 0.375f) / 0.175f
                val worldDropY = phase * (launchSpan(screenWidth, screenHeight) * 0.8f)

                canvas.save()
                val shakeIntensity = 6f + phase * 10f
                canvas.translate(
                    (Random.nextFloat() - 0.5f) * shakeIntensity,
                    (Random.nextFloat() - 0.5f) * shakeIntensity
                )

                // Massive thruster flare below stationary ship
                val thrusterSize = 20f + phase * 120f
                val flamePaint = Paint().apply {
                    color = if (isCorruptedLaunch) 0xFFCC3300.toInt() else 0xFFFF6600.toInt()
                    alpha = 220
                }
                val flameY = shipY + 30f
                canvas.drawOval(
                    RectF(centerX - thrusterSize * 0.4f, flameY,
                        centerX + thrusterSize * 0.4f, flameY + thrusterSize), flamePaint)

                val corePaint = Paint().apply {
                    color = if (isCorruptedLaunch) 0xFFFF8888.toInt() else 0xFFFFFF88.toInt()
                    alpha = 240
                }
                canvas.drawOval(
                    RectF(centerX - thrusterSize * 0.15f, flameY,
                        centerX + thrusterSize * 0.15f, flameY + thrusterSize * 0.6f), corePaint)

                // Ship stays at center
                if (selectedShip != null) {
                    ShipRenderer.drawShip(
                        canvas = canvas, shapeRenderer = shapeRenderer,
                        x = centerX, y = shipY,
                        rotation = (-Math.PI / 2).toFloat(),
                        size = GameConfig.SHIP_BASE_SIZE,
                        shipColor = launchColor,
                        pilotColor = launchPilotColor,
                        startingWeaponId = selectedShip.startingWeaponId,
                        alpha = 1f
                    )
                }

                // Walkway sliding down with the world — same extent as the static walkway it
                // takes over from (drawWalkway), or it would pop wider the instant it drops.
                // Below the gate that extent is 0..screenWidth, exactly as before.
                val walkwayDropPaint = Paint().apply {
                    color = 0xFF2A2A30.toInt()
                    style = Paint.Style.FILL
                }
                val (dropLeft, dropRight) = buildingExtent(state)
                canvas.drawRect(dropLeft, walkwayY + worldDropY, dropRight, walkwayY + worldDropY + 4f, walkwayDropPaint)

                // Particles drop with world
                val particlePaint = Paint().apply {
                    color = if (isCorruptedLaunch) 0xFFDD4422.toInt() else 0xFFFFAA44.toInt()
                    alpha = ((1f - phase) * 200).toInt()
                }
                for (i in 0..20) {
                    val angle = Random.nextFloat() * 2f * PI.toFloat()
                    val dist = Random.nextFloat() * 80f * phase
                    val px = centerX + cos(angle) * dist
                    val py = shipY + worldDropY + sin(angle).coerceAtLeast(0f) * dist
                    canvas.drawCircle(px, py, 2f + Random.nextFloat() * 4f, particlePaint)
                }

                canvas.restore()
            }

            // Phase 4: Hyperspace (0.55 - 1.0, ~1.8s)
            else -> {
                val hyperPhase = (progress - 0.55f) / 0.45f  // 0.0 to 1.0 within hyperspace
                val arrivalStart = 0.67f  // ~1.2s travel, ~0.6s arrival
                val isArrival = hyperPhase >= arrivalStart
                val arrivalPhase = if (isArrival) (hyperPhase - arrivalStart) / (1f - arrivalStart) else 0f

                // Background: dark blue-black during travel, lerps to game background during arrival
                // Game background is 0xFF000011 = rgb(0, 0, 17)
                val bgR = if (isArrival) (10 * (1f - arrivalPhase)).toInt() else 10
                val bgG = if (isArrival) (10 * (1f - arrivalPhase)).toInt() else 10
                val bgB = if (isArrival) (26 + (17 - 26) * arrivalPhase).toInt() else 26
                hyperBgPaint.color = android.graphics.Color.rgb(bgR, bgG, bgB)
                canvas.drawRect(0f, 0f, screenWidth, screenHeight, hyperBgPaint)

                // Star streaks — white/blue only
                val numStreaks = 60
                hyperStreakRandom.setSeed(42)

                // Arrival deceleration — length shrinks, alpha fades, offset decelerates
                val lengthMult = if (isArrival) 1f - arrivalPhase * 0.8f else 1f
                val streakAlphaBase = if (isArrival) 1f - arrivalPhase else 1f

                val yOffset = hyperStreakOffset(hyperPhase, arrivalStart, launchSpan(screenWidth, screenHeight))

                for (i in 0 until numStreaks) {
                    val xPos = hyperStreakRandom.nextFloat() * screenWidth
                    val baseY = hyperStreakRandom.nextFloat() * screenHeight
                    val maxLength = 40f + hyperPhase.coerceAtMost(0.5f) * 2f * 250f
                    val streakLength = maxLength * lengthMult
                    val startY = (baseY + yOffset) % (screenHeight + 300f)
                    val endY = startY + streakLength

                    // Skip streaks that cross the ship area
                    val shipZoneLeft = centerX - 40f
                    val shipZoneRight = centerX + 40f
                    if (xPos > shipZoneLeft && xPos < shipZoneRight) continue

                    // Fade in during first 10% of travel
                    val fadeIn = if (hyperPhase < 0.10f) hyperPhase / 0.10f else 1f
                    val alpha = (fadeIn * streakAlphaBase * 255).toInt().coerceIn(0, 255)
                    if (alpha == 0) continue

                    hyperStreakPaint.alpha = alpha
                    // ~60% white, ~40% light blue
                    hyperStreakPaint.color = if (i % 5 < 3) 0xFFFFFFFF.toInt() else 0xFF88BBFF.toInt()
                    hyperStreakPaint.strokeWidth = if (i % 4 == 0) 3f else if (i % 2 == 0) 2f else 1f

                    canvas.drawLine(xPos, startY, xPos, endY, hyperStreakPaint)
                }

                // Ship stays visible at center — drawn last, on top of everything
                if (selectedShip != null) {
                    ShipRenderer.drawShip(
                        canvas = canvas, shapeRenderer = shapeRenderer,
                        x = centerX, y = shipCenterY,
                        rotation = (-Math.PI / 2).toFloat(),
                        size = GameConfig.SHIP_BASE_SIZE,
                        shipColor = launchColor,
                        pilotColor = launchPilotColor,
                        startingWeaponId = selectedShip.startingWeaponId,
                        alpha = 1f
                    )
                }
            }
        }
    }

    // =======================================================================
    // Codex (preserved faithfully from original)
    // =======================================================================

    private fun drawCodex(canvas: Canvas, state: HangarState) {
        // Fullscreen overlay
        val bgPaint = Paint().apply {
            color = 0xEE111122.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, bgPaint)

        val discovered = persistence.getDiscoveredEvolutions()
        val evolutions = WeaponDefinitions.evolutions
        val baseWeapons = WeaponDefinitions.weapons

        // Build evolution → (baseWeaponId, passiveId) mapping
        data class EvolutionMapping(
            val baseWeaponId: String,
            val baseWeaponName: String,
            val passiveId: String,
            val effectivePassiveId: String,
            val passiveName: String,
            val evolutionId: String,
            val evolutionName: String,
            val isWeaponKnown: Boolean,
            val isPassiveKnown: Boolean
        )

        val mappings = evolutions.mapNotNull { evo ->
            val base = baseWeapons.find { it.evolutionWeaponId == evo.id } ?: return@mapNotNull null
            val passiveId = base.evolutionPassive ?: return@mapNotNull null
            val passiveName = PassiveDefinitions.getDisplayName(
                                passiveId,
                                PassiveDefinitions.ASTRO_PILOT_ID,
                                isAstroLoopRun = StoryStateManager.isAstroLoop(persistence)
                            )
            val effectivePassiveId = PassiveDefinitions.getEffectivePassiveId(
                passiveId,
                PassiveDefinitions.ASTRO_PILOT_ID,
                isAstroLoopRun = StoryStateManager.isAstroLoop(persistence)
            )

            val weaponShipIndex = ShipDefinitions.ships.indexOfFirst { it.startingWeaponId == base.id }
            val isWeaponKnown = weaponShipIndex >= 0 && state.isShipUnlocked(weaponShipIndex)

            val passivePilotIndex = PilotDefinitions.pilots.indexOfFirst { it.startingPassiveId == passiveId }
            val isPassiveKnown = passivePilotIndex >= 0 && state.isPilotUnlocked(passivePilotIndex)

            EvolutionMapping(base.id, base.name, passiveId, effectivePassiveId, passiveName, evo.id, evo.name, isWeaponKnown, isPassiveKnown)
        }

        // Layout: the rows fill the safe band, so a display cutout never eats the first one
        val (rowsTop, rowsBottom) = codexRowSpan(layout)
        val usableHeight = rowsBottom - rowsTop
        val rowHeight = usableHeight / mappings.size.coerceAtLeast(1)

        val iconSize = (rowHeight * 0.45f).coerceIn(16f, 60f)
        val labelSize = (rowHeight * 0.30f).coerceIn(11f, 24f)
        val iconGap = iconSize * 0.30f
        val symbolTextSize = (iconSize * 0.60f).coerceIn(18f, 32f)
        val symbolYOffset = iconSize * 0.10f
        val iconPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        for ((index, mapping) in mappings.withIndex()) {
            val isDiscovered = discovered.contains(mapping.evolutionId)
            val rowCenterY = rowsTop + rowHeight * index + rowHeight / 2f

            // Horizontal layout: [weapon] + [passive] = [evolution]
            // Centered on screen with even spacing, measured on the PORTRAIT content width so a
            // rotated device shows the same rows at the same width rather than stretching them
            // across the landscape screen (owner, 2026-09-29). Identical to layout's in portrait.
            val spacing = portraitLayout.content.width / 6f
            val weaponX = layout.content.centerX - spacing * 1.5f
            val plusX = layout.content.centerX - spacing * 0.75f
            val passiveX = layout.content.centerX
            val equalsX = layout.content.centerX + spacing * 0.75f
            val evoX = layout.content.centerX + spacing * 1.5f

            val iconY = rowCenterY - iconSize * 0.3f  // Offset up to make room for name below
            val nameY = iconY + iconSize + iconGap

            iconPaint.alpha = 255  // reset per-row to prevent bleed from previous iteration

            if (isDiscovered) {
                // All three fully bright
                iconPaint.color = 0xFFFFFFFF.toInt()
                iconPaint.alpha = 255
                IconRenderer.drawIcon(canvas, mapping.baseWeaponId, true, weaponX, iconY, iconSize, iconPaint)
                IconRenderer.drawIcon(canvas, mapping.effectivePassiveId, false, passiveX, iconY, iconSize, iconPaint)
                IconRenderer.drawIcon(canvas, mapping.evolutionId, true, evoX, iconY, iconSize, iconPaint)

                textPaint.textSize = symbolTextSize
                textPaint.color = 0xFF888888.toInt()
                canvas.drawText("+", plusX, iconY + symbolYOffset, textPaint)
                canvas.drawText("=", equalsX, iconY + symbolYOffset, textPaint)

                textPaint.textSize = labelSize
                textPaint.color = 0xFF999999.toInt()
                canvas.drawText(mapping.baseWeaponName, weaponX, nameY, textPaint)
                canvas.drawText(mapping.passiveName, passiveX, nameY, textPaint)
                textPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawText(mapping.evolutionName, evoX, nameY, textPaint)
            } else {
                // Weapon column
                if (mapping.isWeaponKnown) {
                    iconPaint.color = 0xFFFFFFFF.toInt()
                    iconPaint.alpha = 255
                    IconRenderer.drawIcon(canvas, mapping.baseWeaponId, true, weaponX, iconY, iconSize, iconPaint)
                    textPaint.textSize = labelSize
                    textPaint.color = 0xFF999999.toInt()
                    canvas.drawText(mapping.baseWeaponName, weaponX, nameY, textPaint)
                } else {
                    iconPaint.color = 0xFF333344.toInt()
                    iconPaint.alpha = 80
                    IconRenderer.drawIcon(canvas, mapping.baseWeaponId, true, weaponX, iconY, iconSize, iconPaint)
                    textPaint.textSize = labelSize
                    textPaint.color = 0xFF333344.toInt()
                    canvas.drawText("???", weaponX, nameY, textPaint)
                }

                // Passive column
                if (mapping.isPassiveKnown) {
                    iconPaint.color = 0xFFFFFFFF.toInt()
                    iconPaint.alpha = 255
                    IconRenderer.drawIcon(canvas, mapping.effectivePassiveId, false, passiveX, iconY, iconSize, iconPaint)
                    textPaint.textSize = labelSize
                    textPaint.color = 0xFF999999.toInt()
                    canvas.drawText(mapping.passiveName, passiveX, nameY, textPaint)
                } else {
                    iconPaint.color = 0xFF333344.toInt()
                    iconPaint.alpha = 80
                    IconRenderer.drawIcon(canvas, mapping.effectivePassiveId, false, passiveX, iconY, iconSize, iconPaint)
                    textPaint.textSize = labelSize
                    textPaint.color = 0xFF333344.toInt()
                    canvas.drawText("???", passiveX, nameY, textPaint)
                }

                // + symbol: bright only when both sides are known
                textPaint.textSize = symbolTextSize
                textPaint.color = if (mapping.isWeaponKnown && mapping.isPassiveKnown) 0xFF888888.toInt() else 0xFF333344.toInt()
                canvas.drawText("+", plusX, iconY + symbolYOffset, textPaint)

                // = and evolution: always dimmed until discovered
                textPaint.color = 0xFF333344.toInt()
                canvas.drawText("=", equalsX, iconY + symbolYOffset, textPaint)
                iconPaint.color = 0xFF333344.toInt()
                iconPaint.alpha = 80
                IconRenderer.drawIcon(canvas, mapping.evolutionId, true, evoX, iconY, iconSize, iconPaint)
                textPaint.textSize = labelSize
                canvas.drawText("???", evoX, nameY, textPaint)
            }
        }

        // No "how to close" line (owner, 2026-09-29: it makes sense as is). A tap anywhere, or a
        // pad's B, closes it. The rows still stop where that line used to sit — codexRowSpan —
        // so removing the words did not move a row.

        textPaint.color = 0xFFFFFFFF.toInt()
    }

    companion object {
        /**
         * The launch sequence's yardstick for how far the world moves: the device's long edge,
         * which is the screen height upright and its width rotated.
         *
         * Both launch motions — the liftoff's world drop and the hyperspace streaks' scroll — were
         * multiples of `screenHeight`, so on a rotated phone (960 tall against 2142) they ran at
         * less than half speed while the ship and the streaks stayed the same size: long, slow
         * streaks. Owner, 2026-09-29: the launch should look the same either way up. Upright this
         * IS screenHeight, so portrait is bit-identical.
         */
        internal fun launchSpan(screenWidth: Float, screenHeight: Float): Float =
            maxOf(screenWidth, screenHeight)

        /**
         * How far the hyperspace streaks have scrolled at [hyperPhase] (0..1 of the phase).
         * Linear through the travel, then decelerating over the arrival from [arrivalStart]: the
         * arrival term is the integral of a speed falling from 1 to 0.2, `t - 0.4t^2`.
         */
        internal fun hyperStreakOffset(hyperPhase: Float, arrivalStart: Float, span: Float): Float {
            val travel = hyperPhase.coerceAtMost(arrivalStart) * span * 0.6f
            if (hyperPhase < arrivalStart) return travel
            val t = (hyperPhase - arrivalStart) / (1f - arrivalStart)
            return travel + (t - 0.4f * t * t) * (1f - arrivalStart) * span * 0.6f
        }

        /** A panel button's icon, as a fraction of the card's short side. Tune by eye. */
        internal const val PANEL_ICON_FRACTION = 0.75f

        /**
         * Floor for the carousel's per-ship draw width — see [measureShipHalfExtent], which owns
         * the derivation. Exposed so the fade's own test can state the distance it expects rather
         * than copying the number.
         */
        internal const val MIN_SHIP_EXTENT = 180f

        /**
         * The band the codex lays its rows in.
         *
         * Anchored to the safe area, not the screen: at targetSdk 36 the window fills the display
         * cutout, and rows measured from a raw screenHeight put the first one inside the notch —
         * the same failure drawYenCounter had, in this same file. A no-op wherever safe == full.
         */
        internal fun codexRowSpan(layout: ScreenLayout): Pair<Float, Float> =
            layout.safe.top + CODEX_TOP_MARGIN to codexHintY(layout) - CODEX_HINT_LINE

        /**
         * Where the "how to leave" line sits: the bottom of the safe band, with the rows stopping a
         * line short of it. Drawn at the rows' own bottom it landed across the last row's labels,
         * because the rows divide the whole band — the band's bottom edge is the last row.
         */
        internal fun codexHintY(layout: ScreenLayout): Float =
            layout.safe.bottom - CODEX_BOTTOM_MARGIN

        private const val CODEX_TOP_MARGIN = 30f
        private const val CODEX_BOTTOM_MARGIN = 20f

        /** The line reserved for the hint, kept clear of the rows above it. */
        private const val CODEX_HINT_LINE = 34f

        /**
         * The [CREW] [LAUNCH] [SHOP] row, as focus targets.
         *
         * Geometry mirrors the tap zones in HangarSurfaceView.handleTap, which are anchored to
         * content width rather than screen width so the side labels do not drift on wide
         * screens. HangarNavFocusTest asserts the two agree rect for rect, because this is a
         * deliberate copy and a copy is what drifts. The band's own top is the one part that is
         * no longer copied — both sides read [HangarMetrics.navBandTop], because a panel's
         * arrangement depends on that line too.
         *
         * The current page's own label is published but disabled — a remote user should see
         * where they are, not have it vanish. During the intro cinematic only `nav:1` is
         * published: the launch pad is the one hop a swipe can make then, and publishing the
         * others would hand a controller a page change no finger can make.
         */
        fun publishNavTargets(
            focus: FocusRegistry,
            currentPage: Int,
            screenWidth: Float,
            screenHeight: Float,
            contentWidth: Float,
            introCinematic: Boolean,
            onNavigate: (Int) -> Unit
        ) {
            val labelY = HangarMetrics.navLabelY(screenHeight)
            val bandTop = HangarMetrics.navBandTop(screenHeight)
            val centerX = screenWidth / 2f
            val spacing = contentWidth * 0.25f
            for (i in 0..2) {
                // During the intro only the launch pad's own label exists, matching the one hop a
                // swipe can make. Once that hop is made it is the current page, so it publishes
                // disabled and the cinematic stays shut.
                if (introCinematic && i != 1) continue
                val labelCenterX = centerX + (i - 1) * spacing
                val halfWidth = spacing * 0.4f
                focus.add(
                    FocusTarget(
                        id = "nav:$i",
                        rect = RectF(
                            labelCenterX - halfWidth, bandTop,
                            labelCenterX + halfWidth, labelY + 15f
                        ),
                        enabled = i != currentPage
                    ) { onNavigate(i) }
                )
            }
        }

        /**
         * The shipyard, as one focus target: the ship itself.
         *
         * Left and right are the carousel rather than focus moves, and OK buys or launches what
         * is in front of the player, so there is nothing else on the page to land on. Four
         * targets made flying the second ship a five-press sequence — right, OK, up, OK — with
         * the ring hopping between three ships that are one control, not three.
         *
         * The rect is the ship's own tap zone, so the callback can re-enter handleShipyardTap at
         * its centre rather than carrying a second copy of the purchase.
         */
        fun publishShipyardTarget(
            focus: FocusRegistry,
            centerX: Float,
            shipY: Float,
            hitSize: Float,
            onActivate: () -> Unit
        ) {
            focus.add(
                FocusTarget(
                    "ship",
                    RectF(centerX - hitSize, shipY - hitSize, centerX + hitSize, shipY + hitSize)
                ) { onActivate() }
            )
        }
    }
}
