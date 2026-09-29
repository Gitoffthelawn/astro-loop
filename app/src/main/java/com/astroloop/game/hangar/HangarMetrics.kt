package com.astroloop.game.hangar

import com.astroloop.game.core.ScreenLayout

/**
 * Shared metrics for the three-page hangar — the room widths the pages tile at, and the nav row
 * the three pages share.
 *
 * A room is normally the whole screen, and the three pages tile edge to edge one screen apart.
 * On large screens the room narrows to the content column instead, so the counter and the pilot
 * grid share a width and the pages tile at that narrower stride — which puts the neighbouring
 * rooms partly on screen and keeps the walkway continuous for the same reason it always was.
 *
 * Below the gate this returns the screen width and nothing about the layout changes.
 */
object HangarMetrics {

    /**
     * Short-edge width, in dp, at which rooms narrow to the content column.
     *
     * The same breakpoint Android uses to decide whether to honour a portrait lock, so large
     * screens are one concept rather than two. The deciding reason is not aesthetic: anchoring a
     * 16:9 phone to the content column cuts the chat column from ~1060px to ~841px, below the
     * ~930px floor the authored bar-chatter line lengths assume, so lines that fit everywhere
     * today would start ellipsizing there.
     */
    const val ADJACENT_ROOMS_MIN_SW_DP = ScreenLayout.LARGE_SCREEN_MIN_SW_DP

    /**
     * Width of a single hangar room, in design units, which is also the page stride.
     *
     * [screenWidth] and [contentWidth] are design units (physical px / renderScale), matching
     * `HangarSurfaceView.screenWidth` and `layout.content.width`.
     */
    fun roomWidth(screenWidth: Float, contentWidth: Float, smallestScreenWidthDp: Int): Float {
        if (screenWidth <= 0f) return screenWidth
        if (smallestScreenWidthDp < ADJACENT_ROOMS_MIN_SW_DP) return screenWidth
        return contentWidth.coerceIn(1f, screenWidth)
    }

    /**
     * The room width to actually draw with, falling back to [screenWidth] for the frames before
     * dimensions have propagated. Every consumer goes through this rather than repeating the
     * check, so there is one definition of "not set yet".
     */
    fun effectiveRoomWidth(roomWidth: Float, screenWidth: Float): Float =
        if (roomWidth > 0f) roomWidth else screenWidth

    /**
     * A screen-space content-column X (`layout.content.left` / `.right`) in room-local units.
     *
     * `ScreenLayout` centres the content column in the safe area, so its coordinates are screen
     * space; used raw inside a page's translate they land a room-offset too far right. Above the
     * gate the room *is* the content column, so this collapses to `0 .. roomWidth` and the grids
     * span the room. Below the gate the room is the whole screen, the offset is zero, and this is
     * the identity — the content-anchored layout phones ship today is untouched.
     *
     * No scroll term: the page's own translate already carries it. This is what the deleted
     * `toRoomX(contentX, roomWidth, screenWidth, pageScrollOffset = 0f)` expanded to — that
     * function's body less a scroll term that was passed as zero.
     */
    fun contentXInRoom(contentX: Float, roomWidth: Float, screenWidth: Float): Float =
        contentX - (screenWidth - effectiveRoomWidth(roomWidth, screenWidth)) / 2f

    /**
     * Baseline Y of the nav row's `[CREW] [LAUNCH] [SHOP]` labels, in design units.
     *
     * Shared with [navBandTop] rather than written out at each draw site, so the row's position
     * and the band that catches taps for it can never drift apart.
     */
    fun navLabelY(screenHeight: Float): Float = screenHeight * 0.95f

    /** How far above [navLabelY] the nav row's tap band starts. */
    private const val NAV_BAND_ABOVE_LABEL = 30f

    /**
     * Top of the nav row's tap band, in design units.
     *
     * One definition for a number four sites used to hand-copy: `HangarSurfaceView.handleTap`'s
     * input gate, `HangarRenderer.drawPageIndicator`'s labels, `publishNavTargets`' focus rects
     * and `HangarRenderer.panelAvailableHeight`. That last one is why this is a seam and not a
     * comment: a PANEL'S ARRANGEMENT now depends on this line. The nav row is drawn on top of the
     * panel layer, so a board allowed to grow into the band puts its bottom row underneath the
     * lit labels — a press-and-hold there completing a purchase while a quick tap navigated away
     * (found in review). Four literals agreeing by convention is not a defence against
     * that recurring; one function is.
     */
    fun navBandTop(screenHeight: Float): Float = navLabelY(screenHeight) - NAV_BAND_ABOVE_LABEL

    /** Number of stools drawn along the counter. */
    const val STOOL_COUNT = 8

    /**
     * Centre X of stool [stool] (1..STOOL_COUNT) in room-local design units.
     *
     * Single source of truth: BarPageRenderer draws stools here and HangarState walks seated
     * crew here. These were duplicated, so moving one without the other would seat crew at
     * coordinates with no stool drawn under them.
     */
    fun stoolCenterX(roomWidth: Float, stool: Int): Float {
        val barLeft = 10f
        val barRight = roomWidth - 10f
        val spacing = (barRight - barLeft) / (STOOL_COUNT + 1)
        return barLeft + spacing * stool
    }
}
