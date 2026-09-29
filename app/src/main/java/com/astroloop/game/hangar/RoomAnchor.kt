package com.astroloop.game.hangar

/**
 * Where each hangar page sits on the screen, and how far apart the pages tile.
 *
 * In portrait this is the transform that shipped, written out: every page is as wide as the room,
 * the three-room block is centred (which is zero below the sw600 gate), and pages tile one room
 * apart. Nothing here has a portrait special case to get wrong — the portrait branch IS the old
 * arithmetic.
 *
 * In landscape the room keeps its PORTRAIT width and leans toward the shipyard: crew hugs the
 * right edge of its screen, shop hugs the left, the launchpad stays full-screen. The stride stays
 * one page per screen, so neighbouring rooms remain off-screen with starfield either side — the
 * sw600 adjacent-rooms behaviour is deliberately portrait-only, because tiling neighbours into
 * view would put the crew and shop buttons on top of a neighbour's room.
 *
 * Why `anchorX` is branched rather than written as one expression: above the gate the portrait
 * room is already the content column and the block is already centred, so the obvious
 * `screenWidth - pageWidth` would shove a tablet's PORTRAIT rooms to the right edge.
 */
object RoomAnchor {

    /** Square counts as portrait — the same tie-break as `DesignSpace.isLandscape`. */
    fun isLandscape(screenWidth: Float, screenHeight: Float): Boolean = screenWidth > screenHeight

    /**
     * The drawn width of one page. The launchpad is the exception in landscape: it is not
     * anchored and not narrowed, because `LaunchContinuityTest` pins the launch point to the
     * full-screen centre at zero tolerance and combat opens the ship there.
     */
    fun pageWidth(page: Int, screenWidth: Float, roomWidth: Float, landscape: Boolean): Float =
        if (landscape && page == 1) screenWidth
        else HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth)

    /** How far apart pages tile in world space. One page per screen in landscape. */
    fun stride(screenWidth: Float, roomWidth: Float, landscape: Boolean): Float =
        if (landscape) screenWidth
        else HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth)

    /** Where a page sits inside its own screen slot. */
    fun anchorX(page: Int, screenWidth: Float, roomWidth: Float, landscape: Boolean): Float {
        val pw = pageWidth(page, screenWidth, roomWidth, landscape)
        if (!landscape) return (screenWidth - pw) / 2f
        return when (page) {
            0 -> screenWidth - pw   // crew hugs right
            2 -> 0f                 // shop hugs left
            else -> (screenWidth - pw) / 2f
        }
    }

    /**
     * World X of the left screen edge — subtract it from a world X to get a screen X.
     *
     * Unlike the `HangarMetrics.viewportX` this replaces, it carries NO centring term: the anchor
     * is per page now, so it belongs in [pageOriginX] and in the walker's world target rather
     * than folded in here where every page would share one value.
     */
    fun viewportX(
        currentPage: Int, pageScrollOffset: Float,
        screenWidth: Float, roomWidth: Float, landscape: Boolean
    ): Float = currentPage * stride(screenWidth, roomWidth, landscape) + pageScrollOffset

    /** Screen X of page [page]'s left edge, for this frame. */
    fun pageOriginX(
        page: Int, currentPage: Int, pageScrollOffset: Float,
        screenWidth: Float, roomWidth: Float, landscape: Boolean
    ): Float = page * stride(screenWidth, roomWidth, landscape) -
        viewportX(currentPage, pageScrollOffset, screenWidth, roomWidth, landscape) +
        anchorX(page, screenWidth, roomWidth, landscape)

    /**
     * Screen X → the CURRENT room's local X. The single inverse: page renderers draw inside their
     * page's translate and publish their rects in that space, so every hit test against one of
     * those rects crosses back through here. If the anchor enters the draw and not this, every
     * tap lands off-target.
     */
    fun toRoomX(
        screenX: Float, currentPage: Int, pageScrollOffset: Float,
        screenWidth: Float, roomWidth: Float, landscape: Boolean
    ): Float = screenX -
        pageOriginX(currentPage, currentPage, pageScrollOffset, screenWidth, roomWidth, landscape)
}
