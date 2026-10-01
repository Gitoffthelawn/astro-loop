package com.astroloop.game.tuning

/**
 * What a build that watches runs (the Lab) is told. The game installs no listener, so none of
 * this runs there.
 */
interface RunListener {
    /** A run has just been set up, including one restored after the process was recreated. */
    fun onRunStarted() {}

    /** A run has ended, by any route; called on the game thread just before the run hands back. */
    fun onRunEnded(summary: RunSummary) {}

    /** update() threw. The game thread skips the frame and carries on; the listener may record it. */
    fun onUpdateError(error: Throwable) {}

    /** Whether the pause screen should offer TUNE. The game offers nothing. */
    fun tuneAvailable(): Boolean = false

    /**
     * Whether the pause screen should offer QUIT, which ends the run as any run end does. The game offers nothing.
     * QUIT is drawn beside TUNE on the plain pause overlay, so it is honoured only when [tuneAvailable] is also true.
     */
    fun quitAvailable(): Boolean = false

    /**
     * The player asked to tune from the pause screen. Called on the UI thread with the paused
     * run's activity, its weapons with their levels in HUD order, and its passives; the run stays
     * paused until the player resumes it.
     */
    fun onTuneRequested(host: android.app.Activity, weaponLevels: Map<String, Int>, passiveIds: List<String>) {}
}

object RunHooks {
    @Volatile
    var listener: RunListener? = null
}
