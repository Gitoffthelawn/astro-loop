package com.astroloop.game.cabinet

import com.astroloop.game.data.CrystalFightLines
import com.astroloop.game.system.CrystalPhase

/**
 * What the crystal is saying, this frame — a design decision.
 *
 * Pure, and separated from the drawing for the reason this branch keeps relearning: a unit
 * test drives a unit, the host composes them, and the composition is what breaks. Three of
 * this feature's four escaped defects lived in a host where nothing could reach them. The
 * schedule is the part with edges in it, so the schedule is the part that gets tested.
 *
 * The gel strip is the crystal's own real estate — a design decision made it red, and during the
 * reckoning it already carries the crystal's health rather than a score. Speaking there is
 * the crystal using a surface it has taken, which is the same idea the takeover puts on the
 * cabinet itself.
 *
 * **Silence is authored, not absent.** Every branch here can return null, and most of a
 * phase is quiet: a lure that never stops talking is noise, and the pauses are what make
 * the next line arrive.
 */
object CrystalVoice {

    /** Beat one lands once the player has settled into what looks like an ordinary game. */
    const val BEAT_ONE_AT = 3f

    /** Beat two, far enough after that the first has been read and let go. */
    const val BEAT_TWO_AT = 7f

    /** How long an opening beat stays up. */
    const val BEAT_HOLD = 3.5f

    /**
     * Where in the lull the second half of a lure arrives — a design decision.
     *
     * The crystal speaks into the beat of quiet at the head of each pattern rather than
     * over the pattern itself. That beat is [ReckoningDirector.LULL_SECONDS] long and
     * carries both halves, so this splits it.
     *
     * **The gate this replaces did not work, and it was measured rather than guessed at.**
     * Lines used to wait for local room — nothing within two spacing floors. Against a
     * player holding engagement range that is true for 1.7% of PULSE, 1.4% of VOLLEY and
     * 1.3% of SHATTER, in stretches leaving 29 unbroken seconds of silence. Three of the
     * five patterns were effectively mute. The quiet had to be made, not found.
     */
    const val PART_TWO_AT = 2.5f

    /**
     * The line for a given moment, or null for silence.
     *
     * Beat three is deliberately NOT on a timer: it shows for the whole of
     * [ReckoningOpening.Stage.ARRIVAL], so *"So I came here"* is on screen for exactly as
     * long as the crystal is crossing the field. Tying it to the stage rather than to a
     * clock means the line and the flight cannot drift apart if either is retuned.
     */
    fun lineAt(
        stage: ReckoningOpening.Stage,
        openingElapsed: Float,
        phaseIndex: Int,
        inLull: Boolean,
        lullElapsed: Float
    ): String? = when (stage) {
        ReckoningOpening.Stage.PLAY -> when {
            openingElapsed >= BEAT_ONE_AT && openingElapsed < BEAT_ONE_AT + BEAT_HOLD ->
                CrystalFightLines.opening[0]
            openingElapsed >= BEAT_TWO_AT && openingElapsed < BEAT_TWO_AT + BEAT_HOLD ->
                CrystalFightLines.opening[1]
            else -> null
        }
        // Beat three rides the whole flight in, so "So I came here" is on screen for
        // exactly as long as the crystal is crossing the field toward you.
        ReckoningOpening.Stage.ARRIVAL -> CrystalFightLines.opening[2]
        ReckoningOpening.Stage.FIGHT -> when {
            // Only into the seam. Once the pattern is firing the crystal has nothing to
            // say, because there is no moment in which it could be read.
            !inLull -> null
            lullElapsed < PART_TWO_AT -> CrystalFightLines.taunt(phaseFor(phaseIndex)).first
            else -> CrystalFightLines.taunt(phaseFor(phaseIndex)).second
        }
    }

    /**
     * Convenience for the host, which holds the run rather than its fields.
     *
     * The outcome is handled HERE rather than inside [lineAt], which stays about the fight
     * itself. A finished run has no phase and no lull, so threading it through would mean
     * teaching the fight's schedule about states in which there is no schedule.
     */
    fun lineFor(run: ReckoningRun): String? {
        // A REPLAY HAS NO CRYSTAL IN IT. Owner, device pass: "when you enter the fight
        // through the ??? button, the crystal itself is gone, so I don't want it speaking
        // to you." It is gone — `crystal_released` is set, the story resolved, and ??? runs
        // a fight that already happened. The lures, the pleading and the thanks all belong
        // to the first time and cannot be said again by something that is not there.
        //
        // What is left is the cabinet, running a recording of it, so the strip carries the
        // machine's own readout instead. Cold, and deliberately not a voice: no one is
        // addressing the player here.
        if (run.isReplay) return machineReadout(run)
        return crystalLineFor(run)
    }

    /** The cabinet reporting on itself while it replays a fight nobody is in. */
    private fun machineReadout(run: ReckoningRun): String? = when (run.outcome) {
        // The record is the point of the ??? entry, so the time is what the machine says
        // when it finishes — where the crystal's thanks would have been.
        // Three digits, zero-padded — the machine's convention everywhere else it shows a
        // number, and this printed a bare one. The board shows the very same figure as
        // "BEST RELEASE 087"; the two are far apart in the code and were formatted apart.
        ReckoningRun.Outcome.WON ->
            "RELEASE " + run.fightSeconds.toInt().coerceIn(0, 999).toString().padStart(3, '0')
        ReckoningRun.Outcome.LOST -> null
        ReckoningRun.Outcome.RUNNING ->
            if (run.opening.stage != ReckoningOpening.Stage.FIGHT) PLAYBACK
            else patternLabel(run.director.phaseIndex)
    }

    /** What the machine is running, 1-indexed for a reader rather than for the array. */
    fun patternLabel(phaseIndex: Int): String =
        "PATTERN ${phaseIndex.coerceIn(0, CrystalPhase.entries.size - 1) + 1} OF ${CrystalPhase.entries.size}"

    /** Shown for the whole of the authored opening, which a replay still plays. */
    const val PLAYBACK = "PLAYBACK"

    private fun crystalLineFor(run: ReckoningRun): String? = when (run.outcome) {
        // A design decision. Into the hold the shell already takes while the shatter clears —
        // about three seconds at ENDING_TIME_SCALE, which until now played as dead air.
        ReckoningRun.Outcome.WON -> CrystalFightLines.farewell
        // A crystal that outlives you has nothing to thank you for.
        ReckoningRun.Outcome.LOST -> null
        ReckoningRun.Outcome.RUNNING -> lineAt(
            run.opening.stage, run.opening.elapsed,
            run.director.phaseIndex, run.director.inLull, run.director.lullElapsed
        )
    }

    /**
     * The director's phase index as a [CrystalPhase].
     *
     * Coerced rather than trusted. The director wraps its index at the end of a lap and the
     * five patterns line up one-to-one with P1..P5 today, but a sixth pattern would other-
     * wise reach this as an ArrayIndexOutOfBounds in the middle of the ending.
     */
    fun phaseFor(index: Int): CrystalPhase =
        CrystalPhase.entries[index.coerceIn(0, CrystalPhase.entries.size - 1)]
}
