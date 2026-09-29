package com.astroloop.game.hangar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sizes here are the computed profile figures from the spec, so a change in the rule shows
 * up as a change in a real device's arrangement rather than an abstract one.
 */
class PanelGridTest {

    // Pixel 9 Pro with a cutout: card 217.20 x 325.90, landscape grid space 927.8 - 24 padding.
    private val phoneCardW = 217.20f
    private val phoneCardH = 325.90f

    // 16:9 / TV: card 228.00 x 342.61, landscape grid space 1168.8 - 24.
    private val tvCardW = 228.00f
    private val tvCardH = 342.61f

    @Test
    fun `a phone cannot fit four by three, so twelve pilots go six by two`() {
        val a = PanelGrid.arrange(
            count = 12, itemW = phoneCardW, itemH = phoneCardH, gap = 8f,
            preferredCols = 4, availableWidth = 2142f - 40f, availableHeight = 927.8f - 24f
        )
        assertEquals(6, a.cols)
        assertEquals(2, a.rows)
        assertEquals(6 * phoneCardW + 5 * 8f, a.width, 0.01f)
        assertEquals(2 * phoneCardH + 8f, a.height, 0.01f)
    }

    @Test
    fun `a TV keeps the portrait four by three`() {
        val a = PanelGrid.arrange(
            count = 12, itemW = tvCardW, itemH = tvCardH, gap = 8f,
            preferredCols = 4, availableWidth = 2142f - 40f, availableHeight = 1168.8f - 24f
        )
        assertEquals(4, a.cols)
        assertEquals(3, a.rows)
    }

    @Test
    fun `nine shop tiles stay three by three wherever they fit`() {
        val a = PanelGrid.arrange(
            count = 9, itemW = 288.27f, itemH = 288.27f, gap = 10f,
            preferredCols = 3, availableWidth = 2142f - 40f, availableHeight = 927.8f - 24f
        )
        assertEquals(3, a.cols)
        assertEquals(3, a.rows)
    }

    @Test
    fun `nine shop tiles fall back to five plus four when three rows will not fit`() {
        val a = PanelGrid.arrange(
            count = 9, itemW = 302.67f, itemH = 302.67f, gap = 10f,
            preferredCols = 3, availableWidth = 2142f - 40f, availableHeight = 800f
        )
        assertEquals(5, a.cols)
        assertEquals(2, a.rows)
    }

    @Test
    fun `the short row is centred under the full one`() {
        val a = PanelGrid.arrange(
            count = 9, itemW = 100f, itemH = 100f, gap = 10f,
            preferredCols = 3, availableWidth = 2000f, availableHeight = 260f
        )
        val rects = PanelGrid.rects(a, 9, originX = 0f, originY = 0f, itemW = 100f, itemH = 100f, gap = 10f)
        assertEquals(5, a.cols)
        // Full row spans 5*100 + 4*10 = 540. Short row spans 4*100 + 3*10 = 430, so it starts
        // (540 - 430) / 2 = 55 in.
        assertEquals(0f, rects[0].left, 0.01f)
        assertEquals(55f, rects[5].left, 0.01f)
    }

    @Test
    fun `a grid never reduces below one row`() {
        val a = PanelGrid.arrange(
            count = 4, itemW = 100f, itemH = 400f, gap = 10f,
            preferredCols = 2, availableWidth = 2000f, availableHeight = 50f
        )
        assertEquals(1, a.rows)
        assertEquals(4, a.cols)
    }

    @Test
    fun `a reduction that would overflow the width is refused`() {
        // Two rows would need 6 columns; the width only has room for 4, so three rows stand
        // even though they overflow the height. Clipping beats a grid running off the screen.
        val a = PanelGrid.arrange(
            count = 12, itemW = 300f, itemH = 300f, gap = 10f,
            preferredCols = 4, availableWidth = 1300f, availableHeight = 500f
        )
        assertEquals(4, a.cols)
        assertEquals(3, a.rows)
    }

    @Test
    fun `indexAt finds the rect a point is inside and nothing in the gaps`() {
        val a = PanelGrid.arrange(12, 100f, 100f, 10f, 4, 2000f, 1000f)
        val rects = PanelGrid.rects(a, 12, 0f, 0f, 100f, 100f, 10f)
        assertEquals(0, PanelGrid.indexAt(rects, 50f, 50f))
        assertEquals(1, PanelGrid.indexAt(rects, 160f, 50f))
        assertNull(PanelGrid.indexAt(rects, 105f, 50f))
    }

    // --- Degenerate-input edge cases ---------------------------------------
    //
    // build() forces rows = ceil(count/c).coerceAtLeast(1), so whenever that first build already
    // lands on rows = 1, the while loop's `best.rows > 1` guard is false on entry and no
    // iteration runs at all — the availableHeight/availableWidth arguments below are deliberately
    // hostile (far too small to hold anything) to prove that no reduction is attempted, not that
    // one succeeds.

    @Test
    fun `count of zero returns a single row sized for the preferred columns, untouched by the loop`() {
        // build(0, 4, ...): rows = ceil(0/4).coerceAtLeast(1) = 1, cols = preferredCols = 4.
        // rows is 1 from the very first build, so `best.rows > 1` is false and the loop never
        // runs — availableHeight = 5 is far smaller than the resulting height (100) to prove
        // that no reduction is attempted despite the overflow.
        val a = PanelGrid.arrange(
            count = 0, itemW = 100f, itemH = 100f, gap = 10f, preferredCols = 4,
            availableWidth = 2000f, availableHeight = 5f
        )
        assertEquals(4, a.cols)
        assertEquals(1, a.rows)
        assertEquals(4 * 100f + 3 * 10f, a.width, 0.01f)
        assertEquals(100f, a.height, 0.01f)
        assertEquals(0, PanelGrid.rects(a, 0, 0f, 0f, 100f, 100f, 10f).size)
    }

    @Test
    fun `count of one keeps the preferred column count and never enters the reduction loop`() {
        // build(1, 4, ...): rows = ceil(1/4).coerceAtLeast(1) = 1, same reasoning as count = 0 —
        // cols stays at the preferred 4 (it is not clamped down to the item count), so the single
        // item is centred under a virtual 4-wide row rather than filling a 1x1 grid.
        val a = PanelGrid.arrange(
            count = 1, itemW = 100f, itemH = 100f, gap = 10f, preferredCols = 4,
            availableWidth = 2000f, availableHeight = 5f
        )
        assertEquals(4, a.cols)
        assertEquals(1, a.rows)
        assertEquals(4 * 100f + 3 * 10f, a.width, 0.01f)
        val rects = PanelGrid.rects(a, 1, originX = 0f, originY = 0f, itemW = 100f, itemH = 100f, gap = 10f)
        // Row width for the lone item is 100; centred under a.width (430) puts its left edge at
        // (430 - 100) / 2 = 165.
        assertEquals(1, rects.size)
        assertEquals(165f, rects[0].left, 0.01f)
    }

    @Test
    fun `availableHeight smaller than a single item still converges to one row and stops`() {
        // Initial build(6, 3, ...): cols=3, rows=ceil(6/3)=2, height = 2*200+10 = 410.
        // Iteration 1: target rows=1, next=build(6, ceil(6/1)=6, ...) -> cols=6, rows=1,
        // width=6*100+5*10=650, height=200. width(650) fits availableWidth(2000) and
        // rows(1) < best.rows(2), so it's accepted. Now best.rows=1, so the loop's `rows > 1`
        // guard stops it — even though height (200) still exceeds availableHeight (50), which is
        // smaller than a single item's own height (200): there is nowhere further to go.
        val a = PanelGrid.arrange(
            count = 6, itemW = 100f, itemH = 200f, gap = 10f, preferredCols = 3,
            availableWidth = 2000f, availableHeight = 50f
        )
        assertEquals(6, a.cols)
        assertEquals(1, a.rows)
        assertEquals(6 * 100f + 5 * 10f, a.width, 0.01f)
        assertEquals(200f, a.height, 0.01f)
    }

    @Test
    fun `availableWidth smaller than a single item refuses the first reduction and returns the original`() {
        // Initial build(8, 2, ...): cols=2, rows=ceil(8/2)=4, width=2*500+10=1010, height=430.
        // Iteration 1 would target rows=3: next=build(8, ceil(8/3)=3, ...) -> cols=3,
        // width=3*500+2*10=1520. availableWidth is 100 (smaller than one 500-wide item), so
        // 1520 > 100 and the width-refusal `break` fires immediately — the original,
        // unreduced arrangement is returned even though its own height (430) also overflows.
        val a = PanelGrid.arrange(
            count = 8, itemW = 500f, itemH = 100f, gap = 10f, preferredCols = 2,
            availableWidth = 100f, availableHeight = 50f
        )
        assertEquals(2, a.cols)
        assertEquals(4, a.rows)
        assertEquals(2 * 500f + 1 * 10f, a.width, 0.01f)
        assertEquals(4 * 100f + 3 * 10f, a.height, 0.01f)
    }

    @Test
    fun `preferredCols greater than count resolves to one row immediately and centres it`() {
        // build(3, 10, ...): cols=10, rows=ceil(3/10)=1 from the first build, so the loop never
        // runs. a.width is the full 10-column virtual width (1090), not the 3 items' own width.
        val a = PanelGrid.arrange(
            count = 3, itemW = 100f, itemH = 100f, gap = 10f, preferredCols = 10,
            availableWidth = 2000f, availableHeight = 500f
        )
        assertEquals(10, a.cols)
        assertEquals(1, a.rows)
        assertEquals(10 * 100f + 9 * 10f, a.width, 0.01f)
        // Row width for 3 items = 3*100 + 2*10 = 320. Centred under a.width (1090) starts the
        // row at (1090 - 320) / 2 = 385; the three items follow at 110 (itemW + gap) apart.
        val rects = PanelGrid.rects(a, 3, originX = 0f, originY = 0f, itemW = 100f, itemH = 100f, gap = 10f)
        assertEquals(385f, rects[0].left, 0.01f)
        assertEquals(495f, rects[1].left, 0.01f)
        assertEquals(605f, rects[2].left, 0.01f)
    }

    // --- Multi-iteration loop coverage (review Minor finding) ---------------------------------

    @Test
    fun `two successive row reductions both apply before the loop stops`() {
        // Real TV/16:9 card size (228 x 342.61, as used above) at the default 12-pilot preferred
        // 4 columns, but on a hypothetical very short, very wide viewport (a 4000-wide, 300-tall
        // grid area) that needs two row-reductions before it fits under the width ceiling.
        //
        // Initial build(12, 4, ...): cols=4, rows=ceil(12/4)=3, height=3*342.61+2*8=1043.83.
        // Iteration 1: target rows=2, next=build(12, ceil(12/2)=6, ...) -> cols=6, rows=2,
        //   width=6*228+5*8=1408, height=2*342.61+8=693.22.
        //   1408 <= 4000 and rows(2) < best.rows(3) -> accepted, best.rows is now 2.
        // Iteration 2: height(693.22) still > 300 and rows(2) > 1, so it continues.
        //   target rows=1, next=build(12, ceil(12/1)=12, ...) -> cols=12, rows=1,
        //   width=12*228+11*8=2824, height=342.61.
        //   2824 <= 4000 and rows(1) < best.rows(2) -> accepted, best.rows is now 1.
        // Loop's `rows > 1` guard now stops it. Two reductions ran (3 -> 2 -> 1), the first
        // multi-iteration case in this suite.
        val a = PanelGrid.arrange(
            count = 12, itemW = 228f, itemH = 342.61f, gap = 8f, preferredCols = 4,
            availableWidth = 4000f, availableHeight = 300f
        )
        assertEquals(12, a.cols)
        assertEquals(1, a.rows)
        assertEquals(12 * 228f + 11 * 8f, a.width, 0.01f)
        assertEquals(342.61f, a.height, 0.01f)
    }
}
