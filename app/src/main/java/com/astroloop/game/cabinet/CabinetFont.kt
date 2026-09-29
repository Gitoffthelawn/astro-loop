package com.astroloop.game.cabinet

/**
 * A stroke-drawn alphabet for BELT RUN.
 *
 * Text on this machine must be DRAWN, not typeset. A TTF would sit on top of the
 * picture — it would not bloom with the phosphor or flicker with the tube — so every
 * glyph here is a list of polylines and gets the vector treatment for free.
 *
 * Coordinates are on a 6-wide by 10-tall design grid, y down. Advance is 8 so glyphs
 * carry two units of side bearing; the face is monospaced, which is what a real score
 * table needs so digits line up in columns.
 */
object CabinetFont {

    const val EM = 10f
    const val ADVANCE = 8f

    private fun s(vararg v: Float) = v

    private val glyphs: Map<Char, Array<FloatArray>> = mapOf(
        'A' to arrayOf(s(0f,10f, 0f,3f, 3f,0f, 6f,3f, 6f,10f), s(0f,6f, 6f,6f)),
        'B' to arrayOf(s(0f,0f, 0f,10f), s(0f,0f, 4f,0f, 6f,2f, 6f,3f, 4f,5f, 0f,5f),
                       s(4f,5f, 6f,7f, 6f,8f, 4f,10f, 0f,10f)),
        'C' to arrayOf(s(6f,2f, 4f,0f, 2f,0f, 0f,2f, 0f,8f, 2f,10f, 4f,10f, 6f,8f)),
        'D' to arrayOf(s(0f,0f, 0f,10f), s(0f,0f, 4f,0f, 6f,2f, 6f,8f, 4f,10f, 0f,10f)),
        'E' to arrayOf(s(6f,0f, 0f,0f, 0f,10f, 6f,10f), s(0f,5f, 4f,5f)),
        'F' to arrayOf(s(6f,0f, 0f,0f, 0f,10f), s(0f,5f, 4f,5f)),
        'G' to arrayOf(s(6f,2f, 4f,0f, 2f,0f, 0f,2f, 0f,8f, 2f,10f, 4f,10f, 6f,8f, 6f,5f, 3f,5f)),
        'H' to arrayOf(s(0f,0f, 0f,10f), s(6f,0f, 6f,10f), s(0f,5f, 6f,5f)),
        'I' to arrayOf(s(1f,0f, 5f,0f), s(3f,0f, 3f,10f), s(1f,10f, 5f,10f)),
        'J' to arrayOf(s(6f,0f, 6f,8f, 4f,10f, 2f,10f, 0f,8f)),
        'K' to arrayOf(s(0f,0f, 0f,10f), s(6f,0f, 0f,5f), s(2f,4f, 6f,10f)),
        'L' to arrayOf(s(0f,0f, 0f,10f, 6f,10f)),
        'M' to arrayOf(s(0f,10f, 0f,0f, 3f,4f, 6f,0f, 6f,10f)),
        'N' to arrayOf(s(0f,10f, 0f,0f, 6f,10f, 6f,0f)),
        'O' to arrayOf(s(2f,0f, 4f,0f, 6f,2f, 6f,8f, 4f,10f, 2f,10f, 0f,8f, 0f,2f, 2f,0f)),
        'P' to arrayOf(s(0f,10f, 0f,0f, 4f,0f, 6f,2f, 6f,4f, 4f,6f, 0f,6f)),
        'Q' to arrayOf(s(2f,0f, 4f,0f, 6f,2f, 6f,8f, 4f,10f, 2f,10f, 0f,8f, 0f,2f, 2f,0f),
                       s(4f,7f, 6f,10f)),
        'R' to arrayOf(s(0f,10f, 0f,0f, 4f,0f, 6f,2f, 6f,4f, 4f,6f, 0f,6f), s(3f,6f, 6f,10f)),
        'S' to arrayOf(s(6f,2f, 4f,0f, 2f,0f, 0f,2f, 0f,4f, 6f,6f, 6f,8f, 4f,10f, 2f,10f, 0f,8f)),
        'T' to arrayOf(s(0f,0f, 6f,0f), s(3f,0f, 3f,10f)),
        'U' to arrayOf(s(0f,0f, 0f,8f, 2f,10f, 4f,10f, 6f,8f, 6f,0f)),
        'V' to arrayOf(s(0f,0f, 3f,10f, 6f,0f)),
        'W' to arrayOf(s(0f,0f, 1f,10f, 3f,5f, 5f,10f, 6f,0f)),
        'X' to arrayOf(s(0f,0f, 6f,10f), s(6f,0f, 0f,10f)),
        'Y' to arrayOf(s(0f,0f, 3f,5f, 6f,0f), s(3f,5f, 3f,10f)),
        'Z' to arrayOf(s(0f,0f, 6f,0f, 0f,10f, 6f,10f)),
        '0' to arrayOf(s(2f,0f, 4f,0f, 6f,2f, 6f,8f, 4f,10f, 2f,10f, 0f,8f, 0f,2f, 2f,0f),
                       s(0f,8f, 6f,2f)),
        '1' to arrayOf(s(1f,2f, 3f,0f, 3f,10f), s(1f,10f, 5f,10f)),
        '2' to arrayOf(s(0f,2f, 2f,0f, 4f,0f, 6f,2f, 6f,4f, 0f,10f, 6f,10f)),
        '3' to arrayOf(s(0f,0f, 6f,0f, 3f,4f), s(3f,4f, 6f,6f, 6f,8f, 4f,10f, 2f,10f, 0f,8f)),
        '4' to arrayOf(s(5f,10f, 5f,0f, 0f,7f, 6f,7f)),
        '5' to arrayOf(s(6f,0f, 0f,0f, 0f,4f, 4f,4f, 6f,6f, 6f,8f, 4f,10f, 2f,10f, 0f,8f)),
        '6' to arrayOf(s(6f,1f, 4f,0f, 2f,0f, 0f,3f, 0f,8f, 2f,10f, 4f,10f, 6f,8f, 6f,6f, 4f,4f, 0f,5f)),
        '7' to arrayOf(s(0f,0f, 6f,0f, 2f,10f)),
        '8' to arrayOf(s(2f,0f, 4f,0f, 6f,2f, 4f,5f, 2f,5f, 0f,2f, 2f,0f),
                       s(2f,5f, 0f,7f, 0f,8f, 2f,10f, 4f,10f, 6f,8f, 6f,7f, 4f,5f)),
        '9' to arrayOf(s(0f,9f, 2f,10f, 4f,10f, 6f,7f, 6f,2f, 4f,0f, 2f,0f, 0f,2f, 0f,4f, 2f,6f, 6f,5f)),
        '-' to arrayOf(s(1f,5f, 5f,5f)),
        '.' to arrayOf(s(3f,9f, 3f,10f)),
        // A device pass: sentences with an apostrophe rendered a hole. The crystal's own
        // lines are full of them - "You don't get to stop.", "They're all still in here."
        // A short stroke at cap height, leaning the way a typed apostrophe does.
        '\'' to arrayOf(s(3f,0f, 2f,3f)),
        ':' to arrayOf(s(3f,3f, 3f,4f), s(3f,7f, 3f,8f)),
        '?' to arrayOf(s(0f,2f, 2f,0f, 4f,0f, 6f,2f, 6f,3f, 3f,6f, 3f,7f), s(3f,9f, 3f,10f)),
        '!' to arrayOf(s(3f,0f, 3f,7f), s(3f,9f, 3f,10f)),
        '¥' to arrayOf(s(0f,0f, 3f,4f, 6f,0f), s(3f,4f, 3f,10f), s(1f,5f, 5f,5f), s(1f,7f, 5f,7f)),
        ' ' to arrayOf()
    )

    /** Strokes for [ch], or null if the face has no such glyph. Case-insensitive. */
    fun glyph(ch: Char): Array<FloatArray>? = glyphs[ch.uppercaseChar()]

    /** Rendered width of [text] at a given cap height. Monospaced, so it is exact. */
    fun width(text: String, size: Float): Float = text.length * ADVANCE * (size / EM)

    /**
     * The largest size at which [text] still fits [avail], never exceeding [cap].
     *
     * Lives here, beside [width], because it is the same arithmetic read backwards — and
     * because this file has no Android in it, which is what lets it be tested. The caller
     * that needed it is a renderer whose Paint fields cannot be constructed under plain
     * JUnit at all.
     *
     * [width] is linear in size, so one measurement at [cap] gives the exact scale factor.
     * Returns [cap] untouched when it already fits, so a roomy column is not penalised for
     * a narrow one's sake.
     *
     * **Why this exists.** A device pass found the high score board clipping. Its text was
     * sized from `lineHeight` — `reelHeight / 7`, a VERTICAL measure — while the panel it
     * had to fit is 15% of the machine's WIDTH, so "HIGH SCORE" rendered 386px wide inside
     * a 127px plate. The Exo 2 code it replaced never hit that because it carried a hard
     * `.coerceIn(12f, 20f)`. Deriving the size from the space available is what makes that
     * class of mistake unavailable rather than merely fixed.
     */
    fun fitted(text: String, avail: Float, cap: Float): Float {
        if (avail <= 0f) return cap
        val at = width(text, cap)
        return if (at <= avail) cap else cap * avail / at
    }
}
