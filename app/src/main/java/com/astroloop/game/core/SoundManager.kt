package com.astroloop.game.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton managing all game audio.
 *
 * - SoundPool for short one-shot SFX (low latency, concurrent playback)
 * - MediaPlayer for ambient loops (crossfading between tracks)
 * - Data-driven: sound files in res/raw/ named to match event IDs.
 *   Missing files = silence, no code changes needed to add/remove sounds.
 * - Volume categories: ambient, sfx_combat, sfx_ui, sfx_radio — independent multipliers
 * - Respects system volume — in-app mute toggle via setMuted()
 * - Combat BGM: sequential segment playback (intro → loop) with BeatClock sync
 */
object SoundManager {

    private const val TAG = "SoundManager"
    private const val MAX_STREAMS = 16
    private const val CROSSFADE_STEP = 0.05f

    // ── Volume category multipliers (0.0–1.0) ──────────────────────────
    // Per-sound loudness is baked into the files (LUFS ladder); these category knobs sit
    // near 1.0 so we don't double-attenuate the normalized assets.
    var volumeAmbient: Float = 0.8f      // was 0.25 — music is now baked to ~−18 LUFS
    var volumeSfxCombat: Float = 1.0f
    var volumeSfxUi: Float = 0.8f        // was 0.25 — UI baked to −18 LUFS
    var volumeSfxRadio: Float = 0.8f     // was 0.25 — radio baked to −18 LUFS

    /**
     * Call volume for the heart-to-heart / desert-farewell typewriter tick.
     *
     * It lives here, beside the two category volumes above, because it is the same kind of number
     * and it was missed for exactly that reason. When the February normalization pass (84c3a2fb)
     * re-baked the library quieter, the compensation was applied to the *category* volumes — that
     * is what the two "was 0.25" comments above record. `sfx_text_tick` is not in either category
     * (it falls to [volumeSfxCombat], which stayed at 1.0), and its trim was instead hardcoded as
     * `0.15f` at two call sites in GameSurfaceView. It got the quieter bake and none of the
     * compensation.
     *
     * Measured: the sample's peak went −5.7 dB → −15.8 dB in that pass, a 10.1 dB drop. At the old
     * 0.15 trim that put its effective peak at −32.3 dB, under `bgm_heart_to_heart` — roughly 11 dB
     * below the next quietest thing in the game, and inaudible on device. Every other quiet sample
     * (ui_swipe at −19.0, slot_win at −18.5) is played at full call volume; the typewriter was the
     * only one attenuated at its call site.
     *
     * 0.5 restored the effective level the 0.15 was originally chosen to produce (−21.8 dB versus
     * −22.2 dB before the re-bake) — and on device that was still too quiet: audible, but drowned
     * by `bgm_heart_to_heart` (owner, 2026-09-03). So the pre-re-bake mix was itself wrong against
     * this scene's music; restoring history was never going to be enough.
     *
     * 1.0 puts the effective peak at −15.8 dB, level with `sfx_ui_upgrade_select` (−15.0 dB), the
     * loudest of the library's quiet samples. No clipping headroom is at risk: the sample peaks
     * 15.8 dB below full scale and [volumeSfxCombat] is 1.0, so the product cannot reach unity.
     *
     * **This is the ceiling for this lever** — `playSFX` coerces volume into 0f..1f, so there is
     * nothing above 1.0 here. If the tick ever needs to be louder still, the next moves are a
     * hotter re-bake of the sample or ducking the ambient bed under the scene, not this constant.
     * If the sample is re-baked, recompute from its measured peak rather than nudging by ear.
     */
    const val TEXT_TICK_VOLUME = 1.0f

    // The menu tap is mixed hot relative to the other UI sounds — trim it to 30%.
    private const val TAP_VOLUME_MULT = 0.3f

    var activeSet: String = "normal"
    var weaponVolume: Float = 1.0f

    // (weaponSfxVolume map removed — per-weapon loudness is now baked into the normalized
    //  files on the LUFS ladder; getWeaponSfxVolume returns a flat 1.0.)

    // Per-weapon beat timing offset (ms, additive to beatPhaseOffsetMs)
    // Per-weapon attack-delay compensation. Reset to empty: the old values were tuned by ear for
    // the pre-instrument-restyle sounds, and the current SFX are export-trimmed so their transient
    // sits at t=0 (~5 ms) — they should fire right on the grid boundary (offset 0). Re-tune here
    // only if a specific new sound's perceived attack reads early/late.
    private val weaponBeatOffsetMs = emptyMap<String, Long>()

    // Per-weapon balance now lives in the normalized files (−15 LUFS), not here.
    fun getWeaponSfxVolume(weaponId: String): Float = 1.0f
    fun getWeaponBeatOffsetMs(weaponId: String): Long = weaponBeatOffsetMs[weaponId] ?: 0L

    val beatClock = BeatClock(120f)
    // Wall-clock time the combat loop actually started — the beat grid anchors here (the music's
    // downbeat), NOT game-load time, so weapons land on the music's beat even though the track
    // started earlier during the launch animation.
    private var combatMusicStartMs = 0L

    var isMuted: Boolean = false
        private set

    // ── Internal state ─────────────────────────────────────────────────
    private var initialized = false
    private var appContext: Context? = null

    // SoundPool for one-shot SFX
    private var soundPool: SoundPool? = null
    // Concurrent, not plain collections.
    //
    // init() spawns a background thread that loads 39 sounds, writing sfxIds as each lands, while
    // SoundPool's load-complete callback writes sfxLoaded and drains pendingPlays on a third
    // thread. playSFX reads all of them from the game thread, the UI thread and the hangar's
    // render thread — and the hangar is already interactive and playing tap sounds during that
    // window. A plain HashMap growing to 39 entries rehashes several times in exactly that
    // window, and a read mid-rehash is where unsafe maps return garbage or worse.
    //
    // The window is only the first seconds after launch; once loading finishes these are
    // read-only and were always safe. It is cheap to close anyway.
    private val sfxIds = ConcurrentHashMap<String, Int>()       // eventId → SoundPool sound id
    private val sfxLoaded = ConcurrentHashMap.newKeySet<Int>()  // sound ids confirmed loaded
    // Pending plays: queued when playSFX is called before a sound finishes loading
    private data class PendingPlay(val volume: Float, val rate: Float, val categoryVolume: Float)
    // Written from playSFX on the caller's thread AND removed from on SoundPool's load-complete
    // thread — a genuine two-writer case, the worst of the three.
    private val pendingPlays = ConcurrentHashMap<Int, PendingPlay>()  // soundId → play params

    /**
     * Minimum gap between two plays of the same event id.
     *
     * Ten enemies firing the same weapon in one frame asked the pool for ten voices of one sound —
     * ten synchronous play() calls on the game thread, for an effect the ear hears as one shot with
     * a smear on it. Those ten calls all land inside a single `update()`, microseconds apart, so
     * the window only has to be wide enough to cover one frame.
     *
     * It was 40ms, and that silenced the heart-to-heart typewriter.
     *
     * The original reasoning — "40ms allows 25 distinct plays a second, far above the fastest
     * weapon cooldown" — checked weapons and missed the typewriter, which is the fastest
     * intentional repeat in the game at exactly one tick per 0.04s. That is not "far above" the
     * window, it *is* the window. The accumulator in `updateHeartToHeart` resets to zero on each
     * tick, discarding the overshoot, so consecutive ticks are separated by exactly 40ms whenever
     * the frame period divides 40 evenly — which it does, since [GameConfig.FRAME_TIME_MS] is
     * `1000L / 120` = 8. That leaves a margin of zero. The gap actually measured is between two
     * `System.currentTimeMillis()` samples taken inside `update()`, and it drifts by a fraction of
     * a millisecond with how much work each frame did before reaching the tick — so a large share
     * of ticks measured 39 and were dropped, in the middle of the story's climax.
     *
     * 16ms is at least one frame at the 120fps budget (and covers a slow update() spreading a
     * volley across several milliseconds), while leaving 24ms of headroom under the typewriter.
     * The invariant to keep: **this must stay well under the fastest cadence any caller actually
     * intends**, not merely under the fastest one somebody remembered to check.
     * `SfxCoalesceTest` pins both halves.
     */
    internal const val SFX_COALESCE_MS = 16L

    /** The fastest intentional repeat in the game: the heart-to-heart typewriter, one per 0.04s. */
    internal const val FASTEST_INTENTIONAL_REPEAT_MS = 40L

    // SoundPool.play() is a synchronous hop into the audio server. On the game thread that is a
    // frame-time cost paid per voice, per frame. The queue owns it instead.
    // @Volatile: written on the init thread, read from the game and render threads on every
    // playSFX. Same hazard class as the maps above, same fix.
    @Volatile private var audioThread: HandlerThread? = null
    @Volatile private var audioHandler: Handler? = null
    // Concurrent, not a plain map: playSFX is called from the game thread AND the UI thread (the
    // hangar's taps and the cabinet both go through it), so an unsynchronized HashMap here can
    // corrupt under a resize.
    private val lastPlayedAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Ambient MediaPlayers (dual-player for gapless looping)
    private var ambientCurrent: MediaPlayer? = null
    private var ambientNext: MediaPlayer? = null
    private var ambientFadingOut: MediaPlayer? = null
    private var currentAmbientId: String? = null

    // Combat BGM sequential segments
    private var combatPlayer: MediaPlayer? = null
    private var combatSegments = listOf<String>()
    private var currentSegmentIndex = 0
    private var combatActive = false

    // Boss fight BGM
    private var bossPlayer: MediaPlayer? = null

    // Boss/reckoning BGM target level, read by setMuted and the fade-out so neither has to
    // guess what the track was playing at. It was also the reckoning's duck target, under a
    // ghost scene stage 3 deleted; nothing lowers it now, but it stays because mute and fade
    // both still need to know the level.
    private var bossPlayerVolume = 0.7f

    // Intro swell — a ~10s musical sting. Played via MediaPlayer, not SoundPool: SoundPool
    // caps each sample at ~1MB of decoded PCM (~5s at 48kHz stereo) and silently truncates
    // the rest, which cut the swell off mid-fade on the launchpad.
    private var introSwellPlayer: MediaPlayer? = null

    // ── All known SFX event IDs ────────────────────────────────────────
    // `internal`, not private: CabinetCueRegistrationTest crosses from the cabinet's cues to
    // this registry, which is the only place the two halves meet. An id missing here is a
    // silent no-op — playSFX returns on `sfxIds[eventId] ?: return` — and that shipped once.
    internal val allSfxEventIds = listOf(
        // Hangar (loaded first — launch is the earliest possible player action)
        // NOTE: sfx_intro_swell is intentionally NOT here — it's ~10s, which exceeds
        // SoundPool's ~1MB sample cap. It plays via MediaPlayer (playIntroSwell).
        "sfx_launch",
        "sfx_slot_win", "sfx_pilot_recruit",
        // Radio
        "sfx_radio_corrupted", "sfx_radio_crackle",
        // UI
        "sfx_ui_tap", "sfx_ui_swipe", "sfx_ui_purchase",
        "sfx_ui_upgrade_select", "sfx_slot_spin", "sfx_slot_jackpot",
        // Combat
        "sfx_shield_hit", "sfx_shield_break", "sfx_player_hit", "sfx_player_death",
        "sfx_boss_spawn", "sfx_phoenix_revive", "sfx_crystal_activate", "sfx_evolution",
        "sfx_lucky_star_bounce", "sfx_lucky_star_select",
        // Base weapons
        "sfx_weapon_pulse_cannon", "sfx_weapon_energy_saw", "sfx_weapon_scatter_shot",
        "sfx_weapon_homing_missiles", "sfx_weapon_ion_orbiters", "sfx_weapon_railgun",
        "sfx_weapon_space_mines", "sfx_weapon_solar_storm", "sfx_weapon_nova_blast",
        "sfx_weapon_needle_gun", "sfx_weapon_cluster_bomb", "sfx_weapon_flak_cannon",
        // Evolution weapons
        "sfx_weapon_storm_cannon", "sfx_weapon_warp_saw", "sfx_weapon_leech_burst",
        "sfx_weapon_autonomous_ace", "sfx_weapon_frost_ring", "sfx_weapon_oblivion_beam",
        "sfx_weapon_jackpot_mines", "sfx_weapon_phoenix_flare", "sfx_weapon_lingering_nova",
        "sfx_weapon_siphon_needles", "sfx_weapon_hunter_killer", "sfx_weapon_flak_barrage",
        // Drone
        "sfx_weapon_drone",
        // Extra
        "sfx_enemy_death", "sfx_asteroid_break", "sfx_powerup_pickup", "sfx_near_miss",
        "sfx_explosion", "sfx_yen_pickup",
        // Narrative
        "sfx_text_tick",
        // Desert
        "sfx_tank_shot", "sfx_desert_enemy_gun",
        // Crystal reveal + fleet
        "sfx_crystal_glow", "sfx_crystal_orb",
        // BELT RUN cabinet — 1979-idiom square waves and noise bursts, not the main game's SFX.
        "sfx_belt_fire",
        "sfx_belt_break_lge",
        "sfx_belt_break_med",
        "sfx_belt_break_sml",
        "sfx_belt_death",
        "sfx_belt_coin",
        "sfx_belt_beat_lo",
        "sfx_belt_beat_hi",
        // The crystal, in the machine's own idiom — a design decision.
        "sfx_belt_crystal_fire",
        "sfx_belt_crystal_hit",
        "sfx_belt_crystal_shatter"
    )

    // ── Volume category routing ────────────────────────────────────────

    private fun getVolumeCategory(eventId: String): Float {
        return when {
            eventId.startsWith("sfx_radio") -> volumeSfxRadio
            eventId.startsWith("sfx_ui_") || eventId.startsWith("sfx_slot") -> volumeSfxUi
            else -> volumeSfxCombat
        }
    }

    /**
     * Apply an [AudioMode]. Music and combat are silenced independently; the hangar's own
     * interface sounds only stop for [AudioMode.NONE].
     *
     * [isMuted] keeps its original meaning of "everything off" so the many music-volume sites that
     * already consult it stay correct, and [combatSfxMuted] adds the narrower gate on top.
     */
    fun applyAudioMode(mode: AudioMode) {
        audioMode = mode
        setMuted(mode.musicSilenced)
    }

    /** What is currently being silenced. Every SFX gate asks this rather than [isMuted]. */
    var audioMode: AudioMode = AudioMode.ALL
        private set

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) {
            setPlayerVolume(ambientCurrent, 0f)
            setPlayerVolume(ambientNext, 0f)
            setPlayerVolume(ambientFadingOut, 0f)
            setPlayerVolume(combatPlayer, 0f)
            bossPlayer?.setVolume(0f, 0f)
        } else {
            setPlayerVolume(ambientCurrent, volumeAmbient)
            setPlayerVolume(ambientNext, volumeAmbient)
            setPlayerVolume(combatPlayer, volumeAmbient)
            bossPlayer?.setVolume(bossPlayerVolume, bossPlayerVolume)
        }
    }

    // ── Initialization ─────────────────────────────────────────────────

    /**
     * Initialize SoundPool and load all SFX from res/raw/.
     * Safe to call multiple times — only the first call takes effect.
     */
    fun init(ctx: Context) {
        if (initialized) return

        appContext = ctx.applicationContext

        val prefs = ctx.getSharedPreferences("astrohunt_save", Context.MODE_PRIVATE)
        applyAudioMode(
            AudioMode.resolve(
                storedName = prefs.getString("audio_mode", null),
                legacyMuted = prefs.getBoolean("audio_muted", false)
            )
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        audioThread = HandlerThread("astroloop-sfx").also { it.start() }
        audioHandler = Handler(audioThread!!.looper)

        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(audioAttributes)
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0) {
                        sfxLoaded.add(sampleId)
                        // Play any sound that was requested before it finished loading
                        pendingPlays.remove(sampleId)?.let { pending ->
                            // Re-asked on arrival: the mode may have changed while it loaded.
                            if (!audioMode.effectsSilenced) {
                                val vol = (pending.volume * pending.categoryVolume).coerceIn(0f, 1f)
                                val rate = pending.rate.coerceIn(0.5f, 2.0f)
                                val handler = audioHandler
                                if (handler != null) {
                                    handler.post { pool.play(sampleId, vol, vol, 1, 0, rate) }
                                } else {
                                    pool.play(sampleId, vol, vol, 1, 0, rate)
                                }
                            }
                        }
                    }
                }
            }

        initialized = true

        // Load SFX on background thread — pendingPlays queue handles early access
        Thread {
            for (eventId in allSfxEventIds) {
                loadSfx(eventId)
            }
        }.start()
    }

    /**
     * Try to load a single SFX from res/raw/. If the resource doesn't exist,
     * silently skip — no crash, no code changes needed.
     */
    private fun loadSfx(eventId: String) {
        val ctx = appContext ?: return
        val pool = soundPool ?: return

        val resId = ctx.resources.getIdentifier(eventId, "raw", ctx.packageName)
        if (resId == 0) {
            // File not in res/raw/ — that's fine, play will be silent
            return
        }

        try {
            val soundId = pool.load(ctx, resId, 1)
            sfxIds[eventId] = soundId
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load SFX: $eventId", e)
        }
    }

    // ── One-shot SFX ───────────────────────────────────────────────────

    /**
     * Fire a one-shot SFX. If the event ID has no loaded file, this is a no-op.
     *
     * @param eventId  The SFX event name (e.g. "sfx_weapon_railgun")
     * @param volume   Per-call volume multiplier (0.0–1.0), combined with category volume
     * @param rate     Playback rate (0.5–2.0)
     */
    /**
     * Whether this play of [eventId] at [now] falls inside [SFX_COALESCE_MS] of the last one, and
     * should therefore be dropped as a duplicate. Records [now] as the last play when it does not.
     *
     * Extracted from [playSFX] so the window can be tested without standing up a SoundPool — the
     * bug this guards against is entirely a question of arithmetic on timestamps.
     */
    internal fun isCoalescedDuplicate(eventId: String, now: Long): Boolean {
        val last = lastPlayedAtMs[eventId]
        if (last != null && now - last < SFX_COALESCE_MS) return true
        lastPlayedAtMs[eventId] = now
        return false
    }

    fun playSFX(eventId: String, volume: Float = 1.0f, rate: Float = 1.0f, isSoundboard: Boolean = false) {
        // isMuted now tracks the music, which may be silenced while the fight is not — so the SFX
        // gate reads the two dedicated flags instead of riding on it.
        if (audioMode.effectsSilenced) return
        val pool = soundPool ?: return
        val soundId = sfxIds[eventId] ?: return

        // Per-sound loudness trim applied before both the deferred and immediate paths.
        val perSoundVolume = if (eventId == "sfx_ui_tap") volume * TAP_VOLUME_MULT else volume

        val categoryVolume = getVolumeCategory(eventId)

        if (!sfxLoaded.contains(soundId)) {
            // Sound still loading — queue it to play when ready
            pendingPlays[soundId] = PendingPlay(perSoundVolume, rate, categoryVolume)
            return
        }

        if (isCoalescedDuplicate(eventId, System.currentTimeMillis())) return

        val weaponMult = if (!isSoundboard && eventId.startsWith("sfx_weapon_")) weaponVolume else 1f
        val finalVolume = (perSoundVolume * categoryVolume * weaponMult).coerceIn(0f, 1f)
        val playbackRate = rate.coerceIn(0.5f, 2.0f)
        val handler = audioHandler
        if (handler != null) {
            handler.post { pool.play(soundId, finalVolume, finalVolume, 1, 0, playbackRate) }
        } else {
            // No audio thread (release() has run, or a test): play inline rather than drop it.
            pool.play(soundId, finalVolume, finalVolume, 1, 0, playbackRate)
        }
    }

    // ── Ambient (single track, e.g. hangar) ────────────────────────────

    /**
     * Start or crossfade to an ambient track. The file should exist in res/raw/
     * as e.g. `ambient_hangar.ogg`. If already playing the same track, no-op.
     * If a different track is playing, crossfade out the old one.
     */
    fun playAmbient(ambientId: String) {
        if (ambientId == currentAmbientId && ambientCurrent?.isPlaying == true) return

        val ctx = appContext ?: return
        val resId = ctx.resources.getIdentifier(ambientId, "raw", ctx.packageName)
        if (resId == 0) {
            Log.d(TAG, "Ambient track not found: $ambientId")
            return
        }

        // Dequeue next player before switching tracks
        try { ambientCurrent?.setNextMediaPlayer(null) } catch (_: Exception) {}
        ambientNext?.release()
        ambientNext = null

        // Fade out current ambient if any
        ambientCurrent?.let { old ->
            ambientFadingOut?.release()
            ambientFadingOut = old
            fadeOutAndRelease(old)
        }

        // Start new ambient
        try {
            val current = MediaPlayer.create(ctx, resId) ?: return
            current.isLooping = false
            current.setVolume(0f, 0f)
            current.start()
            fadeIn(current, if (isMuted) 0f else volumeAmbient)
            ambientCurrent = current
            currentAmbientId = ambientId
            queueNextAmbient(resId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play ambient: $ambientId", e)
        }
    }

    private fun queueNextAmbient(resId: Int) {
        val ctx = appContext ?: return
        val current = ambientCurrent ?: return
        var next: MediaPlayer? = null
        try {
            next = MediaPlayer.create(ctx, resId) ?: return
            next.isLooping = false
            current.setNextMediaPlayer(next)
            current.setOnCompletionListener { done ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    done.release()
                    if (ambientCurrent === done) {
                        ambientCurrent = next
                        ambientNext = null
                        // Apply current mute state now that next has become current
                        setPlayerVolume(ambientCurrent, if (isMuted) 0f else volumeAmbient)
                        val id = currentAmbientId ?: return@post
                        val nextResId = ctx.resources.getIdentifier(id, "raw", ctx.packageName)
                        if (nextResId != 0) queueNextAmbient(nextResId)
                    }
                }
            }
            ambientNext = next
        } catch (e: Exception) {
            Log.w(TAG, "Failed to queue next ambient", e)
            next?.release()
        }
    }

    /**
     * Stop the current ambient track with a fade-out.
     */
    fun stopAmbient(fadeOutMillis: Long? = null) {
        try { ambientCurrent?.setNextMediaPlayer(null) } catch (_: Exception) {}
        ambientNext?.release()
        ambientNext = null
        val toFade = ambientCurrent
        ambientCurrent = null
        currentAmbientId = null
        toFade?.let { fadeOutAndRelease(it, fadeOutMillis = fadeOutMillis) }
    }

    // ── Combat BGM (sequential segments) ───────────────────────────────

    /**
     * Start combat BGM. Plays segments sequentially (intro → loop).
     * The last segment loops. BeatClock starts for weapon sync.
     */
    /**
     * Start combat music early (during launch animation) with a fade-in.
     * BeatClock is NOT started — call startCombatMusic() later for beat sync.
     */
    fun startCombatMusicEarly(context: Context) {
        if (combatActive) return
        stopAmbient()
        combatActive = true
        combatSegments = listOf("bgm_${activeSet}_combat_loop")
        currentSegmentIndex = 0
        playCombatSegment(context, 0)
        combatMusicStartMs = System.currentTimeMillis()   // anchor ≈ music onset (after prep/start)
        // Override volume — fade in from silence
        combatPlayer?.let {
            it.setVolume(0f, 0f)
            fadeIn(it, if (isMuted) 0f else volumeAmbient)
        }
    }

    fun startCombatMusic(context: Context) {
        if (combatActive) {
            // Music already started early (launch animation) — anchor the beat grid to WHEN THE
            // MUSIC STARTED, not now, so weapon hits land on the music's beat (not offset by the
            // launch-animation gap). The clock's elapsed-since-start math handles the catch-up.
            beatClock.start(combatMusicStartMs)
            return
        }
        stopAmbient()
        combatActive = true
        combatSegments = listOf("bgm_${activeSet}_combat_loop")
        currentSegmentIndex = 0
        playCombatSegment(context, 0)
        combatMusicStartMs = System.currentTimeMillis()
        beatClock.start(combatMusicStartMs)
    }

    private fun playCombatSegment(context: Context, index: Int) {
        combatPlayer?.release()
        val resId = context.resources.getIdentifier(combatSegments[index], "raw", context.packageName)
        if (resId == 0) {
            Log.d(TAG, "Combat segment not found: ${combatSegments[index]}")
            return
        }
        try {
            combatPlayer = MediaPlayer.create(context, resId)?.apply {
                val isLastSegment = index == combatSegments.size - 1
                isLooping = isLastSegment
                val vol = if (isMuted) 0f else volumeAmbient
                setVolume(vol, vol)
                setOnCompletionListener {
                    if (!isLastSegment && combatActive) {
                        currentSegmentIndex = index + 1
                        playCombatSegment(context, currentSegmentIndex)
                    }
                }
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play combat segment: ${combatSegments[index]}", e)
        }
    }

    /**
     * Stop combat BGM with fade-out. Stops BeatClock.
     */
    fun stopCombatMusic() {
        combatActive = false
        beatClock.stop()
        combatPlayer?.let { fadeOutAndRelease(it) }
        combatPlayer = null
        bossPlayer?.let { fadeOutAndRelease(it) }
        bossPlayer = null
    }

    fun startBossBGM(context: Context) = startBossTrack(context, "bgm_boss")

    private fun startBossTrack(context: Context, resName: String) {
        // This can run on a boss-spawn frame before any fight phase is set, and GameThread
        // swallows anything update() throws — a throw here permanently freezes the scripted
        // choreography. MediaPlayer.create(context, 0) throws NotFoundException, so a
        // missing track must bail out first, before killing the combat loop.
        val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
        if (resId == 0) {
            Log.w(TAG, "$resName not found in res/raw — keeping the combat loop")
            return
        }

        // Fade out combat music if still playing
        combatPlayer?.let { fadeOutAndRelease(it) }
        combatPlayer = null
        combatActive = false

        bossPlayer?.release()
        bossPlayerVolume = 0.7f
        val vol = if (isMuted) 0f else bossPlayerVolume
        try {
            bossPlayer = MediaPlayer.create(context, resId)?.apply {
                isLooping = true
                setVolume(vol, vol)
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play $resName", e)
        }
        // Re-lock the beat grid to the track's start so grid consumers stay on-beat.
        beatClock.start(System.currentTimeMillis())
    }


    /** Fade the boss/reckoning BGM out and release it — the shatter kills the beat. */
    fun fadeOutBossBGM() {
        bossPlayer?.let { fadeOutAndRelease(it, fromVolume = bossPlayerVolume) }
        bossPlayer = null
    }

    /**
     * Play the intro swell (one-shot). Uses MediaPlayer so the full ~10s plays —
     * SoundPool would truncate it at ~5s (see introSwellPlayer). Full volume to match
     * its previous SoundPool playback (volumeSfxCombat = 1.0).
     */
    fun playIntroSwell(context: Context) {
        if (isMuted) return
        introSwellPlayer?.release()
        introSwellPlayer = null
        val resId = context.resources.getIdentifier("sfx_intro_swell", "raw", context.packageName)
        if (resId == 0) return
        try {
            introSwellPlayer = MediaPlayer.create(context, resId)?.apply {
                isLooping = false
                setVolume(1f, 1f)
                setOnCompletionListener {
                    it.release()
                    if (introSwellPlayer === it) introSwellPlayer = null
                }
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play intro swell", e)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Pause all audio (call from Activity.onPause).
     */
    fun pause() {
        ambientCurrent?.let { if (it.isPlaying) it.pause() }
        // Prevent automatic transition to ambientNext while backgrounded
        try { ambientCurrent?.setNextMediaPlayer(null) } catch (_: Exception) {}
        combatPlayer?.let { if (it.isPlaying) it.pause() }
        bossPlayer?.let { if (it.isPlaying) it.pause() }
        introSwellPlayer?.let { if (it.isPlaying) it.pause() }
        soundPool?.autoPause()
    }

    /**
     * Resume all audio (call from Activity.onResume).
     */
    fun resume() {
        ambientCurrent?.start()
        // Release stale next player before re-queuing (pause dequeued it via setNextMediaPlayer(null))
        ambientNext?.release()
        ambientNext = null
        // Re-queue next player
        val id = currentAmbientId
        val ctx = appContext
        if (id != null && ctx != null) {
            val resId = ctx.resources.getIdentifier(id, "raw", ctx.packageName)
            if (resId != 0) queueNextAmbient(resId)
        }
        if (combatActive) combatPlayer?.start()
        bossPlayer?.start()
        try { introSwellPlayer?.start() } catch (_: Exception) {}
        soundPool?.autoResume()
    }

    /**
     * Stop all audio immediately (no fade).
     */
    fun stopAll() {
        stopAmbient()
        stopCombatMusic()
        introSwellPlayer?.let { fadeOutAndRelease(it, fromVolume = 1f, fadeOutMillis = 400) }
        introSwellPlayer = null
        // SoundPool streams stop when pool is paused/destroyed
        soundPool?.autoPause()
        volumeAmbient = 0.8f
    }

    /**
     * Release all resources. Call from Activity.onDestroy.
     * After this, init() must be called again before use.
     */
    fun release() {
        try { ambientCurrent?.setNextMediaPlayer(null) } catch (_: Exception) {}
        ambientCurrent?.release()
        ambientCurrent = null
        ambientNext?.release()
        ambientNext = null
        ambientFadingOut?.release()
        ambientFadingOut = null
        currentAmbientId = null

        combatPlayer?.release()
        combatPlayer = null
        combatActive = false

        bossPlayer?.release()
        bossPlayer = null

        introSwellPlayer?.release()
        introSwellPlayer = null

        audioHandler = null
        audioThread?.quitSafely()
        audioThread = null
        lastPlayedAtMs.clear()

        soundPool?.release()
        soundPool = null
        sfxIds.clear()
        sfxLoaded.clear()
        pendingPlays.clear()

        initialized = false
        appContext = null
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun setPlayerVolume(player: MediaPlayer?, volume: Float) {
        try {
            val v = if (isMuted) 0f else volume.coerceIn(0f, 1f)
            player?.setVolume(v, v)
        } catch (e: IllegalStateException) {
            // Player may have been released
        }
    }

    /**
     * Fade in a MediaPlayer from 0 to target volume over ~500ms.
     * Uses a background thread to avoid blocking the game loop.
     */
    private fun fadeIn(player: MediaPlayer, targetVolume: Float) {
        Thread {
            var vol = 0f
            try {
                while (vol < targetVolume) {
                    vol = (vol + CROSSFADE_STEP).coerceAtMost(targetVolume)
                    player.setVolume(vol, vol)
                    Thread.sleep(20)
                }
            } catch (_: Exception) {
                // Player released during fade — ignore
            }
        }.start()
    }

    /**
     * Fade out a MediaPlayer and release it when done.
     * Uses a background thread to avoid blocking the game loop.
     */
    private fun fadeOutAndRelease(
        player: MediaPlayer,
        fromVolume: Float = volumeAmbient,
        fadeOutMillis: Long? = null,
    ) {
        Thread {
            try {
                val start = if (isMuted) 0f else fromVolume
                val step = if (fadeOutMillis != null)
                    start / (fadeOutMillis / 20L).coerceAtLeast(1L)
                else CROSSFADE_STEP
                var vol = start
                while (vol > 0f) {
                    vol = (vol - step).coerceAtLeast(0f)
                    player.setVolume(vol, vol)
                    Thread.sleep(20)
                }
                player.stop()
                player.release()
            } catch (_: Exception) {
                // Player may already be released — ignore
                try { player.release() } catch (_: Exception) {}
            }
        }.start()
    }
}
