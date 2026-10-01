package com.astroloop.game.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LabApp::class)
class LoadoutActivityTest {
    @Test
    fun `the default loadout shows its slot counts`() {
        val text = Robolectric.buildActivity(LoadoutActivity::class.java).setup().get().shownText()
        assertTrue(text, "Weapons 1/4" in text)
        assertTrue(text, "Passives 1/4" in text)
    }

    @Test
    fun `pressing a stepper button keeps focus on the same control after the rebuild`() {
        val a = Robolectric.buildActivity(LoadoutActivity::class.java).setup().get()
        for (tag in listOf("w-plus:pulse_cannon", "w-plus:pulse_cannon", "w-minus:pulse_cannon", "p-toggle:" + com.astroloop.game.data.PassiveDefinitions.passives.first().id, "p-plus:" + com.astroloop.game.data.PassiveDefinitions.passives.first().id)) {
            val b = a.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.view.View>(tag)
            assertTrue("no button tagged $tag", b != null)
            b.performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val target = a.focusTarget()
            assertTrue("focus target for $tag is missing after rebuild", target != null)
            assertTrue(target!!.tag == tag)
        }
    }

    @Test
    fun `ships and pilots sit two to a row with the icon in front`() {
        val a = Robolectric.buildActivity(LoadoutActivity::class.java).setup().get()
        assertEquals(2, a.gridColumns("ship:"))
        assertEquals(2, a.gridColumns("pilot:"))
        val ship = a.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.widget.TextView>("ship:ship_blue")
        assertTrue(ship.text.toString().startsWith("${LabIcons.MARK} "))
        val pilot = a.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.widget.TextView>("pilot:pilot_astro")
        assertTrue(pilot.text.toString().startsWith("${LabIcons.MARK} "))
    }

    @Test
    fun `weapon and passive rows lead with their icon`() {
        val text = Robolectric.buildActivity(LoadoutActivity::class.java).setup().get().shownText()
        assertTrue(text, "${LabIcons.MARK} Pulse Cannon  L1" in text)
        assertTrue(text, "${LabIcons.MARK} Railgun" in text)
    }

    @Test
    fun `holding the minute plus button counts up and saves once`() {
        val a = clearedLoadout()
        val prefs = a.getSharedPreferences("lab_loadout", android.content.Context.MODE_PRIVATE)
        val stored = prefs.all.toMap()
        val content = a.findViewById<android.view.View>(android.R.id.content)
        val plus = content.findViewWithTag<android.view.View>("minute-plus")
        val now = android.os.SystemClock.uptimeMillis()
        touch(plus, android.view.MotionEvent.ACTION_DOWN, now)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("saved mid-hold", stored, prefs.all.toMap())
        val shown = content.findViewWithTag<android.widget.TextView>("minute-value").text.toString().trim().toInt()
        assertTrue("minute $shown", shown >= 5)
        touch(plus, android.view.MotionEvent.ACTION_UP, now + 1000)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(shown, LoadoutModel.load(PrefsKeyValue(prefs)).startMinute)
    }

    private fun clearedLoadout(): LoadoutActivity {
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("lab_loadout", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        return Robolectric.buildActivity(LoadoutActivity::class.java).setup().get()
    }

    private fun touch(v: android.view.View, action: Int, at: Long) {
        v.dispatchTouchEvent(android.view.MotionEvent.obtain(at, at, action, 5f, 5f, 0))
    }

    @Test
    fun `a tap on the minute plus steps once and saves`() {
        val a = clearedLoadout()
        val before = LoadoutModel.load(PrefsKeyValue(a.getSharedPreferences("lab_loadout", android.content.Context.MODE_PRIVATE))).startMinute
        val plus = a.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.view.View>("minute-plus")
        val now = android.os.SystemClock.uptimeMillis()
        touch(plus, android.view.MotionEvent.ACTION_DOWN, now)
        touch(plus, android.view.MotionEvent.ACTION_UP, now + 80)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        val saved = LoadoutModel.load(PrefsKeyValue(a.getSharedPreferences("lab_loadout", android.content.Context.MODE_PRIVATE)))
        assertEquals(before + 1, saved.startMinute)
    }

    @Test
    fun `a scroll that starts on the minute plus and cancels before the first repeat saves nothing`() {
        val a = clearedLoadout()
        val prefs = a.getSharedPreferences("lab_loadout", android.content.Context.MODE_PRIVATE)
        val stored = prefs.all.toMap()
        val content = a.findViewById<android.view.View>(android.R.id.content)
        val plus = content.findViewWithTag<android.view.View>("minute-plus")
        val shown = content.findViewWithTag<android.widget.TextView>("minute-value").text.toString()
        val now = android.os.SystemClock.uptimeMillis()
        touch(plus, android.view.MotionEvent.ACTION_DOWN, now)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        touch(plus, android.view.MotionEvent.ACTION_CANCEL, now + 200)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(stored, prefs.all.toMap())
        assertTrue("rebuilt", plus === content.findViewWithTag<android.view.View>("minute-plus"))
        assertEquals(shown, content.findViewWithTag<android.widget.TextView>("minute-value").text.toString())
    }
}
