package com.astroloop.game.cabinet

import kotlin.math.ln

/**
 * The reckoning's fairness contract, as numbers.
 *
 * The most load-bearing part of the fight this replaces was never its constants — it was
 * that its fairness was unit-tested. That property carries; the numbers do not. Every
 * figure here **derives** from a cabinet constant, so a change to the flight model moves
 * the contract with it rather than leaving a stale literal behind. That has already
 * happened once on this branch: `commitmentDistance()`'s 140px survived in the design doc
 * for days after the function that produced it was deleted.
 *
 * Reference figures are for a 1080px short edge.
 */
object ReckoningFairness {

    /**
     * **RULE 1b IS RETIRED, and this is the record of why.**
     *
     * It said: an opening the player must be *standing in* has to be one they can *get to*.
     * Rule 1 measures the hole's WIDTH and would not notice a hole that sweeps faster than
     * the ship can fly. An earlier change found exactly that on hardware — PULSE's gap needed 378px/s
     * at engagement range against a top speed of 302.4, and its rings outran the ship
     * radially too, so there was no escape in either direction.
     *
     * It is retired because **no pattern has such an opening any more.** A device pass took
     * PULSE's gap away (*"it's easy enough right now to dodge even without the gap"*) and
     * CURTAIN's walking hole with it; WINDMILL's arms were always pickets you dive between,
     * which is its whole question. With nothing declaring an occupied opening, the check
     * short-circuited for all five patterns and passed unconditionally — coverage in
     * appearance only, which is worse than no check at all.
     *
     * ⚠️ **Reinstate it with any pattern that brings back a hole you must sit in.** The
     * shape it needs: an angular sweep rate, and `rate * engagementRange(m) <= topSpeed` at
     * every lap through [FAIR_LAPS].
     */
    private const val RULE_1B_RETIRED = true

    /**
     * Room to react, on top of the bare sum.
     *
     * The spec states rule 1 as "spacing clears danger diameter + braking distance" with no
     * margin — but the fight it replaces carried "with room to react" inside its own
     * tests (`spacing >= 2.5 * dangerDiameter`). A bare sum certifies a stream as
     * threadable when it is exactly, and only just, threadable.
     */
    const val SPACING_MARGIN = 1.25f

    /**
     * Where a player realistically engages from — 432px at reference.
     *
     * Also **the radius at which every pattern's opening is measured**, and that is a
     * deliberate convention rather than a convenience. Radial emissions are tighter the
     * nearer the crystal you are: `N` bullets in a ring are `2πr/N` apart, so the same
     * ring that offers 340px of room at engagement range offers 86px at the crystal's
     * own surface. Measuring at the surface would cap every simultaneous emission at
     * five bullets and make VOLLEY, CURTAIN and SHATTER geometrically impossible.
     *
     * The consequence is a feature: **closing on the crystal costs you room.** The spec says
     * damage is never free because shooting means flying at the thing trying to kill
     * you; this is that same statement in geometry. Do not "fix" the tightness near the
     * crystal — it is the fight.
     */
    const val ENGAGEMENT_RANGE_FRAC = 0.4f

    /** The radius every pattern's [ReckoningPattern.minSpacing] is measured at. */
    fun engagementRange(m: CabinetMetrics): Float = ENGAGEMENT_RANGE_FRAC * m.minEdge

    /**
     * Peak simultaneously-live hostile bullets.
     *
     * Not a pool limit — `CabinetSim` uses an `ArrayList` — but a frame-time one. Each
     * hostile bullet is a streak through the three-pass glow, so this is ~360 `drawPath`
     * calls, against a menu measured at ~1000. Frame time on slower devices is a standing
     * complaint and this feature must not make it worse.
     */
    const val DENSITY_CEILING = 120

    /**
     * The last lap on which rules 1 and 2 are asserted.
     *
     * The spec: *"A player who engages wins in one or two laps."* Every lap a winning player
     * actually meets is provably fair; past that is the crystal losing patience.
     */
    const val FAIR_LAPS = 2

    /** The lap by which escalation must demonstrably bite — fairness rule 5. */
    const val ESCALATED_LAP = 4

    /**
     * How far the ship travels before it can be going the other way, at top speed.
     *
     * Mirrors `CabinetShip.reversalDistance()` at `sp == topSpeed`, where it collapses to
     * `(T/k)(1 - ln2)`. Worst case is the only case worth measuring — and the
     * function is speed-dependent, so a representative value would understate it.
     *
     * NOTE: the spec justifies the "range rather than a constant" by a design decision's analog
     * thrust. **That decision was reversed.** The conclusion still stands, but because
     * the velocity lerp commands `-topSpeed` regardless of current speed — not because
     * thrust is analog.
     */
    fun reversalDistance(m: CabinetMetrics): Float =
        (m.topSpeed / CabinetMetrics.VELOCITY_LERP) * (1f - ln(2f))

    /**
     * The ship plus a bullet, doubled — the width of lane a shot denies.
     *
     * Uses the SMALLER crystal-bullet hitbox, not the rock radius: the rock
     * circle is about twice the drawn hull at mid-ship, so measuring against it would
     * demand spacing for a ship far larger than the one on screen.
     */
    fun dangerDiameter(m: CabinetMetrics): Float =
        2f * (m.crystalBulletHitRadius + m.crystalBulletRadius)

    /** Fairness rule 1's floor. 121.9px at reference. */
    fun spacingFloor(m: CabinetMetrics): Float =
        (dangerDiameter(m) + reversalDistance(m)) * SPACING_MARGIN

    /** 90% of a 180° reorientation at `TURN_RATE`. 0.288s. */
    fun turnTime(): Float = ln(10f) / CabinetMetrics.TURN_RATE
}
