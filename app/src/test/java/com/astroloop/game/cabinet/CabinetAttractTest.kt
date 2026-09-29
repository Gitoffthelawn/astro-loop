package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test

/**
 * The attract loop as its own unit.
 *
 * CabinetShellTest already drives this through the shell, which is how the cabinet's own
 * menu uses it. These cover it as the shared class, because the second caller — the store
 * page's bezel — has no test of its own and used to be a hand-rolled copy that had
 * neither of the properties below.
 */
class CabinetAttractTest {

    private fun attract() = CabinetAttract(CabinetMetrics(1080f, 2400f))

    @Test fun theDemoFliesItself() {
        // The bezel's old version passed a hardcoded (0f, 0f, false) to its sim, so its
        // ship never steered: it sat dead centre firing straight up until a rock found it.
        val a = attract()
        val start = a.sim.ship.x to a.sim.ship.y
        repeat(60) { a.update(1f / 60f) }
        assertTrue(
            "the demo ship should be flying",
            a.sim.ship.x != start.first || a.sim.ship.y != start.second
        )
    }

    @Test fun itHoldsOnItsWreckBeforeStartingOver() {
        // The bezel's old version was `if (sim.over) sim.start()`, which drew the wreck
        // for exactly one frame and then wiped it — an instant disappearance.
        val a = attract()
        val rock = a.sim.rocks.first()
        a.sim.ship.x = rock.x; a.sim.ship.y = rock.y
        a.update(1f / 120f)
        assertTrue("the demo should have died", a.sim.over)
        assertTrue("its wreck should be on screen", a.sim.debris.isNotEmpty())

        a.update(1f / 60f)
        assertTrue("and must still be there the next frame", a.sim.debris.isNotEmpty())
        assertTrue("with the demo still on its game over", a.sim.over)

        // The hold expires on this step, but the debris gate is read before this call's
        // own aging runs, so the restart lands on the following step — see
        // CabinetAttract.ATTRACT_RESTART_HOLD.
        a.update(CabinetAttract.ATTRACT_RESTART_HOLD)
        a.update(1f / 60f)
        assertFalse("the demo must never sit on a game over forever", a.sim.over)
    }

    @Test fun theWreckMustNotOutlastTheRestartBeat() {
        // Guards a coupling that is documented at CabinetDebris.LIFETIME and was, until
        // now, documented and nothing else. The restart gate is `hold expired AND debris
        // empty`, so if a fragment outlives the hold it becomes the thing pacing the
        // attract loop — lengthening a beat already signed off on hardware ("holds on its
        // own wreck before restarting") without failing anything.
        //
        // A device pass asked for a slower fade and got one (0.6s -> 0.8s). This is the
        // headroom that request has left.
        assertTrue(
            "debris lifetime ${CabinetDebris.LIFETIME}s must stay under the " +
                "${CabinetAttract.ATTRACT_RESTART_HOLD}s restart beat",
            CabinetDebris.LIFETIME < CabinetAttract.ATTRACT_RESTART_HOLD
        )
    }
}
