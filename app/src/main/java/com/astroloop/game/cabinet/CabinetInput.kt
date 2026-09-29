package com.astroloop.game.cabinet

import kotlin.math.hypot

/**
 * BELT RUN's control: the main game's relative virtual joystick.
 *
 * An origin is planted wherever the finger lands, and the output is the vector from
 * that origin to the finger — dead-zoned, clamped, normalised into direction x
 * magnitude. **The ship's position is never consulted.**
 *
 * That is the entire difference from what a device pass shipped, and it fixes three
 * things at once. The ship no longer chases and then orbits a stationary finger. Thrust
 * no longer sags as the ship approaches the touch point. And a still finger inside the
 * dead zone now means genuinely nothing, which is what let the double-tap slop gate and
 * its time-promotion fallback be deleted outright rather than tuned.
 *
 * It also retired an open question rather than answering it: with no ship-relative
 * delta left, there is nothing for the wrap to disagree about, so the feature is
 * toroidal everywhere with no exceptions.
 *
 * Pure — no Android, no MotionEvent. [deadZone] and [maxRadius] are injected in
 * playfield pixels; the host passes `CabinetMetrics.stickDeadZone` / `.stickRadius`.
 */
class CabinetInput(
    private val deadZone: Float,
    private val maxRadius: Float
) {
    init {
        require(maxRadius > deadZone) {
            "maxRadius ($maxRadius) must be greater than deadZone ($deadZone), or magnitude " +
                "division in recompute() divides by zero or a negative span"
        }
    }

    var active: Boolean = false
        private set

    /** Direction x magnitude, magnitude 0..1. Zero whenever the stick is centred. */
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set

    /**
     * The stick is actually asking for thrust: a finger is down AND it has left the dead
     * zone. [active] alone is not that — [down] sets it before [recompute] zeroes the
     * vector, so a thumb resting where it landed is active with no output.
     *
     * One definition, because the host needs this in two places that must agree: the sim
     * and the thrust sound take it from `update`, the flame behind the ship takes it from
     * `render`, and keying the flame on [active] instead drew an engine burning silently
     * behind a coasting ship.
     */
    val steering: Boolean get() = active && (x != 0f || y != 0f)

    // Exposed read-only for StickReadoutRenderer, which draws where the finger planted the
    // stick and where it has travelled to. Still only written in here.
    var originX = 0f
        private set
    var originY = 0f
        private set
    var currentX = 0f
        private set
    var currentY = 0f
        private set

    /** The cabinet's stick is sized from CabinetMetrics, so the readout is told, not assuming. */
    val deadZoneRadius: Float get() = deadZone
    val stickRadius: Float get() = maxRadius

    fun down(px: Float, py: Float) {
        originX = px; originY = py
        currentX = px; currentY = py
        active = true
        recompute()
    }

    fun move(px: Float, py: Float) {
        if (!active) return
        currentX = px; currentY = py
        recompute()
    }

    /**
     * Lift the finger.
     *
     * @return true if the gesture never left the dead zone — a tap, not a drag. This is
     *   the same test `TouchController` uses, and it is what double-tap-to-pause is
     *   built on.
     */
    fun up(): Boolean {
        if (!active) return false
        val wasTap = hypot(currentX - originX, currentY - originY) < deadZone
        active = false
        x = 0f; y = 0f
        return wasTap
    }

    /** Abandon the gesture. Never reports a tap — a cancel must not pause anything. */
    fun cancel() {
        active = false
        x = 0f; y = 0f
    }

    /**
     * Drive the stick from a controller instead of a finger.
     *
     * Writes the same x/y the touch path computes, so BELT RUN's flight, its fairness maths and
     * its pause detection are all untouched — this is a second way to fill one output, not a
     * second control model.
     */
    fun setExternal(directionX: Float, directionY: Float, magnitude: Float) {
        active = magnitude > 0f
        x = directionX * magnitude
        y = directionY * magnitude
    }

    /**
     * Back to rest, and back to the finger.
     *
     * The drag origin is cleared along with the output: down() would otherwise measure the next
     * finger against a stale origin and read as a full-radius deflection on the first move.
     */
    fun clearExternal() {
        active = false
        x = 0f
        y = 0f
        originX = 0f
        originY = 0f
        currentX = 0f
        currentY = 0f
    }

    private fun recompute() {
        val dx = currentX - originX
        val dy = currentY - originY
        val d = hypot(dx, dy)
        if (d < deadZone) { x = 0f; y = 0f; return }
        val clamped = d.coerceAtMost(maxRadius)
        val mag = ((clamped - deadZone) / (maxRadius - deadZone)).coerceIn(0f, 1f)
        x = dx / d * mag
        y = dy / d * mag
    }
}
