package com.astroloop.game.hangar

import com.astroloop.game.core.LayoutRect
import kotlin.math.ceil

/**
 * How a panel arranges a fixed number of fixed-size items.
 *
 * The owner's rule, and the only concession landscape gets: **a grid is an arrangement, not an
 * object**. Cards and tiles keep the size they have in portrait; when the portrait shape will not
 * fit a short screen, rows come off and columns go on. Nothing is ever scaled to fit — scaling is
 * what the page-scale attempt did, and it is what the golden rule forbids.
 *
 * A reduction that would overflow the available WIDTH is refused, on the grounds that a grid
 * running off the side of the screen is worse than one clipped at the bottom. No profile in the
 * spec reaches that branch.
 */
object PanelGrid {

    data class Arrangement(val cols: Int, val rows: Int, val width: Float, val height: Float)

    private fun build(count: Int, cols: Int, itemW: Float, itemH: Float, gap: Float): Arrangement {
        val c = cols.coerceAtLeast(1)
        val rows = ceil(count.toFloat() / c).toInt().coerceAtLeast(1)
        return Arrangement(
            cols = c,
            rows = rows,
            width = c * itemW + (c - 1) * gap,
            height = rows * itemH + (rows - 1) * gap
        )
    }

    /**
     * [availableWidth] and [availableHeight] are the space for the GRID itself — the caller has
     * already taken the panel's padding off, so this object owns no padding constants.
     */
    fun arrange(
        count: Int, itemW: Float, itemH: Float, gap: Float, preferredCols: Int,
        availableWidth: Float, availableHeight: Float
    ): Arrangement {
        var best = build(count, preferredCols, itemW, itemH, gap)
        while (best.height > availableHeight && best.rows > 1) {
            val rows = best.rows - 1
            val next = build(count, ceil(count.toFloat() / rows).toInt(), itemW, itemH, gap)
            if (next.width > availableWidth) break
            // Termination guarantee: this is what actually keeps the loop finite, not the algebra
            // of nested ceils above. It forces best.rows to strictly decrease on every accepted
            // step, floored at 1 by build()'s coerceAtLeast(1) — so the loop can run at most
            // (initial rows - 1) times before this fires or the rows > 1 guard above stops it.
            if (next.rows >= best.rows) break
            best = next
        }
        return best
    }

    /** Laid out from the top-left, with a short final row centred under the full ones. */
    fun rects(
        a: Arrangement, count: Int, originX: Float, originY: Float,
        itemW: Float, itemH: Float, gap: Float
    ): List<LayoutRect> {
        val out = ArrayList<LayoutRect>(count)
        for (i in 0 until count) {
            val row = i / a.cols
            val col = i % a.cols
            val inRow = minOf(a.cols, count - row * a.cols)
            val rowWidth = inRow * itemW + (inRow - 1) * gap
            val rowLeft = originX + (a.width - rowWidth) / 2f
            val x = rowLeft + col * (itemW + gap)
            val y = originY + row * (itemH + gap)
            out.add(LayoutRect(x, y, x + itemW, y + itemH))
        }
        return out
    }

    /**
     * Hit test by containment rather than by arithmetic. The grid's gaps are dead space and a
     * point in one belongs to no item — the same contract the in-room grid has always had, got
     * for free by testing the rects that were actually drawn.
     */
    fun indexAt(rects: List<LayoutRect>, x: Float, y: Float): Int? {
        for ((i, r) in rects.withIndex()) if (r.contains(x, y)) return i
        return null
    }
}
