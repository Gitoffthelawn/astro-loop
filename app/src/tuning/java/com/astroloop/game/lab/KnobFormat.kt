package com.astroloop.game.lab

import com.astroloop.game.tuning.EvoRateKnob
import com.astroloop.game.tuning.FloatKnob
import com.astroloop.game.tuning.IntKnob
import com.astroloop.game.tuning.Knob
import com.astroloop.game.tuning.KnobsWeapons
import com.astroloop.game.tuning.SixteenthsKnob
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** How the editor shows and steps each knob. */
object KnobFormat {
    private const val SIXTEENTH_MS = 125
    /** Two frames at 60 Hz: an interval this short can lose shots on a slow phone. */
    private const val FAST_MS = 34.0

    fun value(k: Knob): String = text(k, k.raw)
    fun default(k: Knob): String = text(k, k.defaultRaw)

    private fun text(k: Knob, raw: Float): String = when (k) {
        is SixteenthsKnob -> "${musical(raw.toInt())} · ${raw.toInt() * SIXTEENTH_MS} ms"
        is EvoRateKnob -> "${if (raw < 0.75f) "½" else "1"}× ${baseName(k)}"
        is IntKnob -> raw.toInt().toString()
        is FloatKnob -> ShareCode.formatValue(raw)
    }

    fun musical(sixteenths: Int): String {
        if (sixteenths % 16 == 0) return if (sixteenths == 16) "1 bar" else "${sixteenths / 16} bars"
        val whole = sixteenths / 4
        val frac = when (sixteenths % 4) { 1 -> "¼"; 2 -> "½"; 3 -> "¾"; else -> "" }
        val number = if (whole == 0) frac else "$whole$frac"
        val plural = sixteenths > 4
        return "$number ${if (plural) "beats" else "beat"}"
    }

    fun stepUp(k: Knob): Float = stepped(k, +1)
    fun stepDown(k: Knob): Float = stepped(k, -1)

    /** How far one press moves [k]. */
    internal fun step(k: Knob): Float = when (k) {
        is FloatKnob -> stepFor(k.defaultRaw, k.minRaw, k.maxRaw)
        is EvoRateKnob -> 0.5f
        else -> 1f
    }

    /**
     * The largest of 1, 2 or 5 times a power of ten that is no more than a tenth of [default], so
     * every float knob moves in round, fixed amounts. A zero default uses a tenth of the range.
     */
    internal fun stepFor(default: Float, min: Float, max: Float): Float {
        val basis = if (default != 0f) abs(default.toDouble()) else (max - min).toDouble() / 10.0
        val target = basis / 10.0
        if (target <= 0.0) return 1f
        val power = 10.0.pow(floor(log10(target)))
        val mantissa = target / power
        val pick = when {
            mantissa >= 5.0 - 1e-9 -> 5.0
            mantissa >= 2.0 - 1e-9 -> 2.0
            else -> 1.0
        }
        return (pick * power).toFloat()
    }

    /**
     * One press in [direction] along the lattice `default + n x step`. Coming back to n = 0 gives
     * the default itself, so a knob stepped away and back reads as unchanged. A value off the
     * lattice (typed, or clamped at a range edge) moves to the next lattice point that way.
     */
    private fun stepped(k: Knob, direction: Int): Float {
        if (k !is FloatKnob) return k.normalise(k.raw + direction * step(k))
        val s = step(k).toDouble()
        val n = (k.raw.toDouble() - k.defaultRaw.toDouble()) / s
        val nearest = Math.round(n).toDouble()
        val onLattice = abs(n - nearest) < 1e-3
        val next = when {
            onLattice -> nearest + direction
            direction > 0 -> ceil(n)
            else -> floor(n)
        }
        if (next == 0.0) return k.normalise(k.defaultRaw)
        val places = max(0, -floor(log10(s)).toInt()) + 2
        val value = BigDecimal(k.defaultRaw.toDouble() + next * s).setScale(places, RoundingMode.HALF_EVEN).toFloat()
        return k.normalise(value)
    }

    fun fastWarning(k: Knob): String? {
        val baseMs = when (k) {
            is SixteenthsKnob -> k.value * SIXTEENTH_MS.toDouble()
            is EvoRateKnob -> baseCooldown(k)?.let { it.value * SIXTEENTH_MS * k.factor.toDouble() * 2 } ?: return null
            else -> return null
        }
        // The evolution's interval, halved by Revenge.
        return if (baseMs / 4 <= FAST_MS) "Very fast: at ½× and under Revenge, slow phones may skip shots" else null
    }

    private fun baseName(k: EvoRateKnob): String =
        KnobsWeapons.all.firstOrNull { it.rate === k }?.evolvesFrom?.name ?: "base"

    private fun baseCooldown(k: EvoRateKnob): SixteenthsKnob? =
        KnobsWeapons.all.firstOrNull { it.rate === k }?.evolvesFrom?.cooldown
}
