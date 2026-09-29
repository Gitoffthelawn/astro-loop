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
 * **PULSE** — expanding rings from centre, each with a gap that rotates between rings.
 *
 * *Can you read a gap and commit to it?* First in the teaching order, because it
 * establishes the grammar: the crystal emits, the emission has a way through, and finding
 * it is the game.
 *
 * **Its structural lull is the rest between bursts.** Three rings, then silence. Without
 * that rest, rings arrive forever at a fixed cadence and there is never a moment to turn
 * and shoot — which would make "the crystal is always damageable" true in principle and
 * false in practice.
 *
 * **The opening rule 1 measures is the GAP, not the arc between neighbouring bullets in a
 * ring.** A ring is a wall you pass through its hole; bullets on the wall are not a lane.
 * At engagement range the hole is 678.6px wide, so the binding constraint on lap 1 is the
 * *radial* distance between successive rings — which is also what tightens, and therefore
 * what makes escalation bite at lap 4.
 */
class PulsePattern : ReckoningPattern {

    override val name = "PULSE"
    override val duration = 15f

    override fun minSpacing(m: CabinetMetrics, tighten: Float): Float =
        min(radialSpacing(m, tighten), neighbourArc(m))

    /** Distance between consecutive rings: what a player crossing outward threads. */
    private fun radialSpacing(m: CabinetMetrics, tighten: Float): Float =
        speed(m) * RING_INTERVAL * tighten

    /**
     * The arc between neighbouring bullets, at engagement range — **the way through, now
     * that there is no hole.**
     *
     * A device pass: *"the bullet patterns shot by the boss also don't need a gap in them,
     * it's easy enough right now to dodge even without the gap."* The ring used to carry a
     * three-slot door and `minSpacing` measured THAT; the door is gone and the opening is
     * every gap instead of one.
     *
     * Does not scale with `tighten`: escalation tightens intervals, not geometry,
     * so late laps close the radial gap while this stays put — which is why the radial
     * term is what breaches the floor at lap 4 and drives fairness rule 5.
     */
    private fun neighbourArc(m: CabinetMetrics): Float =
        2f * PI.toFloat() * ReckoningFairness.engagementRange(m) / COUNT

        override fun peakDensity(m: CabinetMetrics, tighten: Float): Float {
        val ringsAlive = ceil(crossTime(m) / (RING_INTERVAL * tighten))
        return COUNT * ringsAlive
    }

    override fun emit(
        t: Float, dt: Float, tighten: Float, m: CabinetMetrics, shipX: Float, shipY: Float
    ): List<CabinetBullet> {
        val out = ArrayList<CabinetBullet>()
        // Every slot fires. A design decision retired fairness rule 2: there is no rest
        // between bursts any more, so the rings arrive at a fixed unbroken cadence.
        forEachTick(t, dt, RING_INTERVAL * tighten) { _, _ ->
            ring(m, shipX, shipY, out)
        }
        return out
    }

    private fun ring(
        m: CabinetMetrics, shipX: Float, shipY: Float, out: MutableList<CabinetBullet>
    ) {
        val cx = crystalX(m)
        val cy = crystalY(m)
        val r0 = CabinetCrystal.RADIUS_FRAC * m.minEdge
        val sp = speed(m)
        val life = crossingLife(m, sp)
        val slot = TWO_PI / COUNT
        // Every slot fires. The ring used to carry a rotating three-slot door, and the
        // machinery that aimed it — sweepRate, GAP_STEP, and fairness rule 1b's check that
        // the door stayed within the ship's reach — existed only to serve it. A uniform
        // ring has no door to outrun, so all of that goes with it.
        //
        // ANCHORED TO THE SHIP, as of a device pass. A ring is COUNT bullets over the whole
        // circle and therefore rotation INVARIANT, so this cannot make it denser or sparser
        // — it only decides which bearing a bullet sits on, and now one of them always sits
        // on yours. Spacing is untouched, so rule 1 sees exactly what it saw.
        val aim = atan2(
            m.wrappedDelta(shipY, cy, m.height), m.wrappedDelta(shipX, cx, m.width)
        )
        for (i in 0 until COUNT) {
            val a = aim + i * slot
            val ca = cos(a)
            val sa = sin(a)
            out.add(
                CabinetBullet(cx + ca * r0, cy + sa * r0, ca * sp, sa * sp, life, hostile = true)
            )
        }
    }

    private fun speed(m: CabinetMetrics): Float = SPEED_FRAC * m.minEdge

    /** How long a ring takes to leave the field from the crystal's surface. */
    private fun crossTime(m: CabinetMetrics): Float {
        val corner = hypot(m.width / 2f, m.height / 2f)
        return (corner - CabinetCrystal.RADIUS_FRAC * m.minEdge) / speed(m)
    }

    companion object {
        private const val TWO_PI = (2.0 * PI).toFloat()

        /**
         * 324 px/s at reference.
         *
         * **Slow bullets are usable here for the first time.** The fight this replaces
         * carried a hard floor — *"Speeds NEVER drop below 487.5 px/s or the fight becomes
         * winnable by flying away."* On a wrapping playfield you cannot fly away; fleeing
         * returns you into what you fled. That rule is void, and a readable expanding ring
         * is what it bought.
         */
        const val SPEED_FRAC = 0.30f

        /** 259.2px between rings at lap 1 — comfortably over the 121.9px floor. */
        const val RING_INTERVAL = 0.8f

        /** Slots around the circle, and how many of them are the way through. */
        const val COUNT = 9

    }
}
