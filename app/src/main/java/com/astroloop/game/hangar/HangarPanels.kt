package com.astroloop.game.hangar

import com.astroloop.game.core.LayoutRect

/**
 * Where a landscape page's buttons sit, and where the panel they open goes.
 *
 * A button is card-sized and carries one icon. Opening its panel turns it into the panel's TAB:
 * it slides just outside the panel's outer edge and lights up, and pressing it closes — so "press
 * it again to close" survives on screens where the panel covers the button's home. The slide is a
 * clamp, not a branch: a panel that never reaches the button leaves it exactly where it was.
 *
 * Since the owner's 2026-09-20 pass the clamp is a SAFETY NET rather than the normal case. The
 * buttons sit [EDGE_FRACTION] of the safe width in from their own edge, which on every profile in
 * the spec is already outside the reach of the widest panel that page can open — so a button
 * stays where it is and the tab lights up in place, instead of jumping sideways on the frame the
 * panel starts fading in. On a rotated Pixel 9 Pro the shop's 5+4 board reaches x 1645.7 and needs
 * the stack no further right than 1675.73; the stack's left edge sits at 1720.24, clearing it by
 * 44.51. The crew board reaches 730.81 and needs its button left of 483.61; it sits at 300.96.
 * `HangarRenderer.drawPanelLayer` still clamps EVERY button on the open panel's page rather than
 * just the active one — a wider board would otherwise slide SHOP's own tab clear and leave SLOT
 * sitting underneath it — and `HangarPanelsTest` pins both the clearance and the clamp, so a
 * future panel that does grow into a button pushes it rather than drawing over it.
 *
 * The panel anchors to the room's side of the screen (crew right, shop left) rather than the
 * centre, because that is what leaves the tab a margin to live in. Centring a 1343-wide crew
 * panel on a 2142 screen would push the tab off the left edge.
 */
object HangarPanels {

    enum class Panel { CREW, SHOP, SLOT }

    /** Horizontal padding inside a panel box. */
    const val PAD_H = 24f

    /**
     * Vertical padding inside a panel box.
     *
     * This was chosen when it decided the shop's arrangement, and it no longer does. The figure
     * it was measured against — the shop's 3x3 board being the portrait content width less 32,
     * against a landscape screen whose HEIGHT is the portrait width — ignored the nav row drawn
     * ON TOP of the panel. `HangarRenderer.panelAvailableHeight` is the binding constraint now:
     * it caps a panel's content so the box, always centred on `safe.centerY`, cannot reach the
     * nav row's tap band ([HangarMetrics.navBandTop]). Under that cap a rotated phone shows 5+4
     * whatever this is set to, and a 16:9 screen, TV or tablet shows 3x3 — every profile in the
     * spec arranges the same at 24 as at 12.
     *
     * What it still controls: the padding between a panel's content and its own box edge, and —
     * since `panelAvailableHeight` subtracts it from both the nav-band and the safe-area
     * clearances — the margin the CREW board has to clear. CREW's 6x2 board sits about 100 units
     * inside that budget on a rotated Pixel 9 Pro, so this is the number that margin is measured
     * against, not one the shop's arrangement turns on. See the spec and its 2026-09-19
     * correction.
     */
    const val PAD_V = 12f

    /** Gap between a panel box and the screen edge it hugs. */
    const val SCREEN_EDGE = 20f

    /** Gap between a panel box and its tab. */
    const val TAB_GAP = 30f

    /** Gap between the shop page's two stacked buttons. */
    const val STACK_GAP = 40f

    /**
     * How far in from its own side of the safe area a button's NEAR edge sits, as a fraction of
     * the safe width.
     *
     * Owner, 2026-09-20: the buttons used to be centred a third and two thirds across, which put
     * the crew button's left edge inside the reach of its own panel — so opening the roster slid
     * the button 122 units left and it visibly jumped. At a tenth of the safe width in from the
     * edge, every profile in the spec clears [tabRect]'s clamp with margin to spare and the
     * button stays put through the fade. It is an anchor, not a guarantee: the clamp is still
     * live, and a panel wide enough to reach the button would still push it clear rather than
     * draw over it.
     *
     * Measured against `safe`, not the raw screen, for the reason [panelBox] hugs `safe` too — a
     * rotated device's cutout sits on a side edge, and a fraction of the raw width would put the
     * crew button under it on exactly the devices this layer exists for.
     */
    const val EDGE_FRACTION = 0.10f

    /** Peak alpha (0..255) of the full-screen scrim behind an open panel. */
    const val SCRIM_MAX = 0xB0

    /**
     * Alpha (0..[SCRIM_MAX]) of the full-screen scrim, at [fade] (0 shut .. 1 fully open).
     *
     * A pure seam so the fade curve is testable without a Canvas: `HangarRendererTest`-style
     * pixel inspection can't tell "the scrim's alpha tracks fade" from "the scrim is hardcoded
     * to some fixed value that happens to look right at fade==1", which is exactly the gap
     * A code review found — hardcoding this to a constant fails nothing in
     * a suite that never renders at a partial fade.
     */
    fun panelScrimAlpha(fade: Float): Int = (SCRIM_MAX * fade).toInt().coerceIn(0, SCRIM_MAX)

    /**
     * Alpha (0..255) of the canvas layer that fades the panel box and its contents as one unit.
     * Same rationale as [panelScrimAlpha].
     */
    fun panelLayerAlpha(fade: Float): Int = (255f * fade).toInt().coerceIn(0, 255)

    fun pageOf(panel: Panel): Int = if (panel == Panel.CREW) 0 else 2

    /** The pages that own buttons at all, in page order. The launchpad owns none. */
    val PANEL_PAGES = listOf(0, 2)

    fun panelsOn(page: Int): List<Panel> = when (page) {
        0 -> listOf(Panel.CREW)
        2 -> listOf(Panel.SHOP, Panel.SLOT)
        else -> emptyList()
    }

    /**
     * Where a button sits with its panel shut: [EDGE_FRACTION] of the safe width in from its own
     * side — the crew button from the left, the shop stack from the right.
     *
     * Each is anchored by its NEAR edge, so the two pages are mirror images of each other however
     * wide the card turns out to be. Anchoring the centre instead would leave the gutter varying
     * with the card width, which is the one thing about a card that is allowed to differ between
     * devices.
     */
    fun buttonHome(
        panel: Panel, safe: LayoutRect, cardW: Float, cardH: Float
    ): LayoutRect {
        val inset = safe.width * EDGE_FRACTION
        val left = if (panel == Panel.CREW) safe.left + inset
        else safe.right - inset - cardW
        val cx = left + cardW / 2f
        val cy = when (panel) {
            Panel.CREW -> safe.centerY
            Panel.SHOP -> safe.centerY - (cardH + STACK_GAP) / 2f
            Panel.SLOT -> safe.centerY + (cardH + STACK_GAP) / 2f
        }
        return LayoutRect(cx - cardW / 2f, cy - cardH / 2f, cx + cardW / 2f, cy + cardH / 2f)
    }

    /**
     * The clear space between a page's buttons and its own room — the distance the page travels
     * on a swipe before the room arrives at the buttons.
     *
     * Owner, 2026-09-20: "I want the buttons to be completely gone once the bar hits the button
     * (or the shop on the other side)." The buttons sit in the starfield beside their room, on
     * the far side from the shipyard, so a swipe slides the room straight at them — and a fade
     * measured as a flat fraction of the stride left them still half lit as the bar arrived.
     * This is the distance that fade has to finish in, so the button is gone by the moment the
     * two would meet.
     *
     * Which side the room comes from follows [buttonHome]'s own branch: CREW sits LEFT of a room
     * that hugs the right edge, so the room's left edge closes on the button's right; SHOP and
     * SLOT sit RIGHT of a room that hugs the left, so its right edge closes on their left. The
     * page's own room is always the binding one — on a rotated Pixel 9 Pro the bar reaches the
     * crew button after 0.31 of a stride and the incoming full-width launchpad only after 0.76,
     * which is why the owner sees the bar do it.
     *
     * Never negative: a button overlapping its own room at rest is not a state any profile in the
     * spec reaches, and `PanelButtonSwipeFadeTest` pins the real gaps well clear of zero —
     * but a zero here would divide, so it is floored rather than trusted.
     */
    fun buttonRoomGap(panel: Panel, roomLeft: Float, roomRight: Float, button: LayoutRect): Float {
        val gap = if (panel == Panel.CREW) roomLeft - button.right else button.left - roomRight
        return gap.coerceAtLeast(MIN_ROOM_GAP)
    }

    /**
     * Floor for [buttonRoomGap]. Only a guard against dividing by zero on a profile where a
     * button and its room overlap at rest; large enough that the fade is still a fade there and
     * not a blink.
     */
    const val MIN_ROOM_GAP = 60f

    /**
     * The panel's outer box: its content plus padding, anchored to the room's side.
     *
     * Anchored to [safe], not the raw screen box: in landscape a display cutout can sit on a
     * side edge, and an edge-anchored panel must hug the safe area the same way the combat HUD
     * and the yen counter do, or its far column ends up underneath the cutout.
     */
    fun panelBox(
        panel: Panel, contentW: Float, contentH: Float, safe: LayoutRect
    ): LayoutRect {
        val w = contentW + PAD_H * 2f
        val h = contentH + PAD_V * 2f
        val left = if (panel == Panel.CREW) safe.right - SCREEN_EDGE - w else safe.left + SCREEN_EDGE
        val top = safe.centerY - h / 2f
        return LayoutRect(left, top, left + w, top + h)
    }

    /**
     * The button while its panel is open: pushed just clear of the panel's outer edge, or left
     * at home if the panel never reaches it.
     *
     * Degenerate case, unreachable on every profile in the spec: if this would put the tab
     * off-screen, the caller docks it onto the panel's outer edge and draws it above the panel.
     */
    fun tabRect(panel: Panel, home: LayoutRect, box: LayoutRect): LayoutRect {
        val left = if (panel == Panel.CREW) minOf(home.left, box.left - TAB_GAP - home.width)
        else maxOf(home.left, box.right + TAB_GAP)
        return LayoutRect(left, home.top, left + home.width, home.bottom)
    }
}
