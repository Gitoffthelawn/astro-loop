package com.astroloop.game.cabinet

import com.astroloop.game.data.PersistenceManager

/**
 * What a finished reckoning writes to the save.
 *
 * **Only a WIN writes anything.** A loss leaves the save untouched and returns the player to the
 * bar in silence. It still *resolves* — [apply] returns
 * true so the shell latches the run and stops re-applying it — it simply has nothing to record.
 *
 * **Extracted so it can be tested.** These writes used to live inline in `HangarSurfaceView`,
 * where nothing can reach them — and that is precisely where this feature's escaped defects have
 * landed twice: a unit test drives a unit, the host composes them, and the composition is what
 * breaks. The final review found a third of exactly that shape here, and the covering test could
 * not have caught it because it set only one of the two flags then in play. That defect is now
 * gone by construction rather than by guard: there is only one flag left to leave standing.
 *
 * Pure apart from the [PersistenceManager] it is handed, so a test can drive a real one under
 * Robolectric and assert what a whole outcome actually leaves behind.
 */
object ReckoningOutcomeWriter {

    /**
     * Apply [outcome]'s consequences.
     *
     * @param isReplay true for a run started from the menu's replay entry. A replay resolves for
     *   the shell's own screen handling and writes **nothing** to the story — not the release
     *   flag, not the one-shot post-win conversation flag. Only the WON branch consults it,
     *   because it is the only branch that writes.
     * @return true iff the run is resolved and the caller should latch it. `RUNNING` returns
     *   false, so a run is never marked resolved before it finishes.
     */
    fun apply(
        outcome: ReckoningRun.Outcome,
        isReplay: Boolean,
        seconds: Int,
        p: PersistenceManager
    ): Boolean =
        when (outcome) {
            ReckoningRun.Outcome.WON -> {
                // The record is the ONE thing a replay may write. Everything below is
                // story state and fires once; this has to stay improvable or the ??? entry
                // could never beat the time it set the first time through.
                p.setBestReleaseSecondsIfBetter(seconds)
                if (!isReplay) {
                    p.setCrystalReleased(true)
                    p.setReckoningJustWon(true)
                }
                true
            }
            ReckoningRun.Outcome.LOST -> {
                // A loss writes NOTHING, on either a real run or a replay, which is why this
                // branch does not consult isReplay: there is no write for the replay guard to
                // suppress. Two things follow, and both are load-bearing.
                //
                // Losing does NOT consume the ending — crystal_released stays false, so the gate
                // is still open and AGAIN? is a real offer.
                //
                // And it does not disturb a pending win: a replayed loss after a genuine win must
                // leave reckoning_just_won standing, which an empty branch gives for free.
                //
                // It still RESOLVES. A loss is a finished outcome, so the shell latches it and
                // stops re-applying the run every frame.
                true
            }
            ReckoningRun.Outcome.RUNNING -> false
        }
}
