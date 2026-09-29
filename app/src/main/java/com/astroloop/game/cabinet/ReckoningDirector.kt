package com.astroloop.game.cabinet

import com.astroloop.game.cabinet.patterns.CurtainPattern
import com.astroloop.game.cabinet.patterns.PulsePattern
import com.astroloop.game.cabinet.patterns.ShatterPattern
import com.astroloop.game.cabinet.patterns.VolleyPattern
import com.astroloop.game.cabinet.patterns.WindmillPattern
import kotlin.math.pow

/**
 * The reckoning's clock: which pattern is running, how tight it has become, and what it
 * emits this frame.
 *
 * Shaped after `CrystalFightSystem` — `update()` returns bullets and the caller spawns
 * them — so this holds **no world state and no reference to the sim**, and every fairness
 * rule is provable over it alone. No `Random`: the same `dt` sequence is the same fight.
 *
 * **The fight advances on DAMAGE**: the live pattern is a function of the
 * crystal's health, one per fifth, so all five play for every player by construction —
 * you cannot reach the last fifth without crossing the four before it. A device pass
 * killed the crystal mid-WINDMILL on a first attempt and CURTAIN and SHATTER had never
 * been seen; that is what this fixes.
 *
 * A design decision's HP bands did the same job with a floor pushed in every frame, a fractional
 * carry, and a crystal that stopped reacting mid-pattern. All of it is gone.
 *
 * **Escalation still runs on a clock** ([elapsed]) and must, or a player who dodges
 * perfectly and never shoots would sit in the first pattern for ever.
 */
class ReckoningDirector(
    private val m: CabinetMetrics,
    patterns: List<ReckoningPattern>
) {
    /**
     * Copied, not aliased.
     *
     * `List` in Kotlin is a read-only *view*, not an immutable type — a caller holding the
     * `MutableList` it passed could append a zero-duration pattern after construction, and
     * the check below would already have run. `update()`'s `while` would then hang on it,
     * which is precisely the failure this class checks for. A copy is what makes the check
     * a guarantee rather than a courtesy.
     */
    private val patterns: List<ReckoningPattern> = patterns.toList()

    init {
        require(patterns.isNotEmpty()) { "the reckoning needs at least one pattern" }
        // A sanity check on a DECLARED figure, and no longer a guard against a hang.
        // Phases used to advance by draining phaseElapsed against duration in a `while`,
        // which could not terminate on a zero-duration pattern; a design decision replaced that
        // with phaseFor(healthFrac) and the loop is gone. duration now only documents how
        // long a pattern is meant to run, and this is the one thing still reading it.
        require(patterns.all { it.duration > 0f }) {
            "every pattern must consume time; found ${patterns.first { it.duration <= 0f }.name}"
        }
    }

    /**
     * Total time in the fight, seconds. What escalation runs on.
     *
     * The phase comes from damage now, so nothing else advances on a clock — but something
     * must, or a player who dodges perfectly and never shoots would sit in the first
     * pattern for ever. Fairness rule 5 is why this exists: escalation is the forcing
     * function, and it is the only thing left that presses on someone who refuses to fight.
     */
    var elapsed: Float = 0f
        private set

    /** Which escalation step the fight is on. Derived from [elapsed], not from cycling. */
    val lap: Int get() = (elapsed / ESCALATION_SECONDS).toInt() + 1

    var phaseIndex: Int = 0
        private set

    var phaseElapsed: Float = 0f
        private set

    /**
     * Seconds left in the beat of quiet before the current pattern starts — a design decision.
     *
     * **A lull between the patterns, not inside one.** A design decision retired fairness rule 2
     * because the owner found the scheduled silences inside a pattern unnecessary, and that
     * stands: within a pattern the fire is still unbroken. But it left the crystal with
     * nowhere to speak — measured across a whole phase, a player holding engagement range
     * has nothing within two spacing floors for 1.7% of PULSE, 1.4% of VOLLEY and 1.3% of
     * SHATTER, with unbroken 29-second stretches. The lines were being written and never
     * shown.
     *
     * So the quiet moves to the seam. Every pattern opens with one, the crystal says its
     * piece into it, and then the pattern begins. Bullets already in the air keep flying,
     * so the field drains rather than blinking out.
     */
    var lull: Float = LULL_SECONDS
        private set

    /** True while the fight is between patterns and nothing new is being emitted. */
    val inLull: Boolean get() = lull > 0f

    /** How far into the current lull, seconds — what picks which half of the line shows. */
    val lullElapsed: Float get() = (LULL_SECONDS - lull).coerceAtLeast(0f)

    val pattern: ReckoningPattern get() = patterns[phaseIndex]


    /**
     * Interval multiplier for the current lap: 1.0, then 0.75, 0.5625, 0.421875.
     *
     * This is the forcing function (fairness rule 5). Nothing presses on a player
     * who dodges well and never shoots, so the patterns cycle and each lap tightens —
     * lap two noticeably, lap three frighteningly, lap four not really survivably. A
     * player who engages wins in one or two laps; one who refuses dies to the crystal
     * losing patience, which is the right death.
     */
    val tighten: Float get() = TIGHTEN_PER_LAP.pow(lap - 1)

    /**
     * Which pattern a crystal at [healthFrac] is in — a design decision.
     *
     * **The fight advances on damage, not on a clock.** One pattern per fifth of the
     * crystal's health: 100–80% is the first, 80–60% the second, and so on. A device pass
     * asked for exactly this, and it replaces a design decision's HP bands — which existed to
     * guarantee all five patterns played, and which this guarantees by construction
     * instead. You cannot reach the crystal's last fifth without having passed through the
     * four before it, so there is nothing left to enforce.
     *
     * The bands' whole apparatus goes with it: no floor to push across every frame, no
     * saturated-damage carry, and no crystal that stops reacting while a pattern runs out.
     */
    fun phaseFor(healthFrac: Float): Int =
        // The epsilon is not slack, it is a correction. Band edges land on values that are
        // not representable: `1f - 0.6f` is 0.39999998, so five-fifths of it floors to 1
        // and a crystal sitting exactly on 60% would show the pattern before the one it is
        // owed. Nudging past the boundary puts every edge on the side the design names.
        (((1f - healthFrac) * patterns.size + BAND_EPSILON).toInt())
            .coerceIn(0, patterns.size - 1)

    /**
     * Drop straight into phase [n] at [lap] (default 1). What `RECKONING_PHASE_N` calls.
     *
     * Defaults to lap 1: a jump exists to tune a pattern at its authored difficulty, and
     * landing on an escalated copy of it by surprise would tune the wrong thing. But the
     * default is not a ceiling — the jump is also the cheap way to reach a later lap's
     * escalation on purpose, rather than requiring a full 75-second lap survived first.
     * [lap] is `coerceAtLeast(1)`'d: there is no lap zero.
     *
     * The caller is expected to move the crystal's health to match ([healthForPhase]),
     * because the pattern follows the health: seating the index alone would last exactly
     * one frame before `phaseFor` snapped it back to whatever the health implies.
     */
    fun startAtPhase(n: Int, lap: Int = 1) {
        phaseIndex = n.coerceIn(0, patterns.size - 1)
        phaseElapsed = 0f
        lull = LULL_SECONDS
        // The PARAMETER, not `this.lap` — which is now derived from `elapsed` and would
        // therefore read the value being replaced and seat the clock at zero every time.
        elapsed = (lap.coerceAtLeast(1) - 1) * ESCALATION_SECONDS
    }

    /** The health a debug jump to pattern [n] (1-based) should place the crystal at. */
    fun healthForPhase(n: Int): Float =
        1f - (n - 1).coerceAtLeast(0) * (1f / patterns.size)

    fun update(
        rawDt: Float, shipX: Float, shipY: Float, healthFrac: Float
    ): List<CabinetBullet> {
        // Clamp before anything reads it. HangarSurfaceView.run() derives deltaTime from an
        // unclamped nanoTime() diff — unlike GameThread.kt:39's coerceAtMost(0.033f) — and
        // its own comments anticipate large deltas on resume from background. Unclamped,
        // emit() would receive that whole span and forEachTick would fire every instant
        // inside it, spawning hundreds of bullets in one frame: fairness rule 3's density
        // ceiling and the frame budget both breached at once.
        //
        // Clamping rather than skipping is the right failure mode: a backgrounded player
        // returns to the fight where they left it, slightly slowed, rather than to a lap
        // that ran without them.
        val dt = rawDt.coerceAtMost(MAX_STEP)

        // The pattern follows the damage. phaseElapsed restarts whenever it changes, so
        // each pattern begins its own schedule from zero rather than inheriting the last
        // one's offset — a pattern picked up mid-cycle would emit its first ring late.
        val next = phaseFor(healthFrac)
        if (next != phaseIndex) {
            phaseIndex = next
            phaseElapsed = 0f
            lull = LULL_SECONDS
        }

        elapsed += dt
        if (inLull) {
            // Nothing new is emitted, but the clock still runs: escalation must not be
            // pausable by taking damage, or a player could hold the fight open by trickling
            // hits into it. What is already in the air keeps flying — the caller still
            // updates the sim, so the field drains on its own rather than being cleared.
            lull -= dt
            return emptyList()
        }

        val p = patterns[phaseIndex]
        val out = p.emit(phaseElapsed, dt, tighten, m, shipX, shipY)
        phaseElapsed += dt
        return out
    }

    companion object {
        /** Each lap's intervals, as a fraction of the lap before. */
        const val TIGHTEN_PER_LAP = 0.75f

        /** How long a step of escalation lasts, seconds. Was the length of one full lap. */
        const val ESCALATION_SECONDS = 75f

        /**
         * The beat of quiet at the head of every pattern, seconds.
         *
         * Long enough to carry both halves of a lure and be read — the lines are up to 35
         * characters and arrive in pairs. Five of these across a fight is roughly 25s of
         * punctuation, which is the cost of the crystal being audible at all.
         */
        const val LULL_SECONDS = 5f

        /** Pushes a health sitting exactly on a band edge onto the correct side of it. */
        const val BAND_EPSILON = 1e-4f

        /**
         * Longest step the fight will take in one call, seconds.
         *
         * 20fps. Below that the reckoning runs slow rather than skipping — see [update].
         */
        const val MAX_STEP = 0.05f

        /**
         * The five, in the spec's teaching order: PULSE establishes the grammar, VOLLEY
         * punishes standing still, WINDMILL asks for sustained weaving, CURTAIN for
         * planning, SHATTER for nerve.
         *
         * Order is content, not configuration. Reordering these changes what the fight
         * teaches and in what sequence, and every one of them assumes the player has met
         * the ones before it.
         */
        val DEFAULT_PATTERNS: List<ReckoningPattern>
            get() = listOf(
                PulsePattern(), VolleyPattern(), WindmillPattern(),
                CurtainPattern(), ShatterPattern()
            )
    }
}
