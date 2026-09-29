package com.astroloop.game.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.astroloop.game.core.GameConfig
import com.astroloop.game.input.FocusRegistry
import com.astroloop.game.input.InputModeState
import kotlin.math.min
import kotlin.math.sin

/**
 * The focus ring: four corner brackets over a rect that already exists.
 *
 * It never changes layout — nothing reserves space for it, so a screen looks pixel-identical
 * whether or not a controller is in use. It is never drawn to a player who has only ever
 * touched the screen, and it fades out — rather than vanishing — the moment a finger arrives.
 *
 * **Why brackets and not a ring.** Every focusable surface already wears a border, and the
 * icons on those surfaces are flat line art in one colour each taken straight from the ship
 * palette — Ion Orbiters is cyan, Railgun is white, Nano Repair pink. There is no hue left
 * that some card is not already wearing, so a closed outline distinguished only by colour
 * reads as a second, badly-aligned card edge. Brackets are not a closed shape, so they cannot
 * be read as a border whatever the target or its icon is wearing, and the colour becomes a
 * free choice rather than a load-bearing one.
 *
 * The weight and the lift wash are what make that shape carry from across a room, since a TV
 * is the reason this layer exists at all.
 *
 * A disabled target still gets brackets, drawn dimmer: the player must be able to land on the
 * launch pad before the walker arrives and see that it is there but not yet usable.
 */
class FocusRingRenderer {

    private val ringPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
        isAntiAlias = true
    }

    /**
     * The lift is a wash this renderer draws itself, not a fill swap inside each screen.
     * A fill swap would mean every focusable surface — upgrade cards, store tiles, the
     * shipyard, the bar, the cabinet — has to know whether it is focused. The wash keeps this
     * class a pure overlay that works anywhere it is pointed, which is what every surface
     * that uses it needs it to be.
     */
    private val liftPaint = Paint().apply {
        style = Paint.Style.FILL
        color = LIFT_COLOR
    }

    /** One reusable Path — allocating per frame in a render loop is the thing this codebase avoids. */
    private val bracketPath = Path()

    private var pulse = 0f

    /**
     * Matches StickReadoutRenderer: fast in so it is there by the time the eye arrives, slower
     * out so a finger arriving does not cut the ring off the screen.
     */
    internal var alpha: Float = 0f
        private set

    private val fadeInPerSecond = 6f
    private val fadeOutPerSecond = 3f

    /** Test seam for the wrap. Production code reads the breath, never the raw phase. */
    internal val pulseForTest: Float get() = pulse

    fun update(deltaTime: Float) {
        // Wrapped rather than accumulated: a bare running total loses float precision over a
        // long session, which is the trap the radio wave timing already hit. One period is a
        // whole cycle, so wrapping is invisible.
        pulse = (pulse + deltaTime) % BREATH_PERIOD
        alpha = if (InputModeState.isDirectional) {
            (alpha + fadeInPerSecond * deltaTime).coerceAtMost(1f)
        } else {
            (alpha - fadeOutPerSecond * deltaTime).coerceAtLeast(0f)
        }
    }

    /**
     * [dim] is the caller's own fade — the hangar hands in the launch fade, so the ring dissolves
     * with the ship name and the peek ships rather than hanging over a ship that has already gone.
     */
    internal fun shouldDraw(registry: FocusRegistry, dim: Float = 1f): Boolean {
        if (alpha * dim <= 0f) return false
        val id = registry.focusedId ?: return false
        return registry.byId(id) != null
    }

    fun render(canvas: Canvas, registry: FocusRegistry, dim: Float = 1f) {
        if (!shouldDraw(registry, dim)) return
        val target = registry.byId(registry.focusedId!!) ?: return
        val r = target.rect

        val ring = (ringAlpha(target.enabled, pulse) * alpha * dim).toInt().coerceIn(0, 255)

        // Wash first, under the brackets, so the corners stay crisp on top of it.
        liftPaint.alpha = (Color.alpha(LIFT_COLOR) * ring / 255f).toInt().coerceIn(0, 255)
        canvas.drawRect(r, liftPaint)

        val l = r.left - OUTSET
        val t = r.top - OUTSET
        val rt = r.right + OUTSET
        val b = r.bottom + OUTSET
        val arm = armLength(rt - l, b - t)

        bracketPath.rewind()
        // Top-left
        bracketPath.moveTo(l + arm, t); bracketPath.lineTo(l, t); bracketPath.lineTo(l, t + arm)
        // Top-right
        bracketPath.moveTo(rt - arm, t); bracketPath.lineTo(rt, t); bracketPath.lineTo(rt, t + arm)
        // Bottom-left
        bracketPath.moveTo(l + arm, b); bracketPath.lineTo(l, b); bracketPath.lineTo(l, b - arm)
        // Bottom-right
        bracketPath.moveTo(rt - arm, b); bracketPath.lineTo(rt, b); bracketPath.lineTo(rt, b - arm)

        ringPaint.color = GameConfig.COLOR_POWERUP
        ringPaint.alpha = ring
        canvas.drawPath(bracketPath, ringPaint)
    }

    companion object {
        /** How far outside the target's own rect the brackets sit. */
        const val OUTSET = 8f
        const val STROKE = 6f

        /**
         * Arm length as a fraction of the *shorter* edge. Scaling off the shorter edge is what
         * keeps the brackets from meeting on a square target and closing into the border this
         * shape exists not to be — a store tile is square, an upgrade card is three times taller
         * than it is wide, and both have to read.
         */
        const val ARM_FRACTION = 0.22f

        /** A whole breath, so wrapping the phase on it lands on the same value. */
        const val BREATH_PERIOD = (2.0 * Math.PI / 3.0).toFloat()

        /**
         * Lifts the target's ground from the card fill #111122 toward #15182E at this alpha.
         * Solved for that pair rather than eyeballed: result = bg·(1−a) + c·a.
         */
        val LIFT_COLOR: Int = Color.argb(26, 56, 86, 152)

        internal fun armLength(width: Float, height: Float): Float =
            min(width, height) * ARM_FRACTION

        internal fun breathAt(pulse: Float): Float = 0.75f + 0.25f * sin(pulse * 3f)

        internal fun ringAlpha(enabled: Boolean, pulse: Float): Int =
            ((if (enabled) 255f else 110f) * breathAt(pulse)).toInt().coerceIn(0, 255)
    }
}
