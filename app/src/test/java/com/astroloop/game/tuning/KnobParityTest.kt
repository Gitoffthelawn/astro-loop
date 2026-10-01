package com.astroloop.game.tuning

import org.junit.Assert.assertTrue
import org.junit.Test

/** Weapons behave identically against enemies and asteroids, so no weapon or passive knob may name a target. */
class KnobParityTest {
    @Test
    fun `no weapon or passive knob is specific to a target type`() {
        val offenders = Tunables.all
            .filter { it.group.startsWith("Weapons/") || it.group.startsWith("Passives/") }
            .filter { k -> listOf("enemy", "enemies", "asteroid", "boss", "crew").any { it in k.id.substringAfter('.') } }
        assertTrue("target-specific knobs: ${offenders.map { it.id }}", offenders.isEmpty())
    }
}
