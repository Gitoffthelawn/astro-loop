package com.astroloop.game.input

import kotlin.math.abs

/**
 * Picks the next focus target for a direction, from rects alone.
 *
 * The rule: only consider targets whose centre lies in the pressed half-plane, then rank by
 * distance along the axis of travel plus a penalty for drifting off it. The penalty is what
 * stops DOWN landing on something that is mostly sideways, which is the failure everyone
 * notices immediately on a grid.
 *
 * Disabled targets are still reachable — a control the player cannot use yet should still be
 * landable so they can see it is there. Skipping them would make the launch pad invisible to a
 * controller until the moment it became usable.
 *
 * No wrapping. Running off the end of a row does nothing, which is what every console UI does
 * and what stops a held direction cycling forever.
 */
object FocusNavigator {

    /** Drift off the axis of travel costs this much per pixel, relative to travel along it. */
    private const val CROSS_AXIS_PENALTY = 2f

    fun next(targets: List<FocusTarget>, fromId: String?, direction: Direction): String? {
        if (targets.isEmpty()) return null

        val from = fromId?.let { id -> targets.firstOrNull { it.id == id } }
            ?: return firstInReadingOrder(targets)

        val fromX = from.rect.centerX()
        val fromY = from.rect.centerY()

        var bestId: String? = null
        var bestCost = Float.MAX_VALUE

        for (candidate in targets) {
            if (candidate.id == from.id) continue

            val dx = candidate.rect.centerX() - fromX
            val dy = candidate.rect.centerY() - fromY

            val along: Float
            val across: Float
            when (direction) {
                Direction.LEFT -> { along = -dx; across = abs(dy) }
                Direction.RIGHT -> { along = dx; across = abs(dy) }
                Direction.UP -> { along = -dy; across = abs(dx) }
                Direction.DOWN -> { along = dy; across = abs(dx) }
            }

            // Must actually be in the pressed direction.
            if (along <= 0f) continue

            val cost = along + across * CROSS_AXIS_PENALTY
            if (cost < bestCost) {
                bestCost = cost
                bestId = candidate.id
            }
        }

        return bestId
    }

    private fun firstInReadingOrder(targets: List<FocusTarget>): String =
        targets.minWithOrNull(
            compareBy({ it.rect.top }, { it.rect.left })
        )!!.id
}
