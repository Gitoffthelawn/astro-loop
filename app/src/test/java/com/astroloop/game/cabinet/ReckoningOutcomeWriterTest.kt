package com.astroloop.game.cabinet

import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReckoningOutcomeWriterTest {

    private lateinit var p: PersistenceManager

    @Before
    fun setup() {
        p = PersistenceManager(ApplicationProvider.getApplicationContext())
        p.resetAllProgress()
    }

    @Test
    fun theFirstWinRecordsItsTimeAsTheReleaseRecord() {
        // The very first completion sets the record. There is nothing to beat yet, so the time
        // you took the first time through IS the time to beat — a board that stays empty until
        // you replay the fight is telling the player their first win did not count.
        assertEquals("no record before anything is beaten", 0, p.getBestReleaseSeconds())

        ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.WON, isReplay = false, seconds = 74, p)

        assertTrue(p.hasReleaseRecord())
        assertEquals(74, p.getBestReleaseSeconds())
    }

    @Test
    fun aWinConsumesTheEndingAndArmsTheWinChatter() {
        assertTrue(ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.WON, isReplay = false, seconds = 90, p))
        assertTrue(p.isCrystalReleased())
        assertTrue(p.isReckoningJustWon())
    }

    @Test
    fun aLossResolvesAndLeavesTheGateOpenAndWritesNothing() {
        assertTrue("a loss is still a finished outcome and must latch",
            ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.LOST, isReplay = false, seconds = 90, p))
        assertFalse("losing must not consume the ending", p.isCrystalReleased())
        assertFalse("a loss returns you to the bar in silence — no flag is armed",
            p.isReckoningJustWon())
    }

    @Test
    fun aWinAfterALossIsAnOrdinaryWin() {
        // The likeliest first completion there is: lose, retry, win.
        //
        // This used to assert a SUPERSEDE property, and it was the final review's one Important:
        // a win had to actively clear a pending loss flag, or ChatSystem — which checks the win
        // first and returns — would leave the loss standing to fire on some LATER bar return,
        // handing the player the retry script for a fight they had already ended.
        //
        // A design decision deleted the losing flag outright, so a loss now leaves nothing behind for a
        // win to supersede. The property was NOT dropped: its precondition was. What is asserted
        // here is the surviving half — that a preceding loss cannot spoil the win that follows it.
        ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.LOST, isReplay = false, seconds = 90, p)
        assertFalse("precondition: the loss wrote nothing", p.isCrystalReleased())

        ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.WON, isReplay = false, seconds = 90, p)

        assertTrue(p.isReckoningJustWon())
        assertTrue(p.isCrystalReleased())
    }

    @Test
    fun aRunningOutcomeWritesNothingAndDoesNotResolve() {
        assertFalse(
            "RUNNING must not resolve — latching it would mark the run finished early",
            ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.RUNNING, isReplay = false, seconds = 90, p)
        )
        assertFalse(p.isCrystalReleased())
        assertFalse(p.isReckoningJustWon())
    }

    @Test
    fun aReplayWinWritesNothingToTheStory() {
        p.setCrystalReleased(true)   // the ending was already earned
        assertTrue(ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.WON, isReplay = true, seconds = 90, p))
        assertFalse("a replay must not re-fire the post-win chatter", p.isReckoningJustWon())
    }

    @Test
    fun aReplayLossWritesNothingToTheStory() {
        // A real loss already writes nothing, so this is not a replay-guard test any more — it
        // pins that the LOST branch stays inert when it stops consulting isReplay at all.
        p.setCrystalReleased(true)
        assertTrue(ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.LOST, isReplay = true, seconds = 90, p))
        assertTrue("and must not un-consume the ending", p.isCrystalReleased())
        assertFalse(p.isReckoningJustWon())
    }

    @Test
    fun aReplayLossDoesNotClearAGenuinePendingWin() {
        ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.WON, isReplay = false, seconds = 90, p)
        ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.LOST, isReplay = true, seconds = 90, p)
        assertTrue("a replay must not disturb a real result", p.isReckoningJustWon())
    }
}
