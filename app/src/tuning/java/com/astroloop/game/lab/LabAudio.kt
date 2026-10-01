package com.astroloop.game.lab

import com.astroloop.game.core.AudioMode

/** Maps the Lab home's two switches, music and effects, onto the game's four-state [AudioMode]. */
object LabAudio {
    fun modeFor(music: Boolean, effects: Boolean): AudioMode = when {
        music && effects -> AudioMode.ALL
        !music && !effects -> AudioMode.NONE
        music -> AudioMode.EFFECTS_MUTED
        else -> AudioMode.MUSIC_MUTED
    }

    fun musicOn(mode: AudioMode): Boolean = !mode.musicSilenced

    fun effectsOn(mode: AudioMode): Boolean = !mode.effectsSilenced
}
