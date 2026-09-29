package com.astroloop.game.cabinet

/**
 * When the machine glitches, and how hard — a design decision.
 *
 * Once `CrystalReckoning.shouldEnter` is true the crystal is taking BELT RUN over, and the
 * cabinet has to say so before the player ever presses PLAY. This object owns the *decision*
 * — schedule, strength and jitter — and nothing else. It draws nothing and imports no
 * Android, which is what makes the one genuinely testable part of a view feature testable.
 * The four surfaces that consume it ([CRT], [MARQUEE], [BOARD], [MENU]) each turn these
 * numbers into strokes themselves.
 *
 * **Intermittent, not constant.** The register is a 1979 vector cabinet being pushed on from
 * inside, not a datamosh: constant heavy corruption reads as broken hardware, stops being
 * legible, and would have a player unable to find PLAY. So the machine is clean for
 * [CYCLE_MS] minus [EPISODE_MS] — a ~91% duty cycle of behaving itself — and comes apart in
 * brief episodes. That is also the cheap answer to a design decision's frame-time constraint:
 * outside an episode [intensity] returns 0, every caller early-outs, and the takeover costs
 * literally nothing. Frame time is a standing complaint, and
 * `ReckoningFairness.DENSITY_CEILING` says in as many words that this feature must not
 * make it worse.
 *
 * **Time-derived, not state.** A glitch is ambience, not simulation, so a clock is fair game
 * where the sim's patterns refuse `Random`. Everything here is a pure function of the wall
 * clock in milliseconds, which buys three things at once: no per-surface state to keep in
 * sync (all four tear on the same beat because they are asked the same question at the same
 * millisecond), nothing to reset when the overlay opens, and — because [noise] is quantised
 * to [STEP_MS] rather than re-rolled per call — a jitter that is identical at 30fps and at
 * 60fps. A frame-counter would have failed that last one.
 *
 * `% CYCLE_MS` before any float conversion is deliberate, and is the same trap the radio
 * wave hit: `System.currentTimeMillis()` is ~1.8e12 and loses precision as a float outright.
 */
object CabinetTakeover {

    /** One episode per cycle. Long enough that the machine is mostly composed. */
    const val CYCLE_MS = 3700L

    /** How long an episode lasts. 340/3700 is a 9.2% duty cycle. */
    const val EPISODE_MS = 340L

    /**
     * The fraction of an episode held at full strength before it decays out.
     *
     * A glitch arrives; it does not swell. The onset is a step and the recovery is a ramp,
     * which is the shape a tube actually loses and regains lock in.
     */
    const val HOLD_FRAC = 0.4f

    /**
     * How often the jitter re-rolls inside an episode.
     *
     * 55ms gives an episode ~6 distinct states, which is what makes it read as a stutter
     * rather than a slide — and it is why no separate stutter term is needed on top of
     * [intensity]'s smooth decay. Quantising to wall-clock time rather than to frames is
     * what keeps the effect identical at any frame rate.
     */
    const val STEP_MS = 55L

    /** Horizontal picture displacement, as a fraction of the surface's width. */
    const val SLIP_FRAC = 0.020f

    /** Colour-separation offset, as a fraction of the stroke's own size. */
    const val GHOST_FRAC = 0.13f

    /** Dropout band thickness, as a fraction of the surface's height. */
    const val BAND_MIN_FRAC = 0.020f
    const val BAND_MAX_FRAC = 0.055f

    /** At most two rows are ever lost at once. Three starts to hide the buttons. */
    const val MAX_BANDS = 2

    // Surface ids. They exist so the four surfaces tear in different places on the same
    // beat — one machine losing lock, not four copies of one animation.
    const val CRT = 1
    const val MARQUEE = 2
    const val BOARD = 3
    const val MENU = 4

    /**
     * 0 when the machine is behaving, up to 1 inside an episode.
     *
     * Callers are expected to early-out on 0 and do no work at all; that is where the
     * frame budget is won.
     */
    fun intensity(timeMs: Long): Float {
        val phase = wrap(timeMs, CYCLE_MS)
        if (phase >= EPISODE_MS) return 0f
        val t = phase.toFloat() / EPISODE_MS
        if (t <= HOLD_FRAC) return 1f
        return (1f - (t - HOLD_FRAC) / (1f - HOLD_FRAC)).coerceIn(0f, 1f)
    }

    /** Whether anything should be drawn at all this frame. */
    fun isGlitching(timeMs: Long): Boolean = intensity(timeMs) > 0f

    /**
     * Deterministic jitter in `[-1, 1]`, constant for a whole [STEP_MS] and different per
     * [channel].
     *
     * A hash of the step index rather than a `Random`: a stream would need state, would
     * advance at the frame rate, and would give two surfaces asking on the same frame
     * different answers depending on draw order.
     */
    fun noise(timeMs: Long, channel: Int): Float {
        // Reduce before narrowing: timeMs / STEP_MS is ~3.3e10 and does not fit an Int.
        val step = (wrap(timeMs / STEP_MS, STEP_SPAN)).toInt()
        return (mix(step * 2654435761.toInt() + channel) and 0xFFFF) / 32767.5f - 1f
    }

    /**
     * Horizontal displacement of a whole picture [extent] px wide.
     *
     * The cheapest effect on the machine and the most convincing: the CRT's contents are
     * already drawn inside a `translate`, so slipping them sideways is one number added to
     * a call that was happening anyway — no second pass, no offscreen buffer, no extra
     * `drawPath`.
     */
    fun slip(timeMs: Long, extent: Float, surface: Int): Float =
        intensity(timeMs) * noise(timeMs, surface * SURFACE_STRIDE) * extent * SLIP_FRAC

    /**
     * Colour-separation offset for a stroke of [extent] px — the distance to re-draw it at
     * in `CabinetRenderer.CORRUPTION`.
     *
     * Magnitude comes from [intensity] alone and only the SIGN jitters, so the ghost snaps
     * side to side and never lands back exactly under the real stroke, which is what a
     * noise-scaled magnitude would do at every zero crossing — an invisible ghost on a
     * frame that is paying for it.
     */
    fun ghost(timeMs: Long, extent: Float, surface: Int): Float {
        val n = noise(timeMs, surface * SURFACE_STRIDE + 1)
        return intensity(timeMs) * extent * GHOST_FRAC * (if (n < 0f) -1f else 1f)
    }

    /** How many dropped rows this surface is missing right now: 0 outside an episode. */
    fun bandCount(timeMs: Long, surface: Int): Int {
        if (!isGlitching(timeMs)) return 0
        return if (noise(timeMs, surface * SURFACE_STRIDE + 2) > 0.35f) MAX_BANDS else 1
    }

    /**
     * Top of dropped row [index], as a fraction of the surface's height.
     *
     * Scaled by `1 - height` so a band can never hang off the bottom of the surface it is
     * drawn on — every caller clips to its own rect anyway, but a band that is half outside
     * reads as a thinner band, not as a band at the edge.
     */
    fun bandTopFrac(timeMs: Long, surface: Int, index: Int): Float {
        val unit = (noise(timeMs, surface * SURFACE_STRIDE + 3 + index * 2) + 1f) * 0.5f
        return (unit * (1f - bandHeightFrac(timeMs, surface, index))).coerceIn(0f, 1f)
    }

    /** Thickness of dropped row [index], as a fraction of the surface's height. */
    fun bandHeightFrac(timeMs: Long, surface: Int, index: Int): Float {
        val unit = (noise(timeMs, surface * SURFACE_STRIDE + 4 + index * 2) + 1f) * 0.5f
        return BAND_MIN_FRAC + unit * (BAND_MAX_FRAC - BAND_MIN_FRAC)
    }

    /** Non-negative remainder. Kotlin's `%` keeps the sign of the dividend. */
    private fun wrap(v: Long, span: Long): Long = ((v % span) + span) % span

    /** Enough distinct steps that the pattern's repeat is not perceptible. */
    private const val STEP_SPAN = 100_003L

    /** Channel spacing per surface, wide enough that no two surfaces collide. */
    private const val SURFACE_STRIDE = 101

    /** Integer avalanche (murmur3's finaliser). Adjacent steps must not look adjacent. */
    private fun mix(n: Int): Int {
        var h = n
        h = h xor (h ushr 16)
        h *= 0x85EBCA6B.toInt()
        h = h xor (h ushr 13)
        h *= 0xC2B2AE35.toInt()
        h = h xor (h ushr 16)
        return h
    }
}
