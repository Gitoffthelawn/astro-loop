package com.astroloop.game.core

import org.junit.Assert.*
import org.junit.Test

class ResumeAudioDecisionTest {
    @Test fun `paused game keeps audio off`() = assertFalse(shouldResumeAudio(isGameView = true, runPaused = true))
    @Test fun `unpaused game resumes audio`() = assertTrue(shouldResumeAudio(isGameView = true, runPaused = false))
    @Test fun `hangar always resumes audio`() {
        assertTrue(shouldResumeAudio(isGameView = false, runPaused = false))
        assertTrue(shouldResumeAudio(isGameView = false, runPaused = true))
    }
}
