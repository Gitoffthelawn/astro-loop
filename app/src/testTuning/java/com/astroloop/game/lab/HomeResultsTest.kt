package com.astroloop.game.lab

import android.content.Intent
import android.os.Bundle
import com.astroloop.game.tuning.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LabApp::class)
class HomeResultsTest {
    private val summary = RunSummary(
        shipId = "ship_blue", pilotId = "pilot_astro", startMinute = 0,
        survivedSeconds = 60f, cause = "x", finalWeapons = emptyMap(), finalPassives = emptyMap(),
        damageByWeapon = emptyMap(), damageTakenBy = emptyMap(), asteroidsDestroyed = 0,
        avgFps = 60f, tunedMidRunAtSeconds = null, loadout = null,
    )

    @Test
    fun `home opens results after a finished run`() {
        val controller = Robolectric.buildActivity(LabActivity::class.java).setup()
        LabSession.onRunEnded(summary)
        controller.pause().resume()
        val next = shadowOf(controller.get()).nextStartedActivity
        assertEquals(ResultsActivity::class.java.name, next.component!!.className)
    }

    @Test
    fun `a fresh home does not reopen results for an old run, even after recreation`() {
        LabSession.onRunEnded(summary)
        val first = Robolectric.buildActivity(LabActivity::class.java).setup()
        assertNull(shadowOf(first.get()).nextStartedActivity)
        val state = Bundle()
        first.saveInstanceState(state)
        val second = Robolectric.buildActivity(LabActivity::class.java).create(state).start().restoreInstanceState(state).resume()
        assertNull(shadowOf(second.get()).nextStartedActivity)
    }

    @Test
    fun `results shows the report and flags an error`() {
        LabSession.onUpdateError(IllegalStateException("boom"))
        LabSession.onRunEnded(summary)
        val a = Robolectric.buildActivity(ResultsActivity::class.java, Intent()).setup().get()
        assertNotNull(a.shownText())
        assert("error.1" in a.shownText()) { a.shownText() }
        assert("This tuning broke something" in a.shownText())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LabApp::class)
class ReentryGuardTest {
    private fun allViews(v: android.view.View, out: MutableList<android.view.View> = mutableListOf()): List<android.view.View> {
        out += v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) allViews(v.getChildAt(i), out)
        return out
    }

    private fun press(a: android.app.Activity, label: String) {
        val b = allViews(a.window.decorView).filterIsInstance<android.widget.Button>().first { it.text.toString() == label }
        b.performClick()
    }

    @Test
    fun `double launch starts one game, and launch works again after resume`() {
        val c = Robolectric.buildActivity(LabActivity::class.java).setup()
        val sh = shadowOf(c.get())
        press(c.get(), "Launch"); press(c.get(), "Launch")
        assertNotNull(sh.nextStartedActivity)
        assertNull(sh.nextStartedActivity)
        c.pause().resume()
        press(c.get(), "Launch")
        assertNotNull(sh.nextStartedActivity)
    }

    @Test
    fun `double run again starts one game`() {
        LabSession.onRunEnded(RunSummary(
            shipId = "ship_blue", pilotId = "pilot_astro", startMinute = 0,
            survivedSeconds = 60f, cause = "x", finalWeapons = emptyMap(), finalPassives = emptyMap(),
            damageByWeapon = emptyMap(), damageTakenBy = emptyMap(), asteroidsDestroyed = 0,
            avgFps = 60f, tunedMidRunAtSeconds = null, loadout = null,
        ))
        val a = Robolectric.buildActivity(ResultsActivity::class.java, Intent()).setup().get()
        press(a, "Run again"); press(a, "Run again")
        val sh = shadowOf(a)
        assertNotNull(sh.nextStartedActivity)
        assertNull(sh.nextStartedActivity)
    }

    @Test
    fun `second tune request opens no second editor until the first closes`() {
        LabSession.editorClosed()
        val host = Robolectric.buildActivity(LabActivity::class.java).setup().get()
        val sh = shadowOf(host)
        LabSession.onTuneRequested(host, mapOf("pulse_cannon" to 1), emptyList())
        LabSession.onTuneRequested(host, mapOf("pulse_cannon" to 1), emptyList())
        assertNotNull(sh.nextStartedActivity)
        assertNull(sh.nextStartedActivity)
        LabSession.editorClosed()
        LabSession.onTuneRequested(host, mapOf("pulse_cannon" to 1), emptyList())
        assertNotNull(sh.nextStartedActivity)
        LabSession.editorClosed()
    }

    @Test
    fun `restored home clamps a stale seenSeq so the next run still shows results`() {
        val first = Robolectric.buildActivity(LabActivity::class.java).setup()
        val state = Bundle()
        first.saveInstanceState(state)
        state.putInt("seenSeq", LabSession.reportSeq + 5)
        val second = Robolectric.buildActivity(LabActivity::class.java).create(state).start().restoreInstanceState(state).resume()
        LabSession.onRunEnded(RunSummary(
            shipId = "ship_blue", pilotId = "pilot_astro", startMinute = 0,
            survivedSeconds = 60f, cause = "x", finalWeapons = emptyMap(), finalPassives = emptyMap(),
            damageByWeapon = emptyMap(), damageTakenBy = emptyMap(), asteroidsDestroyed = 0,
            avgFps = 60f, tunedMidRunAtSeconds = null, loadout = null,
        ))
        second.pause().resume()
        assertEquals(ResultsActivity::class.java.name, shadowOf(second.get()).nextStartedActivity.component!!.className)
    }

    @Test
    fun `home shows the loadout with icons`() {
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("lab_loadout", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val home = Robolectric.buildActivity(LabActivity::class.java).setup().get()
        val m = LabIcons.MARK
        assertEquals("$m Scout \u00b7 $m ASTRO\n$m L1\n$m 1\nStart minute 0", home.loadoutCardText())
    }
}
