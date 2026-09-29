package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ReckoningOpeningTest {

    private val m = CabinetMetrics(1080f, 2400f)

    private fun sim(seed: Int = 5) = CabinetSim(m, Random(seed)).also { it.start() }

    private fun run(o: ReckoningOpening, s: CabinetSim, seconds: Float) {
        val dt = 1f / 60f
        var t = 0f
        while (t < seconds) { o.update(dt, s); s.update(dt, 0f, 0f, false); t += dt }
    }

    @Test fun itBeginsInNormalPlay() {
        val s = sim()
        val o = ReckoningOpening(m)
        o.begin(s)
        assertEquals(ReckoningOpening.Stage.PLAY, o.stage)
        assertNull("no crystal until the field has been eaten", s.crystal)
    }

    @Test fun theSeededFieldIsTheSameEveryTime() {
        // The whole point of a design decision. A live run would put a different number of
        // bodies into the crystal every time, so the entrance would look different -
        // and better or worse - depending on how the player had been flying.
        //
        // Different seeds on purpose: begin() never touches rng (only shape/rotation/spin
        // do), so the positions must match even when the two sims would draw differently
        // for anything that did consult it. Matching seeds could not tell "rng-independent"
        // apart from "the draws happened to coincide".
        val a = sim(5); val b = sim(9)
        ReckoningOpening(m).begin(a)
        ReckoningOpening(m).begin(b)
        assertEquals(ReckoningOpening.SEED_ROCKS, a.rocks.size)
        assertEquals(a.rocks.size, b.rocks.size)
        for (i in a.rocks.indices) {
            assertEquals(a.rocks[i].x, b.rocks[i].x, 0.001f)
            assertEquals(a.rocks[i].y, b.rocks[i].y, 0.001f)
            assertEquals(a.rocks[i].vx, b.rocks[i].vx, 0.001f)
        }
    }

    @Test fun theSeededRocksClearTheShip() {
        // The same fairness rule the polish pass added for wave spawns: a rock
        // materialising on the player is not an opening, it is an ambush.
        val s = sim()
        ReckoningOpening(m).begin(s)
        val safe = CabinetSim.SPAWN_SAFE_RADIUS_FRAC * m.minEdge
        for (r in s.rocks) {
            assertTrue(
                "seeded rock at ${r.x},${r.y} is only ${m.distance(r.x, r.y, s.ship.x, s.ship.y)}px away",
                m.distance(r.x, r.y, s.ship.x, s.ship.y) >= safe
            )
        }
    }

    @Test fun wavesAreSuspendedThroughout() {
        // Otherwise clearing the seeded field mints wave 2 and the opening never ends.
        val s = sim()
        val o = ReckoningOpening(m)
        o.begin(s)
        assertTrue(s.wavesSuspended)
        run(o, s, 2f)
        assertTrue(s.wavesSuspended)
    }

    @Test fun crystallisationStartsAfterTheAuthoredStretch() {
        val s = sim()
        val o = ReckoningOpening(m)
        o.begin(s)
        run(o, s, ReckoningOpening.OPENING_SECONDS - 0.5f)
        assertEquals(ReckoningOpening.Stage.PLAY, o.stage)
        run(o, s, 1f)
        assertEquals(ReckoningOpening.Stage.ARRIVAL, o.stage)
    }

    @Test fun theFieldFreezesWhileTheCrystalCrosses() {
        // REPLACES "the rocks fly inward rather than vanishing". A design decision: the rocks no
        // longer implode into a forming crystal — they hold still while the thing that is
        // coming for them crosses the field, which is what makes the crystal the only
        // moving object in its own entrance.
        val s = sim()
        val o = ReckoningOpening(m)
        o.begin(s)
        run(o, s, ReckoningOpening.OPENING_SECONDS + 0.1f)
        assertEquals(ReckoningOpening.Stage.ARRIVAL, o.stage)
        assertTrue("the rocks are still there", s.rocks.isNotEmpty())

        val before = s.rocks.map { it.x to it.y }
        run(o, s, 1.5f)
        val after = s.rocks.map { it.x to it.y }
        assertEquals("no rock may drift while the crystal is on its way", before, after)
    }

    @Test fun theRocksDieWithTheLandingRatherThanVanishing() {
        // Nothing vanishes, which the implosion used to satisfy by having them eaten. They are
        // still not removed and replaced: shatterField gives every one of them its own
        // edges to come apart into, and those outlive the frame the crystal lands on.
        val s = sim()
        val o = ReckoningOpening(m)
        o.begin(s)
        run(o, s, ReckoningOpening.OPENING_SECONDS + 0.1f)
        assertTrue(s.rocks.isNotEmpty())

        run(o, s, ReckoningOpening.ARRIVAL_SECONDS + 0.1f)
        assertEquals(ReckoningOpening.Stage.FIGHT, o.stage)
        assertTrue("every rock must be gone", s.rocks.isEmpty())
        assertTrue("and must have left something behind", s.debris.isNotEmpty())
    }

    @Test fun theCrystalCrossesFromTheStripToTheCentre() {
        // The entrance is drawn from these, so if they stop moving the crystal appears at
        // the centre exactly as it used to and the whole rework is invisible.
        val s = sim()
        val o = ReckoningOpening(m)
        o.begin(s)
        run(o, s, ReckoningOpening.OPENING_SECONDS + 0.1f)
        val startY = o.arrivalY()
        assertTrue("it starts up in the strip", startY < m.height * 0.1f)

        run(o, s, ReckoningOpening.ARRIVAL_SECONDS * 0.5f)
        val midY = o.arrivalY()
        assertTrue("and travels down the screen", midY > startY)
        assertEquals("without wandering sideways", m.width / 2f, o.arrivalX(), 0.001f)
    }

    @Test fun theFightBeginsOnceTheFieldIsEaten() {
        val s = sim()
        val o = ReckoningOpening(m)
        o.begin(s)
        run(o, s, ReckoningOpening.OPENING_SECONDS + ReckoningOpening.ARRIVAL_SECONDS + 1f)
        assertEquals(ReckoningOpening.Stage.FIGHT, o.stage)
        assertEquals(0, s.rocks.size)
        assertNotNull("the crystal exists once it has a body", s.crystal)
        assertEquals(ReckoningOpening.CRYSTAL_HP, s.crystal!!.maxHp)
    }

    @Test fun theEntranceCannotHang() {
        // It used to be able to. The implosion ended when the last rock had been eaten, so
        // one the pull could not reach - clipped by a seam, or flung outward - would have
        // stranded the player in an entrance that never finished, and a timeout was the
        // guarantee against it. A design decision's crossing runs on a clock and simply ends, so
        // the hang is now unreachable by construction. Kept because that is worth pinning.
        val s = sim()
        val o = ReckoningOpening(m)
        o.begin(s)
        // Fling one rock hard outward so the pull has real work to do.
        s.placeRock(RockSize.LARGE, 40f, 40f, -4000f, -4000f)
        run(o, s, ReckoningOpening.OPENING_SECONDS + ReckoningOpening.ARRIVAL_SECONDS + 0.5f)
        assertEquals(ReckoningOpening.Stage.FIGHT, o.stage)
        assertEquals(0, s.rocks.size)
    }


    @Test fun theShipSurvivesTheEntrance() {
        // The crystal appears where the rocks converge. If the player happens to be
        // sitting there it must not be an instant unavoidable death - the crystal is
        // placed only once the field is clear, and the ship starts at centre.
        val s = sim()
        val o = ReckoningOpening(m)
        o.begin(s)
        run(o, s, ReckoningOpening.OPENING_SECONDS - 0.2f)
        assertTrue("nothing may kill the player during the authored stretch", s.ship.alive)
    }

    @Test fun theCrystalShovesThePlayerClearAsItForms() {
        // CabinetShip.reset() parks the ship at exactly the field centre, which is where
        // the crystal forms. Without this the entrance is an unavoidable death for a
        // player who happened to be sitting there - the same defect the polish pass fixed
        // for wave spawns, arriving by a different door.
        val s = sim()
        val o = ReckoningOpening(m)
        o.begin(s)
        s.placeShip(m.width / 2f, m.height / 2f)
        run(o, s, ReckoningOpening.OPENING_SECONDS + ReckoningOpening.ARRIVAL_SECONDS + 1f)
        val c = s.crystal!!
        assertTrue("the player must not be inside the thing they are fighting",
            m.distance(s.ship.x, s.ship.y, c.x, c.y) > c.radius(m) + m.shipRadius)
        assertTrue("and must be alive to fight it", s.ship.alive)
    }

    @Test fun aPlayerAlreadyClearIsNotMoved() {
        // A nudge, not a teleport. The ship must be moved BEFORE the transition, because
        // formCrystal runs exactly once — on CRYSTALLISING -> FIGHT. Repositioning
        // afterwards and calling update() again tests nothing at all: that call hits
        // Stage.FIGHT -> Unit and returns.
        val s = sim()
        val o = ReckoningOpening(m)
        o.begin(s)
        run(o, s, ReckoningOpening.OPENING_SECONDS + 0.1f)
        assertEquals(ReckoningOpening.Stage.ARRIVAL, o.stage)
        val far = m.height * 0.9f
        s.placeShip(m.width / 2f, far)
        run(o, s, ReckoningOpening.ARRIVAL_SECONDS + 0.5f)
        assertEquals(ReckoningOpening.Stage.FIGHT, o.stage)
        assertEquals("a player already clear must be left where they were", far, s.ship.y, 1f)
    }


    @Test fun anAbsorbedRockStillLeavesSomethingBehind() {
        // Nothing vanishes, carried over from the implosion this replaced. Every rock on the
        // field when the crystal lands has to be SEEN to go — shatterField gives each one
        // its own edges rather than removing it, which is the same exit a rock destroyed
        // by the player already gets.
        val s = sim()
        s.clearRocksForTest()
        s.wavesSuspended = true
        s.placeRock(RockSize.SMALL, 540f, 1220f, 0f, 0f)
        assertTrue("debris must be empty beforehand", s.debris.isEmpty())
        s.shatterField()
        assertEquals("the rock must be gone", 0, s.rocks.size)
        assertTrue(
            "a rock cleared by the landing must leave debris, not simply disappear",
            s.debris.isNotEmpty()
        )
    }
}
