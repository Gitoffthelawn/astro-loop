package com.astroloop.game.cabinet

/**
 * The two-tone bass pulse that accelerates as a wave thins and resets when the next
 * begins.
 *
 * This is the most recognisable thing about the machine BELT RUN descends from, it does
 * tension work no visual can, and it is the reason the classic wave structure was chosen
 * over continuous spawn: the pulse needs a thinning field to accelerate against.
 */
class CabinetHeartbeat {

    private var timer = 0f

    /**
     * Which of the pair the beat that just landed was.
     *
     * Seeded TRUE for the same reason [reset] re-seeds it true — see there. A fresh
     * heartbeat is in exactly the position a reset one is, so it cannot seed differently:
     * the first heartbeat of a whole session is also the first beat of wave one.
     */
    var isHighTone: Boolean = true
        private set

    /** @return true on the frame a beat should sound. */
    fun update(dt: Float, rocksRemaining: Int, rocksAtWaveStart: Int): Boolean {
        val fraction = if (rocksAtWaveStart <= 0) 1f
                       else (rocksRemaining.toFloat() / rocksAtWaveStart).coerceIn(0f, 1f)
        val interval = FAST + (SLOW - FAST) * fraction

        timer -= dt
        if (timer <= 0f) {
            timer = interval
            isHighTone = !isHighTone
            return true
        }
        return false
    }

    /**
     * Back to a full field and the low tone — a design decision.
     *
     * `isHighTone` is seeded TRUE, which reads backwards until you see [update]: it flips
     * BEFORE it reports, so the first beat after a reset inverts this. Seeded false, every
     * wave opened on the HIGH tone — the wrong way round for the machine this descends
     * from, and audible at the top of all eight waves of a run.
     */
    fun reset() { timer = 0f; isHighTone = true }

    companion object {
        /** Interval with a full field. */
        const val SLOW = 1.0f
        /** Interval with the field nearly cleared. */
        const val FAST = 0.22f
    }
}
