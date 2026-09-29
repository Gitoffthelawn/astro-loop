package com.astroloop.game.input

/**
 * Which kind of device produced the most recent input.
 *
 * This gates affordances, and each mode has exactly one, never both: TOUCH draws the stick
 * readout and no focus ring; DIRECTIONAL draws the focus ring and no stick readout. A player
 * who only ever touches the screen must never see a focus ring.
 */
enum class InputMode { TOUCH, DIRECTIONAL }

/**
 * Mode follows the last *event*, never device presence — a controller that is plugged in but
 * idle changes nothing.
 *
 * Written from the UI thread and read from GameThread, so the field is volatile. Same
 * treatment TouchController already gives its cross-thread state.
 */
object InputModeState {

    @Volatile
    var mode: InputMode = InputMode.TOUCH
        private set

    /**
     * The device that last spoke directionally, so a rumble can reach the thing in the player's
     * hands and a disconnect can tell whether it mattered.
     *
     * A touch changes the mode but not this: the pad is still the one they picked up.
     */
    @Volatile
    var activeDeviceId: Int = -1
        private set

    val isTouch: Boolean get() = mode == InputMode.TOUCH
    val isDirectional: Boolean get() = mode == InputMode.DIRECTIONAL

    fun markTouch() {
        mode = InputMode.TOUCH
    }

    fun markDirectional(deviceId: Int = activeDeviceId) {
        mode = InputMode.DIRECTIONAL
        activeDeviceId = deviceId
    }

    /** Test seam. Production code never calls this. */
    fun reset() {
        mode = InputMode.TOUCH
        activeDeviceId = -1
    }
}
