package com.astroloop.game.weapon

import com.astroloop.game.weapon.weapons.NovaBlast
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Nova Blast's reach, exposed so the effect ring can draw the same number the weapon fires with.
 * A ring that disagrees with the blast is worse than no ring at all.
 */
class NovaBlastRadiusTest {

    @Test
    fun `each level has its shipped radius`() {
        assertEquals(180f, NovaBlast.blastRadiusFor(1, 1f), 0.001f)
        assertEquals(234f, NovaBlast.blastRadiusFor(2, 1f), 0.001f)
        assertEquals(281f, NovaBlast.blastRadiusFor(3, 1f), 0.001f)
        assertEquals(316f, NovaBlast.blastRadiusFor(4, 1f), 0.001f)
        assertEquals(351f, NovaBlast.blastRadiusFor(5, 1f), 0.001f)
    }

    @Test
    fun `area multiplier scales the radius`() {
        assertEquals(360f, NovaBlast.blastRadiusFor(1, 2f), 0.001f)
        assertEquals(90f, NovaBlast.blastRadiusFor(1, 0.5f), 0.001f)
    }

    @Test
    fun `levels past the table clamp to the top rung, as the shipped when did`() {
        assertEquals(351f, NovaBlast.blastRadiusFor(6, 1f), 0.001f)
        assertEquals(351f, NovaBlast.blastRadiusFor(99, 1f), 0.001f)
    }
}
