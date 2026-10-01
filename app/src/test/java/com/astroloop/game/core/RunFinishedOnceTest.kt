package com.astroloop.game.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.data.HighScoreManager
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.tuning.RunHooks
import com.astroloop.game.tuning.RunListener
import com.astroloop.game.tuning.RunSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Several end-of-run paths keep calling finishRun every frame until the UI thread pauses the view. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RunFinishedOnceTest {

    @After
    fun tearDown() {
        RunHooks.listener = null
    }

    @Test
    fun `a run ends and pays out once however often finishRun is reached`() {
        PersistenceManager(ApplicationProvider.getApplicationContext()).resetAllProgress()
        var ended = 0
        var started = 0
        RunHooks.listener = object : RunListener {
            override fun onRunStarted() { started++ }
            override fun onRunEnded(summary: RunSummary) { ended++ }
        }
        var gameOvers = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = GameSurfaceView(context, "ship_blue", "pilot_medic") { _, _ -> gameOvers++ }
        view.surfaceCreated(view.holder)
        view.surfaceChanged(view.holder, 0, 1280, 2844)

        view.finishRun(10, false)
        view.finishRun(10, false)
        view.finishRun(10, false)

        assertEquals(1, ended)
        assertEquals(1, gameOvers)
        assertEquals(1, started)
    }

    private fun newView(): GameSurfaceView {
        PersistenceManager(ApplicationProvider.getApplicationContext()).resetAllProgress()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = GameSurfaceView(context, "ship_blue", "pilot_medic") { _, _ -> }
        view.surfaceCreated(view.holder)
        view.surfaceChanged(view.holder, 0, 1280, 2844)
        return view
    }

    private fun gamesPlayed() = HighScoreManager(ApplicationProvider.getApplicationContext<Context>()).getTotalGamesPlayed()

    @Test
    fun `a retreat that holds its end condition for several frames saves stats once`() {
        val view = newView()
        view.state.phase = GamePhase.PLAYING
        view.state.retreatPhase = 3
        view.state.retreatTimer = 2f

        repeat(5) { view.update(0.05f) }

        assertEquals(1, gamesPlayed())
    }

    @Test
    fun `a timeline shift that holds its end condition for several frames saves stats once`() {
        val view = newView()
        view.state.phase = GamePhase.TIMELINE_SHIFT
        val cls = GameSurfaceView::class.java
        cls.getDeclaredField("timelineShiftAlpha").apply { isAccessible = true }.setFloat(view, 1f)
        cls.getDeclaredField("timelineShiftHoldTimer").apply { isAccessible = true }.setFloat(view, 1f)

        repeat(5) { view.update(0.05f) }

        assertEquals(1, gamesPlayed())
    }

    @Test
    fun `two lethal hits in one frame of a non-Astro corruption run are one death`() {
        val view = newView()
        val persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.setStoryStageCode(StoryStage.CORRUPTION.code)
        view.state.phase = GamePhase.PLAYING
        val death = GameSurfaceView::class.java.getDeclaredMethod("handlePlayerDeath")
            .apply { isAccessible = true }

        death.invoke(view)
        death.invoke(view)

        assertEquals(1, gamesPlayed())
        assertEquals(1, persistence.getTotalDeaths())
    }
}
