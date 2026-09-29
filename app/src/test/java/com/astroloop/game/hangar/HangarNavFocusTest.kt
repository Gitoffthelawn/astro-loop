package com.astroloop.game.hangar

import com.astroloop.game.input.FocusRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HangarNavFocusTest {

    private lateinit var focus: FocusRegistry
    private var navigatedTo = -1

    @Before
    fun setup() {
        focus = FocusRegistry()
        navigatedTo = -1
    }

    private fun publish(currentPage: Int, introCinematic: Boolean = false) {
        focus.begin()
        HangarRenderer.publishNavTargets(
            focus, currentPage,
            screenWidth = 1080f, screenHeight = 2400f, contentWidth = 1080f,
            introCinematic = introCinematic
        ) { navigatedTo = it }
        focus.commit()
    }

    @Test
    fun `all three nav labels are published`() {
        publish(currentPage = 1)

        assertEquals(listOf("nav:0", "nav:1", "nav:2"), focus.targets().map { it.id })
    }

    @Test
    fun `the current page's own label is disabled`() {
        publish(currentPage = 1)

        assertEquals(false, focus.byId("nav:1")!!.enabled)
        assertEquals(true, focus.byId("nav:0")!!.enabled)
    }

    @Test
    fun `activating a nav label navigates to that page`() {
        publish(currentPage = 1)
        focus.focusedId = "nav:2"

        assertTrue(focus.activate())
        assertEquals(2, navigatedTo)
    }

    @Test
    fun `the labels sit in page order left to right`() {
        publish(currentPage = 1)
        val xs = focus.targets().map { it.rect.centerX() }

        assertTrue("nav:0 must be left of nav:1", xs[0] < xs[1])
        assertTrue("nav:1 must be left of nav:2", xs[1] < xs[2])
    }

    @Test
    fun `the labels sit near the bottom, where they are drawn`() {
        publish(currentPage = 1)

        for (target in focus.targets()) {
            assertTrue(
                "${target.id} must sit in the bottom tenth of the screen",
                target.rect.centerY() > 2400f * 0.9f
            )
        }
    }

    @Test
    fun `the intro publishes only the one hop a swipe allows`() {
        publish(currentPage = 0, introCinematic = true)

        assertEquals(listOf("nav:1"), focus.targets().map { it.id })
        assertTrue("the launch pad label is the way out of the intro", focus.byId("nav:1")!!.enabled)
    }

    @Test
    fun `after the intro's hop the label stays but goes dead`() {
        // The cinematic is still running until the launch itself, and a controller must not be
        // able to wander back to the bar when a finger cannot.
        publish(currentPage = 1, introCinematic = true)

        assertEquals(listOf("nav:1"), focus.targets().map { it.id })
        assertFalse(focus.byId("nav:1")!!.enabled)
    }

    @Test
    fun `the focus rects match the tap zones they mirror`() {
        // The geometry is duplicated from handleTap by necessity — this is what keeps the copy
        // honest. Zone: |x - labelCenter| < spacing*0.4, labelY-30 < y < labelY+15.
        publish(currentPage = 1)

        val screenWidth = 1080f
        val labelY = 2400f * 0.95f
        val spacing = 1080f * 0.25f
        for (i in 0..2) {
            val labelCenterX = screenWidth / 2f + (i - 1) * spacing
            val rect = focus.byId("nav:$i")!!.rect

            assertEquals(labelCenterX - spacing * 0.4f, rect.left, 0.01f)
            assertEquals(labelCenterX + spacing * 0.4f, rect.right, 0.01f)
            assertEquals(labelY - 30f, rect.top, 0.01f)
            assertEquals(labelY + 15f, rect.bottom, 0.01f)
        }
    }

    @Test
    fun `a disabled current-page label cannot be activated`() {
        publish(currentPage = 1)
        focus.focusedId = "nav:1"

        assertFalse(focus.activate())
        assertEquals(-1, navigatedTo)
    }
}
