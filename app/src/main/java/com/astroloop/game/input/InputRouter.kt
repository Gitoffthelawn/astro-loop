package com.astroloop.game.input

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Turns raw key and motion events into the small set of verbs a screen understands.
 *
 * Lives at the Activity because MainActivity swaps a single content view, so device
 * classification happens once rather than in each of the three SurfaceViews.
 *
 * Focus repeat rides on the system's own key repeat (`event.repeatCount`) rather than a timer
 * of our own — the platform already has the initial delay and rate a user expects, and
 * movement simply ignores repeats because it is a held state.
 */
class InputRouter {

    var surface: InputSurface? = null

    /**
     * The device that last spoke, so a disconnect can tell whether it mattered.
     *
     * Read from the shared state rather than kept here: the rumble target reads the same field, and
     * two copies of "which pad is the player holding" is how the two drift apart.
     */
    val lastDeviceId: Int get() = InputModeState.activeDeviceId

    private val directional = DirectionalInput()

    private var left = false
    private var right = false
    private var up = false
    private var down = false

    /** A gamepad D-pad that arrives as the hat axes rather than as keys: -1, 0 or 1 on each. */
    private var hatX = 0
    private var hatY = 0

    /** Stick flick edge detection, so a stick can move focus in menus. */
    private var flickLatched = false

    /** Analog triggers, latched like the flick: one page change per pull, not one per frame. */
    private var leftTriggerLatched = false
    private var rightTriggerLatched = false

    fun reset() {
        left = false; right = false; up = false; down = false
        hatX = 0; hatY = 0
        flickLatched = false
        leftTriggerLatched = false; rightTriggerLatched = false
        directional.clear()
        surface?.onDirectionalState(directional)
    }

    fun onKey(event: KeyEvent): Boolean {
        if (!isRealDirectionalHardware(event)) return false

        val pressed = event.action == KeyEvent.ACTION_DOWN
        val target = surface

        directionFor(event.keyCode)?.let { direction ->
            markDirectional(event)
            setHeld(direction, pressed)
            pushState()
            // Repeats drive focus only; movement is already held.
            if (pressed) target?.onDirectionalPress(direction)
            return true
        }

        // Everything below acts on the press edge, not on repeats.
        if (event.repeatCount > 0) return true

        return when (event.keyCode) {
            // The bottom face button confirms and the right one goes back — the Android, Xbox and
            // PlayStation convention (owner, 2026-09-29, replacing "both confirm"). Android reports
            // face buttons by POSITION, so this is bottom = A / cross, right = B / circle on every
            // pad family the game meets, a Switch Pro included.
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> {
                markDirectional(event)
                if (pressed) target?.onActivateDown() else target?.onActivateUp()
                true
            }

            // Back. B is the pad's own; the small select button — Switch minus, Xbox View,
            // PlayStation Share — keeps the verb it carried while B confirmed, as a second way out.
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_ESCAPE -> {
                markDirectional(event)
                if (pressed) target?.onCancel()
                true
            }

            // The two upper-left face buttons, and Start, and P.
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_P -> {
                markDirectional(event)
                if (pressed) target?.onPause()
                true
            }

            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_E -> {
                markDirectional(event)
                if (pressed) target?.onPage(1)
                true
            }

            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_Q -> {
                markDirectional(event)
                if (pressed) target?.onPage(-1)
                true
            }

            else -> false
        }
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false

        val flat = event.device?.getMotionRange(MotionEvent.AXIS_X, event.source)?.flat ?: DEFAULT_FLAT

        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        directional.setStick(x, y, flat)
        val stickActive = directional.isActive

        // A stick flick also moves focus, so a gamepad player never has to reach for the D-pad
        // in a menu. Latched, so holding the stick does not scroll away uncontrollably. Decided on
        // the stick alone, before a held hat fills the shared state with full magnitude.
        var flick: Direction? = null
        if (directional.magnitude > FLICK_ON && !flickLatched) {
            flickLatched = true
            val d = directional.direction
            flick = if (abs(d.x) > abs(d.y)) {
                if (d.x > 0) Direction.RIGHT else Direction.LEFT
            } else {
                if (d.y > 0) Direction.DOWN else Direction.UP
            }
        } else if (directional.magnitude < FLICK_OFF) {
            flickLatched = false
        }

        // Most gamepads — Xbox and DualSense among them — report the D-pad as the hat axes, not as
        // DPAD keys. The system only synthesises keys from a hat nobody handled, and this method
        // handles every joystick event, so the hat is read here or not at all.
        val newHatX = hatStep(event.getAxisValue(MotionEvent.AXIS_HAT_X))
        val newHatY = hatStep(event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        val pressX = if (newHatX != 0 && newHatX != hatX) newHatX else 0
        val pressY = if (newHatY != 0 && newHatY != hatY) newHatY else 0
        hatX = newHatX
        hatY = newHatY

        // An Xbox pad reports its triggers only as axes, and some pads spell them BRAKE and GAS,
        // so each side takes the larger of its pair. Latched on the way up, or a finger resting on
        // a trigger would walk through every page.
        val leftPull = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE)
        )
        val rightPull = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS)
        )
        var page = 0
        if (leftPull > TRIGGER_ON && !leftTriggerLatched) {
            leftTriggerLatched = true
            page = -1
        } else if (leftPull < TRIGGER_OFF) {
            leftTriggerLatched = false
        }
        if (rightPull > TRIGGER_ON && !rightTriggerLatched) {
            rightTriggerLatched = true
            page = 1
        } else if (rightPull < TRIGGER_OFF) {
            rightTriggerLatched = false
        }
        if (page != 0) {
            InputModeState.markDirectional(event.deviceId)
        }

        // A stick at rest hands movement back to whatever digital direction is still held.
        if (!stickActive) applyDigital()

        if (stickActive || hatX != 0 || hatY != 0) {
            InputModeState.markDirectional(event.deviceId)
        }
        surface?.onDirectionalState(directional)

        // Press edges only, like a D-pad key's ACTION_DOWN. A hat has no system key repeat, so
        // holding it steers the ship but steps focus once.
        if (pressX != 0) surface?.onDirectionalPress(if (pressX > 0) Direction.RIGHT else Direction.LEFT)
        if (pressY != 0) surface?.onDirectionalPress(if (pressY > 0) Direction.DOWN else Direction.UP)
        flick?.let { surface?.onDirectionalPress(it) }
        if (page != 0) surface?.onPage(page)
        return true
    }

    private fun setHeld(direction: Direction, pressed: Boolean) {
        when (direction) {
            Direction.LEFT -> left = pressed
            Direction.RIGHT -> right = pressed
            Direction.UP -> up = pressed
            Direction.DOWN -> down = pressed
        }
    }

    private fun pushState() {
        applyDigital()
        surface?.onDirectionalState(directional)
    }

    /** Keys and the hat are both digital, so either can hold a direction. */
    private fun applyDigital() {
        directional.setDigital(left || hatX < 0, right || hatX > 0, up || hatY < 0, down || hatY > 0)
    }

    private fun hatStep(value: Float): Int = when {
        value > HAT_ON -> 1
        value < -HAT_ON -> -1
        else -> 0
    }

    private fun markDirectional(event: KeyEvent) {
        InputModeState.markDirectional(event.deviceId)
    }

    private fun directionFor(keyCode: Int): Direction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Direction.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Direction.RIGHT
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Direction.UP
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Direction.DOWN
        else -> null
    }

    /**
     * A soft keyboard must never flip the input mode — it would strand a touch player looking
     * at a focus ring they cannot move.
     *
     * An on-screen IME really does inject events carrying SOURCE_KEYBOARD, so the source alone
     * cannot separate it from a plugged-in keyboard. The virtual device id is what does:
     * anything the system synthesises arrives as KeyCharacterMap.VIRTUAL_KEYBOARD.
     */
    private fun isRealDirectionalHardware(event: KeyEvent): Boolean {
        if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
        val source = event.source
        return source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
    }

    private companion object {
        const val FLICK_ON = 0.6f
        const val FLICK_OFF = 0.4f

        /** Same shape as the flick: a high edge to fire on, a low one to re-arm. */
        const val TRIGGER_ON = 0.6f
        const val TRIGGER_OFF = 0.3f

        /** A hat reports exactly -1, 0 or 1; the threshold only keeps a noisy one from chattering. */
        const val HAT_ON = 0.5f

        /** Used only when a device declines to report its own dead zone. */
        const val DEFAULT_FLAT = 0.15f
    }
}

/**
 * Pause only when the player actually loses the thing they were playing with. Unplugging a
 * spare controller, or one that was never used, must not interrupt a run — and a touch player
 * must never be interrupted at all, which is the case that matters most: on a phone a Bluetooth
 * pad going to sleep in a drawer would otherwise stop somebody's game.
 */
internal fun shouldPauseOnDisconnect(wasLastSource: Boolean, mode: InputMode): Boolean =
    wasLastSource && mode == InputMode.DIRECTIONAL
