package com.astroloop.game.input

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InputRouterTest {

    private class FakeSurface : InputSurface {
        var magnitude = 0f
        var directionX = 0f
        val presses = mutableListOf<Direction>()
        var activateDowns = 0
        var cancels = 0
        var pauses = 0
        var pageDelta = 0

        override fun onDirectionalState(input: DirectionalInput) {
            magnitude = input.magnitude
            directionX = input.direction.x
        }

        override fun onDirectionalPress(direction: Direction): Boolean {
            presses.add(direction)
            return true
        }

        override fun onActivateDown(): Boolean { activateDowns++; return true }
        override fun onCancel(): Boolean { cancels++; return true }
        override fun onPause(): Boolean { pauses++; return true }
        override fun onPage(delta: Int): Boolean { pageDelta += delta; return true }
    }

    private lateinit var router: InputRouter
    private lateinit var surface: FakeSurface

    @Before
    fun setup() {
        InputModeState.reset()
        surface = FakeSurface()
        router = InputRouter()
        router.surface = surface
    }

    /** deviceId 1 stands for real hardware; the virtual keyboard is always id -1. */
    private fun key(
        code: Int,
        action: Int,
        source: Int = InputDevice.SOURCE_GAMEPAD,
        repeat: Int = 0,
        deviceId: Int = 1
    ) = KeyEvent(0L, 0L, action, code, repeat, 0, deviceId, 0, 0, source)

    @Test
    fun `a dpad press switches to directional mode`() {
        router.onKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN))

        assertTrue(InputModeState.isDirectional)
    }

    @Test
    fun `a soft keyboard press must not switch mode`() {
        // An on-screen IME injects events that really do carry SOURCE_KEYBOARD — the only thing
        // separating them from a plugged-in keyboard is the virtual device id. Getting this
        // wrong strands a touch player looking at a focus ring they cannot move.
        router.onKey(
            key(
                KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN,
                source = InputDevice.SOURCE_KEYBOARD,
                deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD
            )
        )

        assertTrue(InputModeState.isTouch)
        assertTrue("and it must not steer the ship either", surface.presses.isEmpty())
    }

    @Test
    fun `a real keyboard press does switch mode`() {
        router.onKey(key(KeyEvent.KEYCODE_W, KeyEvent.ACTION_DOWN, source = InputDevice.SOURCE_KEYBOARD))

        assertTrue(InputModeState.isDirectional)
    }

    @Test
    fun `holding a direction reports continuous state`() {
        router.onKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN))

        assertEquals(1f, surface.magnitude, 0.0001f)
        assertEquals(1f, surface.directionX, 0.0001f)
    }

    @Test
    fun `releasing a direction returns to rest`() {
        router.onKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN))
        router.onKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP))

        assertEquals(0f, surface.magnitude, 0.0001f)
    }

    @Test
    fun `a direction press also emits one discrete press for focus`() {
        router.onKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN))

        assertEquals(listOf(Direction.RIGHT), surface.presses)
    }

    @Test
    fun `releasing a direction emits no focus press`() {
        router.onKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN))
        router.onKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP))

        assertEquals("a release must not move focus a second time", 1, surface.presses.size)
    }

    @Test
    fun `system key repeat drives focus repeat`() {
        router.onKey(key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN))
        router.onKey(key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, repeat = 1))

        assertEquals(listOf(Direction.DOWN, Direction.DOWN), surface.presses)
    }

    @Test
    fun `WASD maps to directions`() {
        router.onKey(key(KeyEvent.KEYCODE_W, KeyEvent.ACTION_DOWN, source = InputDevice.SOURCE_KEYBOARD))
        router.onKey(key(KeyEvent.KEYCODE_D, KeyEvent.ACTION_DOWN, source = InputDevice.SOURCE_KEYBOARD))

        assertEquals(listOf(Direction.UP, Direction.RIGHT), surface.presses)
        // Held diagonal stays a unit vector.
        assertEquals(1f, surface.magnitude, 0.0001f)
    }

    @Test
    fun `A and Enter and DPAD_CENTER all activate`() {
        router.onKey(key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
        router.onKey(key(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, source = InputDevice.SOURCE_KEYBOARD))
        router.onKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN))

        assertEquals(3, surface.activateDowns)
    }

    @Test
    fun `A confirms and B goes back`() {
        // The platform convention, by position: bottom confirms, right goes back.
        router.onKey(key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
        router.onKey(key(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN))

        assertEquals("only A confirms", 1, surface.activateDowns)
        assertEquals("B is back", 1, surface.cancels)
    }

    @Test
    fun `X and Y both pause`() {
        router.onKey(key(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.ACTION_DOWN))
        router.onKey(key(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.ACTION_DOWN))

        assertEquals(2, surface.pauses)
    }

    @Test
    fun `Escape and Select cancel, Start pauses`() {
        // Select is the small button: Switch minus, Xbox View, PlayStation Share. It is the pad's
        // Back now that B confirms.
        router.onKey(key(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_DOWN, source = InputDevice.SOURCE_KEYBOARD))
        router.onKey(key(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.ACTION_DOWN))
        router.onKey(key(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_DOWN))

        assertEquals(2, surface.cancels)
        assertEquals(1, surface.pauses)
    }

    @Test
    fun `shoulders and QE page in both directions`() {
        router.onKey(key(KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.ACTION_DOWN))
        router.onKey(key(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.ACTION_DOWN))
        router.onKey(key(KeyEvent.KEYCODE_E, KeyEvent.ACTION_DOWN, source = InputDevice.SOURCE_KEYBOARD))

        assertEquals(1, surface.pageDelta)
    }

    @Test
    fun `an unknown key is not consumed`() {
        assertFalse(router.onKey(key(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN)))
    }

    @Test
    fun `reset drops held directions`() {
        router.onKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN))
        router.reset()
        router.onKey(key(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN))

        // Without the reset, RIGHT would still be held and this would read as a diagonal —
        // which is what a swap between screens would leave behind.
        assertEquals(0f, surface.directionX, 0.0001f)
    }

    /** A joystick-source motion event, the shape a gamepad's sticks, hat and triggers arrive in. */
    private fun joystick(
        x: Float = 0f,
        y: Float = 0f,
        hatX: Float = 0f,
        hatY: Float = 0f,
        lt: Float = 0f,
        rt: Float = 0f,
        deviceId: Int = 1
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0 }
        val coords = MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, x)
            setAxisValue(MotionEvent.AXIS_Y, y)
            setAxisValue(MotionEvent.AXIS_HAT_X, hatX)
            setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
            setAxisValue(MotionEvent.AXIS_LTRIGGER, lt)
            setAxisValue(MotionEvent.AXIS_RTRIGGER, rt)
        }
        return MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_MOVE, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_JOYSTICK, 0
        )
    }

    @Test
    fun `a stick push steers`() {
        // Guards the helper as much as the router: if the axes never reached the event, the hat
        // tests below could pass or fail for the wrong reason.
        router.onMotion(joystick(x = 1f))

        assertEquals(1f, surface.magnitude, 0.0001f)
        assertEquals(1f, surface.directionX, 0.0001f)
    }

    @Test
    fun `a gamepad D-pad on the hat axes steers like D-pad keys`() {
        // Xbox and DualSense pads report the D-pad as AXIS_HAT_X/Y rather than as DPAD keys. The
        // system would synthesise keys from an unhandled hat, but onMotion handles every joystick
        // event, so nothing else ever sees it.
        router.onMotion(joystick(hatX = 1f))

        assertEquals(1f, surface.magnitude, 0.0001f)
        assertEquals(1f, surface.directionX, 0.0001f)
    }

    @Test
    fun `a hat press moves focus once`() {
        router.onMotion(joystick(hatX = 1f))

        // Once, not twice: a held hat reads as full magnitude, which must not also count as a flick.
        assertEquals(listOf(Direction.RIGHT), surface.presses)
    }

    @Test
    fun `holding the hat does not repeat the focus press`() {
        router.onMotion(joystick(hatY = -1f))
        router.onMotion(joystick(x = 0.05f, hatY = -1f))

        assertEquals(listOf(Direction.UP), surface.presses)
    }

    @Test
    fun `releasing the hat returns to rest`() {
        router.onMotion(joystick(hatX = -1f))
        router.onMotion(joystick())

        assertEquals(0f, surface.magnitude, 0.0001f)
        assertEquals(1, surface.presses.size)
    }

    @Test
    fun `a hat press makes the pad the active input`() {
        router.onMotion(joystick(hatX = 1f, deviceId = 7))

        assertTrue(InputModeState.isDirectional)
        assertEquals("so losing this pad mid-run pauses", 7, router.lastDeviceId)
    }

    @Test
    fun `the trigger buttons page, like the shoulders`() {
        // Switch Pro and DualSense send ZL/ZR and L2/R2 as buttons.
        router.onKey(key(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.ACTION_DOWN))
        assertEquals(1, surface.pageDelta)

        router.onKey(key(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.ACTION_DOWN))
        assertEquals(0, surface.pageDelta)
    }

    @Test
    fun `an analog trigger pages once per pull`() {
        // An Xbox pad sends no key at all for its triggers — only the axis.
        router.onMotion(joystick(rt = 0.8f))

        assertEquals(1, surface.pageDelta)
    }

    @Test
    fun `holding an analog trigger does not run through the pages`() {
        router.onMotion(joystick(rt = 0.8f))
        router.onMotion(joystick(rt = 0.9f))
        router.onMotion(joystick(rt = 1f))

        assertEquals("a held trigger is one page change, not three", 1, surface.pageDelta)
    }

    @Test
    fun `releasing an analog trigger re-arms it`() {
        router.onMotion(joystick(rt = 0.8f))
        router.onMotion(joystick(rt = 0.1f))
        router.onMotion(joystick(rt = 0.8f))

        assertEquals(2, surface.pageDelta)
    }

    @Test
    fun `the left analog trigger pages backwards`() {
        router.onMotion(joystick(lt = 0.8f))

        assertEquals(-1, surface.pageDelta)
    }
}
