package com.astroloop.game.hangar

import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.StoryStage
import com.astroloop.game.data.LoopDefinitions
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BandanaAwardCeremonyTest {

    private lateinit var persistence: PersistenceManager
    private lateinit var state: HangarState
    private lateinit var chat: ChatSystem

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
        persistence.setStoryStageCode(StoryStage.ASTRO_LOOP.code)
        persistence.setLastAstroRunSeconds(650f)
        persistence.updateAstroLoopBestSeconds(650f)
        state = HangarState(persistence)
        chat = ChatSystem()
    }

    /** Death-return lines are queued as a conversation — tick update() until delivered. */
    private fun drainConversation() {
        var guard = 0
        while (state.activeConversation != null && guard++ < 40) {
            chat.update(ChatSystem.LINE_PAUSE + 0.1f, state)
        }
    }

    // A design decision: the desert-town hints and Tobar's twelfth beat were deleted as signposting —
    // the destination is already signposted, directively, in the corrupted bar chatter. These
    // literal strings are the deleted content itself (the production symbols that held them are
    // gone), kept here only so a regression that resurrects the text — under any symbol name —
    // still fails these tests.
    private val deletedDesertHints = listOf(
        "Odd bit on the news. Some desert town. Slow day, I guess.",
        "Same broadcast as last night. Word for word. I counted.",
        "...You feel that? It's waiting for you, Astro."
    )
    private val deletedTwelfthBeat = listOf(
        "That's the last one. All twelve of you, marked.",
        "Commander... whatever's out there - it's here now."
    )

    @Test
    fun ceremonyAddsTobarFramingAndPilotReplyOnly() {
        persistence.addBandana("pilot_dash")          // count = 1 (< 12)
        persistence.setPendingBandanaPilot("pilot_dash")

        chat.onDeathReturn(state, "pilot_dash")

        assertTrue("Return burst must be queued, not dumped in one frame",
            state.chatMessages.isEmpty() && state.activeConversation != null)
        drainConversation()

        val texts = state.chatMessages.map { it.text }
        assertTrue("Tobar framing missing", texts.any { it in LoopDefinitions.tobarBandanaFraming })
        assertTrue("Pilot reply missing",
            texts.contains(LoopDefinitions.bandanaAwardReplies["pilot_dash"]))
        // Inversion of the old signpost assertion: survived-time report + framing + reply, and
        // nothing else appended — no extra signpost line.
        assertEquals("Ceremony must be report + framing + reply only, no extra signpost line", 3, texts.size)
        for (hint in deletedDesertHints) {
            assertFalse("Desert hint must not reappear: $hint", texts.contains(hint))
        }
        assertNull("Pending must be cleared", persistence.getPendingBandanaPilot())
    }

    @Test
    fun seventhBandanaCeremonyCarriesNoDesertHint() {
        val ids = listOf(
            "pilot_medic", "pilot_rascal", "pilot_brutus", "pilot_frost",
            "pilot_dash", "pilot_ember", "pilot_fang"
        )
        for (id in ids) persistence.addBandana(id)    // count = 7
        persistence.setPendingBandanaPilot("pilot_fang")

        chat.onDeathReturn(state, "pilot_fang")
        drainConversation()

        val texts = state.chatMessages.map { it.text }
        assertTrue("Tobar framing missing", texts.any { it in LoopDefinitions.tobarBandanaFraming })
        assertTrue("Pilot reply missing",
            texts.contains(LoopDefinitions.bandanaAwardReplies["pilot_fang"]))
        assertEquals("No hint line should ride along at any bandana count", 3, texts.size)
        for (hint in deletedDesertHints) {
            assertFalse("Desert hint must not reappear: $hint", texts.contains(hint))
        }
    }

    @Test
    fun twelfthBandanaUsesTheSameFramingAsEveryOther() {
        val ids = listOf(
            "pilot_medic", "pilot_rascal", "pilot_brutus", "pilot_frost",
            "pilot_dash", "pilot_ember", "pilot_fang", "pilot_kraken",
            "pilot_whiskers", "pilot_unit7", "pilot_havoc", "pilot_astro"
        )
        for (id in ids) persistence.addBandana(id)    // count = 12
        persistence.setPendingBandanaPilot("pilot_astro")

        chat.onDeathReturn(state, "pilot_astro")
        drainConversation()

        val texts = state.chatMessages.map { it.text }
        // The bandanaCount >= 12 branch collapsed: the twelfth ceremony must still produce a
        // Tobar/barman line, drawn from the same framing pool as every other ceremony — not
        // silence, and not the deleted special beat.
        assertTrue("Twelfth ceremony must still produce a Tobar line",
            texts.any { it in LoopDefinitions.tobarBandanaFraming })
        assertTrue("Pilot reply missing",
            texts.contains(LoopDefinitions.bandanaAwardReplies["pilot_astro"]))
        assertEquals("Twelfth ceremony must be report + framing + reply only, like every other", 3, texts.size)
        for (beat in deletedTwelfthBeat) {
            assertFalse("Twelfth-bandana special beat must not reappear: $beat", texts.contains(beat))
        }
    }

    @Test
    fun noPendingMeansNoCeremony() {
        chat.onDeathReturn(state, "pilot_dash")
        drainConversation()
        val texts = state.chatMessages.map { it.text }
        assertFalse(texts.any { it in LoopDefinitions.tobarBandanaFraming })
    }
}
