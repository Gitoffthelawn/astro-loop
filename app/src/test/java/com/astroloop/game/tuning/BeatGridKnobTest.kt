package com.astroloop.game.tuning

import com.astroloop.game.core.BeatClock
import org.junit.Assert.assertEquals
import org.junit.Test

class BeatGridKnobTest {

    /** A 64th note at 120 BPM: a 16th halved twice (evolution at 0.5x, then Revenge). */
    private val sixtyFourthUs = 31_250L

    @Test
    fun `every tunable interval is a whole number of 64ths, halved or not`() {
        for (sixteenths in SixteenthsKnob.MIN..SixteenthsKnob.MAX) {
            val seconds = sixteenths * SixteenthsKnob.SIXTEENTH_SECONDS
            for (evo in listOf(1f, 0.5f)) for (revenge in listOf(1f, 0.5f)) {
                val us = BeatClock.cooldownToSubdivisionUs(seconds * evo * revenge)
                assertEquals("$sixteenths×$evo×$revenge", 0L, us % sixtyFourthUs)
                assertEquals((sixteenths * 125_000L * evo * revenge).toLong(), us)
            }
        }
    }

    @Test
    fun `a 32nd-note interval lands on its grid, not on truncated milliseconds`() {
        val clock = BeatClock(120f).also { it.start(0L) }
        val subdivUs = BeatClock.cooldownToSubdivisionUs(3 * SixteenthsKnob.SIXTEENTH_SECONDS * 0.5f) // 187.5 ms
        assertEquals(187_500L, subdivUs)
        for (now in 0L..2_000L step 7L) {
            val delay = clock.usUntilNextSubdivision(subdivUs, now)
            assertEquals("now=$now", 0L, (now * 1000L + delay) % subdivUs)
        }
    }

    @Test
    fun `microsecond anchoring equals the millisecond path for shipped intervals`() {
        val clock = BeatClock(120f).also { it.start(0L) }
        for (ms in listOf(125L, 250L, 500L, 750L, 1000L, 1500L, 2000L, 4000L)) for (phase in listOf(0L, 250L, 500L, 1500L, 2000L)) {
            for (now in 0L..5_000L step 13L) {
                assertEquals(clock.msUntilNextSubdivision(ms, now, phase) * 1000L, clock.usUntilNextSubdivision(ms * 1000L, now, phase))
            }
        }
    }

    @Test
    fun `grid-anchored delay is strictly future and bumps a first-half landing`() {
        val clock = BeatClock(120f).also { it.start(0L) }
        assertEquals(250_000L, clock.gridAnchoredDelayUs(250_000L, 1000L))     // on a tick → full subdivision
        assertEquals(244_000L, clock.gridAnchoredDelayUs(250_000L, 1006L))
        assertEquals(260_000L, clock.gridAnchoredDelayUs(250_000L, 1240L))     // 10 ms left < half → bump
    }
}
