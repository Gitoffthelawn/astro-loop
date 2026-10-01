package com.astroloop.game.tuning

import com.astroloop.game.core.GameState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PassiveKnobsTest {
    @After
    fun reset() = Tunables.resetAll()

    private fun stateWith(id: String, stacks: Int) =
        GameState().also { it.reset(); it.passiveStacks[id] = stacks; it.recalculateStats() }

    @Test
    fun `defaults are the shipped values the golden cannot see`() {
        with(KnobsPassives) {
            assertEquals(2f, revengeSecondsPerStack.value)
            assertEquals(0.2f, vampiricTick.value)
            assertEquals(6, vampiricMaxTargets.value)
            assertEquals(5f, droneDamage.value)
            assertEquals(450f, droneSpeed.value)
            assertEquals(1.2f, droneFireRate.value)
            assertEquals(0.15f, droneSpread.value)
            assertEquals(45f, droneEvolvedDamage.value)
            assertEquals(500f, droneEvolvedSpeed.value)
            assertEquals(2f, droneEvolvedFireRate.value)
            assertEquals(2.5f, droneEvolvedHoming.value)
        }
    }

    @Test
    fun `a per-stack knob reaches recalculateStats`() {
        Tunables.set(KnobsPassives.nanoRegenPerStack, 1f)
        assertEquals(3f, stateWith("nano_repair", 3).healthRegen)
    }

    @Test
    fun `cryo radius reads the base radius knob`() {
        Tunables.set(KnobsPassives.cryoBaseRadius, 200f)
        assertEquals(250f, stateWith("cryo_field", 1).getCryoRadius())
    }
}
