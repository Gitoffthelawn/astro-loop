package com.astroloop.game.core

/** Plain-Kotlin rectangle (no Android deps) so all layout math is unit-testable. */
data class LayoutRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

/**
 * Resolution-independent layout. All values are in DESIGN units (post renderScale).
 *
 *  - [full]    entire design space; backgrounds / starfield / room floors bleed here.
 *  - [safe]    full space minus system-cutout insets; edge-anchored UI (HUD) pins here.
 *  - [content] design-aspect rect (DESIGN_WIDTH:DESIGN_HEIGHT) contained and centered
 *              inside [safe]; centered content blocks (grids, cards, carousel) lay out here.
 */
class ScreenLayout private constructor(
    val full: LayoutRect,
    val safe: LayoutRect,
    val content: LayoutRect,
    val cornerRadius: Float = 0f
) {
    val width: Float get() = full.width
    val height: Float get() = full.height

    /**
     * The right-most x a glyph whose highest point is [top] can reach before the display's
     * rounded TOP-RIGHT corner clips it.
     *
     * Rounded corners are not insets. The cutout is, the system bars are, and [safe] accounts for
     * both — but a phone's corner radius is reported separately (`WindowInsets.getRoundedCorner`,
     * API 31+) and nothing here used to ask for it. The hangar's yen counter is the one piece of
     * chrome that sits in a corner, and it is legible in portrait only because the cutout pushes
     * `safe.top` down past the arc. Rotate the device — or hold it upside down — and `safe.top` is
     * 0, the counter sits at y=50 in the bare corner, and the arc eats its right-hand end (owner,
     * 2026-09-20, on a Pixel 9 Pro).
     *
     * The arc's centre is at (`full.right - r`, `full.top + r`), so at a height [top] the display's
     * own right boundary is `full.right - r + sqrt(r^2 - (r - dy)^2)`, `dy` being the drop below
     * the top edge. The topmost point of a text box binds, since that boundary widens as `dy`
     * grows. A zero radius — every test fixture, and every square-cornered display — returns
     * [full].right unchanged, so this can only ever move something INWARD, and only as far as the
     * arc actually demands. That is what keeps portrait still: at `safe.top` 96.4 with a 28px
     * counter the glyphs start 120 units down, past a Pixel 9 Pro's arc entirely, so the
     * expression returns `full.right` and the counter does not move at all.
     */
    fun cornerSafeRight(top: Float): Float {
        val r = cornerRadius
        if (r <= 0f) return full.right
        val dy = (top - full.top).coerceIn(0f, r)
        val leg = r - dy
        return full.right - r + kotlin.math.sqrt(r * r - leg * leg)
    }

    /**
     * The device's own portrait design width — the same number in either orientation.
     *
     * `renderScale` is rotation-invariant: portrait takes `min(w/960, h/2142)`, landscape
     * `min(w/2142, h/960)`, and rotating swaps `w` and `h`, so the two are one expression. Both
     * this and `DesignSpace.metricsFor(shortEdge, longEdge).width` therefore reduce to
     * `shortEdge / renderScale` — the same operands in the same order — and agree bit-for-bit,
     * not to a tolerance.
     *
     * In landscape this is the width the combat HUD keeps rather than spanning the screen; see
     * HudBand.
     */
    val shortEdge: Float get() = minOf(width, height)

    companion object {
        /**
         * Short-edge width, in dp, at or above which a screen counts as large.
         *
         * The single home for that threshold. Both features that care — the hangar's adjacent-room
         * layout and the combat HUD's width — read it from here, because they gate on the same
         * physical judgement: this screen is wider than the design was drawn for. Two independent
         * 600s could drift apart and leave a device where the hangar narrows but the HUD does not.
         *
         * It is also the breakpoint Android itself uses to decide whether to honour a portrait lock.
         */
        const val LARGE_SCREEN_MIN_SW_DP = 600

        fun compute(
            width: Float,
            height: Float,
            insetLeft: Float = 0f,
            insetTop: Float = 0f,
            insetRight: Float = 0f,
            insetBottom: Float = 0f,
            designAspect: Float = GameConfig.DESIGN_WIDTH / GameConfig.DESIGN_HEIGHT,
            cornerRadius: Float = 0f
        ): ScreenLayout {
            val full = LayoutRect(0f, 0f, width, height)
            val safe = LayoutRect(insetLeft, insetTop, width - insetRight, height - insetBottom)

            val safeW = safe.width
            val safeH = safe.height
            // Guard transient zero/negative measurements (surfaceChanged can fire with 0 dims,
            // and renderScale=0 yields NaN). Fall back to a degenerate content == safe rather
            // than dividing by zero / propagating NaN into renderers.
            if (safeW <= 0f || safeH <= 0f || designAspect <= 0f) {
                return ScreenLayout(full, safe, safe, cornerRadius)
            }

            val contentW: Float
            val contentH: Float
            if (safeW / safeH > designAspect) {
                contentH = safeH
                contentW = safeH * designAspect
            } else {
                contentW = safeW
                contentH = safeW / designAspect
            }
            val cl = safe.left + (safeW - contentW) / 2f
            val ct = safe.top + (safeH - contentH) / 2f
            val content = LayoutRect(cl, ct, cl + contentW, ct + contentH)

            return ScreenLayout(full, safe, content, cornerRadius)
        }
    }
}
