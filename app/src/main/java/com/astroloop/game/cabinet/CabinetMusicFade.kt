package com.astroloop.game.cabinet

/**
 * When the hangar bed gets out of the way — a design decision.
 *
 * The hangar music is **not** silenced for the whole cabinet session, and it is not ducked
 * either. It plays on under the bezel and the machine's menu — which is where you are picking
 * a pilot and reading a high score table, and where the room should still feel like the
 * hangar. The moment a RUN starts it fades to nothing over a few seconds and stays gone until
 * you leave the machine.
 *
 * **A latch, not a volume.** `SoundManager.stopAmbient(fadeOutMillis)` already owns the ramp,
 * on its own thread, in 20ms steps. The only thing with edges in it is *which single frame*
 * asks for that ramp, so that is all this decides — and the reason it is a class rather than
 * an `if` in the host is that the host is view code no test can reach, which is where every
 * escaped defect on this branch has lived.
 *
 * Shaped after [CabinetHeartbeat]: a small stateful helper the host ticks, returning true on
 * the frame something should happen.
 */
class CabinetMusicFade {

    /**
     * True once the bed has been asked to fade, for the rest of this cabinet session.
     *
     * The host reads it on the way out to decide whether the hangar needs its music put
     * back — a session spent reading the high scores never touched it, and restarting a
     * track that was already playing would be an audible hiccup for nothing.
     */
    var faded: Boolean = false
        private set

    /**
     * Tick with the machine's current screen.
     *
     * @return true on the one frame the fade should be started.
     */
    fun update(screen: CabinetScreen): Boolean {
        // PLAY is the only screen that means a run is actually under way — and it covers the
        // reckoning too, which runs on PLAY and holds there through its ending.
        if (faded || screen != CabinetScreen.PLAY) return false
        faded = true
        return true
    }

    /**
     * Re-arm for a new cabinet session. Called when the machine is opened, not when a run
     * ends: dying and hitting AGAIN? must not swell the music back up between attempts, and
     * the reckoning is a thing people retry.
     */
    fun reset() { faded = false }

    companion object {
        /**
         * How long the bed takes to go, milliseconds.
         *
         * Long enough to read as the room receding rather than as a cut — the owner asked
         * for "a few seconds" — and comfortably longer than the CRT power-up (0.25s), so the
         * machine is already lit and running while the hangar is still fading behind it.
         */
        const val FADE_OUT_MILLIS = 3000L
    }
}
