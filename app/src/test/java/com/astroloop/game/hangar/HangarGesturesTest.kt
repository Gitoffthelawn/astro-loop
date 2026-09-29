package com.astroloop.game.hangar

import com.astroloop.game.input.Direction
import org.junit.Assert.*
import org.junit.Test

/**
 * Which gesture a moving finger has committed to, and whether a hold survives it.
 *
 * Owner, 2026-08-11: buying an upgrade asks for too much precision. Three things were wrong, and
 * all three came from one line that treated every kind of movement the same way.
 *
 * **The threshold was raw pixels.** 15px, not density units — about 5dp on a 3x phone. Android's
 * own `ViewConfiguration.getScaledTouchSlop()` is 8dp (~24px there), and Apple's
 * `UILongPressGestureRecognizer.allowableMovement` defaults to 10 points (~30px). The hold was
 * being cancelled at roughly half the drift iOS tolerates, and under what Android needs to call
 * something a drag at all. It now comes from the platform, so it scales with the screen.
 *
 * **Vertical drift cancelled a purchase for nothing.** The store page only scrolls sideways — ship
 * drag is a shipyard gesture — so a thumb rolling downward on a tile was cancelling against a
 * gesture that does not exist there. A page swipe now needs *horizontal* movement.
 *
 * **A long press should get a bigger budget than a scroll**, on both platforms, because a finger
 * held still for a second drifts more than one about to flick. Here they were the same number.
 * The hold's budget is now the tile itself: drift as far as you like inside the control you are
 * pressing, which is what both platforms do and what a player expects.
 */
class HangarGesturesTest {

    /** Stand-in for the platform's density-scaled slop; the real one comes from ViewConfiguration. */
    private val slop = 24f

    // ── Page swipe ──────────────────────────────────────────────────────────

    @Test
    fun `horizontal movement past the slop starts a page swipe`() {
        assertTrue(HangarGestures.startsPageSwipe(totalDx = slop + 1f, totalDy = 0f, slop = slop))
    }

    @Test
    fun `movement under the slop commits to nothing`() {
        assertFalse(HangarGestures.startsPageSwipe(totalDx = slop - 1f, totalDy = 0f, slop = slop))
    }

    @Test
    fun `vertical drift alone never starts a page swipe`() {
        // The store page has nothing to scroll vertically, so this must not cancel a purchase.
        assertFalse(HangarGestures.startsPageSwipe(totalDx = 2f, totalDy = 200f, slop = slop))
    }

    @Test
    fun `a diagonal drag that clears the slop sideways is still a page swipe`() {
        assertTrue(HangarGestures.startsPageSwipe(totalDx = slop + 5f, totalDy = slop + 40f, slop = slop))
    }

    // ── Ship drag ───────────────────────────────────────────────────────────

    @Test
    fun `a dominant vertical drag on the ship starts a ship drag`() {
        assertTrue(HangarGestures.startsShipDrag(
            totalDx = 5f, totalDy = slop + 1f, slop = slop, shipDragPossible = true))
    }

    @Test
    fun `vertical movement off the ship starts no ship drag`() {
        assertFalse(HangarGestures.startsShipDrag(
            totalDx = 5f, totalDy = slop + 1f, slop = slop, shipDragPossible = false))
    }

    @Test
    fun `a mostly-horizontal drag on the ship is a page swipe, not a ship drag`() {
        assertFalse(HangarGestures.startsShipDrag(
            totalDx = 100f, totalDy = slop + 1f, slop = slop, shipDragPossible = true))
    }

    // ── The hold's budget is the tile ───────────────────────────────────────

    @Test
    fun `a hold survives any drift that stays on the tile`() {
        // A tile is a third of the screen; the old 15px budget used a fraction of it.
        assertTrue(HangarGestures.holdSurvivesDrift(
            x = 190f, y = 10f, left = 0f, top = 0f, right = 200f, bottom = 200f))
        assertTrue(HangarGestures.holdSurvivesDrift(
            x = 100f, y = 199f, left = 0f, top = 0f, right = 200f, bottom = 200f))
    }

    @Test
    fun `a hold ends when the finger leaves the tile it started on`() {
        assertFalse(HangarGestures.holdSurvivesDrift(
            x = 201f, y = 100f, left = 0f, top = 0f, right = 200f, bottom = 200f))
        assertFalse(HangarGestures.holdSurvivesDrift(
            x = 100f, y = -1f, left = 0f, top = 0f, right = 200f, bottom = 200f))
    }

    @Test
    fun `the hold tolerates far more drift than the swipe slop`() {
        // The point of the change: pressing a button should not be a test of steadiness.
        val tolerated = 100f
        assertTrue("a tile is much wider than the swipe slop", tolerated > slop)
        assertTrue(HangarGestures.holdSurvivesDrift(
            x = tolerated, y = tolerated, left = 0f, top = 0f, right = 200f, bottom = 200f))
    }

    // ── The intro cinematic's one hop ───────────────────────────────────────

    @Test
    fun `the intro allows only the hop a swipe allows`() {
        // The swipe release permits currentPage++ from the bar while the cinematic holds, and
        // nothing else. A controller gets exactly that and no more.
        assertTrue(HangarGestures.introPageHopAllowed(introCinematic = true, currentPage = 0, target = 1))
        assertFalse(HangarGestures.introPageHopAllowed(introCinematic = true, currentPage = 1, target = 2))
        assertFalse(HangarGestures.introPageHopAllowed(introCinematic = true, currentPage = 1, target = 0))
    }

    @Test
    fun `outside the intro every page change is allowed`() {
        assertTrue(HangarGestures.introPageHopAllowed(introCinematic = false, currentPage = 2, target = 0))
    }

    // ── The ship carousel ───────────────────────────────────────────────────

    @Test
    fun `left and right work the carousel while the ship is focused`() {
        assertEquals(1, HangarGestures.shipCarouselStep("ship", Direction.RIGHT))
        assertEquals(-1, HangarGestures.shipCarouselStep("ship", Direction.LEFT))
    }

    @Test
    fun `up and down still move focus off the ship`() {
        // Down is how the nav row is reached; making it a carousel move would strand the player.
        assertEquals(0, HangarGestures.shipCarouselStep("ship", Direction.DOWN))
        assertEquals(0, HangarGestures.shipCarouselStep("ship", Direction.UP))
    }

    @Test
    fun `every other target navigates as usual`() {
        assertEquals(0, HangarGestures.shipCarouselStep("nav:1", Direction.RIGHT))
        assertEquals(0, HangarGestures.shipCarouselStep(null, Direction.LEFT))
    }

    // ── The controller launch ───────────────────────────────────────────────

    @Test
    fun `the pad launch starts at rest and arrives at the halo`() {
        assertEquals(1800f, HangarGestures.padLaunchY(0f, restingY = 1800f, haloY = 1200f), 0.01f)
        assertEquals(1200f, HangarGestures.padLaunchY(HangarGestures.PAD_LAUNCH_RISE, 1800f, 1200f), 0.01f)
    }

    @Test
    fun `the ship holds in the halo rather than passing through it`() {
        // Half a second of holding is the point: the player asked to see the launch happen.
        val held = HangarGestures.padLaunchY(HangarGestures.PAD_LAUNCH_RISE + 0.25f, 1800f, 1200f)

        assertEquals(1200f, held, 0.01f)
    }

    @Test
    fun `the rise only ever moves upward`() {
        var previous = Float.MAX_VALUE
        var t = 0f
        while (t <= HangarGestures.PAD_LAUNCH_RISE) {
            val y = HangarGestures.padLaunchY(t, 1800f, 1200f)
            assertTrue("y must not fall back down at $t", y <= previous + 0.01f)
            previous = y
            t += 0.01f
        }
    }

    @Test
    fun `the launch fires after the rise and the hold, not before`() {
        assertFalse(HangarGestures.padLaunchDone(HangarGestures.PAD_LAUNCH_RISE))
        assertFalse(HangarGestures.padLaunchDone(HangarGestures.PAD_LAUNCH_RISE + HangarGestures.PAD_LAUNCH_HOLD - 0.01f))
        assertTrue(HangarGestures.padLaunchDone(HangarGestures.PAD_LAUNCH_RISE + HangarGestures.PAD_LAUNCH_HOLD))
    }

    // ── The drag fade every piece of launch chrome shares ────────────────────

    @Test
    fun `the drag fade is full at rest and gone at the fade end`() {
        assertEquals(1f, HangarGestures.shipDragFade(1800f, restingY = 1800f, fadeEnd = 1200f), 0.0001f)
        assertEquals(0f, HangarGestures.shipDragFade(1200f, restingY = 1800f, fadeEnd = 1200f), 0.0001f)
    }

    @Test
    fun `it is half way across at the midpoint`() {
        assertEquals(0.5f, HangarGestures.shipDragFade(1500f, restingY = 1800f, fadeEnd = 1200f), 0.0001f)
    }

    @Test
    fun `it clamps rather than going negative past the end`() {
        assertEquals(0f, HangarGestures.shipDragFade(900f, restingY = 1800f, fadeEnd = 1200f), 0.0001f)
        assertEquals(1f, HangarGestures.shipDragFade(1900f, restingY = 1800f, fadeEnd = 1200f), 0.0001f)
    }

    @Test
    fun `a degenerate range stays visible rather than dividing by zero`() {
        // shipRestingY is 0 until the first layout pass, and the intro title's variant can put the
        // fade end on top of the resting position.
        assertEquals(1f, HangarGestures.shipDragFade(0f, restingY = 1200f, fadeEnd = 1200f), 0.0001f)
    }

    // ── The codex sequence ───────────────────────────────────────────────────

    private fun enter(vararg directions: Direction): Int {
        var progress = 0
        for (d in directions) progress = HangarGestures.codexSequenceStep(progress, d)
        return progress
    }

    @Test
    fun `the whole sequence completes`() {
        val progress = enter(
            Direction.UP, Direction.UP, Direction.DOWN, Direction.DOWN,
            Direction.LEFT, Direction.RIGHT, Direction.LEFT, Direction.RIGHT
        )

        assertEquals(HangarGestures.CODEX_SEQUENCE.size, progress)
    }

    @Test
    fun `a wrong direction resets`() {
        // Navigating the shop must not creep toward the secret by accident.
        assertEquals(0, enter(Direction.UP, Direction.UP, Direction.LEFT))
    }

    @Test
    fun `a fumbled start restarts on the up rather than dropping it`() {
        // Someone reaching for the sequence presses up three times. The third up is a fresh first
        // press, not a miss that throws the run away.
        assertEquals(1, enter(Direction.UP, Direction.UP, Direction.UP))
    }

    @Test
    fun `the sequence does not re-trigger on the next press`() {
        val complete = HangarGestures.CODEX_SEQUENCE.size
        // Already open; a stray direction afterwards must not read as another completion.
        assertEquals(0, HangarGestures.codexSequenceStep(complete, Direction.DOWN))
    }
}
