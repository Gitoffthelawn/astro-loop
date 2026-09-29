package com.astroloop.game.input

import android.graphics.RectF

/**
 * One thing a directional device can land on.
 *
 * Published by renderers during layout. The touch path keeps its own hit-testing — these two
 * are deliberately parallel, and a per-screen parity test asserts they agree.
 *
 * @param id stable across layout passes; focus is restored by id, never by index.
 * @param enabled a disabled target is drawn and skipped, not omitted — the launch pad before
 *   the walker arrives is visible but unavailable, and the player should see that.
 */
data class FocusTarget(
    val id: String,
    val rect: RectF,
    val enabled: Boolean = true,
    val onActivate: () -> Unit
)
