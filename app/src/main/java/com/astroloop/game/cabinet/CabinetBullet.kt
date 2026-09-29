package com.astroloop.game.cabinet

/**
 * A shot. Dies on [life] rather than on distance.
 *
 * The player's shots wrap like everything else on the field. **The crystal's do not**
 * ([hostile]) — the design: it bounds their lifetime to one playfield crossing,
 * which is what makes fairness rule 3's density figure deterministic, and it reads
 * in-fiction as the crystal not obeying the cabinet's rules.
 *
 * This is the ONE deliberate non-toroidal motion path in `cabinet/`. Everything else in
 * the package goes through [CabinetMetrics.distance] / [CabinetMetrics.wrappedDelta], and
 * a constraint sweep that finds this should read this comment rather than "fixing" it.
 * Note that *collision* against a hostile bullet is still toroidal: the ship is drawn at
 * every wrapped position, so toroidal distance is what matches the picture.
 */
class CabinetBullet(
    var x: Float, var y: Float,
    val vx: Float, val vy: Float,
    var life: Float,
    val hostile: Boolean = false
) {
    /**
     * True once a [hostile] bullet has left the field and must be culled.
     *
     * Culling on exit rather than on a timer is what makes "one playfield crossing"
     * exact instead of direction-dependent — a bullet heading for a corner has further
     * to travel than one heading for an edge.
     */
    var escaped: Boolean = false
        private set

    fun update(dt: Float, m: CabinetMetrics) {
        x += vx * dt
        y += vy * dt
        life -= dt
        if (hostile) {
            if (x < 0f || x >= m.width || y < 0f || y >= m.height) escaped = true
            return
        }
        if (x < 0f) { x += m.width }
        if (x >= m.width) { x -= m.width }
        if (y < 0f) { y += m.height }
        if (y >= m.height) { y -= m.height }
    }
}
