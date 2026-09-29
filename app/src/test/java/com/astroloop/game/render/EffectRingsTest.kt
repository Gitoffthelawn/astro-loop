package com.astroloop.game.render

import com.astroloop.game.core.GameState
import com.astroloop.game.data.PilotDefinitions
import com.astroloop.game.data.ShipDefinitions
import com.astroloop.game.entity.Boss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The effect rings' logic. Plain JVM on purpose — EffectRings imports no Android types, exactly
 * as HudBand does not, which is what lets the whole rule be tested without a Canvas.
 */
class EffectRingsTest {

    private fun colorOf(passiveId: String) =
        PilotDefinitions.pilots.first { it.startingPassiveId == passiveId }.color

    @Test
    fun `a bare ship has no rings`() {
        val state = GameState()
        state.recalculateStats()
        assertTrue(EffectRings.ringsFor(state).isEmpty())
    }

    @Test
    fun `each passive contributes its own ring only when held`() {
        val state = GameState()
        state.addPassive("magnet_field")
        state.recalculateStats()
        val ids = EffectRings.ringsFor(state).map { it.id }
        assertEquals(listOf("magnet_field"), ids)
    }

    @Test
    fun `rings take the colour of the pilot who owns the passive`() {
        val state = GameState()
        state.addPassive("vampiric_core")
        state.recalculateStats()
        val ring = EffectRings.ringsFor(state).single()
        assertEquals(colorOf("vampiric_core"), ring.color)
    }

    @Test
    fun `nova blast rings from its weapon colour, not a pilot colour`() {
        val state = GameState()
        state.weaponLevels["nova_blast"] = 3
        state.recalculateStats()
        val ring = EffectRings.ringsFor(state).single()
        assertEquals("nova_blast", ring.id)
        assertEquals(ShipDefinitions.getWeaponColor("nova_blast", false), ring.color)
        assertEquals(281f, ring.radius, 0.001f)
    }

    @Test
    fun `the corruption run turns the passive rings red`() {
        val state = GameState()
        state.isCorruptionRun = true
        state.addPassive("cryo_field")
        state.addPassive("magnet_field")
        state.addPassive("vampiric_core")
        state.recalculateStats()
        for (ring in EffectRings.ringsFor(state)) {
            assertEquals("${ring.id} must be corrupted red", Boss.CORRUPTION_COLOR, ring.color)
        }
    }

    @Test
    fun `vampiric ring does not grow with stacks`() {
        val state = GameState()
        state.addPassive("vampiric_core")
        state.recalculateStats()
        val one = EffectRings.ringsFor(state).single().radius
        repeat(4) { state.addPassive("vampiric_core") }
        state.recalculateStats()
        assertEquals(one, EffectRings.ringsFor(state).single().radius, 0.001f)
    }

    private fun ring(id: String, radius: Float) = EffectRings.Ring(id, radius, 0xFF000000.toInt())

    @Test
    fun `exactly coincident rings merge`() {
        // cryo at 4 stacks and magnet at 5 both land on 200.
        val groups = EffectRings.group(listOf(ring("cryo_field", 200f), ring("magnet_field", 200f)))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun `rings a few pixels apart merge`() {
        // vampiric 100 against magnet at 1 stack, 104.
        val groups = EffectRings.group(listOf(ring("magnet_field", 104f), ring("vampiric_core", 100f)))
        assertEquals(1, groups.size)
    }

    @Test
    fun `rings far apart stay separate`() {
        val groups = EffectRings.group(listOf(ring("vampiric_core", 100f), ring("cryo_field", 200f)))
        assertEquals(2, groups.size)
        assertEquals(1, groups[0].size)
        assertEquals(1, groups[1].size)
    }

    @Test
    fun `three rings can form one group`() {
        // cryo 175, magnet 176, nova 180 — all inside the threshold of the group minimum.
        val groups = EffectRings.group(
            listOf(ring("nova_blast", 180f), ring("cryo_field", 175f), ring("magnet_field", 176f))
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].size)
    }

    @Test
    fun `a chain of close rings does not merge without bound`() {
        // Each ring is 5px from the next — inside MERGE_THRESHOLD — but the span from the
        // group's smallest radius is 15px. A previous-member comparison would chain all four
        // into one arbitrarily wide group; measuring against the group minimum splits them.
        // This is the one case that tells the two rules apart, so it is the regression guard
        // for the whole function.
        val groups = EffectRings.group(
            listOf(ring("a", 100f), ring("b", 105f), ring("c", 110f), ring("d", 115f))
        )
        assertEquals("must split rather than chain", 2, groups.size)
        assertEquals(listOf(100f, 105f), groups[0].map { it.radius })
        assertEquals(listOf(110f, 115f), groups[1].map { it.radius })
    }

    @Test
    fun `groups come back sorted by radius`() {
        val groups = EffectRings.group(
            listOf(ring("nova_blast", 351f), ring("vampiric_core", 100f), ring("cryo_field", 225f))
        )
        assertEquals(listOf(100f, 225f, 351f), groups.map { it[0].radius })
    }

    @Test
    fun `an empty ring list produces no groups`() {
        assertTrue(EffectRings.group(emptyList()).isEmpty())
    }

    @Test
    fun `a solo ring draws as one full circle`() {
        val arcs = EffectRings.arcsFor(listOf(ring("cryo_field", 150f)))
        assertEquals(1, arcs.size)
        assertEquals(360f, arcs[0].sweepDegrees, 0.001f)
        assertEquals(150f, arcs[0].radius, 0.001f)
    }

    @Test
    fun `a merged pair draws as six alternating arcs`() {
        val a = EffectRings.Ring("cryo_field", 200f, 0x11111111)
        val b = EffectRings.Ring("magnet_field", 200f, 0x22222222)
        val arcs = EffectRings.arcsFor(listOf(a, b))
        assertEquals(6, arcs.size)
        // Owners alternate rather than clustering: a, b, a, b, a, b
        assertEquals(listOf(0x11111111, 0x22222222, 0x11111111, 0x22222222, 0x11111111, 0x22222222),
            arcs.map { it.color })
    }

    @Test
    fun `a merged triple draws nine arcs`() {
        val arcs = EffectRings.arcsFor(
            listOf(ring("cryo_field", 175f), ring("magnet_field", 176f), ring("nova_blast", 180f))
        )
        assertEquals(9, arcs.size)
    }

    @Test
    fun `each arc keeps its own ring's true radius`() {
        val arcs = EffectRings.arcsFor(
            listOf(EffectRings.Ring("vampiric_core", 100f, 1), EffectRings.Ring("magnet_field", 104f, 2))
        )
        // No arc is displaced to a compromise radius — every one is 100 or 104 exactly.
        assertTrue(arcs.all { it.radius == 100f || it.radius == 104f })
        assertTrue(arcs.any { it.radius == 100f })
        assertTrue(arcs.any { it.radius == 104f })
    }

    @Test
    fun `arcs leave a gap so the alternation reads`() {
        val arcs = EffectRings.arcsFor(listOf(ring("a", 200f), ring("b", 200f)))
        val step = 360f / 6f
        for (arc in arcs) {
            assertTrue("sweep ${arc.sweepDegrees} must be short of the full step", arc.sweepDegrees < step)
        }
    }

    @Test
    fun `an empty group produces no arcs`() {
        assertTrue(EffectRings.arcsFor(emptyList()).isEmpty())
    }

    @Test
    fun `the nova evolution keeps a ring rather than blinking out`() {
        val state = GameState()
        state.weaponLevels["nova_blast"] = 5
        state.recalculateStats()
        val before = EffectRings.ringsFor(state).single()

        state.replaceWeapon("nova_blast", "lingering_nova", 5)
        state.recalculateStats()
        val after = EffectRings.ringsFor(state).single()

        assertEquals("lingering_nova", after.id)
        assertEquals(
            ShipDefinitions.getEvolutionColor("nova_blast", false), after.color
        )
        // The evolution reaches further — the ring must not shrink on evolving.
        assertTrue(
            "evolved reach ${after.radius} should exceed base ${before.radius}",
            after.radius > before.radius
        )
    }

    @Test
    fun `an unknown passive falls back instead of throwing`() {
        // pilotColorFor must not raise on a renamed id: GameThread swallows Throwables per
        // frame, so a throw here would silently blank the frame forever rather than crash.
        assertEquals(0xFFFFFFFF.toInt(), EffectRings.pilotColorFor("no_such_passive"))
    }

    @Test
    fun `alpha pulses within the faint band and scales with the fade`() {
        for (t in 0..40) {
            val a = EffectRings.alpha(t / 4f, 1f)
            assertTrue("alpha $a out of band", a >= 0.09f && a <= 0.21f)
        }
        assertEquals(0f, EffectRings.alpha(0f, 0f), 0.001f)
        assertTrue(EffectRings.alpha(0f, 0.5f) < EffectRings.alpha(0f, 1f))
    }
}
