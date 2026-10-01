package com.astroloop.game.lab

import com.astroloop.game.tuning.RunLoadout
import com.astroloop.game.tuning.RunSummary
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The text a player posts after a run: one `key: value` per line, fixed keys, so people can read
 * it and a script can collect a thread of them. Parsers skip lines they do not know, so keys may
 * be added later without breaking anything already collected.
 */
object RunReport {
    private const val LOW_FPS = 30f

    fun format(
        summary: RunSummary,
        build: String,
        code: String,
        codeHash: String,
        errors: List<String>,
    ): String = buildString {
        line("astro-loop-lab-report", "1")
        line("build", build)
        line("code", code)
        line("code-hash", codeHash)
        line("ship", summary.shipId)
        line("pilot", summary.pilotId)
        line("loadout", summary.loadout?.let { describe(it.weapons.toMap(), it.passives.toMap()) } ?: "default")
        line("final", describe(summary.finalWeapons, summary.finalPassives))
        line("start-minute", summary.startMinute.toString())
        line("survived", clock(summary.survivedSeconds))
        line("cause", summary.cause)
        line("tuned-mid-run", summary.tunedMidRunAtSeconds?.let { clock(it) } ?: "no")
        line("avg-fps", whole(summary.avgFps).toString())
        line("low-fps", if (summary.avgFps < LOW_FPS) "yes" else "no")
        val flown = summary.survivedSeconds - summary.startMinute * 60f
        val minutes = flown.coerceAtLeast(1f) / 60f
        for ((weapon, dmg) in summary.damageByWeapon.toSortedMap()) {
            line("damage.${key(weapon)}", "${whole(dmg)} (${if (flown < 60f) "n/a" else "${whole(dmg / minutes)}/min"})")
        }
        for ((source, dmg) in summary.damageTakenBy.toSortedMap()) {
            line("damage-taken.${key(source)}", whole(dmg).toString())
        }
        line("asteroids-destroyed", summary.asteroidsDestroyed.toString())
        errors.forEachIndexed { i, e -> line("error.${i + 1}", e.trim()) }
    }

    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val sep = raw.indexOf(": ")
            if (sep <= 0) continue
            val k = raw.substring(0, sep).trim()
            if (k.isEmpty()) continue
            out[k] = raw.substring(sep + 2).trim()
        }
        return out
    }

    private fun StringBuilder.line(key: String, value: String) {
        val cleanedValue = value.replace('\n', ' ').replace('\r', ' ').trim()
        val displayValue = if (cleanedValue.isEmpty()) "-" else cleanedValue
        append(key).append(": ").append(displayValue).append('\n')
    }

    /** Rounded for display; NaN and infinities read as 0 rather than losing the whole report. */
    private fun whole(x: Float): Int = if (x.isFinite()) x.roundToInt() else 0

    /** Sanitise key by replacing non-alphanumeric characters. */
    private fun key(s: String): String = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun describe(weapons: Map<String, Int>, passives: Map<String, Int>): String =
        (weapons.map { "${it.key} L${it.value}" } + passives.map { "${it.key} ${it.value}" }).joinToString(", ")

    private fun clock(seconds: Float): String {
        val s = seconds.toInt().coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }
}
