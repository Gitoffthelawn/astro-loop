package com.astroloop.game.hangar

import com.astroloop.game.core.LayoutRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HangarPanelsTest {

    private val screenW = 2142f
    private val safe = LayoutRect(0f, 0f, 2142f, 927.8f)
    private val cardW = 217.20f
    private val cardH = 325.90f

    @Test
    fun `the crew button's left edge sits a tenth of the safe width in, vertically centred`() {
        val home = HangarPanels.buttonHome(HangarPanels.Panel.CREW, safe, cardW, cardH)
        // By hand: safe.width 2142 * 0.10 = 214.2, measured from safe.left (0 here).
        assertEquals(214.2f, home.left, 0.01f)
        assertEquals(safe.centerY, home.centerY, 0.01f)
        assertEquals(cardW, home.width, 0.01f)
        assertEquals(cardH, home.height, 0.01f)
    }

    @Test
    fun `the shop buttons stack a tenth of the safe width in from the right, with a gap`() {
        val shop = HangarPanels.buttonHome(HangarPanels.Panel.SHOP, safe, cardW, cardH)
        val slot = HangarPanels.buttonHome(HangarPanels.Panel.SLOT, safe, cardW, cardH)
        // Mirror image of the crew button: 2142 - 214.2 = 1927.8, and the card hangs off its
        // RIGHT edge, so left = 1927.8 - 217.2 = 1710.6.
        assertEquals(1927.8f, shop.right, 0.01f)
        assertEquals(1927.8f, slot.right, 0.01f)
        assertEquals(1710.6f, shop.left, 0.01f)
        assertEquals(HangarPanels.STACK_GAP, slot.top - shop.bottom, 0.01f)
        assertEquals(safe.centerY, (shop.top + slot.bottom) / 2f, 0.01f)
    }

    /**
     * The two pages are mirror images, and each is measured from ITS OWN safe edge rather than
     * from the screen — so a cutout on one side moves the button on that side only.
     *
     * Discriminating: it fails against a `screenWidth * 0.10f` formulation (which would put the
     * crew button at 214.2 here too, ignoring the 96.4 the cutout takes) and against anchoring
     * the card's CENTRE instead of its near edge (which would leave the gutter varying with the
     * card width, and puts the crew button's left edge at 192.5, not 301).
     */
    @Test
    fun `each button is measured from its own safe edge, so a side cutout moves only its side`() {
        // A rotated Pixel 9 Pro: the 128px cutout is 96.4 design units on the LEFT.
        val insetSafe = LayoutRect(96.405f, 0f, 2142f, 964.05f)
        val inset = insetSafe.width * HangarPanels.EDGE_FRACTION // 2045.595 * 0.10 = 204.5595

        val crew = HangarPanels.buttonHome(HangarPanels.Panel.CREW, insetSafe, cardW, cardH)
        assertEquals("crew is pushed right by the cutout", 96.405f + inset, crew.left, 0.01f)
        assertEquals("and lands at the measured 300.96", 300.96f, crew.left, 0.01f)

        val shop = HangarPanels.buttonHome(HangarPanels.Panel.SHOP, insetSafe, cardW, cardH)
        assertEquals("the far side is unaffected by a near-side cutout", 2142f - inset, shop.right, 0.01f)
        assertEquals("and lands at the measured 1720.24", 1720.24f, shop.left, 0.01f)
    }

    @Test
    fun `the crew panel hugs the right edge and the shop panel the left`() {
        val crew = HangarPanels.panelBox(HangarPanels.Panel.CREW, 1343.2f, 659.8f, safe)
        assertEquals(screenW - HangarPanels.SCREEN_EDGE, crew.right, 0.01f)
        val shop = HangarPanels.panelBox(HangarPanels.Panel.SHOP, 884.8f, 884.8f, safe)
        assertEquals(HangarPanels.SCREEN_EDGE, shop.left, 0.01f)
    }

    @Test
    fun `the crew panel hugs the safe edge, not the raw screen edge, when a cutout sits on the right`() {
        // A rotated Pixel 9 Pro's 128px cutout is 96.4 design units; safe.right sits inside the
        // 2142-wide screen box by that much. By hand: w = 1343.2 + PAD_H*2 = 1391.2, so
        // left = safe.right - SCREEN_EDGE - w = 2045.6 - 20 - 1391.2 = 634.4, right = 2025.6.
        val insetSafe = LayoutRect(0f, 0f, 2142f - 96.4f, 927.8f)
        val crew = HangarPanels.panelBox(HangarPanels.Panel.CREW, 1343.2f, 659.8f, insetSafe)
        assertEquals(insetSafe.right - HangarPanels.SCREEN_EDGE, crew.right, 0.01f)
        assertTrue(
            "panel right edge ${crew.right} must clear the cutout at ${insetSafe.right}",
            crew.right <= insetSafe.right
        )
        assertTrue(
            "panel must not still hug the raw screen edge at ${screenW - HangarPanels.SCREEN_EDGE}",
            crew.right < screenW - HangarPanels.SCREEN_EDGE
        )
    }

    @Test
    fun `a panel box is its content plus padding, centred in safe`() {
        val box = HangarPanels.panelBox(HangarPanels.Panel.CREW, 1343.2f, 659.8f, safe)
        assertEquals(1343.2f + HangarPanels.PAD_H * 2f, box.width, 0.01f)
        assertEquals(659.8f + HangarPanels.PAD_V * 2f, box.height, 0.01f)
        assertEquals(safe.centerY, box.centerY, 0.01f)
    }

    /**
     * The point of the owner's 2026-09-20 move: with the button a tenth of the way in, the crew
     * panel it opens no longer reaches it, so the tab lights up exactly where the button already
     * was instead of jumping 122 units left on the frame the fade starts.
     *
     * The clearance is asserted as a NUMBER as well as a no-op, so a future panel that grows
     * toward the button shows up here as a shrinking margin before it starts shoving the button
     * again in front of the player.
     */
    @Test
    fun `the real crew panel does not reach its button, so the tab lights up in place`() {
        val home = HangarPanels.buttonHome(HangarPanels.Panel.CREW, safe, cardW, cardH)
        val box = HangarPanels.panelBox(HangarPanels.Panel.CREW, 1343.2f, 659.8f, safe)
        val tab = HangarPanels.tabRect(HangarPanels.Panel.CREW, home, box)
        assertEquals("the button does not move when its panel opens", home.left, tab.left, 0.01f)
        // box.left 730.8 - TAB_GAP 30 - cardW 217.2 = 483.6 is the furthest right the tab could
        // sit; the button is at 214.2, so the clamp has 269.4 units of slack to eat first.
        assertEquals(269.4f, (box.left - HangarPanels.TAB_GAP - cardW) - home.left, 0.01f)
        assertTrue("the tab must not overlap its panel", tab.right <= box.left)
        assertEquals("the tab keeps its row", home.top, tab.top, 0.01f)
    }

    /**
     * The clamp is a safety net now rather than the normal case, so it needs its own guard: with
     * the real panels it never fires, and a broken clamp would sit undetected until some future
     * panel grew wide enough to need it.
     *
     * 1800 units of content is wider than anything the hangar opens today — it exists to drive
     * the branch. Verified by hand: box.left = 2142 - 20 - (1800 + 48) = 274, so the tab must
     * land at 274 - 30 - 217.2 = 26.8, well left of the button's 214.2 home.
     */
    @Test
    fun `a panel wide enough to reach the button still pushes it clear`() {
        val home = HangarPanels.buttonHome(HangarPanels.Panel.CREW, safe, cardW, cardH)
        val box = HangarPanels.panelBox(HangarPanels.Panel.CREW, 1800f, 659.8f, safe)
        val tab = HangarPanels.tabRect(HangarPanels.Panel.CREW, home, box)
        assertEquals(26.8f, tab.left, 0.01f)
        assertTrue("the clamp actually moved it", tab.left < home.left)
        assertTrue("the tab must not leave the screen", tab.left >= 0f)
        assertTrue("the tab must not overlap its panel", tab.right <= box.left)
        assertEquals("the tab keeps its row", home.top, tab.top, 0.01f)
    }

    /** The mirror of the above, on the side where the clamp pushes RIGHT rather than left. */
    @Test
    fun `a shop panel wide enough to reach the stack still pushes it clear`() {
        val home = HangarPanels.buttonHome(HangarPanels.Panel.SHOP, safe, cardW, cardH)
        val box = HangarPanels.panelBox(HangarPanels.Panel.SHOP, 1800f, 659.8f, safe)
        val tab = HangarPanels.tabRect(HangarPanels.Panel.SHOP, home, box)
        // box.left = SCREEN_EDGE 20, so box.right = 20 + 1848 = 1868; the tab clears it by TAB_GAP.
        assertEquals(1898f, tab.left, 0.01f)
        assertTrue("the clamp actually moved it", tab.left > home.left)
        assertEquals("the tab keeps its row", home.top, tab.top, 0.01f)
    }

    @Test
    fun `a panel that does not reach the button leaves it exactly where it was`() {
        // The shop panel's right edge is ~996; the stack's left edge is at 1710.6. The clamp is a
        // no-op, so the SHOP/SLOT stack stays intact while either panel is open.
        val home = HangarPanels.buttonHome(HangarPanels.Panel.SHOP, safe, cardW, cardH)
        val box = HangarPanels.panelBox(HangarPanels.Panel.SHOP, 884.8f, 884.8f, safe)
        val tab = HangarPanels.tabRect(HangarPanels.Panel.SHOP, home, box)
        assertEquals(home.left, tab.left, 0.01f)
    }

    // =====================================================================================
    // A code review: the fade's alphas are pure seams, testable without a Canvas.
    // Hardcoding either to a fixed value — e.g. always fully opaque — passes every test that
    // never renders at a partial fade; these pin the actual curve directly.
    // =====================================================================================

    @Test
    fun `the panel layer alpha tracks fade linearly from shut to fully open`() {
        assertEquals(0, HangarPanels.panelLayerAlpha(0f))
        assertEquals(127, HangarPanels.panelLayerAlpha(0.5f))
        assertEquals(255, HangarPanels.panelLayerAlpha(1f))
    }

    @Test
    fun `the panel layer alpha clamps rather than overshoots`() {
        assertEquals(0, HangarPanels.panelLayerAlpha(-1f))
        assertEquals(255, HangarPanels.panelLayerAlpha(2f))
    }

    @Test
    fun `the scrim alpha tracks fade linearly up to its own peak, not full opacity`() {
        assertEquals(0, HangarPanels.panelScrimAlpha(0f))
        assertEquals(HangarPanels.SCRIM_MAX / 2, HangarPanels.panelScrimAlpha(0.5f))
        assertEquals(HangarPanels.SCRIM_MAX, HangarPanels.panelScrimAlpha(1f))
        assertTrue("the scrim never reaches full opacity", HangarPanels.SCRIM_MAX < 255)
    }

    @Test
    fun `the scrim alpha clamps rather than overshoots`() {
        assertEquals(0, HangarPanels.panelScrimAlpha(-1f))
        assertEquals(HangarPanels.SCRIM_MAX, HangarPanels.panelScrimAlpha(2f))
    }

    @Test
    fun `pages own the panels they show`() {
        assertEquals(0, HangarPanels.pageOf(HangarPanels.Panel.CREW))
        assertEquals(2, HangarPanels.pageOf(HangarPanels.Panel.SHOP))
        assertEquals(2, HangarPanels.pageOf(HangarPanels.Panel.SLOT))
        assertEquals(listOf(HangarPanels.Panel.CREW), HangarPanels.panelsOn(0))
        assertEquals(emptyList<HangarPanels.Panel>(), HangarPanels.panelsOn(1))
        assertEquals(
            listOf(HangarPanels.Panel.SHOP, HangarPanels.Panel.SLOT),
            HangarPanels.panelsOn(2)
        )
    }
}
