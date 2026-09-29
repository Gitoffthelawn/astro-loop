package com.astroloop.game.cabinet

import kotlin.math.min

/**
 * Every constant in BELT RUN as a fraction of the playfield's short edge.
 *
 * The playfield fills the device screen, so a pixel constant would make the same
 * pattern a different fight on every handset. Expressing everything against
 * [minEdge] makes the cabinet device-independent by construction rather than by
 * device testing.
 *
 * Reference figures below are for a 1080px short edge, which is the design device.
 */
class CabinetMetrics(width: Float, height: Float) {

    /**
     * The playfield, in screen pixels. Mutable only through [resize]: the cabinet can be open
     * while the device rotates, and every class in a session shares this one instance, so
     * changing it here is what makes the whole cabinet follow the new screen at once.
     */
    var width: Float = width
        private set
    var height: Float = height
        private set

    /** Unchanged by a rotation — that swaps [width] and [height], not the short edge. */
    val minEdge: Float get() = min(width, height)

    /** The screen changed under an open cabinet. See [CabinetShell.resize]. */
    fun resize(newWidth: Float, newHeight: Float) {
        width = newWidth
        height = newHeight
    }

    /** 302 px/s at reference — deliberately matched to GameConfig.SHIP_BASE_SPEED (300f). */
    val topSpeed: Float get() = TOP_SPEED_FRAC * minEdge

    /**
     * The joystick's dead zone and full-deflection radius, in playfield pixels.
     *
     * Derived from the main game's own `JOYSTICK_DEAD_ZONE 20` and
     * `JOYSTICK_MAX_RADIUS 120` against its `DESIGN_WIDTH 960`, so the stick is the
     * same physical size on the glass in both games: 20/960 and 120/960.
     */
    val stickDeadZone: Float get() = STICK_DEAD_ZONE_FRAC * minEdge
    val stickRadius: Float get() = STICK_RADIUS_FRAC * minEdge

    /**
     * 75.6 px/s^2 — roughly a fifth of the main game's 400. This is the glide, and it is
     * the single constant that defines how the cabinet feels: 4.0s and 0.56 of the short
     * edge to coast to rest, against the main game's 0.75s and 112px.
     */
    val drag: Float get() = DRAG_FRAC * minEdge

    val shipRadius: Float get() = SHIP_RADIUS_FRAC * minEdge
    val bulletSpeed: Float get() = BULLET_SPEED_FRAC * minEdge
    val bulletLifetime: Float get() = BULLET_LIFETIME
    val fireInterval: Float get() = FIRE_INTERVAL
    val rockBaseSpeed: Float get() = ROCK_SPEED_FRAC * minEdge

    /** 5.4px at reference — the crystal's shot, visible on a vector tube. */
    val crystalBulletRadius: Float get() = CRYSTAL_BULLET_FRAC * minEdge

    /**
     * What a crystal bullet tests against the ship: HALF the rock hitbox.
     *
     * `resolveShipCollision` uses a circle of 1.0 × shipRadius, but `CabinetShip.HULL`
     * draws a long thin triangle scaled by that same radius — the circle is 11% wider
     * than the drawn hull at its widest point and about twice as wide at mid-hull. Rocks
     * are 59.4px across and slow, so the discrepancy never decides anything in free play.
     * Small fast bullets are where it decides everything, and a bullet that visibly
     * clears the silhouette must not kill.
     */
    val crystalBulletHitRadius: Float get() = CRYSTAL_HIT_RADIUS_FRAC * minEdge

    /**
     * The shortest signed separation from [b] to [a] on an axis of length [span].
     *
     * The playfield is a TORUS. Every distance in the sim has to be measured this way:
     * a naive delta says opposite edges are a full screen apart when they are in fact
     * adjacent, which would let bullets pass through rocks at the seam.
     */
    fun wrappedDelta(a: Float, b: Float, span: Float): Float {
        var d = a - b
        val half = span / 2f
        if (d > half) d -= span
        if (d < -half) d += span
        return d
    }

    /** Shortest distance between two points on the wrapping field. */
    fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float = kotlin.math.hypot(
        wrappedDelta(ax, bx, width),
        wrappedDelta(ay, by, height)
    )

    fun rockRadius(size: RockSize): Float = when (size) {
        RockSize.LARGE -> ROCK_LARGE_FRAC * minEdge
        RockSize.MEDIUM -> ROCK_MEDIUM_FRAC * minEdge
        RockSize.SMALL -> ROCK_SMALL_FRAC * minEdge
    }

    companion object {
        const val TOP_SPEED_FRAC = 0.28f
        const val DRAG_FRAC = 0.070f
        const val SHIP_RADIUS_FRAC = 0.023f
        const val BULLET_SPEED_FRAC = 0.55f
        const val BULLET_LIFETIME = 1.2f
        const val FIRE_INTERVAL = 1f / 6f
        const val ROCK_SPEED_FRAC = 0.07f
        const val ROCK_LARGE_FRAC = 0.055f
        const val ROCK_MEDIUM_FRAC = 0.034f
        const val ROCK_SMALL_FRAC = 0.020f

        const val CRYSTAL_BULLET_FRAC = 0.005f

        /** Derived from the ship, not restated, so the two cannot drift apart. */
        const val CRYSTAL_HIT_RADIUS_FRAC = SHIP_RADIUS_FRAC * 0.5f

        /** Matches the main game's lerpAngle rate so the two ships turn alike. */
        const val TURN_RATE = 8f

        /**
         * How fast velocity converges on the input vector, per second.
         *
         * The main game's effective rate is SHIP_ACCELERATION 800 / SHIP_BASE_SPEED 300
         * = 2.67. This is deliberately slower, and it is the single number that carries
         * the owner's "more momentum": 0.53 of top speed at 0.5s, 0.99 at 3s.
         */
        const val VELOCITY_LERP = 1.5f

        const val STICK_DEAD_ZONE_FRAC = 0.021f
        const val STICK_RADIUS_FRAC = 0.125f
    }
}
