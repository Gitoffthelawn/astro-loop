package com.astroloop.game.system

import com.astroloop.game.core.BeatClock
import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.GameState
import com.astroloop.game.entity.*
import com.astroloop.game.weapon.Weapon
import com.astroloop.game.weapon.WeaponFactory
import com.astroloop.game.weapon.weapons.FrostRing
import com.astroloop.game.weapon.weapons.IonOrbiters
import com.astroloop.game.weapon.weapons.LingeringNova
import com.astroloop.game.weapon.weapons.SpaceMines
import com.astroloop.game.core.SoundManager

class WeaponSystem(
    private val projectilePool: EntityPool<Projectile>
) {
    private val activeWeapons = mutableMapOf<String, Weapon>()

    /** Wall clock the beat grid is measured against. Tests replace it with a fake clock. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    fun update(
        deltaTime: Float,
        ship: Ship,
        state: GameState,
        targets: List<Entity>,
        asteroids: List<Asteroid> = emptyList()
    ) {
        // Update all weapon cooldowns (Revenge Protocol: 2x fire rate = 2x delta while active)
        activeWeapons.values.forEach { weapon ->
            val isSaw = weapon.id == "energy_saw" || weapon.id == "warp_saw" || weapon.id == "oblivion_beam"
            val effectiveDelta = if (state.revengeActive && !isSaw) deltaTime * 2f else deltaTime
            weapon.update(effectiveDelta)
        }

        // Update orbiters, attached beam positions, and pending mines
        activeWeapons.values.forEach { weapon ->
            when (weapon) {
                is IonOrbiters -> weapon.updateOrbiters(ship.position, deltaTime, state)
                is FrostRing -> weapon.updateOrbiters(ship.position, deltaTime)
                // EnergySaw has no persistent projectile state to update
                is SpaceMines -> weapon.updatePendingMines(deltaTime, projectilePool, state)
                is LingeringNova -> weapon.updatePending(deltaTime, projectilePool, state)
            }
        }

        // Auto-fire all weapons (beat-synced)
        for (weapon in activeWeapons.values) {
            if (weapon.canFire()) {
                // Energy Saw / Warp Saw / Oblivion Beam: no beat sync (continuous damage)
                // Ion Orbiters / Frost Ring: sound on hit, not on fire
                val isSaw = weapon.id == "energy_saw" || weapon.id == "warp_saw" || weapon.id == "oblivion_beam"
                val isOrbiter = weapon.id == "ion_orbiters" || weapon.id == "frost_ring"
                val onGrid = !isSaw && !isOrbiter && SoundManager.beatClock.isRunning

                // Timers count down at 2x during Revenge, so a real-time delay is doubled to
                // land on the real-time grid.
                val timerScale = if (state.revengeActive && !isSaw) 2f else 1f

                if (onGrid && !weapon.beatSynced) {
                    // First shot: wait for the next tick of this weapon's grid.
                    weapon.beatSynced = true
                    val delayUs = SoundManager.beatClock.usUntilNextSubdivision(
                        gridSubdivisionUs(weapon, state), nowMs(), gridPhaseMs(weapon)
                    )
                    if (delayUs > 0) {
                        weapon.cooldownTimer = delayUs / 1_000_000f * timerScale
                        continue
                    }
                }

                weapon.fire(ship, state, projectilePool, targets)
                if (!isSaw && !isOrbiter) {
                    weapon.beatSynced = true
                    SoundManager.playSFX("sfx_weapon_${weapon.id}", SoundManager.getWeaponSfxVolume(weapon.id))
                }
                // Re-anchor every grid weapon at fire time. Resetting the timer to a full
                // interval drops the frame the shot was late by, and the lateness then builds
                // up shot after shot; anchoring to the next tick cannot drift.
                if (onGrid) {
                    weapon.cooldownTimer = SoundManager.beatClock.gridAnchoredDelayUs(
                        gridSubdivisionUs(weapon, state), nowMs(), gridPhaseMs(weapon)
                    ) / 1_000_000f * timerScale
                }
            }
        }

        // Note: Evolutions are now presented as choices in UpgradeSystem
        // checkEvolutions(state) - removed auto-evolution

        // Update ship weapon visuals
        ship.updateWeaponVisuals(activeWeapons.keys)
    }

    private fun gridSubdivisionUs(weapon: Weapon, state: GameState): Long {
        val seconds = if (state.revengeActive) weapon.getCooldown(state) / 2f else weapon.getCooldown(state)
        return BeatClock.cooldownToSubdivisionUs(seconds)
    }

    private fun gridPhaseMs(weapon: Weapon): Long =
        weapon.beatPhaseOffsetMs + SoundManager.getWeaponBeatOffsetMs(weapon.id)

    fun addWeapon(weaponId: String, state: GameState): Boolean {
        val existingWeapon = activeWeapons[weaponId]
        if (existingWeapon != null) {
            // Level up existing weapon
            if (existingWeapon.level < GameConfig.WEAPON_MAX_LEVEL) {
                existingWeapon.onLevelUp()
                state.weaponLevels[weaponId] = existingWeapon.level
                return true
            }
            return false
        }

        // Create new weapon
        val weapon = WeaponFactory.createWeapon(weaponId)
        if (weapon != null) {
            activeWeapons[weaponId] = weapon
            state.weaponLevels[weaponId] = 1
            return true
        }

        return false
    }

    fun updateOrbitersOnly(shipPosition: com.astroloop.game.util.Vector2, deltaTime: Float) {
        activeWeapons.values.forEach { weapon ->
            when (weapon) {
                is IonOrbiters -> weapon.updateOrbiters(shipPosition, deltaTime)
                is FrostRing -> weapon.updateOrbiters(shipPosition, deltaTime)
                else -> {}
            }
        }
    }

    fun removeWeapon(weaponId: String) {
        val weapon = activeWeapons.remove(weaponId)
        // Clean up orbiters/beams if needed
        when (weapon) {
            is IonOrbiters -> weapon.clearOrbiters()
            is FrostRing -> weapon.clearOrbiters()
            // EnergySaw has no persistent state to clear
        }
    }

    fun empHitOrbiters() {
        activeWeapons.values.forEach { weapon ->
            when (weapon) {
                is IonOrbiters -> weapon.fadeOutOrbiters()
                is FrostRing -> weapon.fadeOutOrbiters()
            }
        }
    }

    fun getWeapon(weaponId: String): Weapon? = activeWeapons[weaponId]

    fun getActiveWeaponIds(): Set<String> = activeWeapons.keys.toSet()

    /**
     * Apply an evolution - called when player selects evolution upgrade card.
     */
    fun applyEvolution(baseWeaponId: String, evolvedWeaponId: String, state: GameState) {
        evolveWeapon(baseWeaponId, evolvedWeaponId, state)
    }

    private fun evolveWeapon(baseWeaponId: String, evolvedWeaponId: String, state: GameState) {
        // Remove base weapon
        removeWeapon(baseWeaponId)

        // Add evolved weapon
        val evolvedWeapon = WeaponFactory.createWeapon(evolvedWeaponId)
        if (evolvedWeapon != null) {
            evolvedWeapon.level = GameConfig.WEAPON_MAX_LEVEL // Start at max level
            activeWeapons[evolvedWeaponId] = evolvedWeapon
            // In place, not appended — see GameState.replaceWeapon. The HUD grid draws
            // weaponLevels in insertion order, so appending moved the weapon to the end of the
            // row at the exact moment it evolved.
            state.replaceWeapon(baseWeaponId, evolvedWeaponId, evolvedWeapon.level)
            state.addEvolution(evolvedWeaponId)
        } else {
            state.weaponLevels.remove(baseWeaponId)
        }
    }

    fun reset() {
        // Clear all orbiters and beams
        activeWeapons.values.forEach { weapon ->
            when (weapon) {
                is IonOrbiters -> weapon.clearOrbiters()
                is FrostRing -> weapon.clearOrbiters()
                // EnergySaw has no persistent state to clear
            }
        }
        activeWeapons.clear()
    }

    /**
     * Knobs changed while the run was paused. Every weapon waits for the next tick of its (possibly
     * new) grid, and orbiters leave through their fade and return at their new count and radius.
     */
    fun onKnobsChanged() {
        for (weapon in activeWeapons.values) {
            when (weapon) {
                is IonOrbiters -> weapon.fadeOutOrbiters()
                is FrostRing -> weapon.fadeOutOrbiters()
            }
            weapon.beatSynced = false
            weapon.cooldownTimer = 0f
        }
    }

    /** Reset beat sync on all weapons so they snap to the nearest subdivision on next fire.
     *  Call this when returning from upgrade selection to keep weapons on the beat. */
    fun resetBeatSync() {
        activeWeapons.values.forEach { weapon ->
            weapon.beatSynced = false
            weapon.cooldownTimer = 0f
        }
    }

    fun hasWeapon(weaponId: String): Boolean = activeWeapons.containsKey(weaponId)

    fun getWeaponCount(): Int = activeWeapons.size

    /**
     * Sync activeWeapons with state.weaponLevels (used by debug menu).
     * Creates missing weapons, removes stale ones, and syncs levels.
     */
    fun syncFromState(state: GameState) {
        // Remove weapons no longer in state
        val toRemove = activeWeapons.keys.filter { it !in state.weaponLevels }
        toRemove.forEach { removeWeapon(it) }

        // Add or update weapons from state
        for ((weaponId, targetLevel) in state.weaponLevels) {
            val existing = activeWeapons[weaponId]
            if (existing == null) {
                val weapon = WeaponFactory.createWeapon(weaponId)
                if (weapon != null) {
                    while (weapon.level < targetLevel) weapon.onLevelUp()
                    activeWeapons[weaponId] = weapon
                }
            } else if (existing.level != targetLevel) {
                // Recreate at correct level
                removeWeapon(weaponId)
                val weapon = WeaponFactory.createWeapon(weaponId)
                if (weapon != null) {
                    while (weapon.level < targetLevel) weapon.onLevelUp()
                    activeWeapons[weaponId] = weapon
                }
            }
        }
    }
}
