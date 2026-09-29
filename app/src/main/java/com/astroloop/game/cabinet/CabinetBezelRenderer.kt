package com.astroloop.game.cabinet

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.PilotDefinitions
import kotlin.math.cos
import kotlin.math.sin

/**
 * BELT RUN as it appears in the store, without opening it.
 *
 * The slot machine's four zones map onto a cabinet almost one to one, which is why
 * this is a re-skin of a layout rather than a new one:
 *   left 12%  - maintenance hatch  -> the coin door / service panel, mechanically unchanged
 *   reels     - the three reel rects -> the CRT, running attract
 *   spin      - SPIN - 100Y        -> INSERT COIN - 100Y, same rect, same broke state
 *   right 15% - payout table       -> the high score board
 *   corners   - mute toggles       -> unchanged, they already read as cabinet hardware
 */
object CabinetBezelRenderer {

    private val fill = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val line = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }

    /** How dim the marquee's ambient rocks are behind BELT RUN — full brightness is the name's. */
    private const val DRIFT_ALPHA_SCALE = 0.45f

    /**
     * The CRT area of the bezel, running a real attract demo.
     *
     * Until a device pass this took a [CabinetRenderer] and never used it — it drew a
     * black rect and a border, so the machine had never once run attract. The playfield
     * is drawn in the sim's own coordinate space and scaled into [screenRect], which is
     * why the sim gets its own metrics rather than sharing the overlay's.
     *
     * The takeover's horizontal slip is added to a `translate` this method was already
     * making, so the whole picture loses lock for the cost of one addition — no second
     * pass over the sim, no offscreen buffer. That existing `clipRect` is what keeps the
     * slipped picture inside the bezel.
     */
    fun drawScreen(
        canvas: Canvas,
        screenRect: RectF,
        sim: CabinetSim?,
        renderer: CabinetRenderer?,
        takeoverMs: Long? = null
    ) {
        fill.color = CabinetRenderer.SCREEN
        canvas.drawRect(screenRect, fill)

        if (sim != null && renderer != null) {
            val saved = canvas.save()
            canvas.clipRect(screenRect)
            val slip = takeoverMs
                ?.let { CabinetTakeover.slip(it, screenRect.width(), CabinetTakeover.CRT) } ?: 0f
            canvas.translate(screenRect.left + slip, screenRect.top)
            canvas.scale(
                screenRect.width() / sim.m.width,
                screenRect.height() / sim.m.height
            )
            renderer.drawPlayfield(canvas, sim, showWaveBanner = false)
            canvas.restoreToCount(saved)
        }

        // Dropped rows go over the picture, under the bezel's own frame: signal loss is in
        // the tube, not in the woodwork.
        if (takeoverMs != null && renderer != null) {
            renderer.dropout(canvas, screenRect, takeoverMs, CabinetTakeover.CRT)
        }

        line.color = CabinetRenderer.PHOSPHOR_GLOW
        line.alpha = 90
        canvas.drawRect(screenRect, line)
        line.alpha = 255
    }

    /**
     * The readout below the CRT, as the machine's own nameplate.
     *
     * BELT RUN appeared exactly once as player-facing text before a device pass — the
     * overlay's menu title, behind a ¥100 door — so a player could walk past this
     * cabinet for a whole playthrough and never learn its name. The original spec
     * called for a marquee; there was none.
     *
     * It stays load-bearing: [message] is the vibration and audio toggles' feedback, and
     * when one is showing it takes the whole plate. Drawn in CabinetFont either way,
     * because the amber TTF it replaced was the slot machine's styling on a machine that
     * is no longer a slot machine.
     *
     * [drift] is the plate's ambient rock drift (polish pass) — drawn first so it sits
     * behind the text, dimmed, and clipped to [rect] so a rock straddling the plate's
     * edge draws at its wrapped position too rather than being sliced at the seam. It
     * keeps running under [message]: `VIBRATE OFF` replaces the text, not the ambience.
     *
     * The border draw does not early-return on a null [renderer] — it used to, via a
     * `val r = renderer ?: return` sitting above it, which meant the bezel's phosphor
     * frame silently vanished on any frame the renderer wasn't ready yet.
     */
    fun drawMarquee(
        canvas: Canvas, rect: RectF, message: String?, renderer: CabinetRenderer?,
        drift: CabinetMarqueeDrift? = null,
        takeoverMs: Long? = null
    ) {
        fill.color = CabinetRenderer.SCREEN
        canvas.drawRect(rect, fill)

        if (renderer != null) {
            if (drift != null) {
                val saved = canvas.save()
                canvas.clipRect(rect)
                canvas.translate(rect.left, rect.top)
                for (rock in drift.rocks) drawDriftRock(canvas, renderer, drift, rock)
                canvas.restoreToCount(saved)
            }

            val label = message ?: "BELT RUN"
            val size = rect.height() * 0.42f
            val w = CabinetFont.width(label, size)
            val lx = rect.centerX() - w / 2f
            val ly = rect.centerY() - size / 2f
            // The nameplate is the one word on the machine a passer-by reads, so this is
            // where the takeover has to land. The red copy goes down first and the real
            // one over it: BELT RUN stays legible, with the crystal showing through it.
            if (takeoverMs != null) {
                renderer.ghostText(canvas, label, lx, ly, size, 1.4f, takeoverMs, CabinetTakeover.MARQUEE)
            }
            renderer.text(canvas, label, lx, ly, size, 1.4f)

            if (takeoverMs != null) {
                renderer.dropout(canvas, rect, takeoverMs, CabinetTakeover.MARQUEE)
            }
        }

        line.color = CabinetRenderer.PHOSPHOR_GLOW
        line.alpha = 70
        canvas.drawRect(rect, line)
        line.alpha = 255
    }

    /** Scratch buffer for [drawDriftRock] — the largest rock shape is 11 vertices. */
    private val driftScratch = FloatArray(24)

    /**
     * One ambient rock, stroked at [CabinetMarqueeDrift.radiusOf] and dimmed to
     * [DRIFT_ALPHA_SCALE], with its own wrap math against [drift]'s metrics.
     *
     * Deliberately does not reuse [CabinetRenderer]'s private rock-drawing: that wraps
     * against the *renderer's own* metrics, which here is the bezel's CRT-scaled
     * renderer — a different rect entirely from the marquee plate — so borrowing it would
     * wrap rocks against the wrong extent. [CabinetRenderer.strokePolyline] itself takes
     * no metrics, so it's the safe part to share.
     */
    private fun drawDriftRock(
        canvas: Canvas, renderer: CabinetRenderer, drift: CabinetMarqueeDrift, rock: CabinetRock
    ) {
        val radius = drift.radiusOf(rock)
        val w = drift.metrics.width
        val h = drift.metrics.height
        val c = cos(rock.rot)
        val s = sin(rock.rot)
        val n = rock.shape.size / 2

        fun strokeAt(ox: Float, oy: Float) {
            var i = 0
            for (v in 0 until n) {
                val lx = rock.shape[v * 2] * radius
                val ly = rock.shape[v * 2 + 1] * radius
                driftScratch[i++] = rock.x + ox + (lx * c - ly * s)
                driftScratch[i++] = rock.y + oy + (lx * s + ly * c)
            }
            renderer.strokePolyline(canvas, driftScratch, i, true, 1.3f, DRIFT_ALPHA_SCALE)
        }

        // Same reasoning as CabinetRenderer.forEachWrap: a rock straddling the plate's
        // edge occupies both sides of it, so drawing only its stored position slices it
        // at the seam. Nothing on this machine is ever cut like that.
        // Rocks are the biggest things on screen and the most obvious when sliced,
        // so the 1.3 allows for the shape's own radial variance pushing past r.
        val offLeft = rock.x - radius * 1.3f < 0f
        val offRight = rock.x + radius * 1.3f > w
        val offTop = rock.y - radius * 1.3f < 0f
        val offBottom = rock.y + radius * 1.3f > h
        val gx = if (offLeft) w else if (offRight) -w else 0f
        val gy = if (offTop) h else if (offBottom) -h else 0f

        strokeAt(0f, 0f)
        if (gx != 0f) strokeAt(gx, 0f)
        if (gy != 0f) strokeAt(0f, gy)
        if (gx != 0f && gy != 0f) strokeAt(gx, gy)
    }

    /**
     * The score board, on **its own lit screen** rather than printed on the panel.
     *
     * A device pass: *"the high scores should be in their own display, with the belt run
     * font, right now they're printed on the display."* They were — drawn straight onto
     * the payout panel through the slot machine's Exo 2 paints, which is the one piece of
     * the machine's old typography that survived the conversion. The board now gets the
     * same treatment the marquee got in a device pass: a `SCREEN` plate with a phosphor
     * border, and text in [CabinetFont].
     *
     * The [titlePaint]/[valuePaint] fallback is kept deliberately, and mirrors
     * [drawMarquee]'s: the plate and its border draw unconditionally, and only the TEXT
     * depends on [renderer]. A null renderer means the board reads in the old amber for a
     * frame rather than vanishing — that exact vanish was a real bug in the marquee's
     * border once, and the fix was to stop early-returning on a null renderer.
     *
     * Insets are deliberate: the old +4/-2 put the text hard against the panel edges,
     * which read as clipped on device.
     *
     * The takeover slips the whole readout sideways inside a clip to [plate], which is why
     * only the phosphor path takes it: the fallback below draws through the slot machine's
     * own amber paints, and those are not this feature's to disturb. **The five scores
     * themselves are never garbled** — they are the board a player reads to see how close
     * the gate is, and a corrupted digit there would be a lie rather than an effect.
     */
    fun drawBoard(
        canvas: Canvas,
        persistence: PersistenceManager,
        left: Float, right: Float, top: Float, lineHeight: Float,
        titlePaint: Paint, valuePaint: Paint,
        renderer: CabinetRenderer? = null,
        takeoverMs: Long? = null
    ) {
        val pad = (right - left) * 0.10f
        // Starts AT the panel top, not above it. It used to start at `top - lineHeight *
        // 0.35f`, and payoutTop is only machineTop + 10 — so on device the plate hung 17 to
        // 22px off the top edge of the cabinet. A device pass: "it's clipping (both the
        // screen and the text)".
        val plate = RectF(left, top, right, top + lineHeight * 6.2f)

        fill.color = CabinetRenderer.SCREEN
        canvas.drawRect(plate, fill)

        if (renderer != null) {
            // SIZED TO THE PANEL'S WIDTH, not to lineHeight — the other half of the
            // clipping. lineHeight is reelHeight/7, a VERTICAL measure, while this panel is
            // only 15% of the machine's width, so deriving text size from it put
            // "HIGH SCORE" at 386px inside a 127px plate. The Exo 2 version this replaced
            // never hit that because it carried a hard `.coerceIn(12f, 20f)`; moving to
            // CabinetFont dropped the cap and kept the proportion, which is exactly
            // backwards for a narrow column.
            val avail = (right - left) - pad * 2f
            val titleSize = CabinetFont.fitted("HIGH SCORE", avail, lineHeight * 0.52f)
            // Six characters of content plus one of clearance, so the initials and the
            // score can never meet in the middle on the narrowest machine.
            val rowSize = CabinetFont.fitted("0000000", avail, lineHeight * 0.62f)
            val tw = CabinetFont.width("HIGH SCORE", titleSize)

            // A matrix save, not a saveLayer: this shifts where the strokes land, it does
            // not composite a second surface. The clip is what stops a slipped row hanging
            // off the plate and reading as a layout fault. Taken only while the gate is
            // open, so the ordinary board pays not even a save/restore for this.
            val saved = if (takeoverMs == null) -1 else canvas.save()
            if (takeoverMs != null) {
                canvas.clipRect(plate)
                canvas.translate(
                    CabinetTakeover.slip(takeoverMs, plate.width(), CabinetTakeover.BOARD), 0f
                )
            }

            val titleX = plate.centerX() - tw / 2f
            val titleY = top + lineHeight * 0.35f
            if (takeoverMs != null) {
                renderer.ghostText(
                    canvas, "HIGH SCORE", titleX, titleY, titleSize, 1.2f,
                    takeoverMs, CabinetTakeover.BOARD
                )
            }
            renderer.text(canvas, "HIGH SCORE", titleX, titleY, titleSize, 1.2f)
            topFive(persistence).forEachIndexed { i, row ->
                val y = top + lineHeight * (i + 1.35f)
                renderer.text(canvas, row.initials, left + pad, y, rowSize, 1.3f)
                val score = row.score.toString().padStart(3, '0')
                val sw = CabinetFont.width(score, rowSize)
                renderer.text(canvas, score, right - pad - sw, y, rowSize, 1.3f)
            }
            if (saved >= 0) canvas.restoreToCount(saved)

            if (takeoverMs != null) {
                renderer.dropout(canvas, plate, takeoverMs, CabinetTakeover.BOARD)
            }
        } else {
            canvas.drawText("HIGH SCORE", (left + right) / 2f, top + lineHeight * 0.7f, titlePaint)
            topFive(persistence).forEachIndexed { i, row ->
                val y = top + lineHeight * (i + 1.7f)
                canvas.drawText(row.initials, left + pad, y, titlePaint)
                canvas.drawText(row.score.toString().padStart(3, '0'), right - pad, y, valuePaint)
            }
        }

        line.color = CabinetRenderer.PHOSPHOR_GLOW
        line.alpha = 70
        canvas.drawRect(plate, line)
        line.alpha = 255
    }


    /** Best five across all pilots, which is what a cabinet shows. */
    fun topFive(persistence: PersistenceManager): List<BoardRow> = allPilots(persistence)
        .sortedByDescending { it.score }
        .take(5)

    /** All twelve in roster order — the overlay's SCORES screen, where the gate is legible. */
    fun allPilots(persistence: PersistenceManager): List<BoardRow> =
        PilotDefinitions.pilots.map {
            val score = persistence.getArcadeScore(it.id)
            BoardRow(
                PilotCabinetRules.forPilot(it.id).initials,
                score,
                score >= PersistenceManager.ARCADE_CLEAR_SCORE
            )
        }

    /**
     * The walkway silhouette, 18x32px. Same body as the slot machine's; the three tiny
     * reels and the glow strip beneath them collapse into one lit screen.
     */
    fun drawWalkwayMini(canvas: Canvas, pilotX: Float, walkwayY: Float) {
        val w = 18f
        val h = 32f
        val left = pilotX - w / 2f
        val right = pilotX + w / 2f
        val top = walkwayY - h

        fill.color = 0xFF22223A.toInt()
        canvas.drawRoundRect(RectF(left, top, right, walkwayY), 3f, 3f, fill)
        line.color = 0xFF665544.toInt()
        line.strokeWidth = 1f
        canvas.drawRoundRect(RectF(left, top, right, walkwayY), 3f, 3f, line)

        // The lit screen, replacing the reels.
        fill.color = CabinetRenderer.SCREEN
        val sTop = top + h * 0.16f
        val sBottom = top + h * 0.52f
        canvas.drawRect(left + w * 0.14f, sTop, right - w * 0.14f, sBottom, fill)
        fill.color = CabinetRenderer.PHOSPHOR_BEAM
        fill.alpha = 60
        canvas.drawRect(left + w * 0.14f, sTop, right - w * 0.14f, sBottom, fill)
        fill.alpha = 255

        // One angled line for the sloped control deck - the silhouette cue that reads
        // "arcade" rather than "fruit machine" at this size.
        line.color = 0xFF665544.toInt()
        canvas.drawLine(left + w * 0.1f, sBottom + h * 0.12f, right - w * 0.1f, sBottom + h * 0.20f, line)
    }
}
