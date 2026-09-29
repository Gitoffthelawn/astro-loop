package com.astroloop.game.cabinet

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.astroloop.game.entity.Boss
import com.astroloop.game.render.CrystalPalette
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Draws BELT RUN as a vector monitor.
 *
 * Three constraints shape every line of this class, all from the design doc:
 * - **Stroke only.** The main game fills its shapes; this one never does. That single
 *   difference is what reads as "the old game" before any effect is applied.
 * - **Bloom is layered low-alpha passes**, never BlurMaskFilter and never an offscreen
 *   pass — `minSdk 24` rules out RenderEffect and the project avoids per-frame bitmaps.
 * - **No curvature and no scanlines.** Both were prototyped and cut. Do not add them.
 *
 * **Beam persistence is cut.** It was tried twice and failed twice: first as a fade of
 * the previous frame, which cannot work at all because a `SurfaceView`'s locked canvas
 * never carries the last frame's pixels; then as an explicit decaying streak, which was
 * barely visible on hardware and drew a rigid stick along the current velocity rather
 * than a trail along the path actually flown. Do not reinstate it without solving the
 * second problem — the streak has to follow position history, not the velocity vector.
 */
class CabinetRenderer(private val m: CabinetMetrics) {

    /**
     * The display's side cutouts and rounded-corner radius, in playfield pixels. Set by the
     * host alongside `CabinetShellRenderer.topInset`, and for the same reason kept out of
     * [CabinetMetrics]: they are about the glass, not the game.
     *
     * Only the top bar reads them. In portrait the top inset already pushes its text below the
     * corner arc and there are no side cutouts, so all three change nothing there; in landscape
     * the bar's text sits in the bare corners, which on a Pixel 9 Pro ate the initials and the
     * score (owner, 2026-09-28).
     */
    var leftInset: Float = 0f
    var rightInset: Float = 0f
    var cornerRadius: Float = 0f

    /** Left-most x anything readable at height [top] may use, before its own padding. */
    private fun safeLeft(top: Float): Float = maxOf(leftInset, cornerClearance(cornerRadius, top))

    /** Right-most x, mirror of [safeLeft]. */
    private fun safeRight(top: Float): Float =
        m.width - maxOf(rightInset, cornerClearance(cornerRadius, top))

    private val stroke = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val path = Path()

    /**
     * Scratch buffer for world-space polylines; avoids per-frame allocation.
     *
     * Every writer fills it and immediately consumes it — via [strokePolyline] — before anything else writes. Do not nest [forEachWrap]
     * bodies and do not draw from two threads — either would let one writer clobber
     * another's points with no error.
     */
    private val scratch = FloatArray(64)

    /**
     * Draws [body] once per wrapped position, so nothing is ever sliced at the seam.
     *
     * The field is a torus but the canvas is not. An entity straddling an edge occupies
     * BOTH sides of it, so drawing only at its stored coordinates cuts it in half at the
     * boundary — very visible on a rock, and the first thing that reads as broken.
     * Corners need all four positions.
     *
     * Deliberately allocation-free: this runs per entity per frame, so the obvious
     * build-an-array-and-iterate version would churn thousands of short-lived arrays a
     * second.
     */
    private inline fun forEachWrap(x: Float, y: Float, radius: Float, body: (Float, Float) -> Unit) {
        val offLeft = x - radius < 0f
        val offRight = x + radius > m.width
        val offTop = y - radius < 0f
        val offBottom = y + radius > m.height

        body(x, y)

        val wrapX = offLeft || offRight
        val wrapY = offTop || offBottom
        val gx = if (offLeft) x + m.width else x - m.width
        val gy = if (offTop) y + m.height else y - m.height

        if (wrapX) body(gx, y)
        if (wrapY) body(x, gy)
        if (wrapX && wrapY) body(gx, gy)
    }

    fun clearScreen(canvas: Canvas) {
        fill.color = SCREEN
        canvas.drawRect(0f, 0f, m.width, m.height, fill)
    }

    /**
     * The house glow technique — two enlarged low-alpha passes behind a hot core, the
     * same approach `BarPageRenderer.kt:887` uses. Never BlurMaskFilter.
     *
     * [alphaScale] fades the whole stack together. It exists so callers that need a
     * fade — the wave banner, a dimmed button — do not reach for a saveLayer: an
     * offscreen pass per frame is exactly what this renderer is built to avoid.
     */
    private fun strokePath(
        canvas: Canvas, weight: Float, alphaScale: Float = 1f, p: Phosphor = ICE
    ) {
        val a = alphaScale.coerceIn(0f, 1f)

        stroke.color = p.glow
        stroke.alpha = (26 * a).toInt()
        stroke.strokeWidth = weight * 6.5f
        canvas.drawPath(path, stroke)

        stroke.color = p.beam
        stroke.alpha = (56 * a).toInt()
        stroke.strokeWidth = weight * 2.9f
        canvas.drawPath(path, stroke)

        stroke.color = p.core
        stroke.alpha = (255 * a).toInt()
        stroke.strokeWidth = weight
        canvas.drawPath(path, stroke)
    }

    fun strokePolyline(
        canvas: Canvas, pts: FloatArray, count: Int, closed: Boolean, weight: Float,
        alphaScale: Float = 1f, p: Phosphor = ICE
    ) {
        if (count < 4) return
        path.reset()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < count) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        if (closed) path.close()
        strokePath(canvas, weight, alphaScale, p)
    }

    /**
     * A rectangular outline, glowing like everything else this renderer draws rather than
     * sitting flat on top of it. Exists so callers with a tap rect (the shell's buttons)
     * can box it without reaching into [strokePolyline] and the scratch buffer themselves.
     */
    fun strokeBox(canvas: Canvas, rect: RectF, weight: Float = 1.2f) {
        scratch[0] = rect.left; scratch[1] = rect.top
        scratch[2] = rect.right; scratch[3] = rect.top
        scratch[4] = rect.right; scratch[5] = rect.bottom
        scratch[6] = rect.left; scratch[7] = rect.bottom
        strokePolyline(canvas, scratch, 8, true, weight)
    }

    /**
     * Draws [s] with its top-left at [x],[y]. [size] is the cap height.
     *
     * [p] exists for a design decision's colour separation and for nothing else. It defaults to
     * [ICE], so every existing caller is unchanged: the cabinet's own lettering is ice
     * white and a design decision's single-phosphor rule still holds. The one thing allowed to
     * break it is the crystal, exactly as on the playfield — see [CORRUPTION]'s own doc.
     */
    fun text(
        canvas: Canvas, s: String, x: Float, y: Float, size: Float,
        weight: Float = 1.3f, alphaScale: Float = 1f, p: Phosphor = ICE
    ) {
        val u = size / CabinetFont.EM
        var cx = x
        for (ch in s) {
            CabinetFont.glyph(ch)?.forEach { strokeData ->
                path.reset()
                path.moveTo(cx + strokeData[0] * u, y + strokeData[1] * u)
                var i = 2
                while (i < strokeData.size) {
                    path.lineTo(cx + strokeData[i] * u, y + strokeData[i + 1] * u)
                    i += 2
                }
                strokePath(canvas, weight, alphaScale, p)
            }
            cx += CabinetFont.ADVANCE * u
        }
    }

    /**
     * A design decision's colour separation: [text]'s own stroke drawn a second time, displaced,
     * in the crystal's red. Draw it BEFORE the real lettering so the readable copy stays on
     * top — a ghost over the top is the version that stops a player finding PLAY.
     *
     * A second stroked pass, not an offscreen one, and the cost is countable rather than
     * estimated. `BELT RUN` is 12 strokes in [CabinetFont] and each goes through
     * [strokePath]'s three passes, so the ghost is 36 `drawPath` calls against a menu device
     * pass measured at ~1000. `HIGH SCORE` is 17 strokes, 51 calls. It is paid only inside
     * an episode, and [CabinetTakeover] holds those to a 9.2% duty cycle: 3.6% of a menu
     * frame while it is happening, ~0.3% averaged. Frame time stays where it was.
     */
    fun ghostText(
        canvas: Canvas, s: String, x: Float, y: Float, size: Float, weight: Float,
        timeMs: Long, surface: Int
    ) {
        val g = CabinetTakeover.intensity(timeMs)
        if (g <= 0f) return
        text(
            canvas, s, x + CabinetTakeover.ghost(timeMs, size, surface), y, size, weight,
            g * GHOST_ALPHA, CORRUPTION
        )
    }

    /**
     * A design decision's dropped rows: a band or two of lost signal across [rect], each closed by
     * a hot line in the crystal's red — the tear is the crystal's, not the tube's.
     *
     * **This is not the scanlines this class's header refuses**, and must not be read as
     * reopening them. Those were a permanent texture laid over the whole picture, which is
     * what got them cut. This is at most two rects, at most 340ms at a time, and nothing at
     * all on ~91% of frames: [CabinetTakeover.bandCount] returns 0 outside an episode and
     * this returns immediately. Do not promote it to something continuous.
     *
     * Costs four `drawRect` calls at the very worst, and needs no `saveLayer` because a
     * dropped row is opaque — it replaces the picture rather than compositing with it, which
     * is precisely why it was chosen over the offscreen effects `minSdk 24` rules out anyway.
     */
    fun dropout(canvas: Canvas, rect: RectF, timeMs: Long, surface: Int) {
        val g = CabinetTakeover.intensity(timeMs)
        if (g <= 0f) return
        val bands = CabinetTakeover.bandCount(timeMs, surface)
        for (i in 0 until bands) {
            val h = CabinetTakeover.bandHeightFrac(timeMs, surface, i) * rect.height()
            val top = rect.top + CabinetTakeover.bandTopFrac(timeMs, surface, i) * rect.height()

            fill.color = SCREEN
            canvas.drawRect(rect.left, top, rect.right, top + h, fill)

            // Drawn as a rect rather than through `stroke`, deliberately: drawDebris's doc
            // names itself the sole direct consumer of that paint outside strokePath's
            // discipline, and a second one would quietly make that false.
            fill.color = CORRUPTION.beam
            fill.alpha = (TEAR_ALPHA * g).toInt().coerceIn(0, 255)
            val tear = (h * 0.14f).coerceAtLeast(1f)
            canvas.drawRect(rect.left, top + h - tear, rect.right, top + h, fill)
            fill.alpha = 255
        }
    }

    fun drawPlayfield(canvas: Canvas, sim: CabinetSim, showWaveBanner: Boolean = true) {
        for (rock in sim.rocks) drawRock(canvas, rock)

        for (d in sim.debris) drawDebris(canvas, d)

        sim.crystal?.let { drawCrystal(canvas, it) }

        for (b in sim.bullets) {
            val sp = hypot(b.vx, b.vy)
            if (sp <= 0f) continue
            val tailX = b.vx / sp * m.minEdge * 0.008f
            val tailY = b.vy / sp * m.minEdge * 0.008f
            // A streak, not a dot: on a tube with persistence a moving point already is one.
            if (b.hostile) {
                // Hostile shots do NOT wrap, so drawing them wrapped would show a copy
                // at a seam the bullet will never actually reach - a phantom threat.
                scratch[0] = b.x; scratch[1] = b.y
                scratch[2] = b.x - tailX; scratch[3] = b.y - tailY
                strokePolyline(canvas, scratch, 4, false, 2.1f, 1f, CORRUPTION)
            } else {
                forEachWrap(b.x, b.y, m.minEdge * 0.008f) { px, py ->
                    scratch[0] = px; scratch[1] = py
                    scratch[2] = px - tailX; scratch[3] = py - tailY
                    strokePolyline(canvas, scratch, 4, false, 2.1f)
                }
            }
        }

        if (sim.ship.alive) drawShip(canvas, sim.ship.x, sim.ship.y, sim.ship.heading)

        if (showWaveBanner && sim.waveBanner > 0f) drawWaveBanner(canvas, sim.wave, sim.waveBanner)
    }

    /**
     * WAVE N, centred, fading out over its last half second so it leaves rather than
     * disappears — that is asked of everything on screen, text included.
     */
    private fun drawWaveBanner(canvas: Canvas, wave: Int, remaining: Float) {
        val label = "WAVE $wave"
        val size = m.minEdge / 14f
        val alpha = (remaining / (CabinetSim.WAVE_BANNER_SECONDS * 0.5f)).coerceIn(0f, 1f)
        text(canvas, label, m.width / 2f - CabinetFont.width(label, size) / 2f,
            m.height * 0.42f, size, 1.8f, alphaScale = alpha)
    }

    private fun drawShip(canvas: Canvas, x: Float, y: Float, heading: Float) {
        val c = cos(heading); val s = sin(heading)
        val r = m.shipRadius
        forEachWrap(x, y, r * 1.7f) { px, py ->
            var i = 0
            for (p in CabinetShip.HULL) {
                val lx = p[0] * r; val ly = p[1] * r
                scratch[i++] = px + (lx * c - ly * s)
                scratch[i++] = py + (lx * s + ly * c)
            }
            strokePolyline(canvas, scratch, i, true, 1.6f)
        }
    }

    /**
     * One free-drifting edge of something that came apart.
     *
     * Drawn straight rather than through [strokePath] so the fade can drive alpha
     * directly — a dying line should dim, not bloom harder as it goes.
     */
    private fun drawDebris(canvas: Canvas, d: CabinetDebris) {
        val c = cos(d.rot); val s = sin(d.rot)
        val ax = d.x1 * c - d.y1 * s
        val ay = d.x1 * s + d.y1 * c
        val bx = d.x2 * c - d.y2 * s
        val by = d.x2 * s + d.y2 * c
        val reach = hypot(ax, ay).coerceAtLeast(hypot(bx, by))

        val savedColor = stroke.color
        val savedAlpha = stroke.alpha
        val savedStrokeWidth = stroke.strokeWidth

        stroke.color = PHOSPHOR_CORE
        stroke.alpha = (255 * d.fade).toInt().coerceIn(0, 255)
        stroke.strokeWidth = 1.5f
        forEachWrap(d.x, d.y, reach) { px, py ->
            path.reset()
            path.moveTo(px + ax, py + ay)
            path.lineTo(px + bx, py + by)
            canvas.drawPath(path, stroke)
        }
        // Restore all three paint properties symmetrically. drawDebris is the sole direct
        // consumer of stroke outside strokePath's discipline. The next direct call must not
        // inherit this function's state; symmetric restore enforces that contract.
        stroke.color = savedColor
        stroke.alpha = savedAlpha
        stroke.strokeWidth = savedStrokeWidth
    }

    /**
     * The crystal at its own position — what the fight draws every frame.
     *
     * Shape and reasoning live on [drawCrystalAt], which does the work; this only supplies
     * the crystal's own position, radius and health.
     *
     * **Damage dims the ring; it never removes pieces of it.** A facet disappearing as HP
     * falls would be an instant disappearance on every hit — the no-vanishing rule applies
     * to the thing you are killing as much as to the things killing you. The health bar carries
     * the precise number; this carries the impression.
     *
     * ⚠️ **This header has been wrong three times and it is worth not re-acquiring any of
     * them.** It said *"The shell is gone"* and cited an `ORB_HALO_FRAC` of 1.0; then it
     * described the orb as matching the main game's size, which it does not (see
     * [drawCrystalAt]); then a lattice replaced the orb outright on the reading that there
     * is no crystal to draw. A design decision settled it: there IS one in the fight, and there
     * is not one on a replay, which is the distinction the other three all missed.
     *
     * Not wrapped: it is anchored at the field's centre and its radius cannot reach a
     * seam, so `forEachWrap` would cost nine position tests to produce one draw.
     */
    fun drawCrystal(canvas: Canvas, c: CabinetCrystal) {
        // The crystal carries whether it has a body — see [CabinetCrystal.hasBody]. It used to
        // be a parameter threaded down from the host, which every screen drawing the playfield
        // had to remember to set; GAME OVER did not, so a replay's empty shell grew its crystal
        // back the moment you died in it.
        drawCrystalAt(canvas, c.x, c.y, c.radius(m), c.healthFrac, c.hasBody)
    }

    /**
     * The crystal, at an arbitrary position — so the entrance can fly it in.
     *
     * **The ring, and the crystal inside it when [hasCrystal].** The ring is the hitbox — a
     * corruption-red circle marking exactly what you are shooting at, in the same red as the
     * bullets because it is the same thing throwing them. Inside it, the Time Crystal's own
     * orb, at the game's numbers and its own pulse.
     *
     * **[hasCrystal] is false on a replay** — a design decision. You released it; the ??? entry
     * runs a recording, so what you fight there is the containment with nothing in it. The
     * voice substitutes on the same flag, [ReckoningRun.crystalHasBody].
     *
     * ⚠️ **The orb is NOT the same apparent size as the main game's**, whatever the comment
     * that shipped with it said. Its 10/4 radii were copied literally from
     * `CrystalRenderer.renderTimeCrystalOrb` under a comment claiming that made it *"the
     * same object rather than a scaled likeness"*. **That claim was false, and the reason is
     * a units mismatch nobody checked.** The main game and the hangar draw in DESIGN SPACE —
     * `GameConfig.DESIGN_WIDTH` 960 x `DESIGN_HEIGHT` 2142, through a single
     * `canvas.scale(renderScale, renderScale)` — so that `10f` is ten DESIGN units and
     * becomes 13.3 physical pixels on a Pixel 9 Pro. **This renderer draws in raw device
     * pixels** (`CabinetMetrics` is built from the view's own `width`/`height`), so the same
     * literal 10 was ten PHYSICAL pixels: three quarters the size of the object it was
     * copying, and a different fraction of it on every screen.
     *
     * That is also why the ring-to-orb ratio drifted per device — 2.45:1 at 720 minEdge,
     * 3.67:1 at 1080, 4.90:1 at 1440 — while the main game's crystal, scaling uniformly with
     * everything else, never had the problem at all. A design decision removed the orb for a story
     * reason; keeping its replacement proportional to [radius] is what stops the units
     * mismatch returning behind it.
     */
    fun drawCrystalAt(
        canvas: Canvas, x: Float, y: Float, radius: Float, health: Float,
        hasCrystal: Boolean = true
    ) {
        // The shell: a plain circle, corruption red, dimming as it dies. No spikes — a device
        // pass asked for exactly a circle, and a jagged silhouette read as a creature
        // rather than as a containment around something.
        val lit = 0.45f + 0.55f * health
        var i = 0
        for (v in 0 until SHELL_SEGMENTS) {
            val a = (v.toFloat() / SHELL_SEGMENTS) * TWO_PI
            scratch[i++] = x + cos(a) * radius
            scratch[i++] = y + sin(a) * radius
        }
        strokePolyline(canvas, scratch, i, true, 1.4f, lit, CORRUPTION)

        // AND THE CRYSTAL INSIDE IT, WHEN THERE IS ONE — a design decision. On a replay there
        // is not: you already released it, so the ??? entry is the cabinet running a
        // recording and what you fight is the containment with nothing in it. The same
        // fact drives the voice, which is why both read [ReckoningRun.crystalHasBody]
        // rather than each deciding for itself.
        if (!hasCrystal) return

        // The orb, at the game's own numbers and its own pulse.
        val t = (System.currentTimeMillis() % 10000L) / 1000f
        val pulse = 0.7f + 0.3f * sin(t * ORB_PULSE_RATE)
        fill.color = CrystalPalette.MID
        fill.alpha = (pulse * ORB_HALO_ALPHA).toInt()
        canvas.drawCircle(x, y, ORB_HALO_PX, fill)
        fill.alpha = (pulse * ORB_CORE_ALPHA).toInt()
        canvas.drawCircle(x, y, ORB_CORE_PX, fill)
        fill.alpha = 255
    }

    private fun drawRock(canvas: Canvas, rock: CabinetRock) {
        val r = rock.radius(m)
        val c = cos(rock.rot); val s = sin(rock.rot)
        val n = rock.shape.size / 2
        // Rocks are the biggest things on screen and the most obvious when sliced,
        // so the 1.3 allows for the shape's own radial variance pushing past r.
        forEachWrap(rock.x, rock.y, r * 1.3f) { px, py ->
            var i = 0
            for (v in 0 until n) {
                val lx = rock.shape[v * 2] * r
                val ly = rock.shape[v * 2 + 1] * r
                scratch[i++] = px + (lx * c - ly * s)
                scratch[i++] = py + (lx * s + ly * c)
            }
            strokePolyline(canvas, scratch, i, true, 1.5f)
        }
    }

    /**
     * The top bar: initials at the left, score at the right, over a full-width gel.
     *
     * [gelColor] must already have been passed through
     * `StoryStateManager.corruptColor()`; the halving is what keeps the score legible
     * against the bright pilot colours (Havoc and Dash are near-white at full value).
     *
     * [topInset] is the display cutout. The GEL still runs to y=0 — a black strip above
     * a coloured bar would look like a rendering fault — but nothing readable is drawn
     * inside the inset. Deliberately a draw parameter rather than part of
     * [CabinetMetrics]: the sim stays resolution-independent, which is the property that
     * makes the fairness maths device-independent.
     *
     * **Reverses an earlier design decision**,
     * which chose full opacity. That decision's reasoning was about colour wash and
     * legibility only; it never considered that the playfield is full-bleed underneath,
     * so a fully opaque gel hides every rock — and the player's own ship — the instant
     * either passes under the bar. Getting killed by something you cannot see is the
     * worst kind of unfair, so the gel goes translucent instead. Score and initials keep
     * full brightness and their three-pass glow, which is what carries them over the wash.
     *
     * **No bottom rule.** The bar used to close with a full-width ICE stroke along
     * `barHeight` — a 1px `#EAFBFF` core inside a ~6.5px cyan bloom, the renderer's default
     * phosphor. It was never a considered choice, just [strokePolyline]'s default colour, and
     * on device it read as a white stripe laid across the screen under the pilot's gel rather
     * than as part of the bar. Owner's call, 2026-09-03: dropped. The gel is translucent, so
     * its own edge against the playfield is all the separation the bar needs.
     *
     * If an edge is ever wanted back, tint it to [gelColor] rather than restoring the default
     * ICE — a white lid on a coloured band is the thing that looked wrong — and put it back in
     * BOTH this and [drawCrystalBar], whose geometry is a verbatim copy of this one's.
     */
    fun drawTopBar(
        canvas: Canvas,
        rules: CabinetRules,
        score: Int,
        gelColor: Int,
        topInset: Float,
        line: String? = null,
        showScore: Boolean = true
    ) {
        val size = m.minEdge / 26f
        val barHeight = topInset + size * 2.2f

        // Color must be set before alpha — Paint.setColor() resets alpha to 255.
        fill.color = gelColor
        fill.alpha = GEL_ALPHA
        canvas.drawRect(0f, 0f, m.width, barHeight, fill)
        fill.alpha = 255

        val y = topInset + size * 0.6f
        // The glyph box's TOP binds: the corner arc is narrowest there.
        text(canvas, rules.initials, safeLeft(y) + size * 0.7f, y, size, 1.5f)

        // No readout at all when the run does not score, rather than a readout of zero.
        //
        // The reckoning turns scoring off from its first frame (the entrance is not free play),
        // so through the authored opening the bar sat there reading 000 until the crystal landed
        // and the health bar took over. A three-digit zero is not "no score" to a player, it is a
        // score of nothing — it reads as a run going badly, in the twelve seconds meant to be
        // building dread. Absence says it properly.
        if (showScore) {
            val scoreText = score.toString().padStart(3, '0')
            text(canvas, scoreText, safeRight(y) - size * 0.7f - CabinetFont.width(scoreText, size), y, size, 1.5f)
        }

        // The crystal can speak over the ORDINARY bar too. Through the opening the fight
        // has not started, the score is still the player's readout, and the crystal is
        // already talking — so the line has to live here as well as on the health bar.
        if (line != null) voiceLine(canvas, line, topInset)
    }

    /**
     * The crystal's voice in the strip, at a fixed height so it does not jump when the bar
     * beneath it changes from the score to the crystal's health. It should read as one
     * continuous voice, not as two readouts that happen to carry text.
     */
    private fun voiceLine(canvas: Canvas, line: String, topInset: Float) {
        val voiceSize = m.minEdge * VOICE_SIZE_FRAC
        val w = CabinetFont.width(line, voiceSize)
        val y = topInset + m.minEdge * BAR_Y_FRAC +
            m.minEdge * BAR_THICKNESS_FRAC / 2f + voiceSize * VOICE_GAP_FRAC
        text(canvas, line, (m.width - w) / 2f, y, voiceSize, 1.3f, p = ICE)
    }

    /**
     * The reckoning's readout: the same strip, carrying the crystal's health instead of
     * initials and a score.
     *
     * The score half is dropped because there are no rocks during the fight, so it would
     * read 0 for the whole climax and look broken. The gel stays — it is
     * the pilot's colour and the one thing on screen that says who is flying.
     *
     * The fill drains from both ends toward the middle rather than left-to-right: this is
     * a thing being destroyed, not a task being completed, and a progress bar reads as the
     * latter.
     *
     * GEOMETRY IS SHARED WITH [drawTopBar] — same gel rect, same inset handling. Do not
     * recompute it here; a second set of bounds is how one of them drifts on a cutout
     * device.
     */
    fun drawCrystalBar(
        canvas: Canvas,
        healthFrac: Float,
        gelColor: Int,
        topInset: Float,
        line: String? = null
    ) {
        // 1. Gel: identical to drawTopBar's, lifted verbatim. Neither draws a bottom rule.
        val size = m.minEdge / 26f
        val barHeight = topInset + size * 2.2f

        // Color must be set before alpha — Paint.setColor() resets alpha to 255.
        fill.color = gelColor
        fill.alpha = GEL_ALPHA
        canvas.drawRect(0f, 0f, m.width, barHeight, fill)
        fill.alpha = 255

        // 2. The health fill, drained from both ends.
        val h = healthFrac.coerceIn(0f, 1f)
        val inset = m.minEdge * 0.03f
        val barY = topInset + m.minEdge * BAR_Y_FRAC
        val thickness = m.minEdge * BAR_THICKNESS_FRAC
        // Same corner/cutout clearance as [drawTopBar]'s text: the bar's ends sit in the corners.
        val left = safeLeft(barY - thickness) + inset
        val right = safeRight(barY - thickness) - inset
        val span = (right - left) * h
        val cx = (left + right) / 2f

        scratch[0] = cx - span / 2f; scratch[1] = barY
        scratch[2] = cx + span / 2f; scratch[3] = barY
        strokePolyline(canvas, scratch, 4, false, thickness, 1f, CORRUPTION)

        // 3. The empty channel, dim, so the bar has a length even when nearly dead —
        //    a fill with no track leaves the player unable to see how much is gone.
        scratch[0] = left; scratch[1] = barY
        scratch[2] = right; scratch[3] = barY
        strokePolyline(canvas, scratch, 4, false, thickness * 0.35f, 0.25f, CORRUPTION)

        // 4. The crystal, talking — a design decision. Beneath the bar rather than over it: the
        //    health readout is the thing being consulted under pressure and must never be
        //    the thing a line of dialogue sits on top of. In CORRUPTION, because this is
        //    WHITE, not CORRUPTION. The red was the argument-from-authorship — this is
        //    the crystal talking, so it should be the crystal's colour — and it lost to the
        //    screen: red text on the red gel strip, at 27px, over a red health bar. Owner,
        //    device pass: "I want the text in the gel to be white". The bar beneath it
        //    stays red, which is what keeps the strip the crystal's.
        if (line != null) voiceLine(canvas, line, topInset)
    }

    companion object {
        const val SCREEN = 0xFF000206.toInt()

        /**
         * How far in from a side edge a rounded display corner of [radius] reaches at height
         * [top] below the top edge. 0 at or below the arc, and 0 for a square corner. Same arc
         * as `ScreenLayout.cornerSafeRight`, measured from the edge instead of as an x.
         */
        fun cornerClearance(radius: Float, top: Float): Float {
            if (radius <= 0f) return 0f
            val leg = radius - top.coerceIn(0f, radius)
            return radius - kotlin.math.sqrt(radius * radius - leg * leg)
        }

        /**
         * Top bar gel opacity, ~62%. Balances two things pulling in opposite directions:
         * low enough that rocks — and the player's own ship — stay visible passing under
         * the bar, high enough that the score stays legible against the brighter pilot
         * colours (Havoc and Dash are near-white yellows even after `corruptColor()`
         * halves them). See [drawTopBar]'s KDoc for why this replaced full opacity.
         */
        const val GEL_ALPHA = 158

        /** Where the health bar sits inside the strip, and how heavy its beam is. */
        const val BAR_Y_FRAC = 0.028f
        const val BAR_THICKNESS_FRAC = 0.008f

        /** The crystal's voice in the gel strip: 27px at reference. */
        const val VOICE_SIZE_FRAC = 0.025f

        /** Clearance between the health bar and the line beneath it, in text sizes. */
        const val VOICE_GAP_FRAC = 0.55f

        /** Ice white. Chosen over amber and green in design review. */
        const val PHOSPHOR_CORE = 0xFFEAFBFF.toInt()
        const val PHOSPHOR_BEAM = 0xFF9FE8FF.toInt()
        const val PHOSPHOR_GLOW = 0xFF3FA8CC.toInt()

        /**
         * One beam colour in its three glow passes: hot core, mid beam, wide halo.
         *
         * Exists because `strokePath` used to hardcode the ice phosphor, which made the
         * whole tube monochrome by construction — fine while the cabinet drew only its
         * own game, impossible once the crystal arrived.
         */
        class Phosphor(val core: Int, val beam: Int, val glow: Int)

        /** The cabinet's own beam. A design decision: ice white, not amber and not green. */
        val ICE = Phosphor(PHOSPHOR_CORE, PHOSPHOR_BEAM, PHOSPHOR_GLOW)

        /**
         * The crystal's. A design decision — corruption red, and the ONE exception to the
         * monochrome playfield.
         *
         * That is not a contradiction: a design decision makes the playfield single-phosphor so
         * pilot identity has to come from the gel over the score strip. The crystal is
         * the one thing on this screen that is not the cabinet's own game, and it is
         * supposed to look like it does not belong.
         */
        val CORRUPTION = Phosphor(0xFFFF2A2A.toInt(), 0xFFE02020.toInt(), Boss.CORRUPTION_COLOR)

        /*
         * **THE CORE IS THE COLOUR. Everything else is a halo.**
         *
         * Two device passes were spent on this and both fixes missed, so the mechanism is
         * written down here rather than left to be re-derived. [strokePath] draws three
         * passes: glow at alpha 26, beam at alpha 56, and core at alpha **255**. The core is
         * fully opaque and one pixel wide, so it is essentially all of what the eye
         * receives; beam and glow only tint the bloom around it.
         *
         * The first attempt raised the BEAM's saturation — a layer that is 22% opaque. The
         * second set the core to pure WHITE on the theory that a hue-neutral centre would
         * let the beam speak, which is exactly backwards at these alphas: it produced a
         * white line inside a red halo, which is the textbook recipe for pink.
         *
         * So the core is a vivid red now. ICE gets away with a near-white core because the
         * cabinet's own beam is *supposed* to look white-hot; the crystal is not, and the
         * one exception a design decision grants it is the whole point of it being red.
         */

        private const val TWO_PI = (2.0 * Math.PI).toFloat()

        /** Segments in the shell circle — enough to read as round rather than as a polygon. */
        const val SHELL_SEGMENTS = 24

        /**
         * The orb's radii, copied from `CrystalRenderer.renderTimeCrystalOrb`.
         *
         * ⚠️ **Not the same apparent size as the main game's, and that is worth knowing.**
         * The main game draws in DESIGN space — 960x2142 through one
         * `canvas.scale(renderScale, renderScale)` — so its `10f` becomes 13.3 physical
         * pixels on a Pixel 9 Pro. This renderer draws in RAW DEVICE PIXELS, so the same
         * literal is 10. The old comment here claimed copying the numbers made it "the same
         * object rather than a scaled likeness"; it makes it three quarters the size, and a
         * different fraction of the ring on every screen. Kept as it was because this is
         * the size that has been flown and liked — not because the reasoning held.
         */
        const val ORB_HALO_PX = 10f
        const val ORB_CORE_PX = 4f

        /** Carried over unchanged so it pulses like the object it is copying. */
        const val ORB_PULSE_RATE = 4f
        const val ORB_HALO_ALPHA = 100f
        const val ORB_CORE_ALPHA = 220f

        /**
         * Brightness of a design decision's displaced red copy, relative to the real lettering.
         *
         * Below full so the ghost reads as a fringe rather than as a second, competing word.
         * It is multiplied by the episode's own intensity on top of this.
         */
        const val GHOST_ALPHA = 0.6f

        /** Peak alpha of the red line closing a dropped row. */
        const val TEAR_ALPHA = 150f
    }
}
