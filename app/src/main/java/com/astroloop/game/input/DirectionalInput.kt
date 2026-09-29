package com.astroloop.game.input

import com.astroloop.game.util.Vector2
import kotlin.math.sqrt

/**
 * Held directional state from any device, reduced to the pair the game already speaks:
 * a unit [direction] and a 0..1 [magnitude].
 *
 * This is deliberately the same shape TouchController emits, so ship movement consumes a
 * gamepad, a keyboard and a finger through one code path. Digital sources always report
 * full magnitude — a D-pad has no half-press — which is why digital flight is 8 headings
 * at full speed. That is accepted, not an oversight: see a design decision in the design doc.
 */
class DirectionalInput {

    val direction = Vector2()

    var magnitude: Float = 0f
        private set

    val isActive: Boolean get() = magnitude > 0f

    /**
     * @param flat the device's own reported dead zone, from
     *   `InputDevice.getMotionRange(axis, source).flat` — never a hardcoded constant.
     */
    fun setStick(axisX: Float, axisY: Float, flat: Float) {
        val distance = sqrt(axisX * axisX + axisY * axisY)
        if (distance <= flat) {
            clear()
            return
        }
        // Rescale from the flat edge so the first movement past the dead zone is gentle
        // rather than a jump to whatever fraction the hardware happened to report.
        val span = 1f - flat
        magnitude = if (span <= 0f) 1f else ((distance - flat) / span).coerceIn(0f, 1f)
        direction.set(axisX / distance, axisY / distance)
    }

    fun setDigital(left: Boolean, right: Boolean, up: Boolean, down: Boolean) {
        val dx = (if (right) 1f else 0f) - (if (left) 1f else 0f)
        val dy = (if (down) 1f else 0f) - (if (up) 1f else 0f)
        if (dx == 0f && dy == 0f) {
            clear()
            return
        }
        // Normalise, or a held diagonal would be 1.41x speed.
        val length = sqrt(dx * dx + dy * dy)
        direction.set(dx / length, dy / length)
        magnitude = 1f
    }

    fun clear() {
        direction.zero()
        magnitude = 0f
    }

    /**
     * Snapshot another instance. The router owns one DirectionalInput and mutates it in place,
     * so a surface that wants to read it on the render thread needs its own copy rather than a
     * reference that changes underneath a frame.
     */
    fun copyFrom(other: DirectionalInput) {
        direction.set(other.direction)
        magnitude = other.magnitude
    }
}
