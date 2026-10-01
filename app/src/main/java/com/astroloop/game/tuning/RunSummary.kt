package com.astroloop.game.tuning

import com.astroloop.game.core.GameState

/** What one finished run reports. Built from GameState when the run ends. */
data class RunSummary(
    val shipId: String,
    val pilotId: String,
    val startMinute: Int,
    val survivedSeconds: Float,
    val cause: String,
    val finalWeapons: Map<String, Int>,
    val finalPassives: Map<String, Int>,
    val damageByWeapon: Map<String, Float>,
    val damageTakenBy: Map<String, Float>,
    val asteroidsDestroyed: Int,
    val avgFps: Float,
    val tunedMidRunAtSeconds: Float?,
    val loadout: RunLoadout?,
) {
    companion object {
        fun from(state: GameState, shipId: String, pilotId: String, startMinute: Int, nowNanos: Long, loadout: RunLoadout?): RunSummary {
            val wallSeconds = (nowNanos - state.runStartNanos) / 1_000_000_000f
            return RunSummary(
                shipId = shipId,
                pilotId = pilotId,
                startMinute = startMinute,
                survivedSeconds = state.survivalTime,
                cause = state.lastDamageSource,
                finalWeapons = LinkedHashMap(state.weaponLevels),
                finalPassives = LinkedHashMap(state.passiveStacks),
                damageByWeapon = state.runDamageByWeapon(),
                damageTakenBy = state.runDamageTakenBy(),
                asteroidsDestroyed = state.telemetryAsteroidsDestroyed,
                avgFps = if (wallSeconds > 0f) state.frameCount / wallSeconds else 0f,
                tunedMidRunAtSeconds = state.firstTunedAtSeconds,
                loadout = loadout,
            )
        }
    }
}
