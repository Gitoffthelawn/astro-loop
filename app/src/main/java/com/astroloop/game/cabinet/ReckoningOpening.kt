package com.astroloop.game.cabinet

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How the reckoning arrives: a stretch of ordinary BELT RUN, then the field crystallising
 * into the thing you fight.
 *
 * **Authored rather than played.** The original design contradicted itself
 * here — a design decision said the launch dissolves the hangar *into the cabinet*, while the spec said
 * *"You are playing a normal game when it arrives"* with rocks on the field to
 * crystallise. Both hold only if there is a stretch of BELT RUN first, and its length was
 * never specified.
 *
 * A scripted field and a fixed length settles it, and buys three things a live run cannot:
 * the entrance always has the same number of bodies, so the ending's quality does not vary
 * with how well the player was flying; it is deterministic; and **it does not depend on
 * wave pacing**, which is the one figure this project has repeatedly refused to guess.
 *
 * The player cannot tell a scripted wave from a random one, so the spec's *"normal game"*
 * survives in the only sense that matters.
 */
class ReckoningOpening(
    private val m: CabinetMetrics,
    /**
     * Handed to the [CabinetCrystal] this builds — see [CabinetCrystal.hasBody]. Defaults to
     * true so a test that only cares about the entrance's choreography need not know about
     * replays at all.
     */
    private val crystalHasBody: Boolean = true
) {

    enum class Stage {
        /** Ordinary BELT RUN against the authored field. */
        PLAY,

        /**
         * The field freezes and the crystal flies in from the top strip.
         *
         * Was CRYSTALLISING, where the rocks imploded into the forming crystal and were
         * eaten. A device pass replaced that: "instead of the asteroids in the first part
         * of the fight going to the middle, please just freeze the asteroids, as the
         * crystal does its opening lines and flies to the middle."
         */
        ARRIVAL,

        /** The crystal has a body. The director takes over. */
        FIGHT
    }

    var stage: Stage = Stage.PLAY
        private set

    var elapsed: Float = 0f
        private set

    /**
     * How far the crystal has travelled in, 0 at the top strip and 1 at the centre.
     *
     * Eased, so it leaves slowly and arrives with weight rather than sliding at a constant
     * rate. The renderer draws the crystal along this; the sim has no crystal at all until
     * the moment it lands, which is what keeps the health bar from showing a readout for a
     * thing that is not there yet.
     */
    val arrival: Float
        get() = if (stage != Stage.ARRIVAL) 0f else {
            val t = (elapsed / ARRIVAL_SECONDS).coerceIn(0f, 1f)
            t * t * (3f - 2f * t)
        }

    /** Where the crystal is right now, mid-flight. Only meaningful during [Stage.ARRIVAL]. */
    fun arrivalX(): Float = m.width / 2f
    fun arrivalY(): Float = ENTRY_Y_FRAC * m.minEdge +
        (m.height / 2f - ENTRY_Y_FRAC * m.minEdge) * arrival

    /** Seeds the authored field. Call once, on entry. */
    fun begin(sim: CabinetSim) {
        stage = Stage.PLAY
        elapsed = 0f
        sim.clearField()
        // Waves off from the first frame: clearing the seeded field would otherwise mint
        // wave 2 and the opening would never reach its own ending.
        sim.wavesSuspended = true
        sim.scores = false
        val r = SEED_RING_FRAC * m.minEdge
        for (i in 0 until SEED_ROCKS) {
            val a = (i.toFloat() / SEED_ROCKS) * TWO_PI
            // A ring around the ship's start, drifting tangentially. Every position
            // clears CabinetSim.SPAWN_SAFE_RADIUS_FRAC by construction rather than by
            // retry, which is what keeps the layout identical every time.
            val x = wrapX(m.width / 2f + cos(a) * r)
            val y = wrapY(m.height / 2f + sin(a) * r)
            val sp = SEED_DRIFT_FRAC * m.minEdge
            sim.placeRock(RockSize.LARGE, x, y, -sin(a) * sp, cos(a) * sp)
        }
    }

    /** @return true on the frame the fight begins. */
    fun update(dt: Float, sim: CabinetSim): Boolean {
        elapsed += dt
        when (stage) {
            Stage.PLAY -> {
                if (elapsed >= OPENING_SECONDS) {
                    stage = Stage.ARRIVAL
                    elapsed = 0f
                    sim.rocksFrozen = true
                }
            }
            Stage.ARRIVAL -> {
                if (elapsed >= ARRIVAL_SECONDS) {
                    // It lands. Everything still on the field dies with it — shatterField
                    // gives every rock its own edges to come apart into, which is what the
                    // no-vanishing rule asks for — nothing leaves the screen unseen — and
                    // what the old implosion was doing by another route.
                    sim.shatterField()
                    sim.rocksFrozen = false
                    formCrystal(sim)
                    stage = Stage.FIGHT
                    elapsed = 0f
                    return true
                }
            }
            Stage.FIGHT -> Unit
        }
        return false
    }

    /**
     * Give the crystal its body — and get the player out from under it.
     *
     * **The crystal forms at the field's centre, which is exactly where
     * [CabinetShip.reset] parks the ship.** So a debug jump straight to a phase would kill
     * the player on frame 1, and a player who happens to be sitting at centre when the
     * field is finally eaten dies to a body materialising on top of them with no warning
     * and no counterplay. That is the same defect the polish pass fixed for wave spawns,
     * arriving by a different door.
     *
     * A nudge, not a teleport: the ship is pushed straight out along the direction it
     * already lies in, so it is displaced rather than relocated, and a player who was
     * circling keeps their heading and their speed.
     *
     * The field clear is [CabinetSim.shatterField], not [CabinetSim.clearField]: on the
     * normal path (`left == 0`) the field is already empty and this is a no-op, but on the
     * timeout path a straggler the pull could not reach in time can still be sitting on
     * the field, and simply erasing it would vanish a rock the player was watching.
     * Shattering gives it the same visible exit every other destroyed rock gets.
     */
    private fun formCrystal(sim: CabinetSim) {
        sim.placeCrystal(CabinetCrystal(m.width / 2f, m.height / 2f, CRYSTAL_HP, crystalHasBody))
        // Idempotent, and kept as the second gate: a player who drifted back to the centre
        // during the implosion is moved again here. Someone already clear is not touched.
        shoveClear(sim)
    }

    /**
     * Move the player off the crystal's seat, if they are on it.
     *
     * **One caller now: [formCrystal].** It used to run twice — once as the implosion
     * started and once as the crystal formed — but a design decision replaced the implosion with
     * the flight in, so there is no earlier moment to shove at. The idempotence the second
     * call needed is kept anyway: it is a shove to a minimum clearance, not a displacement,
     * so calling it on a player who is already clear does nothing.
     */
    private fun shoveClear(sim: CabinetSim) {
        val cx = m.width / 2f
        val cy = m.height / 2f
        val clear = CabinetCrystal.RADIUS_FRAC * m.minEdge + m.shipRadius +
            CLEAR_MARGIN_FRAC * m.minEdge
        val dx = m.wrappedDelta(sim.ship.x, cx, m.width)
        val dy = m.wrappedDelta(sim.ship.y, cy, m.height)
        val d = hypot(dx, dy)
        if (d >= clear) return
        // Dead centre has no outward direction; pick one rather than divide by zero.
        val ux = if (d < 0.001f) 0f else dx / d
        val uy = if (d < 0.001f) 1f else dy / d
        // Out to ENGAGEMENT RANGE, not to the shell's surface. Clearing the body is not the
        // same as being able to survive: pressed against the crystal at 186.8px, PULSE's
        // first ring — emitted from the surface at 97.2px — arrives in 0.222s, and no
        // amount of skill builds enough lateral speed in that time. Engagement range gives
        // 0.978s, and it is the radius the whole fairness contract is authored against, so
        // it is the one place on the field the patterns are *proven* passable.
        //
        // The THRESHOLD above is deliberately not widened to match: only a player who would
        // otherwise be inside the crystal is moved at all. Someone flying legitimately at
        // 300px keeps their position.
        val out = ReckoningFairness.engagementRange(m)
        sim.placeShip(wrapX(cx + ux * out), wrapY(cy + uy * out))
    }

    /**
     * Jump straight to a live fight with no opening — what `RECKONING_PHASE_1..5` need.
     *
     * Shares [formCrystal] with the live entrance rather than duplicating it: same shove,
     * same reason. This runs right after [CabinetShell.startReckoning] calls `sim.start()`,
     * which parks the ship at the field centre — placing the crystal there without the
     * shove would make every debug phase jump an instant death.
     */
    fun skipToFight(sim: CabinetSim) {
        sim.wavesSuspended = true
        sim.scores = false
        sim.rocksFrozen = false
        sim.shatterField()
        formCrystal(sim)
        stage = Stage.FIGHT
        elapsed = 0f
    }

    private fun wrapX(v: Float): Float =
        ((v % m.width) + m.width) % m.width

    private fun wrapY(v: Float): Float =
        ((v % m.height) + m.height) % m.height

    companion object {
        private const val TWO_PI = (2.0 * PI).toFloat()

        /**
         * How long the player flies an ordinary game before it turns.
         *
         * Long enough to settle into the machine they know, short enough that the ending
         * is not asking them to grind. NOT derived from wave pacing, deliberately — see
         * the class comment.
         */
        const val OPENING_SECONDS = 12f

        /** Eight large rocks: enough to read as a field, few enough to be eaten quickly. */
        const val SEED_ROCKS = 8

        /** 0.40 × minEdge from centre — clears SPAWN_SAFE_RADIUS_FRAC 0.35 by construction. */
        const val SEED_RING_FRAC = 0.40f

        /** Tangential drift, so the ring turns rather than collapsing or dispersing. */
        const val SEED_DRIFT_FRAC = 0.05f


        /** Hard ceiling on the entrance. See [update] — the pull speed is not a guarantee. */
        /**
         * How long the crystal takes to cross from the strip to the centre.
         *
         * Long enough to carry the last two opening beats, which is the point of flying it
         * at all — the entrance used to be 3.5s of rocks converging and the crystal simply
         * appeared at the end of it.
         */
        const val ARRIVAL_SECONDS = 4.5f

        /** Where it starts, as a fraction of minEdge — the height of the strip's readout. */
        const val ENTRY_Y_FRAC = 0.028f


        /** Breathing room beyond the crystal's surface when it shoves the player clear. */
        const val CLEAR_MARGIN_FRAC = 0.06f

        /**
         * The crystal's health, and therefore the length of the fight.
         *
         * 200 -> 300 when a design decision needed five HP bands to fit above it; back to 180 at
         * a device pass, where the owner found the fight too hard and asked for less. With
         * a design decision this is the ONLY dial that sets the fight's length — each pattern is
         * a fifth of it, so 180 gives 36 HP a pattern.
         */
        const val CRYSTAL_HP = 180
    }
}
