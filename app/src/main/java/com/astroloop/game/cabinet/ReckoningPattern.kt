package com.astroloop.game.cabinet

import kotlin.math.ceil
import kotlin.math.hypot

/**
 * One of the crystal's five attacks.
 *
 * Every pattern **declares its own fairness figures** and the harness checks the
 * declaration against what the pattern actually emits. Declaring alone is
 * cheap and is how `CrystalFightSystem`'s tests stay three lines each — but a declaration
 * nothing verifies is precisely the failure this branch keeps catching, most sharply in
 * the stage 1 final review, where tests ran green over a path the product never took.
 * **A pattern must not be able to lie about itself.**
 *
 * Implementations are **pure and stateless**: everything derives from the phase-local
 * time [emit] is handed, so two directors fed identical `dt` produce identical fights and
 * a jump straight into phase N needs no warm-up.
 */
interface ReckoningPattern {

    /** Shown in no UI; used by tests and reports. Keep it the spec name. */
    val name: String

    /**
     * Seconds this pattern runs in one lap.
     *
     * Does NOT scale with `tighten` — a design decision. Escalation tightens emission intervals
     * only, so the fight densifies without accelerating and a lap stays ~75s.
     *
     * **Must be > 0, and must not change between reads.** [ReckoningDirector] drains
     * elapsed time against this in a loop, which cannot terminate on a phase that consumes
     * none. The director checks it once at construction; a `duration` backed by changing
     * external state would pass that check and hang later, so this is a stored value in
     * every implementation, never a computed one — which the interface's own pure and
     * stateless contract already requires.
     */
    val duration: Float

    /**
     * The smallest **passable opening** this pattern presents, in pixels, measured at
     * [ReckoningFairness.ENGAGEMENT_RANGE_FRAC] from the crystal. Fairness rule 1
     * requires this to clear [ReckoningFairness.spacingFloor].
     *
     * "Passable opening" rather than "gap between adjacent bullets" is deliberate: for
     * PULSE the way through a ring is its gap, not the arc between two bullets in it.
     *
     * **Measured at engagement range, not at the crystal's surface**, and that choice is
     * load-bearing — see [ReckoningFairness.ENGAGEMENT_RANGE_FRAC]. Radial patterns are
     * geometrically tighter the closer you are, so closing on the crystal costs the
     * player room. That is the spec's *"damage is never free"* expressed as geometry rather
     * than as a rule, and it is a property to preserve, not to engineer away.
     */
    fun minSpacing(m: CabinetMetrics, tighten: Float): Float


    /** Peak simultaneously-live bullets. Fairness rule 3. */
    fun peakDensity(m: CabinetMetrics, tighten: Float): Float

    /**
     * Emit whatever falls in `[t, t + dt)`.
     *
     * @param t phase-local elapsed time, seconds
     * @param tighten 1.0 on lap 1, 0.75 on lap 2, and so on — multiply intervals by it
     * @param shipX the player's x position, for patterns that aim (VOLLEY)
     * @param shipY the player's y position, for patterns that aim (VOLLEY)
     */
    fun emit(
        t: Float, dt: Float, tighten: Float, m: CabinetMetrics, shipX: Float, shipY: Float
    ): List<CabinetBullet>
}

/**
 * Calls [body] once for every multiple of [interval] falling in `[t, t + dt)`.
 *
 * This is what makes patterns stateless: rather than carrying a cooldown that drifts, a
 * pattern asks which of its scheduled instants belong to this frame. The half-open range
 * is what stops an instant landing exactly on a frame boundary from firing twice.
 *
 * **Requires `t >= 0`.** The clamp below guards against unbounded negative-index iteration
 * rather than describing a domain: an instant scheduled at a negative index is dropped, not
 * fired. Every call site passes a phase-local elapsed time, which starts at zero and only
 * grows, so this is a precondition rather than a limitation.
 */
inline fun forEachTick(t: Float, dt: Float, interval: Float, body: (Int, Float) -> Unit) {
    if (interval <= 0f || dt <= 0f) return
    // Both bounds go through the SAME expression, and the caller's next `t` is literally
    // this frame's `t + dt` — the same float, the same division, the same result. So this
    // frame's exclusive upper index IS the next frame's inclusive lower index, and the
    // windows tile exactly whatever the rounding does. Comparing `k * interval` against
    // `t + dt` instead lets the two sides round independently, and an instant landing on
    // a frame boundary then fires twice or not at all.
    val from = ceil(t / interval).toInt()
    val to = ceil((t + dt) / interval).toInt()
    // Guards against unbounded negative-index iteration; requires t >= 0 (see KDoc) — an
    // instant at a negative index is dropped here, not fired.
    var k = if (from < 0) 0 else from
    while (k < to) { body(k, k * interval); k++ }
}

/**
 * How long a hostile bullet must be allowed to live to cross the field on its longest
 * diagonal.
 *
 * Hostile bullets die by leaving the field, not by timer — but the timer must not cut
 * them short first, or a shot heading for a far corner evaporates in mid-air, which is an
 * instant disappearance, which nothing in this game is allowed.
 */
fun crossingLife(m: CabinetMetrics, speed: Float): Float = hypot(m.width, m.height) / speed

/**
 * The crystal is anchored at the field's centre (the spec — *"You always know where to point"*),
 * so patterns emit from there rather than being handed a position they cannot influence.
 */
fun crystalX(m: CabinetMetrics): Float = m.width / 2f

/** @see crystalX */
fun crystalY(m: CabinetMetrics): Float = m.height / 2f
