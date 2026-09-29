package com.astroloop.game.cabinet

import kotlin.math.hypot
import kotlin.random.Random

/**
 * One line segment of something that has come apart.
 *
 * Nothing in this game may simply vanish — every removal needs a visible exit. The period-correct answer satisfies that exactly: a destroyed shape
 * separates at its own vertices, each edge becoming a free segment that drifts outward,
 * spins slightly and fades. The death animation is the shape falling apart into the
 * lines it was drawn from.
 *
 * Sim-side rather than renderer-side deliberately. It makes the rule testable, it keeps
 * the wreck deterministic under the injected [Random], and it means a future renderer
 * cannot quietly drop the visual and reintroduce the breach.
 *
 * Debris never collides with anything and never blocks a wave advancing.
 *
 * [x1],[y1]..[x2],[y2] are the endpoints in local space, relative to this segment's own
 * midpoint at [x],[y], so [rot] spins the segment about its middle.
 */
class CabinetDebris(
    var x: Float, var y: Float,
    val vx: Float, val vy: Float,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    var rot: Float, val spin: Float,
    var life: Float
) {
    private val startLife: Float = life

    /** 1.0 at spawn, 0.0 at expiry — the renderer's alpha. */
    val fade: Float get() = (life / startLife).coerceIn(0f, 1f)

    fun update(dt: Float, m: CabinetMetrics) {
        x += vx * dt
        y += vy * dt
        rot += spin * dt
        life -= dt
        if (x < 0f) x += m.width
        if (x >= m.width) x -= m.width
        if (y < 0f) y += m.height
        if (y >= m.height) y -= m.height
    }

    companion object {
        /**
         * How long a fragment takes to drift out and fade.
         *
         * 0.6s until a device pass, where the owner asked for the fade to be "a little
         * slower" — the exit was correct but too brief to read.
         *
         * ⚠️ TWO CEILINGS, and the tighter one binds:
         *
         * 1. **Under `CRYSTAL_DEBRIS_LIFE / 3`, i.e. under 1.0s.** `CrystalDeathTest`
         *    requires the crystal's pieces to linger *more than three times* a rock's —
         *    "far longer" is the ending's whole weight, and the 3.0s it lingers for is
         *    authored — the shatter is meant to run a full three seconds.
         *    So this yields to the crystal, not the other way round. 1.0f lands exactly on
         *    that boundary and fails; 0.8f keeps the ratio at 3.75x.
         * 2. Under `ATTRACT_RESTART_HOLD` (1.4s). `CabinetAttract` restarts its demo only
         *    once the hold expires **and** the debris list is empty, so past 1.4s the wreck
         *    rather than the beat would pace the attract loop — silently lengthening a
         *    beat that has already been signed off on hardware.
         */
        const val LIFETIME = 0.8f

        /** Outward drift, as a fraction of minEdge per second. */
        const val DRIFT_FRAC = 0.05f

        /**
         * Break a closed polygon into one drifting segment per edge.
         *
         * @param cx,[cy] the shape's centre — what the fragments drift away from
         * @param worldPts interleaved x,y in WORLD space, already rotated and scaled
         * @param count how many floats of [worldPts] are live (the renderer's scratch
         *   buffer is longer than any one shape, so this cannot be `worldPts.size`)
         */
        fun shatter(
            cx: Float, cy: Float,
            worldPts: FloatArray, count: Int,
            rng: Random, m: CabinetMetrics,
            inheritVx: Float, inheritVy: Float,
            life: Float = LIFETIME,
            driftScale: Float = 1f
        ): List<CabinetDebris> {
            val n = count / 2
            if (n < 2) return emptyList()
            val out = ArrayList<CabinetDebris>(n)
            for (i in 0 until n) {
                val ax = worldPts[i * 2]
                val ay = worldPts[i * 2 + 1]
                val j = (i + 1) % n
                val bx = worldPts[j * 2]
                val by = worldPts[j * 2 + 1]

                val mx = (ax + bx) / 2f
                val my = (ay + by) / 2f

                var ox = mx - cx
                var oy = my - cy
                val d = hypot(ox, oy)
                if (d > 0.001f) {
                    ox /= d; oy /= d
                } else {
                    // Degenerate: the edge midpoint sits on the centre, so there is no
                    // outward direction. Pick one rather than divide by zero.
                    ox = 0f; oy = -1f
                }

                val sp = DRIFT_FRAC * m.minEdge * driftScale * (0.6f + rng.nextFloat() * 0.8f)
                out.add(
                    CabinetDebris(
                        x = mx, y = my,
                        vx = inheritVx + ox * sp,
                        vy = inheritVy + oy * sp,
                        x1 = ax - mx, y1 = ay - my,
                        x2 = bx - mx, y2 = by - my,
                        rot = 0f,
                        spin = (rng.nextFloat() - 0.5f) * 2.5f,
                        life = life
                    )
                )
            }
            return out
        }
    }
}
