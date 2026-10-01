package com.astroloop.game.system

import com.astroloop.game.core.GameState
import com.astroloop.game.tuning.KnobsField

class DifficultySystem {

    companion object {
        internal const val PEAK_MINUTES = 8.0f
        internal const val PEAK_DIFFICULTY = 4.0f
        internal const val LINEAR_SLOPE = 0.4f
    }

    fun update(deltaTime: Float, state: GameState) {
        state.survivalTime += deltaTime * state.corruptionTimeMultiplier

        if (state.hasCrystalPowers) {
            state.difficultyMultiplier = 1f
            return
        }

        val minutes = state.survivalTime / 60f

        val peakMinute = KnobsField.peakMinute.value
        val peak = KnobsField.peakDifficulty.value
        state.difficultyMultiplier = when {
            state.astroLoopMode && minutes > peakMinute ->
                peak + KnobsField.postPeakSlope.value * (minutes - peakMinute)
            state.astroLoopMode -> {
                // A parabola from 1.0 at minute 0 to its vertex at (peakMinute, peak).
                val rise = peak - 1f
                val a = 2f * rise / peakMinute
                val b = rise / (peakMinute * peakMinute)
                (1f + a * minutes - b * minutes * minutes)
            }
            else ->
                (1f + 0.706f * minutes - 0.0415f * minutes * minutes).coerceAtMost(4.0f)
        }
    }

    fun reset() {
        // Nothing to reset - state handles this
    }
}
