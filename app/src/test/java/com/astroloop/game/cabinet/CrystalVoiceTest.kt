package com.astroloop.game.cabinet

import com.astroloop.game.data.CrystalFightLines
import com.astroloop.game.system.CrystalPhase
import org.junit.Assert.*
import org.junit.Test

class CrystalVoiceTest {

    private val m = CabinetMetrics(1080f, 2400f)

    private val play = ReckoningOpening.Stage.PLAY
    private val forming = ReckoningOpening.Stage.ARRIVAL
    private val fight = ReckoningOpening.Stage.FIGHT

    private fun atPlay(t: Float) = CrystalVoice.lineAt(play, t, 0, false, 0f)
    /** [t] is time into the lull at the head of pattern [i]. */
    private fun inPhase(i: Int, t: Float, lull: Boolean = true) =
        CrystalVoice.lineAt(fight, 0f, i, lull, t)

    @Test fun theOpeningStartsInSilence() {
        // The authored twelve seconds must read as an ordinary game before it turns. A
        // line on the first frame announces the fight the opening exists to hide.
        assertNull(atPlay(0f))
        assertNull(atPlay(CrystalVoice.BEAT_ONE_AT - 0.1f))
    }

    @Test fun theFirstTwoBeatsPlayOverTheOrdinaryGame() {
        assertEquals(CrystalFightLines.opening[0], atPlay(CrystalVoice.BEAT_ONE_AT))
        assertEquals(CrystalFightLines.opening[0], atPlay(CrystalVoice.BEAT_ONE_AT + 1f))
        assertEquals(CrystalFightLines.opening[1], atPlay(CrystalVoice.BEAT_TWO_AT))
        assertEquals(CrystalFightLines.opening[1], atPlay(CrystalVoice.BEAT_TWO_AT + 1f))
    }

    @Test fun theBeatsAreSeparatedBySilenceRatherThanRunningTogether() {
        // The pauses are what make the next line arrive. Without this the three beats read
        // as one paragraph.
        assertNull(atPlay(CrystalVoice.BEAT_ONE_AT + CrystalVoice.BEAT_HOLD))
        assertNull(atPlay(CrystalVoice.BEAT_TWO_AT - 0.1f))
        assertNull(atPlay(CrystalVoice.BEAT_TWO_AT + CrystalVoice.BEAT_HOLD))
    }

    @Test fun beatThreeHoldsForTheWholeEntrance() {
        // Not on a timer: CRYSTALLISING ends when the field is eaten, not at a fixed time,
        // so "So I came here" is on screen exactly while the rocks implode into it.
        assertEquals(CrystalFightLines.opening[2], CrystalVoice.lineAt(forming, 0f, 0, false, 0f))
        assertEquals(CrystalFightLines.opening[2], CrystalVoice.lineAt(forming, 3.4f, 0, false, 0f))
    }

    @Test fun everyPhaseOpensWithItsLure() {
        for (i in 0 until CrystalPhase.entries.size) {
            val expected = CrystalFightLines.taunt(CrystalVoice.phaseFor(i)).first
            assertEquals("phase $i", expected, inPhase(i, 0f))
        }
    }

    @Test fun theSecondHalfLandsAfterTheFirstHasBeenSittingThere() {
        val second = CrystalFightLines.taunt(CrystalPhase.P2).second
        assertEquals(CrystalFightLines.taunt(CrystalPhase.P2).first, inPhase(1, 0f))
        assertEquals(second, inPhase(1, CrystalVoice.PART_TWO_AT))
    }

    @Test fun theCrystalOnlySpeaksIntoTheSeamBetweenPatterns() {
        // A design decision. It used to wait for LOCAL room — nothing within two spacing floors —
        // and that was measured as unworkable: against a player holding engagement range
        // there is room for 1.7% of PULSE, 1.4% of VOLLEY and 1.3% of SHATTER, in stretches
        // that leave 29 unbroken seconds of silence. Three of the five patterns never spoke.
        //
        // The quiet is now MADE rather than found: every pattern opens with a beat in which
        // nothing is emitted, and that is the only place a line appears.
        assertNull("nothing to say once the pattern is firing", inPhase(0, 0f, lull = false))
        assertNull(inPhase(0, CrystalVoice.PART_TWO_AT, lull = false))
        assertNotNull("and it speaks into the seam", inPhase(0, 0f, lull = true))
    }

    @Test fun bothHalvesOfALureFitInsideTheSeam() {
        // The lull has to carry the pair or the second half is written and never shown.
        assertTrue(
            "PART_TWO_AT ${CrystalVoice.PART_TWO_AT} must leave room inside a " +
                "${ReckoningDirector.LULL_SECONDS}s lull",
            CrystalVoice.PART_TWO_AT < ReckoningDirector.LULL_SECONDS
        )
        val second = CrystalFightLines.taunt(CrystalPhase.P2).second
        assertEquals(second, inPhase(1, ReckoningDirector.LULL_SECONDS - 0.1f))
    }

    @Test fun shatterHasNoSecondHalfAndThatIsThePoint() {
        // "STAY - STAY..." is the last thing it manages. The silence after it is authored:
        // SHATTER's taunt has no second half, so the phase's back half stays quiet even
        // with all the room in the world.
        assertEquals("STAY - STAY...", inPhase(4, 0f))
        assertNull(inPhase(4, CrystalVoice.PART_TWO_AT))
    }

    @Test fun aSixthPatternWouldNotCrashTheEnding() {
        // The five patterns line up one-to-one with P1..P5 today. If a sixth is ever added
        // this must clamp rather than throw in the middle of the finale.
        assertEquals(CrystalPhase.P5, CrystalVoice.phaseFor(5))
        assertEquals(CrystalPhase.P5, CrystalVoice.phaseFor(99))
        assertEquals(CrystalPhase.P1, CrystalVoice.phaseFor(-1))
    }

    @Test fun everyLineFitsTheStrip() {
        // 35 chars is the budget the radio lines were authored to and the strip inherits.
        val all = CrystalFightLines.opening +
            CrystalPhase.entries.flatMap { listOfNotNull(
                CrystalFightLines.taunt(it).first, CrystalFightLines.taunt(it).second
            ) }
        for (l in all) assertTrue("too long for the strip: \"$l\" (${l.length})", l.length <= 35)
    }

    @Test fun theOpeningHasExactlyTheThreeBeatsTheScheduleIndexes() {
        // lineAt indexes [0], [1] and [2] directly. A shorter list is a crash on entry.
        assertEquals(3, CrystalFightLines.opening.size)
    }

    @Test fun theCrystalThanksYouOnTheWayOut() {
        // A design decision. The win hold already exists — the shell stays on PLAY until the
        // shatter clears — and until now it played as dead air.
        val s = CabinetSim(m, kotlin.random.Random(3))
        s.start()
        val r = ReckoningRun(m, startPhase = 1)
        r.begin(s)
        r.update(1f / 60f, s)
        assertNotEquals(
            "it must not be saying goodbye while the fight is still on",
            CrystalFightLines.farewell, CrystalVoice.lineFor(r)
        )
        val c = s.crystal!!
        repeat(c.maxHp) { c.damage() }
        r.update(1f / 60f, s)
        assertEquals(ReckoningRun.Outcome.WON, r.outcome)
        assertEquals(CrystalFightLines.farewell, CrystalVoice.lineFor(r))
    }

    @Test fun aCrystalThatOutlivesYouSaysNothing() {
        val s = CabinetSim(m, kotlin.random.Random(3))
        s.start()
        val r = ReckoningRun(m, startPhase = 1)
        r.begin(s)
        r.update(1f / 60f, s)
        s.placeShip(s.crystal!!.x, s.crystal!!.y)
        s.update(1f / 60f, 0f, 0f, false)
        r.update(1f / 60f, s)
        assertEquals(ReckoningRun.Outcome.LOST, r.outcome)
        assertNull("it has nothing to thank you for", CrystalVoice.lineFor(r))
    }

    @Test fun theLastWordFitsTheStrip() {
        assertTrue(
            "\"${CrystalFightLines.farewell}\" is ${CrystalFightLines.farewell.length} chars",
            CrystalFightLines.farewell.length <= 35
        )
    }

    // ── a replay has no crystal in it ─────────────────────────────────────

    private fun replayLine(run: ReckoningRun) = CrystalVoice.lineFor(run)

    @Test fun aReplayNeverSpeaksInTheCrystalsVoice() {
        // Owner, device pass: "when you enter the fight through the ??? button, the crystal
        // itself is gone, so I don't want it speaking to you." It IS gone — crystal_released
        // is set, the story resolved, and the ??? entry replays a fight that already
        // happened. The lures, the pleading and the thanks all belong to the first time.
        val m = CabinetMetrics(1080f, 2400f)
        val s = CabinetSim(m, kotlin.random.Random(13)).also { it.start() }
        val r = ReckoningRun(m, isReplay = true)
        r.begin(s)
        val authored = CrystalPhase.entries.flatMap {
            val (a, b) = CrystalFightLines.taunt(it); listOfNotNull(a, b)
        }.toSet() + CrystalFightLines.opening.toSet() + setOf(CrystalFightLines.farewell)
        val dt = 1f / 60f
        repeat(60 * 120) {
            val line = replayLine(r)
            assertFalse("a replay spoke the crystal's line: $line", line in authored)
            r.update(dt, s); s.update(dt, 0f, 0f, false)
        }
    }

    @Test fun aReplaySaysWhatTheMachineIsDoing() {
        val m = CabinetMetrics(1080f, 2400f)
        val s = CabinetSim(m, kotlin.random.Random(13)).also { it.start() }
        val r = ReckoningRun(m, isReplay = true)
        r.begin(s)
        assertEquals(CrystalVoice.PLAYBACK, replayLine(r))
        val dt = 1f / 60f
        var guard = 0
        while (!r.justLanded && guard < 60 * 30) { r.update(dt, s); s.update(dt, 0f, 0f, false); guard++ }
        r.update(dt, s)
        assertEquals("the machine names the pattern it is running",
            CrystalVoice.patternLabel(0), replayLine(r))
    }

    @Test fun theMachineNamesEveryPatternOneToFive() {
        val labels = (0..4).map { CrystalVoice.patternLabel(it) }
        assertEquals("five distinct labels", 5, labels.toSet().size)
        labels.forEachIndexed { i, l -> assertTrue("$l should name pattern ${i + 1}", l.contains("${i + 1}")) }
        assertEquals("an index off the end must not throw",
            CrystalVoice.patternLabel(4), CrystalVoice.patternLabel(99))
    }

    @Test fun aRealRunStillGetsTheCrystal() {
        // The substitution must not leak into the fight it was written for.
        val m = CabinetMetrics(1080f, 2400f)
        val s = CabinetSim(m, kotlin.random.Random(13)).also { it.start() }
        val r = ReckoningRun(m, isReplay = false)
        r.begin(s)
        assertNotEquals(CrystalVoice.PLAYBACK, CrystalVoice.lineFor(r))
    }

    // ── the body and the voice answer the same question ───────────────────

    @Test fun aReplayFightsAnEmptyShell() {
        // A design decision. The crystal is not there on a replay, and that has to be true of
        // its BODY as well as its voice: you fight the containment with nothing in it.
        val m = CabinetMetrics(1080f, 2400f)
        assertFalse(ReckoningRun(m, isReplay = true).crystalHasBody)
    }

    @Test fun theRealFightHasACrystalInIt() {
        val m = CabinetMetrics(1080f, 2400f)
        assertTrue(ReckoningRun(m, isReplay = false).crystalHasBody)
    }

    @Test fun theBodyAndTheVoiceNeverDisagree() {
        // The two substitutions are the same fact — "there is no crystal here" — so they
        // must be driven by the same flag. Reading them off independently is how one gets
        // changed and the other does not.
        val m = CabinetMetrics(1080f, 2400f)
        for (replay in listOf(true, false)) {
            val s = CabinetSim(m, kotlin.random.Random(13)).also { it.start() }
            val r = ReckoningRun(m, isReplay = replay)
            r.begin(s)
            val speaksAsMachine = CrystalVoice.lineFor(r) == CrystalVoice.PLAYBACK
            assertEquals("body and voice disagree at isReplay=$replay",
                r.crystalHasBody, !speaksAsMachine)
        }
    }

    @Test fun theLandedCrystalCarriesTheSameAnswerTheRunGives() {
        // The drawn shell has to outlive the run object. CabinetShell.onBack() nulls
        // `reckoning` and only ASKS to leave, while the host keeps drawing the GAME OVER
        // screen for the whole tube power-down — so a renderer reading the body off the run
        // falls back to "there is a crystal" at exactly the moment the player leaves, and a
        // replay's empty shell fills in as it fades. The crystal it placed carries the fact
        // instead, and this pins the two together at both values.
        val m = CabinetMetrics(1080f, 2400f)
        for (replay in listOf(true, false)) {
            val s = CabinetSim(m, kotlin.random.Random(13)).also { it.start() }
            val r = ReckoningRun(m, startPhase = 1, isReplay = replay)
            r.begin(s)
            val c = s.crystal
            assertNotNull("a phase jump lands the crystal immediately", c)
            assertEquals("the body disagrees with the run at isReplay=$replay",
                r.crystalHasBody, c!!.hasBody)
        }
    }

    @Test fun theReplaysTimeIsThreeDigitsLikeEverySpaceOnTheMachine() {
        // Three digits zero-padded is the cabinet's convention and seven other sites keep
        // it — the score board, the bezel, the top strip, GAME OVER. This one printed
        // "RELEASE 87" and was the only place a number was shown bare, on the far side of
        // the machine from the board that shows the same figure padded.
        val m = CabinetMetrics(1080f, 2400f)
        val s = CabinetSim(m, kotlin.random.Random(13)).also { it.start() }
        val r = ReckoningRun(m, isReplay = true)
        r.begin(s)
        val dt = 1f / 60f
        var guard = 0
        while (!r.justLanded && guard < 60 * 30) { r.update(dt, s); s.update(dt, 0f, 0f, false); guard++ }
        repeat(60 * 5) { r.update(dt, s); s.update(dt, 0f, 0f, false) }
        s.crystal?.damage(10_000)
        r.update(dt, s)
        assertEquals(ReckoningRun.Outcome.WON, r.outcome)

        val line = CrystalVoice.lineFor(r)!!
        assertTrue("the machine printed a bare number: $line", Regex("^RELEASE \\d{3}$").matches(line))
        assertEquals("RELEASE 005", line)
    }
}
