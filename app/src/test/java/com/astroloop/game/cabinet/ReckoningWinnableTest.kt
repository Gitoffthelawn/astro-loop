package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Fairness rule 4, both halves — one pilot per half, and neither of them is a person.
 *
 * [aim] is deliberately DUMB: it dodges the nearest threat and otherwise flies at the
 * crystal, never aiming. That is the point — it establishes a LOWER bound, and a crystal
 * it can kill is one a human can certainly kill. It reuses `CabinetAttract.aim()`'s shape,
 * a pure policy driving `sim.update()`, which is already extracted, deterministic under an
 * injected `Random`, and settled.
 *
 * [perfectFire] is deliberately IMPOSSIBLE: parked on the firing line, immune, never
 * needing to turn. It establishes the UPPER bound, and it is what a device pass proved
 * this file was missing — the old ceiling test was arithmetic, and the arithmetic was
 * wrong, so the crystal died before CURTAIN on a first attempt and two authored patterns
 * had never been seen.
 */
class ReckoningWinnableTest {

    private val m = CabinetMetrics(1080f, 2400f)

    /** Steer away from the nearest incoming shot; otherwise close on the crystal. */
    /**
     * A potential field: pushed off every live hostile bullet by 1/d^2, pulled toward the
     * crystal, holding at engagement range.
     *
     * Deliberately has NO reaction threshold, because a threshold cannot work here and
     * that is a property of the flight model rather than of any number. A bullet closing
     * at up to 626 px/s crosses a 74.5px panic radius in 0.119s, and at VELOCITY_LERP 1.5
     * the ship builds 3.03px of lateral clearance in that time against the 17.8px it
     * needs. Off by 6x. The cabinet's ship is floaty by design - "more momentum" was the
     * whole point of a device pass - so anyone flying it must ANTICIPATE, and widening the
     * threshold until a reactive dodge passes would be fitting the robot to the test,
     * which decision explicitly rejected.
     *
     * The one scale it does carry is derived, not chosen: repulsion balances the crystal's
     * pull at exactly [ReckoningFairness.spacingFloor], the distance the contract itself
     * calls the edge of threadable.
     */
    private fun aim(sim: CabinetSim): Pair<Float, Float> {
        val ship = sim.ship
        var rx = 0f
        var ry = 0f
        for (b in sim.bullets) {
            if (!b.hostile) continue
            val dx = m.wrappedDelta(ship.x, b.x, m.width)
            val dy = m.wrappedDelta(ship.y, b.y, m.height)
            val d = hypot(dx, dy).coerceAtLeast(1f)
            val w = repelScale / (d * d)
            rx += dx / d * w
            ry += dy / d * w
        }
        val c = sim.crystal
        if (c != null) {
            val dx = m.wrappedDelta(c.x, ship.x, m.width)
            val dy = m.wrappedDelta(c.y, ship.y, m.height)
            val d = hypot(dx, dy).coerceAtLeast(1f)
            // Hold at engagement range: close if further, back off if nearer. Flying all
            // the way in is not dumb play, it is suicide - the crystal's body kills.
            val sign = if (d > ReckoningFairness.engagementRange(m)) 1f else -1f
            rx += dx / d * sign
            ry += dy / d * sign
        }
        val n = hypot(rx, ry)
        if (n < 0.001f) return 0f to 0f
        return (rx / n) to (ry / n)
    }

    private val repelScale =
        ReckoningFairness.spacingFloor(m) * ReckoningFairness.spacingFloor(m)

    private fun flyIt(startPhase: Int, seconds: Float): Pair<ReckoningRun, CabinetSim> {
        val sim = CabinetSim(m, Random(20260819))
        sim.start()
        val run = ReckoningRun(m, startPhase)
        run.begin(sim)
        val dt = 1f / 60f
        var t = 0f
        while (t < seconds && run.outcome == ReckoningRun.Outcome.RUNNING) {
            val (ax, ay) = aim(sim)
            sim.update(dt, ax, ay, true)
            run.update(dt, sim)
            t += dt
        }
        return run to sim
    }

    @Ignore(
        "Rule 4's lower bound has no CI gate — but NOT for the reason first recorded. The " +
            "original @Ignore blamed the design, claiming a policy naive enough to be a " +
            "fair lower bound cannot both survive and shoot. That was measured against a " +
            "BLOCKED GUN: MAX_BULLETS gated the shared bullet list, so with the crystal's " +
            "shots on the field the player fired once in fifteen seconds. Fixed in " +
            "CabinetSim; the same pilot now fires at 3.40/sec and deals 29 damage in 76s " +
            "where it dealt 3. What remains is accuracy, not the trade-off: this pilot " +
            "lands 11% of its shots because it holds range and dodges rather than aiming, " +
            "so it cannot finish the crystal in two laps while an ordinary human " +
            "does it in one. That gap is the pilot's aim, and closing it means writing a " +
            "pilot that aims — which is a real decision, not a tuning knob. A device pass " +
            "then settled which side the risk is on: the first player to fly it WON on " +
            "their first attempt, before CURTAIN, so this pilot was never evidence about " +
            "players and the fight was never in danger of being unwinnable. Kept rather " +
            "than deleted so the requirement stays visible."
    )
    @Test fun aDumbPilotCanKillIt() {
        // Rule 4, lower bound.
        //
        // The instruction here used to read "if this is ever un-ignored and fails,
        // CRYSTAL_HP comes DOWN - never the fairness numbers". That was written when the
        // fear was an unwinnable fight, and a device pass measured the opposite: a first-
        // time player won before CURTAIN and two authored patterns had never been seen.
        // So a failure here is NOT a licence to lower CRYSTAL_HP — read it off
        // ReckoningOpening rather than quoting a figure here, which is how this comment
        // came to claim 300 against the 180 the constant actually holds.
        // A failure means this pilot's 11% accuracy is the thing that is wrong, and the
        // fix is a pilot that aims. The fairness numbers stay untouched either way.
        val budget = ReckoningFairness.FAIR_LAPS * LAP_SECONDS
        val (run, _) = flyIt(startPhase = 1, seconds = budget)
        assertEquals(
            "a dumb pilot must finish inside ${budget}s (${ReckoningFairness.FAIR_LAPS} laps)",
            ReckoningRun.Outcome.WON, run.outcome
        )
    }


    /**
     * A pilot that cannot miss, flown against the real sim until SHATTER starts.
     *
     * Parked due south of the crystal at engagement range with its nose on it. Heading 0
     * is up the screen and an uninputted ship neither turns nor accelerates, so every
     * shot the autofire lets go leaves on the same line into a stationary 23.8px target
     * with an empty field behind it. Nothing misses, and the cadence is `FIRE_INTERVAL`'s
     * 6/sec — the ceiling, since `MAX_BULLETS` cannot bind at a 0.5s flight.
     *
     * It is deliberately better than any human: it is never made to turn, never made to
     * break off, and the director's bullets are counted for their timing but dropped
     * rather than spawned, so it cannot be shot at all. An upper bound has to be
     * unreachable to be an upper bound — that is the same reasoning [aDumbPilotCanKillIt]
     * uses in the other direction, where a pilot worse than any human sets the lower one.
     *
     */
    /** Every pattern a perfect pilot met on the way down, in the order it met them. */
    private fun perfectFire(): Pair<List<String>, Int> {
        val sim = CabinetSim(m, Random(20260823))
        sim.start()
        sim.clearRocksForTest()
        sim.wavesSuspended = true
        val c = CabinetCrystal(m.width / 2f, m.height / 2f, ReckoningOpening.CRYSTAL_HP)
        sim.placeCrystal(c)
        sim.placeShip(c.x, c.y + ReckoningFairness.engagementRange(m))
        sim.ship.heading = 0f
        val director = ReckoningDirector(m, ReckoningDirector.DEFAULT_PATTERNS)
        val dt = 1f / 60f
        val seen = ArrayList<String>()
        var shots = 0
        var guard = 0
        while (c.alive && guard++ < 60_000) {
            director.update(dt, sim.ship.x, sim.ship.y, c.healthFrac)
            if (seen.lastOrNull() != director.pattern.name) seen.add(director.pattern.name)
            sim.update(dt, 0f, 0f, false)
            shots += sim.shotsFiredThisFrame
        }
        return seen to shots
    }

    @Test fun evenPerfectFireMeetsEveryPattern() {
        // Rule 4's upper bound, and the exact thing that failed on device: CURTAIN and
        // SHATTER had never been seen by a player.
        //
        // It has been three different tests. First arithmetic — MAX_BULLETS /
        // BULLET_LIFETIME — which was false, because the bullet cap never binds and the
        // cadence does. Then a check that decision's HP bands held a pilot back until
        // the last pattern started.
        //
        // A design decision makes both unnecessary: the pattern follows the crystal's health, one
        // per fifth, so the last fifth is unreachable without crossing the four before it.
        // The guarantee is structural, and this asserts the structure rather than a rate.
        val (seen, shots) = perfectFire()
        assertEquals(
            "a pilot that cannot miss still met ${seen.size} patterns: $seen",
            ReckoningDirector.DEFAULT_PATTERNS.map { it.name }, seen
        )
        // Not vacuous: the pilot really did land a lethal fight's worth of shots. Without
        // this the test would also pass against a gun that never fired.
        assertTrue(
            "perfect fire landed only $shots shots on a ${ReckoningOpening.CRYSTAL_HP} " +
                "HP crystal - this pilot is not establishing an upper bound on anything",
            shots >= ReckoningOpening.CRYSTAL_HP
        )
    }

    @Test fun theFightIsNotOverInSeconds() {
        // The other trivial failure: a crystal so soft the fight is a formality.
        //
        // NARROWED, deliberately. This used to assert the run was still RUNNING, which
        // conflates two different failures: the crystal dying fast (what the comment above
        // describes, and what this test is for) and the PILOT dying fast (which is fairness
        // rule 5's business, and is covered by aPilotThatNeverAttacksDies). A design decision
        // retired the lull and this pilot now dies at 15.9s, so the old form failed for a
        // reason it was never written to detect. Asserting on the crystal says what the
        // comment always claimed.
        val (_, sim) = flyIt(startPhase = 1, seconds = 20f)
        assertTrue(
            "twenty seconds is not a finale — the crystal must still be standing",
            sim.crystal?.alive == true
        )
    }

    @Test fun aPilotThatNeverAttacksDies() {
        // Fairness rule 5, end to end rather than per pattern. Someone who dodges well
        // and never turns to shoot must not be able to orbit forever - that is the hole
        // the first draft of the design had, and the reason the patterns cycle at all.
        val sim = CabinetSim(m, Random(4242))
        sim.start()
        val run = ReckoningRun(m, 1)
        run.begin(sim)
        // Park the gun by keeping the crystal permanently topped up.
        val dt = 1f / 60f
        var t = 0f
        val limit = (ReckoningFairness.ESCALATED_LAP + 1) * LAP_SECONDS
        // An unkillable crystal rather than a healed one. The sim autofires
        // unconditionally - fire() has no input gate - so the pilot cannot stop
        // shooting, but against REFUSENIK_HP it cannot meaningfully dent it either.
        // Healing through damage(-1) would abuse a named mutator and would need the
        // production clamp widened to serve a test.
        sim.placeCrystal(CabinetCrystal(m.width / 2f, m.height / 2f, REFUSENIK_HP))
        while (t < limit && sim.ship.alive) {
            val (ax, ay) = aim(sim)
            sim.update(dt, ax, ay, true)
            run.update(dt, sim)
            t += dt
        }
        assertFalse(
            "a refusenik must die to the crystal losing patience, by lap " +
                "${ReckoningFairness.ESCALATED_LAP + 1} at the latest",
            sim.ship.alive
        )
    }

    companion object {
        private const val LAP_SECONDS = 75f
        private const val DODGE_RADII = 6f

        /** The pattern whose arrival the whole band mechanism exists to guarantee. */

        /** Far beyond anything the gun can reach in five laps. */
        private const val REFUSENIK_HP = 1_000_000
    }
}
