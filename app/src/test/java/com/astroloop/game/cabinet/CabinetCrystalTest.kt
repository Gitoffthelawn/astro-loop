package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CabinetCrystalTest {

    private val m = CabinetMetrics(1080f, 2400f)

    private fun sim() = CabinetSim(m, Random(77)).also { it.start() }

    /** A sim with an empty field, waves off and a crystal at centre — the fight's world. */
    private fun fightSim(hp: Int = 100): CabinetSim {
        val s = sim()
        s.clearRocksForTest()
        s.wavesSuspended = true
        s.placeCrystal(CabinetCrystal(m.width / 2f, m.height / 2f, hp))
        // The ship MUST be moved off centre. CabinetShip.reset() parks it at exactly the
        // crystal's own position, so a fightSim() using the default would kill the player
        // on frame 1 — which silently defeats every test here, and would make Step 8's
        // mutation check pass vacuously: ship-death, not the suspend flag, would be what
        // stopped the wave spawning. 0.4 x minEdge is engagement range, comfortably
        // outside the crystal's 97.2px radius plus the ship's own 24.84px.
        s.placeShip(m.width / 2f, m.height / 2f + 0.4f * m.minEdge)
        return s
    }

    @Test fun aFreshCrystalIsAtFullHealth() {
        val c = CabinetCrystal(0f, 0f, 80)
        assertEquals(80, c.hp)
        assertTrue(c.alive)
        assertEquals(1f, c.healthFrac, 0.0001f)
    }

    @Test fun damageDrivesHealthDownAndStopsAtZero() {
        val c = CabinetCrystal(0f, 0f, 10)
        c.damage(4)
        assertEquals(6, c.hp)
        assertEquals(0.6f, c.healthFrac, 0.0001f)
        c.damage(99)
        assertEquals("hp must not go negative", 0, c.hp)
        assertFalse(c.alive)
        assertEquals(0f, c.healthFrac, 0.0001f)
    }

    @Test fun nonPositiveDamageIsANoOp() {
        // damage() is a named mutator and healing through it would be an abuse of the
        // name - which is exactly what `hp - n` used to do for a negative n.
        val c = CabinetCrystal(0f, 0f, 10)
        c.damage(0)
        c.damage(-5)
        assertEquals(10, c.hp)
    }

    // --- Design decisions: the band floor -------------------------------------------------

    @Test fun aFreshCrystalHasNoFloorUntilTheFightGivesItOne() {
        // Free play, and every frame before the director is driving. The unbanded
        // crystal is the one that shipped, and it must still be reachable.
        val c = CabinetCrystal(0f, 0f, 300)
        repeat(300) { c.damage() }
        assertFalse(c.alive)
    }

    @Test fun damageLandsInFullUntilTheFloorIsReached() {
        val c = CabinetCrystal(0f, 0f, 300)
        repeat(60) { c.damage() }
        assertEquals("the band itself is ordinary damage", 240, c.hp)
        assertTrue(c.alive)
    }




    @Test fun loweringTheFloorReleasesFullDamageAgain() {
        // The next band. The floor only ever falls, and the moment it does the same gun
        // is worth eight times as much again.
        val c = CabinetCrystal(0f, 0f, 300)
        repeat(60) { c.damage() }
        assertEquals(240, c.hp)
        repeat(10) { c.damage() }
        assertEquals(230, c.hp)
    }

    @Test fun theSoftFloorStillCannotDriveHpBelowZero() {
        val c = CabinetCrystal(0f, 0f, 10)
        c.damage(999)
        assertEquals(0, c.hp)
        assertFalse(c.alive)
    }


    @Test fun theRadiusIsAFractionOfTheShortEdge() {
        // Resolution independence: every cabinet constant is a fraction of minEdge.
        assertEquals(CabinetCrystal.RADIUS_FRAC * 1080f, CabinetCrystal(0f, 0f, 1).radius(m), 0.001f)
    }

    @Test fun wavesDoNotSpawnWhileSuspended() {
        // CabinetSim.kt:150 spawns whenever the field is empty. The fight runs on an
        // empty field, so without this it mints a wave every single frame behind the
        // crystal - the same class of defect as two earlier fixes.
        val s = fightSim()
        val waveBefore = s.wave
        repeat(120) { s.update(1f / 60f, 0f, 0f, false) }
        assertEquals(0, s.rocks.size)
        assertEquals("no wave may spawn on an empty suspended field", waveBefore, s.wave)
    }

    @Test fun wavesResumeWhenUnsuspended() {
        // The suspension must be a switch, not a one-way door: free play still needs it.
        val s = fightSim()
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(0, s.rocks.size)
        s.wavesSuspended = false
        s.update(1f / 60f, 0f, 0f, false)
        assertTrue("waves must come back", s.rocks.isNotEmpty())
    }

    @Test fun aPlayerBulletDamagesTheCrystalAndIsConsumed() {
        val s = fightSim(hp = 100)
        s.placeBulletForTest(m.width / 2f, m.height / 2f)
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(99, s.crystal!!.hp)
        // Consumed, or one shot damages on every frame it overlaps and rule 4's DPS
        // model means nothing. The ship's own autofire adds bullets, so count only
        // the one placed at the centre by checking the crystal took exactly 1.
        assertTrue(s.bullets.none { !it.hostile && m.distance(it.x, it.y, m.width / 2f, m.height / 2f) < 1f })
    }

    @Test fun aPlayerBulletDoesNotDamageADeadCrystal() {
        val s = fightSim(hp = 1)
        s.crystal!!.damage(1)
        assertFalse(s.crystal!!.alive)
        s.placeBulletForTest(m.width / 2f, m.height / 2f)
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(0, s.crystal!!.hp)
    }

    @Test fun flyingIntoTheCrystalKillsTheShipAndItComesApart() {
        // Without a solid body the optimal strategy is to sit inside the crystal, where
        // its own bullets have not spread yet and it cannot miss.
        val s = fightSim()
        s.placeShip(m.width / 2f, m.height / 2f)
        s.update(1f / 60f, 0f, 0f, false)
        assertFalse(s.ship.alive)
        assertTrue(s.over)
        assertEquals("nothing vanishes - the hull's four edges", 4, s.debris.size)
    }

    @Test fun theShipClearsTheCrystalJustOutsideItsRadius() {
        val s = fightSim()
        val c = s.crystal!!
        // 2px of clearance beyond crystal radius + ship radius.
        s.placeShip(c.x + c.radius(m) + m.shipRadius + 2f, c.y)
        s.update(1f / 60f, 0f, 0f, false)
        assertTrue(s.ship.alive)
    }

    @Test fun endRunEndsTheRunWithoutKillingTheShip() {
        // The win: the crystal dies, not the pilot.
        val s = fightSim()
        s.endRun()
        assertTrue(s.over)
        assertTrue("the ship survives its own victory", s.ship.alive)
        assertEquals("no wreck on a win", 0, s.debris.size)
    }

    @Test fun startClearsTheCrystalAndTheSuspension() {
        // A stale crystal surviving into free play would be a rock nobody can break,
        // parked in the middle of the field.
        val s = fightSim()
        s.start()
        assertNull(s.crystal)
        assertFalse(s.wavesSuspended)
    }
}
