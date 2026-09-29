package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test

class CabinetHeartbeatTest {

    private fun beatsIn(seconds: Float, remaining: Int, atStart: Int): Int {
        val h = CabinetHeartbeat()
        var n = 0
        var t = 0f
        while (t < seconds) { if (h.update(1f / 120f, remaining, atStart)) n++; t += 1f / 120f }
        return n
    }

    @Test fun acceleratesAsTheFieldThins() {
        val full = beatsIn(10f, 28, 28)
        val nearlyClear = beatsIn(10f, 2, 28)
        assertTrue("thin field should beat faster: $full vs $nearlyClear", nearlyClear > full * 2)
    }

    @Test fun alternatesBetweenTwoTones() {
        val h = CabinetHeartbeat()
        val tones = ArrayList<Boolean>()
        var t = 0f
        while (t < 5f) { if (h.update(1f / 120f, 1, 28)) tones.add(h.isHighTone); t += 1f / 120f }
        assertTrue(tones.size >= 4)
        for (i in 1 until tones.size) assertNotEquals(tones[i - 1], tones[i])
    }

    @Test fun anEmptyWaveDoesNotDivideByZero() {
        // The guard falls back to fraction = 1f (the SLOW interval) whenever
        // rocksAtWaveStart <= 0. A single beat firing on the very first update() call is
        // true regardless of the guard — the timer starts at exactly 0f — so that alone
        // can't tell a fallback interval from a NaN-fuelled runaway (Kotlin float division
        // by zero yields NaN/Infinity rather than throwing, so a removed guard wouldn't
        // crash this test either). Beating repeatedly over several seconds and counting
        // pins the interval to SLOW (1.0s): ~3 beats over 3s. A removed guard divides
        // 0f / 0f = NaN, `.coerceIn` on NaN returns NaN, and every subsequent interval and
        // comparison poisoned by that NaN behaves unpredictably — this assertion catches it.
        val n = beatsIn(3f, 0, 0)
        assertTrue("expected a plausible number of SLOW-interval beats, got $n", n in 2..4)
    }

    @Test fun everyWaveOpensOnTheLowTone() {
        // A design decision. update() flips BEFORE it reports, so a reset leaving isHighTone
        // false made the first beat of every wave the HIGH one — backwards from the machine
        // this descends from, and audible at the top of all eight waves of a run.
        val h = CabinetHeartbeat()
        var t = 0f
        while (t < 5f) {
            if (h.update(1f / 120f, 28, 28)) break
            t += 1f / 120f
        }
        assertFalse("the first beat of a wave must be the low tone", h.isHighTone)
    }

    @Test fun aResetMidWaveStartsLowAgain() {
        val h = CabinetHeartbeat()
        // Beat a few times so the alternation is somewhere arbitrary.
        var beats = 0
        var t = 0f
        while (t < 10f && beats < 3) { if (h.update(1f / 120f, 4, 28)) beats++; t += 1f / 120f }
        assertEquals(3, beats)
        h.reset()
        while (!h.update(1f / 120f, 28, 28)) { /* to the next beat */ }
        assertFalse("a new wave restarts the pair, not just the clock", h.isHighTone)
    }
}
