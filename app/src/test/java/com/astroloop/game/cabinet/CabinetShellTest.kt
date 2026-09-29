package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CabinetShellTest {

    private class FakeWallet(var credits: Int = 0) {
        fun spend(): Boolean { if (credits <= 0) return false; credits--; return true }
    }

    private fun shell(credits: Int = 1, shouldStartReckoning: () -> Boolean = { false }): Pair<CabinetShell, FakeWallet> {
        val wallet = FakeWallet(credits)
        val s = CabinetShell(
            CabinetMetrics(1080f, 2400f),
            Random(2),
            spendCredit = { wallet.spend() },
            recordScore = { _ -> },
            shouldStartReckoning = shouldStartReckoning
        )
        return s to wallet
    }

    @Test fun opensOnTheMenu() {
        assertEquals(CabinetScreen.MENU, shell().first.screen)
    }

    @Test fun playSpendsACreditAndStartsARun() {
        val (s, wallet) = shell(credits = 1)
        assertTrue(s.onPlay())
        assertEquals(CabinetScreen.PLAY, s.screen)
        assertEquals(0, wallet.credits)
        assertEquals(0, s.sim.score)
    }

    @Test fun playWithoutACreditIsRefusedAndChangesNothing() {
        val (s, _) = shell(credits = 0)
        assertFalse(s.onPlay())
        assertEquals(CabinetScreen.MENU, s.screen)
    }

    @Test fun scoresAndBackAreReversible() {
        val (s, _) = shell()
        s.onScores()
        assertEquals(CabinetScreen.SCORES, s.screen)
        s.onBack()
        assertEquals(CabinetScreen.MENU, s.screen)
    }

    @Test fun pausingHoldsTheClock() {
        val (s, _) = shell()
        s.onPlay()
        repeat(30) { s.update(1f / 60f, 0f, -1f, true) }
        s.onPause()
        assertEquals(CabinetScreen.PAUSE, s.screen)
        val frozen = s.sim.ship.x to s.sim.ship.y
        repeat(60) { s.update(1f / 60f, 1f, 0f, true) }
        assertEquals("paused sim must not advance", frozen.first, s.sim.ship.x, 0.001f)
        assertEquals(frozen.second, s.sim.ship.y, 0.001f)
    }

    @Test fun resumeReturnsToPlay() {
        val (s, _) = shell()
        s.onPlay(); s.onPause(); s.onResume()
        assertEquals(CabinetScreen.PLAY, s.screen)
    }

    @Test fun quitFromPauseGoesToTheMenuAndAbandonsTheRun() {
        val (s, _) = shell()
        s.onPlay(); s.onPause(); s.onQuit()
        assertEquals(CabinetScreen.MENU, s.screen)
    }

    @Test fun theGenuineReckoningStillRefusesAPause() {
        val (s, _) = shell()
        s.startReckoning(startPhase = 0, isReplay = false)
        s.onPause()
        assertEquals(CabinetScreen.PAUSE, s.screen)
        assertTrue("the ending refuses a pause", s.refusesPause)
        // The crystal takes the menu, answers it, and hands the fight back.
        repeat(200) { s.update(1f / 60f, 0f, 0f, false) }
        assertEquals("the pause must not be allowed to last", CabinetScreen.PLAY, s.screen)
    }

    @Test fun aReplayGrantsARealPause() {
        val (s, _) = shell()
        s.startReckoning(startPhase = 0, isReplay = true)
        s.onPause()
        assertEquals(CabinetScreen.PAUSE, s.screen)
        assertFalse("a replay is not the ending and does not refuse", s.refusesPause)
        // Held for as long as the player wants it, like any ordinary pause.
        repeat(600) { s.update(1f / 60f, 0f, 0f, false) }
        assertEquals("a replay's pause must hold", CabinetScreen.PAUSE, s.screen)
        assertEquals("and it must not tick the crystal's answering line", 0, s.pauseAttempts)
    }

    @Test fun aReplayCanBeQuitButTheEndingCannot() {
        val (replay, _) = shell()
        replay.startReckoning(startPhase = 0, isReplay = true); replay.onPause(); replay.onQuit()
        assertEquals("a replay was entered from the menu and returns to it",
            CabinetScreen.MENU, replay.screen)

        val (real, _) = shell()
        real.startReckoning(startPhase = 0, isReplay = false)
        real.onPause(); real.onQuit()
        assertEquals("there is no quitting the ending", CabinetScreen.PAUSE, real.screen)
    }

    @Test fun deathMovesToOver() {
        val (s, _) = shell()
        s.onPlay()
        val rock = s.sim.rocks.first()
        s.sim.ship.x = rock.x; s.sim.ship.y = rock.y
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(CabinetScreen.OVER, s.screen)
    }

    @Test fun theWreckFinishesItsFallOnTheGameOverScreen() {
        // The level that matters. CabinetSimTest.theWreckKeepsMovingAfterTheRunIsOver
        // already asserted this of CabinetSim in isolation and stayed green while the
        // product was broken, because the shell's OVER branch was `Unit`: the sim was
        // ticked exactly once on the death frame and then never again, so the hull
        // segments sat frozen at their spawn vertices — an intact-looking ship under
        // GAME OVER — and the wave banner's countdown froze with them, on top of the
        // score. Nothing below touches CabinetSim directly; it all goes through the
        // shell, which is where the freeze lived.
        val (s, _) = shell()
        s.onPlay()
        val rock = s.sim.rocks.first()
        s.sim.ship.x = rock.x; s.sim.ship.y = rock.y
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(CabinetScreen.OVER, s.screen)

        val wreck = s.sim.debris.firstOrNull()
        assertNotNull("the ship should have come apart", wreck)
        val before = wreck!!.x to wreck.y
        val bannerBefore = s.sim.waveBanner
        assertTrue("WAVE 1 should still be on screen at this point", bannerBefore > 0f)

        // Well inside CabinetDebris.LIFETIME, so this segment is still a live fragment.
        repeat(6) { s.update(1f / 60f, 0f, 0f, false) }

        assertTrue(
            "the wreck must keep drifting once the shell is on OVER",
            wreck.x != before.first || wreck.y != before.second
        )
        assertTrue(
            "the wave banner must keep counting down once the shell is on OVER",
            s.sim.waveBanner < bannerBefore
        )
    }

    @Test fun againIsAFreshRunFromZeroNotAContinue() {
        // This is what keeps every 999 a single unbroken life, so the gate stays a
        // skill test that yen cannot buy.
        val (s, wallet) = shell(credits = 2)
        s.onPlay()
        s.sim.addScoreForTest(400)
        val rock = s.sim.rocks.first()
        s.sim.ship.x = rock.x; s.sim.ship.y = rock.y
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(CabinetScreen.OVER, s.screen)

        assertTrue(s.onAgain())
        assertEquals(CabinetScreen.PLAY, s.screen)
        assertEquals("AGAIN must reset the score", 0, s.sim.score)
        assertEquals("and must cost a credit", 0, wallet.credits)
    }

    @Test fun againWithoutACreditIsRefused() {
        val (s, _) = shell(credits = 1)
        s.onPlay()
        val rock = s.sim.rocks.first()
        s.sim.ship.x = rock.x; s.sim.ship.y = rock.y
        s.update(1f / 60f, 0f, 0f, false)
        assertFalse(s.onAgain())
        assertEquals(CabinetScreen.OVER, s.screen)
    }

    @Test fun theRunsScoreIsRecordedExactlyOnceOnDeath() {
        var recorded = ArrayList<Int>()
        val s = CabinetShell(
            CabinetMetrics(1080f, 2400f),
            Random(2),
            spendCredit = { true },
            recordScore = { recorded.add(it) },
            shouldStartReckoning = { false }
        )
        s.onPlay()
        s.sim.addScoreForTest(250)
        val rock = s.sim.rocks.first()
        s.sim.ship.x = rock.x; s.sim.ship.y = rock.y
        // Placing the ship exactly on the rock also puts the ship's own frame-0 shot
        // there: resolveBulletHits() runs before resolveShipCollision() in the same
        // update(), so that rock is destroyed (+1, LARGE) an instant before the two
        // fragments it leaves in the same spot kill the ship. 251 is the deterministic
        // result, not 250 — the point under test is that the callback fires once.
        repeat(10) { s.update(1f / 60f, 0f, 0f, false) }
        assertEquals(listOf(251), recorded)
    }

    @Test fun attractRunsOnTheMenuOnly() {
        val (s, _) = shell()
        s.update(1f / 60f, 0f, 0f, false)
        assertTrue("the attract demo should be advancing", s.attractElapsed > 0f)
        s.onPlay()
        val frozen = s.attractElapsed
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(frozen, s.attractElapsed, 0.0001f)
    }

    @Test fun theAttractDemoPlaysItselfOnTheMenu() {
        // A real cabinet's attract loop, and it costs nothing: the same CabinetSim the
        // player uses, driven by two floats from a dumb autopilot.
        val (s, _) = shell()
        val start = s.attractSim.ship.x to s.attractSim.ship.y
        repeat(60) { s.update(1f / 60f, 0f, 0f, false) }
        assertTrue("the demo ship should be flying",
            s.attractSim.ship.x != start.first || s.attractSim.ship.y != start.second)
    }

    @Test fun theAttractDemoIsFrozenDuringARun() {
        val (s, _) = shell()
        s.onPlay()
        val frozen = s.attractSim.ship.x
        repeat(60) { s.update(1f / 60f, 0f, 0f, false) }
        assertEquals(frozen, s.attractSim.ship.x, 0.0001f)
    }

    @Test fun theAttractDemoRestartsWhenItDies() {
        // Superseded in spirit by theAttractDemoHoldsOnItsWreckBeforeRestarting, which
        // pins the hold's exact timing. This just guards the outer property: whatever
        // the hold is, the demo must never sit on a game-over screen forever.
        val (s, _) = shell()
        val rock = s.attractSim.rocks.first()
        s.attractSim.ship.x = rock.x; s.attractSim.ship.y = rock.y
        s.update(1f / 60f, 0f, 0f, false)
        assertTrue("the demo should have died", s.attractSim.over)
        s.update(CabinetShell.ATTRACT_RESTART_HOLD, 0f, 0f, false)
        // The restart gate checks attractSim.debris.isEmpty() *before* this call's own
        // aging runs, so a single huge-dt step that both crosses the hold and ages the
        // wreck out only clears the debris — it lands the restart on the following
        // step, once the now-empty debris list is visible to the check.
        s.update(1f / 60f, 0f, 0f, false)
        assertFalse("the demo must never sit on a game-over screen forever", s.attractSim.over)
    }

    @Test fun theAttractDemoNeverTouchesTheRealScore() {
        var recorded = 0
        val s = CabinetShell(
            CabinetMetrics(1080f, 2400f),
            Random(2),
            spendCredit = { true },
            recordScore = { recorded++ },
            shouldStartReckoning = { false }
        )
        repeat(600) { s.update(1f / 60f, 0f, 0f, false) }
        assertEquals("attract deaths must not be logged as runs", 0, recorded)
    }

    @Test fun theAttractDemoHoldsOnItsWreckBeforeRestarting() {
        // It used to restart in the same frame it died, which read as a glitch rather
        // than a loop. The debris gives it a beat to hold on.
        val (s, _) = shell()
        // Kill the demo outright by parking its ship on a rock.
        val rock = s.attractSim.rocks.first()
        s.attractSim.ship.x = rock.x
        s.attractSim.ship.y = rock.y
        s.update(1f / 120f, 0f, 0f, false)
        assertTrue("the demo should have died", s.attractSim.over)

        // Still dead a beat later, with wreckage on screen.
        s.update(0.3f, 0f, 0f, false)
        assertTrue("must not restart instantly", s.attractSim.over)
        assertTrue("the wreck should be visible", s.attractSim.debris.isNotEmpty())

        // The hold expires this step, but the debris gate is checked before this
        // call's own aging runs — so this step only finishes fading the wreck out
        // (life was 0.3s, well under the 1.4s dt), and the restart lands next step
        // once debris.isEmpty() is visible to the check.
        s.update(CabinetShell.ATTRACT_RESTART_HOLD, 0f, 0f, false)
        assertTrue("wreck fades out but restart waits one more step", s.attractSim.over)
        s.update(1f / 60f, 0f, 0f, false)
        assertFalse("should have restarted by now", s.attractSim.over)
    }

    @Test fun aLargeFrameDeltaDoesNotPopTheWreckMidFade() {
        // HangarSurfaceView computes deltaTime from an unclamped nanoTime() diff, and
        // its own code anticipates a large delta on resume from background (see the
        // try/catch around update()/render() in its run() loop). A hitch of that size
        // while the attract demo sits on its wreck is a production-reachable event,
        // not a hypothetical: this single call can satisfy ATTRACT_RESTART_HOLD on
        // its own before the wreck has had any chance to age down.
        val (s, _) = shell()
        val rock = s.attractSim.rocks.first()
        s.attractSim.ship.x = rock.x
        s.attractSim.ship.y = rock.y
        s.update(1f / 120f, 0f, 0f, false)
        assertTrue("the demo should have died", s.attractSim.over)
        assertTrue("wreckage should be on screen", s.attractSim.debris.isNotEmpty())

        // One giant step — the background-resume hitch — jumps attractRestartHold
        // straight past ATTRACT_RESTART_HOLD in a single call. The old code restarted
        // right here, wiping still-fading debris in the same frame it was last drawn
        // visible. The fix must refuse: the wreck was alive when this step began, so
        // the demo must not have restarted by the time it ends.
        s.update(CabinetShell.ATTRACT_RESTART_HOLD, 0f, 0f, false)
        assertTrue(
            "a dt spike must not restart the demo while its wreck was still visible",
            s.attractSim.over
        )
    }

    @Test fun exitIsRequestedFromTheMenu() {
        val (s, _) = shell()
        assertFalse(s.requestExit)
        s.onExit()
        assertTrue(s.requestExit)
    }

    @Test fun startReckoningItselfNeverSpendsACredit() {
        // startReckoning() is the shared primitive: the debug jump calls it directly and
        // must stay free (it is a developer tool, not PLAY), while beginRun() calls it
        // too but only after spendCredit() has already run there. A design decision moved the
        // charge into beginRun's ordering; this primitive was never what charged and
        // still isn't.
        val (s, wallet) = shell(credits = 0)
        s.startReckoning(startPhase = 1)
        assertEquals(CabinetScreen.PLAY, s.screen)
        assertEquals(0, wallet.credits)
        assertTrue(s.isReckoning)
    }

    @Test fun playStartsTheReckoningWhenTheGateIsOpen() {
        var spent = 0
        val s = CabinetShell(
            CabinetMetrics(1080f, 2400f), Random(2),
            spendCredit = { spent++; true },
            recordScore = { },
            shouldStartReckoning = { true }
        )
        assertTrue(s.onPlay())
        assertTrue("the gate must start a reckoning, not a free-play run", s.isReckoning)
        assertEquals("the finale is charged for like any other run", 1, spent)
    }

    @Test fun playStartsAFreePlayRunWhenTheGateIsShut() {
        val s = CabinetShell(
            CabinetMetrics(1080f, 2400f), Random(2),
            spendCredit = { true },
            recordScore = { },
            shouldStartReckoning = { false }
        )
        assertTrue(s.onPlay())
        assertFalse(s.isReckoning)
        assertEquals(CabinetScreen.PLAY, s.screen)
    }

    @Test fun noCreditMeansNoRunOfEitherKind() {
        val s = CabinetShell(
            CabinetMetrics(1080f, 2400f), Random(2),
            spendCredit = { false },
            recordScore = { },
            shouldStartReckoning = { true }
        )
        assertFalse(s.onPlay())
        assertFalse("a refused credit must not start the ending either", s.isReckoning)
        assertEquals(CabinetScreen.MENU, s.screen)
    }

    @Test fun againDuringAReckoningRestartsTheFightAndCharges() {
        var spent = 0
        val s = CabinetShell(
            CabinetMetrics(1080f, 2400f), Random(2),
            spendCredit = { spent++; true },
            recordScore = { },
            shouldStartReckoning = { true }
        )
        s.onPlay()                       // spent == 1, reckoning live
        s.sim.endRun()
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(CabinetScreen.OVER, s.screen)

        assertTrue(s.onAgain())
        assertTrue("AGAIN? must restart the FIGHT, not a free-play run", s.isReckoning)
        assertEquals("a design decision: AGAIN? charges too", 2, spent)
    }

    @Test fun aBrokePlayerCannotRetryTheReckoning() {
        var first = true
        val s = CabinetShell(
            CabinetMetrics(1080f, 2400f), Random(2),
            spendCredit = { if (first) { first = false; true } else false },
            recordScore = { },
            shouldStartReckoning = { true }
        )
        s.onPlay()
        s.sim.endRun()
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(CabinetScreen.OVER, s.screen)

        assertFalse(s.onAgain())
        assertEquals("a refused retry leaves the player on OVER", CabinetScreen.OVER, s.screen)
    }

    @Test fun theGateIsCheckedFreshOnEveryPlayNotCachedAtConstruction() {
        // The timing property that actually matters: a developer clearing the twelfth
        // pilot from the debug menu inside an open cabinet session must find the gate
        // open on the very next PLAY, with no need to close and reopen the machine.
        // That is only true if shouldStartReckoning() is called from beginRun() itself
        // rather than read once and cached.
        var gateOpen = false
        val s = CabinetShell(
            CabinetMetrics(1080f, 2400f), Random(2),
            spendCredit = { true },
            recordScore = { },
            shouldStartReckoning = { gateOpen }
        )
        assertTrue(s.onPlay())
        assertFalse("the gate was shut at this PLAY", s.isReckoning)
        s.sim.endRun()
        s.update(1f / 60f, 0f, 0f, false)
        s.onBack()
        assertEquals(CabinetScreen.MENU, s.screen)

        // The gate flips mid-session, exactly as clearing the twelfth pilot would.
        gateOpen = true
        assertTrue(s.onPlay())
        assertTrue("the next PLAY must see the gate open without reopening the cabinet", s.isReckoning)
    }

    @Test fun theReckoningNeverWritesAnArcadeScore() {
        // A design decision. The stage 3 gate reads that table; "cannot" beats "happens not to".
        var recorded = -1
        val sh = CabinetShell(
            CabinetMetrics(1080f, 2400f), Random(2),
            spendCredit = { true },
            recordScore = { v -> recorded = v },
            shouldStartReckoning = { false }
        )
        sh.startReckoning(startPhase = 1)
        sh.sim.addScoreForTest(500)
        sh.sim.endRun()
        sh.update(1f / 60f, 0f, 0f, false)
        assertEquals(CabinetScreen.OVER, sh.screen)
        assertEquals("no score may reach the board from the ending", -1, recorded)
    }

    @Test fun thereIsNoQuittingTheReckoning() {
        // INVERTED. A later decision amends an earlier one, which used to drop you back in the
        // hangar because the attract menu was no place to leave somebody out of an ending.
        // The owner's answer is that there is no leaving at all: "if the player wants out
        // they either beat it or die."
        //
        // The button is also absent from the paused screen, so this is the second gate
        // rather than the only one — but it is the one that holds if anything else ever
        // reaches onQuit.
        val (s, _) = shell(credits = 0)
        s.startReckoning(startPhase = 1)
        s.onPause()
        assertEquals(CabinetScreen.PAUSE, s.screen)
        s.onQuit()
        assertFalse("the crystal does not let go", s.requestExit)
        assertTrue("and the run is still there", s.isReckoning)
    }

    @Test fun leavingAReckoningMustNotTurnItsOverScreenIntoAScoringRun() {
        // The GAME OVER screen suppresses its three-digit readout because the reckoning keeps
        // no score, and it used to decide that from `isReckoning`. But onBack() lets go of the
        // run and only ASKS to leave: the host then powers the tube down over POWER_DOWN_SECONDS,
        // still drawing this same screen. So the readout that was correctly absent for the whole
        // death popped to "000" on the frame the player pressed LEAVE, under the words GAME OVER.
        //
        // sim.scores is the fact that actually survives, and it is the same flag that stops rocks
        // paying — the exact condition under which a readout would be a lie. This pins the
        // divergence the fix depends on.
        val (s, _) = shell(credits = 1, shouldStartReckoning = { true })
        s.onPlay()
        s.sim.endRun()
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(CabinetScreen.OVER, s.screen)
        assertTrue("the run is still held while the screen stands", s.isReckoning)
        assertFalse("the reckoning never scores", s.sim.scores)

        s.onBack()
        assertTrue("LEAVE only asks; the host still has a tube to power down", s.requestExit)
        assertFalse("the run itself is let go here", s.isReckoning)
        assertFalse(
            "but the screen still on the tube must keep saying the run did not score",
            s.sim.scores
        )
    }

    @Test fun theCrystalHandsTheFightBackOnItsOwn() {
        // A design decision. The pause is real for a beat, so the refusal reads as a refusal
        // rather than as a dropped input — then the crystal takes the menu and resumes it.
        val (s, _) = shell(credits = 0)
        s.startReckoning(startPhase = 1)
        s.onPause()
        assertEquals(CabinetScreen.PAUSE, s.screen)

        s.update(CabinetShell.PAUSE_HELD_SECONDS, 0f, 0f, false)
        assertEquals("the pause must visibly happen first", CabinetScreen.PAUSE, s.screen)

        s.update(CabinetShell.PAUSE_DENIAL_SECONDS, 0f, 0f, false)
        assertEquals("and then be handed back", CabinetScreen.PLAY, s.screen)
    }

    @Test fun aFreePlayPauseIsStillAPause() {
        // The regression this must not cause. Nothing outside the reckoning is denied, and
        // nothing outside it resumes on its own.
        val (s, _) = shell(credits = 1)
        assertTrue(s.onPlay())
        s.onPause()
        s.update(CabinetShell.PAUSE_DENIAL_SECONDS * 3f, 0f, 0f, false)
        assertEquals("free play holds the clock, as pause is for", CabinetScreen.PAUSE, s.screen)
    }

    @Test fun quitFromAFreePlayRunStillGoesToTheMenu() {
        // The regression this must not cause: normal play is unchanged.
        val (s, _) = shell(credits = 1)
        assertTrue(s.onPlay())
        s.onPause()
        s.onQuit()
        assertEquals(CabinetScreen.MENU, s.screen)
        assertFalse(s.requestExit)
    }

    @Test fun pauseStillPausesInTheReckoning() {
        // A design decision's "as everywhere else" must not break in the fight that most
        // needs a breather.
        val (s, _) = shell(credits = 0)
        s.startReckoning(startPhase = 1)
        s.onPause()
        assertEquals(CabinetScreen.PAUSE, s.screen)
        val before = s.sim.bullets.size
        repeat(60) { s.update(1f / 60f, 0f, 0f, false) }
        assertEquals("pause holds the clock", before, s.sim.bullets.size)
        s.onResume()
        assertEquals(CabinetScreen.PLAY, s.screen)
    }

    @Test fun winningNeverShowsTheGameOverScreen() {
        // The screen says GAME OVER over a three-digit score. Showing it on a win would
        // end the game's climax by telling the player they lost.
        val (s, _) = shell(credits = 0)
        s.startReckoning(startPhase = 1)
        val c = s.sim.crystal!!
        repeat(c.maxHp) { c.damage() }
        repeat(600) { s.update(1f / 60f, 0f, 0f, false) }
        assertNotEquals(CabinetScreen.OVER, s.screen)
        assertTrue("a win leaves the machine", s.requestExit)
    }

    @Test fun theWinHoldsUntilTheWreckIsGone() {
        // Gated on the wreck being gone, not on a timer - the dt-proof condition
        // CabinetAttract already uses. A long frame must not skip the ending.
        val (s, _) = shell(credits = 0)
        s.startReckoning(startPhase = 1)
        val c = s.sim.crystal!!
        repeat(c.maxHp) { c.damage() }
        s.update(1f / 60f, 0f, 0f, false)
        assertTrue("the shatter must exist to be watched", s.sim.debris.isNotEmpty())
        assertFalse("and must not be cut short", s.requestExit)
    }

    @Test fun backFromAReckoningLossLeavesTheMachine() {
        val (s, _) = shell(credits = 0)
        s.startReckoning(startPhase = 1)
        s.sim.endRun()
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(CabinetScreen.OVER, s.screen)
        s.onBack()
        assertTrue(s.requestExit)
    }

    @Test fun backFromAFreePlayLossStillGoesToTheMenu() {
        val (s, _) = shell(credits = 1)
        assertTrue(s.onPlay())
        s.sim.endRun()
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(CabinetScreen.OVER, s.screen)
        s.onBack()
        assertEquals(CabinetScreen.MENU, s.screen)
        assertFalse(s.requestExit)
    }

    @Test
    fun theReplayEntryStartsAReckoningMarkedAsAReplay() {
        var spent = 0
        val shell = CabinetShell(
            CabinetMetrics(1080f, 2400f), Random(1),
            spendCredit = { spent++; true },
            recordScore = { },
            shouldStartReckoning = { false }   // gate shut: the crystal is already released
        )
        assertTrue(shell.onReplay())
        assertTrue(shell.isReckoning)
        assertTrue("a replay must know it is one", shell.reckoning!!.isReplay)
        assertEquals("a replay is charged for like any other run", 1, spent)
    }

    @Test
    fun theReplayEntryIsOnlyReachableFromTheMenu() {
        // screen has a private setter, so this reaches SCORES the same way the product
        // does — via onScores() — rather than assigning it directly.
        val shell = CabinetShell(
            CabinetMetrics(1080f, 2400f), Random(1),
            spendCredit = { true }, recordScore = { }, shouldStartReckoning = { false }
        )
        shell.onScores()
        assertFalse(shell.onReplay())
        assertFalse(shell.isReckoning)
    }

    @Test
    fun anOrdinaryReckoningIsNotAReplay() {
        val shell = CabinetShell(
            CabinetMetrics(1080f, 2400f), Random(1),
            spendCredit = { true }, recordScore = { }, shouldStartReckoning = { true }
        )
        shell.onPlay()
        assertFalse("the first, gated entry is the real ending", shell.reckoning!!.isReplay)
    }

    @Test
    fun retryingAReplayStaysAReplay() {
        // The trap: beginRun's isReckoning branch calls startReckoning(0), which builds a
        // fresh ReckoningRun. Unless the replay flag is carried across explicitly, AGAIN?
        // pressed inside a replay would produce a run that believes it is the real ending.
        val shell = CabinetShell(
            CabinetMetrics(1080f, 2400f), Random(1),
            spendCredit = { true }, recordScore = { }, shouldStartReckoning = { false }
        )
        shell.onReplay()
        // Reach OVER the same way the file's other reckoning tests do — sim.endRun() then
        // one update() — since screen's setter is private to CabinetShell.
        shell.sim.endRun()
        shell.update(1f / 60f, 0f, 0f, false)
        assertEquals(CabinetScreen.OVER, shell.screen)

        shell.onAgain()
        assertTrue("AGAIN? inside a replay must not become the real ending", shell.reckoning!!.isReplay)
    }

    @Test fun theBackButtonPausesThroughTheSameDoorAsADoubleTap() {
        // onBack()'s `when` set screen = PAUSE directly, bypassing onPause() — the only
        // place pauseAttempts++ and pauseDenial = 0f happen. So the reflex path (the system
        // back button, which onBackPressedFromActivity's own KDoc calls exactly that) never
        // advanced the denial line: drawPause indexes on pauseAttempts, so every back-button
        // pause showed line 0, "Not yet.", and the other authored answers were unreachable.
        val (s, _) = shell(credits = 1, shouldStartReckoning = { true })
        s.onPlay()
        assertEquals(CabinetScreen.PLAY, s.screen)
        assertEquals(0, s.pauseAttempts)

        s.onBack()
        assertEquals(CabinetScreen.PAUSE, s.screen)
        assertEquals("the back button must count as an attempt", 1, s.pauseAttempts)

        s.onResume()
        s.onBack()
        assertEquals("and keep counting, so the crystal's answers advance", 2, s.pauseAttempts)
    }

    @Test fun theBackButtonRezeroesTheDenialClockLikeADoubleTapDoes() {
        val (s, _) = shell(credits = 1, shouldStartReckoning = { true })
        s.onPlay()
        s.onPause()
        s.update(1f, 0f, 0f, false)          // let the denial clock run
        assertTrue(s.pauseDenial > 0f)
        s.onResume()
        s.onBack()
        assertEquals("a fresh pause starts its denial from zero", 0f, s.pauseDenial, 1e-6f)
    }
}
