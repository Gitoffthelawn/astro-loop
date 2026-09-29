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
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * **CURTAIN** — a slow arc unfurling from the crystal, with one opening.
 *
 * *Can you plan ahead instead of reacting?* Fourth in the teaching order, and the only
 * pattern that cannot be survived by reflex: the arc is visible long before it arrives
 * and the hole is somewhere you have to go.
 *
 * **Rebuilt for a design decision.** It used to spawn full-width walls just inside the top or
 * bottom edge, alternating. A device pass: *"all patterns should emit from the ship
 * instead of having patterns that start from outside of the screen."* Read against the
 * code, CURTAIN was the only one of the five that did — the other four all spawn at
 * [crystalX]/[crystalY]. The objection is readability, and it lands hardest here: a wall
 * born off-screen cannot be read before it commits you, which is precisely this pattern's
 * job.
 *
 * Bullets are now born at the crystal's surface and travel radially outward, so the arc
 * **widens as it goes** — a curtain unfurling rather than sliding in. Successive arcs
 * point on different bearings, replacing the old top/bottom alternation, and the hole
 * walks within the arc so the answer to the last one is never the answer to the next.
 *
 * **The second pattern the deleted speed floor bought.** At 216 px/s this is slower than
 * the ship, so it can be outrun — and on a torus outrunning it only brings you back to
 * it. In the old camera-following fight that made a threat worthless; here it makes one
 * you have to solve rather than escape.
 *
 * ⚠️ **WHERE THIS COULD GO WRONG, and no test can see it.** Radial fan-out gives an arc
 * the same geometry as a PULSE ring: tight near the body, loose at the edge. The only
 * thing keeping them distinct is that a ring surrounds you and must be threaded, while an
 * arc arrives on one bearing and can be escaped sideways. [ARC_FRAC] is what protects
 * that — at 0.55 the arc covers a little over half the circle and leaves 45% of it
 * completely open. **Widen it toward 1.0 and this stops being CURTAIN and becomes a slow
 * PULSE**, collapsing two of the five patterns into one idea.
 *
 * The distinctions that survive, and they are not small: PULSE emits every 0.8s against
 * this pattern's 4.0s, at 1.5x the speed, through a hole nearly three times wider
 * (678.6px against 237.6px at engagement range). Frequent and forgiving against slow,
 * rare and exact.
 */
class CurtainPattern : ReckoningPattern {

    override val name = "CURTAIN"
    override val duration = 15f

    override fun minSpacing(m: CabinetMetrics, tighten: Float): Float =
        min(neighbourSpacing(m, tighten), interArcSpacing(m, tighten))

    /**
     * Gap between neighbouring bullets in one arc, **measured at engagement range**.
     *
     * This is the binding term on every lap and the one that tightens — a curtain gets
     * denser rather than more frequent, because more frequent would put four arcs on
     * the field at once and stop it being a thing you plan around.
     *
     * Unchanged from the edge-entry design, deliberately: the arc's ANGULAR step is
     * derived from this so that the arc-length between neighbours at engagement range is
     * the same figure the old flat wall used. Rules 1 and 5 therefore see exactly the
     * numbers they saw before the rebuild — 205.2px at lap 1 down to 86.6px at lap 4 —
     * and the redesign changes the pattern's shape without moving its fairness.
     */
    private fun neighbourSpacing(m: CabinetMetrics, tighten: Float): Float =
        WALL_SPACING_FRAC * m.minEdge * tighten

    /** Radial distance between successive arcs. 864px at lap 1 — never binds. */
    private fun interArcSpacing(m: CabinetMetrics, tighten: Float): Float =
        speed(m) * WALL_INTERVAL * tighten

    /** The angular step that realises [neighbourSpacing] at engagement range. */
    private fun angularStep(m: CabinetMetrics, tighten: Float): Float =
        neighbourSpacing(m, tighten) / ReckoningFairness.engagementRange(m)

    override fun peakDensity(m: CabinetMetrics, tighten: Float): Float =
        bulletsPerArc(m, tighten) * ceil(crossTime(m) / (WALL_INTERVAL * tighten))

    private fun bulletsPerArc(m: CabinetMetrics, tighten: Float): Float =
        floor(ARC_SPAN / angularStep(m, tighten)) + 1f

    override fun emit(
        t: Float, dt: Float, tighten: Float, m: CabinetMetrics, shipX: Float, shipY: Float
    ): List<CabinetBullet> {
        val out = ArrayList<CabinetBullet>()
        forEachTick(t, dt, WALL_INTERVAL * tighten) { k, _ ->
            arc(k, m, tighten, shipX, shipY, out)
        }
        return out
    }

    private fun arc(
        k: Int, m: CabinetMetrics, tighten: Float, shipX: Float, shipY: Float,
        out: MutableList<CabinetBullet>
    ) {
        val cx = crystalX(m)
        val cy = crystalY(m)
        val r0 = CabinetCrystal.RADIUS_FRAC * m.minEdge
        val sp = speed(m)
        val life = crossingLife(m, sp)
        val step = angularStep(m, tighten)
        val half = ARC_SPAN / 2f
        // AIMED AT THE SHIP, as of a device pass — owner: "it's easy to just camp and
        // easily damage the crystal."
        //
        // The arc used to walk a fixed irrational fraction each time, which meant a player
        // who stayed put simply waited for the arcs that missed them. Centring it on the
        // ship makes standing still the one thing that never works: the wall arrives on your
        // bearing every time, and the 45% of the circle ARC_FRAC leaves open is where you
        // have to get to. That is CURTAIN's stated question — "can you plan ahead instead of
        // reacting" — asked properly for the first time.
        //
        // The walk survives as a smaller, BOUNDED offset so consecutive arcs are not
        // identical, and it still derives from the arc's index rather than any accumulated
        // field, so the pattern stays stateless and a debug jump needs no warm-up.
        val aim = atan2(shipY - cy, shipX - cx)
        // BOUNDED, and it was not. `aim + k * step * AIM_JITTER` ACCUMULATES: the offset
        // grows 0.328 rad per wall and wraps, so walls 6 through 14 of every 19 arrived
        // with the ship outside the arc entirely — 32 consecutive seconds at lap 1 in
        // which standing still was safe, which is the exact camping the aim was added to
        // punish. Taking the sine of the walk keeps the variation and caps the offset at
        // ±0.31 rad, well inside the arc's ±1.73 half-span, so the ship is always covered.
        val bearing = aim + sin(k * BEARING_STEP_FRAC * TWO_PI) * (AIM_JITTER * half)
        val slots = floor(ARC_SPAN / step).toInt()
        // Unbroken. The arc used to carry a walking opening; a device pass retired it —
        // "it's easy enough right now to dodge even without the gap" — so the way through
        // is now the same everywhere along it, which is what neighbourSpacing measures.
        for (i in 0..slots) {
            val a = bearing - half + i * step
            val ca = cos(a)
            val sa = sin(a)
            out.add(CabinetBullet(cx + ca * r0, cy + sa * r0, ca * sp, sa * sp, life, hostile = true))
        }
    }

    private fun speed(m: CabinetMetrics): Float = SPEED_FRAC * m.minEdge

    /** How long an arc takes to leave the field, from the crystal's surface. */
    private fun crossTime(m: CabinetMetrics): Float {
        val corner = hypot(m.width / 2f, m.height / 2f)
        return (corner - CabinetCrystal.RADIUS_FRAC * m.minEdge) / speed(m)
    }

    companion object {
        /** 216 px/s at reference — below the ship's 302.4, deliberately outrunnable. */
        const val SPEED_FRAC = 0.20f

        /** 205.2px between bullets at lap 1; 86.6px by lap 4, under the floor. */
        const val WALL_SPACING_FRAC = 0.19f

        /** Seconds between arcs. */
        const val WALL_INTERVAL = 4.0f

        /**
         * The arc's share of the full circle. **This is what keeps CURTAIN from being a
         * slow PULSE** — see the class KDoc. At 0.55 it leaves 45% of the circle open, so
         * the arc arrives on a bearing and can be escaped sideways rather than threaded.
         */
        const val ARC_FRAC = 0.55f

        /** How far the arc's bearing walks each time, before [AIM_JITTER] scales it. */
        const val BEARING_STEP_FRAC = 0.29f

        /**
         * How much of the arc's half-span the aim is allowed to wander, 0..1.
         *
         * At 0.0 the arc is perfectly centred every time and becomes rhythmic; at 1.0 the
         * offset reaches the arc's own edge and the ship falls out of it. 0.18 is ±0.31 rad
         * against a ±1.73 half-span, which varies consecutive arcs while leaving the ship
         * covered by a wide margin.
         *
         * ⚠️ This scales a BOUNDED offset, not an accumulating walk. It used to multiply
         * `k * BEARING_STEP_FRAC * TWO_PI` directly, which grows without limit — and since
         * a design decision made phases advance on DAMAGE, a player who never shoots never leaves
         * the phase, so `k` never resets and the drift never stops. Nine of every nineteen
         * walls missed. `everyArcArrivesOnTheShipsBearing_notOnlyTheFirst` pins this.
         */
        const val AIM_JITTER = 0.18f

        private const val TWO_PI = (2.0 * PI).toFloat()

        /** [ARC_FRAC] as an angle. 3.456 rad — a little under 200 degrees. */
        const val ARC_SPAN = ARC_FRAC * TWO_PI
    }
}
