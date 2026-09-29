package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test

/**
 * The high score board's text must fit the panel it is drawn in.
 *
 * A device pass found it clipping — both the plate and the text. The text half was a unit
 * error: the size came from `lineHeight`, which is `reelHeight / 7` and therefore VERTICAL,
 * while the panel it has to fit is 15% of the machine's WIDTH.
 */
class CabinetBoardFitTest {

    @Test fun aSizeThatAlreadyFitsIsLeftAlone() {
        // A roomy panel must not be shrunk for a narrow one's sake.
        assertEquals(20f, CabinetFont.fitted("ABC", 10_000f, 20f), 0.001f)
    }

    @Test fun aSizeThatOverflowsIsScaledUntilItFits() {
        val avail = 100f
        val got = CabinetFont.fitted("HIGH SCORE", avail, 48f)
        assertTrue("must shrink below the cap", got < 48f)
        assertTrue(
            "still ${CabinetFont.width("HIGH SCORE", got)}px against ${avail}px",
            CabinetFont.width("HIGH SCORE", got) <= avail + 0.01f
        )
    }

    @Test fun theRealPanelGeometryFits() {
        // The case that shipped broken. A ~900px machine gives a 15% panel, a 127px plate
        // and lineHeight 93 — at which the old `lineHeight * 0.52f` title rendered 386px
        // wide, overflowing by 259.
        val plate = 127f
        val pad = plate * 0.10f
        val avail = plate - pad * 2f
        val lineHeight = 93f

        val title = CabinetFont.fitted("HIGH SCORE", avail, lineHeight * 0.52f)
        assertTrue("title overflows", CabinetFont.width("HIGH SCORE", title) <= avail + 0.01f)

        // Initials and score are drawn from opposite edges, so what must fit is both plus
        // clearance — six characters of content and one of gap.
        val row = CabinetFont.fitted("0000000", avail, lineHeight * 0.62f)
        val both = CabinetFont.width("FRS", row) + CabinetFont.width("999", row)
        assertTrue("initials and score would meet in the middle", both < avail)
    }

    @Test fun aDegenerateWidthDoesNotProduceNonsense() {
        // A zero-width panel would otherwise divide by a zero-width measurement.
        assertEquals(12f, CabinetFont.fitted("ABC", 0f, 12f), 0.001f)
        assertEquals(12f, CabinetFont.fitted("ABC", -5f, 12f), 0.001f)
    }
}
