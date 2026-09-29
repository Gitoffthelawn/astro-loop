package com.astroloop.game.cabinet

import kotlin.random.Random

enum class CabinetScreen { MENU, SCORES, PLAY, PAUSE, OVER }

/**
 * The cabinet's front end: five screens and the rules for moving between them.
 *
 * Pure — persistence and the coin box are injected as lambdas so this is testable
 * without Android. The shell owns the [sim] and decides when it may advance.
 *
 * The one rule worth stating plainly: **AGAIN is not a continue.** It costs a credit
 * and starts a fresh run from zero, which is what keeps every 999 a single unbroken
 * life and stops the gate being purchasable.
 */
class CabinetShell(
    val m: CabinetMetrics,
    private val rng: Random,
    private val spendCredit: () -> Boolean,
    private val recordScore: (Int) -> Unit,
    /**
     * Whether PLAY should begin the reckoning rather than a free-play run — the whole score
     * table cleared and the crystal not yet released. A lambda, like its two siblings, so the
     * shell stays pure and testable without a Context. Evaluated fresh every time [beginRun]
     * runs rather than cached — the debug menu can clear the twelfth pilot without closing the
     * cabinet, and the gate must be open on the very next PLAY when that happens.
     */
    private val shouldStartReckoning: () -> Boolean
) {
    var screen: CabinetScreen = CabinetScreen.MENU
        private set

    val sim = CabinetSim(m, rng)

    /**
     * The demo the machine plays to itself, behind the menu and the board. Shared with
     * the store page's bezel — same class, same autopilot, same restart beat.
     */
    private val attract = CabinetAttract(m)

    /** The demo's sim, for the renderer and the tests. */
    val attractSim get() = attract.sim

    /** The attract demo's clock. Advances on MENU and SCORES only. */
    var attractElapsed: Float = 0f
        private set

    /** Set when the player asks to leave; the host closes the overlay and clears it. */
    var requestExit: Boolean = false
        private set

    private var scoreRecorded = false

    /** The ending in progress, or null in ordinary free play. */
    var reckoning: ReckoningRun? = null
        private set

    /** How long the crystal has been holding the paused screen. A design decision. */
    var pauseDenial: Float = 0f
        private set

    /** How many times the player has tried to pause this run — picks which line answers. */
    var pauseAttempts: Int = 0
        private set

    val isReckoning: Boolean get() = reckoning != null

    /**
     * Whether the crystal refuses the player a pause — **the genuine ending only**.
     *
     * A design decision is about the ending: there is no pausing and no quitting it, "if the player
     * wants out they either beat it or die." A replay from the menu's ??? entry is not the
     * ending. It writes nothing but a record, it is entered deliberately from a menu, and it is
     * a fight the player has already won — holding them in it enforces a rule whose reason has
     * already been spent.
     *
     * **Quitting a replay is allowed too — owner, 2026-08-31.** Asked explicitly, because the
     * pause and the quit are the same predicate and only the pause had been requested. A replay
     * exits to the menu it was launched from.
     *
     * Every pause-related branch reads this rather than [isReckoning], so the denial timer, the
     * attempt counter that picks the crystal's answering line, the QUIT button and the refusal
     * behind it can never disagree about which kind of run this is.
     */
    val refusesPause: Boolean get() = reckoning?.isReplay == false

    /**
     * Begin the ending. Does not itself touch the coin economy — the debug jump calls this
     * directly and must stay free, while [beginRun] calls it too but only after it has
     * already spent a credit. A design decision retired the free retry; the charge lives in that
     * ordering, not here.
     *
     * @param startPhase 0 plays the authored opening; 1..5 drop into that pattern.
     * @param startLap which lap of the director's escalation to land on (default 1) —
     *   only meaningful alongside [startPhase] 1..5. The debug jump exists to tune a
     *   pattern at its authored difficulty, but a later lap needs tuning too, and that
     *   was reachable only by surviving a full 75-second lap first without this.
     * @param isReplay carried straight onto the [ReckoningRun] — see its own doc. Default
     *   false so the debug jump and the real gate both still build an ordinary ending.
     */
    fun startReckoning(startPhase: Int, startLap: Int = 1, isReplay: Boolean = false) {
        val run = ReckoningRun(m, startPhase, startLap, isReplay)
        sim.start()
        run.begin(sim)
        reckoning = run
        scoreRecorded = false
        // A fresh attempt must not inherit a pending exit from whatever ended the last one.
        requestExit = false
        screen = CabinetScreen.PLAY
    }

    /**
     * The MENU's fourth entry: replay a beaten ending. Only reachable from MENU, and priced
     * like every other run — a design decision retired the free finale for the real ending, and
     * this is not a special case of it.
     *
     * @return false if not on MENU or there was no credit to spend; nothing changes either way.
     */
    fun onReplay(): Boolean {
        if (screen != CabinetScreen.MENU) return false
        if (!spendCredit()) return false
        startReckoning(0, isReplay = true)
        return true
    }

    fun update(dt: Float, inputX: Float, inputY: Float, hasInput: Boolean) {
        when (screen) {
            CabinetScreen.MENU, CabinetScreen.SCORES -> {
                attractElapsed += dt
                attract.update(dt)
            }
            CabinetScreen.PLAY -> {
                sim.update(dt, inputX, inputY, hasInput)
                val run = reckoning
                run?.update(dt, sim)
                if (sim.over) {
                    // A WIN never reaches the OVER screen. That screen says GAME OVER over
                    // a three-digit score, which would tell a victorious player they lost
                    // under a number the reckoning does not even keep. Hold on PLAY while
                    // the shatter finishes - gated on the wreck being GONE rather than on
                    // a timer, the same dt-proof condition CabinetAttract uses - then ask
                    // the host to leave, which is what finishReckoningWin already does.
                    if (run != null && run.outcome == ReckoningRun.Outcome.WON) {
                        if (sim.debris.isEmpty()) requestExit = true
                    } else {
                        // A design decision: the ending never writes to the per-pilot table the
                        // stage 3 gate reads. "Cannot" beats "happens not to".
                        if (!isReckoning && !scoreRecorded) {
                            recordScore(sim.score); scoreRecorded = true
                        }
                        screen = CabinetScreen.OVER
                    }
                }
            }
            // OVER still ticks. The run is finished, but the ship's four hull edges are
            // mid-flight when it ends — freezing the sim here left them at their spawn
            // vertices at full opacity, which is an intact-looking ship under GAME OVER
            // and the no-vanishing rule breached by another name. It also froze
            // waveBanner, so dying inside a WAVE N announcement pinned it over the
            // score. No input is passed: the pilot is dead and the gun is silent.
            CabinetScreen.OVER -> sim.update(dt, 0f, 0f, false)
            // PAUSE does not. Holding the clock is the entire point of pause — except in
            // the reckoning, where the crystal does not grant one. A design decision: it takes the
            // menu, answers it, and hands the fight back. The sim still does not advance
            // while it is up; what advances is only the denial's own clock, so the pause is
            // real for as long as it lasts and the player loses nothing but the beat.
            CabinetScreen.PAUSE -> {
                if (refusesPause) {
                    pauseDenial += dt
                    if (pauseDenial >= PAUSE_DENIAL_SECONDS) {
                        screen = CabinetScreen.PLAY
                        pauseDenial = 0f
                    }
                }
            }
        }
    }

    /** @return false if there was no credit to spend; nothing changes in that case. */
    fun onPlay(): Boolean {
        if (screen != CabinetScreen.MENU) return false
        return beginRun()
    }

    /** @return false if there was no credit to spend. */
    fun onAgain(): Boolean {
        if (screen != CabinetScreen.OVER) return false
        return beginRun()
    }

    private fun beginRun(): Boolean {
        // Spend FIRST, then decide which run this is. A design decision retired the free finale,
        // so both kinds cost a credit now and there is one economy in the machine.
        //
        // The order is load-bearing and the tempting edit is a bug: deleting the isReckoning
        // branch rather than moving it below the spend would make AGAIN? charge a credit and
        // start a FREE-PLAY run underneath a reckoning that has not ended.
        //
        // isReckoning here can only mean AGAIN? pressed from OVER during the fight. Both exits
        // (onBack, onQuit) null out `reckoning` and request exit before a fresh PLAY is ever
        // possible, so there is no path back to MENU with a live fight — a fresh PLAY is
        // always the lambda's decision, never this branch's.
        if (!spendCredit()) return false
        if (isReckoning || shouldStartReckoning()) {
            // Carry the replay flag across AGAIN?. reckoning is still the run that just
            // ended (screen is OVER, not yet replaced) when isReckoning is what brought us
            // here, so reckoning?.isReplay reads the run being retried. When it was the real
            // gate that brought us here instead, reckoning is null and this is false — a
            // fresh reckoning is never mistaken for a replay. A fresh ReckoningRun here
            // without this would come back believing it is the real ending and, on a win,
            // write crystal_released and re-fire the one-shot bar chatter.
            startReckoning(0, isReplay = reckoning?.isReplay == true)
            return true
        }
        sim.start()
        scoreRecorded = false
        screen = CabinetScreen.PLAY
        return true
    }

    /**
     * The screen under an open cabinet changed size — a rotation, in practice. Every screen
     * lays out from [m] each frame, so resizing it is all the menus need; the two fields'
     * contents are moved proportionally so nothing is left outside the new bounds.
     */
    fun resize(newWidth: Float, newHeight: Float) {
        if (newWidth <= 0f || newHeight <= 0f) return
        if (newWidth == m.width && newHeight == m.height) return
        val sx = newWidth / m.width
        val sy = newHeight / m.height
        m.resize(newWidth, newHeight)
        sim.rescale(sx, sy)
        attract.sim.rescale(sx, sy)
    }

    fun onScores() { if (screen == CabinetScreen.MENU) screen = CabinetScreen.SCORES }

    fun onBack() {
        // From OVER during the reckoning, "back" means out of the machine, not into its
        // attract menu — the same destination decision rejected for QUIT.
        if (screen == CabinetScreen.OVER && isReckoning) {
            reckoning = null
            requestExit = true
            return
        }
        // PLAY delegates to onPause() rather than setting the screen itself. It used to
        // assign PAUSE directly, which skipped the only place pauseAttempts++ and
        // pauseDenial = 0f happen — so the back button, which is the REFLEX way to pause,
        // never advanced the crystal's answer and always drew line 0, "Not yet."
        if (screen == CabinetScreen.PLAY) { onPause(); return }
        screen = when (screen) {
            CabinetScreen.SCORES, CabinetScreen.OVER -> CabinetScreen.MENU
            else -> screen
        }
    }

    fun onPause() {
        if (screen != CabinetScreen.PLAY) return
        screen = CabinetScreen.PAUSE
        pauseDenial = 0f
        if (refusesPause) pauseAttempts++
    }
    fun onResume() {
        if (screen == CabinetScreen.PAUSE) { screen = CabinetScreen.PLAY; pauseDenial = 0f }
    }

    /**
     * Leave a paused run.
     *
     * In free play that means the cabinet's own menu. **In the reckoning it means the
     * hangar** — the attract demo is no place to drop somebody out of an
     * ending. The ending is not consumed either way: `crystal_released` is written only
     * on a win, so losing already permits a retry and quitting matches it.
     */
    fun onQuit() {
        if (screen != CabinetScreen.PAUSE) return
        // THERE IS NO QUITTING THE RECKONING. A later decision amends an earlier one: it used to
        // drop you back in the hangar on the grounds that the attract demo was no place to
        // leave somebody out of an ending. The owner's answer is that there is no leaving
        // at all — "if the player wants out they either beat it or die."
        //
        // Not merely hidden: the button is gone from the paused screen, and this refuses
        // even if something else reaches it. Losing is still an exit, and still cheap — a
        // loss does not consume the ending, so AGAIN? and the walk to the bar both survive.
        //
        // A replay is exempt: see [refusesPause]. It was entered from the menu, so the menu is
        // where quitting it belongs.
        if (refusesPause) return
        screen = CabinetScreen.MENU
    }
    fun onExit() { if (screen == CabinetScreen.MENU) requestExit = true }
    fun clearExitRequest() { requestExit = false }

    companion object {
        /**
         * How long the crystal holds the paused screen before handing the fight back.
         *
         * Long enough that the pause visibly HAPPENED — a menu that never appears reads as
         * a dropped input, not as a refusal — and short enough that it cannot be used as
         * rest. [PAUSE_HELD_SECONDS] of it is the ordinary menu; the remainder is the
         * crystal's answer.
         */
        const val PAUSE_DENIAL_SECONDS = 1.6f

        /** How long the ordinary paused screen stands before the crystal takes it. */
        const val PAUSE_HELD_SECONDS = 0.7f

        // The attract loop lives in CabinetAttract, which the store page's bezel shares.
        // These are aliases so the shell's own callers and tests keep one name for it.
        const val ATTRACT_SEED = CabinetAttract.ATTRACT_SEED
        const val ATTRACT_RESTART_HOLD = CabinetAttract.ATTRACT_RESTART_HOLD
    }
}
