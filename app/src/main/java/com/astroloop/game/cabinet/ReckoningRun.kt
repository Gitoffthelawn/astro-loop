package com.astroloop.game.cabinet

/**
 * One attempt at the ending: the authored opening, then the director, then an outcome.
 *
 * Exists so [CabinetShell] does not grow a second job. The shell decides *when* the sim
 * may advance; this decides *what the fight is doing* while it does.
 */
class ReckoningRun(
    private val m: CabinetMetrics,
    private val startPhase: Int = 0,
    private val startLap: Int = 1,
    /**
     * True when this run was started from the MENU's replay entry rather than by the gate.
     *
     * The distinction lives on the RUN, not on the shell or the cabinet session. A
     * session-keyed latch would survive AGAIN?, which restarts a run without reopening the
     * cabinet — anything keyed to the session would carry into the next attempt and the
     * next fresh reckoning would misread itself as a replay too. Immutable, so it cannot be
     * flipped mid-fight.
     */
    val isReplay: Boolean = false
) {
    enum class Outcome { RUNNING, WON, LOST }

    val opening = ReckoningOpening(m, crystalHasBody = !isReplay)
    val director = ReckoningDirector(m, ReckoningDirector.DEFAULT_PATTERNS)

    var outcome: Outcome = Outcome.RUNNING
        private set

    /** True for exactly the frame the crystal lands. The host plays the impact on it. */
    var justLanded: Boolean = false
        private set

    /**
     * How many bullets the pattern put on the field this frame.
     *
     * The host plays ONE cue when this is non-zero, never one per bullet: PULSE opens a ring
     * of nine in a single frame and SHATTER a cluster of eight, and a cue each is a machine
     * gun rather than a tell.
     */
    var bulletsEmittedThisFrame: Int = 0
        private set

    /**
     * Whether there is a crystal inside the shell — a design decision.
     *
     * False on a replay, and it is the SAME fact [CrystalVoice] reads to substitute the
     * machine's readout for the crystal's lines. You released it; a replay is the cabinet
     * running a recording, so what you fight is the containment with nothing in it.
     *
     * Read off `isReplay` here rather than at each of the two call sites, so the body and
     * the voice cannot be changed apart from one another.
     *
     * This is the VOICE's copy, and the crystal-in-flight's. Once the crystal lands, the body
     * carries its own — [CabinetCrystal.hasBody], handed over at construction from the same
     * `isReplay` — because the drawn shell has to outlive this run object: the exit lets go of
     * the run while the GAME OVER screen it drew is still on a tube that is powering down.
     * A test pins the two against each other.
     */
    val crystalHasBody: Boolean get() = !isReplay

    /**
     * Seconds of FIGHT — what the release record measures, a design decision.
     *
     * The director's clock, which starts when the crystal lands: the twelve-second authored
     * opening is choreography nobody can hurry, so including it would only add a constant
     * and make the number less honest about what it measures.
     *
     * **A debug jump reads 0, and that is the guard.** `startAtPhase` seeds `elapsed` to put
     * the fight at a chosen lap, so on a jump this clock never measured anything —
     * `setBestReleaseSecondsIfBetter` refuses a non-positive time, so a jumped fight cannot
     * set a record without any call site having to remember to check.
     */
    val fightSeconds: Float get() = if (startPhase > 0) 0f else director.elapsed

    /** True for exactly the frame the crystal comes apart. The host plays the ending on it. */
    var justShattered: Boolean = false
        private set

    fun begin(sim: CabinetSim) {
        outcome = Outcome.RUNNING
        opening.begin(sim)
        if (startPhase > 0) {
            // A design decision: 1..5 skip straight in, field cleared and crystal present, so
            // tuning a pattern does not cost the twelve-second opening every time.
            opening.skipToFight(sim)
            director.startAtPhase(startPhase - 1, startLap)
            // The pattern follows the health now, so a jump has to move the health or the
            // director would snap straight back to whatever the crystal's full HP implies.
            // P3 means "the fight as it is at 60%", which is what the operator is asking to
            // look at.
            sim.crystal?.let { it.damage(it.maxHp - (it.maxHp * director.healthForPhase(startPhase)).toInt()) }
        }
    }

    fun update(dt: Float, sim: CabinetSim) {
        // Cleared BEFORE the outcome guard, not after it. Every early return below leaves
        // these standing otherwise, and the guard at the top is taken on every frame of the
        // win hold — so a shatter left latched would replay the ending's sound for the
        // whole three seconds, and an emission count would fire a cue behind a dead crystal.
        bulletsEmittedThisFrame = 0
        justShattered = false
        // justLanded belongs here too, and it was the one left below the guard. It is
        // assigned from opening.update() further down — which runs BEFORE the sim.over
        // check — so a ship that dies on the very frame the crystal touches down leaves it
        // latched true, and every later call returns at the guard without clearing it. The
        // host's landing cue is not gated on screen, so the sting then retriggers every
        // frame through GAME OVER.
        justLanded = false

        if (outcome != Outcome.RUNNING) return

        // One-shot, consumed by the host. The landing is the entrance's punctuation and it
        // needs a sound, but neither this class nor ReckoningOpening may touch SoundManager
        // — they are pure, which is what lets the whole entrance be tested.
        justLanded = opening.update(dt, sim)

        // Before the stage guard, not after. A death during the authored opening — against
        // a seeded ring rock during Stage.PLAY, or against a frozen one while the crystal
        // is crossing in Stage.ARRIVAL — is an ordinary way to lose, not an edge case, and the guard
        // below would otherwise return first and leave a finished run reporting RUNNING
        // forever. It also stays ahead of the crystal branches further down for the same
        // reason: a player who dies while the crystal is still very much alive must be
        // recognised as LOST, not have the run silently re-emit one more volley from a
        // fight that is already over for them.
        //
        // Deliberately no crystal check here. It reads as if one is needed - "don't let
        // this steal the frame the WON branch below is about to decide" - but that branch
        // is the only thing that ever calls endRun(), and it only does so after outcome is
        // already WON, at the tail of this same call; every later call is stopped by the
        // guard at the top of this function before it ever reaches here. So the one state
        // where sim.over is true, outcome is still RUNNING, and the crystal happens to
        // already be dead is never "WON, already handled" - it is resolveBulletHits() and
        // resolveHostileHits() both firing inside one sim.update(), the player's shot
        // killing the crystal the same instant a hostile bullet already in flight kills the
        // ship. That is a loss (killingTheCrystalWinsAndShattersIt's "the pilot survives
        // their own victory" is the whole point), and a crystal-alive guard here would
        // misread it as a win instead.
        if (sim.over) {
            outcome = Outcome.LOST
            return
        }

        if (opening.stage != ReckoningOpening.Stage.FIGHT) return

        // The crystal has a body, so it can shoot. Emitting before this point would
        // undo the entrance's whole image - shots from a thing that is not there yet.
        val c = sim.crystal
        if (c != null && c.alive) {
            // The crystal's health decides which pattern is live — a design decision. It is
            // passed in rather than read, because the director still knows nothing about
            // the crystal: it is handed a number, not a thing.
            for (b in director.update(dt, sim.ship.x, sim.ship.y, c.healthFrac)) {
                sim.addHostileBullet(b)
                bulletsEmittedThisFrame++
            }
            return
        }

        if (c != null && !c.alive) {
            sim.shatterCrystal()
            sim.timeScale = ENDING_TIME_SCALE
            sim.endRun()
            outcome = Outcome.WON
            justShattered = true
        }
    }

    companion object {
        /** The last thing the player watches; it should not go past at full speed. */
        const val ENDING_TIME_SCALE = 0.35f
    }
}
