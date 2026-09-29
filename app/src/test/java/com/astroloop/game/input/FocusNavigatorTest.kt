package com.astroloop.game.input

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FocusNavigatorTest {

    private fun t(id: String, l: Float, top: Float, w: Float = 40f, h: Float = 40f) =
        FocusTarget(id, RectF(l, top, l + w, top + h)) {}

    /** Three upgrade cards in a row, as UpgradeSelectionRenderer lays them out. */
    private val row = listOf(t("a", 0f, 0f), t("b", 60f, 0f), t("c", 120f, 0f))

    @Test
    fun `right moves to the next card`() {
        assertEquals("b", FocusNavigator.next(row, "a", Direction.RIGHT))
    }

    @Test
    fun `left moves back`() {
        assertEquals("b", FocusNavigator.next(row, "c", Direction.LEFT))
    }

    @Test
    fun `the end of a row does not wrap`() {
        assertNull(FocusNavigator.next(row, "c", Direction.RIGHT))
    }

    @Test
    fun `no focus yet lands on the first target in reading order`() {
        assertEquals("a", FocusNavigator.next(row, null, Direction.RIGHT))
    }

    @Test
    fun `an empty registry yields nothing`() {
        assertNull(FocusNavigator.next(emptyList(), null, Direction.RIGHT))
    }

    @Test
    fun `down prefers the target directly below over a nearer one off to the side`() {
        val grid = listOf(
            t("topLeft", 0f, 0f),
            t("belowLeft", 0f, 60f),
            // Physically closer to topLeft than belowLeft, but sideways — a cross-axis
            // penalty must stop DOWN landing on it.
            t("sideways", 50f, 30f)
        )
        assertEquals("belowLeft", FocusNavigator.next(grid, "topLeft", Direction.DOWN))
    }

    @Test
    fun `a 3x3 grid with a hole skips to the next filled cell`() {
        // The store's upgrade grid, with the middle cell of the bottom row absent.
        val grid = listOf(
            t("r0c0", 0f, 0f), t("r0c1", 60f, 0f), t("r0c2", 120f, 0f),
            t("r1c0", 0f, 60f), t("r1c1", 60f, 60f), t("r1c2", 120f, 60f),
            t("r2c0", 0f, 120f), t("r2c2", 120f, 120f)
        )
        assertEquals("r2c0", FocusNavigator.next(grid, "r1c0", Direction.DOWN))
        // Straight down from r1c1 there is nothing, so the nearest cell in that half-plane wins.
        val fromMiddle = FocusNavigator.next(grid, "r1c1", Direction.DOWN)
        assertEquals(true, fromMiddle == "r2c0" || fromMiddle == "r2c2")
    }

    @Test
    fun `ragged rows still move down`() {
        // The bar's pilot cards do not form a clean grid once some are still locked.
        val ragged = listOf(t("a", 0f, 0f), t("b", 70f, 0f), t("c", 35f, 60f))
        assertEquals("c", FocusNavigator.next(ragged, "a", Direction.DOWN))
    }

    @Test
    fun `disabled targets are still reachable`() {
        // The launch pad is disabled until the walker arrives, but the player must be able to
        // land on it and see that it is unavailable.
        val withDisabled = listOf(t("a", 0f, 0f), FocusTarget("b", RectF(60f, 0f, 100f, 40f), enabled = false) {})
        assertEquals("b", FocusNavigator.next(withDisabled, "a", Direction.RIGHT))
    }
}
