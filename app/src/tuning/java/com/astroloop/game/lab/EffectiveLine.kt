package com.astroloop.game.lab

import com.astroloop.game.tuning.Knob
import com.astroloop.game.tuning.KnobsWeapons
import com.astroloop.game.tuning.WeaponKnobs
import com.astroloop.game.tuning.WeaponProbe
import java.util.Locale

/**
 * The numbers a weapon really fires and hits for, shown under one of its knobs. Read from the
 * weapon's own code through [WeaponProbe], so it cannot disagree with the game.
 */
object EffectiveLine {
    data class Line(val text: String, val highlight: IntRange?)

    private const val SEP = " · "
    private val targetLimited = setOf("solar_storm", "phoenix_flare")
    private val noLine = setOf("warp_saw", "oblivion_beam")

    fun weaponOf(k: Knob): WeaponKnobs? {
        val prefix = k.id.substringBefore('.')
        return KnobsWeapons.all.firstOrNull { it.weaponId == prefix }
    }

    fun carrier(w: WeaponKnobs): Knob? = if (w.weaponId in noLine) null else (w.count ?: w.damage)

    fun forKnob(k: Knob, runLevel: Int?): Line? {
        val w = weaponOf(k) ?: return null
        if (carrier(w) !== k) return null
        val id = w.weaponId
        return when {
            id == "energy_saw" -> perLevel("Damage per tick by level: ", (1..5).map { num(WeaponProbe.damage(id, it)) }, runLevel, "")
            WeaponProbe.isEvolution(id) -> Line(evolution(w), null)
            else -> base(id, runLevel)
        }
    }

    private fun base(id: String, runLevel: Int?): Line {
        val counts = (1..5).map { WeaponProbe.fired(id, it) }
        val extra = WeaponProbe.fired(id, 1, duplicator = true) - counts[0]
        val prefix = if (id in targetLimited) "Strikes per level, up to: " else "Fires per level: "
        var suffix = if (extra > 0) " (+$extra with Duplicator)" else ""
        if (WeaponProbe.bomblets(id, 1) != null) {
            suffix += SEP + "bomblets " + (1..5).joinToString(SEP) { WeaponProbe.bomblets(id, it).toString() }
        }
        return perLevel(prefix, counts.map { it.toString() }, runLevel, suffix)
    }

    private fun evolution(w: WeaponKnobs): String {
        val id = w.weaponId
        val n = WeaponProbe.fired(id, 5)
        val withDup = WeaponProbe.fired(id, 5, duplicator = true)
        val sb = StringBuilder("Fires ")
        if (id in targetLimited) sb.append("up to ")
        sb.append(n)
        if (withDup != n) sb.append(" ($withDup with Duplicator)")
        val hit = WeaponProbe.damage(id, 5)
        val knob = w.damage.value
        if (knob != 0f && kotlin.math.abs(hit - knob) > 1e-4f * knob) {
            sb.append(SEP).append("hits for ").append(num(hit))
                .append(" (×").append(num(hit / knob)).append(" evolution bonus)")
        }
        WeaponProbe.bomblets(id, 5)?.let { sb.append(SEP).append(it).append(" bomblets") }
        return sb.toString()
    }

    private fun perLevel(prefix: String, values: List<String>, runLevel: Int?, suffix: String): Line {
        val sb = StringBuilder(prefix)
        var highlight: IntRange? = null
        values.forEachIndexed { i, v ->
            if (i > 0) sb.append(SEP)
            val start = sb.length
            sb.append(v)
            if (runLevel == i + 1) highlight = start until sb.length
        }
        sb.append(suffix)
        return Line(sb.toString(), highlight)
    }

    /** At most two decimals, no trailing zeros: 27.9, 1.55, 31. */
    private fun num(v: Float): String =
        String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
}
