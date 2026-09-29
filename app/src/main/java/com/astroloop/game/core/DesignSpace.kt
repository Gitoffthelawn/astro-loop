package com.astroloop.game.core

/**
 * Everything a SurfaceView needs to lay out a frame, derived once from the physical surface size.
 *
 * [width] and [height] are design units — physical pixels divided by [renderScale] — which is the
 * space every renderer draws in.
 */
data class ScreenMetrics(
    val renderScale: Float,
    val width: Float,
    val height: Float,
    val layout: ScreenLayout
)

/**
 * The design rect, oriented to match the screen.
 *
 * The game is authored against a portrait rect of [GameConfig.DESIGN_WIDTH] x
 * [GameConfig.DESIGN_HEIGHT], and `renderScale` *contains* it: the binding axis lands on its design
 * value and the other reveals more. Rotate the screen and height binds, so everything renders at
 * roughly half size — 24 design units of body text is 12.1px on a 1080p panel, about 0.6mm on a
 * Pixel 9 Pro — while combat reveals nearly four times the play area on a curve tuned in portrait.
 *
 * Transposing the rect fixes both at once, and has three properties worth stating because they are
 * what make it safe:
 *
 *  - **Portrait cannot change.** [isLandscape] is false there, so every value below is the
 *    arithmetic that shipped. `DesignSpaceTest` asserts this at zero tolerance.
 *  - **There is no scale discontinuity.** The two rects are transposes, so a square screen gives
 *    `S / DESIGN_HEIGHT` under either — equal algebraically, not approximately. That matters
 *    because fold inner panels sit within 4% of square.
 *  - **It is one definition.** Both SurfaceViews used to carry their own copy of this derivation.
 *
 * Takes physical pixels, returns design units per pixel. Pure — no Android types — so the whole
 * model is unit-testable, like [ScreenLayout] and `HangarMetrics`.
 */
object DesignSpace {

    /** Square counts as portrait: the scale is identical either way, so the tie needs only to be
     *  broken consistently, and portrait is the side with no behaviour change. */
    fun isLandscape(physW: Float, physH: Float): Boolean = physW > physH

    fun designWidth(physW: Float, physH: Float): Float =
        if (isLandscape(physW, physH)) GameConfig.DESIGN_HEIGHT else GameConfig.DESIGN_WIDTH

    fun designHeight(physW: Float, physH: Float): Float =
        if (isLandscape(physW, physH)) GameConfig.DESIGN_WIDTH else GameConfig.DESIGN_HEIGHT

    /** What [ScreenLayout.compute] contains `content` to on this screen. */
    fun designAspect(physW: Float, physH: Float): Float =
        designWidth(physW, physH) / designHeight(physW, physH)

    /**
     * Physical pixels per design unit. Contain semantics, unchanged from what shipped: the design
     * rect always fits, and the slack axis shows more world.
     */
    fun renderScale(physW: Float, physH: Float): Float =
        minOf(physW / designWidth(physW, physH), physH / designHeight(physW, physH))

    /**
     * The whole derivation, in one place.
     *
     * Both SurfaceViews carried a copy of this: the scale, the two design-unit dimensions, and the
     * [ScreenLayout] built from them with the insets converted. Two copies of a computation this
     * load-bearing is how one of them acquires a fix the other does not.
     *
     * Insets arrive in PHYSICAL pixels and are converted here, exactly as the callers did.
     *
     * A 0x0 surface — which `surfaceChanged` really does deliver — yields a zero scale and NaN
     * dimensions. That is what shipped, and `HangarSurfaceView`'s re-anchor block detects it via
     * `state.pilotX.isNaN()`, so it is deliberately not guarded here.
     */
    fun metricsFor(
        physW: Float,
        physH: Float,
        insetLeftPx: Float = 0f,
        insetTopPx: Float = 0f,
        insetRightPx: Float = 0f,
        insetBottomPx: Float = 0f,
        cornerRadiusPx: Float = 0f
    ): ScreenMetrics {
        val scale = renderScale(physW, physH)
        val width = physW / scale
        val height = physH / scale
        return ScreenMetrics(
            renderScale = scale,
            width = width,
            height = height,
            layout = ScreenLayout.compute(
                width = width,
                height = height,
                insetLeft = insetLeftPx / scale,
                insetTop = insetTopPx / scale,
                insetRight = insetRightPx / scale,
                insetBottom = insetBottomPx / scale,
                designAspect = designAspect(physW, physH),
                cornerRadius = cornerRadiusPx / scale
            )
        )
    }

    /**
     * The device's own PORTRAIT design space, whichever way it is held.
     *
     * In portrait this returns [layout] **unchanged** — the short edge is already the width — so
     * anything laid out against it is bit-for-bit what shipped, and portrait cannot move. That is
     * the safety argument: an identity, not a careful reconstruction.
     *
     * In landscape it transposes, rotating the inset set with the axes: a rotated device's cutout
     * sits on a side edge, so the landscape LEFT inset becomes the portrait-shaped TOP inset.
     * Ignoring insets entirely is how the 2026-09-13 composition attempt broke portrait; getting
     * the rotation wrong would be the same failure mirrored — a reflection, not a rotation, silently
     * swaps `insetLeft` and `insetRight` for the wrong sources while leaving every size unchanged,
     * because `ScreenLayout.compute` derives sizes from the inset *sum*, which cannot tell the two
     * apart. Only positions expose it, so the four expressions below are anchored on a walk of the
     * landscape rect's `safe` corners clockwise (left, top, right, bottom) onto the portrait rect's
     * corners clockwise (top, right, bottom, left) — the actual rotation, not its mirror image — so
     * that they invert exactly what `ScreenLayout.compute` builds:
     * `safe = (insetLeft, insetTop, width - insetRight, height - insetBottom)`.
     *
     * The default `designAspect` is the portrait one, which is correct here by construction.
     */
    fun portraitShaped(layout: ScreenLayout): ScreenLayout {
        if (layout.width <= layout.height) return layout
        return ScreenLayout.compute(
            width = layout.height,
            height = layout.width,
            insetLeft = layout.height - layout.safe.bottom,
            insetTop = layout.safe.left,
            insetRight = layout.safe.top,
            insetBottom = layout.width - layout.safe.right,
            // A radius survives a rotation unchanged — it is the same physical corner, and the
            // four are equal on every phone this has to hold for. Carried rather than dropped so
            // a caller that lays something out against the portrait-shaped rect gets the same
            // answer from `cornerSafeRight` as one working in the real portrait layout.
            cornerRadius = layout.cornerRadius
        )
    }
}
