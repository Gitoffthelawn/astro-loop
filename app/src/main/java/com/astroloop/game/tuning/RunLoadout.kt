package com.astroloop.game.tuning

import com.astroloop.game.core.GameConfig

/**
 * A run's starting loadout: ship, pilot, weapons with levels, passives with stacks, and the
 * survival minute to start at. Travels between activities as one line of text:
 * `ship=ship_blue;pilot=pilot_astro;w=railgun:5,oblivion_beam:5;p=glass_cannon:5;m=8`.
 */
data class RunLoadout(
    val shipId: String,
    val pilotId: String,
    val weapons: List<Pair<String, Int>>,
    val passives: List<Pair<String, Int>>,
    val startMinute: Int,
) {
    fun encode(): String = listOf(
        "ship=$shipId",
        "pilot=$pilotId",
        "w=" + weapons.joinToString(",") { "${it.first}:${it.second}" },
        "p=" + passives.joinToString(",") { "${it.first}:${it.second}" },
        "m=$startMinute",
    ).joinToString(";")

    companion object {
        const val MAX_START_MINUTE = 30

        fun decode(text: String): RunLoadout? {
            val fields = text.split(';').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
            }.toMap()
            val ship = fields["ship"]?.takeIf { it.isNotEmpty() } ?: return null
            val pilot = fields["pilot"]?.takeIf { it.isNotEmpty() } ?: return null
            return RunLoadout(
                shipId = ship,
                pilotId = pilot,
                weapons = pairs(fields["w"], GameConfig.WEAPON_MAX_LEVEL),
                passives = pairs(fields["p"], GameConfig.PASSIVE_MAX_STACKS),
                startMinute = (fields["m"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_START_MINUTE),
            )
        }

        private fun pairs(text: String?, max: Int): List<Pair<String, Int>> =
            text.orEmpty().split(',').mapNotNull { item ->
                val colon = item.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                val id = item.substring(0, colon).trim()
                val n = item.substring(colon + 1).trim().toIntOrNull() ?: return@mapNotNull null
                id to n.coerceIn(1, max)
            }
    }
}
