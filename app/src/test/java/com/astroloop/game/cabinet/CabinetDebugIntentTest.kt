package com.astroloop.game.cabinet

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CabinetDebugIntentTest {

    @After fun tearDown() = CabinetDebugIntent.clear()

    @Test fun nothingPendingByDefault() {
        assertNull(CabinetDebugIntent.consume())
    }

    @Test fun aRequestIsDeliveredOnce() {
        CabinetDebugIntent.request(CabinetDebugIntent.Action.OPEN)
        val first = CabinetDebugIntent.consume()
        assertNotNull(first)
        assertEquals(CabinetDebugIntent.Action.OPEN, first!!.action)
        // Consume-once: the hangar rebuilds more than once per session, and a request
        // that survived its own delivery would reopen the cabinet every time the player
        // walked back into the bar.
        assertNull(CabinetDebugIntent.consume())
    }

    @Test fun thePhaseTravelsWithTheAction() {
        // Action and phase must arrive together. Reading the phase from a separate
        // getter after consume() has already cleared the action is how they drift.
        CabinetDebugIntent.request(CabinetDebugIntent.Action.RECKONING, phase = 4)
        val r = CabinetDebugIntent.consume()!!
        assertEquals(CabinetDebugIntent.Action.RECKONING, r.action)
        assertEquals(4, r.phase)
    }

    @Test fun aSecondRequestReplacesThePendingOne() {
        CabinetDebugIntent.request(CabinetDebugIntent.Action.OPEN)
        CabinetDebugIntent.request(CabinetDebugIntent.Action.RECKONING, phase = 2)
        val r = CabinetDebugIntent.consume()!!
        assertEquals(CabinetDebugIntent.Action.RECKONING, r.action)
        assertEquals(2, r.phase)
        assertNull(CabinetDebugIntent.consume())
    }

    @Test fun clearDiscardsAPendingRequest() {
        CabinetDebugIntent.request(CabinetDebugIntent.Action.PLAY)
        CabinetDebugIntent.clear()
        assertNull(CabinetDebugIntent.consume())
    }

    @Test fun phaseZeroIsTheAuthoredOpening() {
        // A design decision: N = 0 plays the opening, N = 1..5 skip straight to that phase.
        // Without a 0 the entrance would be the one thing the debug page cannot reach.
        CabinetDebugIntent.request(CabinetDebugIntent.Action.RECKONING, phase = 0)
        assertEquals(0, CabinetDebugIntent.consume()!!.phase)
    }
}
