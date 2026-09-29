package com.astroloop.game.hangar

import com.astroloop.game.core.ScreenLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codex's rows, against a display cutout.
 *
 * The yen counter drew itself at a raw `screenHeight`-relative position and ended up inside the
 * notch when targetSdk 36 made edge-to-edge mandatory. The codex divides the screen into twelve
 * rows the same raw way, so its first row lands in the same place.
 */
class CodexLayoutTest {

    private val notch = ScreenLayout.compute(1080f, 2400f, insetTop = 128f)
    private val plain = ScreenLayout.compute(1080f, 2400f)

    @Test
    fun `the rows start below a cutout`() {
        val (top, _) = HangarRenderer.codexRowSpan(notch)

        assertTrue("the first row must clear the 128px notch, was $top", top >= 128f)
    }

    @Test
    fun `the rows end above the bottom inset`() {
        val (_, bottom) = HangarRenderer.codexRowSpan(notch)

        assertTrue("was $bottom", bottom <= 2400f)
    }

    @Test
    fun `a screen without a cutout keeps the old top`() {
        // safe == full here, so the first row must sit exactly where it always did.
        val (top, _) = HangarRenderer.codexRowSpan(plain)

        assertEquals(30f, top, 0.01f)
    }

    @Test
    fun `the hint sits below the rows, not on top of them`() {
        // Drawn at the band's own bottom, it landed across the last row's labels — the rows divide
        // the whole band, so the band's bottom edge IS the last row.
        val (_, rowsBottom) = HangarRenderer.codexRowSpan(plain)

        assertTrue(
            "the hint must clear the rows, hint=${HangarRenderer.codexHintY(plain)} rows end $rowsBottom",
            HangarRenderer.codexHintY(plain) > rowsBottom
        )
    }

    @Test
    fun `the hint stays inside the safe area`() {
        // Below the rows, but never under a gesture bar or off the bottom of the screen.
        assertTrue(HangarRenderer.codexHintY(notch) <= notch.safe.bottom)
        assertTrue(HangarRenderer.codexHintY(plain) <= plain.safe.bottom)
    }
}
