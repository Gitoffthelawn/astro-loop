package com.astroloop.game.tuning

/**
 * A weapon's uniform knobs: damage, fire interval, count, speed, area.
 *
 * The fire interval comes from exactly one of three places. A base weapon has a 16th-note
 * [cooldown]. An evolution has a [rate] against the weapon it evolved from, so it always fires at
 * 1x or 0.5x of that weapon's tuned interval. Saws and the beam have a [tickMs] damage tick, which
 * is not rhythmic and so is plain milliseconds.
 */
class WeaponKnobs private constructor(
    val weaponId: String,
    val name: String,
    internal val g: KnobGroup,
    val damage: FloatKnob,
    val cooldown: SixteenthsKnob?,
    val rate: EvoRateKnob?,
    val evolvesFrom: WeaponKnobs?,
    val tickMs: IntKnob?,
    val count: IntKnob?,
    private val fixedCount: Int,
    val speed: FloatKnob?,
    val area: FloatKnob?,
) {
    val cooldownSeconds: Float
        get() = when {
            tickMs != null -> tickMs.value / 1000f
            cooldown != null -> cooldown.seconds
            else -> evolvesFrom!!.cooldownSeconds * rate!!.factor
        }

    val projectileCount: Int get() = count?.value ?: fixedCount
    val projectileSpeed: Float get() = speed?.value ?: 0f
    val areaScale: Float get() = area?.value ?: 1f

    internal companion object {
        private fun groupFor(id: String, name: String) = KnobGroup("Weapons/$name", id)

        private fun KnobGroup.countKnob(count: Int?, label: String, applies: Applies) =
            count?.let { int("count", label, it, applies = applies) }

        private fun KnobGroup.speedKnob(speed: Float?) =
            speed?.let { float("speed", "Projectile speed", it, min = it / 4f, max = it * 4f) }

        private fun KnobGroup.areaKnob(hasArea: Boolean) =
            if (hasArea) float("area", "Area ×", 1f, min = 0.25f, max = 4f) else null

        fun base(
            id: String, name: String, damage: Float, sixteenths: Int,
            count: Int?, fixedCount: Int = 1, speed: Float? = null, hasArea: Boolean = false,
            countApplies: Applies = Applies.LIVE, countLabel: String = "Projectiles at L1",
        ): WeaponKnobs {
            val g = groupFor(id, name)
            return WeaponKnobs(
                id, name, g,
                damage = g.float("damage", "Damage", damage),
                cooldown = g.sixteenths("cooldown", "Fire interval", sixteenths),
                rate = null, evolvesFrom = null, tickMs = null,
                count = g.countKnob(count, countLabel, countApplies), fixedCount = fixedCount,
                speed = g.speedKnob(speed), area = g.areaKnob(hasArea),
            )
        }

        fun evolution(
            id: String, name: String, from: WeaponKnobs, rate: Float, damage: Float,
            count: Int?, fixedCount: Int = 1, speed: Float? = null, hasArea: Boolean = false,
            countLabel: String = "Projectiles (base)",
        ): WeaponKnobs {
            val g = groupFor(id, name)
            return WeaponKnobs(
                id, name, g,
                damage = g.float("damage", "Damage", damage),
                cooldown = null,
                rate = g.evoRate("rate", "Rate vs ${from.name}", rate),
                evolvesFrom = from, tickMs = null,
                count = g.countKnob(count, countLabel, Applies.LIVE), fixedCount = fixedCount,
                speed = g.speedKnob(speed), area = g.areaKnob(hasArea),
            )
        }

        fun ticking(id: String, name: String, damage: Float, tickMs: Int): WeaponKnobs {
            val g = groupFor(id, name)
            return WeaponKnobs(
                id, name, g,
                damage = g.float("damage", "Damage per tick", damage),
                cooldown = null, rate = null, evolvesFrom = null,
                tickMs = g.int("tick_ms", "Damage tick (ms)", tickMs, min = 20, max = 1000),
                count = null, fixedCount = 1, speed = null, area = null,
            )
        }
    }
}
