package com.astroloop.game.core

import com.astroloop.game.data.PersistenceManager

/**
 * The debug menu's read-only view of persistence.
 *
 * `GameState`'s eight `debug*` fields are a mirror, not run state — their own declaration comment
 * says "updated each frame from persistence". Lifting the population out of `GameSurfaceView` is
 * what lets `HangarSurfaceView` host the same menu without owning a run.
 *
 * **One mirror, two callers, deliberately.** Two independently maintained copies is how one drifts,
 * and the polish pass already spent a review round on exactly that shape with the cutout inset.
 */
object DebugMirror {
    fun populate(state: GameState, p: PersistenceManager) {
        state.debugStoryPhase = p.getStoryStageCode()
        state.debugDeadPilotCount = p.getDeadPilots().size
        state.debugCrystalUnlocked = p.isCrystalUnlocked()
        state.debugArcCompleted = StoryStateManager.hasLoopedBefore(p)
        state.debugDesertCompleted = p.isDesertCompleted()
        state.debugDesertGoodEnding = p.hasDesertGoodEnding()
        state.debugStoryLoop = p.getStoryLoop()
        state.debugAstroLoopMode = StoryStateManager.isAstroLoop(p)
    }
}
