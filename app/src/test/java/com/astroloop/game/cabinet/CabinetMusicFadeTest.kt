package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test

/**
 * When the hangar bed gets out of the way — a design decision.
 *
 * The bed is not silenced for the whole cabinet session (a design decision's first form) and it is
 * not ducked either: it plays under the bezel and the menu, and once a RUN starts it fades to
 * nothing over a few seconds and stays gone until you leave the machine.
 *
 * A latch rather than a per-frame volume, because `SoundManager.stopAmbient(fadeOutMillis)`
 * already owns the ramp on its own thread. All this decides is the single frame the ramp is
 * asked for — and that decision is the part with edges in it.
 */
class CabinetMusicFadeTest {

    @Test fun theBedPlaysOnWhileYouAreOnTheMenu() {
        val f = CabinetMusicFade()
        assertFalse(f.update(CabinetScreen.MENU))
        assertFalse(f.update(CabinetScreen.SCORES))
        assertFalse(f.faded)
    }

    @Test fun startingARunFadesIt() {
        val f = CabinetMusicFade()
        f.update(CabinetScreen.MENU)
        assertTrue("the first PLAY frame asks for the fade", f.update(CabinetScreen.PLAY))
        assertTrue(f.faded)
    }

    @Test fun itIsAskedForOnceAndOnlyOnce() {
        // update() runs every frame. A latch that re-armed would restart a three-second
        // fade on a player already at zero, sixty times a second.
        val f = CabinetMusicFade()
        assertTrue(f.update(CabinetScreen.PLAY))
        repeat(200) { assertFalse(f.update(CabinetScreen.PLAY)) }
    }

    @Test fun pausingDoesNotBringItBackAndResumingDoesNotRefadeIt() {
        val f = CabinetMusicFade()
        assertTrue(f.update(CabinetScreen.PLAY))
        assertFalse(f.update(CabinetScreen.PAUSE))
        assertFalse(f.update(CabinetScreen.PLAY))
    }

    @Test fun dyingAndPlayingAgainDoesNotSwellTheMusicBackUp() {
        // AGAIN? is one tap. A bed that faded up on OVER and back down on the retry would
        // pump between every attempt, and the reckoning is a thing people retry.
        val f = CabinetMusicFade()
        f.update(CabinetScreen.PLAY)
        f.update(CabinetScreen.OVER)
        f.update(CabinetScreen.MENU)
        assertFalse("still faded — you have not left the machine", f.update(CabinetScreen.PLAY))
        assertTrue(f.faded)
    }

    @Test fun leavingTheCabinetRearmsItForTheNextSession() {
        val f = CabinetMusicFade()
        f.update(CabinetScreen.PLAY)
        assertTrue(f.faded)
        f.reset()
        assertFalse(f.faded)
        assertFalse(f.update(CabinetScreen.MENU))
        assertTrue("a fresh session fades again on its own first run", f.update(CabinetScreen.PLAY))
    }

    @Test fun aSessionThatNeverStartsARunNeverTouchedTheBed() {
        // Opening the machine, reading the high scores and walking away must leave the
        // hangar exactly as it was — which is also what tells the host not to restart it.
        val f = CabinetMusicFade()
        listOf(CabinetScreen.MENU, CabinetScreen.SCORES, CabinetScreen.MENU).forEach {
            assertFalse(f.update(it))
        }
        assertFalse("nothing was faded, so nothing needs restoring", f.faded)
    }
}
