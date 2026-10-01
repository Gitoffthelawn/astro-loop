package com.astroloop.game.lab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.astroloop.game.render.FontManager

/** When a held −/+ repeats: a pause, then faster and faster. */
object RepeatSchedule {
    const val FIRST_DELAY_MS = 400L
    private const val SLOW_MS = 150L
    private const val FAST_MS = 40L
    private const val RAMP_MS = 1500L

    fun intervalMs(sinceFirstRepeatMs: Long): Long {
        val t = sinceFirstRepeatMs.coerceIn(0L, RAMP_MS)
        return SLOW_MS - (SLOW_MS - FAST_MS) * t / RAMP_MS
    }
}

/** Shared look for the Lab's plain screens: black, white text in Exo 2, 16dp gutters. */
object LabUi {
    const val BG = Color.BLACK
    const val FG = Color.WHITE
    const val DIM = 0xFF9A9A9A.toInt()
    const val ACCENT = 0xFFDD3333.toInt()
    const val CHANGED = 0xFFFFC857.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun text(ctx: Context, sizeSp: Float, bold: Boolean = false, color: Int = FG): TextView =
        TextView(ctx).apply {
            textSize = sizeSp
            setTextColor(color)
            typeface = if (bold) FontManager.getBold() else FontManager.getRegular()
        }

    fun mono(ctx: Context, sizeSp: Float): TextView =
        TextView(ctx).apply { textSize = sizeSp; setTextColor(FG); typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }

    fun button(ctx: Context, label: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            typeface = FontManager.getBold()
            setOnClickListener { onClick() }
        }

    /**
     * A −/+ button that steps while held, by touch or by a controller's select button. A touch steps
     * once on release when let go before [RepeatSchedule.FIRST_DELAY_MS] (so a scroll that starts on
     * the button and cancels it changes nothing), and from then on repeats while held. A select key
     * steps at once and repeats while held. [onStep] returns false when nothing changed, which ends
     * the run of repeats; [onRelease] runs once when the press ends, however it ends.
     */
    @SuppressLint("ClickableViewAccessibility") // performClick would step a second time; it stays one step for accessibility.
    fun repeatButton(ctx: Context, label: String, onStep: () -> Boolean, onRelease: () -> Unit): Button {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var holding = false
        var stepped = false
        var firstRepeatAt = 0L
        val tick = object : Runnable {
            override fun run() {
                if (!holding) return
                val now = android.os.SystemClock.uptimeMillis()
                if (firstRepeatAt == 0L) firstRepeatAt = now
                stepped = true
                if (onStep()) handler.postDelayed(this, RepeatSchedule.intervalMs(now - firstRepeatAt))
            }
        }
        lateinit var button: Button
        fun start(stepNow: Boolean) {
            if (holding) return
            holding = true
            stepped = stepNow
            firstRepeatAt = 0L
            button.isPressed = true
            if (!stepNow || onStep()) handler.postDelayed(tick, RepeatSchedule.FIRST_DELAY_MS)
        }
        fun stop() {
            if (!holding) return
            holding = false
            handler.removeCallbacks(tick)
            button.isPressed = false
            onRelease()
        }
        /**
         * A touch let go on the button before the first repeat is a tap: one step, then the release.
         * Let go off the button, it is withdrawn and only releases.
         */
        fun lift(onButton: Boolean) {
            if (holding && !stepped && onButton) { stepped = true; onStep() }
            stop()
        }
        button = object : Button(ctx) {
            override fun performClick(): Boolean {
                super.performClick()
                if (!holding) { onStep(); onRelease() }
                return true
            }
            override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
                super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
                if (!gainFocus) stop()
            }
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus) stop()
            }
            override fun onDetachedFromWindow() {
                stop()
                super.onDetachedFromWindow()
            }
        }
        return button.apply {
            text = label
            isAllCaps = false
            typeface = FontManager.getBold()
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> start(stepNow = false)
                    android.view.MotionEvent.ACTION_UP ->
                        lift(e.x >= 0f && e.y >= 0f && e.x < width && e.y < height)
                    android.view.MotionEvent.ACTION_CANCEL -> stop()
                }
                true
            }
            setOnKeyListener { _, keyCode, e ->
                val select = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
                if (!select) return@setOnKeyListener false
                when (e.action) {
                    android.view.KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) start(stepNow = true)
                    android.view.KeyEvent.ACTION_UP -> stop()
                }
                true
            }
        }
    }

    fun column(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun row(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    fun screen(ctx: Context, content: View): View {
        val gutter = dp(ctx, 16)
        content.setPadding(gutter, gutter, gutter, gutter)
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Edge to edge: keep content clear of the status bar, cutout and navigation bar.
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            content.setPadding(gutter + bars.left, gutter + bars.top, gutter + bars.right, gutter + bars.bottom)
            insets
        }
        return scroll
    }
}
