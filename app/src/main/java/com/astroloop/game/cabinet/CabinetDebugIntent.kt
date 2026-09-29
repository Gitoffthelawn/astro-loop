package com.astroloop.game.cabinet

/**
 * A one-shot request from the debug menu to the hangar.
 *
 * The debug menu lives in `GameSurfaceView` and the cabinet overlay lives in
 * `HangarSurfaceView`, with no shared state and no callback between them — which is why
 * stage 1 drew OPEN CABINET and PLAY NOW dimmed and inert. The route that does exist is
 * `onGameOver(0, false)`: `RESET_SMALL`, `RESET_BIG` and `SET_CORRUPT` all use it, and it
 * rebuilds the hangar through `MainActivity.returnToHangar`. This object is what rides it.
 *
 * **Deliberately in-memory, not persisted.** A `SharedPreferences` key would survive a
 * crash and force-open the cabinet on the next boot — and process death is exactly when a
 * debug request *should* be forgotten.
 *
 * **Consume-once**, the same latch shape as `CabinetSim.consumeWaveStart()` and for the
 * same reason: the hangar is rebuilt more than once per session, so a request that
 * survived its own delivery would fire again on every return to the bar.
 *
 * **Both [request] and [consume] currently run on the UI thread**, sequentially: the debug
 * tap arrives through `GameSurfaceView.onTouchEvent`, and the consume site sits in
 * `HangarSurfaceView.resetForReturn`, which `MainActivity.swapToHangar` calls from a
 * `decorView.post` *before* `hangarView.resume()` starts the render thread. So the safety
 * here is single-threaded sequencing, not the `@Volatile`.
 *
 * The `@Volatile` is kept as cheap insurance for the obvious future change — moving the
 * consume into the hangar's `update()`, which does run on the render thread. It is stated
 * this way round deliberately: a comment claiming a cross-thread guarantee the code does
 * not actually rely on is how a hazard comes to look safe, and this branch has already
 * lost a device pass to exactly that (`CabinetShellRenderer`'s rects, a device pass).
 */
object CabinetDebugIntent {

    enum class Action { OPEN, PLAY, RECKONING }

    /**
     * @param phase only meaningful for [Action.RECKONING]. 0 plays the authored opening;
     *   1..5 drop straight into that phase with the field already cleared.
     * @param lap only meaningful alongside a nonzero [phase]: which lap of the director's
     *   escalation to land on. Defaults to 1, the pattern's authored difficulty.
     */
    data class Request(val action: Action, val phase: Int, val lap: Int = 1)

    @Volatile
    private var pending: Request? = null

    fun request(action: Action, phase: Int = 0, lap: Int = 1) {
        pending = Request(action, phase, lap)
    }

    /** @return the pending request, or null. Never returns the same request twice. */
    fun consume(): Request? {
        val r = pending
        pending = null
        return r
    }

    fun clear() { pending = null }
}
