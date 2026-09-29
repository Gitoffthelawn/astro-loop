package com.astroloop.game.data

import com.astroloop.game.system.CrystalPhase
import org.junit.Assert.*
import org.junit.Test

class CrystalFightLinesTest {

    @Test fun allReckoningRadioLinesFitTheHudBudget() {
        val limit = 35
        val lines = mutableListOf<String>()
        // The opening is a flat list of the crystal's own beats as of a design decision. It was
        // pairs when it was ASTRO's monologue and each pair had a follow-up half.
        lines += CrystalFightLines.opening
        CrystalPhase.values().forEach { p ->
            val (a, b) = CrystalFightLines.taunt(p)
            lines += a
            if (b != null) lines += b
        }
        lines.forEach {
            assertTrue("\"$it\" is ${it.length} chars (limit $limit)", it.length <= limit)
        }
    }

    @Test fun thePilotIsNotInThePostWinBeat() {
        // A design decision. They say nothing about the fight anywhere — the same refusal held
        // through all five patterns — and it is what keeps this list honest: the speakers
        // are hardcoded, while the reckoning is reachable on any of the twelve, so a pilot
        // named here would be reporting a fight somebody else flew.
        val speakers = CrystalFightLines.barChatter.map { it.first }.toSet()
        assertEquals("only the bar speaks", setOf("TOBAR", "MEDIC"), speakers)
    }

    @Test fun allBarLinesFitTheChatColumn() {
        val limit = 58
        CrystalFightLines.barChatter.forEach { (s, l) ->
            assertTrue("$s: \"$l\" is ${l.length} chars (limit $limit)", l.length <= limit)
        }
    }

    @Test fun everyPhaseHasATaunt() {
        CrystalPhase.values().forEach { p ->
            assertTrue("${p} needs a part 1", CrystalFightLines.taunt(p).first.isNotBlank())
        }
    }

    @Test fun p5IsASingleScream() {
        // The mask drops — one line, no measured follow-up.
        assertNull(CrystalFightLines.taunt(CrystalPhase.P5).second)
    }

    // NOTE: "Astro never speaks during the fight" is deliberately NOT tested here. taunt() has
    // no speaker field — the speaker is hardcoded by whichever call site consumes it — so any
    // data-level assertion here would be vacuous. That invariant belongs to the caller wiring,
    // not to this data file. Do not add a fake test here to feel covered.
}
