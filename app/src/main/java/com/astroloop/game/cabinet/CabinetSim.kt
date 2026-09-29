package com.astroloop.game.cabinet

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * BELT RUN itself. Waves of rocks, auto-fire forward, one life, score capped at 999.
 *
 * Pure — no Canvas, no Android, injected [rng]. It knows nothing about the crystal,
 * the hangar, or the story; the reckoning is layered on top of this in stage 2 without
 * this class changing.
 */
class CabinetSim(
    val m: CabinetMetrics,
    private val rng: Random
) {
    val ship = CabinetShip(m)
    private val _rocks = ArrayList<CabinetRock>()
    private val _bullets = ArrayList<CabinetBullet>()
    private val _debris = ArrayList<CabinetDebris>()

    val rocks: List<CabinetRock> get() = _rocks
    val bullets: List<CabinetBullet> get() = _bullets
    val debris: List<CabinetDebris> get() = _debris

    var score: Int = 0; private set
    var wave: Int = 0; private set
    var over: Boolean = false; private set

    /**
     * Seconds left on the WAVE N announcement, 0 when hidden.
     *
     * The original machine showed no wave number at all; this is a deliberate departure,
     * asked for on device. It is an overlay and never a gate — rocks move and the gun
     * fires underneath it.
     */
    var waveBanner: Float = 0f; private set

    /**
     * The reckoning's target, or null in free play.
     *
     * The original spec boundary said this class must not know the crystal exists. That was
     * dropped in a design decision — the spec's own architecture table already gave the director
     * "writes bullets into it", and the spec then requires those bullets not to wrap and
     * a design decision requires them red, which IS the crystal's distinguishing properties
     * living here. Keeping the boundary meant inventing generic APIs whose only purpose
     * was to avoid writing one word.
     */
    /**
     * Set through [placeCrystal] and cleared by [start]/[shatterCrystal] — never assigned
     * from outside. `private set` was dropped in the stage-4 reshape and no caller ever
     * needed it: an external write could swap or null the crystal mid-fight and skip
     * shatterCrystal's debris entirely, which is the no-vanishing rule broken at its source.
     */
    var crystal: CabinetCrystal? = null
        private set

    /**
     * Rocks hold their positions. Set while the crystal is on its way in.
     *
     * Deliberately narrower than a general pause: collisions below still resolve, so a
     * player who flies into a frozen rock still dies on it. It is the field holding its
     * breath, not the sim stopping.
     */
    var rocksFrozen: Boolean = false

    /**
     * Stops the wave engine.
     *
     * The wave advance fires on an EMPTY field, and the fight runs on one — so without
     * this the reckoning mints a fresh wave every frame behind the crystal.
     */
    var wavesSuspended: Boolean = false

    /**
     * Whether broken rocks pay. Off for the whole of a reckoning run.
     *
     * Owner, device pass: *"the crystal boss fight [should] not record any high scores,
     * it's a little weird that after the fight you get some points, that's not at all the
     * purpose of the fight."* The board was never at risk — `CabinetShell` gates the write
     * on `!isReckoning` — but the score still ACCRUED, because the authored opening seeds a
     * ring of rocks and shooting them paid. Set beside [wavesSuspended], by the same code,
     * for the same reason: the entrance is not free play.
     */
    var scores: Boolean = true

    /** True once the three-digit readout has stuck. The pilot is cleared at this moment. */
    var capped: Boolean = false; private set

    /**
     * Slows everything the sim does. 1.0 normally; the ending drops it so the shatter has
     * weight.
     *
     * Applied once at the top of [update] rather than at each call site, so nothing can be
     * left running at real time while the rest of the field crawls.
     */
    var timeScale: Float = 1f

    // --- Per-frame event tallies, for the host's audio ---
    // Discrete events cannot be recovered by diffing the collections across frames: a
    // spawn and a destruction in the same frame cancel out, and a bullet expiring as
    // another is fired hides the shot entirely. Counted where they actually happen,
    // cleared at the top of every update().
    var shotsFiredThisFrame = 0; private set
    val destroyedThisFrame = IntArray(RockSize.values().size)
    var shipDiedThisFrame = false; private set
    /**
     * Player shots that landed on the crystal this frame.
     *
     * Its own tally rather than a diff of the crystal's hp, because hp is not the question:
     * the cue answers "did I connect", and a hit inside a saturated band once moved hp by a
     * fraction. It also cannot be recovered from the bullet list — the bullet is CONSUMED by
     * the hit, so a landed shot and an expired one look identical across frames.
     */
    var crystalHitsThisFrame = 0; private set
    /**
     * A wave has spawned and the host has not been told yet.
     *
     * Deliberately NOT a per-frame flag like its neighbours. Waves spawn from two
     * places: inside update(), and from start(), which runs outside the frame loop
     * entirely. A flag cleared at the top of update() is already false by the time the
     * host looks, so every run's opening wave would silently skip its heartbeat reset.
     * This latches until [consumeWaveStart] takes it.
     */
    private var pendingWaveStart = false

    /** @return true once per spawned wave, then false until the next one. */
    fun consumeWaveStart(): Boolean {
        val v = pendingWaveStart
        pendingWaveStart = false
        return v
    }

    private var fireTimer = 0f

    fun start() {
        _rocks.clear(); _bullets.clear()
        _debris.clear()
        score = 0; over = false; capped = false; fireTimer = 0f
        scores = true
        waveBanner = 0f
        crystal = null
        timeScale = 1f
        wavesSuspended = false
        // The entrance freezes the field for the crystal's flight and thaws it on landing.
        // Die during those 4.5s and AGAIN? used to replay the whole authored opening with
        // motionless asteroids, because this reset every other per-run flag but not this.
        rocksFrozen = false
        ship.reset()
        shotsFiredThisFrame = 0
        destroyedThisFrame.fill(0)
        shipDiedThisFrame = false
        crystalHitsThisFrame = 0
        pendingWaveStart = false
        spawnWave(1)
    }

    fun update(rawDt: Float, inputX: Float, inputY: Float, hasInput: Boolean) {
        val dt = rawDt * timeScale
        shotsFiredThisFrame = 0
        destroyedThisFrame.fill(0)
        shipDiedThisFrame = false
        crystalHitsThisFrame = 0

        // Wreckage and rocks tick even once the run is over. Freezing the frame the ship
        // dies would make it vanish by another name, and the drift-and-fade is the beat
        // the attract demo restarts on.
        //
        // This is only reachable if the caller keeps calling. CabinetShell.update()'s
        // OVER branch exists to do exactly that — it once fell through to `Unit`, which
        // made everything below dead code on the player's own death and left the wreck
        // frozen on screen. If that branch stops ticking, this stops being true.
        val dIt = _debris.iterator()
        while (dIt.hasNext()) {
            val d = dIt.next()
            d.update(dt, m)
            if (d.life <= 0f) dIt.remove()
        }
        if (waveBanner > 0f) waveBanner = (waveBanner - dt).coerceAtLeast(0f)
        if (over) {
            _rocks.forEach { it.update(dt, m) }
            // Shots already in the air keep flying and expire on their own lifetime.
            // Freezing them left bullets hanging in the air under GAME OVER — the same
            // defect the wreck had, in the one entity nobody had thought about. They can
            // no longer hit anything: resolveBulletHits sits below this return, so the
            // run really is over and a late shot cannot score.
            val overIt = _bullets.iterator()
            while (overIt.hasNext()) {
                val b = overIt.next()
                b.update(dt, m)
                if (b.life <= 0f || b.escaped) overIt.remove()
            }
            return
        }

        ship.update(dt, inputX, inputY, hasInput)
        // Frozen during the crystal's arrival — a design decision. The field stops so the thing
        // flying into it is the only thing moving, which is the whole image. Only the ROCKS
        // hold still: the ship still flies, its shots still travel and debris still fades,
        // because a player who cannot move while something approaches is being shown a
        // cutscene rather than an entrance.
        if (!rocksFrozen) _rocks.forEach { it.update(dt, m) }

        // CORRECTED after a device pass — the comment here used to say "the cap and the
        // bullet lifetime ARE the fire rate: 4 slots over a 1.2s life is 3.33 shots/sec
        // sustained... fireInterval survives only as a floor". **That model is wrong, and
        // believing it cost two authored patterns.** A bullet that HITS is consumed at the
        // crystal's surface (resolveBulletHits below), 0.564s away at engagement range, so
        // four slots want 7.1 shots/sec and the CADENCE is what binds, not the cap. The
        // cap only binds when shots are missing or flying to the far edge.
        //
        // And the cadence is not 1/6 either. `fireTimer -= dt` with dt = 1/60 leaves a
        // positive float32 residue after ten frames, so the gate opened on the ELEVENTH and
        // delivered 5.46 shots/sec against a nominal 6.0. FIXED below with a carry — the
        // same accumulator CabinetCrystal's soft floor needs, and the same reason. Measured
        // after the fix: exactly 600 shots per 100s.
        //
        // Do not re-derive a sustained fire rate from MAX_BULLETS and BULLET_LIFETIME.
        // ReckoningWinnableTest did exactly that and asserted something false.
        //
        // A blocked shot is skipped, never queued — and the carry must not break that.
        // `+= fireInterval` alone would let debt pile up while the slots are full and then
        // spend it as a burst the instant one frees. Clamping the timer at zero while
        // blocked is what keeps "one shot the moment a slot frees, not four" true: the gun
        // waits at zero rather than going into debt.
        //
        // Counts the PLAYER's shots only. A design decision merged both kinds into one list, and
        // this gate was left reading its size — so during the reckoning, where the crystal
        // keeps 14 to 88 bullets on the field, `size < 4` was almost never true and the
        // player's gun was effectively OFF. Measured: one shot in fifteen seconds, against
        // the ~80 the real cadence delivers. The ending could not be won.
        // In free play every bullet is the player's, so count == size and nothing changes.
        fireTimer -= dt
        val hasSlot = _bullets.count { !it.hostile } < MAX_BULLETS
        if (!hasSlot) fireTimer = fireTimer.coerceAtLeast(0f)
        if (fireTimer <= 0f && hasSlot) {
            fire()
            // `+=`, not `=`: carries the sub-frame overshoot so the next interval starts
            // however early this one ran late. `=` discards it every shot, and the discard
            // is what cost 9% of the cadence.
            fireTimer += m.fireInterval
        }

        val it = _bullets.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.update(dt, m)
            if (b.life <= 0f || b.escaped) it.remove()
        }

        resolveBulletHits()
        resolveHostileHits()
        resolveShipCollision()

        // Guarded on !over: resolveShipCollision() above removes the rock that killed the
        // ship, so taking the last rock of a wave with you would otherwise spawn the next
        // one — banner and all — behind a corpse that cannot shoot it.
        if (!over && !wavesSuspended && _rocks.isEmpty()) spawnWave(wave + 1)
    }

    private fun fire() {
        shotsFiredThisFrame++
        val sx = sin(ship.heading)
        val sy = -cos(ship.heading)
        // Muzzle sits at the nose, not the hull's centre. HULL[0] is the nose vertex
        // (see CabinetShip.HULL) and its distance from the centre, in ship radii, is
        // its own length - derived rather than hardcoded so the muzzle can't drift
        // from the drawn hull if the ship is ever reshaped.
        val nose = CabinetShip.HULL[0]
        val noseOffset = hypot(nose[0], nose[1]) * m.shipRadius
        var spawnX = ship.x + sx * noseOffset
        var spawnY = ship.y + sy * noseOffset
        // Wrap explicitly: near an edge the muzzle offset alone can push the spawn
        // point outside [0, width) / [0, height). The bullet-update loop later this
        // same frame would wrap it too, but that's incidental ordering - not a
        // guarantee - so the new bullet is born already valid.
        if (spawnX < 0f) spawnX += m.width
        if (spawnX >= m.width) spawnX -= m.width
        if (spawnY < 0f) spawnY += m.height
        if (spawnY >= m.height) spawnY -= m.height
        _bullets.add(
            CabinetBullet(
                spawnX, spawnY,
                sx * m.bulletSpeed + ship.vx, sy * m.bulletSpeed + ship.vy,
                m.bulletLifetime
            )
        )
    }

    private fun resolveBulletHits() {
        val c = crystal
        val bIt = _bullets.iterator()
        while (bIt.hasNext()) {
            val b = bIt.next()
            // The crystal's own shots pass straight through it; only the player's bite.
            if (b.hostile) continue
            // The crystal first: it is anchored at centre and the rocks are cleared
            // during the fight, so this is the common case there and a no-op in free play.
            // The bullet is CONSUMED, not merely counted — otherwise one shot damages on
            // every frame it overlaps and the whole DPS model behind rule 4 collapses.
            if (c != null && c.alive && m.distance(c.x, c.y, b.x, b.y) < c.radius(m)) {
                bIt.remove()
                c.damage()
                crystalHitsThisFrame++
                continue
            }
            // Toroidal: the field wraps, so a naive delta misses contacts at the seam.
            val hit = _rocks.firstOrNull { m.distance(it.x, it.y, b.x, b.y) < it.radius(m) }
            if (hit != null) {
                bIt.remove()
                destroy(hit)
            }
        }
    }

    private fun resolveShipCollision() {
        if (!ship.alive) return
        // The crystal is a body, not a hologram. The spec never said so, and left open the
        // optimal strategy is to sit INSIDE it, where its bullets have not spread yet and
        // it cannot miss. It is in effect a very large rock, so it uses the rock
        // convention - the full shipRadius, not the smaller bullet hitbox.
        val c = crystal
        if (c != null && c.alive &&
            m.distance(c.x, c.y, ship.x, ship.y) < c.radius(m) + m.shipRadius
        ) {
            killShip()
            return
        }
        val rock = _rocks.firstOrNull {
            m.distance(it.x, it.y, ship.x, ship.y) < it.radius(m) + m.shipRadius
        } ?: return
        // Both come apart. This rock is removed directly rather than through destroy(),
        // so it needs its own shatter or the no-vanishing rule is breached right here.
        shatterRock(rock)
        _rocks.remove(rock)
        killShip()
    }

    /**
     * End the run and break the ship into its own edges.
     *
     * Shared by every death path. It exists because `shatterShip()` used to be reachable
     * only from the rock collision, so a ship killed any other way would have ended the
     * run without a visible exit — the no-vanishing rule breached by omission,
     * and the exact shape of the Critical the stage 1 final review caught.
     */
    private fun killShip() {
        shatterShip()
        ship.alive = false
        over = true
        shipDiedThisFrame = true
    }

    /**
     * The crystal's shots against the ship, at the SMALLER hitbox — see
     * [CabinetMetrics.crystalBulletHitRadius].
     */
    private fun resolveHostileHits() {
        if (!ship.alive) return
        val reach = m.crystalBulletHitRadius + m.crystalBulletRadius
        val it = _bullets.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (!b.hostile) continue
            if (m.distance(b.x, b.y, ship.x, ship.y) < reach) {
                it.remove()
                killShip()
                return
            }
        }
    }

    /** Destroy [rock], award its points, and return whatever it split into. */
    private fun destroy(rock: CabinetRock): List<CabinetRock> {
        shatterRock(rock)
        _rocks.remove(rock)
        destroyedThisFrame[rock.size.ordinal]++
        addScore(rock.size.points)
        val kids = CabinetRock.split(rock, rng, m)
        _rocks.addAll(kids)
        return kids
    }

    /** Scratch for building a shape's world-space outline before shattering it. */
    private val shatterPts = FloatArray(64)

    /** The crystal's outline is far larger than any rock's — its own buffer. */
    private val crystalPts = FloatArray(CRYSTAL_DEBRIS_SUBDIVISIONS * CabinetCrystal.SHELL_POINTS * 2)

    private fun shatterRock(rock: CabinetRock) {
        val r = rock.radius(m)
        val c = cos(rock.rot); val s = sin(rock.rot)
        val n = rock.shape.size / 2
        var i = 0
        for (v in 0 until n) {
            val lx = rock.shape[v * 2] * r
            val ly = rock.shape[v * 2 + 1] * r
            shatterPts[i++] = rock.x + (lx * c - ly * s)
            shatterPts[i++] = rock.y + (lx * s + ly * c)
        }
        _debris.addAll(CabinetDebris.shatter(rock.x, rock.y, shatterPts, i, rng, m, rock.vx, rock.vy))
    }

    private fun shatterShip() {
        val c = cos(ship.heading); val s = sin(ship.heading)
        val r = m.shipRadius
        var i = 0
        for (p in CabinetShip.HULL) {
            val lx = p[0] * r; val ly = p[1] * r
            shatterPts[i++] = ship.x + (lx * c - ly * s)
            shatterPts[i++] = ship.y + (lx * s + ly * c)
        }
        _debris.addAll(CabinetDebris.shatter(ship.x, ship.y, shatterPts, i, rng, m, ship.vx, ship.vy))
    }

    private fun addScore(points: Int) {
        if (!scores) return
        score = (score + points).coerceAtMost(SCORE_CAP)
        if (score >= SCORE_CAP) capped = true
    }

    private fun spawnWave(n: Int) {
        wave = n
        pendingWaveStart = true
        waveBanner = WAVE_BANNER_SECONDS
        val count = (n + 3).coerceAtMost(MAX_LARGE_PER_WAVE)
        val safeRadius = SPAWN_SAFE_RADIUS_FRAC * m.minEdge
        repeat(count) { _rocks.add(spawnRockAwayFromShip(n, safeRadius)) }
    }

    /**
     * Rocks still spawn on the field's edges — that part of the distribution is
     * unchanged. What is NOT true, and used to be claimed in a comment right here, is
     * that an edge is "away from the player": the playfield is a torus, so x = 0 and
     * x = m.width are adjacent, and wrapping is how the player escapes — hugging an
     * edge is normal, skilful play. The real guarantee is the toroidal distance check
     * below. Retries are capped at [SPAWN_MAX_ATTEMPTS] and fall back to the best
     * (farthest-from-ship) candidate seen, explicitly, so a pathological field aspect
     * or safe-radius fraction degrades gracefully instead of hanging the game.
     */
    private fun spawnRockAwayFromShip(n: Int, safeRadius: Float): CabinetRock {
        var bestX = 0f
        var bestY = 0f
        var bestDist = -1f
        for (attempt in 0 until SPAWN_MAX_ATTEMPTS) {
            val onVertical = rng.nextBoolean()
            val x = if (onVertical) (if (rng.nextBoolean()) 0f else m.width) else rng.nextFloat() * m.width
            val y = if (onVertical) rng.nextFloat() * m.height else (if (rng.nextBoolean()) 0f else m.height)
            val d = m.distance(x, y, ship.x, ship.y)
            if (d > bestDist) { bestDist = d; bestX = x; bestY = y }
            if (d >= safeRadius) break
        }
        val a = rng.nextFloat() * 2f * PI.toFloat()
        val sp = m.rockBaseSpeed * (0.8f + rng.nextFloat() * 0.5f) * (1f + (n - 1) * 0.06f)
        return CabinetRock(
            RockSize.LARGE, bestX, bestY, cos(a) * sp, sin(a) * sp,
            CabinetRock.generateShape(rng),
            rng.nextFloat() * 2f * PI.toFloat(),
            (rng.nextFloat() - 0.5f) * 1.0f
        )
    }

    /**
     * Spawn one of the crystal's shots. Called by the host with whatever
     * [ReckoningDirector] emitted this frame; the sim owns and ticks it from here.
     */
    fun addHostileBullet(b: CabinetBullet) { _bullets.add(b) }

    fun placeCrystal(c: CabinetCrystal) { crystal = c }

    /**
     * Moves everything in the field by the same proportion the screen just changed by, so a
     * rotation keeps each thing where it was relative to the tube instead of stranding it
     * outside the new bounds. Call it AFTER [CabinetMetrics.resize].
     *
     * Not a mid-run feature: a run holds the orientation it began in
     * (HangarSurfaceView's cabinet orientation lock), so in practice this moves the attract
     * demo and a finished run's leftovers. The crystal is left alone — it only exists inside a
     * reckoning, which is a run.
     */
    fun rescale(sx: Float, sy: Float) {
        ship.x *= sx; ship.y *= sy
        for (r in _rocks) { r.x *= sx; r.y *= sy }
        for (b in _bullets) { b.x *= sx; b.y *= sy }
        for (d in _debris) { d.x *= sx; d.y *= sy }
    }

    /**
     * End the run without killing the ship — the win. [killShip] is the other way out,
     * and it is the one that leaves a wreck.
     */
    fun endRun() { over = true }

    /** Place a rock outright — the authored opening seeds its field this way. */
    fun placeRock(size: RockSize, x: Float, y: Float, vx: Float, vy: Float) {
        _rocks.add(
            CabinetRock(
                size, x, y, vx, vy, CabinetRock.generateShape(rng),
                rng.nextFloat() * 2f * PI.toFloat(), (rng.nextFloat() - 0.5f) * 1.0f
            )
        )
    }

        /**
     * Remove every rock outright.
     *
     * A hard reset with no visible exit, so it is only
     * legitimate where nothing was on screen to begin with (seeding) or where everything
     * has already been seen to leave (the frame after the last rock is absorbed).
     */
    fun clearField() { _rocks.clear() }

    /**
     * Clear the field by breaking every remaining rock into its own edges.
     *
     * How the reckoning entrance clears the field. The old implosion *ate* it — the
     * rocks fly into the crystal and become its body, which is their visible exit. But the
     * entrance carries a timeout for a rock the pull cannot reach in time, and a rock that
     * simply stops existing there is the no-vanishing rule broken in the one path built to
     * keep the entrance from hanging.
     *
     * Shattering is the exit every destroyed rock already gets, so nothing new is invented
     * for the corner case — the crystal's formation tears apart what it could not swallow.
     */
    fun shatterField() {
        for (r in _rocks) shatterRock(r)
        _rocks.clear()
    }

    /**
     * The ending. The crystal comes apart into its own edges — the machine's own grammar,
     * applied to the thing that has been chasing you all game — subdivided, thrown hard
     * and left to burn for far longer than a rock's fragments do.
     *
     * The piece count is a FRAME-TIME decision, not an aesthetic one: `drawDebris` costs
     * one `drawPath` per piece per wrap image against a menu already measured at ~1000
     * calls, and frame time is a standing complaint. Recursive shattering was rejected and
     * the cap is what stops it returning by increment.
     */
    fun shatterCrystal() {
        val c = crystal ?: return
        val r = c.radius(m)
        val n = CabinetCrystal.SHELL_POINTS
        val sub = CRYSTAL_DEBRIS_SUBDIVISIONS
        var i = 0
        for (v in 0 until n) {
            val ax = CabinetCrystal.OUTLINE[v * 2] * r
            val ay = CabinetCrystal.OUTLINE[v * 2 + 1] * r
            val w = (v + 1) % n
            val bx = CabinetCrystal.OUTLINE[w * 2] * r
            val by = CabinetCrystal.OUTLINE[w * 2 + 1] * r
            // Subdivide each facet, so the shell breaks into shards rather than into the
            // fourteen sticks it was drawn from.
            for (s in 0 until sub) {
                val f = s.toFloat() / sub
                crystalPts[i++] = c.x + ax + (bx - ax) * f
                crystalPts[i++] = c.y + ay + (by - ay) * f
            }
        }
        _debris.addAll(
            CabinetDebris.shatter(
                c.x, c.y, crystalPts, i, rng, m, 0f, 0f,
                life = CRYSTAL_DEBRIS_LIFE, driftScale = CRYSTAL_DEBRIS_DRIFT
            )
        )
        // The wreck IS the exit, so the body goes now rather than lingering under it.
        crystal = null
    }

    /**
     * Put the ship somewhere. Used by the reckoning's entrance to shove the player clear
     * of a crystal forming on top of them, and by tests that need a known position.
     */
    fun placeShip(x: Float, y: Float) { ship.x = x; ship.y = y }

    // --- test seams -------------------------------------------------------------

    internal fun destroyForTest(rock: CabinetRock): List<CabinetRock> = destroy(rock)
    internal fun addScoreForTest(points: Int) = addScore(points)
    internal fun forceWave(n: Int) { _rocks.clear(); spawnWave(n) }

    /** Raw field seams for the seam-crossing regression tests — bypass wave bookkeeping. */
    internal fun clearRocksForTest() { _rocks.clear() }

    internal fun placeRockForTest(size: RockSize, x: Float, y: Float) {
        _rocks.add(CabinetRock(size, x, y, 0f, 0f, CabinetRock.generateShape(rng), 0f, 0f))
    }

    internal fun placeBulletForTest(x: Float, y: Float) {
        _bullets.add(CabinetBullet(x, y, 0f, 0f, m.bulletLifetime))
    }

    /** Exercises the retry-cap/fallback path directly with an arbitrary radius. */
    internal fun spawnRockAwayFromShipForTest(safeRadius: Float): CabinetRock =
        spawnRockAwayFromShip(wave, safeRadius)

    companion object {
        /** Three digits. It sticks rather than rolling over — see the design doc. */
        const val SCORE_CAP = 999

        /** Wave 11 unbounded would be 77 simultaneous destructibles. */
        const val MAX_LARGE_PER_WAVE = 11

        /**
         * Four shots on screen, hard — the original's own limit.
         *
         * This is the ceiling; m.fireInterval is the floor. Together they are the whole
         * fire-rate model, which is why a device pass reversed a design decision's "no bullet
         * cap, pure cadence".
         */
        const val MAX_BULLETS = 4

        /** How long WAVE N stays up. */
        const val WAVE_BANNER_SECONDS = 1.5f

        /**
         * Minimum toroidal distance a spawned rock must clear from the ship, as a
         * fraction of minEdge — the actual guarantee against a rock materialising on
         * the player (an edge position alone is not one; see [spawnRockAwayFromShip]).
         * 0.35 is comfortably clearable: at the 1080px reference that's 378px, against
         * a maximum toroidal distance on a 1080x2400 field of about 1316px.
         */
        const val SPAWN_SAFE_RADIUS_FRAC = 0.35f

        /**
         * Retry cap for [spawnRockAwayFromShip]. Bounded so a future change to the
         * field's aspect ratio or [SPAWN_SAFE_RADIUS_FRAC] cannot hang the game — past
         * this many tries it falls back to the best candidate found instead of looping
         * forever.
         */
        const val SPAWN_MAX_ATTEMPTS = 20

        /** 14 facets × 5 = 70 pieces. Capped for frame time — see [shatterCrystal]. */
        const val CRYSTAL_DEBRIS_SUBDIVISIONS = 5

        /** 3.0s against a rock's 0.6s. This is the last thing the player watches. */
        const val CRYSTAL_DEBRIS_LIFE = 3.0f

        /** ×7 a rock's drift, so the pieces cross the field rather than sitting on it. */
        const val CRYSTAL_DEBRIS_DRIFT = 7f
    }
}
