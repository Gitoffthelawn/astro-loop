package com.astroloop.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import com.astroloop.game.data.PersistenceManager

/**
 * A draggable button that opens the debug menu, in debug builds only.
 *
 * **Raw device pixels, deliberately.** Both surface views render their content through a
 * `renderScale`, but the cabinet overlay is drawn unscaled — `HangarSurfaceView` routes cabinet
 * touches before the scale division for exactly that reason. Working in raw pixels means one
 * coordinate space for draw and hit-test in both views, and over the cabinet as well.
 *
 * **Tap versus drag has one rule:** a press promotes to a drag on slop **or** on
 * elapsed time. Slop alone was the first version of that rule and it broke stationary holds; the
 * review caught it and the fix was the disjunction. Do not reduce it back to one condition.
 *
 * The caller owns the `BuildConfig.DEBUG` gate at both the draw and the touch site. This class does
 * not check it — a unit test needs to exercise it regardless of build type.
 */
class DebugFloatingButton(private val radiusPx: Float) {

    enum class Outcome {
        /** Not ours. The caller should keep processing the event. */
        IGNORED,
        /** Ours, and handled. The caller must stop processing the event. */
        CONSUMED,
        /** Ours, and it was a tap. The caller should open the debug menu. */
        TAPPED
    }

    var cx: Float = 0f
        private set
    var cy: Float = 0f
        private set

    private var active = false
    private var promoted = false
    private var downX = 0f
    private var downY = 0f
    private var downMs = 0L
    private var grabDx = 0f
    private var grabDy = 0f

    /** Set while a promoted drag is moving the button; folded into [dirtyPosition] when it ends. */
    private var movedThisGesture = false

    /** True from the end of a drag until [consumeDirtyPosition] reads it. */
    private var dirtyPosition = false

    /** Read the saved centre, defaulting and clamping it into a view of this size. */
    fun load(p: PersistenceManager, viewW: Float, viewH: Float) =
        applyLoaded(p.getDebugButtonX(), p.getDebugButtonY(), viewW, viewH)

    fun save(p: PersistenceManager) {
        p.setDebugButtonX(cx)
        p.setDebugButtonY(cy)
    }

    /**
     * The pure half of [load], so the defaulting and clamping are testable without a Context.
     * A negative saved coordinate means "never positioned".
     */
    fun applyLoaded(savedX: Float, savedY: Float, viewW: Float, viewH: Float) {
        val defaultX = viewW - radiusPx - MARGIN
        val defaultY = viewH * 0.5f
        cx = if (savedX < 0f) defaultX else savedX
        cy = if (savedY < 0f) defaultY else savedY
        clampInto(viewW, viewH)
    }

    private fun clampInto(viewW: Float, viewH: Float) {
        cx = cx.coerceIn(radiusPx, (viewW - radiusPx).coerceAtLeast(radiusPx))
        cy = cy.coerceIn(radiusPx, (viewH - radiusPx).coerceAtLeast(radiusPx))
    }

    private fun contains(x: Float, y: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= radiusPx * radiusPx
    }

    fun press(x: Float, y: Float, atMs: Long): Outcome {
        if (!contains(x, y)) return Outcome.IGNORED
        active = true
        promoted = false
        downX = x
        downY = y
        downMs = atMs
        grabDx = cx - x
        grabDy = cy - y
        return Outcome.CONSUMED
    }

    fun move(x: Float, y: Float, atMs: Long, viewW: Float, viewH: Float): Outcome {
        if (!active) return Outcome.IGNORED
        if (!promoted) {
            val dx = x - downX
            val dy = y - downY
            val movedFar = dx * dx + dy * dy > SLOP_PX * SLOP_PX
            val heldLong = atMs - downMs >= HOLD_MS
            if (movedFar || heldLong) promoted = true
        }
        if (promoted) {
            cx = x + grabDx
            cy = y + grabDy
            clampInto(viewW, viewH)
            movedThisGesture = true
        }
        return Outcome.CONSUMED
    }

    /**
     * End the gesture. Takes no position: once [press] armed us the release is ours wherever the
     * finger lifted, and a drag's final centre was already set by the last [move].
     */
    fun release(atMs: Long): Outcome {
        // No arming press means no tap. Without this guard a release that never had a down —
        // reachable whenever another handler consumed the ACTION_DOWN — reports a phantom tap.
        // A device pass found the identical hole in CabinetInput.up().
        if (!active) return Outcome.IGNORED
        val wasPromoted = promoted || (atMs - downMs >= HOLD_MS)
        endGesture()
        return if (wasPromoted) Outcome.CONSUMED else Outcome.TAPPED
    }

    /**
     * Abandon the gesture.
     *
     * **Returns IGNORED when nothing was armed, symmetrically with [release].** The caller sits at
     * the very top of `onTouchEvent` and stops processing the event on anything but IGNORED, so
     * consuming a cancel that was never ours starves every handler below it — including the
     * pause-and-hold debug trigger, whose own disarm runs on ACTION_UP *and* ACTION_CANCEL. Left
     * unguarded, a cancelled hold leaves that trigger armed and it opens the menu five seconds
     * later on its own.
     */
    fun cancel(): Outcome {
        if (!active) return Outcome.IGNORED
        endGesture()
        return Outcome.CONSUMED
    }

    private fun endGesture() {
        if (movedThisGesture) dirtyPosition = true
        active = false
        promoted = false
        movedThisGesture = false
    }

    /**
     * True exactly once after a gesture that actually moved the button, so the caller can persist
     * the new centre without a `SharedPreferences` write per frame.
     *
     * A latch rather than a fourth [Outcome]: the caller already switches on three, and the
     * question "did this gesture move me" belongs to the button, not to a nine-thousand-line view.
     */
    fun consumeDirtyPosition(): Boolean {
        val d = dirtyPosition
        dirtyPosition = false
        return d
    }

    /** Thin shim over the four pure entry points. Verified on device, not in the suite. */
    fun onTouch(event: MotionEvent, viewW: Float, viewH: Float): Outcome {
        val t = event.eventTime
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(event.x, event.y, t)
            MotionEvent.ACTION_MOVE -> move(event.x, event.y, t, viewW, viewH)
            MotionEvent.ACTION_UP -> release(t)
            MotionEvent.ACTION_CANCEL -> cancel()
            else -> Outcome.IGNORED
        }
    }

    fun draw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radiusPx, fill)
        canvas.drawCircle(cx, cy, radiusPx, stroke)
        val r = radiusPx * 0.34f
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, stroke)
        canvas.drawLine(cx - r, cy + r, cx + r, cy - r, stroke)
    }

    // Lazy, and load-bearing rather than stylistic. This class is unit-tested through
    // press/move/release/cancel without ever calling draw(), and plain JUnit runs against the
    // android.jar stub, which throws on any real Paint call. Eager fields would therefore fail
    // every test in this class at construction, before a single assertion ran.
    private val fill by lazy {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = 0x66000000
        }
    }

    private val stroke by lazy {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0x99FFFFFF.toInt()
        }
    }

    companion object {
        /** Promotion distance. Beyond this the press is a drag, never a tap. */
        const val SLOP_PX = 24f

        /** Promotion time. Past this the press is a drag even if it never moved. */
        const val HOLD_MS = 250L

        /** Gap from the view edge for a button that has never been positioned. */
        const val MARGIN = 24f
    }
}
