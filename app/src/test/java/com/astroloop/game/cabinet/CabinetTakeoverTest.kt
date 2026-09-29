package com.astroloop.game.cabinet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The takeover's schedule and jitter.
 *
 * The drawing is view code and the project does not unit-test that. **When to glitch is
 * not view code** — it is the half of a design decision that carries the taste constraint
 * ("restraint is the effect") and the frame-time constraint, and both are
 * properties an assertion can hold onto.
 */
class CabinetTakeoverTest {

    private val surfaces = listOf(
        CabinetTakeover.CRT, CabinetTakeover.MARQUEE,
        CabinetTakeover.BOARD, CabinetTakeover.MENU
    )

    // --- the schedule --------------------------------------------------------

    @Test
    fun theMachineIsMostlyFine() {
        // The taste constraint as a number: constant heavy corruption reads as broken
        // hardware and stops being legible. If someone widens EPISODE_MS to "make it more
        // visible", this is the guard that says the answer is contrast, not duration.
        val glitching = (0 until CabinetTakeover.CYCLE_MS).count {
            CabinetTakeover.isGlitching(it)
        }
        assertEquals(CabinetTakeover.EPISODE_MS.toInt(), glitching)
        assertTrue(
            "duty cycle was ${glitching * 100f / CabinetTakeover.CYCLE_MS}%",
            glitching.toFloat() / CabinetTakeover.CYCLE_MS < 0.12f
        )
    }

    @Test
    fun anEpisodeOpensEveryCycleAndIsOverBeforeTheNext() {
        for (cycle in 0..5) {
            val start = cycle * CabinetTakeover.CYCLE_MS
            assertEquals("cycle $cycle opens at full strength", 1f, CabinetTakeover.intensity(start), 0f)
            assertEquals(
                "cycle $cycle is clean at the episode's end",
                0f, CabinetTakeover.intensity(start + CabinetTakeover.EPISODE_MS), 0f
            )
            assertEquals(
                "cycle $cycle is clean right up to the next one",
                0f, CabinetTakeover.intensity(start + CabinetTakeover.CYCLE_MS - 1), 0f
            )
        }
    }

    @Test
    fun theOnsetIsAStepAndTheRecoveryIsARamp() {
        val hold = (CabinetTakeover.EPISODE_MS * CabinetTakeover.HOLD_FRAC).toLong()
        assertEquals(1f, CabinetTakeover.intensity(0), 0f)
        assertEquals(1f, CabinetTakeover.intensity(hold), 0f)
        // Past the hold it only ever falls, and it reaches zero at the episode's edge.
        var previous = 1f
        for (t in hold..CabinetTakeover.EPISODE_MS) {
            val now = CabinetTakeover.intensity(t)
            assertTrue("intensity rose at t=$t: $previous -> $now", now <= previous + 1e-6f)
            previous = now
        }
        assertEquals(0f, previous, 0f)
    }

    @Test
    fun intensityNeverLeavesItsRange() {
        for (t in 0 until CabinetTakeover.CYCLE_MS * 3) {
            val g = CabinetTakeover.intensity(t)
            assertTrue("intensity $g at t=$t", g in 0f..1f)
        }
    }

    @Test
    fun theRealWallClockIsHandledAndSoIsANegativeOne() {
        // System.currentTimeMillis() is ~1.8e12; nothing here may overflow an Int or lose
        // itself in float precision on the way — the same trap the radio wave hit.
        val now = 1_787_000_000_000L
        for (t in now until now + CabinetTakeover.CYCLE_MS) {
            assertTrue(CabinetTakeover.intensity(t) in 0f..1f)
            assertTrue(CabinetTakeover.noise(t, 1) in -1f..1f)
        }
        // A clock that has gone backwards must not produce a negative phase.
        for (t in -5000L..0L) {
            assertTrue("t=$t", CabinetTakeover.intensity(t) in 0f..1f)
            assertTrue("t=$t", CabinetTakeover.noise(t, 1) in -1f..1f)
        }
    }

    // --- the jitter ----------------------------------------------------------

    @Test
    fun noiseIsBounded() {
        for (t in 0 until 20_000L step 7) {
            for (c in 0 until 500) {
                val n = CabinetTakeover.noise(t, c)
                assertTrue("noise $n at t=$t c=$c", n in -1f..1f)
            }
        }
    }

    @Test
    fun theSameMillisecondAlwaysGivesTheSameJitter() {
        // The frame-rate guarantee, stated as the property that actually delivers it:
        // nothing here advances when it is called, so a 30fps renderer and a 60fps one
        // see the same value at the same wall-clock time, and calling twice in one frame
        // (ghost, then dropout) cannot desynchronise two surfaces.
        val t = 1_787_000_000_123L
        val first = CabinetTakeover.noise(t, 3)
        repeat(1000) { assertEquals(first, CabinetTakeover.noise(t, 3), 0f) }
    }

    @Test
    fun theJitterHoldsForAStepThenMoves() {
        // Held for a whole step: this is what makes an episode read as a stutter of a few
        // discrete states rather than as a smooth slide.
        val base = CabinetTakeover.STEP_MS * 40
        val held = CabinetTakeover.noise(base, 9)
        for (d in 0 until CabinetTakeover.STEP_MS) {
            assertEquals("t=${base + d}", held, CabinetTakeover.noise(base + d, 9), 0f)
        }
        assertNotEquals(held, CabinetTakeover.noise(base + CabinetTakeover.STEP_MS, 9))
    }

    @Test
    fun anEpisodeContainsSeveralDistinctStates() {
        val states = (0 until CabinetTakeover.EPISODE_MS)
            .map { CabinetTakeover.noise(it, CabinetTakeover.CRT) }
            .toSet()
        assertTrue("only ${states.size} distinct jitter states in an episode", states.size >= 5)
    }

    @Test
    fun consecutiveStepsDoNotLookConsecutive() {
        // Guards the hash's avalanche. A weak mixer gives a jitter that walks steadily in
        // one direction, which reads as a slow drift rather than as a machine stuttering.
        var sameSign = 0
        for (step in 0 until 400) {
            val a = CabinetTakeover.noise(step * CabinetTakeover.STEP_MS, CabinetTakeover.CRT)
            val b = CabinetTakeover.noise((step + 1) * CabinetTakeover.STEP_MS, CabinetTakeover.CRT)
            if ((a < 0f) == (b < 0f)) sameSign++
        }
        assertTrue("$sameSign/400 consecutive steps kept their sign", sameSign in 140..260)
    }

    // --- what the surfaces actually consume ----------------------------------

    @Test
    fun nothingIsDrawnWhileTheMachineIsBehaving() {
        // The frame-budget claim, asserted rather than described: outside an episode every
        // consumer gets a zero and does no work.
        val quiet = CabinetTakeover.EPISODE_MS + 500
        assertTrue(CabinetTakeover.intensity(quiet) == 0f)
        for (s in surfaces) {
            assertEquals(0, CabinetTakeover.bandCount(quiet, s))
            assertEquals(0f, CabinetTakeover.slip(quiet, 1000f, s), 0f)
            assertEquals(0f, CabinetTakeover.ghost(quiet, 100f, s), 0f)
        }
    }

    @Test
    fun atMostTwoRowsAreEverLost() {
        for (t in 0 until CabinetTakeover.CYCLE_MS * 4) {
            for (s in surfaces) {
                val n = CabinetTakeover.bandCount(t, s)
                assertTrue("$n bands at t=$t s=$s", n <= CabinetTakeover.MAX_BANDS)
                assertTrue("no band during an episode at t=$t", !CabinetTakeover.isGlitching(t) || n >= 1)
            }
        }
    }

    @Test
    fun aDroppedRowAlwaysFitsInsideItsSurface() {
        for (t in 0 until CabinetTakeover.EPISODE_MS) {
            for (s in surfaces) {
                for (i in 0 until CabinetTakeover.MAX_BANDS) {
                    val top = CabinetTakeover.bandTopFrac(t, s, i)
                    val h = CabinetTakeover.bandHeightFrac(t, s, i)
                    assertTrue("top $top at t=$t s=$s i=$i", top >= 0f)
                    assertTrue("band ran off the bottom: ${top + h} at t=$t s=$s i=$i", top + h <= 1f)
                    assertTrue(h >= CabinetTakeover.BAND_MIN_FRAC)
                    assertTrue(h <= CabinetTakeover.BAND_MAX_FRAC)
                }
            }
        }
    }

    @Test
    fun theFourSurfacesTearInDifferentPlaces() {
        // One machine losing lock, not four copies of one animation. If two surfaces
        // shared a channel they would band at identical heights, which reads as a
        // deliberate stripe rather than as damage.
        var agreements = 0
        var samples = 0
        for (t in 0 until CabinetTakeover.EPISODE_MS) {
            for (a in surfaces.indices) {
                for (b in a + 1 until surfaces.size) {
                    samples++
                    if (CabinetTakeover.bandTopFrac(t, surfaces[a], 0) ==
                        CabinetTakeover.bandTopFrac(t, surfaces[b], 0)
                    ) agreements++
                }
            }
        }
        assertEquals("surfaces banded at the same height $agreements/$samples times", 0, agreements)
    }

    @Test
    fun theRedGhostNeverHidesUnderTheRealStroke() {
        // The whole point of a colour-separated copy is that it is beside the original. A
        // noise-scaled magnitude would put it exactly underneath at every zero crossing —
        // an invisible ghost on a frame that has already paid for it.
        for (t in 0 until CabinetTakeover.EPISODE_MS) {
            for (s in surfaces) {
                val dx = CabinetTakeover.ghost(t, 100f, s)
                if (CabinetTakeover.intensity(t) > 0.05f) {
                    assertTrue("ghost collapsed to $dx at t=$t s=$s", kotlin.math.abs(dx) > 0.3f)
                }
            }
        }
    }

    @Test
    fun theGhostChangesSideWithinAnEpisode() {
        val sides = (0 until CabinetTakeover.EPISODE_MS)
            .map { CabinetTakeover.ghost(it, 100f, CabinetTakeover.MARQUEE) < 0f }
            .toSet()
        assertEquals("the ghost stayed on one side for the whole episode", 2, sides.size)
    }

    @Test
    fun slipStaysWithinItsDeclaredFractionOfTheSurface() {
        // A picture that slipped further than this would leave the bezel's clip showing
        // black down one edge, which reads as a layout fault rather than as lost lock.
        val width = 1080f
        for (t in 0 until CabinetTakeover.CYCLE_MS * 2) {
            for (s in surfaces) {
                val slip = CabinetTakeover.slip(t, width, s)
                assertTrue(
                    "slip $slip at t=$t s=$s",
                    kotlin.math.abs(slip) <= width * CabinetTakeover.SLIP_FRAC + 1e-3f
                )
            }
        }
    }
}
