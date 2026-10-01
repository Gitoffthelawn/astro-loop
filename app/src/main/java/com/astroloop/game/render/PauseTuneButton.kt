package com.astroloop.game.render

import android.graphics.RectF

/** The buttons a watching build adds to the plain pause overlay: TUNE, and QUIT beside it when offered. */
object PauseTuneButton {
    private const val WIDTH_FRACTION = 0.42f
    private const val PAIR_WIDTH_FRACTION = 0.30f
    private const val PAIR_GAP_FRACTION = 0.04f
    private const val HEIGHT = 120f
    private const val TOP_FRACTION = 0.62f

    fun rect(screenWidth: Float, screenHeight: Float): RectF {
        val w = screenWidth * WIDTH_FRACTION
        val left = (screenWidth - w) / 2f
        val top = screenHeight * TOP_FRACTION
        return RectF(left, top, left + w, top + HEIGHT)
    }

    fun tuneRect(screenWidth: Float, screenHeight: Float, withQuit: Boolean): RectF {
        if (!withQuit) return rect(screenWidth, screenHeight)
        val w = screenWidth * PAIR_WIDTH_FRACTION
        val left = screenWidth / 2f + screenWidth * PAIR_GAP_FRACTION / 2f
        val top = screenHeight * TOP_FRACTION
        return RectF(left, top, left + w, top + HEIGHT)
    }

    fun quitRect(screenWidth: Float, screenHeight: Float): RectF {
        val w = screenWidth * PAIR_WIDTH_FRACTION
        val right = screenWidth / 2f - screenWidth * PAIR_GAP_FRACTION / 2f
        val top = screenHeight * TOP_FRACTION
        return RectF(right - w, top, right, top + HEIGHT)
    }

    fun hit(x: Float, y: Float, screenWidth: Float, screenHeight: Float): Boolean =
        rect(screenWidth, screenHeight).contains(x, y)

    fun hitTune(x: Float, y: Float, screenWidth: Float, screenHeight: Float, withQuit: Boolean): Boolean =
        tuneRect(screenWidth, screenHeight, withQuit).contains(x, y)

    fun hitQuit(x: Float, y: Float, screenWidth: Float, screenHeight: Float): Boolean =
        quitRect(screenWidth, screenHeight).contains(x, y)
}
