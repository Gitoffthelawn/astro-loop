package com.astroloop.game.hangar

import com.astroloop.game.input.Direction
import kotlin.math.abs

/**
 * What a moving finger has committed to in the hangar, and whether a store hold survives it.
 *
 * Kept free of Android types so the rules are unit-testable without a device — the view owns the
 * `MotionEvent`s, this owns the decisions.
 *
 * **The slop is the platform's, not a constant.** Callers pass
 * `ViewConfiguration.get(context).scaledTouchSlop`, which is 8dp in real pixels, so the thresholds
 * scale with the screen. A hard-coded pixel figure means one thing on a phone and another on a
 * tablet, and the hangar previously used 15px — about 5dp on a modern phone, tighter than the
 * platform's own minimum for calling something a drag.
 *
 * **A hold gets a bigger budget than a swipe.** Both Android and iOS separate the two, because a
 * finger held still for a second drifts more than one about to flick: Apple's
 * `UILongPressGestureRecognizer.allowableMovement` defaults to 10 points against an 8dp scroll
 * slop. Here the hold's budget is the pressed tile itself, which is the rule a player already
 * expects from every button they have used — drift as much as you like, but leave the control and
 * you have let go of it.
 */
object HangarGestures {

    /**
     * A page swipe needs **horizontal** travel.
     *
     * The hangar's three rooms are tiled side by side, so sideways is the only direction that
     * scrolls. Vertical drift used to start a "page swipe" too, which scrolled by a dx of roughly
     * zero — invisible, but it cancelled any store hold in progress. That was the purchase being
     * taken away for a gesture the page cannot even perform.
     */
    fun startsPageSwipe(totalDx: Float, totalDy: Float, slop: Float): Boolean = abs(totalDx) > slop

    /**
     * A ship drag needs vertical travel that beats the horizontal, and a ship under the finger.
     *
     * Shipyard only, which is why a store hold never has to compete with it.
     */
    fun startsShipDrag(
        totalDx: Float,
        totalDy: Float,
        slop: Float,
        shipDragPossible: Boolean
    ): Boolean = shipDragPossible && abs(totalDy) > slop && abs(totalDy) > abs(totalDx)

    /** Whether a hold started on this tile is still on it. */
    fun holdSurvivesDrift(
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /**
     * The intro cinematic permits exactly one page change — bar to launch pad — because that is
     * the single hop the swipe release permits while it runs.
     *
     * Blocking a controller outright, which is what shipped, means a first-ever launch cannot be
     * started from a pad at all: the one screen that tells the player to go somewhere was the one
     * place a controller could not go.
     */
    fun introPageHopAllowed(introCinematic: Boolean, currentPage: Int, target: Int): Boolean =
        !introCinematic || (currentPage == 0 && target == 1)

    /**
     * How far the ship carousel moves on a directional press: +1, -1, or 0 when the press is an
     * ordinary focus move.
     *
     * The ship is one control, not three. A ring hopping between the peek ships made flying the
     * second ship a five-press sequence; up and down still leave the ship, which is how the nav
     * row is reached.
     */
    fun shipCarouselStep(focusedId: String?, direction: Direction): Int = when {
        focusedId != "ship" -> 0
        direction == Direction.RIGHT -> 1
        direction == Direction.LEFT -> -1
        else -> 0
    }

    /** The rise into the halo, and the beat the ship holds there before the run starts. */
    const val PAD_LAUNCH_RISE = 0.35f
    const val PAD_LAUNCH_HOLD = 0.5f

    /**
     * Where the ship sits during a controller launch.
     *
     * A finger carries the ship into the halo itself, so the drag release can launch on the spot.
     * From a pad nothing carried it, and launching on the spot teleports it — the player never
     * sees the thing they asked for happen. Eased out, so it settles into the halo rather than
     * arriving at full speed, and it stays there for the hold.
     */
    fun padLaunchY(elapsed: Float, restingY: Float, haloY: Float): Float {
        val t = (elapsed / PAD_LAUNCH_RISE).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t)
        return restingY + (haloY - restingY) * eased
    }

    fun padLaunchDone(elapsed: Float): Boolean = elapsed >= PAD_LAUNCH_RISE + PAD_LAUNCH_HOLD

    /**
     * How lit the launch chrome is: 1 with the ship at rest, 0 once it has risen to [fadeEnd].
     *
     * The ship names, the weapon labels, the peek ships, the launch chevrons, the yen counter and
     * the intro title all fade on this one curve. It was written out four times before this, which
     * is how the focus ring came to be the only thing still lit while the ship rose. The title
     * passes a further [fadeEnd] to stretch the same curve over twice the distance.
     */
    fun shipDragFade(shipDragY: Float, restingY: Float, fadeEnd: Float): Float =
        if (restingY == fadeEnd) 1f
        else ((shipDragY - fadeEnd) / (restingY - fadeEnd)).coerceIn(0f, 1f)

    /**
     * How present page [page]'s own chrome is: 1 while that page is settled and current, easing
     * to 0 once it has been dragged [fadeTravel] away, either direction.
     *
     * [fadeTravel] is a DISTANCE, not a fraction of the stride: the caller measures the clear
     * space between the chrome and whatever is sliding toward it (`HangarPanels.buttonRoomGap`
     * for the panel buttons) so the fade finishes exactly as the two would meet. It started life
     * as a flat half-stride, matching [shipDragFade]'s "gone halfway through the travel", which
     * was the right curve against the wrong ruler — on a rotated Pixel 9 Pro the bar reaches the
     * crew button after 0.31 of a stride, with the button still 38% lit underneath it.
     *
     * The panel buttons live on a page but are drawn as screen chrome, so nothing moved them when
     * the page did. They stayed fully lit through the whole swipe and then vanished on the frame
     * the page committed — the owner's report of 2026-09-20, and an instant disappearance of
     * exactly the kind the project forbids.
     *
     * **Continuous across the commit**, which is what makes it a fade rather than a fade plus a
     * jump. On release `HangarSurfaceView` moves the page index and the scroll offset TOGETHER —
     * `pageScrollOffset -= stride` alongside `currentPage + 1` — precisely so the page does not
     * leap, and the displacement below is written from both terms for the same reason. Before the
     * commit it is `-offset`; after it, `(page - currentPage) * stride - (offset - stride)`, which
     * is the same number. The settle animation then carries the offset to zero and the fade to 0
     * with it.
     *
     * A page that owns no chrome still gets an answer; the caller decides what has chrome.
     */
    fun pageSwipeFade(
        page: Int, currentPage: Int, pageScrollOffset: Float, stride: Float, fadeTravel: Float
    ): Float {
        if (stride <= 0f || fadeTravel <= 0f) return if (page == currentPage) 1f else 0f
        val displacement = (page - currentPage) * stride - pageScrollOffset
        return (1f - abs(displacement) / fadeTravel).coerceIn(0f, 1f)
    }

    /**
     * The way in to the codex on a controller, now that the hatch is not a focus target.
     *
     * Directions only, and deliberately: on the shop page a press of A or B would spin the machine
     * or flip a card, so a sequence ending in buttons would charge the player 100¥ for knowing a
     * secret. Directions move the ring and nothing else, and the move is reversible.
     */
    val CODEX_SEQUENCE = listOf(
        Direction.UP, Direction.UP, Direction.DOWN, Direction.DOWN,
        Direction.LEFT, Direction.RIGHT, Direction.LEFT, Direction.RIGHT
    )

    /**
     * The sequence's progress after [direction]: one further on a match, and otherwise a restart —
     * at 1 when the miss is itself the sequence's opening press, because someone fumbling the start
     * presses up three times and should not have to stop and begin again.
     */
    fun codexSequenceStep(progress: Int, direction: Direction): Int {
        val next = if (progress >= CODEX_SEQUENCE.size) 0 else progress
        if (direction == CODEX_SEQUENCE[next]) return next + 1
        return if (direction == CODEX_SEQUENCE[0]) 1 else 0
    }
}
