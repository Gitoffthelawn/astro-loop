package com.astroloop.game.render

import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.LayoutRect
import com.astroloop.game.core.ScreenLayout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards two things: the combat HUD is never clipped by a cutout, and it never escapes its band.
 *
 * The element offsets below still mirror HUDRenderer.renderTopBar and must stay in sync with it,
 * but the horizontal anchors now come from [HudBand] rather than being re-derived here. That
 * matters: re-derived anchors would have kept passing against the pre-band geometry after the
 * renderer moved on.
 */
class HudSafeAreaTest {
    private val padding = 20f
    private val iconSize = 48f
    private val iconPadding = 8f
    private val weaponSlots = 4
    private val yenAreaWidth = 140f
    private val barGap = 4f
    private val barHeight = (iconSize - barGap) / 2f

    private fun band(layout: ScreenLayout, sw: Int) = LayoutRect(
        HudBand.left(layout, sw), layout.safe.top, HudBand.right(layout, sw), layout.safe.bottom
    )

    private fun weaponGrid(layout: ScreenLayout, sw: Int): LayoutRect {
        val left = HudBand.left(layout, sw) + padding
        val top = layout.safe.top + padding
        // 4 weapon slots across the top row
        return LayoutRect(left, top, left + weaponSlots * (iconSize + iconPadding), top + iconSize)
    }

    private fun healthBar(layout: ScreenLayout, sw: Int): LayoutRect {
        val left = HudBand.left(layout, sw) + padding
        val top = layout.safe.top + padding
        val barStartX = left + weaponSlots * (iconSize + iconPadding) + 12f
        val rightX = HudBand.right(layout, sw) - padding
        val barWidth = rightX - barStartX - yenAreaWidth
        return LayoutRect(barStartX, top, barStartX + barWidth, top + barHeight)
    }

    private fun timer(layout: ScreenLayout, sw: Int): LayoutRect {
        // right-aligned at rightX, on the bottom grid row
        val rightX = HudBand.right(layout, sw) - padding
        val top = layout.safe.top + padding
        val bottomRowCenterY = top + iconSize + iconPadding + iconSize / 2f
        // Generous nominal width: HUDRenderer.formatTime() max is "M:SS" (~88px @ 36sp),
        // so 120px is a comfortable upper bound for the right-aligned timer label.
        return LayoutRect(rightX - 120f, bottomRowCenterY - 20f, rightX, bottomRowCenterY + 20f)
    }

    private fun assertInside(el: LayoutRect, container: LayoutRect, what: String) {
        assertTrue("$what: left ${el.left} < ${container.left}", el.left >= container.left)
        assertTrue("$what: top ${el.top} < ${container.top}", el.top >= container.top)
        assertTrue("$what: right ${el.right} > ${container.right}", el.right <= container.right)
        assertTrue("$what: bottom ${el.bottom} > ${container.bottom}", el.bottom <= container.bottom)
    }

    @Test
    fun `combat HUD stays within safe area and inside its band across devices and insets`() {
        // physW, physH, smallestScreenWidthDp, and the insets in PHYSICAL pixels.
        val cases = listOf(
            DesignSpace.metricsFor(1080f, 2424f).layout to 411,
            DesignSpace.metricsFor(1080f, 2424f, insetTopPx = 128f).layout to 411,
            DesignSpace.metricsFor(1080f, 1920f).layout to 360,
            DesignSpace.metricsFor(1080f, 2640f, insetTopPx = 60f, insetBottomPx = 48f).layout to 411,
            DesignSpace.metricsFor(1600f, 2560f, insetTopPx = 90f).layout to 800,
            DesignSpace.metricsFor(2076f, 2152f, insetTopPx = 80f, insetRightPx = 60f).layout to 800,
            // Landscape: the design rect transposes, so a display cutout lands on a SIDE edge.
            DesignSpace.metricsFor(1920f, 1080f).layout to 360,
            DesignSpace.metricsFor(1920f, 1080f, insetLeftPx = 128f).layout to 360,
            DesignSpace.metricsFor(2400f, 1080f, insetRightPx = 128f).layout to 411,
            DesignSpace.metricsFor(3840f, 2160f).layout to 540,
            DesignSpace.metricsFor(2560f, 1600f, insetTopPx = 40f).layout to 800,
            DesignSpace.metricsFor(2152f, 2076f, insetLeftPx = 128f).layout to 800
        )
        for ((layout, sw) in cases) {
            val safe = layout.safe
            val band = band(layout, sw)
            assertInside(band, safe, "band")
            // Without this the test cannot see the bug it exists for: every element is checked
            // against the band, so a band that spans the screen contains them all and passes.
            // The stretch is only visible as a claim about the band's own width.
            if (HudBand.isLandscape(layout)) {
                assertTrue(
                    "the band must not span a landscape screen: ${band.width} of ${safe.width}",
                    band.width < safe.width * 0.95f
                )
            }
            for ((name, el) in listOf(
                "weapon grid" to weaponGrid(layout, sw),
                "health bar" to healthBar(layout, sw),
                "timer" to timer(layout, sw)
            )) {
                assertInside(el, safe, name)
                assertInside(el, band, name)
            }
        }
    }
}
