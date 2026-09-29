package com.astroloop.game.hangar

import com.astroloop.game.core.LayoutRect

/**
 * The hangar's grid geometry, in one place.
 *
 * The pilot grid used to exist twice — `BarPageRenderer` drew it and `HangarRenderer` re-derived
 * it to hit-test it, with a comment asking the two to stay in sync. The landscape panel would
 * have been a third copy, and a three-copy pilot grid is a bug this area has already had.
 *
 * All rects are in the space their `content`/`bounds` argument is in: room-local for the in-room
 * grid, screen space for a panel. This object never decides which — it lays out a grid inside
 * bounds it is handed.
 */
object GridGeometry {

    const val PILOT_COLS = 4
    const val PILOT_ROWS = 3
    const val PILOT_GAP = 8f
    const val PILOT_PADDING = 12f

    const val STORE_COLS = 3
    const val STORE_ROWS = 3
    const val STORE_GAP = 10f
    const val STORE_PADDING = 16f

    /**
     * The pilot grid's outer bounds, content-anchored.
     *
     * `content` is SCREEN space, so its X coordinates cross into room space first or the grid
     * lands a room-offset right of its own room. Vertical stays as-is: rooms tile horizontally.
     */
    fun pilotGridBounds(content: LayoutRect, roomWidth: Float, screenWidth: Float): LayoutRect =
        LayoutRect(
            HangarMetrics.contentXInRoom(content.left, roomWidth, screenWidth) + PILOT_PADDING,
            content.top + 70f,
            HangarMetrics.contentXInRoom(content.right, roomWidth, screenWidth) - PILOT_PADDING,
            content.top + content.height * 0.52f
        )

    /** (width, height) of one pilot card inside [bounds]. */
    fun pilotCardSize(bounds: LayoutRect): Pair<Float, Float> = Pair(
        (bounds.width - PILOT_GAP * (PILOT_COLS - 1)) / PILOT_COLS,
        (bounds.height - PILOT_GAP * (PILOT_ROWS - 1)) / PILOT_ROWS
    )

    fun pilotCardRects(bounds: LayoutRect, count: Int): List<LayoutRect> {
        val (cardW, cardH) = pilotCardSize(bounds)
        val out = ArrayList<LayoutRect>(count)
        for (i in 0 until count) {
            val col = i % PILOT_COLS
            val row = i / PILOT_COLS
            val x = bounds.left + col * (cardW + PILOT_GAP)
            val y = bounds.top + row * (cardH + PILOT_GAP)
            out.add(LayoutRect(x, y, x + cardW, y + cardH))
        }
        return out
    }

    /** The inverse of [pilotCardRects]: null in the gaps and outside the grid, as it always was. */
    fun pilotIndexAt(bounds: LayoutRect, roomX: Float, y: Float, count: Int): Int? {
        val (cardW, cardH) = pilotCardSize(bounds)
        if (roomX < bounds.left || roomX > bounds.right || y < bounds.top || y > bounds.bottom) return null
        val col = ((roomX - bounds.left) / (cardW + PILOT_GAP)).toInt()
        val row = ((y - bounds.top) / (cardH + PILOT_GAP)).toInt()
        if (col >= PILOT_COLS || row >= PILOT_ROWS) return null
        val withinCardX = (roomX - bounds.left) - col * (cardW + PILOT_GAP)
        val withinCardY = (y - bounds.top) - row * (cardH + PILOT_GAP)
        if (withinCardX > cardW || withinCardY > cardH) return null
        val index = row * PILOT_COLS + col
        if (index >= count) return null
        return index
    }

    /** Square store tile, bound by the narrower of the content width and the space above the walkway. */
    fun storeTileSize(content: LayoutRect, walkwayY: Float): Float = minOf(
        (content.width - STORE_PADDING * 2f - STORE_GAP * (STORE_COLS - 1)) / STORE_COLS,
        ((walkwayY - 20f) - 70f - STORE_GAP * (STORE_ROWS - 1)) / STORE_ROWS
    )

    /**
     * The store grid's outer bounds — square tiles, centred horizontally in the content column
     * and vertically between y=70 and the walkway, exactly as the store page has always placed it.
     */
    fun storeGridBounds(content: LayoutRect, contentLeftInRoom: Float, walkwayY: Float): LayoutRect {
        val tile = storeTileSize(content, walkwayY)
        val w = STORE_COLS * tile + STORE_GAP * (STORE_COLS - 1)
        val h = STORE_ROWS * tile + STORE_GAP * (STORE_ROWS - 1)
        val left = contentLeftInRoom + (content.width - w) / 2f
        val gridTop = 70f
        val gridBottom = walkwayY - 20f
        val top = gridTop + (gridBottom - gridTop - h) / 2f
        return LayoutRect(left, top, left + w, top + h)
    }

    fun storeTileRects(bounds: LayoutRect, count: Int): List<LayoutRect> {
        val tile = (bounds.width - STORE_GAP * (STORE_COLS - 1)) / STORE_COLS
        val out = ArrayList<LayoutRect>(count)
        for (i in 0 until count) {
            val col = i % STORE_COLS
            val row = i / STORE_COLS
            val x = bounds.left + col * (tile + STORE_GAP)
            val y = bounds.top + row * (tile + STORE_GAP)
            out.add(LayoutRect(x, y, x + tile, y + tile))
        }
        return out
    }

    /** The slot machine / BELT RUN cabinet: the grid's width, hanging below the walkway. */
    fun machineFrame(gridBounds: LayoutRect, walkwayY: Float, screenHeight: Float): LayoutRect =
        LayoutRect(gridBounds.left, walkwayY + 15f, gridBounds.right, screenHeight * 0.92f)
}
