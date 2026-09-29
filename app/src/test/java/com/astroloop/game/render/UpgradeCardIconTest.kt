package com.astroloop.game.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An upgrade card's icon against the card it has to share with text.
 *
 * Cards are 0.30 x 0.42 of the content rect, so they are 288x900 in portrait and 643x403 once
 * content transposes. Sizing the icon from the card's WIDTH is harmless on a tall card and a
 * runaway on a wide one.
 */
class UpgradeCardIconTest {

    /**
     * Where the description's first line starts, mirroring renderCard's stack:
     * `cardPadding` 24, then the icon, then +10 to the icon centre offset, then the name at +28,
     * the level badge at +32 and the description at +36.
     */
    private fun descriptionTop(cardW: Float, cardH: Float): Float =
        24f + UpgradeSelectionRenderer.cardIconSize(cardW, cardH) + 10f + 28f + 32f + 36f

    /** Two wrapped lines at the 26px line step renderCard uses. */
    private val twoLinesOfDescription = 52f

    @Test
    fun `a portrait card is exactly what shipped`() {
        // The short side is the width on a 288x900 card, so this must be bit-identical.
        assertEquals(288f * 0.45f, UpgradeSelectionRenderer.cardIconSize(288f, 900f), 0f)
        assertEquals(288f * 0.25f, UpgradeSelectionRenderer.evolutionIconSize(288f, 900f), 0f)
    }

    @Test
    fun `the shipped rule would have overflowed a landscape card`() {
        // Why this change exists: 45% of 643 is a 289px icon in a 403px card.
        val widthBasedIcon = 643f * 0.45f
        val widthBasedDescriptionTop = 24f + widthBasedIcon + 10f + 28f + 32f + 36f
        assertTrue(
            "the old rule should overflow, or this test is not testing the bug",
            widthBasedDescriptionTop + twoLinesOfDescription > 403f
        )
    }

    @Test
    fun `a landscape card still has room for name, level and description`() {
        val h = 403f
        assertTrue(
            "description starts at ${descriptionTop(643f, h)} in a $h card",
            descriptionTop(643f, h) + twoLinesOfDescription <= h
        )
    }
}
