package com.astroloop.game.cabinet

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ambient drift for the marquee plate below the main CRT — a handful of rocks wandering
 * behind the `BELT RUN` lettering, per the 2026-08-16 polish pass design.
 *
 * This is deliberately not a second `CabinetSim`: no ship, no bullets, no collisions, no
 * scoring. The big CRT above already runs the real attract demo and has to stay the thing
 * that pulls a player's eye; the plate is ambience, so it can be *only* ambience.
 *
 * Pure and Android-free like the rest of `cabinet/`: no `Canvas`, `Random` is injected.
 */
class CabinetMarqueeDrift(width: Float, height: Float, rng: Random) {

    /**
     * Builds its own metrics sized to the plate rather than sharing the caller's, purely
     * so [CabinetRock.update] gives drift, spin and toroidal wrap for free — the plate
     * wraps as its own small torus, independent of the playfield above it.
     */
    val metrics = CabinetMetrics(width, height)

    /**
     * Plate-scaled radii, standing in for [CabinetMetrics.rockRadius].
     *
     * The standard radii are fractions of `minEdge`, which at plate scale is the plate's
     * own *height* (the plate is much wider than it is tall) — a SMALL rock at 0.020 of a
     * ~130px-tall plate would come out under 3px, an unreadable fleck rather than a
     * silhouette. These map [RockSize] onto plate-scaled fractions of [CabinetMetrics.height]
     * instead. Do not "fix" this back to `metrics.rockRadius(size)` — that is the exact
     * regression `CabinetMarqueeDriftTest.radiusOfIsPlateScaledNotTheTinyCabinetMetricsValue`
     * exists to catch.
     */
    fun radiusOf(rock: CabinetRock): Float = metrics.height * when (rock.size) {
        RockSize.LARGE -> 0.30f
        RockSize.MEDIUM -> 0.22f
        RockSize.SMALL -> 0.15f
    }

    /** Four rocks, a mix of sizes, random starts and directions. */
    val rocks: List<CabinetRock> = List(ROCK_COUNT) { i ->
        val size = SIZE_MIX[i % SIZE_MIX.size]
        val heading = rng.nextFloat() * 2f * PI.toFloat()
        // A fraction of the plate's WIDTH per second, not its minEdge/height like the
        // playfield's own rockBaseSpeed — the plate is a wide strip, so a height-relative
        // speed would have a rock cross it in a fraction of a second. This range is slow
        // enough to read as ambience: several seconds to cross corner to corner.
        val speedFrac = DRIFT_SPEED_MIN_FRAC + rng.nextFloat() * (DRIFT_SPEED_MAX_FRAC - DRIFT_SPEED_MIN_FRAC)
        val speed = speedFrac * width
        CabinetRock(
            size,
            rng.nextFloat() * width,
            rng.nextFloat() * height,
            cos(heading) * speed,
            sin(heading) * speed,
            CabinetRock.generateShape(rng),
            rng.nextFloat() * 2f * PI.toFloat(),
            (rng.nextFloat() - 0.5f) * 1.4f
        )
    }

    fun update(dt: Float) {
        for (rock in rocks) rock.update(dt, metrics)
    }

    companion object {
        private const val ROCK_COUNT = 4
        private val SIZE_MIX = listOf(RockSize.LARGE, RockSize.MEDIUM, RockSize.SMALL, RockSize.MEDIUM)
        private const val DRIFT_SPEED_MIN_FRAC = 0.10f
        private const val DRIFT_SPEED_MAX_FRAC = 0.18f
    }
}
