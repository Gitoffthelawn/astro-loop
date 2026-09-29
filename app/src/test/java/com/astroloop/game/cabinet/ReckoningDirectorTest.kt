package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test

class ReckoningDirectorTest {

    private val m = CabinetMetrics(1080f, 2400f)

    /** A pattern that emits one marked bullet per tick, so sequencing is observable. */
    private class Probe(
        override val name: String,
        override val duration: Float
    ) : ReckoningPattern {
        var lastTighten = -1f
        override fun minSpacing(m: CabinetMetrics, tighten: Float) = 999f
        override fun peakDensity(m: CabinetMetrics, tighten: Float) = 1f
        override fun emit(
            t: Float, dt: Float, tighten: Float, m: CabinetMetrics, shipX: Float, shipY: Float
        ): List<CabinetBullet> {
            lastTighten = tighten
            return listOf(CabinetBullet(shipX, shipY, 0f, 0f, 1f, hostile = true))
        }
    }

    /** A pattern that really schedules, so a stalled frame's emission count is observable. */
    private class TickingProbe(
        override val name: String,
        override val duration: Float,
        private val interval: Float
    ) : ReckoningPattern {
        override fun minSpacing(m: CabinetMetrics, tighten: Float) = 999f
        override fun peakDensity(m: CabinetMetrics, tighten: Float) = 1f
        override fun emit(
            t: Float, dt: Float, tighten: Float, m: CabinetMetrics, shipX: Float, shipY: Float
        ): List<CabinetBullet> {
            val out = ArrayList<CabinetBullet>()
            forEachTick(t, dt, interval * tighten) { _, _ ->
                out.add(CabinetBullet(shipX, shipY, 0f, 0f, 1f, hostile = true))
            }
            return out
        }
    }

    private fun probes() = listOf(
        Probe("A", 10f), Probe("B", 10f), Probe("C", 10f), Probe("D", 10f), Probe("E", 10f)
    )

    private fun director(ps: List<ReckoningPattern> = probes()) = ReckoningDirector(m, ps)

    /**
     * Step past the beat of quiet every pattern opens with — a design decision. Anything asserting
     * about EMISSION has to get through it first, because the director deliberately emits
     * nothing while the crystal is talking.
     */
    private fun pastLull(d: ReckoningDirector, health: Float = 1f) {
        run(d, ReckoningDirector.LULL_SECONDS + 0.05f, health)
    }

    private fun run(d: ReckoningDirector, seconds: Float, health: Float = 1f) {
        val dt = 1f / 60f
        var t = 0f
        while (t < seconds) { d.update(dt, 540f, 1200f, health); t += dt }
    }

    @Test fun startsOnTheFirstPatternOfLapOne() {
        val d = director()
        assertEquals(0, d.phaseIndex)
        assertEquals(1, d.lap)
        assertEquals("A", d.pattern.name)
    }

    @Test fun timeAloneDoesNotAdvanceThePattern() {
        // INVERTED for a design decision. Phases used to run out a 15-second clock each and cycle;
        // now they follow the crystal's health, so a fight in which nothing is damaged sits
        // on its first pattern however long it lasts.
        val d = director()
        run(d, 200f, health = 1f)
        assertEquals("A", d.pattern.name)
        assertEquals(0, d.phaseIndex)
    }

    @Test fun escalationStepsOnItsOwnClock() {
        // Replaces "the lap increments after the last pattern". The lap no longer counts
        // pattern cycles — there are none — so it counts elapsed time directly. That is
        // what keeps fairness rule 5 alive with the phase on damage.
        val d = director()
        assertEquals(1, d.lap)
        run(d, ReckoningDirector.ESCALATION_SECONDS + 0.1f, health = 1f)
        assertEquals(2, d.lap)
    }

    @Test fun tightenIsOneOnTheFirstStepAndSeventyFivePercentPerStepAfter() {
        val d = director()
        val step = ReckoningDirector.ESCALATION_SECONDS
        assertEquals(1f, d.tighten, 0.0001f)
        run(d, step + 0.1f, health = 1f)
        assertEquals(0.75f, d.tighten, 0.0001f)
        run(d, step, health = 1f)
        assertEquals(0.5625f, d.tighten, 0.0001f)
        run(d, step, health = 1f)
        assertEquals(0.421875f, d.tighten, 0.0001f)
    }

    @Test fun escalationStepsDoNotShrink() {
        // A design decision: escalation tightens emission INTERVALS, never the clock it runs on.
        // The fight densifies without accelerating.
        val d = director()
        val step = ReckoningDirector.ESCALATION_SECONDS
        run(d, step + 0.1f, health = 1f)
        assertEquals(2, d.lap)
        run(d, step - 0.3f, health = 1f)
        assertEquals("still step 2 - the clock must not scale with tighten", 2, d.lap)
    }

    @Test fun theCurrentPatternIsToldTheTightenFactor() {
        val ps = probes()
        val d = ReckoningDirector(m, ps)
        run(d, ReckoningDirector.ESCALATION_SECONDS + 0.1f, health = 1f)
        assertEquals(0.75f, (ps[0] as Probe).lastTighten, 0.0001f)
    }

    @Test fun startAtPhaseJumpsStraightIn() {
        // What RECKONING_PHASE_N exists for: a pattern reachable only by surviving the
        // four before it gets tuned twice a day.
        val d = director()
        d.startAtPhase(3)
        assertEquals(3, d.phaseIndex)
        assertEquals("D", d.pattern.name)
        assertEquals("a bare jump still defaults to lap 1", 1, d.lap)
        assertEquals(0f, d.phaseElapsed, 0.0001f)
    }

    @Test fun startAtPhaseClampsOutOfRange() {
        val d = director()
        d.startAtPhase(99)
        assertEquals(4, d.phaseIndex)
        d.startAtPhase(-3)
        assertEquals(0, d.phaseIndex)
    }

    @Test fun startAtPhaseCanJumpToLaterLap() {
        // The debug jump used to hard-reset lap to 1, which made the escalation reachable
        // only by surviving a full 75-second lap in a fight nobody had confirmed was
        // survivable. A lap parameter makes lap 2's tighten cheap to reach directly.
        val d = director()
        d.startAtPhase(2, lap = 2)
        assertEquals(2, d.phaseIndex)
        assertEquals("C", d.pattern.name)
        assertEquals("the jump must seat the escalation clock, not just a counter", 2, d.lap)
        assertEquals(0.75f, d.tighten, 0.0001f)
        assertEquals(0f, d.phaseElapsed, 0.0001f)
    }

    @Test fun startAtPhaseCoercesLapToAtLeastOne() {
        val d = director()
        d.startAtPhase(1, lap = 0)
        assertEquals(1, d.lap)
        d.startAtPhase(1, lap = -5)
        assertEquals(1, d.lap)
    }

    // --- A design decision: the bands ---------------------------------------------------------

    @Test fun everyPatternOpensWithASeamAndThenFires() {
        // A design decision. The lull is BETWEEN patterns, not inside one — a design decision still
        // holds for what a pattern does once it starts.
        val d = director()
        assertTrue("the fight opens on a seam", d.inLull)
        assertTrue("and emits nothing into it", d.update(1f / 60f, 540f, 1200f, 1f).isEmpty())
        pastLull(d)
        assertFalse(d.inLull)
        assertTrue("then it fires", d.update(1f / 60f, 540f, 1200f, 1f).isNotEmpty())
    }

    @Test fun theSeamDoesNotPauseTheEscalationClock() {
        // Or a player could hold the fight open by trickling damage into it, taking a fresh
        // five seconds of quiet every time and never facing a tightened pattern.
        val d = director()
        val before = d.elapsed
        run(d, 2f, health = 1f)
        assertTrue("time passes in the seam", d.elapsed > before + 1.9f)
    }

    @Test fun thePatternFollowsTheCrystalsHealth() {
        // A design decision, replacing a design decision's HP bands. One pattern per fifth: 100-80% is
        // the first, 80-60% the second, and so on. The bands existed to guarantee all five
        // played; this guarantees it by construction, because the last fifth cannot be
        // reached without crossing the four before it.
        val d = director()
        assertEquals(0, d.phaseFor(1.0f))
        assertEquals(0, d.phaseFor(0.81f))
        assertEquals(1, d.phaseFor(0.80f))
        assertEquals(2, d.phaseFor(0.60f))
        assertEquals(3, d.phaseFor(0.40f))
        assertEquals(4, d.phaseFor(0.20f))
        assertEquals(4, d.phaseFor(0.0f))
    }

    @Test fun aHealthOutsideTheRangeCannotIndexPastThePatterns() {
        val d = director()
        assertEquals(0, d.phaseFor(2f))
        assertEquals(4, d.phaseFor(-1f))
    }

    @Test fun damageMovesTheFightOnAndNothingElseDoes() {
        // The whole inversion. A player who never fires stays in the first pattern however
        // long they survive — which is what makes escalation the only thing pressing on
        // them, and why it had to stop riding on the phase cycle.
        val d = director()
        run(d, 200f, health = 1f)
        assertEquals("no damage, no progress", 0, d.phaseIndex)

        run(d, 1f, health = 0.5f)
        assertEquals("damage, and the fight moves", 2, d.phaseIndex)
    }

    @Test fun aPatternPickedUpMidFightStartsItsOwnScheduleFromZero() {
        // phaseElapsed restarts on a phase change. Inheriting the previous pattern's
        // offset would have the new one emit its first ring late, or skip it.
        val d = director()
        pastLull(d)
        run(d, 4f, health = 1f)
        assertTrue("the first pattern has been running", d.phaseElapsed > 3f)
        d.update(1f / 60f, 540f, 1200f, 0.5f)
        assertEquals(2, d.phaseIndex)
        assertTrue("the new pattern starts fresh", d.phaseElapsed < 0.1f)
        assertTrue("and opens with its own seam", d.inLull)
    }

    @Test fun escalationRunsOnTimeSoARefusalStillEnds() {
        // Fairness rule 5. With the phase on damage, time is the only forcing function
        // left: a perfect dodger who never shoots must still be squeezed.
        val d = director()
        assertEquals(1, d.lap)
        run(d, ReckoningDirector.ESCALATION_SECONDS + 1f, health = 1f)
        assertEquals("escalation must advance without any damage", 2, d.lap)
        assertEquals(ReckoningDirector.TIGHTEN_PER_LAP, d.tighten, 0.0001f)
    }

    @Test fun aDebugJumpKnowsWhatHealthItsPatternStartsAt() {
        // RECKONING_PHASE_N asks for "the fight as it is at pattern N", and with the phase
        // on health that means moving the health — otherwise the director would snap back
        // to whatever full HP implies on the next frame.
        val d = director()
        assertEquals(1.0f, d.healthForPhase(1), 0.0001f)
        assertEquals(0.8f, d.healthForPhase(2), 0.0001f)
        assertEquals(0.6f, d.healthForPhase(3), 0.0001f)
        assertEquals(0.2f, d.healthForPhase(5), 0.0001f)
        for (n in 1..5) {
            assertEquals("phase $n must round-trip", n - 1, d.phaseFor(d.healthForPhase(n)))
        }
    }

    @Test fun emissionsAreReturnedToTheCaller() {
        val d = director()
        pastLull(d)
        val out = d.update(1f / 60f, 540f, 1200f, 1f)
        assertEquals(1, out.size)
        assertTrue("everything the director emits is the crystal's", out.all { it.hostile })
    }

    @Test fun updateIsDeterministic() {
        // No Random anywhere: two directors fed identical dt produce identical schedules.
        val a = director()
        val b = director()
        repeat(600) {
            val ea = a.update(1f / 60f, 540f, 1200f, 1f)
            val eb = b.update(1f / 60f, 540f, 1200f, 1f)
            assertEquals(ea.size, eb.size)
        }
        assertEquals(a.phaseIndex, b.phaseIndex)
        assertEquals(a.lap, b.lap)
    }


    @Test fun forEachTickFiresEveryInstantExactlyOnce() {
        // The property every pattern rests on: across consecutive frames each scheduled
        // instant fires once — never twice, never skipped. Asserting a hand-computed list
        // instead would be asserting float rounding, which is not a requirement.
        val interval = 0.07f
        val dt = 1f / 60f
        val fired = ArrayList<Int>()
        var t = 0f
        repeat(600) {
            forEachTick(t, dt, interval) { k, _ -> fired.add(k) }
            t += dt
        }
        assertEquals("no instant may fire twice", fired.size, fired.distinct().size)
        for (i in 1 until fired.size) {
            assertEquals("gap before index ${fired[i]}", fired[i - 1] + 1, fired[i])
        }
        assertEquals("the schedule starts at zero", 0, fired.first())
        // 600 frames at 1/60 is 10s; instants at k * 0.07 for k = 0..142, since
        // 142 x 0.07 = 9.94 and 143 x 0.07 = 10.01. Deliberately not a round multiple —
        // an instant landing exactly on the end of the run is the boundary case this
        // test must not accidentally straddle.
        assertEquals(143, fired.size)
    }

    @Test fun forEachTickStartsAtZero() {
        val hits = ArrayList<Int>()
        forEachTick(0f, 0.1f, 0.04f) { k, _ -> hits.add(k) }
        assertEquals(listOf(0, 1, 2), hits)
    }

    @Test fun forEachTickIgnoresANonAdvancingFrame() {
        // A paused frame must not re-fire the instant it is sitting on.
        var n = 0
        forEachTick(0.5f, 0f, 0.04f) { _, _ -> n++ }
        assertEquals(0, n)
    }

    @Test fun aStalledFrameCannotFastForwardTheFight() {
        // Resume-from-background. HangarSurfaceView does not clamp its deltaTime, so the
        // director must. Unclamped this would advance six phases and emit every instant
        // inside a 100-second window in one frame.
        val d = director()
        d.update(100f, 540f, 1200f, 1f)
        assertEquals("a stall must not burn a lap", 1, d.lap)
        assertEquals("nor skip phases", 0, d.phaseIndex)
        assertTrue(d.phaseElapsed <= ReckoningDirector.MAX_STEP + 0.0001f)
    }

    @Test fun aStalledFrameCannotFloodTheField() {
        // The severe half of the finding, and the half the previous version of this test
        // could not see: emission count must be bounded by the CLAMPED step, not by how
        // long the app was backgrounded. At interval 0.01 an unclamped 100-second frame
        // would fire ten thousand instants in one update.
        val d = ReckoningDirector(m, listOf(TickingProbe("A", 10f, 0.01f)))
        pastLull(d)
        val burst = d.update(100f, 540f, 1200f, 1f)
        assertTrue(
            "a 100s stall emitted ${burst.size} bullets in one frame; MAX_STEP " +
                "${ReckoningDirector.MAX_STEP} at interval 0.01 allows about 5",
            burst.size <= 8
        )
        assertTrue("but it must still emit - a silent stall is its own bug", burst.size >= 4)
    }

    @Test fun anOrdinaryFrameEmitsOnSchedule() {
        // The control. Without it the bound above could be satisfied by emitting nothing.
        val d = ReckoningDirector(m, listOf(TickingProbe("A", 10f, 0.01f)))
        pastLull(d)
        val burst = d.update(1f / 60f, 540f, 1200f, 1f)
        assertTrue("1/60s at interval 0.01 is one or two instants", burst.size in 1..3)
    }

    @Test fun aPatternThatConsumesNoTimeIsRejectedAtConstruction() {
        // The phase advance is a `while`, so a zero-duration pattern would spin forever and
        // freeze the render thread. Loud at construction beats hung at runtime.
        val e = assertThrows(IllegalArgumentException::class.java) {
            ReckoningDirector(m, listOf(Probe("A", 10f), Probe("BAD", 0f)))
        }
        assertTrue(e.message!!.contains("BAD"))
        assertThrows(IllegalArgumentException::class.java) {
            ReckoningDirector(m, listOf(Probe("NEG", -1f)))
        }
    }

    @Test fun anOrdinaryFrameIsNotClamped() {
        // The regression this must not cause: 1/60 and 1/30 are both well under MAX_STEP,
        // so normal play is untouched and the fight does not silently run slow.
        val d = director()
        pastLull(d)
        val before = d.phaseElapsed
        d.update(1f / 60f, 540f, 1200f, 1f)
        assertEquals(before + 1f / 60f, d.phaseElapsed, 0.00001f)
    }

    @Test fun crossingLifeCoversTheLongestDiagonal() {
        // A hostile bullet dies by leaving the field, not by timer - but the timer must
        // not cut it short first, or a shot aimed at a far corner evaporates mid-flight.
        val life = crossingLife(m, 300f)
        assertEquals(kotlin.math.hypot(1080f, 2400f) / 300f, life, 0.0001f)
    }

    @Test fun theDefaultsAreTheFiveInTeachingOrder() {
        val names = ReckoningDirector.DEFAULT_PATTERNS.map { it.name }
        assertEquals(listOf("PULSE", "VOLLEY", "WINDMILL", "CURTAIN", "SHATTER"), names)
    }

    @Test fun aLapIsSeventyFiveSeconds() {
        // The spec's arithmetic: five patterns at ~15s each. A two-to-four minute fight is
        // two or three laps, so most fights never repeat a pattern at all.
        assertEquals(75f, ReckoningDirector.DEFAULT_PATTERNS.sumOf { it.duration.toDouble() }.toFloat(), 0.01f)
    }

    @Test fun everyPatternBindsOnATermThatActuallyTightens() {
        // The trap an earlier change found: a pattern whose narrowest opening is geometric can
        // satisfy rule 1 forever and never satisfy rule 5, because geometry does not
        // scale with tighten. Checked here rather than per pattern so a sixth pattern
        // added later cannot slip through.
        for (p in ReckoningDirector.DEFAULT_PATTERNS) {
            assertTrue(
                "${p.name} does not tighten at all - it can never terminate the fight",
                p.minSpacing(m, 0.421875f) < p.minSpacing(m, 1f)
            )
        }
    }
}
