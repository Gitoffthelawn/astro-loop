package com.astroloop.game.cabinet

/**
 * What the cabinet should play this frame — the decision, split from the playing.
 *
 * Every one of these cues used to be an `if` inside `HangarSurfaceView`, and not one of them
 * had ever been driven: the samples did not exist until 2026-08-29, so the machine had been
 * silently silent since stage 1 and the wiring had never been exercised by a player OR by a
 * test. That is this branch's recurring defect in its purest form — the units are provable
 * and the composition is where it breaks — so the host keeps only the gate and the loop, and
 * everything it would otherwise decide lives here.
 *
 * Pure, Android-free and stateless, for the same reason [CrystalVoice] and [CabinetTakeover]
 * are: a unit test can drive it, and `ReckoningRun`/`CabinetSim` stay unable to touch
 * `SoundManager`.
 *
 * **This is not a mixer.** Per-sound loudness is baked onto a LUFS ladder and the category
 * knobs sit near 1.0 so nothing is attenuated twice; the volumes here are the authored
 * per-call trims that ladder was built against, and they belong with the decision rather
 * than scattered across call sites.
 */
object CabinetCues {

    /** One thing to play: a sample, its authored trim, and a pitch. */
    data class Cue(val eventId: String, val volume: Float, val rate: Float)

    /**
     * Everything about a frame that can make a sound.
     *
     * Counts rather than booleans wherever the sim counts, because that is what the sim
     * publishes — `shotsFiredThisFrame`, `destroyedThisFrame[size]`, `crystalHitsThisFrame` —
     * and converting at the call site would put a judgement back in the host.
     */
    data class Frame(
        val shotsFired: Int = 0,
        val destroyedLarge: Int = 0,
        val destroyedMedium: Int = 0,
        val destroyedSmall: Int = 0,
        val shipDied: Boolean = false,
        val heartbeat: Boolean = false,
        val heartbeatHigh: Boolean = false,
        val bulletsEmitted: Int = 0,
        val crystalHits: Int = 0,
        val shattered: Boolean = false,
        val landed: Boolean = false,
        val phaseIndex: Int = 0
    )

    /**
     * Append this frame's cues to [out]. **Appends — it does not clear.** The host owns one
     * reusable list across frames so a fight does not allocate one per frame at 120fps;
     * clearing is the owner's business, not this function's.
     *
     * Order is fixed and is the order the events happen in: the player acts, the field
     * answers, the crystal answers, the run ends.
     */
    fun forFrame(f: Frame, out: MutableList<Cue>) {
        // ── the machine ──
        // One cue per CALL to fire(), never one per barrel: the gun can put several
        // projectiles up in a frame and it is one trigger pull.
        if (f.shotsFired > 0) out.add(Cue("sfx_belt_fire", VOL_FIRE, 1f))
        // Likewise per SIZE, not per rock. Three rocks of a size coming apart together is
        // one event to the ear, and three overlapping copies of one noise burst is a click.
        if (f.destroyedLarge > 0) out.add(Cue("sfx_belt_break_lge", VOL_BREAK, 1f))
        if (f.destroyedMedium > 0) out.add(Cue("sfx_belt_break_med", VOL_BREAK, 1f))
        if (f.destroyedSmall > 0) out.add(Cue("sfx_belt_break_sml", VOL_BREAK, 1f))
        if (f.heartbeat) {
            out.add(Cue(if (f.heartbeatHigh) "sfx_belt_beat_hi" else "sfx_belt_beat_lo", VOL_BEAT, 1f))
        }
        if (f.shipDied) out.add(Cue("sfx_belt_death", VOL_DEATH, 1f))

        // ── the crystal ──
        // ONE cue for the whole emission. PULSE puts nine bullets on the field in a single
        // frame and SHATTER eight; a cue per bullet is a machine gun, not a tell.
        if (f.bulletsEmitted > 0) {
            out.add(Cue("sfx_belt_crystal_fire", VOL_CRYSTAL_FIRE, rateForPhase(f.phaseIndex)))
        }
        // The fight's only positive feedback. Without it a player shooting a crystal that
        // does not visibly react gets nothing back at all, which is the "my gun is broken"
        // read the health bar's soft floor was once written to avoid.
        if (f.crystalHits > 0) out.add(Cue("sfx_belt_crystal_hit", VOL_CRYSTAL_HIT, 1f))
        if (f.shattered) out.add(Cue("sfx_belt_crystal_shatter", VOL_CRYSTAL_SHATTER, 1f))
        // The arrival. The only cue that is not the machine's own voice — a design decision keeps
        // the main game's sting, because the crossing from the top strip is the one moment
        // the crystal is not yet inside the cabinet.
        if (f.landed) out.add(Cue("sfx_crystal_activate", VOL_CRYSTAL_LANDING, 1f))
    }

    /**
     * Read a frame straight off the sim and the run, and append its cues to [out].
     *
     * **This overload exists so the host cannot get the mapping wrong.** Composing the
     * [Frame] by hand at the call site meant eleven fields copied into a constructor inside
     * view code no test can reach — and a `destroyedThisFrame[MEDIUM]` handed to
     * `destroyedSmall` would be silent, plausible, and invisible to every test that builds
     * its own Frame. `CabinetSim` and `ReckoningRun` are both Android-free, so the mapping
     * can be done here and driven by a real sim instead.
     *
     * The heartbeat is passed in rather than read: [CabinetHeartbeat] is stateful and the
     * host owns its clock and its per-wave reset.
     *
     * [run] is null in free play, and then no crystal cue can be produced at all.
     */
    fun forFrame(
        sim: CabinetSim,
        run: ReckoningRun?,
        heartbeat: Boolean,
        heartbeatHigh: Boolean,
        out: MutableList<Cue>
    ) {
        forFrame(
            Frame(
                shotsFired = sim.shotsFiredThisFrame,
                destroyedLarge = sim.destroyedThisFrame[RockSize.LARGE.ordinal],
                destroyedMedium = sim.destroyedThisFrame[RockSize.MEDIUM.ordinal],
                destroyedSmall = sim.destroyedThisFrame[RockSize.SMALL.ordinal],
                shipDied = sim.shipDiedThisFrame,
                heartbeat = heartbeat,
                heartbeatHigh = heartbeatHigh,
                bulletsEmitted = run?.bulletsEmittedThisFrame ?: 0,
                crystalHits = sim.crystalHitsThisFrame,
                shattered = run?.justShattered == true,
                landed = run?.justLanded == true,
                phaseIndex = run?.director?.phaseIndex ?: 0
            ),
            out
        )
    }

    /**
     * The crystal's gun, pitched by pattern — a design decision.
     *
     * One asset, five voices. A 1979 board pitched one waveform rather than storing five, and
     * the patterns already differ in cadence and geometry; this is what stops the fifth
     * pattern sounding like the first.
     *
     * **Coerced, and not because the director fails to.** It does — but a throw here lands
     * inside `GameThread`'s swallowed `update()`, and a frozen machine is a far worse failure
     * than a wrong pitch. The same reasoning `ReckoningDirector.phaseFor` uses.
     *
     * The five values are the board's audition defaults, so what was picked in the BELT RUN
     * panel is what plays. Retune by ear there, not here.
     */
    fun rateForPhase(phaseIndex: Int): Float =
        PHASE_RATES[phaseIndex.coerceIn(0, PHASE_RATES.size - 1)]

    /** PULSE, VOLLEY, WINDMILL, CURTAIN, SHATTER — the director's own order. */
    private val PHASE_RATES = floatArrayOf(1.00f, 1.25f, 0.85f, 0.65f, 1.50f)

    // The authored trims the LUFS ladder was built against. See the audio design doc:
    // these are half of each sound's final level, and moving one here silently rebases it.
    const val VOL_FIRE = 0.60f
    const val VOL_BREAK = 0.70f
    const val VOL_BEAT = 0.70f
    const val VOL_DEATH = 0.80f

    /**
     * The coin, which [forFrame] never produces.
     *
     * It fires from `handleCabinetTouch` the moment a credit is spent — a touch, not a
     * frame — so it cannot come from here. Its trim still belongs here: it was riding
     * `playSFX`'s default argument, which meant the ladder had an eleventh entry in another
     * file that nobody editing these constants would find.
     */
    const val VOL_COIN = 1.0f

    /**
     * The borrowed arrival sting, trimmed onto this machine's ladder.
     *
     * `sfx_crystal_activate` is a MAIN-GAME sample with eight other callers, levelled for
     * that mix, so it cannot be re-baked to fit this one — the trim has to be here. At full
     * volume it measured **0.66 dB ABOVE the shatter**, and a design decision makes the shatter
     * *"the biggest sound the cabinet makes"*: the arrival was out-shouting the ending.
     * 0.74 puts it 2 dB under, which is still the second-largest thing in the fight.
     */
    const val VOL_CRYSTAL_LANDING = 0.74f

    /** Under the player's gun: this is the one being dodged, not the one being fired. */
    const val VOL_CRYSTAL_FIRE = 0.45f
    const val VOL_CRYSTAL_HIT = 0.55f
    const val VOL_CRYSTAL_SHATTER = 0.90f
}
