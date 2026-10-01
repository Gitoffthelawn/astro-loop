package com.astroloop.game.weapon.weapons

import com.astroloop.game.core.GameState
import com.astroloop.game.entity.Entity
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.Projectile
import com.astroloop.game.entity.Firer
import com.astroloop.game.weapon.Weapon
import com.astroloop.game.tuning.KnobsWeapons

class OblivionBeam : Weapon(
    id = "oblivion_beam",
    name = "Oblivion Beam"
) {
    override val knobs = KnobsWeapons.oblivionBeam

    override fun getDamage(state: GameState): Float = baseDamage * state.damageMultiplier

    override fun fire(
        firer: Firer,
        state: GameState,
        projectilePool: EntityPool<Projectile>,
        targets: List<Entity>
    ) {
        // No-op: BeamDamageSystem applies continuous damage
    }
}
