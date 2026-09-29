package com.astroloop.game.hangar

import com.astroloop.game.input.FocusRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShipyardFocusParityTest {

    private lateinit var focus: FocusRegistry
    private val fired = mutableListOf<String>()

    @Before
    fun setup() {
        focus = FocusRegistry()
        fired.clear()
    }

    private fun publish() {
        focus.begin()
        HangarRenderer.publishShipyardTarget(
            focus, centerX = 540f, shipY = 1200f, hitSize = 70f
        ) { fired.add("activate") }
        focus.commit()
    }

    @Test
    fun `the ship is the only target on the page`() {
        // Left and right are the carousel, not focus moves, so prev, next and the launch pad are
        // not targets: flying the second ship was five presses when they were.
        publish()

        assertEquals(listOf("ship"), focus.targets().map { it.id })
    }

    @Test
    fun `activating the ship fires once`() {
        publish()
        focus.focusedId = "ship"

        assertTrue(focus.activate())
        assertEquals(listOf("activate"), fired)
    }

    @Test
    fun `the target covers the ship's own tap zone`() {
        // handleShipyardTap only looks at ships within hitSize of the ship row, and treats the
        // centre band as the ship itself — which is where the callback re-enters it.
        publish()
        val rect = focus.byId("ship")!!.rect

        assertEquals(540f - 70f, rect.left, 0.01f)
        assertEquals(540f + 70f, rect.right, 0.01f)
        assertEquals(1200f - 70f, rect.top, 0.01f)
        assertEquals(1200f + 70f, rect.bottom, 0.01f)
    }
}
