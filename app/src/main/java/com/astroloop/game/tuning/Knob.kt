package com.astroloop.game.tuning

import kotlin.math.round

/** When an edited value reaches a running game. The Lab's editor labels each knob with this. */
enum class Applies {
    /** Read every frame: takes effect immediately. */
    LIVE,
    /** Read when something spawns: applies to what spawns next. */
    NEW_SPAWNS,
    /** Shapes objects that already exist, or needs a stat recalculation: applied when the editor closes. */
    ON_EDITOR_CLOSE,
}

/**
 * One tunable number.
 *
 * [raw] holds the live value as a Float whatever the knob's type — an Int is exact in a Float far
 * beyond any range here — so the registry can reset, clamp and serialise every knob the same way.
 * Game code never reads [raw]; it reads the typed accessor of the subclass.
 */
sealed class Knob(
    val id: String,
    val group: String,
    val label: String,
    val defaultRaw: Float,
    val minRaw: Float,
    val maxRaw: Float,
    val applies: Applies,
) {
    init {
        require(minRaw <= defaultRaw && defaultRaw <= maxRaw) {
            "knob $id: default $defaultRaw outside [$minRaw, $maxRaw]"
        }
    }

    @Volatile
    var raw: Float = defaultRaw
        internal set

    /** Brings [v] into range and onto the knob's lattice (whole numbers, 1x / 0.5x). */
    open fun normalise(v: Float): Float = if (v.isFinite()) v.coerceIn(minRaw, maxRaw) else defaultRaw

    val isDefault: Boolean get() = raw == defaultRaw
}

class FloatKnob(
    id: String, group: String, label: String,
    default: Float, min: Float, max: Float, applies: Applies,
) : Knob(id, group, label, default, min, max, applies) {
    val value: Float get() = raw
}

open class IntKnob(
    id: String, group: String, label: String,
    default: Int, min: Int, max: Int, applies: Applies,
) : Knob(id, group, label, default.toFloat(), min.toFloat(), max.toFloat(), applies) {
    val value: Int get() = raw.toInt()
    override fun normalise(v: Float): Float = if (v.isFinite()) round(v).coerceIn(minRaw, maxRaw) else defaultRaw
}

/**
 * A fire interval counted in 16th notes at 120 BPM. Every legal value is a whole number of 16ths,
 * so a weapon tuned with it cannot leave the beat grid.
 */
class SixteenthsKnob(
    id: String, group: String, label: String,
    default: Int, applies: Applies,
) : IntKnob(id, group, label, default, MIN, MAX, applies) {
    val seconds: Float get() = value * SIXTEENTH_SECONDS

    companion object {
        /** 60 s / 120 BPM / 4. */
        const val SIXTEENTH_SECONDS = 0.125f
        const val MIN = 1
        /** Two bars. */
        const val MAX = 32
    }
}

/** An evolution's fire rate against its base weapon: exactly 1x or 0.5x the base's tuned interval. */
class EvoRateKnob(
    id: String, group: String, label: String,
    default: Float, applies: Applies,
) : Knob(id, group, label, default, 0.5f, 1f, applies) {
    init {
        require(default == 0.5f || default == 1f) { "knob $id: an evolution rate is 1 or 0.5, not $default" }
    }

    val factor: Float get() = raw
    override fun normalise(v: Float): Float = when {
        v.isNaN() -> defaultRaw
        v < 0.75f -> 0.5f
        else -> 1f
    }
}

/**
 * Declares knobs under one editor group with a shared id prefix. Ranges default to a tenth and ten
 * times the default for floats, and 1 to four times the default (at least 12) for counts.
 */
internal class KnobGroup(private val group: String, private val prefix: String) {

    fun float(
        key: String, label: String, default: Float,
        min: Float = default / 10f, max: Float = default * 10f,
        applies: Applies = Applies.LIVE,
    ): FloatKnob = Tunables.register(FloatKnob("$prefix.$key", group, label, default, min, max, applies))

    fun int(
        key: String, label: String, default: Int,
        min: Int = 1, max: Int = maxOf(default * 4, 12),
        applies: Applies = Applies.LIVE,
    ): IntKnob = Tunables.register(IntKnob("$prefix.$key", group, label, default, min, max, applies))

    fun sixteenths(key: String, label: String, default: Int, applies: Applies = Applies.LIVE): SixteenthsKnob =
        Tunables.register(SixteenthsKnob("$prefix.$key", group, label, default, applies))

    fun evoRate(key: String, label: String, default: Float): EvoRateKnob =
        Tunables.register(EvoRateKnob("$prefix.$key", group, label, default, Applies.LIVE))

    /** One knob per level, `<key>_l1` … `<key>_l5`. */
    fun floatsPerLevel(
        key: String, label: String, defaults: FloatArray, applies: Applies = Applies.LIVE,
    ): List<FloatKnob> = defaults.mapIndexed { i, d -> float("${key}_l${i + 1}", "$label L${i + 1}", d, applies = applies) }
}

/** The value for [level], clamped to the list — a level past the top reads the top rung. */
fun List<FloatKnob>.forLevel(level: Int): Float = this[level.coerceIn(1, size) - 1].value
