package com.astroloop.game.tuning

import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.GameState
import com.astroloop.game.data.PassiveDefinitions
import com.astroloop.game.system.WeaponSystem
import com.astroloop.game.weapon.WeaponFactory

/**
 * Replaces a fresh run's weapons and passives with [RunLoadout]'s, sets the starting minute, and
 * lifts the evolution time gate. Passives go first, because Weapon Expansion changes how many
 * weapon slots there are.
 */
object LoadoutApplier {

    fun apply(loadout: RunLoadout, state: GameState, weaponSystem: WeaponSystem) {
        state.passiveStacks.clear()
        // Same rules as GameState.addPassive: real passives only, Astro's drone is tb26, and the
        // instant-max passives are always full.
        var upgradesCollected = 0
        for ((rawId, stacks) in loadout.passives) {
            val id = state.resolvePassiveId(rawId)
            if (PassiveDefinitions.getPassiveDef(id) == null) continue
            if (id in GameState.INSTANT_MAX_PASSIVES) {
                state.passiveStacks[id] = GameConfig.PASSIVE_MAX_STACKS
                upgradesCollected += 1                       // one pickup in normal play
            } else {
                val n = stacks.coerceIn(1, GameConfig.PASSIVE_MAX_STACKS)
                state.passiveStacks[id] = n
                upgradesCollected += n
            }
        }
        // Normal play counts only addPassive calls (weapon level-ups and evolutions do not touch
        // totalUpgradesCollected), and enemy scaling reads it, so mirror exactly that.
        state.totalUpgradesCollected = upgradesCollected
        state.recalculateStats()

        weaponSystem.reset()
        state.weaponLevels.clear()
        val evolutions = WeaponFactory.getEvolutionIds().toSet()
        for ((id, level) in loadout.weapons) {
            if (state.weaponLevels.size >= state.getMaxWeaponSlots()) break
            if (!weaponSystem.addWeapon(id, state)) continue          // unknown id
            if (id in evolutions) {
                weaponSystem.getWeapon(id)!!.level = GameConfig.WEAPON_MAX_LEVEL
                state.weaponLevels[id] = GameConfig.WEAPON_MAX_LEVEL
                state.addEvolution(id)
                state.hasEvolvedThisGame = true
                state.astroLoopEvolutionUsed = true
            } else {
                repeat(level.coerceIn(1, GameConfig.WEAPON_MAX_LEVEL) - 1) { weaponSystem.addWeapon(id, state) }
            }
        }

        state.survivalTime = loadout.startMinute * 60f
        state.evolutionTimeGateSeconds = 0f
        state.recalculateStats()
    }
}
