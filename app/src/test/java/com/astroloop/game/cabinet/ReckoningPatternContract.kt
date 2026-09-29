package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The fairness contract, as a test every pattern must pass.
 *
 * Abstract and deliberately NOT named `*Test`, so the runner never instantiates it
 * directly — each pattern's own test class extends it and inherits these cases.
 *
 * **Two-sided by design.** Half of these read the pattern's declared
 * figures; the other half simulate it and check the declaration was honest. A declaration
 * nothing verifies is exactly the failure the stage 1 final review caught, where two
 * tests ran green over a path the product never took.
 */
abstract class ReckoningPatternContract {

    protected val m = CabinetMetrics(1080f, 2400f)

    protected abstract fun pattern(): ReckoningPattern

    /**
     * The director's own escalation, not a second copy of it.
     *
     * Four tests need this figure. Computing it inline in each is how a harness ends up
     * testing a fight nobody plays — the numbers agree today only because 0.75 is an exact
     * binary fraction, so repeated multiplication and `pow` happen to match.
     */
    protected fun tightenAt(lap: Int): Float =
        ReckoningDirector.TIGHTEN_PER_LAP.pow(lap - 1)

    /** Somewhere a player plausibly is: below the crystal, mid-field. */
    protected val shipX get() = m.width / 2f
    protected val shipY get() = m.height * 0.72f

    /**
     * The step [emissions] and [peakLive] sample at.
     *
     * Named because the declaration check has to account for its own resolution — see
     * [theDeclaredOpeningIsNotAnOverstatement].
     */
    protected val sampleDt = 1f / 120f

    /** One full phase, as (timeOfEmission, bullets) pairs. Only non-empty frames. */
    protected fun emissions(tighten: Float): List<Pair<Float, List<CabinetBullet>>> {
        val p = pattern()
        val dt = sampleDt
        val out = ArrayList<Pair<Float, List<CabinetBullet>>>()
        var t = 0f
        while (t < p.duration) {
            val e = p.emit(t, dt, tighten, m, shipX, shipY)
            if (e.isNotEmpty()) out.add(t to e)
            t += dt
        }
        return out
    }

    /** Peak simultaneously-live bullets over one phase. */
    protected fun peakLive(tighten: Float): Int {
        val p = pattern()
        val dt = sampleDt
        val live = ArrayList<CabinetBullet>()
        var peak = 0
        var t = 0f
        while (t < p.duration) {
            live.addAll(p.emit(t, dt, tighten, m, shipX, shipY))
            val it = live.iterator()
            while (it.hasNext()) {
                val b = it.next()
                b.update(dt, m)
                if (b.life <= 0f || b.escaped) it.remove()
            }
            if (live.size > peak) peak = live.size
            t += dt
        }
        return peak
    }

    /** Radial picket spacing actually produced: gap between emission events × speed. */
    protected fun actualRadialSpacing(tighten: Float): Float {
        val e = emissions(tighten)
        if (e.size < 2) return Float.MAX_VALUE
        var min = Float.MAX_VALUE
        for (i in 1 until e.size) {
            val gap = e[i].first - e[i - 1].first
            val slowest = e[i].second.minOf { hypot(it.vx, it.vy) }
            val spacing = gap * slowest
            if (spacing < min) min = spacing
        }
        return min
    }

    // --- Rule 1: every stream is threadable, through lap 2 --------------------

    @Test fun rule1_declaredSpacingClearsTheFloorThroughLapTwo() {
        val p = pattern()
        val floor = ReckoningFairness.spacingFloor(m)
        for (lap in 1..ReckoningFairness.FAIR_LAPS) {
            val s = p.minSpacing(m, tightenAt(lap))
            assertTrue(
                "${p.name} lap $lap: opening ${s}px must clear the ${floor}px floor " +
                    "(danger diameter + reversal distance, ×${ReckoningFairness.SPACING_MARGIN})",
                s >= floor
            )
        }
    }

    // --- Rule 5: escalation bites --------------------------------------------

    @Test fun rule5_spacingHasFallenBelowTheFloorByLapFour() {
        // The other half of a design decision. Rule 1 and rule 5 cannot both hold
        // unconditionally: breaking rule 1 IS how rule 5 is implemented. Asserting the
        // breach makes the forcing function a positive property rather than something
        // merely permitted by rule 1's silence.
        val p = pattern()
        val tighten = tightenAt(ReckoningFairness.ESCALATED_LAP)
        assertTrue(
            "${p.name} must become unsurvivable by lap ${ReckoningFairness.ESCALATED_LAP} " +
                "or a player who never attacks orbits forever — opening was " +
                "${p.minSpacing(m, tighten)}px against a ${ReckoningFairness.spacingFloor(m)}px floor",
            p.minSpacing(m, tighten) < ReckoningFairness.spacingFloor(m)
        )
    }

    // --- Rule 3: density is bounded ------------------------------------------

    @Test fun rule3_declaredDensityStaysUnderTheCeiling() {
        val p = pattern()
        for (lap in 1..ReckoningFairness.ESCALATED_LAP) {
            val d = p.peakDensity(m, tightenAt(lap))
            assertTrue(
                "${p.name} lap $lap: ${d} live bullets exceeds the " +
                    "${ReckoningFairness.DENSITY_CEILING} ceiling",
                d <= ReckoningFairness.DENSITY_CEILING
            )
        }
    }

    @Test fun rule3_actualDensityDoesNotExceedWhatWasDeclared() {
        val p = pattern()
        for (lap in 1..ReckoningFairness.ESCALATED_LAP) {
            val tighten = tightenAt(lap)
            val measured = peakLive(tighten)
            assertTrue(
                "${p.name} lap $lap declared ${p.peakDensity(m, tighten)} live bullets " +
                    "but actually peaked at $measured",
                measured <= p.peakDensity(m, tighten)
            )
        }
    }

    // --- The declaration must be honest --------------------------------------

    @Test fun theDeclaredOpeningIsNotAnOverstatement() {
        // The load-bearing half of a design decision. A pattern that declares 200px of room
        // while emitting a wall every 0.1s would pass every rule above.
        //
        // This checks the RADIAL claim — the picket spacing along a stream. The ANGULAR
        // claim (a ring's gap, a windmill's sector) is geometry rather than schedule, so
        // each pattern's own test checks that half.
        val p = pattern()
        val declared = p.minSpacing(m, 1f)
        val actual = actualRadialSpacing(1f)
        // The harness records an emission at its FRAME's start, not at its scheduled
        // instant, so a pattern whose interval is not a whole multiple of sampleDt shows
        // gaps up to one frame short. That is a measurement artifact, not a schedule
        // defect: PULSE passed only because 0.8s is exactly 96 sampling steps, and
        // WINDMILL's 0.62s is not. Allow the measurement's own resolution — one frame of
        // travel at the fastest bullet the pattern emits — and no more.
        val fastest = emissions(1f).flatMap { it.second }.maxOf { hypot(it.vx, it.vy) }
        val resolution = sampleDt * fastest
        assertTrue(
            "${p.name} declares ${declared}px of opening but its emissions are only " +
                "${actual}px apart radially (measurement resolution ${resolution}px) — " +
                "the declaration must be conservative or exact",
            declared <= actual + resolution + 0.5f
        )
    }

    @Test fun thePatternActuallyEmits() {
        // A pattern emitting nothing passes every other test in this class vacuously.
        //
        // TWO events, not one, and that is the load-bearing half: `actualRadialSpacing`
        // returns MAX_VALUE below two emission events, which silently switches OFF
        // `theDeclaredOpeningIsNotAnOverstatement` — a design decision's whole point. A pattern
        // firing a single ten-bullet burst per phase would otherwise clear this entire
        // contract while declaring any opening it liked.
        val e = emissions(1f)
        assertTrue("${pattern().name} emitted nothing over a full phase", e.isNotEmpty())
        assertTrue(
            "${pattern().name} emitted on only ${e.size} frame(s); a schedule with fewer " +
                "than two events disables the declaration check entirely",
            e.size >= 2
        )
        assertTrue(e.sumOf { it.second.size } >= 10)
    }

    @Test fun everythingEmittedIsHostile() {
        for ((_, bs) in emissions(1f)) {
            assertTrue("the crystal's shots must all be hostile", bs.all { it.hostile })
        }
    }

    @Test fun everythingEmittedStartsInsideTheField() {
        // A bullet born outside is culled by `escaped` on its very first update, so it
        // would flicker into existence and vanish — an instant disappearance.
        for ((t, bs) in emissions(1f)) {
            for (b in bs) {
                assertTrue(
                    "${pattern().name} spawned at (${b.x}, ${b.y}) at t=$t, outside the field",
                    b.x >= 0f && b.x < m.width && b.y >= 0f && b.y < m.height
                )
            }
        }
    }

    @Test fun everythingEmittedCanReachTheFarSideOfTheField() {
        // Hostile bullets die by leaving the field. If the timer beats the exit, the
        // bullet evaporates mid-air instead — an instant disappearance by another name.
        val diagonal = hypot(m.width, m.height)
        for ((_, bs) in emissions(1f)) {
            for (b in bs) {
                val reach = hypot(b.vx, b.vy) * b.life
                assertTrue(
                    "a shot reaching only ${reach}px cannot cross a ${diagonal}px field " +
                        "and would expire in mid-air",
                    reach >= diagonal - 1f
                )
            }
        }
    }

    @Test fun emissionIsDeterministic() {
        val a = emissions(1f)
        val b = emissions(1f)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].first, b[i].first, 0.0001f)
            assertEquals(a[i].second.size, b[i].second.size)
            for (j in a[i].second.indices) {
                assertEquals(a[i].second[j].x, b[i].second[j].x, 0.0001f)
                assertEquals(a[i].second[j].vy, b[i].second[j].vy, 0.0001f)
            }
        }
    }
}
