package com.astroloop.game.lab

import com.astroloop.game.tuning.Knob
import com.astroloop.game.tuning.KnobsWeapons

sealed interface EditorRow {
    data class Header(val title: String, val knobIds: List<String>) : EditorRow
    data class KnobRow(val knob: Knob) : EditorRow
}

/** The editor's rows: the run's own knobs first, then every group in registry order, filtered by a search. */
object EditorModel {
    const val IN_THIS_RUN = "In this run"

    fun rows(knobs: List<Knob>, runWeaponIds: List<String>, runPassiveIds: List<String>, query: String): List<EditorRow> {
        val q = query.trim().lowercase()
        val matching = if (q.isEmpty()) knobs else knobs.filter {
            q in it.id.lowercase() || q in it.label.lowercase() || q in it.group.lowercase()
        }
        val prefixes = runPrefixes(runWeaponIds, runPassiveIds)
        val inRun = if (prefixes.isEmpty()) emptyList() else matching.filter { it.id.substringBefore('.') in prefixes }
        val out = mutableListOf<EditorRow>()
        if (inRun.isNotEmpty()) {
            out += EditorRow.Header(IN_THIS_RUN, inRun.map { it.id })
            inRun.forEach { out += EditorRow.KnobRow(it) }
        }
        val rest = matching - inRun.toSet()
        for ((group, groupKnobs) in rest.groupBy { it.group }) {
            out += EditorRow.Header(group, groupKnobs.map { it.id })
            groupKnobs.forEach { out += EditorRow.KnobRow(it) }
        }
        return out
    }

    private fun runPrefixes(weapons: List<String>, passives: List<String>): Set<String> {
        val out = HashSet<String>()
        for (id in weapons) {
            out += id
            KnobsWeapons.all.firstOrNull { it.weaponId == id }?.evolvesFrom?.let { out += it.weaponId }
        }
        for (id in passives) {
            out += id
            if (id == "tb26" || id == "combat_drone") out += "drone"
        }
        return out
    }
}
