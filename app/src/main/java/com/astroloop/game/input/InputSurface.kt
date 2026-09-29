package com.astroloop.game.input

/**
 * A screen that can be driven by a directional device.
 *
 * Every method defaults to "not consumed", so a view overrides only the verbs it has. The
 * router does not know whether a direction means "fly" or "move focus" — the surface decides,
 * from state it already has. See the context table in the design doc.
 */
interface InputSurface {

    /** Continuous held state. Combat reads this; menus ignore it. */
    fun onDirectionalState(input: DirectionalInput) {}

    /** One discrete press, including system key repeat. Menus read this; combat ignores it. */
    fun onDirectionalPress(direction: Direction): Boolean = false

    fun onActivateDown(): Boolean = false

    fun onActivateUp(): Boolean = false

    /** Back or cancel. During play this pauses; in a menu it goes back. */
    fun onCancel(): Boolean = false

    /** A dedicated pause button (gamepad Start, keyboard P). */
    fun onPause(): Boolean = false

    fun onPage(delta: Int): Boolean = false
}
