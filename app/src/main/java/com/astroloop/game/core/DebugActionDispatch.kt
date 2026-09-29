package com.astroloop.game.core

import com.astroloop.game.cabinet.CabinetDebugIntent
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.PilotDefinitions

/**
 * The debug menu's action dispatch — persistence-backed actions plus the run-only actions that
 * only a live [GameSurfaceView] (or eventually `HangarSurfaceView`) can perform.
 *
 * Lifted out of `GameSurfaceView.onTouchEvent` so the hangar can host the same debug menu without
 * owning a run. Seven of these actions genuinely need a live run; [DebugActionHost] names them so
 * a caller without one — the hangar — can dim their rows honestly instead of drawing live buttons
 * that no-op.
 *
 * `EVOLVE:` and `RECKONING_PHASE_` are a separate concern (weapon evolution testing and
 * reckoning phase jumps, not the debug menu's game-state/persistence dispatch) and are not
 * handled here — they stay inline in `GameSurfaceView.onTouchEvent`.
 */
interface DebugActionHost {
    /** False in the hangar. Guards the actions that need a live run. */
    fun isInRun(): Boolean
    /**
     * Hand control back to the hangar. Does **not** close the menu — every caller pairs this with
     * [closeMenu] itself, and the two are separate because a host may need to do one without the
     * other. Corrected from the brief's own sample, which claimed this closed the menu.
     */
    fun returnToHangar()
    /** Close the debug menu overlay. */
    fun closeMenu()
    /** Run-only: toggle a weapon or passive, then recalculate. No-op when [isInRun] is false. */
    fun toggleLoadout(action: String)
    /** Run-only: BOSS_NOW, INSTANT_DEATH, PLAY_DESERT, PLAY_DESERT_P2… No-op when not in a run. */
    fun runOnlyAction(action: String)
    /** Telemetry log, which only GameSurfaceView owns. */
    fun clearTelemetry()
}

object DebugActionDispatch {

    /** The seven actions that need a live run. Pure, so the classification is testable. */
    fun isRunOnly(action: String): Boolean = action in RUN_ONLY

    private val RUN_ONLY = setOf(
        // Need a live run, permanently.
        "WEAPON_TOGGLE", "PASSIVE_TOGGLE", "BOSS_NOW", "INSTANT_DEATH",
        "PLAY_DESERT", "PLAY_DESERT_P2", "DESERT_CRYSTAL"
    )

    /** Returns true iff [action] was recognised and handled. */
    fun handle(action: String, state: GameState, p: PersistenceManager, host: DebugActionHost): Boolean {
        if (isRunOnly(action) && !host.isInRun()) return true
        when (action) {
            "RESET_SMALL" -> {
                p.resetAllProgress()
                p.setYen(50)
                host.clearTelemetry()
                host.closeMenu()
                host.returnToHangar()
            }
            "RESET_BIG" -> {
                p.resetAllProgress()
                p.setYen(10_000_000)
                // resetAllProgress re-arms the first-launch intro, and the hangar's
                // first-launch branch opens by zeroing yen (HangarSurfaceView:297). Because
                // surfaceCreated is deferred it lands *after* the money is banked and wiped
                // it every time — a rich reset put you in the bar with 0. Marking the intro
                // done keeps the yen, and is right on its own terms: this reset exists to jump
                // to a late-game state, which is not something to sit through an opening for.
                p.setFirstLaunchComplete()
                p.setIntroDone()
                host.clearTelemetry()
                p.unlockAllShipsAndPilots()
                for (id in listOf("health", "shields", "speed", "damage", "crit", "yen_bonus", "salvage", "magnet")) {
                    p.setUpgradeLevel(id, 5)
                }
                host.closeMenu()
                host.returnToHangar()
            }
            "WEAPON_TOGGLE" -> host.toggleLoadout(action)
            "PASSIVE_TOGGLE" -> host.toggleLoadout(action)
            "INSTANT_DEATH" -> host.runOnlyAction(action)
            "BOSS_NOW" -> host.runOnlyAction(action)
            "PLAY_DESERT" -> host.runOnlyAction(action)
            "PLAY_DESERT_P2" -> host.runOnlyAction(action)
            "DESERT_CRYSTAL" -> host.runOnlyAction(action)
            "SET_CORRUPT" -> {
                p.setStoryStageCode(StoryStage.CORRUPTION.code)
                host.closeMenu()
                host.returnToHangar()
            }
            "KILL_PILOT" -> {
                if (StoryStateManager.stage(p) == StoryStage.NORMAL) {
                    p.setStoryStageCode(StoryStage.CORRUPTION.code)
                }
                val deadPilots = p.getDeadPilots()
                val nextAlive = PilotDefinitions.pilots
                    .filter { it.id != "pilot_astro" && !deadPilots.contains(it.id) }
                    .firstOrNull()
                if (nextAlive != null) {
                    p.addDeadPilot(nextAlive.id)
                    val shipId = StoryStateManager.getShipForPilot(nextAlive.id)
                    if (shipId != null) p.addDeadShip(shipId)
                }
                DebugMirror.populate(state, p)
            }
            "KILL_ALL" -> {
                if (StoryStateManager.stage(p) == StoryStage.NORMAL) {
                    p.setStoryStageCode(StoryStage.CORRUPTION.code)
                }
                for (pilot in PilotDefinitions.pilots) {
                    if (pilot.id == "pilot_astro") continue
                    p.addDeadPilot(pilot.id)
                    val shipId = StoryStateManager.getShipForPilot(pilot.id)
                    if (shipId != null) p.addDeadShip(shipId)
                }
                if (StoryStateManager.shouldUnlockCrystal(p)) {
                    p.setCrystalUnlocked(true)
                }
                DebugMirror.populate(state, p)
            }
            "BUY_CRYSTAL" -> {
                if (StoryStateManager.stage(p) == StoryStage.NORMAL) {
                    p.setStoryStageCode(StoryStage.CORRUPTION.code)
                }
                p.setCrystalUnlocked(true)
                p.setCrystalPurchased(true)
                DebugMirror.populate(state, p)
            }
            "RESET_STORY" -> {
                p.setStoryStageCode(StoryStage.NORMAL.code)
                p.setStoryLoop(1)
                p.setCrystalUnlocked(false)
                p.setCrystalPurchased(false)
                p.clearDeadPilotsAndShips()
                DebugMirror.populate(state, p)
            }
            "GRANT_BANDANAS" -> {
                for (pilot in PilotDefinitions.pilots) p.addBandana(pilot.id)
                DebugMirror.populate(state, p)
            }
            "CLEAR_BANDANAS" -> {
                p.clearAllBandanas()
                p.clearPendingBandanaPilot()
                p.setCrystalReleased(false)
                DebugMirror.populate(state, p)
            }
            "ARCADE_OPEN" -> {
                // onGameOver(0, false) is the established route from here to the
                // hangar — RESET_SMALL, RESET_BIG and SET_CORRUPT all take it. The
                // intent is picked up by HangarSurfaceView.resetForReturn.
                host.closeMenu()
                CabinetDebugIntent.request(CabinetDebugIntent.Action.OPEN)
                host.returnToHangar()
            }
            "ARCADE_PLAY" -> {
                host.closeMenu()
                // A run costs a credit, and the point of this button is not to be
                // blocked by the coin. Bank one so the shell's spendCredit succeeds.
                p.addCabinetCredit()
                CabinetDebugIntent.request(CabinetDebugIntent.Action.PLAY)
                host.returnToHangar()
            }
            "ARCADE_CREDITS" -> {
                repeat(10) { p.addCabinetCredit() }
                DebugMirror.populate(state, p)
            }
            "ARCADE_CLEAR_PILOT" -> {
                val pilotId = p.getSelectedPilotId()
                p.setArcadeScoreIfBetter(pilotId, PersistenceManager.ARCADE_CLEAR_SCORE)
                DebugMirror.populate(state, p)
            }
            "ARCADE_CLEAR_ALL" -> {
                // The twin of GRANT_BANDANAS: this is what opens the gate in stage 3.
                for (pilot in PilotDefinitions.pilots) {
                    p.setArcadeScoreIfBetter(pilot.id, PersistenceManager.ARCADE_CLEAR_SCORE)
                }
                DebugMirror.populate(state, p)
            }
            "ARCADE_RESET" -> {
                p.resetArcade()
                DebugMirror.populate(state, p)
            }
            "TOGGLE_ASTRO_LOOP" -> {
                if (StoryStateManager.isAstroLoop(p)) {
                    p.setStoryStageCode(StoryStage.NORMAL.code)
                } else {
                    p.setStoryStageCode(StoryStage.ASTRO_LOOP.code)
                }
                state.astroLoopMode = StoryStateManager.isAstroLoop(p)
                DebugMirror.populate(state, p)
            }
            "SET_DESERT_FLAGS" -> {
                p.setDesertCompleted()
                p.setDesertGoodEnding()
                DebugMirror.populate(state, p)
            }
            "CLR_DESERT" -> {
                p.clearDesertCompleted()
                p.clearDesertGoodEnding()
                DebugMirror.populate(state, p)
            }
            "SET_LOOP_1" -> {
                p.setStoryLoop(1)
                state.storyLoop = 1
                DebugMirror.populate(state, p)
            }
            "SET_LOOP_2" -> {
                p.setStoryLoop(2)
                state.storyLoop = 2
                DebugMirror.populate(state, p)
            }
            "SET_LOOP_3" -> {
                p.setStoryLoop(3)
                state.storyLoop = 3
                DebugMirror.populate(state, p)
            }
            else -> return false
        }
        return true
    }
}
