package com.astroloop.game.render

import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.ScreenLayout

/**
 * Horizontal bounds of the combat HUD's top bar, in design units.
 *
 * PORTRAIT is exactly what shipped. Above [ScreenLayout.LARGE_SCREEN_MIN_SW_DP] the HUD pins to
 * [ScreenLayout.content] — the design-aspect column, which gives a large screen the phone's HUD
 * proportions. Below it the HUD spans [ScreenLayout.safe], because a 16:9 phone's safe area is
 * already wider than the design column (about 1205 against 960) and narrowing there would change
 * a device class that never had the stretch problem.
 *
 * LANDSCAPE keeps that same width and centres it. It cannot pin to `content`, because once the
 * design rect transposes `content` IS the full 2142-wide band, which is how the stretch appeared.
 * Instead the band takes the width this device's HUD has in portrait:
 *
 *  - below the gate, [ScreenLayout.shortEdge] — the device's own portrait design width;
 *  - above it, [GameConfig.DESIGN_WIDTH], because the gate has already narrowed that device's
 *    portrait HUD to the design column. Taking the panel's literal portrait design width instead
 *    would grow a tablet's HUD 39% on rotation and hand a near-square fold inner a 2066-wide band
 *    out of 2142 — the stretch all over again.
 *
 * The result is one invariant: **rotation never changes the HUD's physical size.** `renderScale`
 * is rotation-invariant, so equal design units are equal millimetres in both orientations.
 *
 * Two rules about insets, which are deliberate and not symmetric. A vertical inset never changes
 * the band's width — a top notch does not narrow the portrait HUD, so it must not narrow this
 * one. A horizontal inset can only narrow it, via the clamp in [landscapeWidth], which is what
 * makes "never clipped by a cutout" structural rather than empirical. That clamp fires on no real
 * device; it is a guard, not a rule.
 *
 * Extracted from HUDRenderer so the tests call this instead of re-implementing it.
 */
object HudBand {

    /**
     * True when the design space is wider than it is tall, which is exactly
     * `DesignSpace.isLandscape` of the physical surface: both dimensions divide by the same
     * scale, so the comparison survives it.
     */
    fun isLandscape(layout: ScreenLayout): Boolean = layout.width > layout.height

    private fun isLargeScreen(smallestScreenWidthDp: Int): Boolean =
        smallestScreenWidthDp >= ScreenLayout.LARGE_SCREEN_MIN_SW_DP

    /** The landscape band's width: this device's portrait HUD width, clamped to `safe`. */
    private fun landscapeWidth(layout: ScreenLayout, smallestScreenWidthDp: Int): Float {
        val nominal =
            if (isLargeScreen(smallestScreenWidthDp)) GameConfig.DESIGN_WIDTH else layout.shortEdge
        return minOf(nominal, layout.safe.width)
    }

    fun left(layout: ScreenLayout, smallestScreenWidthDp: Int): Float = when {
        isLandscape(layout) ->
            layout.safe.centerX - landscapeWidth(layout, smallestScreenWidthDp) / 2f
        isLargeScreen(smallestScreenWidthDp) -> layout.content.left
        else -> layout.safe.left
    }

    fun right(layout: ScreenLayout, smallestScreenWidthDp: Int): Float = when {
        isLandscape(layout) ->
            layout.safe.centerX + landscapeWidth(layout, smallestScreenWidthDp) / 2f
        isLargeScreen(smallestScreenWidthDp) -> layout.content.right
        else -> layout.safe.right
    }

    /** The HUD's width in either orientation. */
    fun width(layout: ScreenLayout, smallestScreenWidthDp: Int): Float =
        right(layout, smallestScreenWidthDp) - left(layout, smallestScreenWidthDp)
}
