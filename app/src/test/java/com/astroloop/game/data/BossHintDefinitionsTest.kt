package com.astroloop.game.data

import org.junit.Assert.*
import org.junit.Test

/**
 * The ten-minute boss has one answer: fly as Astro, who brings TB-26, and survive. Any other
 * pilot dies out there alone.
 *
 * Players were getting stuck without ever learning that, so TB-26 nudges after a failure. Which
 * nudge depends on why they failed, and sending the wrong one is worse than sending none: telling
 * someone already flying Astro not to go alone reads as nonsense and costs him his credibility.
 */
class BossHintDefinitionsTest {

    /** Worst-case all-caps budget for TB-26 in the bar's chat column. */
    private val speakerBudget = 58

    @Test
    fun `a pilot who went out alone is pointed toward company`() {
        val hint = BossHintDefinitions.hintFor(BossHintDefinitions.Track.SOLO, failures = 1)
        assertNotNull(hint)
        assertTrue("Should be about not being alone: $hint", hint!!.lowercase().contains("alone"))
    }

    @Test
    fun `a pilot already flying Astro is told to endure, never to bring company`() {
        for (failures in 1..5) {
            val hint = BossHintDefinitions.hintFor(BossHintDefinitions.Track.ASTRO, failures)
            assertNotNull(hint)
            assertFalse(
                "Astro already has company; this hint would be nonsense: $hint",
                hint!!.lowercase().contains("alone")
            )
        }
    }

    @Test
    fun `no solo hint presupposes an incomplete roster`() {
        // Astro unlocks on ALL_OTHERS, so every SOLO line may be read by a player who already
        // has him — the track keeps firing for as long as they keep flying someone else. A line
        // that tells that player to go recruit is nonsense: there is nobody left to recruit.
        for (line in BossHintDefinitions.linesFor(BossHintDefinitions.Track.SOLO)) {
            assertFalse(
                "Reads as nonsense once Astro is recruited: $line",
                line.lowercase().contains("recruit")
            )
        }
    }

    @Test
    fun `hints escalate with repeated failures`() {
        for (track in BossHintDefinitions.Track.values()) {
            val first = BossHintDefinitions.hintFor(track, failures = 1)
            val second = BossHintDefinitions.hintFor(track, failures = 2)
            assertNotEquals("$track must not repeat itself on the second failure", first, second)
        }
    }

    @Test
    fun `the last hint keeps being given rather than running out`() {
        for (track in BossHintDefinitions.Track.values()) {
            val last = BossHintDefinitions.hintFor(track, failures = 99)
            assertNotNull("A stuck player must never stop being helped", last)
            assertEquals(
                "Past the end, the most explicit hint repeats",
                BossHintDefinitions.hintFor(track, BossHintDefinitions.linesFor(track).size),
                last
            )
        }
    }

    @Test
    fun `there is no hint before a failure has happened`() {
        for (track in BossHintDefinitions.Track.values()) {
            assertNull(BossHintDefinitions.hintFor(track, failures = 0))
        }
    }

    @Test
    fun `every hint fits the chat column`() {
        for (track in BossHintDefinitions.Track.values()) {
            for (line in BossHintDefinitions.linesFor(track)) {
                assertTrue(
                    "$track hint is ${line.length} chars, over $speakerBudget: $line",
                    line.length <= speakerBudget
                )
            }
        }
    }

    /**
     * Per-speaker budgets: the `[CALLSIGN]: ` prefix eats into the chat column, so a
     * long callsign buys a shorter line. Worst-case all-caps.
     */
    private val budgets = mapOf(
        "DASH" to 59, "FANG" to 59,
        "MEDIC" to 58, "FROST" to 58, "UNIT-7" to 58, "HAVOC" to 58, "ASTRO" to 58,
        "RASCAL" to 57, "BRUTUS" to 57, "EMBER" to 57, "KRAKEN" to 57,
        "WHISKERS" to 55
    )

    @Test
    fun `every pilot in the roster has their own reaction`() {
        for (pilot in PilotDefinitions.pilots) {
            assertNotNull(
                "${pilot.callsign} has no reaction — a new pilot needs one authored",
                BossHintDefinitions.crewReactions[pilot.callsign]
            )
        }
    }

    @Test
    fun `the reactions are all different, not one line in twelve costumes`() {
        val lines = BossHintDefinitions.crewReactions.values
        assertEquals("Duplicated reactions defeat the point", lines.size, lines.toSet().size)
    }

    @Test
    fun `each reaction fits its own speaker's budget`() {
        for ((callsign, line) in BossHintDefinitions.crewReactions) {
            val budget = budgets[callsign]
            assertNotNull("No documented budget for $callsign", budget)
            assertTrue(
                "$callsign reaction is ${line.length} chars, over $budget: $line",
                line.length <= budget!!
            )
        }
    }

    @Test
    fun `an unknown speaker still gets something to say`() {
        assertNotNull(
            "The wiring must never fall through to silence",
            BossHintDefinitions.reactionFor("NOBODY")
        )
    }
}
