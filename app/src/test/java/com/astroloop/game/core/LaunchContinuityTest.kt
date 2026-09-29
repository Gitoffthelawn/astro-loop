package com.astroloop.game.core

import com.astroloop.game.entity.Ship
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The ship launches from the middle of the screen, and combat opens with it in the middle of the
 * screen. That match IS the transition — the hyperspace phase even lerps its background to the
 * combat background, 0xFF000011 — and until now nothing asserted it.
 *
 * The hangar side says so in four places: `beginLaunch` snaps `shipDragY` to `screenHeight / 2`,
 * `drawLaunchSequence` opens with `centerX = screenWidth / 2` and `shipY = shipCenterY`, and its
 * liftoff and hyperspace phases are each commented "ship stays at center". The combat side is
 * exercised for real below, through the actual Camera.
 *
 * What this guards is not someone editing those on purpose. It is someone moving the launch point
 * onto `layout.safe` or `layout.content` for cutout safety — the correct reflex everywhere else in
 * this codebase, and wrong here: rotated, a side cutout puts those centres 71 design units away
 * from where combat will place the ship, and the transition would visibly jump.
 */
class LaunchContinuityTest {

    /** Where combat actually puts the ship, driven through the real camera. */
    private fun combatShipScreenPoint(m: ScreenMetrics): Pair<Float, Float> {
        val camera = Camera()
        camera.initialize(m.width, m.height)
        // GameSurfaceView.initializeGame: ship.position.set(0f, 0f), then camera.update(ship).
        val ship = Ship()
        ship.position.set(0f, 0f)
        camera.update(ship)
        return camera.toScreenX(ship.position.x) to camera.toScreenY(ship.position.y)
    }

    /** Where the hangar launches from: the FULL-screen centre, both axes. */
    private fun launchPoint(m: ScreenMetrics): Pair<Float, Float> = m.width / 2f to m.height / 2f

    private val devices = listOf(
        "Pixel 9 Pro portrait" to (1080f to 2424f),
        "Pixel 9 Pro landscape" to (2424f to 1080f),
        "1080p landscape / TV" to (1920f to 1080f),
        "tablet portrait" to (1600f to 2560f),
        "tablet landscape" to (2560f to 1600f)
    )

    @Test
    fun `combat opens with the ship exactly where the hangar launched it`() {
        for ((name, size) in devices) {
            val m = DesignSpace.metricsFor(size.first, size.second)
            val (combatX, combatY) = combatShipScreenPoint(m)
            val (launchX, launchY) = launchPoint(m)

            assertEquals("x mismatch on $name", combatX, launchX, 0f)
            assertEquals("y mismatch on $name", combatY, launchY, 0f)
        }
    }

    @Test
    fun `a side cutout moves safe and content away from the launch point`() {
        val m = DesignSpace.metricsFor(1920f, 1080f, insetLeftPx = 128f)
        val (launchX, _) = launchPoint(m)
        val (combatX, _) = combatShipScreenPoint(m)

        assertEquals("combat is unaffected by insets", combatX, launchX, 0f)
        assertTrue(
            "safe centre must diverge, or this guards nothing: ${m.layout.safe.centerX} vs $launchX",
            abs(m.layout.safe.centerX - launchX) > 50f
        )
        assertTrue(
            "content centre must diverge too: ${m.layout.content.centerX} vs $launchX",
            abs(m.layout.content.centerX - launchX) > 50f
        )
    }

    @Test
    fun `insets on every edge still leave the launch point on full`() {
        val m = DesignSpace.metricsFor(
            1920f, 1080f,
            insetLeftPx = 128f, insetTopPx = 40f, insetRightPx = 12f, insetBottomPx = 60f
        )
        val (combatX, combatY) = combatShipScreenPoint(m)

        assertEquals(combatX, launchPoint(m).first, 0f)
        assertEquals(combatY, launchPoint(m).second, 0f)
    }
}
