package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CabinetHostileBulletTest {

    private val m = CabinetMetrics(1080f, 2400f)

    private fun sim() = CabinetSim(m, Random(31)).also { it.start() }

    @Test fun aPlayerBulletStillWraps() {
        // The reversal this task must NOT cause: the player's gun is unchanged.
        val b = CabinetBullet(5f, 100f, -600f, 0f, 1.2f)
        b.update(1f / 60f, m)
        assertTrue("player bullet must wrap to the far edge, was ${b.x}", b.x > m.width - 20f)
        assertFalse(b.escaped)
    }

    @Test fun aHostileBulletDoesNotWrap() {
        // The spec: crystal bullets do not wrap. It bounds their lifetime to one playfield crossing
        // and reads in-fiction as the crystal not obeying the cabinet's rules.
        val b = CabinetBullet(5f, 100f, -600f, 0f, 1.2f, hostile = true)
        b.update(1f / 60f, m)
        assertTrue("hostile bullet must keep going negative, was ${b.x}", b.x < 0f)
    }

    @Test fun aHostileBulletEscapesWhenItLeavesTheField() {
        val b = CabinetBullet(5f, 100f, -600f, 0f, 999f, hostile = true)
        assertFalse(b.escaped)
        b.update(1f / 60f, m)
        assertTrue(b.escaped)
    }

    @Test fun aHostileBulletInsideTheFieldHasNotEscaped() {
        val b = CabinetBullet(540f, 1200f, 0f, 60f, 999f, hostile = true)
        repeat(10) { b.update(1f / 60f, m) }
        assertFalse(b.escaped)
    }

    @Test fun theCrystalBulletHitboxIsHalfTheShipRadius() {
        // A design decision. Derived from SHIP_RADIUS_FRAC rather than restated, so the two
        // cannot drift if the ship is ever resized.
        assertEquals(m.shipRadius * 0.5f, m.crystalBulletHitRadius, 0.001f)
        assertEquals(12.42f, m.crystalBulletHitRadius, 0.01f)
    }

    @Test fun aHostileBulletKillsTheShipAtTheSmallerRadius() {
        val s = sim()
        s.clearRocksForTest()
        val ship = s.ship
        // Just inside the combined radius: 12.42 + 5.40 = 17.82px.
        s.addHostileBullet(CabinetBullet(ship.x + 17f, ship.y, 0f, 0f, 999f, hostile = true))
        s.update(1f / 60f, 0f, 0f, false)
        assertFalse(s.ship.alive)
        assertTrue(s.over)
    }

    @Test fun aHostileBulletMissesJustOutsideTheSmallerRadius() {
        val s = sim()
        s.clearRocksForTest()
        val ship = s.ship
        // 20px out: inside the OLD 1.0r hitbox (24.84 + 5.40 = 30.24) and outside the new
        // one. This test is the whole point of a design decision — it fails if the hitbox is
        // ever quietly restored to the rock radius.
        s.addHostileBullet(CabinetBullet(ship.x + 20f, ship.y, 0f, 0f, 999f, hostile = true))
        s.update(1f / 60f, 0f, 0f, false)
        assertTrue("20px must clear the 17.82px bullet hitbox", s.ship.alive)
    }

    @Test fun aShipKilledByABulletStillComesApart() {
        // Nothing vanishes. shatterShip() was reachable only from the rock path; a bullet
        // death takes a different route to `over`. This is the exact shape of the
        // Critical the final whole-branch review caught, where the wreck existed but
        // was never animated.
        val s = sim()
        s.clearRocksForTest()
        s.addHostileBullet(CabinetBullet(s.ship.x, s.ship.y, 0f, 0f, 999f, hostile = true))
        s.update(1f / 60f, 0f, 0f, false)
        assertFalse(s.ship.alive)
        assertEquals("the hull's four edges must become debris", 4, s.debris.size)
    }

    @Test fun anEscapedHostileBulletIsRemoved() {
        val s = sim()
        s.clearRocksForTest()
        s.addHostileBullet(CabinetBullet(10f, 1200f, -600f, 0f, 999f, hostile = true))
        assertEquals(1, s.bullets.count { it.hostile })
        s.update(1f / 60f, 0f, 0f, false)
        assertEquals(0, s.bullets.count { it.hostile })
    }

    @Test fun collisionIsToroidalEvenThoughMotionIsNot() {
        // The ship is DRAWN at every wrapped position (CabinetRenderer.forEachWrap), so a
        // ship at the right edge is visible at the left one. Toroidal distance is what
        // matches the picture, even though the bullet itself never wraps.
        val s = sim()
        s.clearRocksForTest()
        s.placeShip(m.width - 5f, 1200f)
        s.addHostileBullet(CabinetBullet(8f, 1200f, 0f, 0f, 999f, hostile = true))
        s.update(1f / 60f, 0f, 0f, false)
        assertFalse("13px apart across the seam must be a hit", s.ship.alive)
    }
}
