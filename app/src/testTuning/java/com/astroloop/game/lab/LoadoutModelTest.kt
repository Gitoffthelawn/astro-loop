package com.astroloop.game.lab

import org.junit.Assert.*
import org.junit.Test

class LoadoutModelTest {
    private val base = LoadoutModel.DEFAULT

    @Test
    fun `an evolution replaces its base weapon and back`() {
        val evo = LoadoutModel.withWeapon(base, "storm_cannon", 1)
        assertEquals(listOf("storm_cannon" to 5), evo.weapons)
        val back = LoadoutModel.withWeapon(evo, "pulse_cannon", 3)
        assertEquals(listOf("pulse_cannon" to 3), back.weapons)
    }

    @Test
    fun `replacing keeps list order and a re-add updates in place`() {
        var l = LoadoutModel.withWeapon(base, "railgun", 2)
        l = LoadoutModel.withWeapon(l, "needle_gun", 1)
        val evo = LoadoutModel.withWeapon(l, "storm_cannon", 1)
        assertEquals(listOf("storm_cannon" to 5, "railgun" to 2, "needle_gun" to 1), evo.weapons)
        assertEquals(listOf("pulse_cannon" to 1, "railgun" to 4, "needle_gun" to 1), LoadoutModel.withWeapon(l, "railgun", 4).weapons)
    }

    @Test
    fun `weapon slots follow Weapon Expansion`() {
        var l = base
        for (id in listOf("railgun", "needle_gun", "flak_cannon", "scatter_shot")) l = LoadoutModel.withWeapon(l, id, 1)
        assertEquals(4, l.weapons.size)                               // pulse + 3; the 4th add was refused
        assertEquals(4, LoadoutModel.maxWeapons(l))
        l = LoadoutModel.withPassive(l, "extra_weapon_slot", 1)
        assertEquals(5, LoadoutModel.maxWeapons(l))
        assertEquals(5, LoadoutModel.withWeapon(l, "scatter_shot", 1).weapons.size)
    }

    @Test
    fun `removing Weapon Expansion trims weapons to the smaller cap`() {
        var l = base
        for (id in listOf("railgun", "needle_gun", "flak_cannon", "scatter_shot")) l = LoadoutModel.withWeapon(l, id, 1)
        l = LoadoutModel.withPassive(l, "extra_weapon_slot", 1)
        l = LoadoutModel.withWeapon(l, "scatter_shot", 1)
        assertEquals(5, l.weapons.size)
        val first4 = l.weapons.take(4)
        val r = LoadoutModel.withoutPassive(l, "extra_weapon_slot")
        assertEquals(first4, r.weapons)
    }

    @Test
    fun `passive slots shrink with Weapon Expansion`() {
        assertEquals(4, LoadoutModel.maxPassives(base))
        assertEquals(3, LoadoutModel.maxPassives(LoadoutModel.withPassive(base, "extra_weapon_slot", 1)))
    }

    @Test
    fun `Weapon Expansion takes no passive slot itself, as in the game`() {
        var l = base                                   // tb26
        l = LoadoutModel.withPassive(l, "glass_cannon", 1)
        l = LoadoutModel.withPassive(l, "extra_weapon_slot", 1)
        assertEquals(2, LoadoutModel.passiveCount(l))
        l = LoadoutModel.withPassive(l, "lucky_star", 1)
        assertEquals(3, LoadoutModel.passiveCount(l))
        assertEquals(3, LoadoutModel.withPassive(l, "duplicator_core", 1).passives.count { it.first != "extra_weapon_slot" })
        // a full four-passive loadout cannot take Weapon Expansion (the game offers it no slot)
        var full = base
        for (id in listOf("glass_cannon", "lucky_star", "duplicator_core")) full = LoadoutModel.withPassive(full, id, 1)
        assertEquals(4, LoadoutModel.passiveCount(full))
        assertEquals(full, LoadoutModel.withPassive(full, "extra_weapon_slot", 1))
    }

    @Test
    fun `instant-max passives are always full and levels clamp`() {
        val l = LoadoutModel.withPassive(base, "glass_cannon", 1)
        assertTrue("glass_cannon" to 5 in l.passives)
        assertEquals(listOf("pulse_cannon" to 5), LoadoutModel.withWeapon(base, "pulse_cannon", 9).weapons)
    }

    @Test
    fun `save and load round trip, garbage loads the default`() {
        val kv = MapKeyValue()
        val l = LoadoutModel.withWeapon(base, "railgun", 5)
        LoadoutModel.save(kv, l)
        assertEquals(l, LoadoutModel.load(kv))
        kv.put(LoadoutModel.KEY, "garbage")
        assertEquals(LoadoutModel.DEFAULT, LoadoutModel.load(kv))
    }
}
