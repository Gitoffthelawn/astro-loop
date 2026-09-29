package com.astroloop.game.cabinet

import com.astroloop.game.cabinet.patterns.CurtainPattern
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class CurtainPatternTest : ReckoningPatternContract() {

    override fun pattern(): ReckoningPattern = CurtainPattern()

    private val twoPi = (2.0 * PI).toFloat()

    private fun firstArc() = CurtainPattern().emit(0f, 1f / 120f, 1f, m, shipX, shipY)

    /** Each bullet's bearing from the crystal, wrapped into the arc's own frame. */
    private fun relAngles(arc: List<CabinetBullet>): List<Float> {
        val cx = crystalX(m)
        val cy = crystalY(m)
        var sx = 0f
        var sy = 0f
        for (b in arc) {
            val d = hypot(b.vx, b.vy)
            sx += b.vx / d
            sy += b.vy / d
        }
        val bearing = atan2(sy, sx)
        return arc.map {
            var a = atan2(it.y - cy, it.x - cx) - bearing
            while (a > PI) a -= twoPi
            while (a < -PI) a += twoPi
            a
        }.sorted()
    }

    @Test fun itIsCalledCurtain() {
        assertEquals("CURTAIN", CurtainPattern().name)
    }

    @Test fun anArcIsBornAtTheCrystalAndTravelsOutward() {
        // A design decision, and the whole reason this pattern was rebuilt: it used to spawn
        // just inside the top or bottom screen edge, where a wall cannot be read before
        // it commits you.
        val arc = firstArc()
        assertTrue("an arc needs several bullets", arc.size >= 4)
        val cx = crystalX(m)
        val cy = crystalY(m)
        val r0 = CabinetCrystal.RADIUS_FRAC * m.minEdge
        assertTrue(
            "every bullet must be born on the crystal's surface",
            arc.all { abs(hypot(it.x - cx, it.y - cy) - r0) < 1f }
        )
        assertTrue(
            "and must travel directly away from it, which is what makes the arc unfurl",
            arc.all {
                val sp = hypot(it.vx, it.vy)
                abs(it.vx / sp - (it.x - cx) / r0) < 0.01f &&
                    abs(it.vy / sp - (it.y - cy) / r0) < 0.01f
            }
        )
    }

    @Test fun theArcIsNotARingAndThatIsWhatKeepsItFromBeingPulse() {
        // The design's stated failure mode, and until now it had no guard: radial
        // fan-out gives an arc the same geometry as a PULSE ring. The ONLY thing that
        // separates them is that a ring surrounds you and must be threaded, while an arc
        // arrives on one bearing and can be escaped sideways.
        //
        // If this ever fails, CURTAIN has closed into a slow PULSE and two of the five
        // patterns have collapsed into one idea. The fix is CurtainPattern.ARC_FRAC, not
        // this assertion.
        val cx = crystalX(m)
        val cy = crystalY(m)
        val angles = firstArc().map { atan2(it.y - cy, it.x - cx) }.sorted()
        var widest = angles.first() + twoPi - angles.last()
        for (i in 1 until angles.size) {
            if (angles[i] - angles[i - 1] > widest) widest = angles[i] - angles[i - 1]
        }
        assertTrue(
            "the clear sector behind the crystal was only $widest rad — the arc has " +
                "closed into a ring",
            widest > 1.5f
        )
    }

    @Test fun everyArcIsUnbroken() {
        // INVERTED at a device pass. The arc used to carry a walking opening and this
        // asserted there was exactly one of them, in every arc of the phase. Owner: "the
        // bullet patterns shot by the boss also don't need a gap in them, it's easy enough
        // right now to dodge even without the gap."
        //
        // The way through is now the same everywhere along the arc, which is the figure
        // neighbourSpacing measures and the contract's rule 1 already checks. Kept inverted
        // rather than deleted so re-adding an opening fails here.
        val step = CurtainPattern.WALL_SPACING_FRAC * m.minEdge /
            ReckoningFairness.engagementRange(m)
        emissions(1f).forEachIndexed { k, e ->
            val a = relAngles(e.second)
            val holes = (1 until a.size).count { a[it] - a[it - 1] > step * 1.5f }
            assertEquals("arc $k must have no opening in it", 0, holes)
        }
    }

    @Test fun theArcArrivesOnTheShipsBearing() {
        // REPLACES "successive arcs arrive on different bearings". A device pass made
        // CURTAIN aim: a player who stayed put used to simply wait for the arcs that
        // missed them. Now the wall comes to you and the open sector is where you have to
        // get to, which is the question this pattern was always supposed to ask.
        //
        // Consecutive arcs against a stationary ship are near-identical by design;
        // AIM_JITTER keeps them from being exactly so.
        val p = CurtainPattern()
        val dt = 1f / 120f
        fun bearing(arc: List<CabinetBullet>): Float {
            var sx = 0f
            var sy = 0f
            for (b in arc) { val d = hypot(b.vx, b.vy); sx += b.vx / d; sy += b.vy / d }
            return atan2(sy, sx)
        }
        val here = p.emit(0f, dt, 1f, m, shipX, shipY)
        val aim = atan2(shipY - crystalY(m), shipX - crystalX(m))
        var off = abs(bearing(here) - aim)
        if (off > PI) off = twoPi - off
        assertTrue("the arc arrived ${off} rad off the ship", off < 0.4f)

        // And it follows: stand somewhere else and the wall comes from somewhere else.
        val there = p.emit(0f, dt, 1f, m, m.width * 0.2f, m.height * 0.25f)
        var moved = abs(bearing(here) - bearing(there))
        if (moved > PI) moved = twoPi - moved
        assertTrue("the arc ignored where the ship was", moved > 0.5f)
    }

    @Test fun theArcIsSlowEnoughToPlanAgainst() {
        val speed = CurtainPattern.SPEED_FRAC * m.minEdge
        assertTrue("slower than the old fight's 487.5 floor", speed < 487.5f)
        assertTrue("and slower than the player, so it can be outrun and re-approached",
            speed < m.topSpeed)
    }

    @Test fun everyArcArrivesOnTheShipsBearing_notOnlyTheFirst() {
        // ⚠️ theArcArrivesOnTheShipsBearing above only ever calls emit(0f, ...), which is
        // arc k=0. Stage 4 caught the identical weakness in the hole-walk test — "it
        // checked only the FIRST arc" — and the aim test was written the same way.
        //
        // The offset ACCUMULATES with k, so it is a drift rather than a jitter, and past a
        // point it walks the wall right off the player it is aimed at.
        val p = CurtainPattern()
        val dt = 1f / 120f
        val cx = crystalX(m)
        val cy = crystalY(m)
        val aim = atan2(shipY - cy, shipX - cx)

        fun wrap(a: Float): Float {
            var v = a
            while (v > PI) v -= twoPi
            while (v < -PI) v += twoPi
            return abs(v)
        }

        var t = 0f
        var walls = 0
        val missed = ArrayList<Int>()
        while (walls < 25 && t < 400f) {
            val arc = p.emit(t, dt, 1f, m, shipX, shipY)
            t += dt
            if (arc.isEmpty()) continue
            // Is the ship's bearing INSIDE the wall's angular extent? That is what the
            // KDoc claims ("the wall arrives on your bearing every time") and it is the
            // question that decides whether standing still works. A looser test — "is
            // some bullet within one neighbour gap" — allows the nearest bullet to pass
            // 0.48 rad away, which at engagement range is ~104px and a clean miss.
            var sx = 0f
            var sy = 0f
            for (b in arc) { val d = hypot(b.vx, b.vy); sx += b.vx / d; sy += b.vy / d }
            val centre = atan2(sy, sx)
            if (wrap(centre - aim) > CurtainPattern.ARC_SPAN / 2f) missed.add(walls)
            walls++
        }
        assertEquals("walls that arrived with the ship outside the arc entirely: $missed",
            emptyList<Int>(), missed)
    }
}
