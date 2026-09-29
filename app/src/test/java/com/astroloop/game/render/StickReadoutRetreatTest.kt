package com.astroloop.game.render

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.GamePhase
import com.astroloop.game.core.GameSurfaceView
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stick readout goes out when the emergency shield fires, and keeps fading while the ship
 * flies itself off.
 *
 * The owner, 2026-09-20: "when you die in astro loop mode, your touch controller center thing
 * stays visible while flying off, it should fade the instant you use your emergency shield."
 *
 * It was not fading slowly — it was not fading at all. `stickReadout.update` was called from
 * INSIDE `updatePlaying`'s movement guard, so the moment that guard closed (`retreatPhase < 2`
 * going false) nothing advanced the fade and the alpha froze wherever it stood. The readout then
 * sat at full brightness over the whole fly-off. The same freeze was reachable through the
 * guard's three other clauses — a stun, the fleet autopilot, the corruption rush — and is fixed
 * by the same move: tick every frame, and let the ACTIVE flag say whether it should be lit.
 *
 * Driven through real `update(deltaTime)` frames rather than by calling the renderer directly,
 * because "is update even called here?" is the entire defect and only the call site can answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StickReadoutRetreatTest {

    private lateinit var view: GameSurfaceView

    @Before
    fun setup() {
        PersistenceManager(ApplicationProvider.getApplicationContext()).resetAllProgress()
        val context = ApplicationProvider.getApplicationContext<Context>()
        view = GameSurfaceView(context, "ship_blue", "pilot_medic") { _, _ -> }
        view.surfaceChanged(view.holder, 0, 1280, 2844)
    }

    /**
     * A finger planted on the stick and LEFT there, which is the real scenario — the player is
     * still flying when the shield fires. The readout is lit by real frames through the real
     * touch controller, not by poking its alpha, so "a finger is down" stays true for everything
     * that follows and the tests below are about the retreat rather than about the finger.
     */
    private fun fingerDown() {
        view.state.phase = GamePhase.PLAYING
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 400f, 1800f, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
        frames(6)
        assertEquals("fixture: a finger on the stick must light the readout",
            1f, view.stickReadout.alpha, 0.0001f)
    }

    private fun frames(n: Int, dt: Float = 0.05f) = repeat(n) { view.update(dt) }

    @Test
    fun `the readout fades out once the emergency shield has fired`() {
        fingerDown()
        view.state.retreatPhase = 1 // startRetreat: the shield is up

        frames(10)

        assertEquals(
            "the readout must be gone while the ship flies off",
            0f, view.stickReadout.alpha, 0f
        )
    }

    /**
     * The freeze itself: at phase 2 the movement guard closes entirely, which is where the alpha
     * used to stop moving. RED before the fix with the alpha stuck at 1.
     */
    @Test
    fun `it keeps fading through the phase the movement guard shuts off`() {
        fingerDown()
        view.state.retreatPhase = 2 // auto-pilot flying south; touches are refused

        frames(2)
        val partway = view.stickReadout.alpha
        assertTrue("the fade must have started, was $partway", partway < 1f)

        frames(10)
        assertEquals("and finished", 0f, view.stickReadout.alpha, 0f)
    }

    /**
     * It must not come back for the fade-to-black. `emergencyShieldActive` is cleared at phase 3,
     * when the ship leaves the screen, so gating on that flag rather than on `retreatPhase` would
     * relight the readout for the last beat of the death.
     */
    @Test
    fun `it stays out through the final fade to black`() {
        fingerDown()
        view.state.retreatPhase = 3
        view.state.emergencyShieldActive = false // exactly what phase 3 leaves behind

        frames(12)

        assertEquals(0f, view.stickReadout.alpha, 0f)
    }

    /**
     * And the ordinary case is untouched: with no retreat under way, a finger on the stick keeps
     * the readout lit frame after frame. Without this the fix could have been "never draw it",
     * which would pass every assertion above.
     */
    @Test
    fun `a finger on the stick keeps the readout lit when nothing is retreating`() {
        fingerDown()
        assertEquals("precondition: not retreating", 0, view.state.retreatPhase)

        frames(20)

        assertEquals(
            "a held stick must stay lit through ordinary frames",
            1f, view.stickReadout.alpha, 0.0001f
        )
    }
}
