package com.astroloop.game.cabinet.patterns

import com.astroloop.game.cabinet.CabinetBullet
import com.astroloop.game.cabinet.CabinetCrystal
import com.astroloop.game.cabinet.CabinetMetrics
import com.astroloop.game.cabinet.ReckoningFairness
import com.astroloop.game.cabinet.ReckoningPattern
import com.astroloop.game.cabinet.crossingLife
import com.astroloop.game.cabinet.crystalX
import com.astroloop.game.cabinet.crystalY
import com.astroloop.game.cabinet.forEachTick
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * **SHATTER** — a short, chaotic, very dense burst, then a long silence.
 *
 * *Can you survive without shooting, then punish the quiet?* Last in the teaching order,
 * because it asks for nerve rather than a technique.
 *
 * **This is what proves the no-window design.** The spec: it is a phase where shooting is
 * genuinely off the table, followed by one where it is free — *"and no invulnerability
 * flag is involved anywhere. The pattern's own density does the work a state machine
 * would have done."* If a future change makes this survivable while shooting, the whole
 * argument for having no window mechanic weakens with it.
 *
 * **The chaos is deterministic.** Each cluster is rotated by the golden angle from the
 * last, so no two land alike and the sequence never visibly repeats inside a phase — but
 * there is no `Random` anywhere, which every fairness test and the `RECKONING_PHASE_N`
 * jump both depend on.
 *
 * **The cluster itself is evenly spread**, and that is not a contradiction: chaos is
 * *where* a cluster lands, not how it is shaped. An unevenly shaped cluster could put two
 * bullets close enough to close a lane, and nothing would catch it — the shared harness
 * only measures the radial schedule, not the angular geometry.
 */
class ShatterPattern : ReckoningPattern {

    override val name = "SHATTER"
    override val duration = 15f

    override fun minSpacing(m: CabinetMetrics, tighten: Float): Float =
        min(radialSpacing(m, tighten), clusterArc(m))

    private fun radialSpacing(m: CabinetMetrics, tighten: Float): Float =
        speed(m) * CLUSTER_INTERVAL * tighten

    /** Arc between neighbours in one cluster at engagement range. 301.6px — never binds. */
    private fun clusterArc(m: CabinetMetrics): Float =
        2f * PI.toFloat() * ReckoningFairness.engagementRange(m) / COUNT

        override fun peakDensity(m: CabinetMetrics, tighten: Float): Float =
        COUNT * ceil(crossTime(m) / (CLUSTER_INTERVAL * tighten))

    override fun emit(
        t: Float, dt: Float, tighten: Float, m: CabinetMetrics, shipX: Float, shipY: Float
    ): List<CabinetBullet> {
        val out = ArrayList<CabinetBullet>()
        // Every slot fires — a design decision. The long silence is gone.
        forEachTick(t, dt, CLUSTER_INTERVAL * tighten) { _, _ ->
            cluster(m, shipX, shipY, out)
        }
        return out
    }

    private fun cluster(
        m: CabinetMetrics, shipX: Float, shipY: Float, out: MutableList<CabinetBullet>
    ) {
        val cx = crystalX(m)
        val cy = crystalY(m)
        val r0 = CabinetCrystal.RADIUS_FRAC * m.minEdge
        val sp = speed(m)
        val life = crossingLife(m, sp)
        val step = 2f * PI.toFloat() / COUNT
        // AIMED, as of a device pass: every cluster puts one bullet exactly on the ship's
        // bearing. Owner: "it's easy to just camp and easily damage the crystal."
        //
        // A cluster is COUNT bullets evenly spread over the full circle, so it is rotation
        // INVARIANT — the golden angle that used to set this rotation could not make it
        // harder or easier, only different-looking. Anchoring it to the ship does what the
        // rotation could not: standing still means being hit, and the only answer is to
        // keep moving. Spacing between neighbours is still 2*pi/COUNT, so rule 1 sees
        // exactly what it saw before.
        //
        // A stationary player therefore meets the same cluster every time. That is the
        // point rather than a flaw: the pattern is only predictable to someone who has
        // stopped, and stopping is what it punishes.
        val base = atan2(shipY - cy, shipX - cx)
        for (i in 0 until COUNT) {
            val a = base + i * step
            val ca = cos(a)
            val sa = sin(a)
            out.add(
                CabinetBullet(cx + ca * r0, cy + sa * r0, ca * sp, sa * sp, life, hostile = true)
            )
        }
    }

    private fun speed(m: CabinetMetrics): Float = SPEED_FRAC * m.minEdge

    private fun crossTime(m: CabinetMetrics): Float {
        val corner = hypot(m.width / 2f, m.height / 2f)
        return (corner - CabinetCrystal.RADIUS_FRAC * m.minEdge) / speed(m)
    }

    companion object {
        /** 410.4 px/s — the fastest of the five. Chaos wants to arrive quickly. */
        const val SPEED_FRAC = 0.38f

        /** 225.7px between clusters at lap 1; 95.2px by lap 4, under the floor. */
        const val CLUSTER_INTERVAL = 0.55f

        /**
         * Bullets to a cluster.
         *
         * Nine until a device pass, where shrinking `CabinetCrystal.RADIUS_FRAC` to 0.05
         * lengthened every bullet's flight — a smaller body means more of the field to
         * cross — and pushed one more cluster onto the screen at lap 4. Declared density
         * went to 126 against a ceiling of 120, so the count comes down to hold rule 3.
         *
         * It costs nothing here: `clusterArc` was never the binding term in `minSpacing`
         * (the radial schedule is), so rules 1 and 5 see the same numbers at every lap, and
         * the cluster now AIMS, which is worth more than the ninth bullet was.
         */
        const val COUNT = 8

    }
}
