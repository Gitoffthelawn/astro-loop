package com.astroloop.game.cabinet

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * The player ship.
 *
 * Flies the way the main game's ship flies: a relative virtual joystick sets a target
 * velocity and the real velocity LERPS toward it. Heading follows travel and is
 * cosmetic to the motion — but it is what the gun fires along, which is what keeps
 * "shooting the crystal means flying at it" true when stage 2 lands.
 *
 * Pure: no Canvas, no Android. The one behaviour worth stating plainly is that this
 * ship does NOT stop when you let go — [CabinetMetrics.drag] is roughly a fifth of the
 * main game's, and the resulting glide is the cabinet's whole character. A device pass
 * replaced the steering above it and deliberately left the glide alone.
 */
class CabinetShip(private val m: CabinetMetrics) {

    var x: Float = m.width / 2f
    var y: Float = m.height / 2f
    var vx: Float = 0f
    var vy: Float = 0f

    /** Radians. 0 points up the screen, matching the hull's nose-up local space. */
    var heading: Float = 0f

    var alive: Boolean = true

    fun speed(): Float = hypot(vx, vy)

    /**
     * @param inputX the joystick vector's x, already direction x magnitude, magnitude 0..1
     * @param inputY the joystick vector's y, same contract
     * @param hasInput false when the finger is up, or resting inside the dead zone —
     *   either way the ship coasts. Under a relative stick a still finger is genuinely
     *   no input, which is why the old "held finger must keep flying" special case is gone.
     */
    fun update(dt: Float, inputX: Float, inputY: Float, hasInput: Boolean) {
        val raw = hypot(inputX, inputY)

        if (hasInput && raw > 0.001f) {
            val mag = raw.coerceAtMost(1f)
            val dirX = inputX / raw
            val dirY = inputY / raw

            val targetVx = dirX * m.topSpeed * mag
            val targetVy = dirY * m.topSpeed * mag

            // Same form as Ship.kt:77's velocity.lerp(target, ACCEL * dt / speed);
            // only the rate differs. Clamped because a long frame must never overshoot.
            val k = (CabinetMetrics.VELOCITY_LERP * dt).coerceIn(0f, 1f)
            vx += (targetVx - vx) * k
            vy += (targetVy - vy) * k

            heading = lerpAngle(heading, atan2(dirX, -dirY), CabinetMetrics.TURN_RATE * dt)
        } else {
            // Coast. Constant deceleration, exactly as the main game applies its own.
            val sp = speed()
            val d = m.drag * dt
            if (sp > d) { vx *= (sp - d) / sp; vy *= (sp - d) / sp } else { vx = 0f; vy = 0f }
        }

        x += vx * dt
        y += vy * dt
        wrap()
    }

    private fun wrap() {
        if (x < 0f) x += m.width
        if (x >= m.width) x -= m.width
        if (y < 0f) y += m.height
        if (y >= m.height) y -= m.height
    }

    /**
     * How far this ship travels before it can be going the other way — the figure
     * stage 2's fairness rule 1 measures spacing against.
     *
     * This is NOT braking distance: velocity lerps straight toward the input, so the
     * ship never sits at rest mid-reversal — it is still decelerating as it crosses
     * zero and immediately starts accelerating the other way. Worst case, and the only
     * case worth measuring: full deflection held directly opposite to travel. That
     * commands target = -topSpeed regardless of current speed (see update()'s
     * targetVx/targetVy), NOT -sp, so with v0 = sp, T = m.topSpeed, k = VELOCITY_LERP:
     *   v(t) = -T + (sp + T)*e^(-kt)
     *   v = 0 at t = (1/k)*ln((sp + T)/T)
     *   distance = integral of v from 0 to that t = sp/k - (T/k)*ln((sp + T)/T)
     * At sp == T this collapses to (T/k)(1 - ln2) — the figure the old formula always
     * returned, because it assumed target = -sp. That assumption is only true at top
     * speed, which is why the old formula overstated the distance at every lower speed.
     */
    fun reversalDistance(): Float {
        val sp = speed()
        if (sp <= 0f) return 0f
        val k = CabinetMetrics.VELOCITY_LERP
        val t = m.topSpeed
        return sp / k - (t / k) * ln((sp + t) / t)
    }

    fun reset() {
        x = m.width / 2f; y = m.height / 2f
        vx = 0f; vy = 0f; heading = 0f; alive = true
    }

    private fun lerpAngle(from: Float, to: Float, t: Float): Float {
        var d = to - from
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return from + d * t.coerceIn(0f, 1f)
    }

    companion object {
        /**
         * Nose-up, notched rear, in ship radii. The notch is load-bearing: it is what
         * stops the triangle reading as an arrowhead, and it is where the thrust
         * chevron anchors.
         *
         * Lives on the ship rather than the renderer because the sim needs it too —
         * the death animation shatters exactly this polygon into its own edges.
         */
        val HULL = arrayOf(
            floatArrayOf(0f, -1.6f),
            floatArrayOf(0.9f, 1.25f),
            floatArrayOf(0f, 0.7f),
            floatArrayOf(-0.9f, 1.25f)
        )
    }
}
