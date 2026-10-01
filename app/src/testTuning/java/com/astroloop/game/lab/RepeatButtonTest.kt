package com.astroloop.game.lab

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LabApp::class)
class RepeatButtonTest {
    private var steps = 0
    private var releases = 0
    private var limit = Int.MAX_VALUE

    private fun button() = LabUi.repeatButton(ApplicationProvider.getApplicationContext(), "+",
        onStep = { if (steps >= limit) false else { steps++; true } },
        onRelease = { releases++ }).also { it.layout(0, 0, 100, 100) }

    private fun touch(b: android.view.View, action: Int, x: Float = 5f, y: Float = 5f) {
        val now = SystemClock.uptimeMillis()
        b.dispatchTouchEvent(MotionEvent.obtain(now, now, action, x, y, 0))
    }

    @Test
    fun `schedule accelerates from 150 to 40 ms`() {
        assertEquals(150L, RepeatSchedule.intervalMs(0))
        assertEquals(95L, RepeatSchedule.intervalMs(750))
        assertEquals(40L, RepeatSchedule.intervalMs(1500))
        assertEquals(40L, RepeatSchedule.intervalMs(5000))
    }

    @Test
    fun `a tap steps once on release`() {
        val b = button()
        touch(b, MotionEvent.ACTION_DOWN)
        assertEquals(0, steps)
        assertTrue(b.isPressed)
        touch(b, MotionEvent.ACTION_UP)
        assertEquals(1, steps)
        assertEquals(1, releases)
        assertFalse(b.isPressed)
        ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(1, steps)
        assertEquals(1, releases)
    }

    @Test
    fun `a touch let go outside the button is not a tap`() {
        val b = button()
        touch(b, MotionEvent.ACTION_DOWN)
        touch(b, MotionEvent.ACTION_UP, x = 400f, y = 5f)
        assertEquals(0, steps)
        assertEquals(1, releases)
        assertFalse(b.isPressed)
        ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(0, steps)
        assertEquals(1, releases)
    }

    @Test
    fun `holding steps at the first delay, repeats faster and faster, release adds no step`() {
        val b = button()
        touch(b, MotionEvent.ACTION_DOWN)
        assertEquals(0, steps)
        ShadowLooper.idleMainLooper(399, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(0, steps)
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(1, steps)
        ShadowLooper.idleMainLooper(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(2, steps)
        ShadowLooper.idleMainLooper(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
        val held = steps
        assert(held > 30) { "only $held steps in 3.55 s" }
        touch(b, MotionEvent.ACTION_UP)
        ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(held, steps)
        assertEquals(1, releases)
    }

    @Test
    fun `a cancel before the first delay steps zero times and releases once`() {
        val b = button()
        touch(b, MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        touch(b, MotionEvent.ACTION_CANCEL)
        ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(0, steps)
        assertEquals(1, releases)
        assertFalse(b.isPressed)
    }

    @Test
    fun `losing window focus ends a hold and releases once`() {
        val b = button()
        touch(b, MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        val held = steps
        assertTrue("only $held", held >= 2)
        b.onWindowFocusChanged(false)
        assertEquals(1, releases)
        assertFalse(b.isPressed)
        ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        touch(b, MotionEvent.ACTION_UP)
        assertEquals(held, steps)
        assertEquals(1, releases)
    }

    @Test
    fun `repeating stops at the limit`() {
        limit = 3
        val b = button()
        touch(b, MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(3, steps)
        touch(b, MotionEvent.ACTION_UP)
        assertEquals(1, releases)
    }

    @Test
    fun `a held controller button steps at once, repeats, and shows as pressed`() {
        val b = button()
        b.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(1, steps)
        assertTrue(b.isPressed)
        ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        val held = steps
        assert(held > 3) { "only $held" }
        b.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(1, releases)
        assertFalse(b.isPressed)
        assertEquals(held, steps)
    }

    @Test
    fun `performClick is one step and one release`() {
        val b = button()
        b.performClick()
        assertEquals(1, steps)
        assertEquals(1, releases)
    }
}
