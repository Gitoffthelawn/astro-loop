package com.astroloop.game.hangar

import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.StoryStage
import com.astroloop.game.data.CrystalFightLines
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bar's reaction to a won reckoning reaches the player without a flight in between.
 *
 * `reckoning_just_won` is written at the cabinet and used to be drained only by
 * [ChatSystem.onDeathReturn] — the return from a RUN. So beating the crystal and walking
 * back to the bar said nothing at all, and Tobar's "Something out there's gone quiet."
 * waited behind an unrelated death. [ChatSystem.onReckoningWon] is the second drain, called
 * by the host on the first bar-page frame with nothing else being said.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatSystemReckoningWinTest {

    private lateinit var persistence: PersistenceManager
    private lateinit var state: HangarState
    private lateinit var chatSystem: ChatSystem

    @Before
    fun setup() {
        persistence = PersistenceManager(ApplicationProvider.getApplicationContext())
        persistence.resetAllProgress()
        persistence.setStoryStageCode(StoryStage.ASTRO_LOOP.code)
        state = HangarState(persistence)
        chatSystem = ChatSystem()
    }

    @Test
    fun `the win chatter reaches the bar with no flight in between`() {
        chatSystem.onReckoningWon(state)
        drainConversation()

        val texts = state.chatMessages.map { it.text }
        for ((_, line) in CrystalFightLines.barChatter) {
            assertTrue("the bar never said: \"$line\" — got $texts", texts.contains(line))
        }
    }

    @Test
    fun `Tobar opens it and the pilot is not in it`() {
        // A design decision: the crew got better and nobody in the room can be told why, so the
        // pilot says nothing about the fight. If a speaker other than TOBAR or MEDIC ever
        // appears here, the script has grown a witness it is not supposed to have.
        chatSystem.onReckoningWon(state)
        drainConversation()

        assertEquals("TOBAR", state.chatMessages.first().speaker)
        assertTrue(
            "only the bar's own two voices may carry this",
            state.chatMessages.all { it.speaker == "TOBAR" || it.speaker == "MEDIC" }
        )
    }

    @Test
    fun `the death return still drains the flag, so a player who flies first keeps the one-shot`() {
        // Both drains are deliberate. A player who wins and then launches without ever
        // crossing the bar page must not lose the line; whichever gets there first clears
        // the flag, so it can never play twice.
        persistence.setReckoningJustWon(true)
        chatSystem.onDeathReturn(state, "pilot_astro")
        drainConversation()

        assertTrue(
            "the flight return must still deliver it",
            state.chatMessages.any { it.text == CrystalFightLines.barChatter[0].second }
        )
        assertFalse("and must consume the flag", persistence.isReckoningJustWon())
    }

    private fun drainConversation() {
        var guard = 0
        while (state.activeConversation != null && guard++ < 40) {
            chatSystem.update(ChatSystem.LINE_PAUSE + 0.1f, state)
        }
    }
}
