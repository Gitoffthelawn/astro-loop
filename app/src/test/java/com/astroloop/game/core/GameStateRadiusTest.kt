package com.astroloop.game.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The radius helpers the effect rings read. Plain JVM — GameState's stat math has no Android
 * types. These exist so a ring can never disagree with the effect it is drawing: each test
 * pins a helper to the same expression its gameplay call site uses.
 */
class GameStateRadiusTest {

    @Test
    fun `cryo radius is the base scaled by the stack multiplier`() {
        val state = GameState()
        state.addPassive("cryo_field")
        state.recalculateStats()
        // 1 stack -> 1 + 0.25 = 1.25
        assertEquals(GameConfig.CRYO_BASE_RADIUS * 1.25f, state.getCryoRadius(), 0.001f)
    }

    @Test
    fun `cryo radius steps 25px per stack`() {
        val state = GameState()
        repeat(4) { state.addPassive("cryo_field") }
        state.recalculateStats()
        val four = state.getCryoRadius()
        state.addPassive("cryo_field")
        state.recalculateStats()
        assertEquals(25f, state.getCryoRadius() - four, 0.001f)
    }

    @Test
    fun `pickup range is base times passive stacks times the store upgrade`() {
        val state = GameState()
        state.addPassive("magnet_field")
        state.recalculateStats()
        state.permanentMagnetLevel = 0
        // 80 * (1 + 0.3) * 1.0
        assertEquals(104f, state.getPickupRange(), 0.001f)
    }

    @Test
    fun `pickup range includes the black market magnet upgrade`() {
        val state = GameState()
        state.addPassive("magnet_field")
        state.recalculateStats()
        state.permanentMagnetLevel = 5
        // 80 * 1.3 * 1.75
        assertEquals(182f, state.getPickupRange(), 0.001f)
    }

    @Test
    fun `pickup range with no magnet passive is the bare base`() {
        val state = GameState()
        state.recalculateStats()
        state.permanentMagnetLevel = 0
        assertEquals(GameConfig.POWERUP_MAGNET_BASE_RANGE, state.getPickupRange(), 0.001f)
    }

    @Test
    fun `effect ring fade starts opaque and resets with the run`() {
        val state = GameState()
        assertEquals(1f, state.effectRingFadeAlpha, 0.001f)
        state.effectRingFadeAlpha = 0f
        state.reset()
        assertEquals("a new run must not start with faded rings", 1f, state.effectRingFadeAlpha, 0.001f)
    }
}
