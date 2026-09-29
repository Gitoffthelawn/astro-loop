package com.astroloop.game.system

/**
 * The finale's gate.
 *
 * **It stopped being about launching.** Until stage 3 this asked whether Astro's launch in Astro
 * Loop with twelve bandanas should start the fight. The fight now lives in the BELT RUN cabinet and
 * begins when `PLAY` is pressed with the whole score table cleared, so the launch is
 * not a venue and a design decision is retired.
 *
 * `isAstroLoop` and `pilotId` are gone rather than ignored. Astro Loop is structurally guaranteed:
 * `insertCoinRect` is null outside it and is the only route to `openCabinet()`, which is the only
 * thing that sets `cabinetOpen`. An argument that is passed and never read is how a gate quietly
 * keeps checking the old thing.
 */
object CrystalReckoning {
    /** True iff `PLAY` should begin the reckoning rather than a free-play run. A design decision. */
    fun shouldEnter(allPilotsCleared: Boolean, crystalReleased: Boolean): Boolean =
        allPilotsCleared && !crystalReleased
}
