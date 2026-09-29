package com.astroloop.game.cabinet

import android.graphics.Canvas
import com.astroloop.game.data.CrystalFightLines
import android.graphics.RectF
import com.astroloop.game.input.FocusRegistry
import com.astroloop.game.input.FocusTarget

enum class ShellButton { PLAY, SCORES, EXIT, RESUME, QUIT, AGAIN, BACK, REPLAY }

/** One row of the board: three-letter initials and a best score. */
data class BoardRow(val initials: String, val score: Int, val cleared: Boolean)

/**
 * The cabinet's non-play screens.
 *
 * Button rects are built during draw, on the render thread, and read back by [hitTest] on
 * the UI thread. Those are two different threads, so the two never share a live map — see
 * [hitRects].
 *
 * *This doc used to claim the pattern matched `StorePageRenderer`'s. It did not, and that
 * wrong claim is what made the hazard look safe:* every other hit target in this project
 * is an individual `RectF` field that a tap reads and calls `contains()` on. Nothing else
 * hit-tests by iterating a shared collection, which is why nothing else could throw
 * `ConcurrentModificationException`.
 */
class CabinetShellRenderer(
    private val m: CabinetMetrics,
    private val r: CabinetRenderer
) {
    /**
     * Render-thread scratch. Cleared and refilled every frame by [draw]; **never read from
     * the UI thread.**
     */
    private val pending = HashMap<ShellButton, RectF>()

    /**
     * The last *completed* frame's tap targets, published for the UI thread.
     *
     * Do not collapse this back into one shared map. [draw] runs on the render thread and
     * [hitTest] on the UI thread, and a plain `HashMap` used by both crashed the app:
     * `clear()` and `put()` bump `modCount`, `hitTest`'s iteration is fail-fast, and the
     * resulting `ConcurrentModificationException` propagates out of `onTouchEvent` with no
     * per-frame `Throwable` guard to catch it the way the render loop has.
     *
     * It also fixes a quieter bug. Between `clear()` and a given button's `put()`, that
     * button did not exist — and on the menu, everything drawn before `PLAY` registers is
     * around a thousand `drawPath` calls, with `EXIT` registering last of all. Taps landing
     * in that window silently did nothing.
     *
     * Publishing a finished snapshot means the UI thread tests against the frame the player
     * actually saw, which is the correct thing to hit anyway.
     */
    @Volatile private var hitRects: Map<ShellButton, RectF> = emptyMap()

    /**
     * The shell's own registry. The cabinet overlay is modal, so while it is up this is the
     * only registry the hangar's activeFocus() will hand directional input to.
     */
    val focusRegistry = FocusRegistry()

    /** Set by HangarSurfaceView, pointing at the same dispatch the tap path uses. */
    var onShellButton: ((ShellButton) -> Unit)? = null

    /**
     * Display cutout, in playfield pixels. Every screen in this class lays out from here
     * rather than from y=0, so BELT RUN at 0.13 of the height clears a Pixel's camera.
     */
    var topInset: Float = 0f

    /** Usable height below the cutout. */
    private val h: Float get() = m.height - topInset

    /**
     * Reused by [drawMenu]'s dropped rows. Render-thread only, like [pending], and refilled
     * before every use — the takeover is ambience and must not add a per-frame allocation
     * to the one screen the frame-time complaint was raised against.
     */
    private val bandRect = RectF()

    /** A fraction of the usable height, offset past the cutout. */
    private fun vy(frac: Float): Float = topInset + h * frac

    /**
     * @param coinCost what one play actually costs, so the priced buttons can say so.
     *   Passed in rather than duplicated: the charge is made by the host against its own
     *   `COIN_COST`, and a literal here would let the button lie about the price the
     *   moment that constant moved — the exact defect the priced button exists to catch.
     * @param replayAvailable whether the MENU's fourth entry should appear at all. Passed
     *   in rather than read off persistence directly, the same reason [credits] and
     *   [canAfford] are: [CabinetShell] and this renderer are pure and know nothing of
     *   persistence, so whether the crystal has been released is the host's knowledge.
     * @param takeoverMs the wall clock driving a design decision's glitch, or null when the gate
     *   is shut. Passed in for the same reason [replayAvailable] is — the gate is
     *   `CrystalReckoning.shouldEnter`, which needs persistence, which this class does not
     *   have. **MENU only.** SCORES is where the asterisks make gate progress legible and
     *   PAUSE/OVER are read under pressure; corrupting either would cost the player
     *   information rather than telling them something.
     */
    fun draw(
        canvas: Canvas,
        shell: CabinetShell,
        board: List<BoardRow>,
        credits: Int,
        canAfford: Boolean,
        coinCost: Int,
        replayAvailable: Boolean = false,
        takeoverMs: Long? = null,
        /** Best release in whole seconds, 0 for none — a design decision. */
        bestReleaseSeconds: Int = 0
    ) {
        pending.clear()
        when (shell.screen) {
            CabinetScreen.MENU ->
                drawMenu(canvas, credits, canAfford, coinCost, board, replayAvailable, takeoverMs)
            CabinetScreen.SCORES -> drawScores(canvas, board, bestReleaseSeconds)
            CabinetScreen.PLAY -> Unit
            CabinetScreen.PAUSE -> drawPause(canvas, shell)
            // NOT shell.isReckoning. onBack() lets go of the run and only ASKS to leave; the
            // host then powers the tube down over this very screen, so the readout that was
            // correctly absent for the whole death popped to "000" under the words GAME OVER on
            // the frame the player pressed LEAVE — and the button relabelled itself from LEAVE
            // to MENU beside it. sim.scores survives, and is the same flag that stops rocks
            // paying: exactly the condition under which a three-digit readout would be a lie.
            CabinetScreen.OVER ->
                drawOver(canvas, shell.sim.score, credits, canAfford, coinCost, !shell.sim.scores)
        }
        // Publish only once the frame is fully laid out, so the UI thread never sees a
        // half-built set. A copy, not the live map: the whole point is that nothing mutates
        // what hitTest reads. Four entries at most, in a class that already allocates a
        // RectF per button per frame, so the cost is noise.
        hitRects = HashMap(pending)
        // Rebuilt in the same pass that laid the buttons out, so a rect can never outlive the
        // screen that drew it — the shell swaps its whole button set between states.
        focusRegistry.begin()
        publishShellTargets(focusRegistry, hitRects) { button -> onShellButton?.invoke(button) }
        focusRegistry.commit()
    }

    private fun centred(
        canvas: Canvas, s: String, y: Float, size: Float, weight: Float = 1.4f,
        p: CabinetRenderer.Companion.Phosphor = CabinetRenderer.ICE
    ) {
        r.text(canvas, s, m.width / 2f - CabinetFont.width(s, size) / 2f, y, size, weight, p = p)
    }

    /**
     * @param ghostMs when non-null, a corruption-red separation pass under the label on the
     *   takeover's beat. It is drawn HERE rather than by the caller so it can never disagree
     *   with the label about what the string is or where it sits: [pricedButton] decides the
     *   text ("? ? ?" / "? ? ? - 100¥" / "NO ¥") and this centres it, so a caller drawing its
     *   own ghost would be re-deriving both and would drift the moment either changed. Like
     *   the title's ghost it is an extra pass and never a move, so [pending] is untouched and
     *   the tap rect stays where the eye sees the clean stroke.
     */
    private fun button(
        canvas: Canvas, b: ShellButton, label: String, y: Float, size: Float,
        ghostMs: Long? = null, cx: Float = m.width / 2f
    ) {
        val w = CabinetFont.width(label, size)
        val x = cx - w / 2f
        if (ghostMs != null) r.ghostText(canvas, label, x, y, size, 1.5f, ghostMs, CabinetTakeover.MENU)
        r.text(canvas, label, x, y, size, 1.5f)
        val rect = RectF(x - size, y - size * 0.6f, x + w + size, y + size * 1.6f)
        r.strokeBox(canvas, rect)
        pending[b] = rect
    }

    /**
     * A button that names its own price when pressing it will take a coin.
     *
     * A device pass found PLAY silently spending ¥100 through the host's
     * `onPlay() || chargeAndPlay` fallthrough while the readout still said CREDIT 0.
     * The one-tap charge is wanted — the spec argues for it directly — so the fix is
     * that the tap is never a surprise, not that the tap is refused.
     *
     * With no credit AND no yen the button is drawn dim and is not registered as a hit
     * target at all, which is the same dimmed-inert idiom the ARCADE debug page uses.
     */
    private fun pricedButton(
        canvas: Canvas, b: ShellButton, base: String,
        y: Float, size: Float, credits: Int, canAfford: Boolean, coinCost: Int,
        ghostMs: Long? = null, cx: Float = m.width / 2f
    ) {
        val label = pricedLabel(base, credits, canAfford, coinCost)
        if (credits > 0 || canAfford) { button(canvas, b, label, y, size, ghostMs, cx); return }
        run {
            val w = CabinetFont.width(label, size)
            val x = cx - w / 2f
            // The dim, inert branch glitches too. It is still the ??? row — the machine does
            // not stop half-remembering because the player is broke — and leaving this one
            // clean would make the effect read as a function of the wallet.
            if (ghostMs != null) r.ghostText(canvas, label, x, y, size, 1.5f, ghostMs, CabinetTakeover.MENU)
            r.text(canvas, label, x, y, size, 1.5f, alphaScale = DIM)
        }
    }

    /** What [pricedButton] writes: the bare label on credit, the price on yen, NO ¥ on neither. */
    private fun pricedLabel(base: String, credits: Int, canAfford: Boolean, coinCost: Int): String =
        when {
            credits > 0 -> base
            !canAfford -> "NO ¥"
            else -> "$base - $coinCost¥"
        }

    /** Landscape: the playfield is wider than it is tall. The menu and board re-lay out for it. */
    private val landscape: Boolean get() = m.width > m.height

    /**
     * X of a portrait column fraction, inside a band the portrait width wide centred on the
     * screen. Identical to `m.width * frac` in portrait; in landscape it keeps the board the size
     * it is in portrait instead of stretching its columns across the whole tube.
     */
    private fun colX(frac: Float, bandCentre: Float = m.width / 2f): Float =
        bandCentre + (frac - 0.5f) * m.minEdge

    private fun drawMenu(
        canvas: Canvas, credits: Int, canAfford: Boolean, coinCost: Int, board: List<BoardRow>,
        replayAvailable: Boolean, takeoverMs: Long? = null
    ) {
        val big = m.minEdge / 11f
        // The title carries the takeover, and only the title: it is the machine naming
        // itself, so it is the right thing for the crystal to be pushing through. Every
        // button below keeps its own clean stroke and — because the ghost is an extra pass
        // and never a move — its own unshifted tap rect. Nothing here changes what [hitTest]
        // will match.
        if (takeoverMs != null) {
            val tw = CabinetFont.width("BELT RUN", big)
            r.ghostText(
                canvas, "BELT RUN", m.width / 2f - tw / 2f, vy(0.13f), big, 1.9f,
                takeoverMs, CabinetTakeover.MENU
            )
        }
        centred(canvas, "BELT RUN", vy(0.13f), big, 1.9f)
        centred(canvas, "CREDIT $credits", vy(0.13f) + big * 1.7f, big * 0.26f, 1.1f)

        val s = m.minEdge / 20f
        // Landscape has a short edge's worth of height for what portrait spreads over its long
        // one, so the board tightens (1.3 rows instead of 1.5) and starts higher, leaving room for
        // the status line and one horizontal button row underneath. See [drawMenuButtonRow].
        val boardTop = if (landscape) 0.40f else 0.46f
        val rowStep = if (landscape) 1.3f else 1.5f
        centred(canvas, "HIGH SCORE", vy(boardTop - 0.06f), s * 0.85f, 1.3f)
        board.take(5).forEachIndexed { i, row ->
            val y = vy(boardTop) + i * s * rowStep
            r.text(canvas, "${i + 1}", colX(0.20f), y, s * 0.8f, 1.1f)
            r.text(canvas, row.initials, colX(0.34f), y, s, 1.4f)
            val txt = row.score.toString().padStart(3, '0')
            r.text(canvas, txt, colX(0.80f) - CabinetFont.width(txt, s), y, s, 1.4f)
        }

        // The coin rule. Set dressing, not a live value, so it reads fine down here —
        // CREDIT moved up under the title because it's the number that actually changes.
        //
        // vy(0.70f) was chosen over the button row's old vy(0.735f): at the 1080x2400
        // reference (minEdge 1080, so s = 54, statusSize = 32.4) that put this line at
        // 1764-1796.4 while the PLAY box's top sat at 1743.6 — the box swallowed the
        // text. At vy(0.70f) the line runs 1680-1712.4, comfortably clear of the box
        // below (see [by]'s own doc for its numbers).
        val statusSize = s * 0.6f
        centred(canvas, "1 COIN 1 PLAY", vy(if (landscape) 0.75f else 0.70f), statusSize, 1.1f)

        if (landscape) {
            drawMenuButtonRow(canvas, s, credits, canAfford, coinCost, replayAvailable)
        } else {
            drawMenuButtonColumn(canvas, s, credits, canAfford, coinCost, replayAvailable)
        }

        // Last, so the dropped rows sit over the menu AND over the attract demo running
        // behind it — one machine losing lock, not an overlay with a hole in it. Drawn
        // below the cutout only: a band inside the inset would land on the camera notch.
        if (takeoverMs != null) {
            bandRect.set(0f, topInset, m.width, m.height)
            r.dropout(canvas, bandRect, takeoverMs, CabinetTakeover.MENU)
        }
    }

    /**
     * Landscape: PLAY, SCORES, EXIT (and the ??? row once it exists) side by side along the
     * bottom, in the portrait column's order left to right.
     *
     * The portrait column needs ~6.4 button heights below vy(0.75) — more than a landscape
     * screen has, which is what clipped EXIT off the bottom. A row needs one. Each keeps its
     * portrait size, and PLAY stays the big one; the smaller labels sit on PLAY's centre line
     * rather than its top, so the row reads as a row. If the row would ever be wider than the
     * screen (it is not on any 16:9-or-wider device) every size shrinks together to fit.
     */
    private fun drawMenuButtonRow(
        canvas: Canvas, s: Float, credits: Int, canAfford: Boolean, coinCost: Int,
        replayAvailable: Boolean
    ) {
        val playLabel = pricedLabel("PLAY", credits, canAfford, coinCost)
        val replayLabel = pricedLabel("? ? ?", credits, canAfford, coinCost)
        val labels = if (replayAvailable) listOf(playLabel, "SCORES", "EXIT", replayLabel)
        else listOf(playLabel, "SCORES", "EXIT")
        val sizes = FloatArray(labels.size) { if (it == 0) s else s * 0.8f }
        // A button's box is its label plus one size of padding each side — see [button].
        val gap = s
        var total = gap * (labels.size - 1)
        for (i in labels.indices) total += CabinetFont.width(labels[i], sizes[i]) + sizes[i] * 2f
        val fit = (m.width * 0.94f / total).coerceAtMost(1f)
        for (i in sizes.indices) sizes[i] *= fit

        val by = vy(0.86f)
        var left = m.width / 2f - total * fit / 2f
        for (i in labels.indices) {
            val size = sizes[i]
            val boxW = CabinetFont.width(labels[i], size) + size * 2f
            val cx = left + boxW / 2f
            val y = by + (sizes[0] - size) / 2f
            when (i) {
                0 -> pricedButton(canvas, ShellButton.PLAY, "PLAY", y, size, credits, canAfford, coinCost, cx = cx)
                1 -> button(canvas, ShellButton.SCORES, "SCORES", y, size, cx = cx)
                2 -> button(canvas, ShellButton.EXIT, "EXIT", y, size, cx = cx)
                else -> pricedButton(canvas, ShellButton.REPLAY, "? ? ?", y, size,
                    credits, canAfford, coinCost, ghostMs = System.currentTimeMillis(), cx = cx)
            }
            left += boxW + gap * fit
        }
    }

    /** Portrait: the original vertical column. Unchanged — see the numbers in its comments. */
    private fun drawMenuButtonColumn(
        canvas: Canvas, s: Float, credits: Int, canAfford: Boolean, coinCost: Int,
        replayAvailable: Boolean
    ) {
        // vy(0.75f), moved down from 0.74f to widen the gap from the status line above.
        // At the 1080x2400 reference (s = 54) the three boxes land at:
        //   PLAY   by=1800,          rect  1767.6-1886.4
        //   SCORES by+s*2.4f=1929.6, rect 1903.68-1998.72
        //   EXIT   by+s*4.4f=2037.6, rect 2011.68-2106.72
        // all clear of the status line's 1712.4 above and the 2400 screen bottom below.
        val by = vy(0.75f)
        pricedButton(canvas, ShellButton.PLAY, "PLAY", by, s, credits, canAfford, coinCost)
        button(canvas, ShellButton.SCORES, "SCORES", by + s * 2.4f, s * 0.8f)
        button(canvas, ShellButton.EXIT, "EXIT", by + s * 4.4f, s * 0.8f)

        // The machine half-remembers, the way Tobar does — a fourth line that does not
        // belong to BELT RUN's own game. Only once the crystal has been released, and
        // priced like everything else per a design decision: the finale stopped being free.
        //
        // by + s * 6.4f extends the existing spacing rather than re-deriving the column:
        // at the 1080x2400 reference (s = 54) the rect lands 2119.68-2214.72, clear of the
        // 2400 screen bottom by 185px, and nothing above it moves. The polish pass already
        // spent a fix round on this column swallowing its own status line — see [by]'s doc.
        if (replayAvailable) {
            // THE ??? ROW GLITCHES, ALWAYS, AND IT IS THE ONLY ROW THAT DOES.
            //
            // A design decision's takeover is a WARNING: it runs while the crystal is coming for
            // BELT RUN and stops once the fight is over, which is why `takeoverMs` above is
            // null by the time this row exists at all — the two are mutually exclusive, the
            // gate needing the crystal unreleased and this row needing it released. So this
            // asks the clock itself rather than taking the host's parameter.
            //
            // Same beat, deliberately: [CabinetTakeover] is a pure function of the wall clock,
            // so this tears on exactly the cadence every other surface tears on, and it reads
            // as the one machine still not quite holding lock rather than as a second effect.
            // It costs nothing outside an episode — `intensity` returns 0 on ~91% of frames
            // and `ghostText` returns before drawing anything.
            //
            // The rest of the menu stays clean. What is left in there is not coming back; it
            // is only this one line that the machine cannot say straight.
            pricedButton(canvas, ShellButton.REPLAY, "? ? ?", by + s * 6.4f, s * 0.8f,
                credits, canAfford, coinCost, ghostMs = System.currentTimeMillis())
        }
    }

    private fun drawScores(canvas: Canvas, board: List<BoardRow>, bestReleaseSeconds: Int) {
        val s = m.minEdge / 24f
        centred(canvas, "ALL PILOTS", vy(0.10f), s, 1.4f)
        // Landscape: twelve rows at portrait spacing run into BACK, so the board splits into two
        // columns of portrait-sized rows, side by side. Portrait is one column, as it always was.
        val perColumn = if (landscape) (board.size + 1) / 2 else board.size
        val bandOffset = m.minEdge * 0.35f
        board.forEachIndexed { i, row ->
            val column = if (perColumn > 0) i / perColumn else 0
            val band = when {
                !landscape -> m.width / 2f
                column == 0 -> m.width / 2f - bandOffset
                else -> m.width / 2f + bandOffset
            }
            val y = vy(0.18f) + (i % perColumn.coerceAtLeast(1)) * s * 1.45f
            r.text(canvas, row.initials, colX(0.24f, band), y, s, 1.4f)
            val txt = row.score.toString().padStart(3, '0')
            r.text(canvas, txt, colX(0.62f, band), y, s, 1.4f)
            // A cleared pilot is marked; this is how gate progress is legible.
            if (row.cleared) r.text(canvas, "*", colX(0.76f, band), y, s, 1.4f)
        }
        // THE RECKONING'S ONE NUMBER — a design decision. Its own labelled row beneath the
        // board rather than a thirteenth row inside it: the board is initials x score x
        // cleared, and the reckoning has no initials and is not a pilot.
        //
        // Drawn only once there IS one, which doubles as the spoiler gate — a player who
        // has never found the ending sees a board that looks exactly as it always did.
        if (bestReleaseSeconds > 0) {
            val y = vy(0.18f) + perColumn * s * 1.45f + s * 1.2f
            val band = if (landscape) m.width / 2f - bandOffset else m.width / 2f
            r.text(canvas, "BEST RELEASE", colX(0.24f, band), y, s, 1.4f)
            val t = bestReleaseSeconds.coerceAtMost(999).toString().padStart(3, '0')
            r.text(canvas, t, colX(0.62f, band), y, s, 1.4f)
        }
        button(canvas, ShellButton.BACK, "BACK", vy(0.90f), s * 0.85f)
    }

    /**
     * The paused screen — and in the reckoning, the crystal taking it.
     *
     * A design decision. For [CabinetShell.PAUSE_HELD_SECONDS] this is an ordinary pause, so the
     * refusal reads as a refusal rather than as a dropped input. Then the buttons go and
     * the crystal answers, and [CabinetShell] hands the fight back on its own.
     *
     * QUIT is absent for the whole of it. Registering no tap target is the same technique
     * the broke AGAIN? uses: a button that cannot be afforded, or cannot be taken, is drawn
     * or omitted rather than left live and inert.
     */
    private fun drawPause(canvas: Canvas, shell: CabinetShell) {
        val s = m.minEdge / 24f
        val denied = shell.refusesPause && shell.pauseDenial >= CabinetShell.PAUSE_HELD_SECONDS
        if (denied) {
            val line = CrystalFightLines.pauseDenial[
                (shell.pauseAttempts - 1).coerceAtLeast(0) % CrystalFightLines.pauseDenial.size
            ]
            centred(canvas, line, vy(0.30f), s * 0.9f, 1.4f, CabinetRenderer.CORRUPTION)
            return
        }
        centred(canvas, "PAUSED", vy(0.30f), s * 1.2f, 1.7f)
        button(canvas, ShellButton.RESUME, "RESUME", vy(0.66f), s * 0.85f)
        if (!shell.refusesPause) {
            button(canvas, ShellButton.QUIT, "QUIT", vy(0.76f), s * 0.85f)
        }
    }

    private fun drawOver(
        canvas: Canvas, score: Int, credits: Int, canAfford: Boolean, coinCost: Int,
        reckoning: Boolean = false
    ) {
        val s = m.minEdge / 24f
        centred(canvas, "GAME OVER", vy(0.32f), s * 1.1f, 1.7f)
        // The reckoning keeps no score - there are no rocks in it - so the three-digit
        // readout would sit at 000 under the words GAME OVER and read as a bug.
        if (!reckoning) {
            val txt = score.toString().padStart(3, '0')
            centred(canvas, txt, vy(0.44f), s * 1.6f, 1.9f)
        }
        // AGAIN, not CONTINUE: a fresh run from zero, so every 999 is one unbroken life.
        //
        // Priced in every state, including the reckoning. A design decision retired the free
        // finale: one economy, no special case. A broke player who loses the ending sees
        // this drawn unaffordable with no tap target and LEAVE as their only move.
        //
        // That last clause used to carry a second argument — the walk back to the bar was
        // "the only thing that makes the round counter advance at all". A design decision deleted
        // the counter, so that argument is gone. The pricing stands on its own terms, and
        // a design decision records this knock-on precisely so it is not re-derived from it.
        pricedButton(canvas, ShellButton.AGAIN, "AGAIN?", vy(0.64f), s, credits, canAfford, coinCost)
        // MENU, not EXIT. This button has always gone to the menu — onBack() from OVER
        // returns there — and the old label said otherwise. In the reckoning it leaves
        // altogether: a design decision rejected dropping a player out of an ending into the
        // attract demo, and that argument does not care which button got them there.
        button(canvas, ShellButton.BACK, if (reckoning) "LEAVE" else "MENU", vy(0.76f), s * 0.8f)
    }

    /**
     * Which button, if any, sits under [x],[y] — as of the last completed frame.
     *
     * Called on the UI thread. Reads [hitRects] into a local **once**, so the map it walks
     * cannot be swapped out underneath the iteration.
     */
    fun hitTest(x: Float, y: Float): ShellButton? {
        val snapshot = hitRects
        return snapshot.entries.firstOrNull { it.value.contains(x, y) }?.key
    }

    companion object {
        /**
         * hitRects is already keyed by ShellButton, so the map is the target list.
         *
         * Sorted by the buttons' own vertical position, then left to right, because hitRects is a
         * HashMap and its iteration order is arbitrary — focus order has to follow the layout, or
         * DOWN from the top button lands wherever the hash put things. The left-to-right tiebreak
         * is for the landscape menu, whose buttons share a row.
         */
        fun publishShellTargets(
            focus: FocusRegistry,
            rects: Map<ShellButton, RectF>,
            onPress: (ShellButton) -> Unit
        ) {
            for ((button, rect) in rects.entries.sortedWith(
                compareBy({ it.value.top }, { it.value.left })
            )) {
                focus.add(FocusTarget("shell:${button.name}", RectF(rect)) { onPress(button) })
            }
        }

        /**
         * Brightness of a control that is deliberately inert.
         *
         * A dimmed label reads as "not now"; a missing one reads as broken. The store
         * page's own broke-state handling says NO ¥ rather than hiding the button, and
         * the ARCADE debug page dims rather than removes. Same idiom here.
         */
        const val DIM = 0.28f
    }
}
