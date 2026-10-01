package com.astroloop.game.lab

import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.GameState
import com.astroloop.game.data.WeaponDefinitions
import com.astroloop.game.tuning.RunLoadout

/** The rules a Lab loadout follows: the game's own, so every loadout is one normal play could reach. */
object LoadoutModel {
    const val KEY = "lab_loadout"
    private const val EXTRA_SLOT = "extra_weapon_slot"
    val DEFAULT = RunLoadout("ship_blue", "pilot_astro", listOf("pulse_cannon" to 1), listOf("tb26" to 1), 0)

    private val evolutionOf: Map<String, String> =
        WeaponDefinitions.weapons.mapNotNull { w -> w.evolutionWeaponId?.let { w.id to it } }.toMap()
    private val baseOf: Map<String, String> = evolutionOf.entries.associate { (b, e) -> e to b }

    fun load(kv: KeyValue): RunLoadout = kv.get(KEY)?.let { RunLoadout.decode(it) } ?: DEFAULT
    fun save(kv: KeyValue, l: RunLoadout) = kv.put(KEY, l.encode())

    private fun hasExtraSlot(l: RunLoadout) = l.passives.any { it.first == EXTRA_SLOT }
    fun maxWeapons(l: RunLoadout): Int = GameConfig.MAX_WEAPON_SLOTS + if (hasExtraSlot(l)) 1 else 0
    fun maxPassives(l: RunLoadout): Int =
        if (hasExtraSlot(l)) GameConfig.PASSIVE_SLOTS_WITH_EXTRA_WEAPON else GameConfig.MAX_PASSIVE_SLOTS

    /** Passives that fill a slot: Weapon Expansion itself does not, as in the game. */
    fun passiveCount(l: RunLoadout): Int = l.passives.count { it.first != EXTRA_SLOT }

    fun withWeapon(l: RunLoadout, id: String, level: Int): RunLoadout {
        val isEvolution = id in baseOf
        val lvl = if (isEvolution) GameConfig.WEAPON_MAX_LEVEL else level.coerceIn(1, GameConfig.WEAPON_MAX_LEVEL)
        val conflicting = setOfNotNull(baseOf[id], evolutionOf[id])
        val kept = l.weapons.filter { it.first != id && it.first !in conflicting }
        val replacing = kept.size < l.weapons.size
        if (!replacing && kept.size >= maxWeapons(l)) return l
        val index = l.weapons.indexOfFirst { it.first == id || it.first in conflicting }
        val list = kept.toMutableList()
        if (index >= 0) list.add(index.coerceAtMost(list.size), id to lvl) else list.add(id to lvl)
        return l.copy(weapons = list)
    }

    fun withoutWeapon(l: RunLoadout, id: String): RunLoadout = l.copy(weapons = l.weapons.filter { it.first != id })

    fun withPassive(l: RunLoadout, id: String, stacks: Int): RunLoadout {
        val n = if (id in GameState.INSTANT_MAX_PASSIVES) GameConfig.PASSIVE_MAX_STACKS
                else stacks.coerceIn(1, GameConfig.PASSIVE_MAX_STACKS)
        val existing = l.passives.indexOfFirst { it.first == id }
        // The game checks the slot before the pickup, against the loadout as it stands.
        if (existing < 0 && passiveCount(l) >= maxPassives(l)) return l
        val list = l.passives.toMutableList()
        if (existing >= 0) list[existing] = id to n else list.add(id to n)
        return l.copy(passives = list)
    }

    fun withoutPassive(l: RunLoadout, id: String): RunLoadout {
        val r = l.copy(passives = l.passives.filter { it.first != id })
        // Losing Weapon Expansion lowers the weapon cap; trim from the end so the launch never drops one silently.
        return if (id == EXTRA_SLOT) r.copy(weapons = r.weapons.take(maxWeapons(r))) else r
    }
}
