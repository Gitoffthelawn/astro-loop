package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ReckoningRunTest {

    private val m = CabinetMetrics(1080f, 2400f)

    private fun sim() = CabinetSim(m, Random(13)).also { it.start() }

    private fun run(r: ReckoningRun, s: CabinetSim, seconds: Float) {
        val dt = 1f / 60f
        var t = 0f
        while (t < seconds) { r.update(dt, s); t += dt }
    }

    @Test fun phaseZeroPlaysTheOpening() {
        val s = sim()
        val r = ReckoningRun(m, startPhase = 0)
        r.begin(s)
        assertEquals(ReckoningOpening.Stage.PLAY, r.opening.stage)
        assertEquals(ReckoningOpening.SEED_ROCKS, s.rocks.size)
    }

    @Test fun aPhaseJumpSkipsStraightToTheFight() {
        // A design decision. Tuning a pattern must not cost the opening every time.
        val s = sim()
        val r = ReckoningRun(m, startPhase = 3)
        r.begin(s)
        assertEquals(ReckoningOpening.Stage.FIGHT, r.opening.stage)
        assertEquals(0, s.rocks.size)
        assertNotNull(s.crystal)
        assertEquals(2, r.director.phaseIndex)   // phase 3 is the third pattern, index 2
    }

    @Test fun aPhaseJumpDoesNotKillYouOnArrival() {
        // startReckoning calls sim.start(), which parks the ship at the field centre -
        // exactly where the crystal is placed. Without formCrystal's shove, every debug
        // phase jump would be an instant death and every tuning session would start with
        // a corpse.
        val s = sim()
        val r = ReckoningRun(m, startPhase = 3)
        r.begin(s)
        s.update(1f / 60f, 0f, 0f, false)
        assertTrue(s.ship.alive)
        assertFalse(s.over)
    }

    @Test fun noBulletsFlyDuringTheOpening() {
        // The crystal has no body yet. Emitting from a thing that is not there would be
        // the entrance's whole image undone.
        val s = sim()
        val r = ReckoningRun(m, startPhase = 0)
        r.begin(s)
        run(r, s, 3f)
        assertEquals(0, s.bullets.count { it.hostile })
    }

    @Test fun theDirectorTakesOverOnceTheCrystalHasABody() {
        val s = sim()
        val r = ReckoningRun(m, startPhase = 0)
        r.begin(s)
        // Past the entrance AND past the seam the first pattern opens with — a design decision
        // gives the crystal five seconds to speak before it fires a shot.
        run(
            r, s, ReckoningOpening.OPENING_SECONDS + ReckoningOpening.ARRIVAL_SECONDS +
                ReckoningDirector.LULL_SECONDS + 2f
        )
        assertEquals(ReckoningOpening.Stage.FIGHT, r.opening.stage)
        assertTrue("the crystal must be shooting by now", s.bullets.any { it.hostile })
    }

    @Test fun theRunTellsTheDirectorTheCrystalsHealth() {
        // A design decision's one wiring line. The director knows nothing about the crystal — it
        // is handed a number — so if the run stops passing it, the fight silently stays on
        // its first pattern for ever however much damage is done.
        val s = sim()
        val r = ReckoningRun(m, startPhase = 1)
        r.begin(s)
        r.update(1f / 60f, s)
        val c = s.crystal!!
        assertEquals(0, r.director.phaseIndex)
        c.damage(c.maxHp / 2)
        r.update(1f / 60f, s)
        assertEquals("the pattern must follow the damage", 2, r.director.phaseIndex)
    }

    @Test fun killingTheCrystalWinsAndShattersIt() {
        val s = sim()
        val r = ReckoningRun(m, startPhase = 1)
        r.begin(s)
        repeat(s.crystal!!.maxHp) { s.crystal?.damage() }
        r.update(1f / 60f, s)
        assertEquals(ReckoningRun.Outcome.WON, r.outcome)
        assertTrue("the ending is the shatter", s.debris.size >= 60)
        assertTrue(s.over)
        assertTrue("the pilot survives their own victory", s.ship.alive)
    }

    @Test fun theEndingSlowsTheClock() {
        val s = sim()
        val r = ReckoningRun(m, startPhase = 1)
        r.begin(s)
        repeat(s.crystal!!.maxHp) { s.crystal?.damage() }
        r.update(1f / 60f, s)
        assertTrue("the last thing the player watches must have weight", s.timeScale < 1f)
    }

    @Test fun dyingLosesAndDoesNotShatterTheCrystal() {
        val s = sim()
        val r = ReckoningRun(m, startPhase = 1)
        r.begin(s)
        s.placeShip(s.crystal!!.x, s.crystal!!.y)   // fly into its body
        s.update(1f / 60f, 0f, 0f, false)
        r.update(1f / 60f, s)
        assertEquals(ReckoningRun.Outcome.LOST, r.outcome)
        assertNotNull("the crystal outlives you", s.crystal)
    }

    @Test fun theOutcomeIsDecidedOnlyOnce() {
        val s = sim()
        val r = ReckoningRun(m, startPhase = 1)
        r.begin(s)
        repeat(s.crystal!!.maxHp) { s.crystal?.damage() }
        r.update(1f / 60f, s)
        val pieces = s.debris.size
        run(r, s, 1f)
        assertEquals(ReckoningRun.Outcome.WON, r.outcome)
        assertTrue("a second shatter would double the wreck", s.debris.size <= pieces)
    }

    @Test fun dyingDuringTheAuthoredOpeningIsALoss() {
        // The 12-second opening is ordinary BELT RUN against real rocks. Dying there is an
        // ordinary way to lose, and it must report LOST rather than leaving the run
        // reporting RUNNING forever.
        val s = sim()
        val r = ReckoningRun(m, startPhase = 0)
        r.begin(s)
        assertEquals(ReckoningOpening.Stage.PLAY, r.opening.stage)
        // Park the ship on a seeded rock.
        val rock = s.rocks.first()
        s.placeShip(rock.x, rock.y)
        s.update(1f / 60f, 0f, 0f, false)
        r.update(1f / 60f, s)
        assertTrue(s.over)
        assertEquals(ReckoningRun.Outcome.LOST, r.outcome)
    }

    @Test fun dyingOnTheSameTickTheCrystalDiesIsStillALoss() {
        // Both can resolve inside one sim.update(): resolveBulletHits kills the crystal,
        // resolveHostileHits kills the ship. LOST is the only answer consistent with
        // "the pilot survives their own victory" — a win that leaves a corpse is not one.
        val s = sim()
        val r = ReckoningRun(m, startPhase = 1)
        r.begin(s)
        val c = s.crystal!!
        repeat(c.maxHp) { c.damage() }
        s.addHostileBullet(CabinetBullet(s.ship.x, s.ship.y, 0f, 0f, 999f, hostile = true))
        s.update(1f / 60f, 0f, 0f, false)
        r.update(1f / 60f, s)
        assertEquals(ReckoningRun.Outcome.LOST, r.outcome)
        assertFalse(s.ship.alive)
    }
}
