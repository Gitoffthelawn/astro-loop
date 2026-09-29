package com.astroloop.game.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DebugFloatingButtonTest {

    private fun btn() = DebugFloatingButton(60f)

    @Test
    fun anUnpositionedButtonDefaultsInsideTheView() {
        val b = btn()
        b.applyLoaded(-1f, -1f, viewW = 1080f, viewH = 2400f)
        assertEquals(1080f - 60f - DebugFloatingButton.MARGIN, b.cx, 0.01f)
        assertEquals(2400f * 0.5f, b.cy, 0.01f)
    }

    @Test
    fun aLoadedPositionIsClampedIntoTheView() {
        val b = btn()
        // Saved on a taller/wider device, or before a rotation.
        b.applyLoaded(5000f, 9000f, viewW = 1080f, viewH = 2400f)
        assertEquals(1080f - 60f, b.cx, 0.01f)
        assertEquals(2400f - 60f, b.cy, 0.01f)
    }

    @Test
    fun aLoadedPositionInsideTheViewIsKept() {
        val b = btn()
        b.applyLoaded(400f, 900f, viewW = 1080f, viewH = 2400f)
        assertEquals(400f, b.cx, 0.01f)
        assertEquals(900f, b.cy, 0.01f)
    }

    @Test
    fun aPressAndReleaseWithoutMovementIsATap() {
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        assertEquals(DebugFloatingButton.Outcome.CONSUMED, b.press(400f, 900f, atMs = 0L))
        assertEquals(DebugFloatingButton.Outcome.TAPPED, b.release(atMs = 100L))
    }

    @Test
    fun aPressOutsideTheButtonIsIgnoredAndDoesNotArm() {
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        assertEquals(DebugFloatingButton.Outcome.IGNORED, b.press(50f, 50f, atMs = 0L))
        // A release with no arming press must not report a tap — the same phantom-tap hole
        // a device pass found in CabinetInput.up().
        assertEquals(DebugFloatingButton.Outcome.IGNORED, b.release(atMs = 100L))
    }

    @Test
    fun movingPastTheSlopPromotesToADragAndMovesTheButton() {
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        b.press(400f, 900f, atMs = 0L)
        b.move(400f + DebugFloatingButton.SLOP_PX + 1f, 900f, atMs = 20L, viewW = 1080f, viewH = 2400f)
        assertEquals(400f + DebugFloatingButton.SLOP_PX + 1f, b.cx, 0.01f)
        // Promoted: the release is a drag end, not a tap.
        assertEquals(DebugFloatingButton.Outcome.CONSUMED,
            b.release(atMs = 40L))
    }

    @Test
    fun holdingPastTheWindowPromotesEvenWithoutMovement() {
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        b.press(400f, 900f, atMs = 0L)
        b.move(400f, 900f, atMs = DebugFloatingButton.HOLD_MS + 1L, viewW = 1080f, viewH = 2400f)
        // The hold alone must promote inside move() itself, not only in release()'s own
        // elapsed-time re-check (which exists for the real case where a stationary finger
        // never generates an ACTION_MOVE at all, and cannot move cx/cy on its own). A further
        // sub-slop nudge here only tracks the button if move() already flipped `promoted`.
        b.move(405f, 900f, atMs = DebugFloatingButton.HOLD_MS + 2L, viewW = 1080f, viewH = 2400f)
        assertEquals(405f, b.cx, 0.01f)
        // Slop alone would still call this a tap. The rule is slop OR elapsed.
        assertEquals(DebugFloatingButton.Outcome.CONSUMED,
            b.release(atMs = DebugFloatingButton.HOLD_MS + 20L))
    }

    @Test
    fun aDragCannotLeaveTheView() {
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        b.press(400f, 900f, atMs = 0L)
        b.move(-500f, -500f, atMs = 50L, viewW = 1080f, viewH = 2400f)
        assertEquals(60f, b.cx, 0.01f)
        assertEquals(60f, b.cy, 0.01f)
    }

    @Test
    fun cancelDisarmsWithoutTapping() {
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        b.press(400f, 900f, atMs = 0L)
        b.cancel()
        assertEquals(DebugFloatingButton.Outcome.IGNORED, b.release(atMs = 50L))
    }

    @Test
    fun theDefaultPositionIsNotTheOrigin() {
        val b = btn()
        b.applyLoaded(-1f, -1f, 1080f, 2400f)
        assertNotEquals(0f, b.cx)
        assertNotEquals(0f, b.cy)
    }

    // --- ACTION_CANCEL symmetry (review Important 1) ---

    @Test
    fun aCancelWithNoArmingPressIsIgnored() {
        // The call site sits at the top of onTouchEvent and stops processing on anything but
        // IGNORED. Consuming a cancel that was never ours starved the pause-and-hold debug
        // trigger's own disarm, leaving it armed to fire five seconds later on its own.
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        assertEquals(DebugFloatingButton.Outcome.IGNORED, b.cancel())
    }

    @Test
    fun aCancelAfterOurOwnPressIsConsumed() {
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        b.press(400f, 900f, atMs = 0L)
        assertEquals(DebugFloatingButton.Outcome.CONSUMED, b.cancel())
    }

    // --- position persistence latch (review Important 2) ---

    @Test
    fun aDragThatMovedTheButtonMarksThePositionDirty() {
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        b.press(400f, 900f, atMs = 0L)
        b.move(600f, 900f, atMs = 20L, viewW = 1080f, viewH = 2400f)
        b.release(atMs = 40L)
        assertTrue(b.consumeDirtyPosition())
    }

    @Test
    fun aTapNeverMarksThePositionDirty() {
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        b.press(400f, 900f, atMs = 0L)
        b.release(atMs = 100L)
        assertFalse(b.consumeDirtyPosition())
    }

    @Test
    fun midDragTheLatchIsNotYetSet() {
        // Every intermediate MOVE returns CONSUMED too. If the latch were set here the caller
        // would write to SharedPreferences once per frame for the whole drag.
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        b.press(400f, 900f, atMs = 0L)
        b.move(600f, 900f, atMs = 20L, viewW = 1080f, viewH = 2400f)
        assertFalse(b.consumeDirtyPosition())
    }

    @Test
    fun theDirtyLatchIsConsumeOnce() {
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        b.press(400f, 900f, atMs = 0L)
        b.move(600f, 900f, atMs = 20L, viewW = 1080f, viewH = 2400f)
        b.release(atMs = 40L)
        assertTrue(b.consumeDirtyPosition())
        assertFalse("a second read must not re-save", b.consumeDirtyPosition())
    }

    @Test
    fun aDragEndedByCancellationStillSavesItsPosition() {
        val b = btn()
        b.applyLoaded(400f, 900f, 1080f, 2400f)
        b.press(400f, 900f, atMs = 0L)
        b.move(600f, 900f, atMs = 20L, viewW = 1080f, viewH = 2400f)
        b.cancel()
        assertTrue(b.consumeDirtyPosition())
    }
}
