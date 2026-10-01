package com.astroloop.game.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.tuning.RunHooks
import com.astroloop.game.tuning.RunListener
import com.astroloop.game.tuning.RunSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PauseQuitTest {
    @After
    fun tearDown() { RunHooks.listener = null }

    private fun pausedRun(): GameSurfaceView {
        PersistenceManager(ApplicationProvider.getApplicationContext()).resetAllProgress()
        val view = GameSurfaceView(ApplicationProvider.getApplicationContext<Context>(), "ship_blue", "pilot_medic") { _, _ -> }
        view.surfaceCreated(view.holder)
        view.surfaceChanged(view.holder, 0, 1280, 2844)
        view.state.phase = GamePhase.PLAYING
        view.state.isPaused = true
        return view
    }

    @Test
    fun `L1 on the pause screen quits a watched run with cause quit`() {
        val ended = mutableListOf<RunSummary>()
        RunHooks.listener = object : RunListener {
            override fun tuneAvailable() = true
            override fun quitAvailable() = true
            override fun onRunEnded(summary: RunSummary) { ended += summary }
        }
        val view = pausedRun()
        assertTrue(view.onPage(-1))
        assertEquals(0, ended.size)            // handled on the game thread, not in the input call
        view.update(0.016f)
        assertEquals(1, ended.size)
        assertEquals("quit", ended[0].cause)
    }

    @Test
    fun `without a listener L1 does nothing on the pause screen`() {
        RunHooks.listener = null               // the tuning variant's app installs the Lab's listener at boot
        val view = pausedRun()
        assertFalse(view.onPage(-1))
        view.update(0.016f)
        assertTrue(view.state.isPaused)
    }

    @Test
    fun `quit is not honoured where tune is not offered`() {
        val ended = mutableListOf<RunSummary>()
        RunHooks.listener = object : RunListener {
            override fun quitAvailable() = true
            override fun onRunEnded(summary: RunSummary) { ended += summary }
        }
        val view = pausedRun()
        assertFalse(view.onPage(-1))
        view.update(0.016f)
        assertEquals(0, ended.size)
    }

    @Test
    fun `a quit request does not outlive the pause it was made in`() {
        val ended = mutableListOf<RunSummary>()
        RunHooks.listener = object : RunListener {
            override fun tuneAvailable() = true
            override fun quitAvailable() = true
            override fun onRunEnded(summary: RunSummary) { ended += summary }
        }
        val view = pausedRun()
        assertTrue(view.onPage(-1))
        GameSurfaceView::class.java.getDeclaredMethod("resumeFromPlainPause").apply { isAccessible = true }.invoke(view)
        view.state.isPaused = true
        view.update(0.016f)
        assertEquals(0, ended.size)
        assertTrue(view.state.isPaused)
    }
}
