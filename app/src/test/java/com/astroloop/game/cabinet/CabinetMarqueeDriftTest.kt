package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * The marquee plate's ambient rock drift — see `CabinetMarqueeDrift`'s own doc for why it
 * needs its own radii rather than `CabinetMetrics.rockRadius`.
 *
 * A real marquee plate is wide and short (the 2026-08-16 polish pass design), so
 * these use a plate-shaped size — nothing like the square-ish playfield `CabinetRockTest`
 * exercises.
 */
class CabinetMarqueeDriftTest {

    private fun plate(rng: Random = Random(1)) = CabinetMarqueeDrift(360f, 130f, rng)

    @Test fun holdsFourRocks() {
        assertEquals(4, plate().rocks.size)
    }

    @Test fun rocksDrift() {
        val drift = plate()
        val start = drift.rocks.map { it.x to it.y }
        drift.update(1f)
        val moved = drift.rocks.mapIndexed { i, r -> (r.x to r.y) != start[i] }
        assertTrue("every rock should have moved after a full second", moved.all { it })
    }

    @Test fun rocksWrapInsteadOfEscapingThePlate() {
        val drift = plate()
        // Push each rock rightward off the plate's own edge at its own drift speed (a
        // single-frame overshoot, same shape as CabinetRockTest.rocksWrap), then confirm
        // it lands back inside the plate rather than sailing off past it.
        for (rock in drift.rocks) {
            val speed = kotlin.math.hypot(rock.vx, rock.vy)
            rock.x = drift.metrics.width - 1f
            rock.vx = speed
            rock.vy = 0f
        }
        drift.update(1f)
        for (rock in drift.rocks) {
            assertTrue("x=${rock.x} should be within [0, width)", rock.x >= 0f && rock.x < drift.metrics.width)
        }
    }

    @Test fun rocksWrapVerticallyToo() {
        val drift = plate()
        for (rock in drift.rocks) {
            val speed = kotlin.math.hypot(rock.vx, rock.vy)
            rock.y = 1f
            rock.vx = 0f
            rock.vy = -speed
        }
        drift.update(1f)
        for (rock in drift.rocks) {
            assertTrue("y=${rock.y} should be within [0, height)", rock.y >= 0f && rock.y < drift.metrics.height)
        }
    }

    @Test fun radiusOfIsPlateScaledNotTheTinyCabinetMetricsValue() {
        // The regression guard: CabinetMetrics.rockRadius is a fraction of minEdge, which
        // at plate scale is the plate's own HEIGHT (130px here) — a SMALL rock would come
        // out at 130 * 0.020 = 2.6px, unreadable as a silhouette. radiusOf must not be that.
        val drift = plate()
        val small = CabinetRock(RockSize.SMALL, 0f, 0f, 0f, 0f, CabinetRock.generateShape(Random(2)), 0f, 0f)
        val medium = CabinetRock(RockSize.MEDIUM, 0f, 0f, 0f, 0f, CabinetRock.generateShape(Random(2)), 0f, 0f)
        val large = CabinetRock(RockSize.LARGE, 0f, 0f, 0f, 0f, CabinetRock.generateShape(Random(2)), 0f, 0f)

        val tinyStandardRadius = drift.metrics.rockRadius(RockSize.SMALL)
        val plateRadius = drift.radiusOf(small)
        assertTrue(
            "plate radius ($plateRadius) should dwarf the standard minEdge-fraction radius ($tinyStandardRadius)",
            plateRadius > tinyStandardRadius * 5f
        )

        // Roughly LARGE -> 0.30h, MEDIUM -> 0.22h, SMALL -> 0.15h.
        assertEquals(0.30f * drift.metrics.height, drift.radiusOf(large), 0.01f)
        assertEquals(0.22f * drift.metrics.height, drift.radiusOf(medium), 0.01f)
        assertEquals(0.15f * drift.metrics.height, drift.radiusOf(small), 0.01f)

        // And the ordering that makes them read as three distinct sizes at all.
        assertTrue(drift.radiusOf(large) > drift.radiusOf(medium))
        assertTrue(drift.radiusOf(medium) > drift.radiusOf(small))
    }

    @Test fun rocksAreAMixOfSizes() {
        val sizes = plate().rocks.map { it.size }.toSet()
        assertTrue("expected more than one RockSize among the four rocks", sizes.size > 1)
    }

    @Test fun driftSpeedIsAFractionOfPlateWidthPerSecond() {
        // Around 0.10..0.18 of width/s, not of minEdge/height — the plate is much wider
        // than it is tall, so a height-relative speed would race rocks across it.
        val drift = plate()
        for (rock in drift.rocks) {
            val speed = kotlin.math.hypot(rock.vx, rock.vy)
            val frac = speed / drift.metrics.width
            assertTrue("speed fraction $frac out of expected 0.10..0.18 range", frac in 0.09f..0.19f)
        }
    }
}
