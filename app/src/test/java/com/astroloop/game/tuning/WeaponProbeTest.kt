package com.astroloop.game.tuning

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeaponProbeTest {
    @After
    fun reset() = Tunables.resetAll()

    private fun perLevel(id: String, dup: Boolean = false) = (1..5).map { WeaponProbe.fired(id, it, dup) }

    @Test
    fun `base weapons fire what their code says at each level`() {
        assertEquals(listOf(1, 2, 3, 4, 5), perLevel("pulse_cannon"))
        assertEquals(listOf(5, 7, 9, 11, 13), perLevel("scatter_shot"))
        assertEquals(listOf(1, 2, 3, 4, 5), perLevel("homing_missiles"))
        assertEquals(listOf(2, 3, 4, 5, 6), perLevel("ion_orbiters"))
        assertEquals(listOf(1, 1, 1, 1, 1), perLevel("railgun"))
        assertEquals(listOf(1, 1, 2, 2, 3), perLevel("space_mines"))
        assertEquals(listOf(1, 2, 3, 4, 5), perLevel("solar_storm"))
        assertEquals(listOf(8, 8, 8, 8, 8), perLevel("nova_blast"))
        assertEquals(listOf(3, 4, 5, 6, 7), perLevel("needle_gun"))
        assertEquals(listOf(1, 1, 1, 1, 1), perLevel("cluster_bomb"))
        assertEquals(listOf(1, 2, 3, 4, 5), perLevel("flak_cannon"))
        assertEquals(listOf(0, 0, 0, 0, 0), perLevel("energy_saw"))
    }

    @Test
    fun `duplicator adds one to the weapons that honour it and none to the rest`() {
        for (id in listOf("pulse_cannon", "scatter_shot", "homing_missiles", "ion_orbiters", "railgun",
            "space_mines", "needle_gun", "cluster_bomb", "flak_cannon")) {
            assertEquals(id, perLevel(id).map { it + 1 }, perLevel(id, dup = true))
        }
        for (id in listOf("solar_storm", "nova_blast", "energy_saw")) {
            assertEquals(id, perLevel(id), perLevel(id, dup = true))
        }
    }

    @Test
    fun `evolutions fire their hidden level-five count`() {
        val expect = mapOf(
            "storm_cannon" to (6 to 7), "leech_burst" to (16 to 17), "siphon_needles" to (10 to 11),
            "flak_barrage" to (8 to 9), "autonomous_ace" to (5 to 6), "jackpot_mines" to (3 to 4),
            "frost_ring" to (18 to 19), "phoenix_flare" to (24 to 24), "lingering_nova" to (16 to 16),
            "hunter_killer" to (1 to 2), "warp_saw" to (0 to 0), "oblivion_beam" to (0 to 0),
        )
        for ((id, counts) in expect) {
            assertEquals(id, counts.first, WeaponProbe.fired(id, 5))
            assertEquals("$id with duplicator", counts.second, WeaponProbe.fired(id, 5, duplicator = true))
        }
    }

    @Test
    fun `evolution damage carries the level-five bonus and base weapons do not`() {
        assertEquals(27.9f, WeaponProbe.damage("storm_cannon", 5), 0.001f)
        assertEquals(69.75f, WeaponProbe.damage("autonomous_ace", 5), 0.001f)
        assertEquals(139.5f, WeaponProbe.damage("jackpot_mines", 5), 0.001f)
        assertEquals(77.5f, WeaponProbe.damage("phoenix_flare", 5), 0.001f)
        assertEquals(60f, WeaponProbe.damage("hunter_killer", 5), 0.001f)
        assertEquals(18f, WeaponProbe.damage("oblivion_beam", 5), 0.001f)
        assertEquals(15f, WeaponProbe.damage("pulse_cannon", 5), 0.001f)
        assertEquals(12f, WeaponProbe.damage("energy_saw", 5), 0.001f)
    }

    @Test
    fun `bomblets per level`() {
        assertEquals(listOf(2, 3, 4, 5, 6), (1..5).map { WeaponProbe.bomblets("cluster_bomb", it) })
        assertEquals(7, WeaponProbe.bomblets("hunter_killer", 5))
        assertNull(WeaponProbe.bomblets("pulse_cannon", 1))
    }

    @Test
    fun `the probe reads the live knobs`() {
        Tunables.set(KnobsWeapons.stormCannon.count!!, 5f)
        assertEquals(8, WeaponProbe.fired("storm_cannon", 5))
        Tunables.set(KnobsPassives.duplicatorExtraProjectiles, 3f)
        assertEquals(11, WeaponProbe.fired("storm_cannon", 5, duplicator = true))
    }

    @Test
    fun `a repeated question is answered from memory`() {
        val first = WeaponProbe.fired("scatter_shot", 3, duplicator = true)
        val runs = WeaponProbe.probeRuns
        repeat(5) { assertEquals(first, WeaponProbe.fired("scatter_shot", 3, duplicator = true)) }
        assertEquals(runs, WeaponProbe.probeRuns)
        WeaponProbe.fired("scatter_shot", 3, duplicator = false)
        assertEquals("a different key is measured", runs + 1, WeaponProbe.probeRuns)
    }

    @Test
    fun `a knob edit forgets what was remembered`() {
        assertEquals(6, WeaponProbe.fired("storm_cannon", 5))
        assertEquals(27.9f, WeaponProbe.damage("storm_cannon", 5), 0.001f)
        assertEquals(6, WeaponProbe.bomblets("cluster_bomb", 5))
        val runs = WeaponProbe.probeRuns
        Tunables.set(KnobsWeapons.stormCannon.count!!, 5f)
        assertEquals(8, WeaponProbe.fired("storm_cannon", 5))
        assertEquals(runs + 1, WeaponProbe.probeRuns)
        Tunables.resetAll()
        assertEquals(6, WeaponProbe.fired("storm_cannon", 5))
    }

    @Test
    fun `evolution ids are known`() {
        assertTrue(WeaponProbe.isEvolution("storm_cannon"))
        assertFalse(WeaponProbe.isEvolution("pulse_cannon"))
    }
}
