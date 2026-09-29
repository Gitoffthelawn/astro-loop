package com.astroloop.game.hangar

import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.ScreenLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HangarMetricsTest {

    /**
     * Calls what HangarSurfaceView.applyScreenDimensions calls: physical px in, design units out.
     *
     * This used to inline the portrait renderScale formula instead. Nothing failed when the design
     * rect learned to transpose — the tests simply went on passing while modelling devices that had
     * stopped existing, which is worse than a failure.
     */
    private fun roomWidthFor(physW: Float, physH: Float, swDp: Int): Float {
        val m = DesignSpace.metricsFor(physW, physH)
        return HangarMetrics.roomWidth(m.width, m.layout.content.width, swDp)
    }

    @Test
    fun `below the gate the room is the whole screen`() {
        // Compact 16:9 phone. Content would be narrower, but the gate keeps today's layout.
        val renderScale = minOf(1080f / GameConfig.DESIGN_WIDTH, 1920f / GameConfig.DESIGN_HEIGHT)
        val screenW = 1080f / renderScale
        assertEquals(screenW, roomWidthFor(1080f, 1920f, swDp = 411), 0.01f)
    }

    @Test
    fun `at the gate the room narrows to the content column`() {
        // PORTRAIT tablet. Content collapses to exactly DESIGN_WIDTH once wider than design aspect.
        // This used to name a 2560x1600 tablet, which is landscape — and there screenWidth and
        // content.width are both 2142, so both sides of the gate agree and the test proved nothing.
        assertEquals(GameConfig.DESIGN_WIDTH, roomWidthFor(1600f, 2560f, swDp = 800), 0.5f)
    }

    @Test
    fun `600dp is inclusive`() {
        val at599 = roomWidthFor(1600f, 2560f, swDp = 599)
        val at600 = roomWidthFor(1600f, 2560f, swDp = 600)
        assertEquals("599dp must keep the full-screen room", 1338.7f, at599, 2f)
        assertEquals("600dp must narrow to content", GameConfig.DESIGN_WIDTH, at600, 0.5f)
    }

    @Test
    fun `screens narrower than the design aspect are unaffected even above the gate`() {
        // 22:9 is narrower than 0.4482, so content already fills the width.
        val renderScale = minOf(1080f / GameConfig.DESIGN_WIDTH, 2640f / GameConfig.DESIGN_HEIGHT)
        val screenW = 1080f / renderScale
        assertEquals(screenW, roomWidthFor(1080f, 2640f, swDp = 700), 0.01f)
    }

    @Test
    fun `room width never exceeds the screen or collapses to zero`() {
        assertEquals(500f, HangarMetrics.roomWidth(500f, 9999f, 800), 0.01f)
        assertEquals(1f, HangarMetrics.roomWidth(500f, 0f, 800), 0.01f)
    }

    @Test
    fun `degenerate screen width passes through untouched`() {
        assertEquals(0f, HangarMetrics.roomWidth(0f, 0f, 800), 0.01f)
    }

    @Test
    fun `effectiveRoomWidth falls back to the screen until dimensions arrive`() {
        assertEquals(960f, HangarMetrics.effectiveRoomWidth(960f, 3427f), 0.01f)
        assertEquals(3427f, HangarMetrics.effectiveRoomWidth(0f, 3427f), 0.01f)
    }

    // The viewport transform, the room inverse and their round-trip moved to RoomAnchor, which
    // covers all three in BOTH orientations; see RoomAnchorTest. The tests that lived here are
    // gone with the functions they named, not weakened.

    // =====================================================================
    // Content column → room-local (layout inside a page's translate)
    // =====================================================================

    @Test
    fun `below the gate the content column keeps its screen-space position`() {
        // 16:9 phone: content is narrower than the screen, so content.left is NOT zero. The
        // bar and shop grids must stay anchored to it exactly as they ship.
        val renderScale = minOf(1080f / GameConfig.DESIGN_WIDTH, 1920f / GameConfig.DESIGN_HEIGHT)
        val screenW = 1080f / renderScale
        val content = ScreenLayout.compute(screenW, 1920f / renderScale).content
        assertTrue("this device must have a non-zero content.left to be a real test", content.left > 1f)
        assertEquals(content.left, HangarMetrics.contentXInRoom(content.left, screenW, screenW), 0f)
        assertEquals(content.right, HangarMetrics.contentXInRoom(content.right, screenW, screenW), 0f)
    }

    @Test
    fun `above the gate the content column becomes the room`() {
        // PORTRAIT tablet: the room IS the content column, so the column spans 0..roomWidth
        // and a 12px-padded grid sits 2px inside the counter's 10px inset on both edges.
        val m = DesignSpace.metricsFor(1600f, 2560f)
        val content = m.layout.content
        val roomW = HangarMetrics.roomWidth(m.width, content.width, smallestScreenWidthDp = 800)
        assertEquals(0f, HangarMetrics.contentXInRoom(content.left, roomW, m.width), 0.01f)
        assertEquals(roomW, HangarMetrics.contentXInRoom(content.right, roomW, m.width), 0.01f)
    }

    // A test named `in landscape the room is the whole screen under either gate branch` stood
    // here, asserting that a LANDSCAPE rect fed to `roomWidth` yields the whole screen on both
    // sides of the sw600 gate — and concluding the landscape hangar does not depend on the
    // breakpoint. Deleted 2026-09-19: production never makes that call any more.
    // `HangarSurfaceView.applyScreenDimensions` feeds `roomWidth` the PORTRAIT-SHAPED metrics
    // (`DesignSpace.portraitShaped`), so the input combination it exercised no longer occurs, and
    // the conclusion it recorded is the negation of what the branch actually does —
    // `LandscapeRoomTest`'s `a rotated tablet's room is its portrait content column, not the
    // screen` asserts the opposite, against the call production really makes.

    @Test
    fun `stool centres match the renderer's spacing`() {
        // The renderer draws 8 stools between barLeft (10) and barRight (roomWidth - 10),
        // at barLeft + spacing * s for s in 1..8, spacing = (barRight - barLeft) / 9.
        val roomWidth = 960f
        val barLeft = 10f
        val barRight = roomWidth - 10f
        val spacing = (barRight - barLeft) / 9f
        for (s in 1..8) {
            assertEquals(
                "stool $s must sit where the renderer draws it",
                barLeft + spacing * s,
                HangarMetrics.stoolCenterX(roomWidth, s),
                0.01f
            )
        }
    }
}
