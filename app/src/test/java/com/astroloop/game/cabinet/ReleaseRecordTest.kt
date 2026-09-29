package com.astroloop.game.cabinet

import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one number the reckoning keeps — a design decision.
 *
 * Not a score. The fight stopped paying points because shooting the opening's seeded rocks
 * had nothing to do with the crystal; a TIME is a record of the fight itself, which is the
 * opposite claim. It lives on the cabinet and nowhere else: the bar never mentions it and
 * TB-26 never mentions it, the same separation decision draws when it forbids the pilot
 * from reporting on the fight at all.
 */
@RunWith(RobolectricTestRunner::class)
class ReleaseRecordTest {

    private lateinit var p: PersistenceManager

    @Before fun setUp() {
        p = PersistenceManager(ApplicationProvider.getApplicationContext())
        p.resetAllProgress()
    }

    @Test fun thereIsNoRecordUntilSomethingIsReleased() {
        assertEquals(0, p.getBestReleaseSeconds())
        assertFalse(p.hasReleaseRecord())
    }

    @Test fun aWinRecordsItsTime() {
        assertTrue(p.setBestReleaseSecondsIfBetter(87))
        assertEquals(87, p.getBestReleaseSeconds())
        assertTrue(p.hasReleaseRecord())
    }

    @Test fun onlyAFasterReleaseReplacesIt() {
        p.setBestReleaseSecondsIfBetter(87)
        assertFalse("slower must not overwrite", p.setBestReleaseSecondsIfBetter(120))
        assertEquals(87, p.getBestReleaseSeconds())
        assertTrue("faster must", p.setBestReleaseSecondsIfBetter(64))
        assertEquals(64, p.getBestReleaseSeconds())
    }

    @Test fun aNonsenseTimeIsRefused() {
        // The clock is a float turned into whole seconds; zero or negative means something
        // upstream went wrong, and writing it would make an unbeatable record.
        assertFalse(p.setBestReleaseSecondsIfBetter(0))
        assertFalse(p.setBestReleaseSecondsIfBetter(-3))
        assertFalse(p.hasReleaseRecord())
    }

    @Test fun aResetClearsIt() {
        p.setBestReleaseSecondsIfBetter(87)
        p.resetAllProgress()
        assertFalse(p.hasReleaseRecord())
    }

    // ── what the outcome writer does with it ──────────────────────────────

    @Test fun winningWritesTheTime() {
        ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.WON, isReplay = false, seconds = 91, p = p)
        assertEquals(91, p.getBestReleaseSeconds())
    }

    @Test fun aReplayStillSetsTheRecord() {
        // The whole point of the ??? entry now: it writes nothing to the STORY, but the
        // record has to be improvable or it could only ever be set once.
        ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.WON, isReplay = false, seconds = 91, p = p)
        ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.WON, isReplay = true, seconds = 70, p = p)
        assertEquals(70, p.getBestReleaseSeconds())
        assertTrue("a replay must still not re-fire the story", p.isCrystalReleased())
    }

    @Test fun losingRecordsNothing() {
        ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.LOST, isReplay = false, seconds = 40, p = p)
        assertFalse(p.hasReleaseRecord())
    }

    // ── the clock itself ──────────────────────────────────────────────────

    @Test fun theClockMeasuresTheFightNotTheOpening() {
        val m = CabinetMetrics(1080f, 2400f)
        val s = CabinetSim(m, kotlin.random.Random(13)).also { it.start() }
        val r = ReckoningRun(m)
        r.begin(s)
        val dt = 1f / 60f
        // Through the whole authored opening, up to the moment the crystal touches down.
        var guard = 0
        while (!r.justLanded && guard < 60 * 30) { r.update(dt, s); s.update(dt, 0f, 0f, false); guard++ }
        assertTrue("the crystal never landed", r.justLanded)
        assertEquals("the opening must not be on the clock", 0f, r.fightSeconds, 0.05f)

        repeat(60 * 3) { r.update(dt, s); s.update(dt, 0f, 0f, false) }
        assertEquals("three seconds of fight", 3f, r.fightSeconds, 0.1f)
    }

    @Test fun aDebugJumpCannotSetARecord() {
        // startAtPhase seeds the director's clock to reach a chosen lap, so on a jump it
        // never measured anything. Reading 0 makes the writer refuse it with no call site
        // having to remember why.
        val m = CabinetMetrics(1080f, 2400f)
        val s = CabinetSim(m, kotlin.random.Random(13)).also { it.start() }
        val r = ReckoningRun(m, startPhase = 4, startLap = 2)
        r.begin(s)
        repeat(120) { r.update(1f / 60f, s); s.update(1f / 60f, 0f, 0f, false) }
        assertEquals(0f, r.fightSeconds, 1e-6f)
        ReckoningOutcomeWriter.apply(ReckoningRun.Outcome.WON, false, r.fightSeconds.toInt(), p)
        assertFalse("a jumped fight must not own the record", p.hasReleaseRecord())
    }
}
