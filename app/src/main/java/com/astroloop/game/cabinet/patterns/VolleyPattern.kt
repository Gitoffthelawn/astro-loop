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
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * **VOLLEY** — telegraphed bursts fired at where you *are*.
 *
 * *Can you keep moving and still turn to shoot?* Second in the teaching order: PULSE
 * lets you find a gap and sit in it, and this is what stops that working.
 *
 * **The telegraph is the pattern's own first beat, not a separate object.** A burst is
 * three sub-volleys 0.55s apart, each re-aimed at wherever you are by then. The first
 * announces that a burst is happening and roughly where; the next two punish you for
 * still being there. Nothing extra is drawn, and there is no state to keep.
 *
 * **Aiming is toroidal.** A raw delta would fire at a ship a whole field away when it is
 * in fact adjacent across the seam — and hugging an edge is normal, skilful play here,
 * since wrapping is how you escape.
 */
class VolleyPattern : ReckoningPattern {

    override val name = "VOLLEY"
    override val duration = 15f

    override fun minSpacing(m: CabinetMetrics, tighten: Float): Float =
        min(radialSpacing(m, tighten), fanGap(m))

    /** Distance between successive sub-volleys along their line of travel. */
    private fun radialSpacing(m: CabinetMetrics, tighten: Float): Float =
        speed(m) * SUB_INTERVAL * tighten

    /**
     * The lane between two adjacent bullets of one fan, at engagement range.
     *
     * Geometric, so it never tightens — which is why the *schedule* term has to be the
     * one that breaches the floor at lap 4. A pattern whose binding term stays geometric
     * cannot satisfy fairness rule 5 at all.
     */
    private fun fanGap(m: CabinetMetrics): Float =
        FAN_SPREAD * ReckoningFairness.engagementRange(m) / (FAN_COUNT - 1)

        override fun peakDensity(m: CabinetMetrics, tighten: Float): Float =
        FAN_COUNT * ceil(crossTime(m) / (SUB_INTERVAL * tighten))

    override fun emit(
        t: Float, dt: Float, tighten: Float, m: CabinetMetrics, shipX: Float, shipY: Float
    ): List<CabinetBullet> {
        val out = ArrayList<CabinetBullet>()
        // Every slot fires — a design decision. No rest slots between bursts.
        forEachTick(t, dt, SUB_INTERVAL * tighten) { _, _ ->
            fan(m, shipX, shipY, out)
        }
        return out
    }

    private fun fan(m: CabinetMetrics, shipX: Float, shipY: Float, out: MutableList<CabinetBullet>) {
        val cx = crystalX(m)
        val cy = crystalY(m)
        // Toroidal bearing: the short way round, which is the way the player sees it.
        val dx = m.wrappedDelta(shipX, cx, m.width)
        val dy = m.wrappedDelta(shipY, cy, m.height)
        val aim = atan2(dy, dx)
        val r0 = CabinetCrystal.RADIUS_FRAC * m.minEdge
        val sp = speed(m)
        val life = crossingLife(m, sp)
        val step = FAN_SPREAD / (FAN_COUNT - 1)
        for (i in 0 until FAN_COUNT) {
            val a = aim - FAN_SPREAD / 2f + i * step
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
        /** 367.2 px/s at reference — quick, because it is aimed and must be dodged. */
        const val SPEED_FRAC = 0.34f

        /** 202.0px between sub-volleys at lap 1; 85.2px by lap 4, under the floor. */
        const val SUB_INTERVAL = 0.55f

        /** Four bullets over 1.0 rad — 144px apart at engagement range. */
        /**
         * ODD, so one bullet lands exactly on the aim.
         *
         * It was 4, and an even fan spread symmetrically about the ship's bearing puts a
         * HOLE where the ship is — VOLLEY aimed at you and then politely missed. A device
         * pass: "it's still very much possible to just sit still and damage the boss
         * during the first two phases." Standing still was not merely survivable here, it
         * was the safest thing available.
         */
        const val FAN_COUNT = 5
        /**
         * Widened with [FAN_COUNT], to hold fairness rule 1.
         *
         * `fanGap` is `FAN_SPREAD * engagementRange / (FAN_COUNT - 1)`, so a fifth bullet at
         * the old 1.0 would have closed the gap to 108px against a 121.9px floor. At 1.2 it
         * is 129.6px and the fan is a wider cone with a bullet down the middle, which is the
         * shape that punishes standing still.
         */
        const val FAN_SPREAD = 1.2f
    }
}
