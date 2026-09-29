package com.astroloop.game.render

import android.graphics.Canvas
import android.graphics.Paint
import com.astroloop.game.input.InputModeState
import kotlin.math.sqrt

/**
 * A picture of the invisible relative joystick: the zero point the finger planted, the dead
 * zone, and how far the finger has travelled toward full speed.
 *
 * A player asked for it: they could feel the stick but never see where its centre was, so full
 * speed and zero speed had to be learned by guesswork.
 *
 * Strictly cosmetic. It reads TouchController's published state and changes nothing: not the
 * dead zone, not the radius, not where the origin is planted. And it is never drawn in
 * DIRECTIONAL mode, where the player is holding a real stick.
 */
class StickReadoutRenderer {

    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val knobPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    internal var alpha: Float = 0f
        private set

    /** Fast in so it is there by the time the eye arrives; slower out so it does not cut. */
    private val fadeInPerSecond = 6f
    private val fadeOutPerSecond = 3f

    fun update(active: Boolean, deltaTime: Float) {
        alpha = if (active) {
            (alpha + fadeInPerSecond * deltaTime).coerceAtMost(1f)
        } else {
            (alpha - fadeOutPerSecond * deltaTime).coerceAtLeast(0f)
        }
    }

    internal fun shouldDraw(): Boolean = InputModeState.isTouch && alpha > 0f

    /**
     * [deadZoneRadius] and [maxRadius] are passed rather than read from GameConfig, because the
     * cabinet's stick is sized from CabinetMetrics and would otherwise be drawn at combat's
     * dimensions on a playfield that is not combat's size.
     *
     * [color] is the thing the player is actually flying — the ship's own colour in combat, red
     * for the corruption run, the tank's accent in the desert flashback, the phosphor in BELT RUN.
     * It was COLOR_HUD white to begin with, which made the readout the one piece of the HUD that
     * did not belong to the player's own machine.
     */
    fun render(
        canvas: Canvas,
        originX: Float, originY: Float,
        knobX: Float, knobY: Float,
        deadZoneRadius: Float, maxRadius: Float,
        color: Int
    ) {
        if (!shouldDraw()) return

        // Deliberately faint: this is a reference, not a control competing with the game.
        val base = (alpha * 90f).toInt().coerceIn(0, 255)

        strokePaint.color = color
        strokePaint.alpha = base
        canvas.drawCircle(originX, originY, maxRadius, strokePaint)

        strokePaint.alpha = (base * 0.6f).toInt().coerceIn(0, 255)
        canvas.drawCircle(originX, originY, deadZoneRadius, strokePaint)

        // Clamp the knob to the outer ring so it reads as "at full speed" rather than running
        // off into the screen when the finger travels past the max radius.
        val dx = knobX - originX
        val dy = knobY - originY
        val distance = sqrt(dx * dx + dy * dy)
        val scale = if (distance > maxRadius) maxRadius / distance else 1f

        knobPaint.color = color
        knobPaint.alpha = base
        canvas.drawCircle(originX + dx * scale, originY + dy * scale, 10f, knobPaint)
    }
}
