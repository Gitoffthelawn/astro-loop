package com.astroloop.game.lab

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.tuning.KnobsWeapons
import com.astroloop.game.tuning.Tunables
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LabApp::class)
class TuningEditorActivityTest {
    @After
    fun tearDown() = Tunables.resetAll()

    @Test
    fun `run knobs come first and a plus press edits and persists`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TuningEditorActivity::class.java)
            .putExtra(TuningEditorActivity.EXTRA_WEAPONS, arrayOf("railgun"))
            .putExtra(TuningEditorActivity.EXTRA_PASSIVES, arrayOf("glass_cannon"))
        val activity = Robolectric.buildActivity(TuningEditorActivity::class.java, intent).setup().get()
        assertEquals(EditorModel.IN_THIS_RUN, activity.headerTitles().first())
        activity.pressPlus("railgun.damage")
        assertEquals(85f, KnobsWeapons.railgun.damage.value, 0.001f)
        assertEquals(85f, LabApp.presets.active()!!.payload.values["railgun.damage"]!!, 0.001f)
    }

    @Test
    fun `a press redraws only the pressed knob's row`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TuningEditorActivity::class.java)
            .putExtra(TuningEditorActivity.EXTRA_WEAPONS, arrayOf("railgun"))
        val activity = Robolectric.buildActivity(TuningEditorActivity::class.java, intent).setup().get()
        val pressedBefore = activity.rowView("railgun.damage")!!
        val otherId = KnobsWeapons.railgun.speed!!.id
        val otherBefore = activity.rowView(otherId)!!
        activity.pressPlus("railgun.damage")
        assertNotSame(pressedBefore, activity.rowView("railgun.damage"))
        assertSame(otherBefore, activity.rowView(otherId))
    }

    @Test
    fun `parseTyped handles decimal commas and returns null for invalid input`() {
        // Decimal comma (German, French keyboards)
        assertEquals(1.5f, parseTyped("1,5")!!, 0.001f)
        // Decimal point
        assertEquals(2.25f, parseTyped("2.25")!!, 0.001f)
        // Whitespace and comma
        assertEquals(3.75f, parseTyped(" 3,75 ")!!, 0.001f)
        // Whitespace and point
        assertEquals(2.25f, parseTyped(" 2.25 ")!!, 0.001f)
        // Invalid text
        assertNull(parseTyped("abc"))
        // Empty string
        assertNull(parseTyped(""))
        // Whitespace only
        assertNull(parseTyped("   "))
        // Non-finite values
        assertNull(parseTyped("Infinity"))
        assertNull(parseTyped("NaN"))
    }

    private fun texts(v: android.view.View): List<String> = when (v) {
        is android.widget.TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `editing a base cooldown redraws its evolution's rate warning`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TuningEditorActivity::class.java)
            .putExtra(TuningEditorActivity.EXTRA_WEAPONS, arrayOf("pulse_cannon", "storm_cannon"))
        val activity = Robolectric.buildActivity(TuningEditorActivity::class.java, intent).setup().get()
        val rateId = KnobsWeapons.stormCannon.rate!!.id
        assertEquals(false, texts(activity.rowView(rateId)!!).any { it.startsWith("Very fast") })

        val cooldown = KnobsWeapons.pulseCannon.cooldown!!
        LabEdits.set(LabApp.presets, cooldown, 2f)
        activity.pressMinus(cooldown.id)
        assertEquals(1f, cooldown.raw, 0.001f)

        assertEquals(true, texts(activity.rowView(rateId)!!).any { it.startsWith("Very fast") })
    }

    @Test
    fun `rows in the run group name their weapon and other rows keep the plain label`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TuningEditorActivity::class.java)
            .putExtra(TuningEditorActivity.EXTRA_WEAPONS, arrayOf("railgun", "pulse_cannon"))
        val activity = Robolectric.buildActivity(TuningEditorActivity::class.java, intent).setup().get()
        val railgun = texts(activity.rowView("railgun.damage")!!)
        val pulse = texts(activity.rowView("pulse_cannon.damage")!!)
        assertEquals(true, railgun.any { it.startsWith("Railgun ${LabIcons.MARK} · ") })
        assertEquals(true, pulse.any { it.startsWith("Pulse Cannon ${LabIcons.MARK} · ") })
    }

    @Test
    fun `the count row shows what the weapon fires, highlighting the run's level`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TuningEditorActivity::class.java)
            .putExtra(TuningEditorActivity.EXTRA_WEAPONS, arrayOf("scatter_shot"))
            .putExtra(TuningEditorActivity.EXTRA_WEAPON_LEVELS, intArrayOf(3))
        val activity = Robolectric.buildActivity(TuningEditorActivity::class.java, intent).setup().get()
        assertEquals("Fires per level: 5 · 7 · 9 · 11 · 13 (+1 with Duplicator)", activity.effectiveText("scatter_shot.count"))
    }

    @Test
    fun `editing a weapon knob refreshes its effective line`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TuningEditorActivity::class.java)
            .putExtra(TuningEditorActivity.EXTRA_WEAPONS, arrayOf("pulse_cannon"))
            .putExtra(TuningEditorActivity.EXTRA_WEAPON_LEVELS, intArrayOf(1))
        val activity = Robolectric.buildActivity(TuningEditorActivity::class.java, intent).setup().get()
        activity.pressPlus("pulse_cannon.count")
        assertEquals("Fires per level: 2 · 3 · 4 · 5 · 6 (+1 with Duplicator)", activity.effectiveText("pulse_cannon.count"))
    }

    @Test
    fun `editing duplicator refreshes every visible effective line`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TuningEditorActivity::class.java)
            .putExtra(TuningEditorActivity.EXTRA_WEAPONS, arrayOf("pulse_cannon"))
            .putExtra(TuningEditorActivity.EXTRA_WEAPON_LEVELS, intArrayOf(1))
            .putExtra(TuningEditorActivity.EXTRA_PASSIVES, arrayOf("duplicator_core"))
        val activity = Robolectric.buildActivity(TuningEditorActivity::class.java, intent).setup().get()
        activity.pressPlus("duplicator_core.extra_projectiles")
        assertEquals("Fires per level: 1 · 2 · 3 · 4 · 5 (+2 with Duplicator)", activity.effectiveText("pulse_cannon.count"))
    }

    @Test
    fun `weapon and passive headers carry their icon after the name`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TuningEditorActivity::class.java)
        val activity = Robolectric.buildActivity(TuningEditorActivity::class.java, intent).setup().get()
        assertEquals(true, activity.headerTexts().contains("Weapons/Pulse Cannon ${LabIcons.MARK}"))
        assertEquals(true, activity.headerTexts().contains("Passives/Nano Repair ${LabIcons.MARK}"))
        assertEquals(true, activity.headerTexts().any { it.startsWith("Asteroids/") && !it.contains(LabIcons.MARK) })
    }

    @Test
    fun `holding plus keeps stepping without rebuilding the row, and saves on release`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TuningEditorActivity::class.java)
            .putExtra(TuningEditorActivity.EXTRA_WEAPONS, arrayOf("pulse_cannon"))
        val activity = Robolectric.buildActivity(TuningEditorActivity::class.java, intent).setup().get()
        val plus = activity.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.view.View>("plus:pulse_cannon.damage")
        val now = android.os.SystemClock.uptimeMillis()
        plus.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 5f, 5f, 0))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(700, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(true, KnobsWeapons.pulseCannon.damage.value >= 18f)
        assertSame(plus, activity.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.view.View>("plus:pulse_cannon.damage"))
        assertNull(LabApp.presets.active()!!.payload.values["pulse_cannon.damage"])
        plus.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now + 700, android.view.MotionEvent.ACTION_UP, 5f, 5f, 0))
        assertEquals(KnobsWeapons.pulseCannon.damage.value, LabApp.presets.active()!!.payload.values["pulse_cannon.damage"]!!, 0f)
    }

    @Test
    fun `a scroll that starts on plus and cancels before the first repeat changes and saves nothing`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TuningEditorActivity::class.java)
            .putExtra(TuningEditorActivity.EXTRA_WEAPONS, arrayOf("pulse_cannon"))
        val activity = Robolectric.buildActivity(TuningEditorActivity::class.java, intent).setup().get()
        val before = KnobsWeapons.pulseCannon.damage.value
        val active = LabApp.presets.active()
        val plus = activity.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.view.View>("plus:pulse_cannon.damage")
        val now = android.os.SystemClock.uptimeMillis()
        plus.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 5f, 5f, 0))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        plus.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now + 200, android.view.MotionEvent.ACTION_CANCEL, 5f, 5f, 0))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before, KnobsWeapons.pulseCannon.damage.value, 0f)
        assertEquals(active, LabApp.presets.active())
        assertSame(plus, activity.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.view.View>("plus:pulse_cannon.damage"))
    }

    @Test
    fun `a tap on plus steps once and saves`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TuningEditorActivity::class.java)
            .putExtra(TuningEditorActivity.EXTRA_WEAPONS, arrayOf("pulse_cannon"))
        val activity = Robolectric.buildActivity(TuningEditorActivity::class.java, intent).setup().get()
        val knob = KnobsWeapons.pulseCannon.damage
        val before = knob.value
        val plus = activity.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.view.View>("plus:pulse_cannon.damage")
        val now = android.os.SystemClock.uptimeMillis()
        plus.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 5f, 5f, 0))
        plus.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now + 80, android.view.MotionEvent.ACTION_UP, 5f, 5f, 0))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        val after = knob.value
        assertEquals(true, after > before)
        activity.pressPlus("pulse_cannon.damage")
        assertEquals(knob.value - after, after - before, 0.0001f)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(knob.value, LabApp.presets.active()!!.payload.values["pulse_cannon.damage"]!!, 0f)
    }
}
